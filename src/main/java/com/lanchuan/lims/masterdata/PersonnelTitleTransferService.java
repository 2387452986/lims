package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class PersonnelTitleTransferService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_ROWS = 1_000;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final List<String> HEADERS = List.of("工号", "发证时间", "职称证书编号", "专业名称",
            "发证单位", "职称等级", "专业类型", "备注");
    private static final Map<String, String> LEVELS = Map.of(
            "助理工程师", "ASSISTANT_ENGINEER", "工程师", "ENGINEER",
            "副高级工程师", "SENIOR_ENGINEER", "正高级工程师", "PROFESSORATE_SENIOR_ENGINEER");
    private static final Map<String, String> CATEGORIES = Map.of(
            "水利", "WATER_CONSERVANCY", "检测", "TESTING", "交通", "TRANSPORTATION",
            "市政", "MUNICIPAL", "港航", "PORT_WATERWAY", "土木", "CIVIL",
            "岩土", "GEOTECHNICAL", "结构", "STRUCTURAL", "其他", "OTHER");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;

    public PersonnelTitleTransferService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                         StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_BYTES);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    public record Fingerprint(String sha256, long sizeBytes) {}
    public record ImportResult(int importedCount, String sha256, List<UUID> titleIds) {}
    public record ImportRow(int rowNumber, UUID personId, LocalDate issuedOn, String certificateNumber,
                            String specialtyName, String issuingAuthority, String titleLevel,
                            String specialtyCategory, String remark) {}

    public byte[] readUpload(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw invalid("TITLE_IMPORT_EMPTY", "请选择人员职称导入文件");
        String name = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") || upload.getSize() > MAX_BYTES) {
            throw invalid("TITLE_IMPORT_FILE_INVALID", "人员职称导入只接受不超过20MB的受控 .xlsx 文件");
        }
        try {
            byte[] content = upload.getBytes();
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
                throw invalid("TITLE_IMPORT_HEADER_INVALID", "导入文件内容与 .xlsx 格式不匹配");
            }
            return content;
        } catch (IOException exception) {
            throw invalid("TITLE_IMPORT_READ_FAILED", "无法读取人员职称导入文件");
        }
    }

    public Fingerprint fingerprint(byte[] content) {
        try {
            return new Fingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                    content.length);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Transactional
    public StoredFileService.View createTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("人员职称导入");
            var header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
                sheet.setColumnWidth(index, (index == 7 ? 42 : 22) * 256);
            }
            sheet.createFreezePane(0, 1);
            var instruction = workbook.createSheet("填写说明");
            List<String[]> notes = List.of(
                    new String[]{"规则", "要求"},
                    new String[]{"日期", "统一填写 YYYY-MM-DD，也可使用 Excel 日期单元格"},
                    new String[]{"职称等级", String.join("、", LEVELS.keySet())},
                    new String[]{"专业类型", String.join("、", CATEGORIES.keySet())},
                    new String[]{"附件", "批量导入不导入照片，可在未提交记录中受控上传"},
                    new String[]{"事务", "先校验全部行；任一行失败则整份文件零写入"});
            for (int index = 0; index < notes.size(); index++) {
                var row = instruction.createRow(index);
                row.createCell(0).setCellValue(notes.get(index)[0]);
                row.createCell(1).setCellValue(notes.get(index)[1]);
            }
            instruction.setColumnWidth(0, 20 * 256);
            instruction.setColumnWidth(1, 100 * 256);
            workbook.write(output);
            StoredFileService.View stored = files.storeGenerated(output.toByteArray(), "人员职称受控导入模板.xlsx",
                    XLSX, "PERSON_TITLE_IMPORT_TEMPLATE", actor.require().id());
            audit.record("PERSON_TITLE_IMPORT_TEMPLATE_CREATED", "STORED_FILE", stored.id(), null,
                    Map.of("sha256", stored.sha256()), null, null);
            return stored;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TITLE_IMPORT_TEMPLATE_FAILED",
                    "人员职称导入模板生成失败");
        }
    }

    @Transactional
    public ImportResult importWorkbook(byte[] content, Function<ImportRow, UUID> create) {
        Fingerprint fingerprint = fingerprint(content);
        List<ImportRow> rows = parse(content);
        List<UUID> ids = new ArrayList<>();
        for (ImportRow row : rows) {
            try {
                ids.add(create.apply(row));
            } catch (ApiException exception) {
                throw ApiException.blocked("TITLE_IMPORT_ROW_FAILED", "人员职称导入失败，整批已回滚", 0,
                        List.of("第" + row.rowNumber() + "行：" + exception.getMessage()));
            }
        }
        ImportResult result = new ImportResult(ids.size(), fingerprint.sha256(), List.copyOf(ids));
        audit.record("PERSON_TITLES_IMPORTED", "PERSON_TITLE_IMPORT", fingerprint.sha256(), null,
                Map.of("importedCount", ids.size(), "sha256", fingerprint.sha256()), null,
                "受控 XLSX 完整校验后整批事务导入");
        return result;
    }

    List<ImportRow> parse(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() < 1) throw invalid("TITLE_IMPORT_SHEET_MISSING", "导入文件缺少人员职称工作表");
            var sheet = workbook.getSheetAt(0);
            validateHeaders(sheet.getRow(0));
            if (sheet.getLastRowNum() > MAX_ROWS) throw invalid("TITLE_IMPORT_ROW_LIMIT", "单次人员职称导入最多1000行");
            Map<String, UUID> people = people();
            Set<String> existing = new HashSet<>(jdbc.sql("""
                    SELECT upper(certificate_number) FROM person_title_record WHERE organization_id=:organization
                    """).param("organization", actor.require().organizationId()).query(String.class).list());
            Set<String> fileNumbers = new HashSet<>();
            List<ImportRow> rows = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row)) continue;
                rejectFormulas(row, index + 1);
                int rowNumber = index + 1;
                String employeeNumber = text(row, 0);
                LocalDate issuedOn = date(row.getCell(1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                String certificateNumber = text(row, 2);
                String specialtyName = text(row, 3);
                String issuingAuthority = text(row, 4);
                String level = controlled(text(row, 5), LEVELS);
                String category = controlled(text(row, 6), CATEGORIES);
                String remark = text(row, 7);
                UUID personId = people.get(employeeNumber.toUpperCase(Locale.ROOT));
                if (employeeNumber.isBlank() || personId == null) errors.add("第" + rowNumber + "行：工号不存在、已停用或超出当前数据范围");
                if (issuedOn == null) errors.add("第" + rowNumber + "行：发证时间必须为有效日期");
                if (certificateNumber.isBlank() || specialtyName.isBlank() || issuingAuthority.isBlank()) {
                    errors.add("第" + rowNumber + "行：证书编号、专业名称和发证单位必填");
                }
                if (certificateNumber.length() > 120 || specialtyName.length() > 200
                        || issuingAuthority.length() > 200 || remark.length() > 1000) {
                    errors.add("第" + rowNumber + "行：字段长度超过受控上限");
                }
                if (level == null) errors.add("第" + rowNumber + "行：职称等级不在受控选项中");
                if (category == null) errors.add("第" + rowNumber + "行：专业类型不在受控选项中");
                String normalizedNumber = certificateNumber.toUpperCase(Locale.ROOT);
                if (!certificateNumber.isBlank() && (!fileNumbers.add(normalizedNumber) || existing.contains(normalizedNumber))) {
                    errors.add("第" + rowNumber + "行：职称证书编号重复或已存在");
                }
                if (personId != null && issuedOn != null && level != null && category != null) {
                    rows.add(new ImportRow(rowNumber, personId, issuedOn, certificateNumber, specialtyName,
                            issuingAuthority, level, category, remark.isBlank() ? null : remark));
                }
            }
            if (rows.isEmpty() && errors.isEmpty()) throw invalid("TITLE_IMPORT_EMPTY", "导入文件没有可处理的数据行");
            if (!errors.isEmpty()) {
                throw ApiException.blocked("TITLE_IMPORT_VALIDATION_FAILED",
                        "人员职称导入完整校验失败，未写入任何记录", 0, errors.stream().limit(100).toList());
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw invalid("TITLE_IMPORT_STRUCTURE_INVALID", "导入文件不是可安全解析的 .xlsx 工作簿");
        }
    }

    private Map<String, UUID> people() {
        AuthenticatedUser user = actor.require();
        Map<String, UUID> result = new HashMap<>();
        jdbc.sql("""
                SELECT p.employee_number,p.id FROM person p JOIN department d ON d.id=p.department_id
                WHERE p.active AND d.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:department)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:person))
                """).param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("person", user.personId() == null ? new UUID(0, 0) : user.personId())
                .query((rs, row) -> Map.entry(rs.getString("employee_number"), rs.getObject("id", UUID.class)))
                .list().forEach(entry -> result.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue()));
        return result;
    }

    private void validateHeaders(Row row) {
        if (row == null) throw invalid("TITLE_IMPORT_HEADER_INVALID", "导入表头缺失");
        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(text(row, index))) {
                throw invalid("TITLE_IMPORT_HEADER_INVALID",
                        "第" + (index + 1) + "列表头必须为“" + HEADERS.get(index) + "”");
            }
        }
    }

    private void rejectFormulas(Row row, int rowNumber) {
        for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) {
            throw invalid("TITLE_IMPORT_FORMULA_REJECTED", "第" + rowNumber + "行包含公式，人员职称导入只接受原子值");
        }
    }

    private boolean blank(Row row) {
        for (int index = 0; index < HEADERS.size(); index++) if (!text(row, index).isBlank()) return false;
        return true;
    }

    private String text(Row row, int index) {
        return text(row == null ? null : row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
    }

    private String text(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return cell.toString().trim();
    }

    private LocalDate date(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        try { return LocalDate.parse(text(cell)); }
        catch (DateTimeParseException exception) { return null; }
    }

    private String controlled(String value, Map<String, String> options) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (options.containsKey(clean)) return options.get(clean);
        String code = clean.toUpperCase(Locale.ROOT);
        return options.containsValue(code) ? code : null;
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
