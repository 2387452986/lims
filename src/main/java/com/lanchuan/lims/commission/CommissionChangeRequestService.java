package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommissionChangeRequestService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final List<String> TYPES = List.of(
            "CONTACT", "DUE_DATE", "REPORT_REQUIREMENT", "SAMPLE", "DETECTION_SELECTION", "OTHER");
    private static final List<String> METHODS = List.of(
            "UPDATE_CURRENT", "CREATE_REVISION", "VOID_AND_RECREATE", "MANUAL_RECORD");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final CommissionService commissions;
    private final NumberingService numbering;
    private final AuditService audit;

    public CommissionChangeRequestService(JdbcClient jdbc, CurrentActor actor, CommissionService commissions,
                                          NumberingService numbering, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.commissions = commissions;
        this.numbering = numbering;
        this.audit = audit;
    }

    public record View(UUID id, UUID commissionId, String commissionNumber, LocalDate commissionedOn,
                       String detectionProjects, String requestType, String processingMethod, String reason,
                       String requestedContactName, String requestedContactPhone, LocalDate requestedDueOn,
                       String requestedSummary, UUID attachmentFileId, String attachmentName,
                       String state, UUID requestedById, String requestedBy, OffsetDateTime requestedAt,
                       String reviewedBy, OffsetDateTime reviewedAt, String reviewComment,
                       long commissionVersionSnapshot, Long appliedCommissionVersion, UUID resultCommissionId,
                       long version, OffsetDateTime updatedAt, List<String> allowedActions,
                       List<String> blockers) {}
    public record Write(UUID commissionId, String requestType, String processingMethod, String reason,
                        String requestedContactName, String requestedContactPhone, LocalDate requestedDueOn,
                        String requestedSummary, UUID attachmentFileId, Long expectedVersion) {}
    public record BatchTarget(UUID commissionId, long expectedVersion) {}
    public record BatchWrite(List<BatchTarget> targets, String requestType, String processingMethod, String reason,
                             String requestedContactName, String requestedContactPhone, LocalDate requestedDueOn,
                             String requestedSummary, UUID attachmentFileId) {}
    public record BatchCreated(UUID batchId, int requested, List<View> requests) {}

    public CursorPage<View> list(LocalDate from, LocalDate to, String detectionProject, String commissionNumber,
                                 String state, String applicant, String reviewer, String processingMethod,
                                 String cursorValue, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        AuthenticatedUser user = actor.require();
        String filters = """
                  AND (CAST(:from AS date) IS NULL OR request.created_at::date>=CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR request.created_at::date<=CAST(:to AS date))
                  AND (:detection='' OR EXISTS(SELECT 1 FROM commission_detection_selection ds
                    WHERE ds.commission_id=commission.id AND lower(ds.project_name_snapshot) LIKE :detection))
                  AND (:commissionNumber='' OR lower(commission.number) LIKE :commissionNumber)
                  AND (:state='' OR request.state=:state)
                  AND (:applicant='' OR lower(applicant.display_name) LIKE :applicant)
                  AND (:reviewer='' OR lower(COALESCE(reviewer.display_name,'')) LIKE :reviewer)
                  AND (:method='' OR request.processing_method=:method)
                """;
        var statement = jdbc.sql(baseSelect() + " WHERE commission.organization_id=:organization "
                + scopeClause() + filters + """
                  AND (request.updated_at,request.id)<(:cursorAt,:cursorId)
                ORDER BY request.updated_at DESC,request.id DESC LIMIT :limit
                """);
        bind(statement, user, from, to, detectionProject, commissionNumber, state, applicant, reviewer, processingMethod);
        List<View> fetched = statement.param("cursorAt", cursorAt).param("cursorId", cursorId)
                .param("limit", limit + 1).query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        var count = jdbc.sql("""
                SELECT count(*) FROM commission_change_request request
                JOIN commission commission ON commission.id=request.commission_id
                JOIN app_user applicant ON applicant.id=request.requested_by
                LEFT JOIN app_user reviewer ON reviewer.id=request.reviewed_by
                WHERE commission.organization_id=:organization
                """ + scopeClause() + filters);
        bind(count, user, from, to, detectionProject, commissionNumber, state, applicant, reviewer, processingMethod);
        return new CursorPage<>(items, next, hasMore, count.query(Long.class).single());
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(baseSelect() + " WHERE request.id=:id AND commission.organization_id=:organization "
                        + scopeClause())
                .param("id", id).param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
        return statement.query(this::map).optional().orElseThrow(() -> ApiException.notFound(
                "COMMISSION_CHANGE_NOT_FOUND", "委托修改申请不存在或不在当前数据范围内"));
    }

    @Transactional
    public View create(Write body) {
        validate(body);
        CommissionService.View commission = commissions.require(body.commissionId());
        long lockedVersion = jdbc.sql("""
                SELECT version FROM commission WHERE id=:id AND organization_id=:organization FOR UPDATE
                """).param("id", body.commissionId()).param("organization", actor.require().organizationId())
                .query(Long.class).single();
        if (body.expectedVersion() != null && body.expectedVersion() != lockedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", lockedVersion);
        }
        if (commission.state() == CommissionState.DRAFT) throw ApiException.blocked(
                "COMMISSION_CHANGE_NOT_REQUIRED", "草稿委托应直接修改，无需发起变更申请", commission.version(), List.of("请在委托管理中直接编辑"));
        if (List.of(CommissionState.REJECTED, CommissionState.CANCELLED).contains(commission.state())) {
            throw ApiException.blocked("COMMISSION_CHANGE_TERMINAL", "终态委托不能发起修改申请",
                    commission.version(), List.of("当前状态：" + commission.state().name()));
        }
        long openRequests = jdbc.sql("""
                SELECT count(*) FROM commission_change_request
                WHERE commission_id=:commission AND state IN ('DRAFT','SUBMITTED')
                """).param("commission", body.commissionId()).query(Long.class).single();
        if (openRequests > 0) throw ApiException.blocked("COMMISSION_CHANGE_ALREADY_OPEN",
                "该委托已有未完成的修改申请", commission.version(), List.of("请先处理现有草稿或待审申请"));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO commission_change_request(id,commission_id,request_type,processing_method,reason,
                  requested_contact_name,requested_contact_phone,requested_due_on,requested_summary,
                  attachment_file_id,commission_version_snapshot,requested_by)
                VALUES (:id,:commission,:type,:method,:reason,:contact,:phone,:dueOn,:summary,:attachment,
                  :commissionVersion,:actor)
                """).param("id", id).param("commission", body.commissionId()).param("type", upper(body.requestType()))
                .param("method", upper(body.processingMethod())).param("reason", body.reason().trim())
                .param("contact", nullable(body.requestedContactName())).param("phone", nullable(body.requestedContactPhone()))
                .param("dueOn", body.requestedDueOn()).param("summary", nullable(body.requestedSummary()))
                .param("attachment", body.attachmentFileId()).param("commissionVersion", commission.version())
                .param("actor", actor.require().id()).update();
        View created = require(id);
        audit.record("COMMISSION_CHANGE_CREATED", "COMMISSION_CHANGE_REQUEST", id, null, created, null, null);
        return created;
    }

    @Transactional
    public BatchCreated batchCreate(BatchWrite body) {
        if (body == null || body.targets() == null || body.targets().isEmpty() || body.targets().size() > 200
                || body.targets().stream().map(BatchTarget::commissionId).distinct().count() != body.targets().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMMISSION_CHANGE_BATCH_TARGETS_INVALID",
                    "批量修改申请必须包含1至200条不重复委托");
        }
        List<View> created = body.targets().stream().map(target -> create(new Write(target.commissionId(),
                body.requestType(), body.processingMethod(), body.reason(), body.requestedContactName(),
                body.requestedContactPhone(), body.requestedDueOn(), body.requestedSummary(),
                body.attachmentFileId(), target.expectedVersion()))).toList();
        UUID batchId = UUID.randomUUID();
        BatchCreated result = new BatchCreated(batchId, body.targets().size(), created);
        audit.record("COMMISSION_CHANGE_BATCH_CREATED", "COMMISSION_CHANGE_BATCH", batchId, null,
                Map.of("batchId", batchId, "requestIds", created.stream().map(View::id).toList(),
                        "commissionIds", body.targets().stream().map(BatchTarget::commissionId).toList()),
                null, body.reason());
        return result;
    }

    @Transactional
    public View update(UUID id, Write body) {
        validate(body);
        View before = require(id);
        requireOwner(before);
        if (!"DRAFT".equals(before.state())) throw new ApiException(HttpStatus.CONFLICT,
                "COMMISSION_CHANGE_IMMUTABLE", "仅草稿修改申请可编辑");
        if (!before.commissionId().equals(body.commissionId())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "COMMISSION_CHANGE_COMMISSION_IMMUTABLE", "不能更换修改申请所属委托");
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新委托修改申请必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE commission_change_request SET request_type=:type,processing_method=:method,reason=:reason,
                  requested_contact_name=:contact,requested_contact_phone=:phone,requested_due_on=:dueOn,
                  requested_summary=:summary,attachment_file_id=:attachment,version=version+1,updated_at=now()
                WHERE id=:id AND requested_by=:actor AND state='DRAFT' AND version=:version
                """).param("type", upper(body.requestType())).param("method", upper(body.processingMethod()))
                .param("reason", body.reason().trim()).param("contact", nullable(body.requestedContactName()))
                .param("phone", nullable(body.requestedContactPhone())).param("dueOn", body.requestedDueOn())
                .param("summary", nullable(body.requestedSummary())).param("attachment", body.attachmentFileId())
                .param("id", id).param("actor", actor.require().id()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托修改申请已被更新",
                require(id).version());
        View after = require(id);
        audit.record("COMMISSION_CHANGE_UPDATED", "COMMISSION_CHANGE_REQUEST", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT",
                "委托修改申请已被更新", before.version());
        String action = upper(command.action());
        CommissionChangeState next = CommissionChangeState.valueOf(before.state()).transition(action);
        if (List.of("SUBMIT", "WITHDRAW").contains(action)) requireOwner(before);
        if (List.of("APPROVE", "REJECT").contains(action) && before.requestedById().equals(actor.require().id())) {
            throw ApiException.blocked("DUTY_SEPARATION_REQUIRED", "申请人不能审批自己的委托修改申请",
                    before.version(), List.of("请由其他具有业务流转权限的人员审批"));
        }
        Long appliedVersion = null;
        UUID resultCommissionId = null;
        if (action.equals("APPROVE")) {
            ApplyResult result = apply(before);
            appliedVersion = result.version();
            resultCommissionId = result.resultCommissionId();
        }
        int changed = jdbc.sql("""
                UPDATE commission_change_request SET state=:state,
                  reviewed_by=CASE WHEN :reviewed THEN :actor ELSE reviewed_by END,
                  reviewed_at=CASE WHEN :reviewed THEN now() ELSE reviewed_at END,
                  review_comment=CASE WHEN :reviewed THEN :comment ELSE review_comment END,
                  applied_commission_version=CASE WHEN :approved THEN :appliedVersion ELSE applied_commission_version END,
                  result_commission_id=CASE WHEN :approved THEN :resultCommission ELSE result_commission_id END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("state", next.name()).param("reviewed", List.of("APPROVE", "REJECT").contains(action))
                .param("approved", action.equals("APPROVE"))
                .param("actor", actor.require().id()).param("comment", nullable(command.comment()))
                .param("appliedVersion", appliedVersion).param("resultCommission", resultCommissionId)
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托修改申请已被更新",
                require(id).version());
        View after = require(id);
        audit.record("COMMISSION_CHANGE_" + action, "COMMISSION_CHANGE_REQUEST", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private record ApplyResult(long version, UUID resultCommissionId) {}

    private ApplyResult apply(View request) {
        Long currentVersion = jdbc.sql("SELECT version FROM commission WHERE id=:id FOR UPDATE")
                .param("id", request.commissionId()).query(Long.class).single();
        if (currentVersion != request.commissionVersionSnapshot()) throw ApiException.blocked(
                "COMMISSION_CHANGED_SINCE_REQUEST", "申请后委托已发生变化，不能直接审批",
                request.version(), List.of("申请时委托版本：" + request.commissionVersionSnapshot(),
                        "当前委托版本：" + currentVersion, "请驳回后重新发起"));
        return switch (request.processingMethod()) {
            case "UPDATE_CURRENT" -> applyCurrent(request, false);
            case "CREATE_REVISION" -> applyCurrent(request, true);
            case "MANUAL_RECORD" -> new ApplyResult(currentVersion, request.commissionId());
            case "VOID_AND_RECREATE" -> voidAndRecreate(request, currentVersion);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "COMMISSION_CHANGE_METHOD_INVALID", "处理方式无效");
        };
    }

    private ApplyResult applyCurrent(View request, boolean createRevision) {
        if (List.of("SAMPLE", "DETECTION_SELECTION").contains(request.requestType())) {
            throw ApiException.blocked("COMMISSION_CHANGE_STRUCTURED_PAYLOAD_REQUIRED",
                    "样品或检测项变更必须使用结构化重建流程", request.version(),
                    List.of("当前申请仅有文本说明，不足以安全更改冻结快照",
                            "请改用“作废并重建”或补充结构化选项"));
        }
        if (createRevision) createRevisionSnapshot(request);
        int changed = jdbc.sql("""
                UPDATE commission SET
                  contact_name=CASE WHEN :type='CONTACT' AND CAST(:contact AS varchar) IS NOT NULL
                    THEN CAST(:contact AS varchar) ELSE contact_name END,
                  contact_phone=CASE WHEN :type='CONTACT' THEN :phone ELSE contact_phone END,
                  due_on=CASE WHEN :type='DUE_DATE' AND CAST(:dueOn AS date) IS NOT NULL
                    THEN CAST(:dueOn AS date) ELSE due_on END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("type", request.requestType()).param("contact", request.requestedContactName())
                .param("phone", request.requestedContactPhone()).param("dueOn", request.requestedDueOn())
                .param("id", request.commissionId()).param("version", request.commissionVersionSnapshot()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被更新",
                request.commissionVersionSnapshot());
        long version = request.commissionVersionSnapshot() + 1;
        audit.record("COMMISSION_CHANGE_APPLIED", "COMMISSION", request.commissionId(), null,
                Map.of("changeRequestId", request.id(), "newVersion", version), null, request.reason());
        return new ApplyResult(version, request.commissionId());
    }

    private void createRevisionSnapshot(View request) {
        int revision = jdbc.sql("SELECT COALESCE(max(revision_number),0)+1 FROM commission_revision WHERE commission_id=:id")
                .param("id", request.commissionId()).query(Integer.class).single();
        int inserted = jdbc.sql("""
                INSERT INTO commission_revision(id,commission_id,revision_number,change_request_id,snapshot_json,created_by)
                SELECT :id,c.id,:revision,:request,
                  jsonb_build_object('commission',to_jsonb(c),'detectionSelections',COALESCE((
                    SELECT jsonb_agg(jsonb_build_object('selection',to_jsonb(ds),'parameters',COALESCE((
                      SELECT jsonb_agg(to_jsonb(ps) ORDER BY ps.sort_order,ps.id)
                      FROM commission_parameter_selection ps WHERE ps.detection_selection_id=ds.id),'[]'::jsonb))
                      ORDER BY ds.sort_order,ds.id)
                    FROM commission_detection_selection ds WHERE ds.commission_id=c.id),'[]'::jsonb),
                    'sampleGroups',COALESCE((SELECT jsonb_agg(to_jsonb(g) ORDER BY g.sort_order,g.id)
                      FROM commission_sample_group g WHERE g.commission_id=c.id),'[]'::jsonb),
                    'requestedChange',jsonb_build_object('type',:type,'reason',:reason,'summary',:summary)),
                  :actor
                FROM commission c WHERE c.id=:commission
                """).param("id", UUID.randomUUID()).param("revision", revision).param("request", request.id())
                .param("type", request.requestType()).param("reason", request.reason())
                .param("summary", request.requestedSummary()).param("actor", actor.require().id())
                .param("commission", request.commissionId()).update();
        if (inserted != 1) throw ApiException.conflict("COMMISSION_REVISION_SNAPSHOT_FAILED",
                "未能创建委托修订快照", request.commissionVersionSnapshot());
    }

    private ApplyResult voidAndRecreate(View request, long currentVersion) {
        String state = jdbc.sql("SELECT state FROM commission WHERE id=:id").param("id", request.commissionId())
                .query(String.class).single();
        if (!List.of("PRE_ACCEPTED", "ACCEPTED", "SAMPLING", "ASSIGNED").contains(state)) {
            throw ApiException.blocked("COMMISSION_RECREATE_STATE_BLOCKED",
                    "当前委托阶段不允许作废并重建", request.version(),
                    List.of("当前状态：" + state, "检测或报告阶段应使用修订流程"));
        }
        long downstream = jdbc.sql("""
                SELECT (SELECT count(*) FROM sample WHERE commission_id=:id AND state<>'EXPECTED')
                  +(SELECT count(*) FROM test_task WHERE commission_id=:id)
                """).param("id", request.commissionId()).query(Long.class).single();
        if (downstream > 0) throw ApiException.blocked("COMMISSION_RECREATE_DOWNSTREAM_EXISTS",
                "委托已产生样品或任务，不能作废并重建", request.version(),
                List.of("请使用委托修订、复测或数据修改申请"));
        UUID replacement = UUID.randomUUID();
        String number = numbering.next("COMMISSION", "WT");
        int inserted = jdbc.sql("""
                INSERT INTO commission(id,organization_id,project_id,customer_id,number,contact_name,contact_phone,
                  commissioned_on,due_on,state,created_by,intake_source)
                SELECT :replacement,organization_id,project_id,customer_id,:number,
                  COALESCE(:contact,contact_name),CASE WHEN :type='CONTACT' THEN :phone ELSE contact_phone END,
                  commissioned_on,CASE WHEN :type='DUE_DATE' AND CAST(:dueOn AS date) IS NOT NULL
                    THEN CAST(:dueOn AS date) ELSE due_on END,
                  'DRAFT',:actor,'MANUAL' FROM commission WHERE id=:source AND version=:version
                """).param("replacement", replacement).param("number", number)
                .param("contact", request.requestedContactName()).param("phone", request.requestedContactPhone())
                .param("type", request.requestType()).param("dueOn", request.requestedDueOn())
                .param("actor", actor.require().id()).param("source", request.commissionId())
                .param("version", currentVersion).update();
        if (inserted != 1) throw ApiException.conflict("VERSION_CONFLICT", "委托已被更新", currentVersion);
        copySelections(request.commissionId(), replacement);
        int cancelled = jdbc.sql("""
                UPDATE commission SET state='CANCELLED',version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("id", request.commissionId()).param("version", currentVersion).update();
        if (cancelled == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被更新", currentVersion);
        audit.record("COMMISSION_VOIDED_AND_RECREATED", "COMMISSION", request.commissionId(), null,
                Map.of("replacementCommissionId", replacement, "replacementNumber", number), null, request.reason());
        return new ApplyResult(0L, replacement);
    }

    private record GroupCopy(UUID id, String clientKey, String name, String description,
                             java.math.BigDecimal quantity, String unit, int sortOrder, String snapshot) {}
    private record SelectionCopy(UUID id, UUID projectRevisionId, String projectName, String symbol,
                                 int revisionNumber, int sortOrder) {}
    private record ParameterCopy(UUID id, UUID selectionId, UUID parameterId, UUID groupId, String code,
                                 String name, String unit, boolean required, boolean defaultSelected,
                                 int sortOrder, String snapshot) {}

    private void copySelections(UUID source, UUID target) {
        Map<UUID, UUID> groups = new HashMap<>();
        for (GroupCopy group : jdbc.sql("""
                SELECT id,client_key,name,description,quantity,unit,sort_order,snapshot_json::text
                FROM commission_sample_group WHERE commission_id=:id ORDER BY sort_order,id
                """).param("id", source).query((rs, row) -> new GroupCopy(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6),
                rs.getInt(7), rs.getString(8))).list()) {
            UUID id = UUID.randomUUID(); groups.put(group.id(), id);
            jdbc.sql("""
                    INSERT INTO commission_sample_group(id,commission_id,client_key,name,description,quantity,unit,
                      sort_order,snapshot_json) VALUES (:id,:commission,:key,:name,:description,:quantity,:unit,
                      :sortOrder,CAST(:snapshot AS jsonb))
                    """).param("id", id).param("commission", target).param("key", group.clientKey())
                    .param("name", group.name()).param("description", group.description())
                    .param("quantity", group.quantity()).param("unit", group.unit()).param("sortOrder", group.sortOrder())
                    .param("snapshot", group.snapshot()).update();
        }
        Map<UUID, UUID> selections = new HashMap<>();
        for (SelectionCopy selection : jdbc.sql("""
                SELECT id,project_revision_id,project_name_snapshot,project_symbol_snapshot,
                  revision_number_snapshot,sort_order FROM commission_detection_selection
                WHERE commission_id=:id ORDER BY sort_order,id
                """).param("id", source).query((rs, row) -> new SelectionCopy(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6))).list()) {
            UUID id = UUID.randomUUID(); selections.put(selection.id(), id);
            jdbc.sql("""
                    INSERT INTO commission_detection_selection(id,commission_id,project_revision_id,
                      project_name_snapshot,project_symbol_snapshot,revision_number_snapshot,sort_order)
                    VALUES (:id,:commission,:revision,:name,:symbol,:revisionNumber,:sortOrder)
                    """).param("id", id).param("commission", target).param("revision", selection.projectRevisionId())
                    .param("name", selection.projectName()).param("symbol", selection.symbol())
                    .param("revisionNumber", selection.revisionNumber()).param("sortOrder", selection.sortOrder()).update();
        }
        List<ParameterCopy> parameters = jdbc.sql("""
                SELECT ps.id,ps.detection_selection_id,ps.parameter_id,ps.sample_group_id,
                  ps.parameter_code_snapshot,ps.parameter_name_snapshot,ps.unit_snapshot,ps.required_snapshot,
                  ps.default_snapshot,ps.sort_order,ps.snapshot_json::text
                FROM commission_parameter_selection ps
                JOIN commission_detection_selection ds ON ds.id=ps.detection_selection_id
                WHERE ds.commission_id=:id ORDER BY ds.sort_order,ps.sort_order,ps.id
                """).param("id", source).query((rs, row) -> new ParameterCopy(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9),
                rs.getInt(10), rs.getString(11))).list();
        for (ParameterCopy parameter : parameters) {
            jdbc.sql("""
                    INSERT INTO commission_parameter_selection(id,detection_selection_id,parameter_id,sample_group_id,
                      parameter_code_snapshot,parameter_name_snapshot,unit_snapshot,required_snapshot,
                      default_snapshot,sort_order,snapshot_json)
                    VALUES (:id,:selection,:parameter,:group,:code,:name,:unit,:required,:defaultSelected,
                      :sortOrder,CAST(:snapshot AS jsonb))
                    """).param("id", UUID.randomUUID()).param("selection", selections.get(parameter.selectionId()))
                    .param("parameter", parameter.parameterId()).param("group", groups.get(parameter.groupId()))
                    .param("code", parameter.code()).param("name", parameter.name()).param("unit", parameter.unit())
                    .param("required", parameter.required()).param("defaultSelected", parameter.defaultSelected())
                    .param("sortOrder", parameter.sortOrder()).param("snapshot", parameter.snapshot()).update();
        }
    }

    private void validate(Write body) {
        if (body.commissionId() == null || body.reason() == null || body.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMMISSION_CHANGE_REQUIRED", "委托和修改原因不能为空");
        }
        if (!TYPES.contains(upper(body.requestType()))) throw new ApiException(HttpStatus.BAD_REQUEST,
                "COMMISSION_CHANGE_TYPE_INVALID", "修改类型无效");
        if (!METHODS.contains(upper(body.processingMethod()))) throw new ApiException(HttpStatus.BAD_REQUEST,
                "COMMISSION_CHANGE_METHOD_INVALID", "处理方式无效");
        if (body.attachmentFileId() != null) {
            long attachment = jdbc.sql("""
                    SELECT count(*) FROM stored_file file JOIN app_user uploader ON uploader.id=file.uploaded_by
                    WHERE file.id=:id AND uploader.organization_id=:organization
                    """).param("id", body.attachmentFileId()).param("organization", actor.require().organizationId())
                    .query(Long.class).single();
            if (attachment == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMMISSION_CHANGE_ATTACHMENT_INVALID", "附件不存在或不属于当前机构");
        }
        if ("CONTACT".equals(upper(body.requestType())) && (body.requestedContactName() == null
                || body.requestedContactName().isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "COMMISSION_CHANGE_CONTACT_REQUIRED", "联系人修改必须填写新联系人");
        if ("DUE_DATE".equals(upper(body.requestType())) && body.requestedDueOn() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMMISSION_CHANGE_DUE_DATE_REQUIRED", "时限修改必须填写新到期日期");
        }
    }

    private void requireOwner(View value) {
        if (!value.requestedById().equals(actor.require().id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "COMMISSION_CHANGE_OWNER_REQUIRED",
                    "只有申请人可编辑、提交或撤回该申请");
        }
    }

    private void bind(JdbcClient.StatementSpec statement, AuthenticatedUser user, LocalDate from, LocalDate to,
                      String detectionProject, String commissionNumber, String state, String applicant,
                      String reviewer, String processingMethod) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("from", from).param("to", to).param("detection", like(detectionProject))
                .param("commissionNumber", like(commissionNumber)).param("state", upper(state))
                .param("applicant", like(applicant)).param("reviewer", like(reviewer))
                .param("method", upper(processingMethod));
    }

    private String baseSelect() {
        return """
                SELECT request.id,request.commission_id,commission.number commission_number,
                  commission.commissioned_on,COALESCE(selection.projects,'') detection_projects,
                  request.request_type,request.processing_method,request.reason,request.requested_contact_name,
                  request.requested_contact_phone,request.requested_due_on,request.requested_summary,
                  request.attachment_file_id,file.original_name attachment_name,request.state,
                  request.requested_by,applicant.display_name requested_by_name,request.created_at,
                  reviewer.display_name reviewed_by_name,request.reviewed_at,request.review_comment,
                  request.commission_version_snapshot,request.applied_commission_version,
                  request.result_commission_id,request.version,request.updated_at
                FROM commission_change_request request
                JOIN commission commission ON commission.id=request.commission_id
                JOIN app_user applicant ON applicant.id=request.requested_by
                LEFT JOIN app_user reviewer ON reviewer.id=request.reviewed_by
                LEFT JOIN stored_file file ON file.id=request.attachment_file_id
                LEFT JOIN LATERAL (SELECT string_agg(DISTINCT ds.project_name_snapshot,'、'
                    ORDER BY ds.project_name_snapshot) projects FROM commission_detection_selection ds
                  WHERE ds.commission_id=commission.id) selection ON true
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        String method = rs.getString("processing_method");
        String type = rs.getString("request_type");
        return new View(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("commission_number"), rs.getObject("commissioned_on", LocalDate.class),
                rs.getString("detection_projects"), type, method, rs.getString("reason"),
                rs.getString("requested_contact_name"), rs.getString("requested_contact_phone"),
                rs.getObject("requested_due_on", LocalDate.class), rs.getString("requested_summary"),
                rs.getObject("attachment_file_id", UUID.class), rs.getString("attachment_name"), state,
                rs.getObject("requested_by", UUID.class), rs.getString("requested_by_name"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("reviewed_by_name"),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getString("review_comment"),
                rs.getLong("commission_version_snapshot"), rs.getObject("applied_commission_version", Long.class),
                rs.getObject("result_commission_id", UUID.class), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), CommissionChangeState.valueOf(state).allowedActions(),
                blockers(state, method, type));
    }

    private List<String> blockers(String state, String method, String type) {
        if (!"SUBMITTED".equals(state)) return List.of();
        if (("UPDATE_CURRENT".equals(method) || "CREATE_REVISION".equals(method))
                && List.of("SAMPLE", "DETECTION_SELECTION").contains(type)) {
            return List.of("缺少结构化样品或检测选项，不能直接覆盖冻结快照");
        }
        return List.of();
    }

    private String scopeClause() {
        return """
                 AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=commission.created_by AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM test_task task JOIN person person ON person.id=task.assigned_to
                      WHERE task.commission_id=commission.id AND person.department_id=:department)))
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=commission.project_id AND member.user_id=:actor))
                  OR (:scope='SELF' AND (commission.created_by=:actor OR EXISTS(SELECT 1 FROM test_task task
                    WHERE task.commission_id=commission.id AND task.assigned_to=:person))))
                """;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private String upper(String value) { return text(value).toUpperCase(); }
    private String like(String value) { return "%" + text(value).toLowerCase() + "%"; }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
