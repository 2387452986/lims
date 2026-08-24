package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonnelQualificationService {
    public static final String FILE_PURPOSE = "PERSON_QUALIFICATION_CERTIFICATE";
    public static final List<String> SPECIALTIES = List.of(
            "岩土工程", "混凝土工程", "金属结构", "机械电气", "量测",
            "工程师", "助理工程师", "副高级工程师", "正高级工程师");
    private static final long MAX_CERTIFICATE_BYTES = 3L * 1024 * 1024;
    private static final Set<String> CERTIFICATE_MEDIA = Set.of("image/png", "image/jpeg", "image/bmp");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;

    public PersonnelQualificationService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                         StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
    }

    public record PersonOption(UUID id, String employeeNumber, String name, String departmentName) {}
    public record Options(List<PersonOption> people, List<String> specialties, String filePurpose) {}
    public record View(UUID id, UUID personId, String employeeNumber, String personName,
                       String departmentName, String qualificationCode, String certificateNumber,
                       LocalDate validFrom, LocalDate validUntil, String issuingAuthority,
                       String practiceNumber, String practiceOrganization, List<String> specialties,
                       UUID certificateFileId, String certificateFileName, String remark, String status,
                       long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       List<String> allowedActions) {}

    public record Create(@NotNull UUID personId,
                         @NotBlank @Size(max = 80) String qualificationCode,
                         @NotBlank @Size(max = 100) String certificateNumber,
                         @NotNull @PastOrPresent LocalDate validFrom,
                         @NotNull LocalDate validUntil,
                         @NotBlank @Size(max = 200) String issuingAuthority,
                         @NotBlank @Size(max = 120) String practiceNumber,
                         @NotBlank @Size(max = 200) String practiceOrganization,
                         @NotEmpty @Size(max = 9) List<@NotBlank String> specialties,
                         UUID certificateFileId,
                         @Size(max = 2000) String remark) {}

    public record Update(@NotNull UUID personId,
                         @NotBlank @Size(max = 80) String qualificationCode,
                         @NotBlank @Size(max = 100) String certificateNumber,
                         @NotNull @PastOrPresent LocalDate validFrom,
                         @NotNull LocalDate validUntil,
                         @NotBlank @Size(max = 200) String issuingAuthority,
                         @NotBlank @Size(max = 120) String practiceNumber,
                         @NotBlank @Size(max = 200) String practiceOrganization,
                         @NotEmpty @Size(max = 9) List<@NotBlank String> specialties,
                         UUID certificateFileId,
                         @Size(max = 2000) String remark,
                         @NotNull @PositiveOrZero Long expectedVersion) {}

    public record BatchItem(@NotNull UUID id, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record BatchCommand(@NotBlank @Size(max = 40) String action,
                               @NotEmpty @Size(max = 100) List<@Valid BatchItem> items,
                               @Size(max = 80) String reasonCode,
                               @Size(max = 1000) String comment) {}
    public record BatchResult(int processed, List<TransitionResult> results) {}

    public CursorPage<View> list(String status, String specialty, String hasFile, String issuer,
                                 String personName, String cursor, int requestedLimit) {
        AuthenticatedUser user = actor.require();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor page = PageCursor.decode(cursor);
        String normalizedStatus = filter(status).toUpperCase(Locale.ROOT);
        String normalizedSpecialty = filter(specialty);
        String normalizedFile = filter(hasFile).toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty() && !states().contains(normalizedStatus)) {
            throw invalid("QUALIFICATION_STATUS_INVALID", "人员资格状态筛选无效");
        }
        if (!normalizedSpecialty.isEmpty() && !SPECIALTIES.contains(normalizedSpecialty)) {
            throw invalid("QUALIFICATION_SPECIALTY_INVALID", "人员资格专业筛选无效");
        }
        if (!Set.of("", "YES", "NO").contains(normalizedFile)) {
            throw invalid("QUALIFICATION_FILE_FILTER_INVALID", "上传文件筛选无效");
        }
        String issuerPattern = like(issuer);
        String personPattern = like(personName);
        List<View> rows = bindScope(jdbc.sql(selectSql() + """
                  AND (:status='' OR q.status=:status)
                  AND (:specialty='' OR :specialty=ANY(q.specialties))
                  AND (:hasFile='' OR (:hasFile='YES' AND q.certificate_file_id IS NOT NULL)
                    OR (:hasFile='NO' AND q.certificate_file_id IS NULL))
                  AND (:issuerPattern='%%' OR COALESCE(q.issuing_authority,'') ILIKE :issuerPattern ESCAPE '\\')
                  AND (:personPattern='%%' OR p.name ILIKE :personPattern ESCAPE '\\'
                    OR p.employee_number ILIKE :personPattern ESCAPE '\\')
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR
                    (q.updated_at,q.id)<(CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY q.updated_at DESC,q.id DESC LIMIT :fetchLimit
                """), user)
                .param("status", normalizedStatus).param("specialty", normalizedSpecialty)
                .param("hasFile", normalizedFile).param("issuerPattern", issuerPattern)
                .param("personPattern", personPattern).param("cursorAt", page == null ? null : page.updatedAt())
                .param("cursorId", page == null ? null : page.id()).param("fetchLimit", limit + 1)
                .query(this::map).list();
        long total = bindScope(jdbc.sql("""
                SELECT count(*) FROM person_qualification q JOIN person p ON p.id=q.person_id
                JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND p.department_id=:department)
                  OR (:scope IN ('SELF','PROJECT') AND p.id=:person))
                  AND (:status='' OR q.status=:status)
                  AND (:specialty='' OR :specialty=ANY(q.specialties))
                  AND (:hasFile='' OR (:hasFile='YES' AND q.certificate_file_id IS NOT NULL)
                    OR (:hasFile='NO' AND q.certificate_file_id IS NULL))
                  AND (:issuerPattern='%%' OR COALESCE(q.issuing_authority,'') ILIKE :issuerPattern ESCAPE '\\')
                  AND (:personPattern='%%' OR p.name ILIKE :personPattern ESCAPE '\\'
                    OR p.employee_number ILIKE :personPattern ESCAPE '\\')
                """), user).param("status", normalizedStatus).param("specialty", normalizedSpecialty)
                .param("hasFile", normalizedFile).param("issuerPattern", issuerPattern)
                .param("personPattern", personPattern).query(Long.class).single();
        boolean hasMore = rows.size() > limit;
        List<View> items = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore && !items.isEmpty()
                ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public Options options() {
        AuthenticatedUser user = actor.require();
        List<PersonOption> people = bindScope(jdbc.sql("""
                SELECT p.id,p.employee_number,p.name,d.name department_name
                FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND p.active
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:department)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:person))
                ORDER BY p.name,p.employee_number
                """), user).query((rs, row) -> new PersonOption(rs.getObject("id", UUID.class),
                rs.getString("employee_number"), rs.getString("name"), rs.getString("department_name"))).list();
        return new Options(people, SPECIALTIES, FILE_PURPOSE);
    }

    public View require(UUID id) {
        return bindScope(jdbc.sql(selectSql() + " AND q.id=:id"), actor.require()).param("id", id)
                .query(this::map).optional().orElseThrow(() -> ApiException.notFound(
                        "PERSON_QUALIFICATION_NOT_FOUND", "人员资格不存在或不在当前数据范围内"));
    }

    @Transactional
    public View create(Create body) {
        AuthenticatedUser user = actor.require();
        accessiblePerson(body.personId(), user);
        Validated value = validate(body.validFrom(), body.validUntil(), body.specialties(), body.certificateFileId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO person_qualification(id,person_id,qualification_code,certificate_number,
                  valid_from,valid_until,status,issuing_authority,practice_number,practice_organization,
                  specialties,remark,certificate_file_id,created_by,updated_by)
                VALUES (:id,:person,:code,:certificate,:validFrom,:validUntil,'DRAFT',:issuer,:practiceNumber,
                  :practiceOrganization,:specialties,:remark,:file,:actor,:actor)
                """).param("id", id).param("person", body.personId()).param("code", clean(body.qualificationCode()))
                .param("certificate", clean(body.certificateNumber())).param("validFrom", body.validFrom())
                .param("validUntil", body.validUntil()).param("issuer", clean(body.issuingAuthority()))
                .param("practiceNumber", clean(body.practiceNumber()))
                .param("practiceOrganization", clean(body.practiceOrganization()))
                .param("specialties", value.specialties().toArray(String[]::new))
                .param("remark", nullable(body.remark())).param("file", body.certificateFileId())
                .param("actor", user.id()).update();
        View created = require(id);
        audit.record("PERSON_QUALIFICATION_CREATED", "PERSON_QUALIFICATION", id, null, created,
                null, "人员资格草稿已建立");
        return created;
    }

    @Transactional
    public View update(UUID id, Update body) {
        View before = require(id);
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "人员资格已被更新", before.version());
        }
        if (!Set.of("DRAFT", "RETURNED").contains(before.status())) {
            throw ApiException.blocked("QUALIFICATION_IMMUTABLE", "已提交的人员资格不能原地修改", before.version(),
                    List.of("请先撤回或由审核人退回"));
        }
        accessiblePerson(body.personId(), actor.require());
        Validated value = validate(body.validFrom(), body.validUntil(), body.specialties(), body.certificateFileId());
        int changed = jdbc.sql("""
                UPDATE person_qualification SET person_id=:person,qualification_code=:code,
                  certificate_number=:certificate,valid_from=:validFrom,valid_until=:validUntil,
                  issuing_authority=:issuer,practice_number=:practiceNumber,
                  practice_organization=:practiceOrganization,specialties=:specialties,remark=:remark,
                  certificate_file_id=:file,updated_by=:actor,updated_at=now(),version=version+1
                WHERE id=:id AND version=:version
                """).param("person", body.personId()).param("code", clean(body.qualificationCode()))
                .param("certificate", clean(body.certificateNumber())).param("validFrom", body.validFrom())
                .param("validUntil", body.validUntil()).param("issuer", clean(body.issuingAuthority()))
                .param("practiceNumber", clean(body.practiceNumber()))
                .param("practiceOrganization", clean(body.practiceOrganization()))
                .param("specialties", value.specialties().toArray(String[]::new))
                .param("remark", nullable(body.remark())).param("file", body.certificateFileId())
                .param("actor", actor.require().id()).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "人员资格已被更新", require(id).version());
        View after = require(id);
        audit.record("PERSON_QUALIFICATION_UPDATED", "PERSON_QUALIFICATION", id, before, after,
                null, "人员资格草稿已修改");
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "人员资格已被更新", before.version());
        }
        String action = clean(command.action()).toUpperCase(Locale.ROOT);
        String next = nextState(before.status(), action);
        if (Set.of("WITHDRAW", "RETURN", "SUSPEND", "REVOKE", "EXPIRE", "ARCHIVE").contains(action)
                && (command.comment() == null || command.comment().isBlank())) {
            throw invalid("QUALIFICATION_REASON_REQUIRED", "该资格状态操作必须填写原因");
        }
        AuthenticatedUser user = actor.require();
        int changed = jdbc.sql("""
                UPDATE person_qualification SET status=:state,version=version+1,updated_at=now(),updated_by=:actor,
                  submitted_at=CASE WHEN :action='SUBMIT' THEN now() WHEN :action='WITHDRAW' THEN NULL ELSE submitted_at END,
                  submitted_by=CASE WHEN :action='SUBMIT' THEN :actor WHEN :action='WITHDRAW' THEN NULL ELSE submitted_by END,
                  reviewed_at=CASE WHEN :action IN ('APPROVE','RETURN') THEN now() ELSE reviewed_at END,
                  reviewed_by=CASE WHEN :action IN ('APPROVE','RETURN') THEN :actor ELSE reviewed_by END
                WHERE id=:id AND version=:version
                """).param("state", next).param("action", action).param("actor", user.id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "人员资格已被更新", require(id).version());
        View after = require(id);
        audit.record("PERSON_QUALIFICATION_" + action, "PERSON_QUALIFICATION", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), after.allowedActions());
    }

    @Transactional
    public BatchResult batch(BatchCommand command) {
        if (command.items().stream().map(BatchItem::id).distinct().count() != command.items().size()) {
            throw invalid("QUALIFICATION_BATCH_DUPLICATE", "批量人员资格操作不能包含重复记录");
        }
        List<TransitionResult> results = new ArrayList<>();
        for (BatchItem item : command.items()) {
            results.add(transition(item.id(), new TransitionCommand(command.action(), item.expectedVersion(),
                    command.reasonCode(), command.comment())));
        }
        return new BatchResult(results.size(), List.copyOf(results));
    }

    static String nextState(String state, String action) {
        return switch (state) {
            case "DRAFT", "RETURNED" -> switch (action) {
                case "SUBMIT" -> "SUBMITTED";
                case "ARCHIVE" -> "ARCHIVED";
                default -> invalidTransition(state, action);
            };
            case "SUBMITTED" -> switch (action) {
                case "WITHDRAW" -> "DRAFT";
                case "APPROVE" -> "VALID";
                case "RETURN" -> "RETURNED";
                default -> invalidTransition(state, action);
            };
            case "VALID" -> switch (action) {
                case "SUSPEND" -> "SUSPENDED";
                case "REVOKE" -> "REVOKED";
                case "EXPIRE" -> "EXPIRED";
                default -> invalidTransition(state, action);
            };
            case "SUSPENDED" -> switch (action) {
                case "ACTIVATE" -> "VALID";
                case "REVOKE" -> "REVOKED";
                case "EXPIRE" -> "EXPIRED";
                default -> invalidTransition(state, action);
            };
            default -> invalidTransition(state, action);
        };
    }

    static List<String> actions(String state) {
        return switch (state) {
            case "DRAFT", "RETURNED" -> List.of("SUBMIT", "ARCHIVE");
            case "SUBMITTED" -> List.of("WITHDRAW", "APPROVE", "RETURN");
            case "VALID" -> List.of("SUSPEND", "REVOKE", "EXPIRE");
            case "SUSPENDED" -> List.of("ACTIVATE", "REVOKE", "EXPIRE");
            default -> List.of();
        };
    }

    private View map(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("status");
        return new View(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("employee_number"), rs.getString("person_name"), rs.getString("department_name"),
                rs.getString("qualification_code"), rs.getString("certificate_number"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                rs.getString("issuing_authority"), rs.getString("practice_number"),
                rs.getString("practice_organization"), array(rs.getArray("specialties")),
                rs.getObject("certificate_file_id", UUID.class), rs.getString("certificate_file_name"),
                rs.getString("remark"), state, rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                actions(state));
    }

    private String selectSql() {
        return """
                SELECT q.id,q.person_id,p.employee_number,p.name person_name,d.name department_name,
                  q.qualification_code,q.certificate_number,q.valid_from,q.valid_until,q.issuing_authority,
                  q.practice_number,q.practice_organization,q.specialties,q.certificate_file_id,
                  file.original_name certificate_file_name,q.remark,q.status,q.version,q.created_at,q.updated_at
                FROM person_qualification q JOIN person p ON p.id=q.person_id
                JOIN department d ON d.id=p.department_id
                LEFT JOIN stored_file file ON file.id=q.certificate_file_id
                WHERE d.organization_id=:organization AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND p.department_id=:department)
                  OR (:scope IN ('SELF','PROJECT') AND p.id=:person))
                """;
    }

    private JdbcClient.StatementSpec bindScope(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("person", user.personId() == null ? new UUID(0, 0) : user.personId());
    }

    private void accessiblePerson(UUID id, AuthenticatedUser user) {
        boolean accessible = bindScope(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person p JOIN department d ON d.id=p.department_id
                  WHERE p.id=:id AND d.organization_id=:organization AND p.active
                    AND (:scope IN ('ALL','ORGANIZATION')
                      OR (:scope='DEPARTMENT' AND p.department_id=:department)
                      OR (:scope IN ('SELF','PROJECT') AND p.id=:person)))
                """), user).param("id", id).query(Boolean.class).single();
        if (!accessible) throw ApiException.notFound("PERSON_NOT_FOUND", "人员不存在或不在当前数据范围内");
    }

    private Validated validate(LocalDate validFrom, LocalDate validUntil, List<String> specialties, UUID fileId) {
        if (validUntil.isBefore(validFrom)) {
            throw invalid("QUALIFICATION_DATES_INVALID", "资格有效期不能早于生效日期");
        }
        if (validUntil.isBefore(LocalDate.now())) {
            throw invalid("QUALIFICATION_EXPIRED_ON_CREATE", "新建人员资格的有效期不能早于今日");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String specialty : specialties) {
            String value = clean(specialty);
            if (!SPECIALTIES.contains(value)) {
                throw invalid("QUALIFICATION_SPECIALTY_INVALID", "人员资格包含未受控的专业详情");
            }
            distinct.add(value);
        }
        if (distinct.isEmpty()) throw invalid("QUALIFICATION_SPECIALTY_REQUIRED", "至少选择一项专业详情");
        if (fileId != null) {
            StoredFileService.View file = files.requireCurrentUpload(fileId);
            if (!FILE_PURPOSE.equals(file.purpose()) || !CERTIFICATE_MEDIA.contains(file.mediaType())
                    || file.sizeBytes() > MAX_CERTIFICATE_BYTES) {
                throw invalid("QUALIFICATION_FILE_INVALID",
                        "资格照片必须是当前上传的 PNG、JPEG 或 BMP，且不超过3MB");
            }
        }
        return new Validated(List.copyOf(distinct));
    }

    private List<String> array(Array value) throws SQLException {
        if (value == null) return List.of();
        Object content = value.getArray();
        if (content instanceof String[] strings) return List.of(strings);
        Object[] objects = (Object[]) content;
        return java.util.Arrays.stream(objects).map(String::valueOf).toList();
    }

    private static String invalidTransition(String state, String action) {
        throw ApiException.blocked("QUALIFICATION_TRANSITION_INVALID",
                "当前人员资格状态不允许该操作", 0, List.of(state + " 不允许 " + action));
    }

    private Set<String> states() {
        return Set.of("DRAFT", "SUBMITTED", "RETURNED", "VALID", "SUSPENDED", "EXPIRED", "REVOKED", "ARCHIVED");
    }

    private String filter(String value) { return value == null ? "" : value.trim(); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String nullable(String value) { String cleaned = clean(value); return cleaned.isEmpty() ? null : cleaned; }
    private String like(String value) {
        String cleaned = filter(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + cleaned + "%";
    }
    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    private record Validated(List<String> specialties) {}
}
