package com.lanchuan.lims.test;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.CurrentActor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TestImportService {
    static final String TEMPLATE_VERSION = "LIMS_TEST_IMPORT_V1";
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final TestRecordService records;
    private final AuditService audit;

    public TestImportService(JdbcClient jdbc, CurrentActor actor, TestRecordService records, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.records = records;
        this.audit = audit;
    }

    public record ImportFingerprint(String filename, String sha256) {}
    public record ImportResult(int importedCount, List<UUID> recordIds, String templateVersion, String sha256) {}
    private record Pending(int rowNumber, UUID taskId, UUID equipmentId, Map<String, Object> values) {}
    private record TaskRow(UUID id, String state) {}

    @Transactional
    public ImportResult importWorkbook(byte[] content, String filename) {
        validateEnvelope(content, filename);
        String sha = sha256(content);
        List<String> errors = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        Set<UUID> seenTasks = new LinkedHashSet<>();
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_BYTES);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() != 1) errors.add("工作簿必须且只能包含一个数据工作表");
            Sheet sheet = workbook.getSheetAt(0);
            if (!TEMPLATE_VERSION.equals(text(sheet.getRow(0), 0))) errors.add("模板版本不匹配，请重新下载受控导入模板");
            Row header = sheet.getRow(1);
            List<String> headers = headers(header, errors);
            int taskColumn = headers.indexOf("taskNumber");
            int equipmentColumn = headers.indexOf("equipmentCode");
            if (taskColumn < 0) errors.add("缺少必需列：taskNumber");
            if (equipmentColumn < 0) errors.add("缺少必需列：equipmentCode");
            if (headers.size() <= 2) errors.add("模板至少需要一个检测数据列");
            int nonEmptyRows = 0;
            for (int index = 2; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, headers.size())) continue;
                nonEmptyRows++;
                int rowNumber = index + 1;
                if (nonEmptyRows > MAX_ROWS) {
                    errors.add("数据行超过上限 " + MAX_ROWS);
                    break;
                }
                if (taskColumn < 0 || equipmentColumn < 0) continue;
                try {
                    String taskNumber = required(row, taskColumn, rowNumber, "taskNumber");
                    String equipmentCode = required(row, equipmentColumn, rowNumber, "equipmentCode");
                    TaskRow task = jdbc.sql("""
                            SELECT t.id, t.state FROM test_task t JOIN commission c ON c.id=t.commission_id
                            WHERE t.number=:number AND c.organization_id=:organization
                            """).param("number", taskNumber).param("organization", actor.require().organizationId())
                            .query((rs, ignored) -> new TaskRow(rs.getObject("id", UUID.class), rs.getString("state")))
                            .optional().orElseThrow(() -> new IllegalArgumentException("任务编号不存在"));
                    if (!List.of("IN_PROGRESS", "RETURNED").contains(task.state())) {
                        throw new IllegalArgumentException("任务状态不允许录入检测数据");
                    }
                    if (!seenTasks.add(task.id())) throw new IllegalArgumentException("同一任务在文件中重复出现");
                    long existing = jdbc.sql("SELECT count(*) FROM test_record WHERE task_id=:task AND state<>'SUPERSEDED'")
                            .param("task", task.id()).query(Long.class).single();
                    if (existing > 0) throw new IllegalArgumentException("任务已有活动试验记录");
                    UUID equipmentId = jdbc.sql("""
                            SELECT id FROM equipment WHERE code=:code AND organization_id=:organization
                            """).param("code", equipmentCode).param("organization", actor.require().organizationId())
                            .query(UUID.class).optional().orElseThrow(() -> new IllegalArgumentException("设备编号不存在"));
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int column = 0; column < headers.size(); column++) {
                        String key = headers.get(column);
                        if (key.equals("taskNumber") || key.equals("equipmentCode")) continue;
                        String value = cellText(row.getCell(column));
                        if (!value.isBlank()) values.put(key, value);
                    }
                    Map<String, Object> validated = records.validateAndCalculate(task.id(), values);
                    pending.add(new Pending(rowNumber, task.id(), equipmentId, validated));
                } catch (ApiException | IllegalArgumentException exception) {
                    errors.add("第 " + rowNumber + " 行：" + exception.getMessage());
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ApiException api) throw api;
            errors.add("工作簿无法安全解析：" + safeMessage(exception));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_IMPORT_VALIDATION_FAILED",
                    "受控试验数据导入校验失败，未写入任何记录", null,
                    errors.stream().limit(200).toList(), Map.of());
        }
        if (pending.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_IMPORT_EMPTY", "导入文件没有有效数据行");
        }
        List<UUID> ids = new ArrayList<>(pending.size());
        for (Pending row : pending) {
            TestRecordService.View created = records.create(new TestRecordService.Create(row.taskId(), row.equipmentId(), row.values()));
            ids.add(created.id());
        }
        ImportResult result = new ImportResult(ids.size(), List.copyOf(ids), TEMPLATE_VERSION, sha);
        audit.record("TEST_RECORDS_IMPORTED", "TEST_IMPORT", sha, null,
                Map.of("count", result.importedCount(), "recordIds", result.recordIds(), "templateVersion", TEMPLATE_VERSION),
                null, filename);
        return result;
    }

    public byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("试验数据");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            sheet.createRow(0).createCell(0).setCellValue(TEMPLATE_VERSION);
            Row header = sheet.createRow(1);
            List<String> headers = List.of("taskNumber", "equipmentCode", "load", "area");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
                sheet.setColumnWidth(index, 24 * 256);
            }
            sheet.createFreezePane(0, 2);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成受控导入模板", exception);
        }
    }

    public ImportFingerprint fingerprint(byte[] content, String filename) {
        return new ImportFingerprint(filename, sha256(content));
    }

    private void validateEnvelope(byte[] content, String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_IMPORT_FORMAT_INVALID", "仅允许受控 .xlsx 导入文件");
        }
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_IMPORT_SIZE_INVALID", "导入文件须大于0且不超过10MB");
        }
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_IMPORT_HEADER_INVALID", "导入文件内容不是有效的 .xlsx");
        }
    }

    private List<String> headers(Row row, List<String> errors) {
        if (row == null) return List.of();
        List<String> values = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            String value = cellText(row.getCell(index)).trim();
            if (value.isBlank()) errors.add("第2行第" + (index + 1) + "列的字段名为空");
            if (!unique.add(value)) errors.add("第2行字段名重复：" + value);
            values.add(value);
        }
        return values;
    }

    private boolean blank(Row row, int columns) {
        for (int index = 0; index < columns; index++) if (!cellText(row.getCell(index)).isBlank()) return false;
        return true;
    }

    private String required(Row row, int column, int rowNumber, String name) {
        String value = cellText(row.getCell(column)).trim();
        if (value.isBlank()) throw new IllegalArgumentException("缺少 " + name + "（第 " + rowNumber + " 行）");
        return value;
    }

    private String text(Row row, int column) { return row == null ? "" : cellText(row.getCell(column)); }

    private String cellText(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("导入文件不允许公式单元格");
        if (cell.getCellType() == CellType.NUMERIC) return NumberToTextConverter.toText(cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        if (cell.getCellType() == CellType.ERROR) throw new IllegalArgumentException("导入文件包含错误单元格");
        return cell.getStringCellValue().trim();
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
