package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.identity.CurrentActor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StandardWorkbookService {
    static final String TEMPLATE_VERSION = "LIMS_STANDARD_IMPORT_V1";
    private static final int MAX_ROWS = 5_000;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final List<String> REQUIRED_HEADERS = List.of(
            "标准编号", "标准名称", "标准归属", "依据类型", "标准类型",
            "发布机构", "引用依据", "实施日期", "废止日期", "标准状态");
    private static final Map<String, String> OWNERSHIP = aliases(
            "系统", "SYSTEM", "机构", "ORGANIZATION", "SYSTEM", "SYSTEM", "ORGANIZATION", "ORGANIZATION");
    private static final Map<String, String> BASIS = aliases(
            "检测依据", "DETECTION", "判定依据", "JUDGEMENT", "检测/判定依据", "BOTH",
            "DETECTION", "DETECTION", "JUDGEMENT", "JUDGEMENT", "BOTH", "BOTH");
    private static final Map<String, String> TYPE = aliases(
            "国标", "NATIONAL", "行标", "INDUSTRY", "地标", "LOCAL", "团标", "ASSOCIATION",
            "企标", "ENTERPRISE", "其他", "OTHER", "NATIONAL", "NATIONAL", "INDUSTRY", "INDUSTRY",
            "LOCAL", "LOCAL", "ASSOCIATION", "ASSOCIATION", "ENTERPRISE", "ENTERPRISE", "OTHER", "OTHER");
    private static final Map<String, String> STATUS = aliases(
            "现行", "CURRENT", "即将实施", "UPCOMING", "作废", "REPEALED",
            "CURRENT", "CURRENT", "UPCOMING", "UPCOMING", "REPEALED", "REPEALED");
    private static final Map<String, String> OWNERSHIP_LABEL = Map.of("SYSTEM", "系统", "ORGANIZATION", "机构");
    private static final Map<String, String> BASIS_LABEL = Map.of("DETECTION", "检测依据", "JUDGEMENT", "判定依据", "BOTH", "检测/判定依据");
    private static final Map<String, String> TYPE_LABEL = Map.of("NATIONAL", "国标", "INDUSTRY", "行标", "LOCAL", "地标",
            "ASSOCIATION", "团标", "ENTERPRISE", "企标", "OTHER", "其他");
    private static final Map<String, String> STATUS_LABEL = Map.of("CURRENT", "现行", "UPCOMING", "即将实施", "REPEALED", "作废");

    private final StandardService standards;
    private final CurrentActor actor;
    private final AuditService audit;

    public StandardWorkbookService(StandardService standards, CurrentActor actor, AuditService audit) {
        this.standards = standards;
        this.actor = actor;
        this.audit = audit;
    }

    public record ImportFingerprint(String filename, String sha256) {}
    public record ImportResult(int importedCount, int createdCount, int revisedCount, int updatedDraftCount,
                               String templateVersion, String sha256) {}
    public record ExportFile(byte[] content, String filename, int rowCount) {}
    private record Pending(int rowNumber, String code, String name, String ownershipType, String basisType,
                           String standardType, String issuingInstitution, String citedBasis,
                           LocalDate effectiveDate, LocalDate repealDate, String standardStatus) {}

    public byte[] template() {
        return workbook(List.of(), false).content();
    }

    public ExportFile export(String query, String basisType, String standardType, String standardStatus,
                             String state, LocalDate effectiveFrom, LocalDate effectiveTo) {
        List<StandardService.Summary> rows = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<StandardService.Summary> page = standards.list(query, basisType, standardType, standardStatus,
                    state, effectiveFrom, effectiveTo, cursor, 100);
            rows.addAll(page.items());
            if (rows.size() > MAX_ROWS) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STANDARD_EXPORT_TOO_LARGE",
                        "当前筛选结果超过5000条，请缩小范围后导出");
            }
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        ExportFile exported = workbook(rows, true);
        audit.record("DETECTION_STANDARDS_EXPORTED", "DETECTION_STANDARD", null, null,
                Map.of("rowCount", rows.size(), "watermarked", true), null, query == null ? "" : query);
        return exported;
    }

    @Transactional
    public ImportResult importWorkbook(byte[] content, String filename) {
        validateEnvelope(content, filename);
        String sha = sha256(content);
        List<String> errors = new ArrayList<>();
        List<Pending> pending = parse(content, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDARD_IMPORT_VALIDATION_FAILED",
                    "受控标准导入校验失败，未写入任何记录", null,
                    errors.stream().limit(200).toList(), Map.of());
        }
        if (pending.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDARD_IMPORT_EMPTY", "导入文件没有有效数据行");
        }

        int created = 0;
        int revised = 0;
        int updated = 0;
        for (Pending row : pending) {
            StandardService.View latest = standards.latestByCode(row.code()).orElse(null);
            StandardService.Write write = new StandardService.Write(row.code(), row.name(), row.ownershipType(),
                    row.basisType(), row.standardType(), row.issuingInstitution(), row.citedBasis(),
                    row.effectiveDate(), row.repealDate(), row.standardStatus(),
                    latest == null ? null : latest.documentFileId(),
                    latest == null ? List.of() : latest.parameterIds(),
                    latest == null ? 0 : latest.version());
            if (latest == null) {
                standards.create(write);
                created++;
            } else if (latest.state().equals("DRAFT")) {
                standards.update(latest.id(), write);
                updated++;
            } else {
                StandardService.View draft = standards.revise(latest.id());
                standards.update(draft.id(), new StandardService.Write(row.code(), row.name(), row.ownershipType(),
                        row.basisType(), row.standardType(), row.issuingInstitution(), row.citedBasis(),
                        row.effectiveDate(), row.repealDate(), row.standardStatus(), draft.documentFileId(),
                        draft.parameterIds(), draft.version()));
                revised++;
            }
        }
        ImportResult result = new ImportResult(pending.size(), created, revised, updated, TEMPLATE_VERSION, sha);
        audit.record("DETECTION_STANDARDS_IMPORTED", "DETECTION_STANDARD_IMPORT", sha, null, result,
                null, filename);
        return result;
    }

    public ImportFingerprint fingerprint(byte[] content, String filename) {
        return new ImportFingerprint(filename == null ? "" : filename, sha256(content));
    }

    private ExportFile workbook(List<StandardService.Summary> data, boolean export) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("标准库");
            Row marker = sheet.createRow(0);
            marker.createCell(0).setCellValue(TEMPLATE_VERSION);
            marker.createCell(1).setCellValue(export
                    ? "内部受控导出 · " + actor.require().displayName() + " · "
                    + OffsetDateTime.now().atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "受控模板：导入只生成或更新草稿；整文件校验，任一行错误则全部拒绝");
            CellStyle markerStyle = workbook.createCellStyle();
            Font markerFont = workbook.createFont();
            markerFont.setBold(true);
            markerFont.setColor((short) 10);
            markerStyle.setFont(markerFont);
            markerStyle.setAlignment(HorizontalAlignment.LEFT);
            marker.getCell(0).setCellStyle(markerStyle);
            marker.getCell(1).setCellStyle(markerStyle);

            List<String> headers = new ArrayList<>(REQUIRED_HEADERS);
            if (export) headers.addAll(List.of("版本状态", "修订号", "来源校验提示"));
            Row header = sheet.createRow(1);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(headers.get(column));
                cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < data.size(); index++) {
                StandardService.Summary value = data.get(index);
                Row row = sheet.createRow(index + 2);
                List<String> values = List.of(value.code(), value.name(), OWNERSHIP_LABEL.get(value.ownershipType()),
                        BASIS_LABEL.get(value.basisType()), TYPE_LABEL.get(value.standardType()),
                        nullable(value.issuingInstitution()), "", value.effectiveDate().toString(),
                        value.repealDate() == null ? "" : value.repealDate().toString(), STATUS_LABEL.get(value.standardStatus()),
                        value.state(), String.valueOf(value.revisionNumber()), nullable(value.sourceValidationWarning()));
                for (int column = 0; column < values.size(); column++) row.createCell(column).setCellValue(values.get(column));
            }
            int[] widths = {22, 48, 12, 18, 12, 28, 36, 14, 14, 14, 14, 10, 36};
            for (int column = 0; column < headers.size(); column++) sheet.setColumnWidth(column, widths[column] * 256);
            sheet.createFreezePane(0, 2);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(1, Math.max(1, data.size() + 1), 0, headers.size() - 1));
            workbook.write(output);
            String filename = export ? "标准库-受控导出.xlsx" : "标准库-受控导入模板-V1.xlsx";
            return new ExportFile(output.toByteArray(), filename, data.size());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STANDARD_WORKBOOK_FAILED", "标准工作簿生成失败");
        }
    }

    private List<Pending> parse(byte[] content, List<String> errors) {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_BYTES);
        List<Pending> pending = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("标准库");
            if (sheet == null) {
                errors.add("缺少名为“标准库”的数据工作表");
                return pending;
            }
            if (!TEMPLATE_VERSION.equals(cellText(sheet.getRow(0), 0))) {
                errors.add("模板版本不匹配，请重新下载受控导入模板");
            }
            Map<String, Integer> columns = headerColumns(sheet.getRow(1), errors);
            for (String required : REQUIRED_HEADERS) if (!columns.containsKey(required)) errors.add("缺少必需列：" + required);
            int nonEmpty = 0;
            for (int index = 2; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, columns.values())) continue;
                if (++nonEmpty > MAX_ROWS) {
                    errors.add("数据行超过上限 " + MAX_ROWS);
                    break;
                }
                int rowNumber = index + 1;
                if (columns.size() < REQUIRED_HEADERS.size()) continue;
                try {
                    String code = required(row, columns, "标准编号", rowNumber, 240);
                    String normalizedCode = code.toLowerCase(Locale.ROOT);
                    if (!codes.add(normalizedCode)) throw new IllegalArgumentException("标准编号在文件中重复：" + code);
                    String name = required(row, columns, "标准名称", rowNumber, 500);
                    String ownership = enumValue(row, columns, "标准归属", rowNumber, OWNERSHIP);
                    String basis = enumValue(row, columns, "依据类型", rowNumber, BASIS);
                    String type = enumValue(row, columns, "标准类型", rowNumber, TYPE);
                    String issuer = optional(row, columns, "发布机构", 240);
                    String cited = optional(row, columns, "引用依据", 1000);
                    LocalDate effective = date(row, columns, "实施日期", rowNumber, true);
                    LocalDate repeal = date(row, columns, "废止日期", rowNumber, false);
                    if (repeal != null && repeal.isBefore(effective)) throw new IllegalArgumentException("废止日期不能早于实施日期");
                    String status = enumValue(row, columns, "标准状态", rowNumber, STATUS);
                    pending.add(new Pending(rowNumber, code, name, ownership, basis, type, issuer, cited,
                            effective, repeal, status));
                } catch (IllegalArgumentException exception) {
                    errors.add("第 " + rowNumber + " 行：" + exception.getMessage());
                }
            }
        } catch (IOException | RuntimeException exception) {
            errors.add("工作簿无法安全解析：" + safeMessage(exception));
        }
        return pending;
    }

    private Map<String, Integer> headerColumns(Row row, List<String> errors) {
        if (row == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            String value = cellText(row.getCell(column)).trim();
            if (value.isBlank()) continue;
            if (result.putIfAbsent(value, column) != null) errors.add("字段名重复：" + value);
        }
        return result;
    }

    private boolean blank(Row row, Iterable<Integer> columns) {
        for (Integer column : columns) if (!cellText(row.getCell(column)).isBlank()) return false;
        return true;
    }

    private String required(Row row, Map<String, Integer> columns, String header, int rowNumber, int max) {
        String value = optional(row, columns, header, max);
        if (value == null) throw new IllegalArgumentException(header + "不能为空");
        return value;
    }

    private String optional(Row row, Map<String, Integer> columns, String header, int max) {
        Integer column = columns.get(header);
        String value = column == null ? "" : cellText(row.getCell(column)).trim();
        if (value.isBlank()) return null;
        if (value.length() > max) throw new IllegalArgumentException(header + "超过最大长度 " + max);
        return value;
    }

    private String enumValue(Row row, Map<String, Integer> columns, String header, int rowNumber, Map<String, String> aliases) {
        String raw = required(row, columns, header, rowNumber, 40);
        String value = aliases.get(raw.toUpperCase(Locale.ROOT));
        if (value == null) value = aliases.get(raw);
        if (value == null) throw new IllegalArgumentException(header + "无效：" + raw);
        return value;
    }

    private LocalDate date(Row row, Map<String, Integer> columns, String header, int rowNumber, boolean required) {
        Integer column = columns.get(header);
        if (column == null) {
            if (required) throw new IllegalArgumentException(header + "不能为空");
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            if (required) throw new IllegalArgumentException(header + "不能为空");
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = cellText(cell).trim();
        if (raw.isBlank()) {
            if (required) throw new IllegalArgumentException(header + "不能为空");
            return null;
        }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("yyyy.MM.dd"))) {
            try { return LocalDate.parse(raw, formatter); }
            catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException(header + "格式无效，应为 YYYY-MM-DD");
    }

    private String cellText(Row row, int column) { return row == null ? "" : cellText(row.getCell(column)); }

    private String cellText(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("导入文件不允许公式单元格");
        if (cell.getCellType() == CellType.NUMERIC) return NumberToTextConverter.toText(cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        if (cell.getCellType() == CellType.ERROR) throw new IllegalArgumentException("导入文件包含错误单元格");
        return cell.getStringCellValue().trim();
    }

    private void validateEnvelope(byte[] content, String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDARD_IMPORT_FORMAT_INVALID", "仅允许受控 .xlsx 导入文件");
        }
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDARD_IMPORT_SIZE_INVALID", "导入文件须大于0且不超过10MB");
        }
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDARD_IMPORT_HEADER_INVALID", "导入文件内容不是有效的 .xlsx");
        }
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

    private String nullable(String value) { return value == null ? "" : value; }

    private static Map<String, String> aliases(String... entries) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put(entries[index].toUpperCase(Locale.ROOT), entries[index + 1]);
        return Map.copyOf(result);
    }
}
