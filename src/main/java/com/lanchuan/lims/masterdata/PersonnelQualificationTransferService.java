package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonnelQualificationTransferService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_ROWS = 1_000;
    private static final List<String> HEADERS = List.of("工号", "资格代码", "证书编号", "生效日期", "有效期至",
            "发证单位", "执业编号", "执业单位", "专业详情", "备注");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;
    private final PersonnelQualificationService qualifications;

    public PersonnelQualificationTransferService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                                 StoredFileService files,
                                                 PersonnelQualificationService qualifications) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
        this.qualifications = qualifications;
    }

    public record Fingerprint(String sha256, long sizeBytes) {}
    public record ImportResult(int importedCount, String sha256, List<UUID> qualificationIds) {}

    public byte[] readUpload(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw invalid("QUALIFICATION_IMPORT_EMPTY", "请选择人员资格导入文件");
        String name = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") || upload.getSize() > 20L * 1024 * 1024) {
            throw invalid("QUALIFICATION_IMPORT_FILE_INVALID", "人员资格导入只接受不超过20MB的受控 .xlsx 文件");
        }
        try {
            byte[] content = upload.getBytes();
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
                throw invalid("QUALIFICATION_IMPORT_HEADER_INVALID", "导入文件内容与 .xlsx 格式不匹配");
            }
            return content;
        } catch (IOException exception) {
            throw invalid("QUALIFICATION_IMPORT_READ_FAILED", "无法读取人员资格导入文件");
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
            var sheet = workbook.createSheet("人员资格导入");
            var header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
                sheet.setColumnWidth(index, (index == 8 ? 48 : 22) * 256);
            }
            sheet.createFreezePane(0, 1);
            var instruction = workbook.createSheet("填写说明");
            List<String[]> notes = List.of(
                    new String[]{"规则", "要求"},
                    new String[]{"日期", "统一填写 YYYY-MM-DD，生效日期不得晚于今日"},
                    new String[]{"专业详情", String.join("、", PersonnelQualificationService.SPECIALTIES) + "；多项用英文逗号分隔"},
                    new String[]{"附件", "批量导入不导入照片，可在草稿详情中受控上传"},
                    new String[]{"事务", "先校验全部行；任一行失败则整份文件零写入"});
            for (int index = 0; index < notes.size(); index++) {
                var row = instruction.createRow(index);
                row.createCell(0).setCellValue(notes.get(index)[0]);
                row.createCell(1).setCellValue(notes.get(index)[1]);
            }
            instruction.setColumnWidth(0, 20 * 256);
            instruction.setColumnWidth(1, 100 * 256);
            workbook.write(output);
            StoredFileService.View stored = files.storeGenerated(output.toByteArray(), "人员资格受控导入模板.xlsx", XLSX,
                    "PERSON_QUALIFICATION_IMPORT_TEMPLATE", actor.require().id());
            audit.record("PERSON_QUALIFICATION_IMPORT_TEMPLATE_CREATED", "STORED_FILE", stored.id(), null,
                    Map.of("sha256", stored.sha256()), null, null);
            return stored;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "QUALIFICATION_IMPORT_TEMPLATE_FAILED",
                    "人员资格导入模板生成失败");
        }
    }

    @Transactional
    public ImportResult importWorkbook(byte[] content) {
        Fingerprint fingerprint = fingerprint(content);
        Parsed parsed = parse(content);
        List<String> errors = validate(parsed.rows(), parsed.people());
        if (!errors.isEmpty()) {
            throw ApiException.blocked("QUALIFICATION_IMPORT_VALIDATION_FAILED",
                    "人员资格导入完整校验失败，未写入任何记录", 0,
                    errors.stream().limit(100).toList());
        }
        List<UUID> ids = new ArrayList<>();
        for (ImportRow row : parsed.rows()) {
            try {
                PersonnelQualificationService.View created = qualifications.create(row.command(parsed.people()));
                ids.add(created.id());
            } catch (ApiException exception) {
                throw ApiException.blocked("QUALIFICATION_IMPORT_ROW_FAILED",
                        "人员资格导入失败，整批已回滚", 0,
                        List.of("第" + row.rowNumber() + "行：" + exception.getMessage()));
            }
        }
        ImportResult result = new ImportResult(ids.size(), fingerprint.sha256(), List.copyOf(ids));
        audit.record("PERSON_QUALIFICATIONS_IMPORTED", "PERSON_QUALIFICATION_IMPORT", fingerprint.sha256(),
                null, Map.of("importedCount", ids.size(), "sha256", fingerprint.sha256()), null,
                "受控 XLSX 完整校验后整批事务导入");
        return result;
    }

    private Parsed parse(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() < 1) throw invalid("QUALIFICATION_IMPORT_SHEET_MISSING", "导入文件缺少人员资格工作表");
            var sheet = workbook.getSheetAt(0);
            validateHeaders(sheet.getRow(0));
            if (sheet.getLastRowNum() > MAX_ROWS) throw invalid("QUALIFICATION_IMPORT_ROW_LIMIT", "单次人员资格导入最多1000行");
            Map<String, UUID> people = people();
            List<ImportRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row)) continue;
                rejectFormulas(row, index + 1);
                rows.add(new ImportRow(index + 1, text(row, 0), text(row, 1), text(row, 2),
                        date(text(row, 3)), date(text(row, 4)), text(row, 5), text(row, 6), text(row, 7),
                        values(text(row, 8)), text(row, 9)));
            }
            if (rows.isEmpty()) throw invalid("QUALIFICATION_IMPORT_EMPTY", "导入文件没有可处理的数据行");
            return new Parsed(rows, people);
        } catch (IOException exception) {
            throw invalid("QUALIFICATION_IMPORT_STRUCTURE_INVALID", "导入文件不是可安全解析的 .xlsx 工作簿");
        }
    }

    private List<String> validate(List<ImportRow> rows, Map<String, UUID> people) {
        List<String> errors = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        Map<UUID, Set<String>> existing = new HashMap<>();
        for (ImportRow row : rows) {
            if (row.employeeNumber().isBlank() || row.qualificationCode().isBlank()
                    || row.certificateNumber().isBlank() || row.issuingAuthority().isBlank()
                    || row.practiceNumber().isBlank() || row.practiceOrganization().isBlank()) {
                errors.add("第" + row.rowNumber() + "行：工号、资格代码、证书及发证/执业信息必填");
            }
            UUID personId = people.get(row.employeeNumber().toUpperCase(Locale.ROOT));
            if (personId == null) errors.add("第" + row.rowNumber() + "行：工号不存在、已停用或超出当前数据范围");
            if (row.validFrom() == null || LocalDate.MAX.equals(row.validFrom())
                    || row.validUntil() == null || LocalDate.MAX.equals(row.validUntil())) {
                errors.add("第" + row.rowNumber() + "行：生效日期和有效期至必须为YYYY-MM-DD");
            } else {
                if (row.validFrom().isAfter(LocalDate.now())) errors.add("第" + row.rowNumber() + "行：生效日期不能晚于今日");
                if (row.validUntil().isBefore(row.validFrom()) || row.validUntil().isBefore(LocalDate.now())) {
                    errors.add("第" + row.rowNumber() + "行：有效期无效或已过期");
                }
            }
            if (row.specialties().isEmpty() || !PersonnelQualificationService.SPECIALTIES.containsAll(row.specialties())) {
                errors.add("第" + row.rowNumber() + "行：专业详情为空或包含未受控选项");
            }
            String key = row.employeeNumber().toUpperCase(Locale.ROOT) + "|"
                    + row.qualificationCode().toUpperCase(Locale.ROOT) + "|"
                    + row.certificateNumber().toUpperCase(Locale.ROOT);
            if (!duplicates.add(key)) errors.add("第" + row.rowNumber() + "行：文件内资格证书重复");
            if (personId != null) {
                Set<String> keys = existing.computeIfAbsent(personId, id -> new HashSet<>(jdbc.sql("""
                        SELECT upper(qualification_code)||'|'||upper(certificate_number)
                        FROM person_qualification WHERE person_id=:person
                        """).param("person", id).query(String.class).list()));
                String recordKey = row.qualificationCode().toUpperCase(Locale.ROOT) + "|"
                        + row.certificateNumber().toUpperCase(Locale.ROOT);
                if (keys.contains(recordKey)) errors.add("第" + row.rowNumber() + "行：该人员资格证书已存在");
            }
        }
        return errors;
    }

    private Map<String, UUID> people() {
        Map<String, UUID> result = new HashMap<>();
        for (PersonnelQualificationService.PersonOption person : qualifications.options().people()) {
            result.put(person.employeeNumber().toUpperCase(Locale.ROOT), person.id());
        }
        return result;
    }

    private void validateHeaders(Row row) {
        if (row == null) throw invalid("QUALIFICATION_IMPORT_HEADER_INVALID", "导入表头缺失");
        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(text(row, index))) {
                throw invalid("QUALIFICATION_IMPORT_HEADER_INVALID",
                        "第" + (index + 1) + "列表头必须为“" + HEADERS.get(index) + "”");
            }
        }
    }

    private void rejectFormulas(Row row, int rowNumber) {
        for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) {
            throw invalid("QUALIFICATION_IMPORT_FORMULA_REJECTED",
                    "第" + rowNumber + "行包含公式，人员资格导入只接受原子值");
        }
    }

    private boolean blank(Row row) {
        for (int index = 0; index < HEADERS.size(); index++) if (!text(row, index).isBlank()) return false;
        return true;
    }

    private String text(Row row, int index) {
        Cell cell = row == null ? null : row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            return java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return cell.toString().trim();
    }

    private LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException exception) { return LocalDate.MAX; }
    }

    private List<String> values(String value) {
        if (value == null || value.isBlank()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : value.replace('，', ',').split(",")) {
            String cleaned = item.trim();
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        return List.copyOf(result);
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record Parsed(List<ImportRow> rows, Map<String, UUID> people) {}
    private record ImportRow(int rowNumber, String employeeNumber, String qualificationCode,
                             String certificateNumber, LocalDate validFrom, LocalDate validUntil,
                             String issuingAuthority, String practiceNumber, String practiceOrganization,
                             List<String> specialties, String remark) {
        PersonnelQualificationService.Create command(Map<String, UUID> people) {
            return new PersonnelQualificationService.Create(people.get(employeeNumber.toUpperCase(Locale.ROOT)),
                    qualificationCode, certificateNumber, validFrom, validUntil, issuingAuthority,
                    practiceNumber, practiceOrganization, specialties, null, remark);
        }
    }
}
