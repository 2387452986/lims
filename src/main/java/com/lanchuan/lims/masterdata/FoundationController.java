package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Validated
@RestController
@RequestMapping("/api/foundation")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class FoundationController {
    private static final OffsetDateTime FIRST_PAGE_TIME = OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    private static final UUID FIRST_PAGE_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final Set<String> TITLE_LEVELS = Set.of(
            "ASSISTANT_ENGINEER", "ENGINEER", "SENIOR_ENGINEER", "PROFESSORATE_SENIOR_ENGINEER");
    private static final Set<String> TITLE_CATEGORIES = Set.of(
            "WATER_CONSERVANCY", "TESTING", "TRANSPORTATION", "MUNICIPAL", "PORT_WATERWAY",
            "CIVIL", "GEOTECHNICAL", "STRUCTURAL", "OTHER");
    private static final Set<String> TITLE_STATES = Set.of("DRAFT", "SUBMITTED", "RETIRED");
    private static final Set<String> TITLE_FILE_MEDIA = Set.of("image/png", "image/jpeg", "image/bmp");
    private static final long TITLE_FILE_MAX_BYTES = 3L * 1024 * 1024;
    private static final Set<String> QUALIFICATION_DETAILS = Set.of(
            "GEOTECHNICAL_A", "CONCRETE_A", "METAL_STRUCTURE_A", "MECHANICAL_ELECTRICAL_A", "MEASUREMENT_A",
            "GEOTECHNICAL_B", "CONCRETE_B", "METAL_STRUCTURE_B", "MECHANICAL_ELECTRICAL_B", "MEASUREMENT_B");
    private static final List<String> TRAINING_METHODS = List.of("现场授课", "在线学习");
    private static final List<String> TRAINING_ASSESSMENT_RESULTS = List.of("通过", "合格", "优秀");
    private static final Set<String> PROJECT_TEAM_STATES = Set.of("ACTIVE", "INACTIVE");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final StoredFileService files;
    private final ObjectMapper json;
    private final PersonnelTitleTransferService titleTransfers;
    private final ShelfQrRenderer shelfQr = new ShelfQrRenderer();

    public FoundationController(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                IdempotencyService idempotency, StoredFileService files, ObjectMapper json,
                                PersonnelTitleTransferService titleTransfers) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.idempotency = idempotency;
        this.files = files;
        this.json = json;
        this.titleTransfers = titleTransfers;
    }

    public record TrainingView(UUID id, UUID personId, String personName, UUID departmentId, String departmentName,
                               LocalDate startDate, LocalDate endDate, BigDecimal hours, String organizer,
                               String trainingMethod, String assessmentResult, String location, String content,
                               UUID certificateFileId, String certificateFileName, String remark, String state,
                               long version, OffsetDateTime updatedAt) {}
    public record TrainingWrite(@NotNull UUID personId, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @NotNull @DecimalMin("0.0") BigDecimal hours,
                                @NotBlank @Size(max = 200) String organizer,
                                @NotBlank @Size(max = 80) String trainingMethod,
                                @Size(max = 40) String assessmentResult,
                                @NotBlank @Size(max = 240) String location,
                                @NotBlank @Size(max = 2000) String content,
                                UUID certificateFileId, @Size(max = 1000) String remark,
                                @PositiveOrZero Long expectedVersion) {}
    public record TrainingPersonOption(UUID id, String employeeNumber, String name,
                                       UUID departmentId, String departmentName) {}
    public record TrainingDepartmentOption(UUID id, String name) {}
    public record TrainingOptions(List<TrainingPersonOption> people,
                                  List<TrainingDepartmentOption> departments,
                                  List<String> trainingMethods, List<String> assessmentResults) {}
    public record TrainingBatchItem(@NotNull UUID id, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record TrainingBatchCommand(@NotBlank @Size(max = 20) String action,
                                       @NotEmpty @Size(max = 100) List<@Valid TrainingBatchItem> items,
                                       @Size(max = 80) String reasonCode,
                                       @Size(max = 1000) String comment) {}
    public record TrainingBatchResult(int processed, List<TransitionResult> results) {}

    public record TeamMemberWrite(@NotNull UUID personId, @NotBlank @Size(max = 120) String duty,
                                  boolean leader, @PositiveOrZero int sortOrder) {}
    public record TeamMemberView(UUID personId, String personName, String duty, boolean leader, int sortOrder) {}
    public record TeamView(UUID id, String name, String remark, String state, long version,
                           List<TeamMemberView> members, long memberCount, String leaderName,
                           OffsetDateTime updatedAt) {}
    public record TeamWrite(@NotBlank @Size(max = 200) String name, @Size(max = 1000) String remark,
                            @NotEmpty @Size(max = 100) List<@Valid TeamMemberWrite> members,
                            @PositiveOrZero Long expectedVersion) {}

    public record TitleView(UUID id, UUID personId, String personName, LocalDate issuedOn,
                            String certificateNumber, String specialtyName, String issuingAuthority,
                            String titleLevel, String specialtyCategory, UUID attachmentFileId,
                            String attachmentFileName, String remark, String state, long version,
                            OffsetDateTime updatedAt) {}
    public record TitleWrite(@NotNull UUID personId, @NotNull LocalDate issuedOn,
                             @NotBlank @Size(max = 120) String certificateNumber,
                             @NotBlank @Size(max = 200) String specialtyName,
                             @NotBlank @Size(max = 200) String issuingAuthority,
                             @NotBlank String titleLevel, @NotBlank String specialtyCategory,
                             UUID attachmentFileId, @Size(max = 1000) String remark,
                             @PositiveOrZero Long expectedVersion) {}
    public record TitleBatchItem(@NotNull UUID id, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record TitleBatchCommand(@NotBlank @Size(max = 20) String action,
                                    @NotEmpty @Size(max = 100) List<@Valid TitleBatchItem> items,
                                    @Size(max = 80) String reasonCode,
                                    @Size(max = 1000) String comment) {}
    public record TitleBatchResult(int processed, List<TransitionResult> results) {}

    public record OrganizationQualificationView(UUID id, String certificateNumber, LocalDate issuedOn,
                                                LocalDate validUntil, String issuingAuthority, String testingScope,
                                                List<String> qualificationDetails, UUID originalFileId,
                                                String originalFileName, UUID copyFileId, String copyFileName,
                                                String remark, String state, long version, OffsetDateTime updatedAt) {}
    public record OrganizationQualificationWrite(@NotBlank @Size(max = 160) String certificateNumber,
                                                 @NotNull LocalDate issuedOn, @NotNull LocalDate validUntil,
                                                 @NotBlank @Size(max = 240) String issuingAuthority,
                                                 @NotBlank @Size(max = 1000) String testingScope,
                                                 @NotEmpty @Size(max = 10) List<@NotBlank String> qualificationDetails,
                                                 UUID originalFileId, UUID copyFileId,
                                                 @Size(max = 1000) String remark,
                                                 @PositiveOrZero Long expectedVersion) {}
    public record OrganizationQualificationBatchItem(@NotNull UUID id,
                                                       @NotNull @PositiveOrZero Long expectedVersion) {}
    public record OrganizationQualificationBatchCommand(
            @NotBlank @Size(max = 20) String action,
            @NotEmpty @Size(max = 100) List<@Valid OrganizationQualificationBatchItem> items,
            @Size(max = 80) String reasonCode,
            @Size(max = 1000) String comment) {}
    public record OrganizationQualificationBatchResult(int processed, List<TransitionResult> results) {}

    public record MetrologyQualificationView(UUID id, String certificateNumber, LocalDate issuedOn,
                                             LocalDate validUntil, String issuingAuthority,
                                             UUID certificateFileId, String certificateFileName,
                                             UUID otherFileId, String otherFileName, String remark,
                                             String state, long version, OffsetDateTime updatedAt) {}
    public record MetrologyQualificationWrite(@NotBlank @Size(max = 160) String certificateNumber,
                                              @NotNull LocalDate issuedOn, @NotNull LocalDate validUntil,
                                              @NotBlank @Size(max = 240) String issuingAuthority,
                                              @NotNull UUID certificateFileId, UUID otherFileId,
                                              @Size(max = 1000) String remark,
                                              @PositiveOrZero Long expectedVersion) {}
    public record MetrologyQualificationBatchItem(@NotNull UUID id,
                                                   @NotNull @PositiveOrZero Long expectedVersion) {}
    public record MetrologyQualificationBatchCommand(
            @NotBlank @Size(max = 20) String action,
            @NotEmpty @Size(max = 100) List<@Valid MetrologyQualificationBatchItem> items,
            @Size(max = 80) String reasonCode,
            @Size(max = 1000) String comment) {}
    public record MetrologyQualificationBatchResult(int processed, List<TransitionResult> results) {}

    public record ShelfView(UUID id, UUID parentId, String parentShelfNumber, String shelfNumber,
                            int sortOrder, boolean active, long version, int childCount, int activeChildCount,
                            List<ShelfView> children, OffsetDateTime updatedAt) {}
    public record ShelfWrite(@NotBlank @Size(max = 80) String shelfNumber,
                             @PositiveOrZero int sortOrder, UUID parentId,
                             @PositiveOrZero Long expectedVersion) {}
    public record ShelfQrDownload(@NotEmpty @Size(max = 100) List<@NotNull UUID> ids) {}

    public record LegacyRecordCount(String moduleKey, long count) {}

    @GetMapping("/legacy-records/{moduleKey}/count")
    public LegacyRecordCount legacyRecordCount(@PathVariable String moduleKey) {
        if (!Set.of("training", "project-groups", "titles", "organization-qualifications",
                "metrology-qualifications", "shelves").contains(moduleKey)) {
            throw ApiException.notFound("FOUNDATION_MODULE_NOT_FOUND", "基础数据模块不存在");
        }
        long count = jdbc.sql("""
                SELECT count(*) FROM catalog_entry WHERE module_key=:module AND organization_id=:organization
                """).param("module", moduleKey).param("organization", actor.require().organizationId())
                .query(Long.class).single();
        return new LegacyRecordCount(moduleKey, count);
    }

    @GetMapping("/training")
    public CursorPage<TrainingView> training(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "") @Size(max = 200) String organizer,
            @RequestParam(defaultValue = "") @Size(max = 80) String trainingMethod,
            @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        AuthenticatedUser user = actor.require();
        PageCursor pageCursor = cursor(cursor);
        String pattern = pattern(q);
        String organizerPattern = pattern(organizer);
        String method = clean(trainingMethod);
        if (method != null && !TRAINING_METHODS.contains(method)) {
            throw invalid("TRAINING_METHOD_INVALID", "培训方式不在受控选项中");
        }
        List<TrainingView> fetched = bindScope(jdbc.sql("""
                SELECT t.id, t.person_id, p.name person_name, d.id department_id, d.name department_name,
                  t.start_date, t.end_date, t.hours, t.organizer, t.training_method, t.assessment_result,
                  t.location, t.content, t.certificate_file_id, f.original_name certificate_file_name,
                  t.remark, t.state, t.version, t.updated_at
                FROM training_record t
                JOIN person p ON p.id=t.person_id
                LEFT JOIN department d ON d.id=p.department_id
                LEFT JOIN stored_file f ON f.id=t.certificate_file_id
                WHERE t.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                  AND (CAST(:departmentId AS uuid) IS NULL OR d.id=CAST(:departmentId AS uuid))
                  AND t.start_date <= :endDate AND t.end_date >= :startDate
                  AND (p.name ILIKE :pattern ESCAPE '\\' OR t.content ILIKE :pattern ESCAPE '\\')
                  AND t.organizer ILIKE :organizer ESCAPE '\\'
                  AND (:method='' OR t.training_method=:method)
                  AND (t.updated_at,t.id) < (:cursorAt,:cursorId)
                ORDER BY t.updated_at DESC, t.id DESC LIMIT :fetchLimit
                """), user).param("departmentId", departmentId)
                .param("startDate", startDate == null ? LocalDate.of(1900, 1, 1) : startDate)
                .param("endDate", endDate == null ? LocalDate.of(9999, 12, 31) : endDate)
                .param("pattern", pattern).param("organizer", organizerPattern).param("method", method == null ? "" : method)
                .param("cursorAt", pageCursor.updatedAt()).param("cursorId", pageCursor.id())
                .param("fetchLimit", limit + 1).query((rs, row) -> new TrainingView(
                        rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("person_name"),
                        rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getBigDecimal("hours"), rs.getString("organizer"), rs.getString("training_method"),
                        rs.getString("assessment_result"), rs.getString("location"), rs.getString("content"),
                        rs.getObject("certificate_file_id", UUID.class), rs.getString("certificate_file_name"),
                        rs.getString("remark"), rs.getString("state"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
        long total = bindScope(jdbc.sql("""
                SELECT count(*) FROM training_record t JOIN person p ON p.id=t.person_id
                LEFT JOIN department d ON d.id=p.department_id
                WHERE t.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                  AND (CAST(:departmentId AS uuid) IS NULL OR d.id=CAST(:departmentId AS uuid))
                  AND t.start_date <= :endDate AND t.end_date >= :startDate
                  AND (p.name ILIKE :pattern ESCAPE '\\' OR t.content ILIKE :pattern ESCAPE '\\')
                  AND t.organizer ILIKE :organizer ESCAPE '\\' AND (:method='' OR t.training_method=:method)
                """), user).param("departmentId", departmentId)
                .param("startDate", startDate == null ? LocalDate.of(1900, 1, 1) : startDate)
                .param("endDate", endDate == null ? LocalDate.of(9999, 12, 31) : endDate)
                .param("pattern", pattern).param("organizer", organizerPattern).param("method", method == null ? "" : method)
                .query(Long.class).single();
        return page(fetched, limit, total, item -> new PageCursor(item.updatedAt(), item.id()));
    }

    @GetMapping("/training/options")
    public TrainingOptions trainingOptions() {
        AuthenticatedUser user = actor.require();
        List<TrainingPersonOption> people = bindScope(jdbc.sql("""
                SELECT p.id,p.employee_number,p.name,p.department_id,d.name department_name
                FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND p.active
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                ORDER BY p.name,p.employee_number
                """), user).query((rs, row) -> new TrainingPersonOption(rs.getObject("id", UUID.class),
                rs.getString("employee_number"), rs.getString("name"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"))).list();
        List<TrainingDepartmentOption> departments = people.stream()
                .map(person -> new TrainingDepartmentOption(person.departmentId(), person.departmentName()))
                .distinct().sorted(java.util.Comparator.comparing(TrainingDepartmentOption::name)).toList();
        return new TrainingOptions(people, departments, TRAINING_METHODS, TRAINING_ASSESSMENT_RESULTS);
    }

    @GetMapping("/training/{id}")
    public TrainingView trainingDetail(@PathVariable UUID id) {
        return training(id);
    }

    @PostMapping("/training")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TrainingView createTraining(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TrainingWrite body) {
        return idempotency.execute(key, "foundation-training-create", body, TrainingView.class,
                () -> createTrainingInternal(body));
    }

    private TrainingView createTrainingInternal(TrainingWrite body) {
        validateTraining(body, null);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO training_record(id, organization_id, person_id, start_date, end_date, hours,
                  organizer, training_method, assessment_result, location, content, certificate_file_id,
                  remark, created_by)
                VALUES (:id,:organization,:person,:startDate,:endDate,:hours,:organizer,:method,:assessment,
                  :location,:content,:file,:remark,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("person", body.personId())
                .param("startDate", body.startDate()).param("endDate", body.endDate()).param("hours", body.hours())
                .param("organizer", clean(body.organizer())).param("method", clean(body.trainingMethod()))
                .param("assessment", clean(body.assessmentResult())).param("location", clean(body.location()))
                .param("content", clean(body.content())).param("file", body.certificateFileId())
                .param("remark", clean(body.remark())).param("actor", user.id()).update();
        TrainingView created = training(id);
        audit.record("TRAINING_CREATED", "TRAINING", id, null, created, null, body.remark());
        return created;
    }

    @PutMapping("/training/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public TrainingView updateTraining(@PathVariable UUID id, @Valid @RequestBody TrainingWrite body) {
        requireVersion(body.expectedVersion());
        TrainingView before = training(id);
        if (!before.state().equals("ACTIVE")) throw blocked("TRAINING_VOID", "已作废培训记录不可修改", before.version());
        validateTraining(body, before.certificateFileId());
        int changed = jdbc.sql("""
                UPDATE training_record SET person_id=:person, start_date=:startDate, end_date=:endDate,
                  hours=:hours, organizer=:organizer, training_method=:method, assessment_result=:assessment,
                  location=:location, content=:content, certificate_file_id=:file, remark=:remark,
                  version=version+1, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("person", body.personId()).param("startDate", body.startDate())
                .param("endDate", body.endDate()).param("hours", body.hours()).param("organizer", clean(body.organizer()))
                .param("method", clean(body.trainingMethod())).param("assessment", clean(body.assessmentResult()))
                .param("location", clean(body.location())).param("content", clean(body.content()))
                .param("file", body.certificateFileId()).param("remark", clean(body.remark())).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("培训记录", "training_record", id);
        TrainingView after = training(id);
        audit.record("TRAINING_UPDATED", "TRAINING", id, before, after, null, body.remark());
        return after;
    }

    @PostMapping("/training/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionTraining(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-training-transition:" + id, body, TransitionResult.class,
                () -> transitionTrainingInternal(id, body));
    }

    @PostMapping("/training/batch-transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TrainingBatchResult transitionTrainingBatch(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody TrainingBatchCommand body) {
        return idempotency.execute(key, "foundation-training-batch-transition", body,
                new TypeReference<TrainingBatchResult>() {}, () -> {
                    Set<UUID> unique = new HashSet<>();
                    for (TrainingBatchItem item : body.items()) {
                        if (!unique.add(item.id())) throw invalid("TRAINING_BATCH_DUPLICATE", "批量操作不能包含重复培训记录");
                    }
                    List<TransitionResult> results = body.items().stream()
                            .map(item -> transitionTrainingInternal(item.id(), new TransitionCommand(body.action(),
                                    item.expectedVersion(), body.reasonCode(), body.comment())))
                            .toList();
                    return new TrainingBatchResult(results.size(), results);
                });
    }

    @GetMapping("/project-teams")
    public CursorPage<TeamView> teams(@RequestParam(defaultValue = "") @Size(max = 100) String q,
                                      @RequestParam(defaultValue = "") @Size(max = 20) String state,
                                      @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        PageCursor pageCursor = cursor(cursor);
        String normalizedState = clean(state) == null ? "" : clean(state).toUpperCase();
        if (!normalizedState.isEmpty() && !PROJECT_TEAM_STATES.contains(normalizedState)) {
            throw invalid("PROJECT_TEAM_STATE_INVALID", "项目组状态筛选无效");
        }
        AuthenticatedUser user = actor.require();
        List<TeamView> fetched = bindScope(jdbc.sql("""
                SELECT t.id, t.name, t.remark, t.state, t.version, t.updated_at,
                  count(m.person_id) member_count,
                  max(p.name) FILTER (WHERE m.leader) leader_name,
                  coalesce(jsonb_agg(jsonb_build_object('personId',m.person_id,'personName',p.name,
                    'duty',m.duty,'leader',m.leader,'sortOrder',m.sort_order)
                    ORDER BY m.sort_order,m.person_id) FILTER (WHERE m.person_id IS NOT NULL),'[]'::jsonb)::text members
                FROM project_team t LEFT JOIN project_team_member m ON m.team_id=t.id
                LEFT JOIN person p ON p.id=m.person_id
                WHERE t.organization_id=:organization AND t.name ILIKE :pattern ESCAPE '\\'
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      JOIN person visible_person ON visible_person.id=visible_member.person_id
                      WHERE visible_member.team_id=t.id AND visible_person.department_id=:actorDepartment))
                    OR (:scope IN ('SELF','PROJECT') AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      WHERE visible_member.team_id=t.id AND visible_member.person_id=:actorPerson)))
                  AND (:state='' OR t.state=:state) AND (t.updated_at,t.id) < (:cursorAt,:cursorId)
                GROUP BY t.id ORDER BY t.updated_at DESC,t.id DESC LIMIT :fetchLimit
                """), user).param("pattern", pattern(q))
                .param("state", normalizedState).param("cursorAt", pageCursor.updatedAt()).param("cursorId", pageCursor.id())
                .param("fetchLimit", limit + 1).query((rs, row) -> new TeamView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("remark"),
                        rs.getString("state"), rs.getLong("version"), members(rs.getString("members")),
                        rs.getLong("member_count"), rs.getString("leader_name"),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
        long total = bindScope(jdbc.sql("""
                SELECT count(*) FROM project_team WHERE organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      JOIN person visible_person ON visible_person.id=visible_member.person_id
                      WHERE visible_member.team_id=project_team.id AND visible_person.department_id=:actorDepartment))
                    OR (:scope IN ('SELF','PROJECT') AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      WHERE visible_member.team_id=project_team.id AND visible_member.person_id=:actorPerson)))
                  AND name ILIKE :pattern ESCAPE '\\' AND (:state='' OR state=:state)
                """), user).param("pattern", pattern(q))
                .param("state", normalizedState).query(Long.class).single();
        return page(fetched, limit, total, item -> new PageCursor(item.updatedAt(), item.id()));
    }

    @PostMapping("/project-teams")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TeamView createTeam(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TeamWrite body) {
        return idempotency.execute(key, "foundation-project-team-create", body, TeamView.class,
                () -> createTeamInternal(body));
    }

    private TeamView createTeamInternal(TeamWrite body) {
        validateMembers(body.members());
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("INSERT INTO project_team(id,organization_id,name,remark,created_by) VALUES (:id,:organization,:name,:remark,:actor)")
                .param("id", id).param("organization", user.organizationId()).param("name", clean(body.name()))
                .param("remark", clean(body.remark())).param("actor", user.id()).update();
        replaceMembers(id, body.members());
        TeamView created = team(id);
        audit.record("PROJECT_TEAM_CREATED", "PROJECT_TEAM", id, null, created, null, body.remark());
        return created;
    }

    @PutMapping("/project-teams/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public TeamView updateTeam(@PathVariable UUID id, @Valid @RequestBody TeamWrite body) {
        requireVersion(body.expectedVersion());
        validateMembers(body.members());
        TeamView before = team(id);
        int changed = jdbc.sql("""
                UPDATE project_team SET name=:name,remark=:remark,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("name", clean(body.name())).param("remark", clean(body.remark())).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("项目组", "project_team", id);
        replaceMembers(id, body.members());
        TeamView after = team(id);
        audit.record("PROJECT_TEAM_UPDATED", "PROJECT_TEAM", id, before, after, null, body.remark());
        return after;
    }

    @PostMapping("/project-teams/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionTeam(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-project-team-transition:" + id, body, TransitionResult.class, () -> {
            team(id);
            if (body.action().trim().equalsIgnoreCase("ACTIVATE")) validateActiveTeam(id);
            return transition("project_team", "PROJECT_TEAM", id, body,
                    Map.of("ACTIVE", Map.of("DEACTIVATE", "INACTIVE"), "INACTIVE", Map.of("ACTIVATE", "ACTIVE")));
        });
    }

    @GetMapping("/titles")
    public CursorPage<TitleView> titles(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "") @Size(max = 100) String personName,
            @RequestParam(defaultValue = "") @Size(max = 200) String specialtyName,
            @RequestParam(defaultValue = "") @Size(max = 200) String issuingAuthority,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "") @Size(max = 40) String category,
            @RequestParam(defaultValue = "") @Size(max = 40) String level,
            @RequestParam(defaultValue = "") @Size(max = 20) String state,
            @RequestParam(required = false) Boolean hasFile,
            @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        String normalizedCategory = normalized(category);
        String normalizedLevel = normalized(level);
        String normalizedState = normalized(state);
        if (!normalizedCategory.isEmpty() && !TITLE_CATEGORIES.contains(normalizedCategory)) {
            throw invalid("TITLE_CATEGORY_INVALID", "专业类型筛选无效");
        }
        if (!normalizedLevel.isEmpty() && !TITLE_LEVELS.contains(normalizedLevel)) {
            throw invalid("TITLE_LEVEL_INVALID", "职称等级筛选无效");
        }
        if (!normalizedState.isEmpty() && !TITLE_STATES.contains(normalizedState)) {
            throw invalid("TITLE_STATE_INVALID", "人员职称状态筛选无效");
        }
        if (issuedFrom != null && issuedTo != null && issuedTo.isBefore(issuedFrom)) {
            throw invalid("TITLE_DATE_RANGE_INVALID", "签发结束日期不能早于开始日期");
        }
        PageCursor pageCursor = cursor(cursor);
        String pattern = pattern(q);
        AuthenticatedUser user = actor.require();
        List<TitleView> fetched = bindScope(jdbc.sql("""
                SELECT t.id,t.person_id,p.name person_name,t.issued_on,t.certificate_number,t.specialty_name,
                  t.issuing_authority,t.title_level,t.specialty_category,t.attachment_file_id,
                  f.original_name attachment_file_name,t.remark,t.state,t.version,t.updated_at
                FROM person_title_record t JOIN person p ON p.id=t.person_id
                LEFT JOIN stored_file f ON f.id=t.attachment_file_id
                WHERE t.organization_id=:organization AND t.issued_on BETWEEN :issuedFrom AND :issuedTo
                  AND (:category='' OR t.specialty_category=:category) AND (:level='' OR t.title_level=:level)
                  AND (:state='' OR t.state=:state)
                  AND (CAST(:hasFile AS boolean) IS NULL
                    OR (t.attachment_file_id IS NOT NULL)=CAST(:hasFile AS boolean))
                  AND (p.name ILIKE :pattern ESCAPE '\\' OR t.certificate_number ILIKE :pattern ESCAPE '\\'
                    OR t.specialty_name ILIKE :pattern ESCAPE '\\' OR t.issuing_authority ILIKE :pattern ESCAPE '\\')
                  AND p.name ILIKE :personPattern ESCAPE '\\'
                  AND t.specialty_name ILIKE :specialtyPattern ESCAPE '\\'
                  AND t.issuing_authority ILIKE :authorityPattern ESCAPE '\\'
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                  AND (t.updated_at,t.id) < (:cursorAt,:cursorId)
                ORDER BY t.updated_at DESC,t.id DESC LIMIT :fetchLimit
                """), user)
                .param("issuedFrom", issuedFrom == null ? LocalDate.of(1900, 1, 1) : issuedFrom)
                .param("issuedTo", issuedTo == null ? LocalDate.of(9999, 12, 31) : issuedTo)
                .param("category", normalizedCategory).param("level", normalizedLevel)
                .param("state", normalizedState).param("hasFile", hasFile)
                .param("pattern", pattern).param("personPattern", pattern(personName))
                .param("specialtyPattern", pattern(specialtyName)).param("authorityPattern", pattern(issuingAuthority))
                .param("cursorAt", pageCursor.updatedAt()).param("cursorId", pageCursor.id())
                .param("fetchLimit", limit + 1).query((rs, row) -> titleView(rs)).list();
        long total = bindScope(jdbc.sql("""
                SELECT count(*) FROM person_title_record t JOIN person p ON p.id=t.person_id
                WHERE t.organization_id=:organization AND t.issued_on BETWEEN :issuedFrom AND :issuedTo
                  AND (:category='' OR t.specialty_category=:category) AND (:level='' OR t.title_level=:level)
                  AND (:state='' OR t.state=:state)
                  AND (CAST(:hasFile AS boolean) IS NULL
                    OR (t.attachment_file_id IS NOT NULL)=CAST(:hasFile AS boolean))
                  AND (p.name ILIKE :pattern ESCAPE '\\' OR t.certificate_number ILIKE :pattern ESCAPE '\\'
                    OR t.specialty_name ILIKE :pattern ESCAPE '\\' OR t.issuing_authority ILIKE :pattern ESCAPE '\\')
                  AND p.name ILIKE :personPattern ESCAPE '\\'
                  AND t.specialty_name ILIKE :specialtyPattern ESCAPE '\\'
                  AND t.issuing_authority ILIKE :authorityPattern ESCAPE '\\'
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                """), user)
                .param("issuedFrom", issuedFrom == null ? LocalDate.of(1900, 1, 1) : issuedFrom)
                .param("issuedTo", issuedTo == null ? LocalDate.of(9999, 12, 31) : issuedTo)
                .param("category", normalizedCategory).param("level", normalizedLevel)
                .param("state", normalizedState).param("hasFile", hasFile)
                .param("pattern", pattern).param("personPattern", pattern(personName))
                .param("specialtyPattern", pattern(specialtyName)).param("authorityPattern", pattern(issuingAuthority))
                .query(Long.class).single();
        return page(fetched, limit, total, item -> new PageCursor(item.updatedAt(), item.id()));
    }

    @GetMapping("/titles/{id}")
    public TitleView titleDetail(@PathVariable UUID id) {
        return title(id);
    }

    @PostMapping("/titles")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TitleView createTitle(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TitleWrite body) {
        return idempotency.execute(key, "foundation-title-create", body, TitleView.class,
                () -> createTitleInternal(body));
    }

    private TitleView createTitleInternal(TitleWrite body) {
        validateTitle(body, null);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO person_title_record(id,organization_id,person_id,issued_on,certificate_number,
                  specialty_name,issuing_authority,title_level,specialty_category,attachment_file_id,remark,created_by)
                VALUES (:id,:organization,:person,:issuedOn,:number,:specialty,:authority,:level,:category,:file,:remark,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("person", body.personId())
                .param("issuedOn", body.issuedOn()).param("number", clean(body.certificateNumber()))
                .param("specialty", clean(body.specialtyName())).param("authority", clean(body.issuingAuthority()))
                .param("level", body.titleLevel()).param("category", body.specialtyCategory())
                .param("file", body.attachmentFileId()).param("remark", clean(body.remark())).param("actor", user.id()).update();
        TitleView created = title(id);
        audit.record("PERSON_TITLE_CREATED", "PERSON_TITLE", id, null, created, null, body.remark());
        return created;
    }

    @PutMapping("/titles/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public TitleView updateTitle(@PathVariable UUID id, @Valid @RequestBody TitleWrite body) {
        requireVersion(body.expectedVersion());
        TitleView before = title(id);
        validateTitle(body, before.attachmentFileId());
        requireDraft(before.state(), before.version(), "职称");
        int changed = jdbc.sql("""
                UPDATE person_title_record SET person_id=:person,issued_on=:issuedOn,certificate_number=:number,
                  specialty_name=:specialty,issuing_authority=:authority,title_level=:level,
                  specialty_category=:category,attachment_file_id=:file,remark=:remark,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("person", body.personId()).param("issuedOn", body.issuedOn())
                .param("number", clean(body.certificateNumber())).param("specialty", clean(body.specialtyName()))
                .param("authority", clean(body.issuingAuthority())).param("level", body.titleLevel())
                .param("category", body.specialtyCategory()).param("file", body.attachmentFileId())
                .param("remark", clean(body.remark())).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("职称", "person_title_record", id);
        TitleView after = title(id);
        audit.record("PERSON_TITLE_UPDATED", "PERSON_TITLE", id, before, after, null, body.remark());
        return after;
    }

    @PostMapping("/titles/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionTitle(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-title-transition:" + id, body, TransitionResult.class,
                () -> transitionTitleInternal(id, body));
    }

    @PostMapping("/titles/batch-transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TitleBatchResult transitionTitleBatch(@RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody TitleBatchCommand body) {
        return idempotency.execute(key, "foundation-title-batch-transition", body,
                new TypeReference<TitleBatchResult>() {}, () -> {
                    Set<UUID> unique = new HashSet<>();
                    for (TitleBatchItem item : body.items()) {
                        if (!unique.add(item.id())) throw invalid("TITLE_BATCH_DUPLICATE", "批量操作不能包含重复职称记录");
                    }
                    List<TransitionResult> results = body.items().stream()
                            .map(item -> transitionTitleInternal(item.id(), new TransitionCommand(body.action(),
                                    item.expectedVersion(), body.reasonCode(), body.comment())))
                            .toList();
                    return new TitleBatchResult(results.size(), results);
                });
    }

    @PostMapping("/titles/import-template")
    @PreAuthorize("hasAuthority('PERSON_IMPORT')")
    public StoredFileService.View titleImportTemplate(@RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "foundation-title-import-template", Map.of(),
                StoredFileService.View.class, titleTransfers::createTemplate);
    }

    @PostMapping("/titles/imports")
    @PreAuthorize("hasAuthority('PERSON_IMPORT') and hasAuthority('MODULE_WRITE')")
    public PersonnelTitleTransferService.ImportResult importTitles(
            @RequestHeader("Idempotency-Key") String key, @RequestParam MultipartFile file) {
        byte[] content = titleTransfers.readUpload(file);
        PersonnelTitleTransferService.Fingerprint fingerprint = titleTransfers.fingerprint(content);
        return idempotency.execute(key, "foundation-title-import", fingerprint,
                PersonnelTitleTransferService.ImportResult.class,
                () -> titleTransfers.importWorkbook(content, row -> createTitleInternal(new TitleWrite(
                        row.personId(), row.issuedOn(), row.certificateNumber(), row.specialtyName(),
                        row.issuingAuthority(), row.titleLevel(), row.specialtyCategory(), null,
                        row.remark(), null)).id()));
    }

    @GetMapping("/organization-qualifications")
    public CursorPage<OrganizationQualificationView> organizationQualifications(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "") @Size(max = 160) String certificateNumber,
            @RequestParam(defaultValue = "") @Size(max = 20) String state,
            @RequestParam(defaultValue = "") @Size(max = 40) String detail,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        String normalizedState = normalized(state);
        String normalizedDetail = normalized(detail);
        if (!normalizedState.isEmpty() && !TITLE_STATES.contains(normalizedState)) {
            throw invalid("ORGANIZATION_QUALIFICATION_STATE_INVALID", "单位资质提交状态筛选无效");
        }
        if (!normalizedDetail.isEmpty() && !QUALIFICATION_DETAILS.contains(normalizedDetail)) {
            throw invalid("QUALIFICATION_DETAIL_INVALID", "资质详情筛选无效");
        }
        if (issuedFrom != null && issuedTo != null && issuedTo.isBefore(issuedFrom)) {
            throw invalid("QUALIFICATION_ISSUED_RANGE_INVALID", "签发日期结束时间不能早于开始时间");
        }
        PageCursor pageCursor = cursor(cursor);
        List<OrganizationQualificationView> fetched = jdbc.sql("""
                SELECT q.id,q.certificate_number,q.issued_on,q.valid_until,q.issuing_authority,q.testing_scope,
                  q.qualification_details::text qualification_details,q.original_file_id,fo.original_name original_file_name,
                  q.copy_file_id,fc.original_name copy_file_name,q.remark,q.state,q.version,q.updated_at
                FROM organization_qualification_record q
                LEFT JOIN stored_file fo ON fo.id=q.original_file_id LEFT JOIN stored_file fc ON fc.id=q.copy_file_id
                WHERE q.organization_id=:organization AND (:state='' OR q.state=:state)
                  AND (:detail='' OR jsonb_exists(q.qualification_details, :detail))
                  AND (CAST(:issuedFrom AS date) IS NULL OR q.issued_on>=CAST(:issuedFrom AS date))
                  AND (CAST(:issuedTo AS date) IS NULL OR q.issued_on<=CAST(:issuedTo AS date))
                  AND q.certificate_number ILIKE :certificatePattern ESCAPE '\\'
                  AND (q.certificate_number ILIKE :pattern ESCAPE '\\' OR q.issuing_authority ILIKE :pattern ESCAPE '\\'
                    OR q.testing_scope ILIKE :pattern ESCAPE '\\')
                  AND (q.updated_at,q.id) < (:cursorAt,:cursorId)
                ORDER BY q.updated_at DESC,q.id DESC LIMIT :fetchLimit
                """).param("organization", actor.require().organizationId()).param("state", normalizedState)
                .param("detail", normalizedDetail).param("issuedFrom", issuedFrom).param("issuedTo", issuedTo)
                .param("certificatePattern", pattern(certificateNumber)).param("pattern", pattern(q))
                .param("cursorAt", pageCursor.updatedAt()).param("cursorId", pageCursor.id())
                .param("fetchLimit", limit + 1).query((rs, row) -> organizationQualificationView(rs)).list();
        long total = jdbc.sql("""
                SELECT count(*) FROM organization_qualification_record q WHERE q.organization_id=:organization
                  AND (:state='' OR q.state=:state) AND (:detail='' OR jsonb_exists(q.qualification_details, :detail))
                  AND (CAST(:issuedFrom AS date) IS NULL OR q.issued_on>=CAST(:issuedFrom AS date))
                  AND (CAST(:issuedTo AS date) IS NULL OR q.issued_on<=CAST(:issuedTo AS date))
                  AND q.certificate_number ILIKE :certificatePattern ESCAPE '\\'
                  AND (q.certificate_number ILIKE :pattern ESCAPE '\\' OR q.issuing_authority ILIKE :pattern ESCAPE '\\'
                    OR q.testing_scope ILIKE :pattern ESCAPE '\\')
                """).param("organization", actor.require().organizationId()).param("state", normalizedState)
                .param("detail", normalizedDetail).param("issuedFrom", issuedFrom).param("issuedTo", issuedTo)
                .param("certificatePattern", pattern(certificateNumber)).param("pattern", pattern(q))
                .query(Long.class).single();
        return page(fetched, limit, total, item -> new PageCursor(item.updatedAt(), item.id()));
    }

    @GetMapping("/organization-qualifications/{id}")
    public OrganizationQualificationView organizationQualificationDetail(@PathVariable UUID id) {
        return organizationQualification(id);
    }

    @PostMapping("/organization-qualifications")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public OrganizationQualificationView createOrganizationQualification(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationQualificationWrite body) {
        return idempotency.execute(key, "foundation-organization-qualification-create", body,
                OrganizationQualificationView.class, () -> createOrganizationQualificationInternal(body));
    }

    private OrganizationQualificationView createOrganizationQualificationInternal(OrganizationQualificationWrite body) {
        validateOrganizationQualification(body, null, null);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO organization_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,testing_scope,qualification_details,original_file_id,copy_file_id,
                  remark,created_by)
                VALUES (:id,:organization,:number,:issuedOn,:validUntil,:authority,:scope,CAST(:details AS jsonb),
                  :originalFile,:copyFile,:remark,:actor)
                """).param("id", id).param("organization", user.organizationId())
                .param("number", clean(body.certificateNumber())).param("issuedOn", body.issuedOn())
                .param("validUntil", body.validUntil()).param("authority", clean(body.issuingAuthority()))
                .param("scope", clean(body.testingScope())).param("details", encode(body.qualificationDetails()))
                .param("originalFile", body.originalFileId()).param("copyFile", body.copyFileId())
                .param("remark", clean(body.remark())).param("actor", user.id()).update();
        OrganizationQualificationView created = organizationQualification(id);
        audit.record("ORGANIZATION_QUALIFICATION_CREATED", "ORGANIZATION_QUALIFICATION", id, null, created, null, body.remark());
        return created;
    }

    @PutMapping("/organization-qualifications/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public OrganizationQualificationView updateOrganizationQualification(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationQualificationWrite body) {
        return idempotency.execute(key, "foundation-organization-qualification-update:" + id, body,
                OrganizationQualificationView.class, () -> updateOrganizationQualificationInternal(id, body));
    }

    private OrganizationQualificationView updateOrganizationQualificationInternal(
            UUID id, OrganizationQualificationWrite body) {
        requireVersion(body.expectedVersion());
        OrganizationQualificationView before = organizationQualification(id);
        requireDraft(before.state(), before.version(), "单位资质");
        validateOrganizationQualification(body, before.originalFileId(), before.copyFileId());
        int changed = jdbc.sql("""
                UPDATE organization_qualification_record SET certificate_number=:number,issued_on=:issuedOn,
                  valid_until=:validUntil,issuing_authority=:authority,testing_scope=:scope,
                  qualification_details=CAST(:details AS jsonb),original_file_id=:originalFile,copy_file_id=:copyFile,
                  remark=:remark,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("number", clean(body.certificateNumber())).param("issuedOn", body.issuedOn())
                .param("validUntil", body.validUntil()).param("authority", clean(body.issuingAuthority()))
                .param("scope", clean(body.testingScope())).param("details", encode(body.qualificationDetails()))
                .param("originalFile", body.originalFileId()).param("copyFile", body.copyFileId())
                .param("remark", clean(body.remark())).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("单位资质", "organization_qualification_record", id);
        OrganizationQualificationView after = organizationQualification(id);
        audit.record("ORGANIZATION_QUALIFICATION_UPDATED", "ORGANIZATION_QUALIFICATION", id, before, after, null, body.remark());
        return after;
    }

    @PostMapping("/organization-qualifications/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionOrganizationQualification(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-organization-qualification-transition:" + id, body,
                TransitionResult.class, () -> transition("organization_qualification_record",
                        "ORGANIZATION_QUALIFICATION", id, body, submittedTransitions()));
    }

    @PostMapping("/organization-qualifications/batch-transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public OrganizationQualificationBatchResult transitionOrganizationQualificationBatch(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationQualificationBatchCommand body) {
        return idempotency.execute(key, "foundation-organization-qualification-batch-transition", body,
                new TypeReference<OrganizationQualificationBatchResult>() {}, () -> {
                    Set<UUID> unique = new HashSet<>();
                    for (OrganizationQualificationBatchItem item : body.items()) {
                        if (!unique.add(item.id())) {
                            throw invalid("ORGANIZATION_QUALIFICATION_BATCH_DUPLICATE",
                                    "批量操作不能包含重复单位资质");
                        }
                    }
                    List<TransitionResult> results = body.items().stream()
                            .map(item -> transition("organization_qualification_record",
                                    "ORGANIZATION_QUALIFICATION", item.id(),
                                    new TransitionCommand(body.action(), item.expectedVersion(),
                                            body.reasonCode(), body.comment()), submittedTransitions()))
                            .toList();
                    return new OrganizationQualificationBatchResult(results.size(), results);
                });
    }

    @GetMapping("/metrology-qualifications")
    public CursorPage<MetrologyQualificationView> metrologyQualifications(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "") @Size(max = 160) String certificateNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String issuingAuthority,
            @RequestParam(defaultValue = "") @Size(max = 20) String state,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        String normalizedState = normalized(state);
        if (!normalizedState.isEmpty() && !TITLE_STATES.contains(normalizedState)) {
            throw invalid("METROLOGY_QUALIFICATION_STATE_INVALID", "计量资质提交状态筛选无效");
        }
        if (issuedFrom != null && issuedTo != null && issuedTo.isBefore(issuedFrom)) {
            throw invalid("METROLOGY_QUALIFICATION_ISSUED_RANGE_INVALID", "签发日期结束时间不能早于开始时间");
        }
        PageCursor pageCursor = cursor(cursor);
        List<MetrologyQualificationView> fetched = jdbc.sql("""
                SELECT q.id,q.certificate_number,q.issued_on,q.valid_until,q.issuing_authority,
                  q.certificate_file_id,fc.original_name certificate_file_name,q.other_file_id,
                  fo.original_name other_file_name,q.remark,q.state,q.version,q.updated_at
                FROM metrology_qualification_record q JOIN stored_file fc ON fc.id=q.certificate_file_id
                LEFT JOIN stored_file fo ON fo.id=q.other_file_id
                WHERE q.organization_id=:organization AND (:state='' OR q.state=:state)
                  AND (CAST(:issuedFrom AS date) IS NULL OR q.issued_on>=CAST(:issuedFrom AS date))
                  AND (CAST(:issuedTo AS date) IS NULL OR q.issued_on<=CAST(:issuedTo AS date))
                  AND q.certificate_number ILIKE :certificatePattern ESCAPE '\\'
                  AND q.issuing_authority ILIKE :authorityPattern ESCAPE '\\'
                  AND (q.certificate_number ILIKE :pattern ESCAPE '\\' OR q.issuing_authority ILIKE :pattern ESCAPE '\\')
                  AND (q.updated_at,q.id) < (:cursorAt,:cursorId)
                ORDER BY q.updated_at DESC,q.id DESC LIMIT :fetchLimit
                """).param("organization", actor.require().organizationId()).param("state", normalizedState)
                .param("issuedFrom", issuedFrom).param("issuedTo", issuedTo)
                .param("certificatePattern", pattern(certificateNumber))
                .param("authorityPattern", pattern(issuingAuthority)).param("pattern", pattern(q))
                .param("cursorAt", pageCursor.updatedAt()).param("cursorId", pageCursor.id())
                .param("fetchLimit", limit + 1).query((rs, row) -> metrologyQualificationView(rs)).list();
        long total = jdbc.sql("""
                SELECT count(*) FROM metrology_qualification_record q WHERE q.organization_id=:organization
                  AND (:state='' OR q.state=:state)
                  AND (CAST(:issuedFrom AS date) IS NULL OR q.issued_on>=CAST(:issuedFrom AS date))
                  AND (CAST(:issuedTo AS date) IS NULL OR q.issued_on<=CAST(:issuedTo AS date))
                  AND q.certificate_number ILIKE :certificatePattern ESCAPE '\\'
                  AND q.issuing_authority ILIKE :authorityPattern ESCAPE '\\'
                  AND (q.certificate_number ILIKE :pattern ESCAPE '\\' OR q.issuing_authority ILIKE :pattern ESCAPE '\\')
                """).param("organization", actor.require().organizationId()).param("state", normalizedState)
                .param("issuedFrom", issuedFrom).param("issuedTo", issuedTo)
                .param("certificatePattern", pattern(certificateNumber))
                .param("authorityPattern", pattern(issuingAuthority)).param("pattern", pattern(q))
                .query(Long.class).single();
        return page(fetched, limit, total, item -> new PageCursor(item.updatedAt(), item.id()));
    }

    @GetMapping("/metrology-qualifications/{id}")
    public MetrologyQualificationView metrologyQualificationDetail(@PathVariable UUID id) {
        return metrologyQualification(id);
    }

    @PostMapping("/metrology-qualifications")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public MetrologyQualificationView createMetrologyQualification(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MetrologyQualificationWrite body) {
        return idempotency.execute(key, "foundation-metrology-qualification-create", body,
                MetrologyQualificationView.class, () -> createMetrologyQualificationInternal(body));
    }

    private MetrologyQualificationView createMetrologyQualificationInternal(MetrologyQualificationWrite body) {
        validateMetrologyQualification(body, null, null);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO metrology_qualification_record(id,organization_id,certificate_number,issued_on,
                  valid_until,issuing_authority,certificate_file_id,other_file_id,remark,created_by)
                VALUES (:id,:organization,:number,:issuedOn,:validUntil,:authority,:certificateFile,:otherFile,:remark,:actor)
                """).param("id", id).param("organization", user.organizationId())
                .param("number", clean(body.certificateNumber())).param("issuedOn", body.issuedOn())
                .param("validUntil", body.validUntil()).param("authority", clean(body.issuingAuthority()))
                .param("certificateFile", body.certificateFileId()).param("otherFile", body.otherFileId())
                .param("remark", clean(body.remark())).param("actor", user.id()).update();
        MetrologyQualificationView created = metrologyQualification(id);
        audit.record("METROLOGY_QUALIFICATION_CREATED", "METROLOGY_QUALIFICATION", id, null, created, null, body.remark());
        return created;
    }

    @PutMapping("/metrology-qualifications/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public MetrologyQualificationView updateMetrologyQualification(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MetrologyQualificationWrite body) {
        return idempotency.execute(key, "foundation-metrology-qualification-update:" + id, body,
                MetrologyQualificationView.class, () -> updateMetrologyQualificationInternal(id, body));
    }

    private MetrologyQualificationView updateMetrologyQualificationInternal(
            UUID id, MetrologyQualificationWrite body) {
        requireVersion(body.expectedVersion());
        MetrologyQualificationView before = metrologyQualification(id);
        requireDraft(before.state(), before.version(), "计量资质");
        validateMetrologyQualification(body, before.certificateFileId(), before.otherFileId());
        int changed = jdbc.sql("""
                UPDATE metrology_qualification_record SET certificate_number=:number,issued_on=:issuedOn,
                  valid_until=:validUntil,issuing_authority=:authority,certificate_file_id=:certificateFile,
                  other_file_id=:otherFile,remark=:remark,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("number", clean(body.certificateNumber())).param("issuedOn", body.issuedOn())
                .param("validUntil", body.validUntil()).param("authority", clean(body.issuingAuthority()))
                .param("certificateFile", body.certificateFileId()).param("otherFile", body.otherFileId())
                .param("remark", clean(body.remark())).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("计量资质", "metrology_qualification_record", id);
        MetrologyQualificationView after = metrologyQualification(id);
        audit.record("METROLOGY_QUALIFICATION_UPDATED", "METROLOGY_QUALIFICATION", id, before, after, null, body.remark());
        return after;
    }

    @PostMapping("/metrology-qualifications/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionMetrologyQualification(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-metrology-qualification-transition:" + id, body,
                TransitionResult.class, () -> transition("metrology_qualification_record",
                        "METROLOGY_QUALIFICATION", id, body, submittedTransitions()));
    }

    @PostMapping("/metrology-qualifications/batch-transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public MetrologyQualificationBatchResult transitionMetrologyQualificationBatch(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MetrologyQualificationBatchCommand body) {
        return idempotency.execute(key, "foundation-metrology-qualification-batch-transition", body,
                new TypeReference<MetrologyQualificationBatchResult>() {}, () -> {
                    Set<UUID> unique = new HashSet<>();
                    for (MetrologyQualificationBatchItem item : body.items()) {
                        if (!unique.add(item.id())) {
                            throw invalid("METROLOGY_QUALIFICATION_BATCH_DUPLICATE",
                                    "批量操作不能包含重复计量资质");
                        }
                    }
                    List<TransitionResult> results = body.items().stream()
                            .map(item -> transition("metrology_qualification_record", "METROLOGY_QUALIFICATION",
                                    item.id(), new TransitionCommand(body.action(), item.expectedVersion(),
                                            body.reasonCode(), body.comment()), submittedTransitions()))
                            .toList();
                    return new MetrologyQualificationBatchResult(results.size(), results);
                });
    }

    @GetMapping("/shelves")
    public CursorPage<ShelfView> shelves(@RequestParam(defaultValue = "") @Size(max = 100) String q,
                                        @RequestParam(required = false) Boolean active,
                                        @RequestParam(defaultValue = "") @Size(max = 2000) String cursor,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        ShelfCursor pageCursor = ShelfCursor.decode(cursor);
        UUID organization = actor.require().organizationId();
        String search = pattern(q);
        List<ShelfView> roots = jdbc.sql("""
                SELECT root.id,root.parent_id,NULL::varchar parent_shelf_number,root.shelf_number,
                  root.sort_order,root.active,root.version,root.updated_at,
                  (SELECT count(*) FROM sample_shelf child WHERE child.parent_id=root.id) child_count,
                  (SELECT count(*) FROM sample_shelf child WHERE child.parent_id=root.id AND child.active) active_child_count
                FROM sample_shelf root
                WHERE root.organization_id=:organization AND root.parent_id IS NULL
                  AND ((root.shelf_number ILIKE :pattern ESCAPE '\\'
                        AND (CAST(:active AS boolean) IS NULL OR root.active=CAST(:active AS boolean)))
                    OR EXISTS (SELECT 1 FROM sample_shelf child
                      WHERE child.parent_id=root.id AND child.shelf_number ILIKE :pattern ESCAPE '\\'
                        AND (CAST(:active AS boolean) IS NULL OR child.active=CAST(:active AS boolean))))
                  AND (root.sort_order,upper(root.shelf_number),root.id) >
                      (:cursorOrder,:cursorNumber,:cursorId)
                ORDER BY root.sort_order,upper(root.shelf_number),root.id LIMIT :fetchLimit
                """).param("organization", organization).param("pattern", search).param("active", active)
                .param("cursorOrder", pageCursor.sortOrder()).param("cursorNumber", pageCursor.shelfNumber())
                .param("cursorId", pageCursor.id()).param("fetchLimit", limit + 1)
                .query((rs, row) -> shelfView(rs, List.of())).list();
        boolean hasMore = roots.size() > limit;
        List<ShelfView> pageRoots = hasMore ? List.copyOf(roots.subList(0, limit)) : List.copyOf(roots);
        Map<UUID, List<ShelfView>> children = new LinkedHashMap<>();
        if (!pageRoots.isEmpty()) {
            List<UUID> rootIds = pageRoots.stream().map(ShelfView::id).toList();
            jdbc.sql("""
                    SELECT child.id,child.parent_id,parent.shelf_number parent_shelf_number,child.shelf_number,
                      child.sort_order,child.active,child.version,child.updated_at,0 child_count
                      ,0 active_child_count
                    FROM sample_shelf child JOIN sample_shelf parent ON parent.id=child.parent_id
                    WHERE child.organization_id=:organization AND child.parent_id IN (:rootIds)
                      AND (parent.shelf_number ILIKE :pattern ESCAPE '\\'
                           OR child.shelf_number ILIKE :pattern ESCAPE '\\')
                      AND (CAST(:active AS boolean) IS NULL OR child.active=CAST(:active AS boolean))
                    ORDER BY child.parent_id,child.sort_order,upper(child.shelf_number),child.id
                    """).param("organization", organization).param("rootIds", rootIds)
                    .param("pattern", search).param("active", active)
                    .query((rs, row) -> shelfView(rs, List.of())).list()
                    .forEach(child -> children.computeIfAbsent(child.parentId(), ignored -> new ArrayList<>()).add(child));
        }
        List<ShelfView> fetched = pageRoots.stream().map(root -> new ShelfView(root.id(), null, null,
                root.shelfNumber(), root.sortOrder(), root.active(), root.version(), root.childCount(),
                root.activeChildCount(), List.copyOf(children.getOrDefault(root.id(), List.of())),
                root.updatedAt())).toList();
        long total = jdbc.sql("""
                SELECT count(*) FROM sample_shelf WHERE organization_id=:organization
                  AND shelf_number ILIKE :pattern ESCAPE '\\'
                  AND (CAST(:active AS boolean) IS NULL OR active=CAST(:active AS boolean))
                """).param("organization", organization).param("pattern", search)
                .param("active", active).query(Long.class).single();
        String next = hasMore && !fetched.isEmpty() ? ShelfCursor.from(fetched.getLast()).encode() : null;
        return new CursorPage<>(fetched, next, hasMore, total);
    }

    @PostMapping("/shelves")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ShelfView createShelf(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ShelfWrite body) {
        return idempotency.execute(key, "foundation-shelf-create", body, ShelfView.class, () -> {
            UUID id = UUID.randomUUID();
            AuthenticatedUser user = actor.require();
            validateShelfParent(body.parentId());
            jdbc.sql("""
                    INSERT INTO sample_shelf(id,organization_id,parent_id,shelf_number,sort_order,created_by)
                    VALUES (:id,:organization,:parent,:number,:sortOrder,:actor)
                    """).param("id", id).param("organization", user.organizationId())
                    .param("parent", body.parentId())
                    .param("number", clean(body.shelfNumber())).param("sortOrder", body.sortOrder())
                    .param("actor", user.id()).update();
            ShelfView created = shelf(id);
            audit.record("SAMPLE_SHELF_CREATED", "SAMPLE_SHELF", id, null, created, null, null);
            return created;
        });
    }

    @PutMapping("/shelves/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ShelfView updateShelf(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                 @Valid @RequestBody ShelfWrite body) {
        return idempotency.execute(key, "foundation-shelf-update:" + id, body, ShelfView.class, () -> {
            requireVersion(body.expectedVersion());
            ShelfView before = shelf(id);
            if (!Objects.equals(before.parentId(), body.parentId())) {
                throw invalid("SAMPLE_SHELF_PARENT_IMMUTABLE", "货架建立后不能变更上级；请停用后重新建立");
            }
            int changed = jdbc.sql("""
                    UPDATE sample_shelf SET shelf_number=:number,sort_order=:sortOrder,
                      version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("number", clean(body.shelfNumber())).param("sortOrder", body.sortOrder())
                    .param("id", id).param("organization", actor.require().organizationId())
                    .param("version", body.expectedVersion()).update();
            if (changed == 0) throw conflict("货架", "sample_shelf", id);
            ShelfView after = shelf(id);
            audit.record("SAMPLE_SHELF_UPDATED", "SAMPLE_SHELF", id, before, after, null, null);
            return after;
        });
    }

    @PostMapping("/shelves/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionShelf(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "foundation-shelf-transition:" + id, body, TransitionResult.class, () -> {
            ShelfView before = shelf(id);
            String action = body.action().toUpperCase();
            boolean target = switch (action) {
                case "DEACTIVATE" -> false;
                case "ACTIVATE" -> true;
                default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSITION_NOT_ALLOWED", "当前货架不允许该操作");
            };
            if (!target && (body.comment() == null || body.comment().isBlank())) {
                throw invalid("SAMPLE_SHELF_DEACTIVATE_REASON_REQUIRED", "停用货架必须填写原因");
            }
            if (before.active() == target) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "TRANSITION_NOT_ALLOWED", "货架已经处于目标状态");
            if (!target && before.parentId() == null) {
                long activeChildren = jdbc.sql("""
                        SELECT count(*) FROM sample_shelf
                        WHERE organization_id=:organization AND parent_id=:parent AND active
                        """).param("organization", actor.require().organizationId()).param("parent", id)
                        .query(Long.class).single();
                if (activeChildren > 0) throw ApiException.blocked("SAMPLE_SHELF_ACTIVE_CHILDREN",
                        "请先停用全部下级货架", before.version(), List.of("仍有 " + activeChildren + " 个启用中的下级货架"));
            }
            if (target && before.parentId() != null) {
                ShelfView parent = shelf(before.parentId());
                if (!parent.active()) throw ApiException.blocked("SAMPLE_SHELF_PARENT_INACTIVE",
                        "上级货架停用时不能启用下级货架", before.version(), List.of("请先启用上级货架 " + parent.shelfNumber()));
            }
            int changed = jdbc.sql("""
                    UPDATE sample_shelf SET active=:active,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("active", target).param("id", id).param("organization", actor.require().organizationId())
                    .param("version", body.expectedVersion()).update();
            if (changed == 0) throw conflict("货架", "sample_shelf", id);
            ShelfView after = shelf(id);
            audit.record("SAMPLE_SHELF_" + action, "SAMPLE_SHELF", id, before, after, body.reasonCode(), body.comment());
            return new TransitionResult(target ? "ACTIVE" : "INACTIVE", after.version(),
                    List.of(target ? "DEACTIVATE" : "ACTIVATE"));
        });
    }

    @GetMapping("/shelves/{id}/qr")
    public ResponseEntity<byte[]> shelfQr(@PathVariable UUID id) {
        ShelfView value = shelf(id);
        String filename = URLEncoder.encode(value.shelfNumber() + ".png", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + filename)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(shelfQr.png(value.id()));
    }

    @PostMapping("/shelves/qr-downloads")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> downloadShelfQrs(@Valid @RequestBody ShelfQrDownload body) {
        if (new HashSet<>(body.ids()).size() != body.ids().size()) {
            throw invalid("SAMPLE_SHELF_QR_DUPLICATE", "二维码下载不能包含重复货架");
        }
        List<ShelfView> values = body.ids().stream().map(this::shelf).toList();
        byte[] content = shelfQr.zip(values);
        audit.record("SAMPLE_SHELF_QR_DOWNLOADED", "SAMPLE_SHELF", null, null,
                Map.of("count", values.size(), "shelfIds", body.ids()), null, null);
        String filename = URLEncoder.encode("货架二维码.zip", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(content);
    }

    private TrainingView training(UUID id) {
        return bindScope(jdbc.sql("""
                SELECT t.id,t.person_id,p.name person_name,d.id department_id,d.name department_name,
                  t.start_date,t.end_date,t.hours,t.organizer,t.training_method,t.assessment_result,t.location,
                  t.content,t.certificate_file_id,f.original_name certificate_file_name,t.remark,t.state,t.version,t.updated_at
                FROM training_record t JOIN person p ON p.id=t.person_id LEFT JOIN department d ON d.id=p.department_id
                LEFT JOIN stored_file f ON f.id=t.certificate_file_id
                WHERE t.id=:id AND t.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                """), actor.require()).param("id", id)
                .query((rs, row) -> new TrainingView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                        rs.getString("person_name"), rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getBigDecimal("hours"), rs.getString("organizer"), rs.getString("training_method"),
                        rs.getString("assessment_result"), rs.getString("location"), rs.getString("content"),
                        rs.getObject("certificate_file_id", UUID.class), rs.getString("certificate_file_name"),
                        rs.getString("remark"), rs.getString("state"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("TRAINING_NOT_FOUND", "培训记录不存在"));
    }

    private TeamView team(UUID id) {
        return bindScope(jdbc.sql("""
                SELECT t.id,t.name,t.remark,t.state,t.version,t.updated_at,count(m.person_id) member_count,
                  max(p.name) FILTER (WHERE m.leader) leader_name,
                  coalesce(jsonb_agg(jsonb_build_object('personId',m.person_id,'personName',p.name,
                    'duty',m.duty,'leader',m.leader,'sortOrder',m.sort_order)
                    ORDER BY m.sort_order,m.person_id) FILTER (WHERE m.person_id IS NOT NULL),'[]'::jsonb)::text members
                FROM project_team t LEFT JOIN project_team_member m ON m.team_id=t.id
                LEFT JOIN person p ON p.id=m.person_id WHERE t.id=:id AND t.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      JOIN person visible_person ON visible_person.id=visible_member.person_id
                      WHERE visible_member.team_id=t.id AND visible_person.department_id=:actorDepartment))
                    OR (:scope IN ('SELF','PROJECT') AND EXISTS (SELECT 1 FROM project_team_member visible_member
                      WHERE visible_member.team_id=t.id AND visible_member.person_id=:actorPerson)))
                GROUP BY t.id
                """), actor.require()).param("id", id)
                .query((rs, row) -> new TeamView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("remark"), rs.getString("state"), rs.getLong("version"),
                        members(rs.getString("members")), rs.getLong("member_count"), rs.getString("leader_name"),
                        rs.getObject("updated_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("PROJECT_TEAM_NOT_FOUND", "项目组不存在"));
    }

    private TitleView title(UUID id) {
        AuthenticatedUser user = actor.require();
        return bindScope(jdbc.sql("""
                SELECT t.id,t.person_id,p.name person_name,t.issued_on,t.certificate_number,t.specialty_name,
                  t.issuing_authority,t.title_level,t.specialty_category,t.attachment_file_id,
                  f.original_name attachment_file_name,t.remark,t.state,t.version,t.updated_at
                FROM person_title_record t JOIN person p ON p.id=t.person_id
                LEFT JOIN stored_file f ON f.id=t.attachment_file_id
                  WHERE t.id=:id AND t.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                """), user).param("id", id)
                .query((rs, row) -> titleView(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("PERSON_TITLE_NOT_FOUND", "职称记录不存在"));
    }

    private TitleView titleView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TitleView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("person_name"), rs.getObject("issued_on", LocalDate.class),
                rs.getString("certificate_number"), rs.getString("specialty_name"), rs.getString("issuing_authority"),
                rs.getString("title_level"), rs.getString("specialty_category"),
                rs.getObject("attachment_file_id", UUID.class), rs.getString("attachment_file_name"),
                rs.getString("remark"), rs.getString("state"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private OrganizationQualificationView organizationQualification(UUID id) {
        return jdbc.sql("""
                SELECT q.id,q.certificate_number,q.issued_on,q.valid_until,q.issuing_authority,q.testing_scope,
                  q.qualification_details::text qualification_details,q.original_file_id,fo.original_name original_file_name,
                  q.copy_file_id,fc.original_name copy_file_name,q.remark,q.state,q.version,q.updated_at
                FROM organization_qualification_record q LEFT JOIN stored_file fo ON fo.id=q.original_file_id
                LEFT JOIN stored_file fc ON fc.id=q.copy_file_id
                WHERE q.id=:id AND q.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> organizationQualificationView(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("ORGANIZATION_QUALIFICATION_NOT_FOUND", "单位资质不存在"));
    }

    private OrganizationQualificationView organizationQualificationView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrganizationQualificationView(rs.getObject("id", UUID.class), rs.getString("certificate_number"),
                rs.getObject("issued_on", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                rs.getString("issuing_authority"), rs.getString("testing_scope"), strings(rs.getString("qualification_details")),
                rs.getObject("original_file_id", UUID.class), rs.getString("original_file_name"),
                rs.getObject("copy_file_id", UUID.class), rs.getString("copy_file_name"), rs.getString("remark"),
                rs.getString("state"), rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private MetrologyQualificationView metrologyQualification(UUID id) {
        return jdbc.sql("""
                SELECT q.id,q.certificate_number,q.issued_on,q.valid_until,q.issuing_authority,
                  q.certificate_file_id,fc.original_name certificate_file_name,q.other_file_id,
                  fo.original_name other_file_name,q.remark,q.state,q.version,q.updated_at
                FROM metrology_qualification_record q JOIN stored_file fc ON fc.id=q.certificate_file_id
                LEFT JOIN stored_file fo ON fo.id=q.other_file_id
                WHERE q.id=:id AND q.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> metrologyQualificationView(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("METROLOGY_QUALIFICATION_NOT_FOUND", "计量资质不存在"));
    }

    private MetrologyQualificationView metrologyQualificationView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MetrologyQualificationView(rs.getObject("id", UUID.class), rs.getString("certificate_number"),
                rs.getObject("issued_on", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                rs.getString("issuing_authority"), rs.getObject("certificate_file_id", UUID.class),
                rs.getString("certificate_file_name"), rs.getObject("other_file_id", UUID.class),
                rs.getString("other_file_name"), rs.getString("remark"), rs.getString("state"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ShelfView shelf(UUID id) {
        return jdbc.sql("""
                SELECT shelf.id,shelf.parent_id,parent.shelf_number parent_shelf_number,shelf.shelf_number,
                  shelf.sort_order,shelf.active,shelf.version,shelf.updated_at,
                  (SELECT count(*) FROM sample_shelf child WHERE child.parent_id=shelf.id) child_count,
                  (SELECT count(*) FROM sample_shelf child WHERE child.parent_id=shelf.id AND child.active) active_child_count
                FROM sample_shelf shelf LEFT JOIN sample_shelf parent ON parent.id=shelf.parent_id
                WHERE shelf.id=:id AND shelf.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> shelfView(rs, List.of())).optional()
                .orElseThrow(() -> ApiException.notFound("SAMPLE_SHELF_NOT_FOUND", "货架不存在"));
    }

    private ShelfView shelfView(java.sql.ResultSet rs, List<ShelfView> children) throws java.sql.SQLException {
        return new ShelfView(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("parent_shelf_number"), rs.getString("shelf_number"), rs.getInt("sort_order"),
                rs.getBoolean("active"), rs.getLong("version"), rs.getInt("child_count"),
                rs.getInt("active_child_count"), List.copyOf(children),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private void validateShelfParent(UUID parentId) {
        if (parentId == null) return;
        ShelfView parent = shelf(parentId);
        if (parent.parentId() != null) {
            throw invalid("SAMPLE_SHELF_DEPTH_EXCEEDED", "货架最多支持两级，不能在下级货架下继续新增");
        }
        if (!parent.active()) {
            throw ApiException.blocked("SAMPLE_SHELF_PARENT_INACTIVE", "停用的货架不能新增下级",
                    parent.version(), List.of("请先启用上级货架 " + parent.shelfNumber()));
        }
    }

    private TransitionResult transitionTrainingInternal(UUID id, TransitionCommand body) {
        requireVersion(body.expectedVersion());
        TrainingView before = training(id);
        String action = body.action().trim().toUpperCase();
        String target = switch (before.state() + ":" + action) {
            case "ACTIVE:VOID" -> "VOID";
            case "VOID:RESTORE" -> "ACTIVE";
            default -> null;
        };
        if (target == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRAINING_TRANSITION_NOT_ALLOWED",
                    "当前培训记录状态不允许该操作", before.version(),
                    List.of("当前状态：" + before.state()), Map.of());
        }
        if (action.equals("VOID") && (body.comment() == null || body.comment().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSITION_COMMENT_REQUIRED", "作废培训记录必须填写原因");
        }
        int changed = jdbc.sql("""
                UPDATE training_record SET state=:state,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("state", target).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict("培训记录", "training_record", id);
        TrainingView after = training(id);
        audit.record("TRAINING_" + action, "TRAINING", id, before, after, body.reasonCode(), body.comment());
        return new TransitionResult(after.state(), after.version(), trainingActions(after.state()));
    }

    private TransitionResult transitionTitleInternal(UUID id, TransitionCommand body) {
        title(id);
        return transition("person_title_record", "PERSON_TITLE", id, body, submittedTransitions());
    }

    private List<String> trainingActions(String state) {
        return state.equals("ACTIVE") ? List.of("VOID") : state.equals("VOID") ? List.of("RESTORE") : List.of();
    }

    private TransitionResult transition(String table, String resourceType, UUID id, TransitionCommand body,
                                        Map<String, Map<String, String>> transitions) {
        StateVersion before = stateVersion(table, id);
        String action = body.action().trim().toUpperCase();
        String target = transitions.getOrDefault(before.state(), Map.of()).get(action);
        if (target == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSITION_NOT_ALLOWED",
                "当前状态不允许该操作", before.version(), List.of("当前状态：" + before.state()), Map.of());
        if (Set.of("VOID", "RETIRE", "DEACTIVATE").contains(action)
                && (body.comment() == null || body.comment().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSITION_COMMENT_REQUIRED", "该操作必须填写原因");
        }
        int changed = jdbc.sql("UPDATE " + table + " SET state=:state,version=version+1,updated_at=now() " +
                        "WHERE id=:id AND organization_id=:organization AND version=:version")
                .param("state", target).param("id", id).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw conflict(resourceType, table, id);
        StateVersion after = stateVersion(table, id);
        audit.record(resourceType + "_" + action, resourceType, id, before, after, body.reasonCode(), body.comment());
        return new TransitionResult(after.state(), after.version(),
                new ArrayList<>(transitions.getOrDefault(after.state(), Map.of()).keySet()));
    }

    private StateVersion stateVersion(String table, UUID id) {
        return jdbc.sql("SELECT state,version FROM " + table + " WHERE id=:id AND organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> new StateVersion(rs.getString("state"), rs.getLong("version"))).optional()
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "记录不存在"));
    }

    private Map<String, Map<String, String>> submittedTransitions() {
        return Map.of("DRAFT", Map.of("SUBMIT", "SUBMITTED", "RETIRE", "RETIRED"),
                "SUBMITTED", Map.of("RECALL", "DRAFT", "RETIRE", "RETIRED"), "RETIRED", Map.of());
    }

    private void validateTraining(TrainingWrite body, UUID currentFileId) {
        requirePerson(body.personId());
        if (body.endDate().isBefore(body.startDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRAINING_DATES_INVALID", "培训结束日期不能早于开始日期");
        }
        String method = clean(body.trainingMethod());
        String result = clean(body.assessmentResult());
        if (!TRAINING_METHODS.contains(method)) throw invalid("TRAINING_METHOD_INVALID", "培训方式不在受控选项中");
        if (result != null && !TRAINING_ASSESSMENT_RESULTS.contains(result)) {
            throw invalid("TRAINING_ASSESSMENT_INVALID", "培训考核结果不在受控选项中");
        }
        validateTrainingFile(body.certificateFileId(), currentFileId);
    }

    private void validateTitle(TitleWrite body, UUID currentFileId) {
        requirePerson(body.personId());
        if (!TITLE_LEVELS.contains(body.titleLevel())) throw invalid("TITLE_LEVEL_INVALID", "职称等级无效");
        if (!TITLE_CATEGORIES.contains(body.specialtyCategory())) throw invalid("TITLE_CATEGORY_INVALID", "专业类型无效");
        if (body.attachmentFileId() == null) return;
        StoredFileService.View file = body.attachmentFileId().equals(currentFileId)
                ? files.require(body.attachmentFileId()) : files.requireCurrentUpload(body.attachmentFileId());
        if (!file.purpose().equals("PERSON_TITLE") || !TITLE_FILE_MEDIA.contains(file.mediaType())
                || file.sizeBytes() > TITLE_FILE_MAX_BYTES) {
            throw invalid("TITLE_FILE_INVALID", "职称附件必须是当前上传且不超过 3MB 的 JPG、PNG 或 BMP 图片");
        }
    }

    private void validateOrganizationQualification(OrganizationQualificationWrite body,
                                                   UUID currentOriginalFileId, UUID currentCopyFileId) {
        validateDates(body.issuedOn(), body.validUntil(), "资质");
        if (new HashSet<>(body.qualificationDetails()).size() != body.qualificationDetails().size()
                || !QUALIFICATION_DETAILS.containsAll(body.qualificationDetails())) {
            throw invalid("QUALIFICATION_DETAIL_INVALID", "资质详情包含重复项或无效项");
        }
        requireFile(body.originalFileId(), currentOriginalFileId, "ORGANIZATION_QUALIFICATION_ORIGINAL", false);
        requireFile(body.copyFileId(), currentCopyFileId, "ORGANIZATION_QUALIFICATION_COPY", false);
    }

    private void validateMetrologyQualification(MetrologyQualificationWrite body,
                                                UUID currentCertificateFileId, UUID currentOtherFileId) {
        validateDates(body.issuedOn(), body.validUntil(), "计量证书");
        requireFile(body.certificateFileId(), currentCertificateFileId, "METROLOGY_CERTIFICATE", true);
        requireFile(body.otherFileId(), currentOtherFileId, "METROLOGY_OTHER", false);
    }

    private void validateDates(LocalDate issuedOn, LocalDate validUntil, String label) {
        if (validUntil.isBefore(issuedOn)) throw invalid("VALIDITY_DATES_INVALID", label + "有效期不能早于签发日期");
    }

    private void validateMembers(List<TeamMemberWrite> members) {
        Set<UUID> people = new HashSet<>();
        long leaders = members.stream().filter(TeamMemberWrite::leader).count();
        if (leaders != 1) throw invalid("PROJECT_TEAM_LEADER_REQUIRED", "项目组必须且只能指定一名负责人");
        for (TeamMemberWrite member : members) {
            if (!people.add(member.personId())) throw invalid("PROJECT_TEAM_MEMBER_DUPLICATE", "项目组成员不能重复");
            requirePerson(member.personId());
        }
    }

    private void replaceMembers(UUID teamId, List<TeamMemberWrite> members) {
        jdbc.sql("DELETE FROM project_team_member WHERE team_id=:team").param("team", teamId).update();
        for (TeamMemberWrite member : members) {
            jdbc.sql("""
                    INSERT INTO project_team_member(team_id,person_id,duty,leader,sort_order)
                    VALUES (:team,:person,:duty,:leader,:sortOrder)
                    """).param("team", teamId).param("person", member.personId()).param("duty", clean(member.duty()))
                    .param("leader", member.leader()).param("sortOrder", member.sortOrder()).update();
        }
    }

    private void validateActiveTeam(UUID id) {
        TeamView value = team(id);
        if (value.members().isEmpty() || value.members().stream().filter(TeamMemberView::leader).count() != 1) {
            throw blocked("PROJECT_TEAM_INCOMPLETE", "项目组启用前必须配置成员和唯一负责人", value.version());
        }
    }

    private void requirePerson(UUID id) {
        AuthenticatedUser user = actor.require();
        boolean exists = bindScope(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person p JOIN department d ON d.id=p.department_id
                  WHERE p.id=:id AND p.active AND d.organization_id=:organization
                    AND (:scope IN ('ALL','ORGANIZATION')
                      OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                      OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson)))
                """), user).param("id", id)
                .query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("PERSON_NOT_FOUND", "人员不存在、不在当前数据范围或已停用");
    }

    private void validateTrainingFile(UUID id, UUID currentFileId) {
        if (id == null) return;
        StoredFileService.View file = id.equals(currentFileId) ? files.require(id) : files.requireCurrentUpload(id);
        if (!file.purpose().equals("TRAINING_CERTIFICATE")
                || !Set.of("application/pdf", "image/png", "image/jpeg", "image/bmp").contains(file.mediaType())) {
            throw invalid("TRAINING_CERTIFICATE_INVALID", "培训证书必须是当前上传的受控图片或 PDF");
        }
    }

    private JdbcClient.StatementSpec bindScope(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("actorDepartment", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("actorPerson", user.personId() == null ? new UUID(0, 0) : user.personId());
    }

    private void requireFile(UUID id, UUID currentFileId, String purpose, boolean required) {
        if (id == null) {
            if (required) throw invalid("FILE_REQUIRED", "请上传必需的受控附件");
            return;
        }
        StoredFileService.View file = id.equals(currentFileId) ? files.require(id) : files.requireCurrentUpload(id);
        if (!file.purpose().equals(purpose)) throw invalid("FILE_PURPOSE_MISMATCH", "附件用途与当前业务不匹配");
    }

    private void requireDraft(String state, long version, String label) {
        if (!state.equals("DRAFT")) throw blocked("PUBLISHED_RECORD_IMMUTABLE", label + "提交后不可原地修改，请先撤回", version);
    }

    private void requireVersion(Long version) {
        if (version == null) throw invalid("EXPECTED_VERSION_REQUIRED", "更新必须携带 expectedVersion");
    }

    private ApiException conflict(String label, String table, UUID id) {
        StateVersion current = stateVersionForAny(table, id);
        return ApiException.conflict("VERSION_CONFLICT", label + "已被其他用户修改", current.version());
    }

    private StateVersion stateVersionForAny(String table, UUID id) {
        String stateColumn = table.equals("sample_shelf") ? "CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END" : "state";
        return jdbc.sql("SELECT " + stateColumn + " state,version FROM " + table +
                        " WHERE id=:id AND organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> new StateVersion(rs.getString("state"), rs.getLong("version"))).optional()
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "记录不存在"));
    }

    private PageCursor cursor(String value) {
        PageCursor decoded = PageCursor.decode(value);
        return decoded == null ? new PageCursor(FIRST_PAGE_TIME, FIRST_PAGE_ID) : decoded;
    }

    private <T> CursorPage<T> page(List<T> fetched, int limit, long total, Function<T, PageCursor> cursor) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore && !items.isEmpty() ? cursor.apply(items.getLast()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    private String pattern(String value) {
        String clean = value == null ? "" : value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + clean + "%";
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private List<TeamMemberView> members(String value) {
        return decode(value, new TypeReference<List<TeamMemberView>>() {});
    }

    private List<String> strings(String value) {
        return decode(value, new TypeReference<List<String>>() {});
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException exception) { throw new IllegalStateException("无法读取受控字段", exception); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法保存受控字段", exception); }
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private ApiException blocked(String code, String message, long version) {
        return ApiException.blocked(code, message, version, List.of(message));
    }

    private record ShelfCursor(int sortOrder, String shelfNumber, UUID id) {
        private static ShelfCursor decode(String value) {
            if (value == null || value.isBlank()) return new ShelfCursor(-1, "", new UUID(0, 0));
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                int first = decoded.indexOf('|');
                int last = decoded.lastIndexOf('|');
                if (first < 1 || last <= first) throw new IllegalArgumentException();
                return new ShelfCursor(Integer.parseInt(decoded.substring(0, first)),
                        decoded.substring(first + 1, last), UUID.fromString(decoded.substring(last + 1)));
            } catch (RuntimeException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "货架分页游标无效或已损坏");
            }
        }

        private static ShelfCursor from(ShelfView value) {
            return new ShelfCursor(value.sortOrder(), value.shelfNumber().toUpperCase(Locale.ROOT), value.id());
        }

        private String encode() {
            String value = sortOrder + "|" + shelfNumber + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record StateVersion(String state, long version) {}
}
