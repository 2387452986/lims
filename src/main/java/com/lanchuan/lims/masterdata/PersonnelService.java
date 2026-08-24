package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AccountAdministrationService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PersonnelService {
    private static final Set<String> GENDERS = Set.of("UNKNOWN", "MALE", "FEMALE");
    private static final Set<String> PERSON_TYPES = Set.of("EMPLOYEE", "CONTRACTOR", "EXTERNAL_EXPERT", "INTERN");
    private static final Set<String> ACCOUNT_STATES = Set.of("NO_ACCOUNT", "LOCKED", "ENABLED", "DISABLED");
    private static final Set<String> APPLICATION_STATES = Set.of("DRAFT", "SUBMITTED", "VERIFIED", "RETURNED", "ARCHIVED");
    private static final Map<String, String> FILE_PURPOSES = Map.of(
            "avatar", "PERSON_AVATAR",
            "idFront", "PERSON_ID_FRONT",
            "idBack", "PERSON_ID_BACK",
            "socialSecurity", "PERSON_SOCIAL_SECURITY"
    );

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final PersonnelSensitiveDataService sensitive;
    private final AccountAdministrationService accountAdmin;
    private final StoredFileService files;

    public PersonnelService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                            PersonnelSensitiveDataService sensitive,
                            AccountAdministrationService accountAdmin, StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.sensitive = sensitive;
        this.accountAdmin = accountAdmin;
        this.files = files;
    }

    public record PersonnelSummary(UUID id, String employeeNumber, String name, UUID avatarFileId,
                                   UUID departmentId,
                                   String departmentName, String organizationName, String username,
                                   String accountState, String gender,
                                   String maskedPhone, String maskedNationalId, Integer age, LocalDate birthDate,
                                   String title, String position, String personType, String applicationState,
                                   String identityState, String electronicSignatureState, boolean active,
                                   long qualificationCount, long capabilityCount, long version,
                                   OffsetDateTime updatedAt, List<String> roleCodes) {}

    public record AttachmentView(UUID id, String originalName, String mediaType, String purpose) {}

    public record PersonnelDetail(UUID id, String employeeNumber, String name, UUID departmentId,
                                  String departmentName, String username, boolean accountEnabled,
                                  boolean mustChangePassword, Long accountVersion, List<UUID> roleIds,
                                  List<String> roleCodes, String gender, String phone, String nationalId,
                                  boolean nationalIdMasked, LocalDate birthDate, Integer age, String title,
                                  String position, String personType, String officeLocation, String specialty,
                                  String education, LocalDate testingStartedOn, String laborRelationship,
                                  String testingQualification, String titleIssuer, String remark,
                                  String applicationState, String identityState,
                                  String electronicSignatureState, boolean active, long version,
                                  List<UUID> capabilityRevisionIds, List<AttachmentView> attachments,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                  List<String> allowedActions) {}

    public record DepartmentOption(UUID id, String code, String name) {}
    public record CapabilityOption(UUID revisionId, UUID projectNodeId, String path, String symbol) {}
    public record PersonnelOptions(List<DepartmentOption> departments,
                                   List<AccountAdministrationService.RoleOption> roles,
                                   List<CapabilityOption> capabilities,
                                   Map<String, String> filePurposes) {}

    public record AccountCreate(@NotBlank @Size(max = 80) String username,
                                @NotBlank @Size(max = 128) String password,
                                @NotEmpty List<UUID> roleIds,
                                boolean enabled) {}

    public record PersonnelCreate(@NotBlank @Size(max = 40) String employeeNumber,
                                  @NotBlank @Size(max = 120) String name,
                                  @NotNull UUID departmentId,
                                  @NotBlank @Size(max = 24) String personType,
                                  @Size(max = 12) String gender,
                                  @Size(max = 40) String phone,
                                  @Size(max = 32) String nationalId,
                                  LocalDate birthDate,
                                  @Size(max = 120) String title,
                                  @Size(max = 120) String position,
                                  @Size(max = 240) String officeLocation,
                                  @Size(max = 240) String specialty,
                                  @Size(max = 80) String education,
                                  LocalDate testingStartedOn,
                                  @Size(max = 120) String laborRelationship,
                                  @Size(max = 500) String testingQualification,
                                  @Size(max = 240) String titleIssuer,
                                  @Size(max = 2000) String remark,
                                  UUID avatarFileId, UUID idFrontFileId, UUID idBackFileId,
                                  UUID socialSecurityFileId,
                                  List<UUID> capabilityRevisionIds,
                                  @NotNull @Valid AccountCreate account) {}

    public record PersonnelUpdate(@NotBlank @Size(max = 120) String name,
                                  @NotNull UUID departmentId,
                                  @NotBlank @Size(max = 24) String personType,
                                  @Size(max = 12) String gender,
                                  @Size(max = 40) String phone,
                                  @Size(max = 32) String nationalId,
                                  boolean replaceNationalId,
                                  LocalDate birthDate,
                                  @Size(max = 120) String title,
                                  @Size(max = 120) String position,
                                  @Size(max = 240) String officeLocation,
                                  @Size(max = 240) String specialty,
                                  @Size(max = 80) String education,
                                  LocalDate testingStartedOn,
                                  @Size(max = 120) String laborRelationship,
                                  @Size(max = 500) String testingQualification,
                                  @Size(max = 240) String titleIssuer,
                                  @Size(max = 2000) String remark,
                                  UUID avatarFileId, UUID idFrontFileId, UUID idBackFileId,
                                  UUID socialSecurityFileId,
                                  List<UUID> capabilityRevisionIds,
                                  List<UUID> roleIds,
                                  Long expectedAccountVersion,
                                  @NotNull @PositiveOrZero Long expectedVersion) {}

    public record AccountOpen(@NotBlank @Size(max = 80) String username,
                              @NotBlank @Size(max = 128) String password,
                              @NotEmpty List<UUID> roleIds,
                              boolean enabled,
                              @NotNull @PositiveOrZero Long expectedVersion) {}

    public record BatchTransitionItem(@NotNull UUID id,
                                      @NotNull @PositiveOrZero Long expectedVersion) {}

    public record BatchTransitionCommand(@NotBlank @Size(max = 80) String action,
                                         @NotEmpty @Size(max = 100) List<@Valid BatchTransitionItem> items,
                                         @Size(max = 80) String reasonCode,
                                         @Size(max = 1000) String comment) {}

    public record BatchTransitionItemResult(UUID id, String state, long version,
                                            List<String> allowedActions) {}

    public record BatchTransitionResult(int processed, List<BatchTransitionItemResult> items) {}

    public CursorPage<PersonnelSummary> list(String accountState, String applicationState, String personType,
                                             String name, String position, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor page = PageCursor.decode(cursor);
        String account = controlledFilter(accountState, ACCOUNT_STATES, "PERSON_ACCOUNT_STATE_INVALID", "账号状态筛选无效");
        String application = controlledFilter(applicationState, APPLICATION_STATES,
                "PERSON_APPLICATION_STATE_INVALID", "申报状态筛选无效");
        String type = controlledFilter(personType, PERSON_TYPES, "PERSON_TYPE_INVALID", "人员类型筛选无效");
        String namePattern = like(name);
        String positionPattern = like(position);
        AuthenticatedUser user = actor.require();
        List<PersonnelSummary> rows = bindScope(jdbc.sql("""
                SELECT p.id,p.employee_number,p.name,p.avatar_file_id,p.department_id,d.name department_name,
                  o.name organization_name,
                  u.username,u.enabled account_enabled,u.locked_until,p.gender,p.phone,p.national_id_last4,
                  p.birth_date,p.title,p.position,p.person_type,p.application_state,p.identity_state,
                  p.electronic_signature_state,p.active,p.version,p.updated_at,
                  (SELECT count(*) FROM person_qualification q WHERE q.person_id=p.id) qualification_count,
                  (SELECT count(*) FROM person_detection_capability c WHERE c.person_id=p.id) capability_count,
                  COALESCE((SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app_role r
                    JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=u.id),'') role_codes
                FROM person p
                JOIN department d ON d.id=p.department_id
                JOIN organization o ON o.id=d.organization_id
                LEFT JOIN app_user u ON u.person_id=p.id
                WHERE d.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                  AND (:account='' OR (:account='NO_ACCOUNT' AND u.id IS NULL)
                    OR (:account='LOCKED' AND u.locked_until>now())
                    OR (:account='ENABLED' AND u.enabled AND (u.locked_until IS NULL OR u.locked_until<=now()))
                    OR (:account='DISABLED' AND NOT u.enabled))
                  AND (:application='' OR p.application_state=:application)
                  AND (:personType='' OR p.person_type=:personType)
                  AND (:namePattern='%%' OR p.name ILIKE :namePattern ESCAPE '\\'
                    OR p.employee_number ILIKE :namePattern ESCAPE '\\'
                    OR COALESCE(u.username,'') ILIKE :namePattern ESCAPE '\\')
                  AND (:positionPattern='%%' OR COALESCE(p.position,'') ILIKE :positionPattern ESCAPE '\\'
                    OR COALESCE(p.title,'') ILIKE :positionPattern ESCAPE '\\')
                  AND (CAST(:cursorAt AS timestamptz) IS NULL
                    OR (p.updated_at,p.id)<(CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY p.updated_at DESC,p.id DESC LIMIT :fetchLimit
                """), user).param("account", account)
                .param("application", application).param("personType", type)
                .param("namePattern", namePattern).param("positionPattern", positionPattern)
                .param("cursorAt", page == null ? null : page.updatedAt())
                .param("cursorId", page == null ? null : page.id()).param("fetchLimit", limit + 1)
                .query(this::mapSummary).list();
        long total = count(user, account, application, type, namePattern, positionPattern);
        boolean hasMore = rows.size() > limit;
        List<PersonnelSummary> items = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore && !items.isEmpty()
                ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public PersonnelOptions options() {
        AuthenticatedUser user = actor.require();
        List<DepartmentOption> departments = bindScope(jdbc.sql("""
                SELECT id,code,name FROM department WHERE organization_id=:organization AND active
                  AND (:scope IN ('ALL','ORGANIZATION') OR id=:actorDepartment)
                ORDER BY code,name
                """), user).query((rs, row) -> new DepartmentOption(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"))).list();
        List<CapabilityOption> capabilities = jdbc.sql("""
                WITH RECURSIVE paths AS (
                  SELECT id,parent_id,name,name::text path FROM detection_catalog_node WHERE parent_id IS NULL
                  UNION ALL
                  SELECT child.id,child.parent_id,child.name,paths.path || ' / ' || child.name
                  FROM detection_catalog_node child JOIN paths ON paths.id=child.parent_id
                )
                SELECT revision.id revision_id,node.id project_node_id,paths.path,node.symbol
                FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                JOIN paths ON paths.id=node.id
                WHERE revision.status='PUBLISHED' AND node.status='ACTIVE'
                ORDER BY paths.path,revision.revision_number DESC
                """).query((rs, row) -> new CapabilityOption(rs.getObject("revision_id", UUID.class),
                        rs.getObject("project_node_id", UUID.class), rs.getString("path"), rs.getString("symbol"))).list();
        return new PersonnelOptions(departments, accountAdmin.roles(), capabilities, FILE_PURPOSES);
    }

    public AccountAdministrationService.PermissionOverview permissionOverview(UUID id) {
        require(id);
        AccountAdministrationService.PermissionOverview overview = accountAdmin.permissionOverviewForPerson(id);
        audit.record("PERSON_PERMISSION_OVERVIEW_VIEWED", "PERSON", id, null,
                Map.of("username", overview.username(), "roleCount", overview.roles().size(),
                        "permissionCount", overview.effectivePermissions().size()),
                "PERSON_PERMISSION_REVIEW", "查看人员账号的实效权限总览");
        return overview;
    }

    public PersonnelDetail require(UUID id) {
        boolean canReadSensitive = actor.require().permissions().contains("PERSON_SENSITIVE_READ");
        AuthenticatedUser user = actor.require();
        return bindScope(jdbc.sql("""
                SELECT p.*,d.name department_name,u.id account_id,u.username,u.enabled account_enabled,
                  u.must_change_password,u.version account_version
                FROM person p JOIN department d ON d.id=p.department_id
                LEFT JOIN app_user u ON u.person_id=p.id
                WHERE p.id=:id AND d.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                """), user).param("id", id)
                .query((rs, row) -> mapDetail(rs, canReadSensitive)).optional()
                .orElseThrow(() -> ApiException.notFound("PERSON_NOT_FOUND", "人员不存在或不在当前数据范围"));
    }

    @Transactional
    public PersonnelDetail create(PersonnelCreate body) {
        AuthenticatedUser user = actor.require();
        UUID organization = user.organizationId();
        department(body.departmentId(), user);
        ValidatedProfile profile = validateProfile(body.personType(), body.gender(), body.nationalId(),
                body.birthDate(), body.phone());
        validateFiles(body.avatarFileId(), body.idFrontFileId(), body.idBackFileId(), body.socialSecurityFileId());
        List<UUID> capabilities = validateCapabilities(body.capabilityRevisionIds());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO person(id,department_id,employee_number,name,gender,national_id_cipher,
                      national_id_nonce,national_id_digest,national_id_last4,birth_date,position,person_type,
                      office_location,specialty,education,testing_started_on,labor_relationship,
                      testing_qualification,title,title_issuer,phone,remark,avatar_file_id,id_front_file_id,
                      id_back_file_id,social_security_file_id)
                    VALUES (:id,:department,:employeeNumber,:name,:gender,:idCipher,:idNonce,:idDigest,:idLast4,
                      :birthDate,:position,:personType,:officeLocation,:specialty,:education,:testingStartedOn,
                      :laborRelationship,:testingQualification,:title,:titleIssuer,:phone,:remark,:avatar,
                      :idFront,:idBack,:socialSecurity)
                    """).param("id", id).param("department", body.departmentId())
                    .param("employeeNumber", body.employeeNumber().trim()).param("name", body.name().trim())
                    .param("gender", profile.gender()).param("idCipher", bytes(profile.protectedId(), true))
                    .param("idNonce", bytes(profile.protectedId(), false))
                    .param("idDigest", profile.protectedId() == null ? null : profile.protectedId().digest())
                    .param("idLast4", profile.protectedId() == null ? null : profile.protectedId().last4())
                    .param("birthDate", profile.birthDate()).param("position", nullable(body.position()))
                    .param("personType", profile.personType()).param("officeLocation", nullable(body.officeLocation()))
                    .param("specialty", nullable(body.specialty())).param("education", nullable(body.education()))
                    .param("testingStartedOn", body.testingStartedOn())
                    .param("laborRelationship", nullable(body.laborRelationship()))
                    .param("testingQualification", nullable(body.testingQualification()))
                    .param("title", nullable(body.title())).param("titleIssuer", nullable(body.titleIssuer()))
                    .param("phone", profile.phone()).param("remark", nullable(body.remark()))
                    .param("avatar", body.avatarFileId()).param("idFront", body.idFrontFileId())
                    .param("idBack", body.idBackFileId()).param("socialSecurity", body.socialSecurityFileId()).update();
            replaceCapabilities(id, capabilities);
            accountAdmin.createForPerson(id, organization, body.departmentId(), body.account().username(),
                    body.name(), body.account().password(), body.account().roleIds(), body.account().enabled());
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        PersonnelDetail created = require(id);
        audit.record("PERSONNEL_CREATED", "PERSON", id, null, auditSnapshot(created), null, null);
        return created;
    }

    @Transactional
    public PersonnelDetail update(UUID id, PersonnelUpdate body) {
        PersonnelDetail before = require(id);
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "人员档案已被其他操作更新", before.version());
        }
        AuthenticatedUser user = actor.require();
        department(body.departmentId(), user);
        String replacementId = body.replaceNationalId() ? nullable(body.nationalId()) : null;
        ValidatedProfile profile = validateProfile(body.personType(), body.gender(), replacementId,
                body.birthDate(), body.phone());
        validateFiles(body.avatarFileId(), body.idFrontFileId(), body.idBackFileId(), body.socialSecurityFileId());
        List<UUID> capabilities = validateCapabilities(body.capabilityRevisionIds());
        try {
            int changed = jdbc.sql("""
                    UPDATE person SET department_id=:department,name=:name,gender=:gender,
                      national_id_cipher=CASE WHEN :replaceId THEN :idCipher ELSE national_id_cipher END,
                      national_id_nonce=CASE WHEN :replaceId THEN :idNonce ELSE national_id_nonce END,
                      national_id_digest=CASE WHEN :replaceId THEN :idDigest ELSE national_id_digest END,
                      national_id_last4=CASE WHEN :replaceId THEN :idLast4 ELSE national_id_last4 END,
                      birth_date=:birthDate,position=:position,person_type=:personType,
                      office_location=:officeLocation,specialty=:specialty,education=:education,
                      testing_started_on=:testingStartedOn,labor_relationship=:laborRelationship,
                      testing_qualification=:testingQualification,title=:title,title_issuer=:titleIssuer,
                      phone=:phone,remark=:remark,avatar_file_id=:avatar,id_front_file_id=:idFront,
                      id_back_file_id=:idBack,social_security_file_id=:socialSecurity,
                      version=version+1,updated_at=now()
                    WHERE id=:id AND version=:version
                    """).param("department", body.departmentId()).param("name", body.name().trim())
                    .param("gender", profile.gender()).param("replaceId", body.replaceNationalId())
                    .param("idCipher", bytes(profile.protectedId(), true)).param("idNonce", bytes(profile.protectedId(), false))
                    .param("idDigest", profile.protectedId() == null ? null : profile.protectedId().digest())
                    .param("idLast4", profile.protectedId() == null ? null : profile.protectedId().last4())
                    .param("birthDate", profile.birthDate()).param("position", nullable(body.position()))
                    .param("personType", profile.personType()).param("officeLocation", nullable(body.officeLocation()))
                    .param("specialty", nullable(body.specialty())).param("education", nullable(body.education()))
                    .param("testingStartedOn", body.testingStartedOn())
                    .param("laborRelationship", nullable(body.laborRelationship()))
                    .param("testingQualification", nullable(body.testingQualification()))
                    .param("title", nullable(body.title())).param("titleIssuer", nullable(body.titleIssuer()))
                    .param("phone", profile.phone()).param("remark", nullable(body.remark()))
                    .param("avatar", body.avatarFileId()).param("idFront", body.idFrontFileId())
                    .param("idBack", body.idBackFileId()).param("socialSecurity", body.socialSecurityFileId())
                    .param("id", id).param("version", body.expectedVersion()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT",
                    "人员档案已被其他操作更新", require(id).version());
            replaceCapabilities(id, capabilities);
            accountAdmin.synchronizeProfile(id, body.departmentId(), body.name(), body.roleIds(),
                    body.expectedAccountVersion());
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        PersonnelDetail after = require(id);
        audit.record("PERSONNEL_UPDATED", "PERSON", id, auditSnapshot(before), auditSnapshot(after), null, null);
        return after;
    }

    @Transactional
    public PersonnelDetail openAccount(UUID id, AccountOpen body) {
        PersonnelDetail before = require(id);
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "人员档案已被其他操作更新", before.version());
        }
        if (before.username() != null) throw new ApiException(HttpStatus.CONFLICT,
                "PERSON_ACCOUNT_EXISTS", "该人员已经绑定登录账号");
        try {
            accountAdmin.createForPerson(id, actor.require().organizationId(), before.departmentId(),
                    body.username(), before.name(), body.password(), body.roleIds(), body.enabled());
            jdbc.sql("UPDATE person SET version=version+1,updated_at=now() WHERE id=:id AND version=:version")
                    .param("id", id).param("version", body.expectedVersion()).update();
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        PersonnelDetail after = require(id);
        audit.record("PERSONNEL_ACCOUNT_OPENED", "PERSON", id, auditSnapshot(before), auditSnapshot(after), null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        PersonnelDetail before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "人员档案已被其他操作更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if (Set.of("START_IDENTITY_VERIFICATION", "OPEN_ELECTRONIC_SIGNATURE").contains(action)) {
            audit.recordRejected("PERSONNEL_EXTERNAL_ACTION_BLOCKED", "PERSON", id,
                    Map.of("action", action), "EXTERNAL_PROVIDER_UNAVAILABLE",
                    "外部身份或电子签名服务尚未配置，未生成模拟结果");
            throw ApiException.blocked("EXTERNAL_PROVIDER_UNAVAILABLE", "外部服务尚未配置，操作已受控阻止",
                    before.version(), List.of("缺少供应商协议、证书和测试环境"));
        }
        String nextApplication = before.applicationState();
        boolean nextActive = before.active();
        switch (action) {
            case "ENABLE" -> {
                require(!before.active() && !before.applicationState().equals("ARCHIVED"), "人员当前不可启用");
                nextActive = true;
                accountAdmin.setEnabledForPerson(id, true);
            }
            case "DISABLE" -> {
                require(before.active(), "人员已经停用");
                nextActive = false;
                if (before.username() != null) accountAdmin.setEnabledForPerson(id, false);
            }
            case "SUBMIT" -> {
                require(Set.of("DRAFT", "RETURNED").contains(before.applicationState()), "当前申报状态不可提交");
                require(before.username() != null, "人员申报前必须开通登录账号");
                nextApplication = "SUBMITTED";
            }
            case "WITHDRAW" -> {
                require(before.applicationState().equals("SUBMITTED"), "仅已提交申报可撤回");
                nextApplication = "DRAFT";
            }
            case "VERIFY" -> {
                require(before.applicationState().equals("SUBMITTED"), "仅已提交申报可核验通过");
                nextApplication = "VERIFIED";
            }
            case "RETURN" -> {
                require(Set.of("SUBMITTED", "VERIFIED").contains(before.applicationState()), "当前申报状态不可退回");
                require(comment(command), "退回必须填写原因");
                nextApplication = "RETURNED";
            }
            case "ARCHIVE" -> {
                require(!before.active(), "归档前必须先停用人员及账号");
                nextApplication = "ARCHIVED";
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "PERSONNEL_ACTION_INVALID", "人员状态动作无效");
        }
        int changed = jdbc.sql("""
                UPDATE person SET active=:active,application_state=:application,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("active", nextActive).param("application", nextApplication)
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT",
                "人员档案已被其他操作更新", require(id).version());
        PersonnelDetail after = require(id);
        audit.record("PERSONNEL_" + action, "PERSON", id, auditSnapshot(before), auditSnapshot(after),
                command.reasonCode(), command.comment());
        return new TransitionResult(after.applicationState(), after.version(), after.allowedActions());
    }

    @Transactional
    public BatchTransitionResult batchTransition(BatchTransitionCommand command) {
        List<UUID> ids = command.items().stream().map(BatchTransitionItem::id).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERSONNEL_BATCH_DUPLICATE",
                    "批量人员操作不能包含重复档案");
        }
        List<BatchTransitionItemResult> results = new ArrayList<>();
        for (BatchTransitionItem item : command.items()) {
            TransitionResult result = transition(item.id(), new TransitionCommand(command.action(),
                    item.expectedVersion(), command.reasonCode(), command.comment()));
            results.add(new BatchTransitionItemResult(item.id(), result.state(), result.version(),
                    result.allowedActions()));
        }
        return new BatchTransitionResult(results.size(), List.copyOf(results));
    }

    private PersonnelSummary mapSummary(ResultSet rs, int row) throws SQLException {
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        String username = rs.getString("username");
        String accountState = username == null ? "NO_ACCOUNT"
                : lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now()) ? "LOCKED"
                : rs.getBoolean("account_enabled") ? "ENABLED" : "DISABLED";
        LocalDate birth = rs.getObject("birth_date", LocalDate.class);
        boolean canReadSensitive = actor.require().permissions().contains("PERSON_SENSITIVE_READ");
        return new PersonnelSummary(rs.getObject("id", UUID.class), rs.getString("employee_number"),
                rs.getString("name"), rs.getObject("avatar_file_id", UUID.class),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                rs.getString("organization_name"), username, accountState, rs.getString("gender"),
                maskPhone(rs.getString("phone")),
                sensitive.masked(rs.getString("national_id_last4")), age(birth), canReadSensitive ? birth : null,
                rs.getString("title"), rs.getString("position"), rs.getString("person_type"),
                rs.getString("application_state"), rs.getString("identity_state"),
                rs.getString("electronic_signature_state"), rs.getBoolean("active"),
                rs.getLong("qualification_count"), rs.getLong("capability_count"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), split(rs.getString("role_codes")));
    }

    private PersonnelDetail mapDetail(ResultSet rs, boolean canReadSensitive) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID accountId = rs.getObject("account_id", UUID.class);
        byte[] cipher = rs.getBytes("national_id_cipher");
        byte[] nonce = rs.getBytes("national_id_nonce");
        String nationalId = canReadSensitive ? sensitive.reveal(cipher, nonce)
                : sensitive.masked(rs.getString("national_id_last4"));
        LocalDate birth = rs.getObject("birth_date", LocalDate.class);
        List<UUID> roleIds = accountId == null ? List.of() : accountAdmin.require(accountId).roleIds();
        List<String> roleCodes = accountId == null ? List.of() : accountAdmin.require(accountId).roleCodes();
        List<UUID> capabilities = jdbc.sql("""
                SELECT project_revision_id FROM person_detection_capability
                WHERE person_id=:person ORDER BY project_revision_id
                """).param("person", id).query(UUID.class).list();
        List<AttachmentView> attachments = new ArrayList<>();
        addAttachment(attachments, rs.getObject("avatar_file_id", UUID.class), true);
        addAttachment(attachments, rs.getObject("id_front_file_id", UUID.class), canReadSensitive);
        addAttachment(attachments, rs.getObject("id_back_file_id", UUID.class), canReadSensitive);
        addAttachment(attachments, rs.getObject("social_security_file_id", UUID.class), canReadSensitive);
        PersonnelDetail base = new PersonnelDetail(id, rs.getString("employee_number"), rs.getString("name"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                rs.getString("username"), accountId != null && rs.getBoolean("account_enabled"),
                accountId != null && rs.getBoolean("must_change_password"),
                accountId == null ? null : rs.getLong("account_version"), roleIds, roleCodes,
                rs.getString("gender"), canReadSensitive ? rs.getString("phone") : maskPhone(rs.getString("phone")),
                nationalId, cipher != null && !canReadSensitive, canReadSensitive ? birth : null, age(birth),
                rs.getString("title"), rs.getString("position"), rs.getString("person_type"),
                rs.getString("office_location"), rs.getString("specialty"), rs.getString("education"),
                rs.getObject("testing_started_on", LocalDate.class), rs.getString("labor_relationship"),
                rs.getString("testing_qualification"), rs.getString("title_issuer"), rs.getString("remark"),
                rs.getString("application_state"), rs.getString("identity_state"),
                rs.getString("electronic_signature_state"), rs.getBoolean("active"), rs.getLong("version"),
                capabilities, attachments, rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), List.of());
        return withActions(base);
    }

    private PersonnelDetail withActions(PersonnelDetail value) {
        List<String> actions = new ArrayList<>();
        if (value.applicationState().equals("ARCHIVED")) return copyWithActions(value, actions);
        if (value.active()) actions.add("DISABLE"); else actions.add("ENABLE");
        if (Set.of("DRAFT", "RETURNED").contains(value.applicationState())) actions.add("SUBMIT");
        if (value.applicationState().equals("SUBMITTED")) actions.addAll(List.of("WITHDRAW", "VERIFY", "RETURN"));
        if (value.applicationState().equals("VERIFIED")) actions.add("RETURN");
        if (!value.active()) actions.add("ARCHIVE");
        if (!value.identityState().equals("VERIFIED")) actions.add("START_IDENTITY_VERIFICATION");
        if (!value.electronicSignatureState().equals("OPENED")) actions.add("OPEN_ELECTRONIC_SIGNATURE");
        return copyWithActions(value, actions);
    }

    private PersonnelDetail copyWithActions(PersonnelDetail v, List<String> actions) {
        return new PersonnelDetail(v.id(), v.employeeNumber(), v.name(), v.departmentId(), v.departmentName(),
                v.username(), v.accountEnabled(), v.mustChangePassword(), v.accountVersion(), v.roleIds(),
                v.roleCodes(), v.gender(), v.phone(), v.nationalId(), v.nationalIdMasked(), v.birthDate(), v.age(),
                v.title(), v.position(), v.personType(), v.officeLocation(), v.specialty(), v.education(),
                v.testingStartedOn(), v.laborRelationship(), v.testingQualification(), v.titleIssuer(), v.remark(),
                v.applicationState(), v.identityState(), v.electronicSignatureState(), v.active(), v.version(),
                v.capabilityRevisionIds(), v.attachments(), v.createdAt(), v.updatedAt(), List.copyOf(actions));
    }

    private long count(AuthenticatedUser user, String account, String application, String personType,
                       String namePattern, String positionPattern) {
        return bindScope(jdbc.sql("""
                SELECT count(*) FROM person p JOIN department d ON d.id=p.department_id
                LEFT JOIN app_user u ON u.person_id=p.id
                WHERE d.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND p.department_id=:actorDepartment)
                    OR (:scope IN ('SELF','PROJECT') AND p.id=:actorPerson))
                  AND (:account='' OR (:account='NO_ACCOUNT' AND u.id IS NULL)
                    OR (:account='LOCKED' AND u.locked_until>now())
                    OR (:account='ENABLED' AND u.enabled AND (u.locked_until IS NULL OR u.locked_until<=now()))
                    OR (:account='DISABLED' AND NOT u.enabled))
                  AND (:application='' OR p.application_state=:application)
                  AND (:personType='' OR p.person_type=:personType)
                  AND (:namePattern='%%' OR p.name ILIKE :namePattern ESCAPE '\\'
                    OR p.employee_number ILIKE :namePattern ESCAPE '\\'
                    OR COALESCE(u.username,'') ILIKE :namePattern ESCAPE '\\')
                  AND (:positionPattern='%%' OR COALESCE(p.position,'') ILIKE :positionPattern ESCAPE '\\'
                    OR COALESCE(p.title,'') ILIKE :positionPattern ESCAPE '\\')
                """), user).param("account", account)
                .param("application", application).param("personType", personType)
                .param("namePattern", namePattern).param("positionPattern", positionPattern)
                .query(Long.class).single();
    }

    private void replaceCapabilities(UUID personId, List<UUID> capabilities) {
        jdbc.sql("DELETE FROM person_detection_capability WHERE person_id=:person")
                .param("person", personId).update();
        capabilities.forEach(revision -> jdbc.sql("""
                INSERT INTO person_detection_capability(person_id,project_revision_id,created_by)
                VALUES (:person,:revision,:actor)
                """).param("person", personId).param("revision", revision)
                .param("actor", actor.require().id()).update());
    }

    private List<UUID> validateCapabilities(List<UUID> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<UUID> distinct = values.stream().distinct().sorted().toList();
        long count = jdbc.sql("""
                SELECT count(*) FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.id IN (:ids) AND revision.status='PUBLISHED' AND node.status='ACTIVE'
                """).param("ids", distinct).query(Long.class).single();
        if (count != distinct.size()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PERSON_CAPABILITY_INVALID", "检测能力只能关联当前已发布且启用的检测项目");
        return distinct;
    }

    private void validateFiles(UUID avatar, UUID front, UUID back, UUID social) {
        validateFile(avatar, FILE_PURPOSES.get("avatar"));
        validateFile(front, FILE_PURPOSES.get("idFront"));
        validateFile(back, FILE_PURPOSES.get("idBack"));
        validateFile(social, FILE_PURPOSES.get("socialSecurity"));
    }

    private void validateFile(UUID id, String purpose) {
        if (id == null) return;
        StoredFileService.View file = files.require(id);
        if (!file.purpose().equals(purpose)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PERSON_FILE_PURPOSE_INVALID", "人员附件上传入口不匹配");
    }

    private void addAttachment(List<AttachmentView> target, UUID id, boolean visible) {
        if (id == null || !visible) return;
        StoredFileService.View file = files.require(id);
        target.add(new AttachmentView(file.id(), file.originalName(), file.mediaType(), file.purpose()));
    }

    private void department(UUID id, AuthenticatedUser user) {
        boolean exists = bindScope(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM department
                  WHERE id=:id AND organization_id=:organization AND active
                    AND (:scope IN ('ALL','ORGANIZATION') OR id=:actorDepartment))
                """), user).param("id", id).query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("DEPARTMENT_NOT_FOUND", "部门不存在、已停用或不在当前数据范围");
    }

    private ValidatedProfile validateProfile(String personType, String gender, String nationalId,
                                             LocalDate birthDate, String phone) {
        String normalizedType = personType == null ? "" : personType.trim().toUpperCase(Locale.ROOT);
        if (!PERSON_TYPES.contains(normalizedType)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PERSON_TYPE_INVALID", "人员类型无效");
        String normalizedGender = gender == null || gender.isBlank() ? "UNKNOWN" : gender.trim().toUpperCase(Locale.ROOT);
        if (!GENDERS.contains(normalizedGender)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PERSON_GENDER_INVALID", "性别选项无效");
        PersonnelSensitiveDataService.ProtectedValue protectedId = sensitive.protectNationalId(nationalId);
        String normalizedId = nationalId == null ? null : nationalId.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (normalizedId != null && normalizedId.matches("[0-9]{17}[0-9X]")) {
            if (!validResidentIdentityNumber(normalizedId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NATIONAL_ID_INVALID",
                        "居民身份证件号码校验位无效");
            }
            try {
                LocalDate derivedBirth = LocalDate.parse(normalizedId.substring(6, 14),
                        java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                String derivedGender = Character.digit(normalizedId.charAt(16), 10) % 2 == 0 ? "FEMALE" : "MALE";
                if (birthDate != null && !birthDate.equals(derivedBirth)) throw new ApiException(HttpStatus.BAD_REQUEST,
                        "NATIONAL_ID_BIRTH_DATE_MISMATCH", "出生日期与身份证件号码不一致");
                if (!normalizedGender.equals("UNKNOWN") && !normalizedGender.equals(derivedGender)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "NATIONAL_ID_GENDER_MISMATCH", "性别与身份证件号码不一致");
                }
                birthDate = derivedBirth;
                normalizedGender = derivedGender;
            } catch (DateTimeParseException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NATIONAL_ID_INVALID", "身份证件号码中的出生日期无效");
            }
        }
        if (birthDate != null && birthDate.isAfter(LocalDate.now())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "BIRTH_DATE_INVALID", "出生日期不能晚于今天");
        String normalizedPhone = nullable(phone);
        if (normalizedPhone != null && !normalizedPhone.matches("[0-9+() -]{6,40}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PHONE_INVALID", "联系电话格式无效");
        }
        return new ValidatedProfile(normalizedType, normalizedGender, protectedId, birthDate, normalizedPhone);
    }

    static boolean validResidentIdentityNumber(String value) {
        if (value == null || !value.matches("[0-9]{17}[0-9X]")) return false;
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String checks = "10X98765432";
        int sum = 0;
        for (int index = 0; index < weights.length; index++) {
            sum += Character.digit(value.charAt(index), 10) * weights[index];
        }
        return value.charAt(17) == checks.charAt(sum % 11);
    }

    private Map<String, Object> auditSnapshot(PersonnelDetail value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", value.id());
        snapshot.put("employeeNumber", value.employeeNumber());
        snapshot.put("name", value.name());
        snapshot.put("departmentId", value.departmentId());
        snapshot.put("username", value.username());
        snapshot.put("accountEnabled", value.accountEnabled());
        snapshot.put("roles", value.roleCodes());
        snapshot.put("gender", value.gender());
        snapshot.put("phone", maskPhone(value.phone()));
        snapshot.put("nationalId", value.nationalId() == null ? null : value.nationalIdMasked()
                ? value.nationalId() : sensitive.masked(value.nationalId().substring(value.nationalId().length() - 4)));
        snapshot.put("personType", value.personType());
        snapshot.put("applicationState", value.applicationState());
        snapshot.put("active", value.active());
        snapshot.put("capabilityCount", value.capabilityRevisionIds().size());
        snapshot.put("attachmentIds", value.attachments().stream().map(AttachmentView::id).toList());
        snapshot.put("version", value.version());
        return snapshot;
    }

    private ApiException duplicate(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("app_user_username_key")) {
            return new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "登录账号已经存在");
        }
        if (message != null && message.contains("person_national_id_digest_ux")) {
            return new ApiException(HttpStatus.CONFLICT, "NATIONAL_ID_EXISTS", "身份证件号码已经登记");
        }
        if (message != null && message.contains("person_employee_number_key")) {
            return new ApiException(HttpStatus.CONFLICT, "EMPLOYEE_NUMBER_EXISTS", "人员工号已经存在");
        }
        return new ApiException(HttpStatus.CONFLICT, "PERSONNEL_UNIQUE_CONFLICT", "人员账号或档案唯一字段冲突");
    }

    private String normalizeFilter(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String controlledFilter(String value, Set<String> allowed, String code, String message) {
        String normalized = normalizeFilter(value);
        if (!normalized.isEmpty() && !allowed.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized;
    }
    private JdbcClient.StatementSpec bindScope(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("actorDepartment", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("actorPerson", user.personId() == null ? new UUID(0, 0) : user.personId());
    }
    private String like(String value) {
        String input = value == null ? "" : value.trim();
        return "%" + input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private byte[] bytes(PersonnelSensitiveDataService.ProtectedValue value, boolean cipher) {
        return value == null ? null : cipher ? value.cipher() : value.nonce();
    }
    private Integer age(LocalDate birthDate) {
        return birthDate == null ? null : Math.max(0, Period.between(birthDate, LocalDate.now()).getYears());
    }
    private String maskPhone(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() <= 7) return "***" + trimmed.substring(Math.max(0, trimmed.length() - 2));
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
    private boolean comment(TransitionCommand command) {
        return command.comment() != null && !command.comment().isBlank();
    }
    private void require(boolean condition, String message) {
        if (!condition) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PERSONNEL_TRANSITION_BLOCKED", message);
    }

    private record ValidatedProfile(String personType, String gender,
                                    PersonnelSensitiveDataService.ProtectedValue protectedId,
                                    LocalDate birthDate, String phone) {}
}
