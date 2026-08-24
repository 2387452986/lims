package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.PasswordPolicy;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonnelTransferService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_IMPORT_ROWS = 1_000;
    private static final List<String> HEADERS = List.of(
            "工号", "姓名", "部门代码", "登录账号", "初始密码", "角色代码", "人员类型", "性别", "联系电话",
            "身份证件号", "出生日期", "职务", "职称", "办公地点", "专业", "学历", "开始检测工作日期", "劳动关系",
            "检测资格", "职称发证单位", "检测项目版本ID", "备注");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;
    private final PersonnelService personnel;
    private final PersonnelSensitiveDataService sensitive;
    private final PasswordPolicy passwordPolicy;
    private final ObjectMapper json;

    public PersonnelTransferService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                    StoredFileService files, PersonnelService personnel,
                                    PersonnelSensitiveDataService sensitive, PasswordPolicy passwordPolicy,
                                    ObjectMapper json) {
        this.jdbc = jdbc; this.actor = actor; this.audit = audit; this.files = files;
        this.personnel = personnel; this.sensitive = sensitive; this.passwordPolicy = passwordPolicy; this.json = json;
    }

    public record ImportFingerprint(String sha256, long sizeBytes) {}
    public record ImportResult(int importedCount, String sha256, List<UUID> personIds) {}
    public record ExportRequest(String accountState, String applicationState, String personType,
                                String name, String position) {}
    public record JobQueued(UUID jobId, String jobType, String state) {}

    public byte[] readUpload(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw invalid("PERSON_IMPORT_EMPTY", "请选择人员导入文件");
        String name = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") || upload.getSize() > 20L * 1024 * 1024) {
            throw invalid("PERSON_IMPORT_FILE_INVALID", "人员导入仅接受不超过20MB的受控 .xlsx 文件");
        }
        try {
            byte[] content = upload.getBytes();
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
                throw invalid("PERSON_IMPORT_HEADER_INVALID", "导入文件内容与 .xlsx 格式不匹配");
            }
            return content;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERSON_IMPORT_READ_FAILED", "无法读取人员导入文件");
        }
    }

    public ImportFingerprint fingerprint(byte[] content) {
        try {
            return new ImportFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                    content.length);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Transactional
    public ImportResult importWorkbook(byte[] content) {
        ImportFingerprint fingerprint = fingerprint(content);
        List<ImportRow> rows = parse(content);
        List<String> errors = validate(rows);
        if (!errors.isEmpty()) throw ApiException.blocked("PERSON_IMPORT_VALIDATION_FAILED",
                "人员导入完整校验失败，未写入任何记录", 0, errors.stream().limit(100).toList());
        List<UUID> ids = new ArrayList<>();
        for (ImportRow row : rows) {
            try {
                PersonnelService.PersonnelDetail created = personnel.create(row.command());
                ids.add(created.id());
            } catch (ApiException exception) {
                throw ApiException.blocked("PERSON_IMPORT_ROW_FAILED", "人员导入失败，整批已回滚", 0,
                        List.of("第" + row.rowNumber() + "行：" + exception.getMessage()));
            }
        }
        ImportResult result = new ImportResult(ids.size(), fingerprint.sha256(), List.copyOf(ids));
        audit.record("PERSONNEL_IMPORTED", "PERSON_IMPORT", fingerprint.sha256(), null,
                Map.of("importedCount", ids.size(), "sha256", fingerprint.sha256()), null,
                "受控 XLSX 完整校验后整批事务导入");
        return result;
    }

    @Transactional
    public StoredFileService.View createTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("人员导入");
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < HEADERS.size(); i++) sheet.setColumnWidth(i, Math.min(45, Math.max(14, HEADERS.get(i).length() * 3)) * 256);
            var instruction = workbook.createSheet("填写说明");
            List<String[]> notes = List.of(
                    new String[]{"字段", "要求"},
                    new String[]{"角色代码", "多个角色用英文逗号分隔，代码可在权限配置中查看"},
                    new String[]{"人员类型", "EMPLOYEE / CONTRACTOR / EXTERNAL_EXPERT / INTERN"},
                    new String[]{"性别", "UNKNOWN / MALE / FEMALE；居民身份证会自动校验和推导"},
                    new String[]{"日期", "统一填写 YYYY-MM-DD"},
                    new String[]{"检测项目版本ID", "多个已发布版本 UUID 用英文逗号分隔，可留空"},
                    new String[]{"事务规则", "先完整校验全部行；任一行失败则整份文件零写入"},
                    new String[]{"初始密码", "导入成功后不写入审计和导出文件；账号首次登录强制改密"});
            for (int r = 0; r < notes.size(); r++) {
                var row = instruction.createRow(r); row.createCell(0).setCellValue(notes.get(r)[0]);
                row.createCell(1).setCellValue(notes.get(r)[1]);
            }
            instruction.setColumnWidth(0, 22 * 256); instruction.setColumnWidth(1, 80 * 256);
            workbook.write(output);
            StoredFileService.View stored = files.storeGenerated(output.toByteArray(), "人员受控导入模板.xlsx", XLSX,
                    "PERSON_IMPORT_TEMPLATE", actor.require().id());
            audit.record("PERSON_IMPORT_TEMPLATE_CREATED", "STORED_FILE", stored.id(), null,
                    Map.of("sha256", stored.sha256()), null, null);
            return stored;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PERSON_IMPORT_TEMPLATE_FAILED", "人员导入模板生成失败");
        }
    }

    @Transactional
    public JobQueued enqueueExport(ExportRequest request, String idempotencyKey) {
        AuthenticatedUser user = actor.require();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", user.id()); payload.put("organizationId", user.organizationId());
        payload.put("filters", request == null ? new ExportRequest("", "", "", "", "") : request);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type)
                VALUES (:id,'EXPORT_PERSONNEL_XLSX',CAST(:payload AS jsonb),:key,'READY',:actor,'PERSONNEL_EXPORT')
                """).param("id", id).param("payload", encode(payload)).param("key", idempotencyKey)
                .param("actor", user.id()).update();
        JobQueued queued = new JobQueued(id, "EXPORT_PERSONNEL_XLSX", "READY");
        audit.record("PERSONNEL_EXPORT_QUEUED", "BACKGROUND_JOB", id, null, queued, null,
                "默认脱敏人员导出已排队");
        return queued;
    }

    private List<ImportRow> parse(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() < 1) throw invalid("PERSON_IMPORT_SHEET_MISSING", "导入文件缺少人员工作表");
            var sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 1) throw invalid("PERSON_IMPORT_EMPTY", "人员导入工作表为空");
            validateHeaders(sheet.getRow(0));
            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) throw invalid("PERSON_IMPORT_ROW_LIMIT",
                    "单次人员导入最多" + MAX_IMPORT_ROWS + "行");
            Map<String, UUID> departments = departmentMap();
            Map<String, UUID> roles = roleMap();
            List<ImportRow> result = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row)) continue;
                rejectFormulas(row, index + 1);
                String departmentCode = text(row, 2).toUpperCase(Locale.ROOT);
                List<UUID> roleIds = ids(text(row, 5), roles);
                List<UUID> capabilities = uuidList(text(row, 20));
                var account = new PersonnelService.AccountCreate(text(row, 3), text(row, 4), roleIds, true);
                var command = new PersonnelService.PersonnelCreate(text(row, 0), text(row, 1),
                        departments.get(departmentCode), text(row, 6), text(row, 7), text(row, 8), text(row, 9),
                        date(text(row, 10)), text(row, 12), text(row, 11), text(row, 13), text(row, 14), text(row, 15),
                        date(text(row, 16)), text(row, 17), text(row, 18), text(row, 19), text(row, 21),
                        null, null, null, null, capabilities, account);
                result.add(new ImportRow(index + 1, departmentCode, text(row, 5), command));
            }
            if (result.isEmpty()) throw invalid("PERSON_IMPORT_EMPTY", "导入文件没有可处理的数据行");
            return result;
        } catch (IOException exception) {
            throw invalid("PERSON_IMPORT_STRUCTURE_INVALID", "导入文件不是可安全解析的 .xlsx 工作簿");
        }
    }

    private List<String> validate(List<ImportRow> rows) {
        List<String> errors = new ArrayList<>();
        Set<String> employeeNumbers = new HashSet<>(); Set<String> usernames = new HashSet<>();
        Set<String> nationalIdDigests = new HashSet<>();
        Set<String> knownDepartments = departmentMap().keySet(); Set<String> knownRoles = roleMap().keySet();
        Set<UUID> requestedCapabilities = rows.stream().flatMap(row -> row.command().capabilityRevisionIds().stream())
                .filter(id -> !id.equals(new UUID(0, 0))).collect(java.util.stream.Collectors.toSet());
        Set<UUID> knownCapabilities = requestedCapabilities.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("""
                SELECT revision.id FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.id IN (:ids) AND revision.status='PUBLISHED' AND node.status='ACTIVE'
                """).param("ids", requestedCapabilities).query(UUID.class).list());
        Set<String> requestedEmployeeNumbers = rows.stream().map(row -> row.command().employeeNumber())
                .filter(value -> !blank(value)).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedUsernames = rows.stream().map(row -> row.command().account().username())
                .filter(value -> !blank(value)).collect(java.util.stream.Collectors.toSet());
        Set<String> existingEmployeeNumbers = requestedEmployeeNumbers.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql(
                "SELECT employee_number FROM person WHERE employee_number IN (:values)")
                .param("values", requestedEmployeeNumbers).query(String.class).list());
        Set<String> existingUsernames = requestedUsernames.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql(
                "SELECT username FROM app_user WHERE username IN (:values)")
                .param("values", requestedUsernames).query(String.class).list());
        for (ImportRow row : rows) {
            var value = row.command();
            if (blank(value.employeeNumber()) || blank(value.name()) || blank(value.account().username())
                    || blank(value.account().password())) errors.add("第" + row.rowNumber() + "行：工号、姓名、账号和初始密码必填");
            if (!knownDepartments.contains(row.departmentCode())) errors.add("第" + row.rowNumber() + "行：部门代码不存在或已停用");
            List<String> requestedRoles = csv(row.roleCodes());
            if (requestedRoles.isEmpty() || !knownRoles.containsAll(requestedRoles)) errors.add("第" + row.rowNumber() + "行：角色代码为空或包含未知值");
            String employeeNumber = value.employeeNumber().trim();
            String username = value.account().username().trim();
            if (!employeeNumbers.add(employeeNumber)) errors.add("第" + row.rowNumber() + "行：文件内工号重复");
            if (!usernames.add(username)) errors.add("第" + row.rowNumber() + "行：文件内登录账号重复");
            if (existingEmployeeNumbers.contains(employeeNumber)) errors.add("第" + row.rowNumber() + "行：工号已存在");
            if (existingUsernames.contains(username)) errors.add("第" + row.rowNumber() + "行：登录账号已存在");
            if (!username.isBlank() && !username.matches("[A-Za-z0-9._-]{4,80}")) {
                errors.add("第" + row.rowNumber() + "行：登录账号格式无效");
            }
            if (!username.isBlank() && !value.account().password().isBlank()) {
                try { passwordPolicy.validate(username, value.account().password()); }
                catch (ApiException exception) { errors.add("第" + row.rowNumber() + "行：" + exception.getMessage()); }
            }
            if (!Set.of("EMPLOYEE", "CONTRACTOR", "EXTERNAL_EXPERT", "INTERN").contains(
                    value.personType().trim().toUpperCase(Locale.ROOT))) {
                errors.add("第" + row.rowNumber() + "行：人员类型无效");
            }
            if (!Set.of("UNKNOWN", "MALE", "FEMALE").contains(value.gender().isBlank() ? "UNKNOWN"
                    : value.gender().trim().toUpperCase(Locale.ROOT))) {
                errors.add("第" + row.rowNumber() + "行：性别选项无效");
            }
            if (LocalDate.MAX.equals(value.birthDate())) errors.add("第" + row.rowNumber() + "行：出生日期格式必须为YYYY-MM-DD");
            if (LocalDate.MAX.equals(value.testingStartedOn())) errors.add("第" + row.rowNumber() + "行：开始检测工作日期格式必须为YYYY-MM-DD");
            if (value.birthDate() != null && !LocalDate.MAX.equals(value.birthDate())
                    && value.birthDate().isAfter(LocalDate.now())) errors.add("第" + row.rowNumber() + "行：出生日期不能晚于今天");
            if (value.testingStartedOn() != null && !LocalDate.MAX.equals(value.testingStartedOn())
                    && value.testingStartedOn().isAfter(LocalDate.now())) errors.add("第" + row.rowNumber() + "行：开始检测工作日期不能晚于今天");
            if (!blank(value.phone()) && !value.phone().matches("[0-9+() -]{6,40}")) {
                errors.add("第" + row.rowNumber() + "行：联系电话格式无效");
            }
            if (value.capabilityRevisionIds().contains(new UUID(0, 0))
                    || !knownCapabilities.containsAll(value.capabilityRevisionIds())) {
                errors.add("第" + row.rowNumber() + "行：检测项目版本ID无效、未发布或已停用");
            }
            if (!blank(value.nationalId())) {
                String normalizedId = value.nationalId().replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
                if (normalizedId.matches("[0-9]{17}[0-9X]")
                        && !PersonnelService.validResidentIdentityNumber(normalizedId)) {
                    errors.add("第" + row.rowNumber() + "行：居民身份证件号码校验位无效");
                }
                try {
                    PersonnelSensitiveDataService.ProtectedValue protectedValue = sensitive.protectNationalId(normalizedId);
                    if (protectedValue != null && !nationalIdDigests.add(protectedValue.digest())) {
                        errors.add("第" + row.rowNumber() + "行：文件内身份证件号重复");
                    }
                } catch (ApiException exception) {
                    errors.add("第" + row.rowNumber() + "行：" + exception.getMessage());
                }
            }
        }
        if (!nationalIdDigests.isEmpty()) {
            Set<String> existingDigests = new HashSet<>(jdbc.sql(
                    "SELECT national_id_digest FROM person WHERE national_id_digest IN (:values)")
                    .param("values", nationalIdDigests).query(String.class).list());
            for (ImportRow row : rows) {
                if (blank(row.command().nationalId())) continue;
                try {
                    var protectedValue = sensitive.protectNationalId(row.command().nationalId());
                    if (protectedValue != null && existingDigests.contains(protectedValue.digest())) {
                        errors.add("第" + row.rowNumber() + "行：身份证件号已存在");
                    }
                } catch (ApiException ignored) {
                    // The field-specific validation error has already been collected above.
                }
            }
        }
        return errors;
    }

    private void validateHeaders(Row row) {
        if (row == null) throw invalid("PERSON_IMPORT_HEADER_INVALID", "导入表头缺失");
        for (int i = 0; i < HEADERS.size(); i++) {
            if (!HEADERS.get(i).equals(text(row, i))) throw invalid("PERSON_IMPORT_HEADER_INVALID",
                    "第" + (i + 1) + "列表头必须为“" + HEADERS.get(i) + "”");
        }
    }

    private Map<String, UUID> departmentMap() {
        return jdbc.sql("""
                SELECT code,id FROM department WHERE organization_id=:organization AND active
                """).param("organization", actor.require().organizationId())
                .query((rs, row) -> Map.entry(rs.getString("code").toUpperCase(Locale.ROOT), rs.getObject("id", UUID.class)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, UUID> roleMap() {
        return jdbc.sql("SELECT code,id FROM app_role").query((rs, row) ->
                Map.entry(rs.getString("code").toUpperCase(Locale.ROOT), rs.getObject("id", UUID.class)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<UUID> ids(String value, Map<String, UUID> options) {
        return csv(value).stream().map(options::get).filter(java.util.Objects::nonNull).distinct().toList();
    }
    private List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of()
                : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT)).distinct().toList();
    }
    private List<UUID> uuidList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<UUID> result = new ArrayList<>();
        for (String item : value.split(",")) {
            try { result.add(UUID.fromString(item.trim())); }
            catch (IllegalArgumentException exception) { return List.of(new UUID(0, 0)); }
        }
        return result.stream().distinct().toList();
    }
    private LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException exception) { return LocalDate.MAX; }
    }
    private void rejectFormulas(Row row, int rowNumber) {
        for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) throw invalid(
                "PERSON_IMPORT_FORMULA_REJECTED", "第" + rowNumber + "行包含公式，人员导入只接受原子值");
    }
    private boolean blank(Row row) {
        for (int i = 0; i < HEADERS.size(); i++) if (!text(row, i).isBlank()) return false;
        return true;
    }
    private String text(Row row, int index) {
        Cell cell = row == null ? null : row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        return cell.toString().trim();
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法编码人员导出条件", exception); }
    }
    private record ImportRow(int rowNumber, String departmentCode, String roleCodes,
                             PersonnelService.PersonnelCreate command) {}
}
