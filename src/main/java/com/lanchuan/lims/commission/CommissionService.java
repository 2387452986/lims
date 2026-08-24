package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.configuration.DocumentControlSnapshotService;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CommissionService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final List<Option> PROJECT_PROGRESS_OPTIONS = options(
            "FOUNDATION_FACILITIES", "地基基础设施", "MAIN_COMPLETED", "主体竣工",
            "DECORATION_COMPLETED", "装饰竣工", "PENDING_COMPLETION", "待竣工",
            "COMPLETED", "竣工", "TERMINATED", "终止", "TEMPORARY_WORK", "临时工程",
            "MAIN_WORK", "主体工程", "AUXILIARY_FACILITIES", "附属设施",
            "OTHER_FACILITIES", "其他设施", "NOT_APPLICABLE", "/");
    private static final List<Option> TESTING_TYPE_OPTIONS = options(
            "PARALLEL_SUPERVISION", "监理平行检测", "PROJECT_OWNER", "项目法人委托检测",
            "CONSTRUCTION_SELF_TEST", "施工自检", "ENTRUSTED", "委托检测",
            "WITNESSED_ENTRUSTED", "见证委托", "ENTITY", "实体检测", "SUPERVISION", "监督检测",
            "WITNESSED_SAMPLING", "见证（取）送样", "WITNESSED", "见证检测", "SAMPLING", "抽样检测",
            "ARBITRATION", "仲裁检测", "WITNESSED_RETEST", "见证复检",
            "ENTRUSTED_RETEST", "委托复检", "COMPARISON", "对比检测");
    private static final List<Option> TESTING_STANDARD_OPTIONS = options(
            "NATIONAL", "国标", "INDUSTRY", "行标", "WATER_CONSERVANCY", "水利",
            "HIGHWAY", "公路", "WATER_TRANSPORT", "水运", "CONSTRUCTION", "建工",
            "ELECTRIC_POWER", "电力", "ENERGY", "能源", "OTHER_INDUSTRY", "其他行标");
    private static final List<Option> TESTING_PLACE_OPTIONS = options("LAB", "室内", "FIELD", "现场");
    private static final List<Option> SAMPLE_HANDLING_OPTIONS = options(
            "CLIENT_DISPOSE", "委托检测单位处理", "CLIENT_RETRIEVE", "取回样品");
    private static final List<Option> REPORT_DELIVERY_OPTIONS = options(
            "PICKUP", "自取", "MAIL", "邮寄", "DELIVERED", "受托方送达", "EMAIL", "邮箱（电子版）");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberingService numbering;
    private final AuditService audit;
    private final DataScopeService access;
    private final DocumentControlSnapshotService documentControls;

    public CommissionService(JdbcClient jdbc, CurrentActor actor, NumberingService numbering, AuditService audit,
                             DataScopeService access, DocumentControlSnapshotService documentControls) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.numbering = numbering;
        this.audit = audit;
        this.access = access;
        this.documentControls = documentControls;
    }

    public record SampleGroup(String clientKey, String name, String description, String quantity, String unit,
                              List<UUID> parameterIds, Map<String, List<String>> fieldValues) {}
    public record DetectionSelection(UUID projectRevisionId, List<UUID> parameterIds,
                                     List<SampleGroup> sampleGroups) {}
    public record IntakeDetails(String projectProgress, String testingType, String testingStandard,
                                String testingPlace, String sampleHandling, String reportDelivery,
                                String reportDeliveryAddress, int reportCopies, String projectPart, String monomer,
                                String settlementContact, String witnessUnit, String witnessName, String witnessPhone,
                                String witnessCertificate, String samplingUnit, String samplingPerson,
                                String samplingPhone, String samplingCertificate, String constructionUnit,
                                boolean needSampling, boolean electronicSignature, boolean cmaSeal,
                                boolean qualificationSeal, boolean externalCommission, String externalOrganization,
                                String externalNumber, String sampleReceivingAddress, LocalDate expectedReportOn,
                                String remark, String sourceRemark, List<String> ageReportValues,
                                List<UUID> attachmentFileIds) {}
    public record Create(UUID projectId, UUID customerId, String contactName, String contactPhone,
                         LocalDate commissionedOn, LocalDate dueOn, IntakeDetails details,
                         List<DetectionSelection> detectionSelections) {}
    public record Update(String contactName, String contactPhone, LocalDate commissionedOn, LocalDate dueOn,
                         IntakeDetails details, List<DetectionSelection> detectionSelections, long expectedVersion) {}
    public record FileView(UUID id, String name) {}
    public record IntakeDetailsView(String projectProgress, String testingType, String testingStandard,
                                    String testingPlace, String sampleHandling, String reportDelivery,
                                    String reportDeliveryAddress, int reportCopies, String projectPart, String monomer,
                                    String settlementContact, String witnessUnit, String witnessName,
                                    String witnessPhone, String witnessCertificate, String samplingUnit,
                                    String samplingPerson, String samplingPhone, String samplingCertificate,
                                    String constructionUnit, boolean needSampling, boolean electronicSignature,
                                    boolean cmaSeal, boolean qualificationSeal, boolean externalCommission,
                                    String externalOrganization, String externalNumber, String sampleReceivingAddress,
                                    LocalDate expectedReportOn, String remark, String sourceRemark,
                                    List<String> ageReportValues, List<FileView> attachments) {}
    public record View(UUID id, String number, String precommissionNumber,
                       UUID projectId, String projectName, UUID customerId,
                       String customerName, String contactName, String contactPhone,
                       LocalDate commissionedOn, LocalDate dueOn,
                       CommissionState state, long version, long selectionCount, OffsetDateTime selectionFrozenAt,
                       OffsetDateTime updatedAt, IntakeDetailsView details, List<String> allowedActions) {}
    public record Summary(UUID rowId, UUID id, UUID sampleId, UUID sampleGroupId, String sampleNumber,
                          String sampleName, String sampleQuantity, String sampleUnit,
                          String number, String precommissionNumber, long managementSequence, Long internalSequence,
                          String externalNumber, UUID projectId, String projectNumber, String projectName,
                          UUID customerId, String customerName, String contactName, LocalDate commissionedOn,
                          LocalDate dueOn, CommissionState state, String testingPlace, boolean externalCommission,
                          String detectionProjects, long parameterCount, long sampleGroupCount, String flowStatus, String taskStatus,
                          String reportStatus, String printStatus, String receiverName, String departmentName,
                          long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record SampleGroupView(UUID id, String clientKey, String name, String description, String quantity,
                                  String unit, int sortOrder, List<UUID> parameterIds,
                                  Map<String, List<String>> fieldValues) {}
    public record ParameterSelectionView(UUID id, UUID parameterId, String code, String name, String unit,
                                         boolean required, boolean defaultCommissioned, int sortOrder,
                                         UUID sampleGroupId) {}
    public record DetectionSelectionView(UUID id, UUID projectRevisionId, String projectName, String projectSymbol,
                                         int revisionNumber, int sortOrder, List<ParameterSelectionView> parameters,
                                         List<SampleGroupView> sampleGroups) {}
    public record Option(String code, String label) {}
    public record CommissionOptions(List<Option> projectProgress, List<Option> testingTypes,
                                    List<Option> testingStandards, List<Option> testingPlaces,
                                    List<Option> sampleHandling, List<Option> reportDelivery) {}
    public record PersonDefault(String name, String phone, String certificateNumber) {}
    public record ProjectDefaults(UUID projectId, String projectNumber, String projectName, UUID customerId,
                                  String customerName, String constructionUnit, String witnessUnit,
                                  String samplingUnit, PersonDefault entruster, PersonDefault witness,
                                  PersonDefault sampler, String settlementContact, boolean electronicSignature,
                                  boolean cmaSeal, boolean qualificationSeal, List<Option> structureParts) {}
    public record ListQuery(String query, LocalDate commissionedFrom, LocalDate commissionedTo, String states,
                            String flowStates, String taskStates, String testingPlace, Boolean externalCommission,
                            String detectionProject, String commissionNumber, String sampleNumber,
                            String precommissionNumber, String customer, String projectNumber, String projectName,
                            Long managementSequenceMin, Long managementSequenceMax, UUID departmentId,
                            Boolean waitingFlow, String viewMode, String cursor, int limit) {}
    public record BatchTarget(UUID id, long expectedVersion) {}
    public record BatchTransition(List<BatchTarget> targets, String action, String reasonCode, String comment) {}
    public record BatchResult(int requested, int completed, List<UUID> ids) {}
    public record BatchCopy(List<BatchTarget> targets, String comment) {}
    public record InternalSequenceTarget(UUID id, long expectedVersion, Long internalSequence) {}
    public record BatchInternalSequence(List<InternalSequenceTarget> targets, String comment) {}
    public record ExternalNumberWrite(String externalNumber, long expectedVersion, String comment) {}
    public record PrintRestrictionWrite(boolean restricted, Integer printLimit, String reason,
                                        long expectedVersion) {}
    public record PrintRestrictionView(UUID commissionId, boolean restricted, Integer printLimit,
                                       String reason, long version) {}
    public record SampleGroupPartWrite(UUID sampleGroupId, List<UUID> projectPartIds) {}
    public record SampleGroupPartCommand(List<SampleGroupPartWrite> bindings, long expectedVersion,
                                         String comment) {}
    public record SampleGroupPartView(UUID sampleGroupId, UUID projectPartId, String partName) {}
    public record SampleGroupPartsResult(List<SampleGroupPartView> bindings) {}
    public record TerminationWrite(String reasonCode, String comment, long expectedVersion) {}
    public record TerminationResult(UUID commissionId, int cancelledTaskCount, String state, long version) {}
    public record ExportWrite(List<BatchTarget> targets, String comment) {}
    public record JobQueued(UUID jobId, String jobType, String state) {}
    public record ElectronicDocumentReadiness(boolean ready, List<String> blockers) {}
    public record ElectronicDocumentView(UUID id, int revision, UUID templateVersionId, UUID fileId,
                                         UUID jobId, String createdBy, OffsetDateTime createdAt) {}
    public record FlowEvent(UUID id, OffsetDateTime occurredAt, String actorName, String action,
                            String resourceType, String reasonCode, String comment) {}
    public record RelationWrite(UUID targetCommissionId, String relationType, String comment, long expectedVersion) {}
    public record RelationView(UUID id, UUID sourceCommissionId, String sourceNumber, UUID targetCommissionId,
                               String targetNumber, String relationType, String comment, String createdBy,
                               OffsetDateTime createdAt) {}
    public record TransferWrite(UUID toDepartmentId, String comment, long expectedVersion) {}
    public record TransferView(UUID id, UUID commissionId, String commissionNumber, UUID fromDepartmentId,
                               String fromDepartment, UUID toDepartmentId, String toDepartment, String state,
                               String comment, String createdBy, String reviewedBy, long version,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record PrintResult(UUID commissionId, String documentType, String printStatus, long version) {}

    @Transactional
    public View create(Create body) {
        validateDates(body.commissionedOn(), body.dueOn());
        access.requireProject(body.projectId());
        UUID id = UUID.randomUUID();
        String number = numbering.next("COMMISSION", "WT");
        int inserted = jdbc.sql("""
                INSERT INTO commission(id, organization_id, project_id, customer_id, number, contact_name,
                  contact_phone, commissioned_on, due_on, state, created_by, received_department_id,
                  report_print_restricted,report_print_limit)
                SELECT :id, :organization, p.id, c.id, :number, :contact, :phone, :commissionedOn, :dueOn,
                  'DRAFT', :actor, :department,p.report_print_restricted,p.customer_print_limit FROM project p, customer c
                WHERE p.id=:project AND c.id=:customer AND p.organization_id=:organization
                  AND p.customer_id=c.id AND p.status='ACTIVE' AND c.active
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("number", number).param("contact", requiredText(body.contactName(), "委托人不能为空"))
                .param("phone", nullable(body.contactPhone()))
                .param("commissionedOn", body.commissionedOn()).param("dueOn", body.dueOn())
                .param("actor", actor.require().id()).param("department", actor.require().departmentId())
                .param("project", body.projectId()).param("customer", body.customerId())
                .update();
        if (inserted == 0) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMMISSION_REFERENCE_INVALID", "工程或客户不存在、已停用或不属于当前机构");
        }
        saveSelections(id, body.detectionSelections());
        saveDetails(id, normalizedDetails(body.details()), false);
        View created = require(id);
        audit.record("COMMISSION_CREATED", "COMMISSION", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Update body) {
        View before = require(id);
        if (before.state() != CommissionState.DRAFT) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "COMMISSION_SELECTIONS_FROZEN",
                    "委托提交后不能修改检测项目和参数快照");
        }
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        }
        validateDates(body.commissionedOn(), body.dueOn());
        IntakeDetails details = normalizedDetails(body.details());
        int changed = jdbc.sql("""
                UPDATE commission SET contact_name=:contact, contact_phone=:phone,
                  commissioned_on=:commissionedOn, due_on=:dueOn,
                  project_progress=:projectProgress,testing_type=:testingType,testing_standard=:testingStandard,
                  testing_place=:testingPlace,sample_handling=:sampleHandling,report_delivery=:reportDelivery,
                  report_delivery_address=:deliveryAddress,report_copies=:reportCopies,project_part=:projectPart,
                  monomer=:monomer,settlement_contact=:settlementContact,witness_unit=:witnessUnit,
                  witness_name=:witnessName,witness_phone=:witnessPhone,witness_certificate=:witnessCertificate,
                  sampling_unit=:samplingUnit,sampling_person=:samplingPerson,sampling_phone=:samplingPhone,
                  sampling_certificate=:samplingCertificate,construction_unit=:constructionUnit,
                  need_sampling=:needSampling,electronic_signature=:electronicSignature,cma_seal=:cmaSeal,
                  qualification_seal=:qualificationSeal,external_commission=:externalCommission,
                  external_organization=:externalOrganization,external_number=:externalNumber,
                  sample_receiving_address=:receivingAddress,expected_report_on=:expectedReportOn,
                  remark=:remark,source_remark=:sourceRemark,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND version=:version
                """).param("contact", requiredText(body.contactName(), "委托人不能为空"))
                .param("phone", nullable(body.contactPhone()))
                .param("commissionedOn", body.commissionedOn()).param("dueOn", body.dueOn())
                .param("projectProgress", details.projectProgress()).param("testingType", details.testingType())
                .param("testingStandard", details.testingStandard()).param("testingPlace", details.testingPlace())
                .param("sampleHandling", details.sampleHandling()).param("reportDelivery", details.reportDelivery())
                .param("deliveryAddress", nullable(details.reportDeliveryAddress()))
                .param("reportCopies", details.reportCopies()).param("projectPart", nullable(details.projectPart()))
                .param("monomer", nullable(details.monomer())).param("settlementContact", nullable(details.settlementContact()))
                .param("witnessUnit", nullable(details.witnessUnit())).param("witnessName", nullable(details.witnessName()))
                .param("witnessPhone", nullable(details.witnessPhone())).param("witnessCertificate", nullable(details.witnessCertificate()))
                .param("samplingUnit", nullable(details.samplingUnit())).param("samplingPerson", nullable(details.samplingPerson()))
                .param("samplingPhone", nullable(details.samplingPhone())).param("samplingCertificate", nullable(details.samplingCertificate()))
                .param("constructionUnit", nullable(details.constructionUnit())).param("needSampling", details.needSampling())
                .param("electronicSignature", details.electronicSignature()).param("cmaSeal", details.cmaSeal())
                .param("qualificationSeal", details.qualificationSeal()).param("externalCommission", details.externalCommission())
                .param("externalOrganization", nullable(details.externalOrganization())).param("externalNumber", nullable(details.externalNumber()))
                .param("receivingAddress", nullable(details.sampleReceivingAddress())).param("expectedReportOn", details.expectedReportOn())
                .param("remark", nullable(details.remark())).param("sourceRemark", nullable(details.sourceRemark()))
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        jdbc.sql("DELETE FROM commission_detection_selection WHERE commission_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM commission_sample_group WHERE commission_id=:id").param("id", id).update();
        saveSelections(id, body.detectionSelections());
        saveDetails(id, details, true);
        View after = require(id);
        audit.record("COMMISSION_UPDATED", "COMMISSION", id, before, after, null, null);
        return after;
    }

    public CursorPage<Summary> list(ListQuery query) {
        ListQuery filters = query == null ? new ListQuery("", null, null, "", "", "", "", null,
                "", "", "", "", "", "", "", null, null, null, null, "COMMISSION", null, 20) : query;
        boolean sampleView = "SAMPLE".equals(upper(filters.viewMode()));
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        AuthenticatedUser user = actor.require();
        String filtersSql = listFilters();
        var statement = jdbc.sql(summarySelect() + " WHERE c.organization_id=:organization " + scopeClause()
                + filtersSql + " AND (view_row.row_updated_at,view_row.row_id)<(:cursorAt,:cursorId)"
                + " ORDER BY view_row.row_updated_at DESC,view_row.row_id DESC LIMIT :limit");
        bindList(statement, user, filters);
        List<Summary> fetched = statement.param("sampleView", sampleView)
                .param("cursorAt", cursorAt).param("cursorId", cursorId)
                .param("limit", limit + 1).query(this::mapSummary).list();
        boolean hasMore = fetched.size() > limit;
        List<Summary> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().rowId()).encode() : null;
        var count = jdbc.sql("""
                SELECT count(*) FROM commission c
                JOIN project p ON p.id=c.project_id JOIN customer u ON u.id=c.customer_id
                """ + listViewJoin() + flowSummaryJoin() + """
                LEFT JOIN LATERAL (SELECT CASE WHEN count(*)=0 THEN 'NOT_ASSIGNED'
                  WHEN bool_and(t.state='COMPLETED') THEN 'COMPLETED'
                  WHEN bool_or(t.state='RETURNED') THEN 'RETURNED'
                  WHEN bool_or(t.state IN ('IN_PROGRESS','SUBMITTED')) THEN 'IN_PROGRESS'
                  WHEN bool_or(t.state IN ('ASSIGNED','ACCEPTED')) THEN 'ASSIGNED' ELSE 'UNASSIGNED' END status
                  FROM test_task t WHERE t.commission_id=c.id) task_summary ON true
                """ + " WHERE c.organization_id=:organization " + scopeClause() + filtersSql);
        bindList(count, user, filters);
        long total = count.param("sampleView", sampleView).query(Long.class).single();
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        access.requireCommission(id);
        View view = jdbc.sql(baseSelect() + " WHERE c.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("COMMISSION_NOT_FOUND", "委托不存在"));
        return withCollections(view);
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        CommissionState next = before.state().transition(action);
        List<String> blockers = blockers(id, action);
        if (!blockers.isEmpty()) throw ApiException.blocked("COMMISSION_BLOCKED", "委托尚不满足迁移条件", before.version(), blockers);
        String precommissionNumber = action.equals("SUBMIT") && before.precommissionNumber() == null
                ? numbering.next("PRECOMMISSION", "YWT") : before.precommissionNumber();
        int changed = jdbc.sql("""
                UPDATE commission SET state=:state,
                  precommission_number=CASE WHEN :action='SUBMIT' THEN COALESCE(precommission_number,:precommissionNumber)
                    ELSE precommission_number END,
                  submitted_at=CASE WHEN :action='SUBMIT' THEN now() ELSE submitted_at END,
                  accepted_by=CASE WHEN :action='ACCEPT' THEN :actor ELSE accepted_by END,
                  accepted_at=CASE WHEN :action='ACCEPT' THEN now() ELSE accepted_at END,
                  selection_frozen_at=CASE WHEN :action='ACCEPT' THEN now() ELSE selection_frozen_at END,
                  version=version+1, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("state", next.name()).param("action", action)
                .param("precommissionNumber", precommissionNumber).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        if (action.equals("ACCEPT")) {
            documentControls.snapshotCommission(id);
            generateExpectedSamples(id);
        }
        View after = require(id);
        audit.record("COMMISSION_" + action, "COMMISSION", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions());
    }

    @Transactional
    public BatchResult batchTransition(BatchTransition command) {
        if (command == null || command.targets() == null || command.targets().isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_EMPTY", "请至少选择一条委托");
        }
        if (command.targets().size() > 200 || command.targets().stream().map(BatchTarget::id).distinct().count()
                != command.targets().size()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_INVALID", "单次批量操作不得超过200条且不能包含重复委托");
        }
        List<UUID> completed = new ArrayList<>();
        for (BatchTarget target : command.targets()) {
            transition(target.id(), new TransitionCommand(command.action(), target.expectedVersion(),
                    command.reasonCode(), command.comment()));
            completed.add(target.id());
        }
        audit.record("COMMISSION_BATCH_" + upper(command.action()), "COMMISSION", null, null,
                Map.of("count", completed.size(), "ids", completed), command.reasonCode(), command.comment());
        return new BatchResult(command.targets().size(), completed.size(), List.copyOf(completed));
    }

    @Transactional
    public BatchResult batchInternalSequence(BatchInternalSequence command) {
        if (command == null || command.targets() == null || command.targets().isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_EMPTY", "请至少选择一条委托");
        }
        if (command.targets().size() > 200 || command.targets().stream().map(InternalSequenceTarget::id)
                .distinct().count() != command.targets().size()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_INVALID", "单次批量修改不得超过200条且不能包含重复委托");
        }
        List<UUID> completed = new ArrayList<>();
        for (InternalSequenceTarget target : command.targets()) {
            if (target.internalSequence() != null && target.internalSequence() <= 0) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "COMMISSION_INTERNAL_SEQUENCE_INVALID", "内部流水必须为正整数或留空");
            }
            View before = require(target.id());
            if (before.version() != target.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", before.version());
            }
            int changed = jdbc.sql("""
                    UPDATE commission SET internal_sequence=:internalSequence,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("internalSequence", target.internalSequence()).param("id", target.id())
                    .param("organization", actor.require().organizationId()).param("version", target.expectedVersion())
                    .update();
            if (changed == 0) throw ApiException.conflict(
                    "VERSION_CONFLICT", "委托已被其他用户更新", require(target.id()).version());
            completed.add(target.id());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", completed.size());
        result.put("ids", List.copyOf(completed));
        result.put("values", command.targets().stream().map(target -> Map.of(
                "id", target.id(), "internalSequence", target.internalSequence() == null ? "" : target.internalSequence()
        )).toList());
        audit.record("COMMISSION_BATCH_INTERNAL_SEQUENCE_UPDATED", "COMMISSION", null, null,
                result, null, nullable(command.comment()));
        return new BatchResult(command.targets().size(), completed.size(), List.copyOf(completed));
    }

    @Transactional
    public BatchResult batchCopy(BatchCopy command) {
        if (command == null || command.targets() == null || command.targets().isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_EMPTY", "请至少选择一条委托");
        }
        if (command.targets().size() > 50 || command.targets().stream().map(BatchTarget::id).distinct().count()
                != command.targets().size()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_BATCH_COPY_INVALID", "单次批量复制不得超过50条且不能包含重复委托");
        }
        List<UUID> created = new ArrayList<>();
        for (BatchTarget target : command.targets()) created.add(copy(target.id(), target.expectedVersion()).id());
        audit.record("COMMISSION_BATCH_COPIED", "COMMISSION", null, null,
                Map.of("count", created.size(), "createdIds", List.copyOf(created)), null,
                nullable(command.comment()));
        return new BatchResult(command.targets().size(), created.size(), List.copyOf(created));
    }

    @Transactional
    public View bindExternalNumber(UUID id, ExternalNumberWrite command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        if (List.of(CommissionState.ISSUED, CommissionState.ARCHIVED, CommissionState.REJECTED,
                CommissionState.CANCELLED).contains(before.state())) {
            throw ApiException.blocked("COMMISSION_EXTERNAL_NUMBER_FROZEN", "当前委托阶段不能绑定外部编号",
                    before.version(), List.of("当前状态：" + before.state().name()));
        }
        String externalNumber = requiredText(command.externalNumber(), "外部编号不能为空");
        try {
            int changed = jdbc.sql("""
                    UPDATE commission SET external_number=:externalNumber,external_number_bound_at=now(),
                      external_number_bound_by=:actor,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("externalNumber", externalNumber).param("actor", actor.require().id())
                    .param("id", id).param("organization", actor.require().organizationId())
                    .param("version", command.expectedVersion()).update();
            if (changed == 0) throw ApiException.conflict(
                    "VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "COMMISSION_EXTERNAL_NUMBER_DUPLICATE", "该外部编号已绑定到其他委托");
        }
        View after = require(id);
        audit.record("COMMISSION_EXTERNAL_NUMBER_BOUND", "COMMISSION", id, before,
                Map.of("externalNumber", externalNumber, "version", after.version()), null,
                nullable(command.comment()));
        return after;
    }

    public PrintRestrictionView printRestriction(UUID id) {
        View commission = require(id);
        return jdbc.sql("""
                SELECT id,report_print_restricted,report_print_limit,report_print_restriction_reason,version
                FROM commission WHERE id=:id AND organization_id=:organization
                """).param("id", commission.id()).param("organization", actor.require().organizationId())
                .query((rs, row) -> new PrintRestrictionView(rs.getObject("id", UUID.class),
                        rs.getBoolean("report_print_restricted"), rs.getObject("report_print_limit", Integer.class),
                        rs.getString("report_print_restriction_reason"), rs.getLong("version"))).single();
    }

    @Transactional
    public PrintRestrictionView updatePrintRestriction(UUID id, PrintRestrictionWrite command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        if (before.state() == CommissionState.ARCHIVED) throw ApiException.blocked(
                "COMMISSION_PRINT_RESTRICTION_ARCHIVED", "归档委托不能修改报告打印限制", before.version(),
                List.of("归档后只能通过报告修订链处理"));
        if (command.printLimit() != null && command.printLimit() < 0) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_PRINT_LIMIT_INVALID", "打印上限不能为负数");
        String reason = requiredText(command.reason(), "修改报告打印限制必须填写原因");
        Integer limit = command.restricted() ? command.printLimit() : null;
        int changed = jdbc.sql("""
                UPDATE commission SET report_print_restricted=:restricted,report_print_limit=:printLimit,
                  report_print_restriction_reason=:reason,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("restricted", command.restricted()).param("printLimit", limit).param("reason", reason)
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", command.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        PrintRestrictionView after = printRestriction(id);
        audit.record("COMMISSION_REPORT_PRINT_RESTRICTION_UPDATED", "COMMISSION", id, before, after,
                null, reason);
        return after;
    }

    public List<SampleGroupPartView> sampleGroupParts(UUID id) {
        require(id);
        return jdbc.sql("""
                SELECT binding.sample_group_id,binding.project_structure_part_id,binding.part_name_snapshot
                FROM commission_sample_group_part binding
                JOIN commission_sample_group sample_group ON sample_group.id=binding.sample_group_id
                WHERE sample_group.commission_id=:commission
                ORDER BY sample_group.sort_order,binding.part_name_snapshot,binding.project_structure_part_id
                """).param("commission", id).query((rs, row) -> new SampleGroupPartView(
                        rs.getObject("sample_group_id", UUID.class),
                        rs.getObject("project_structure_part_id", UUID.class),
                        rs.getString("part_name_snapshot"))).list();
    }

    @Transactional
    public List<SampleGroupPartView> bindSampleGroupParts(UUID id, SampleGroupPartCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        if (List.of(CommissionState.REPORTING, CommissionState.ISSUED, CommissionState.ARCHIVED,
                CommissionState.REJECTED, CommissionState.CANCELLED).contains(before.state())) {
            throw ApiException.blocked("COMMISSION_SAMPLE_PARTS_FROZEN", "当前委托阶段不能修改分部/分项绑定",
                    before.version(), List.of("当前状态：" + before.state().name()));
        }
        if (command.bindings() == null || command.bindings().isEmpty() || command.bindings().size() > 200
                || command.bindings().stream().map(SampleGroupPartWrite::sampleGroupId).distinct().count()
                != command.bindings().size()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_SAMPLE_PART_BINDINGS_INVALID", "绑定必须包含1至200个不重复样品组");
        }
        List<UUID> groupIds = command.bindings().stream().map(SampleGroupPartWrite::sampleGroupId).toList();
        long validGroups = jdbc.sql("""
                SELECT count(*) FROM commission_sample_group WHERE commission_id=:commission AND id IN (:ids)
                """).param("commission", id).param("ids", groupIds).query(Long.class).single();
        if (validGroups != groupIds.size()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "COMMISSION_SAMPLE_GROUP_INVALID", "样品组不属于当前委托");
        List<UUID> partIds = command.bindings().stream().flatMap(binding -> {
            List<UUID> values = binding.projectPartIds() == null ? List.of() : binding.projectPartIds();
            if (values.size() > 50 || values.stream().distinct().count() != values.size()) throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_PROJECT_PARTS_INVALID",
                    "单个样品组最多绑定50个且不能重复的分部/分项");
            return values.stream();
        }).distinct().toList();
        Map<UUID, String> parts = new HashMap<>();
        if (!partIds.isEmpty()) jdbc.sql("""
                SELECT id,name FROM project_structure_part
                WHERE project_id=:project AND active AND id IN (:ids)
                """).param("project", before.projectId()).param("ids", partIds).query((rs, row) -> {
                    parts.put(rs.getObject("id", UUID.class), rs.getString("name"));
                    return rs.getObject("id", UUID.class);
                }).list();
        if (parts.size() != partIds.size()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "COMMISSION_PROJECT_PART_INVALID", "分部/分项不存在、已停用或不属于当前工程");
        jdbc.sql("DELETE FROM commission_sample_group_part WHERE sample_group_id IN (:ids)")
                .param("ids", groupIds).update();
        for (SampleGroupPartWrite binding : command.bindings()) {
            for (UUID partId : binding.projectPartIds() == null ? List.<UUID>of() : binding.projectPartIds()) {
                jdbc.sql("""
                        INSERT INTO commission_sample_group_part(sample_group_id,project_structure_part_id,
                          part_name_snapshot,created_by) VALUES (:groupId,:partId,:name,:actor)
                        """).param("groupId", binding.sampleGroupId()).param("partId", partId)
                        .param("name", parts.get(partId)).param("actor", actor.require().id()).update();
            }
        }
        touch(id, command.expectedVersion());
        List<SampleGroupPartView> after = sampleGroupParts(id);
        audit.record("COMMISSION_SAMPLE_GROUP_PARTS_BOUND", "COMMISSION", id, before, after,
                null, nullable(command.comment()));
        return after;
    }

    @Transactional
    public TerminationResult terminateTests(UUID id, TerminationWrite command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        if (!List.of(CommissionState.ASSIGNED, CommissionState.TESTING).contains(before.state())) {
            throw ApiException.blocked("COMMISSION_TEST_TERMINATION_STATE_BLOCKED", "当前委托阶段不能终止试验",
                    before.version(), List.of("当前状态：" + before.state().name(), "仅待检测或检测中委托可终止试验"));
        }
        String reasonCode = requiredText(command.reasonCode(), "终止试验必须选择原因代码");
        String comment = requiredText(command.comment(), "终止试验必须填写说明");
        long reports = jdbc.sql("SELECT count(*) FROM report WHERE commission_id=:id AND state<>'VOID'")
                .param("id", id).query(Long.class).single();
        if (reports > 0) throw ApiException.blocked("COMMISSION_TEST_TERMINATION_REPORT_EXISTS",
                "委托已生成报告，不能直接终止试验", before.version(),
                List.of("请先通过报告作废/修订和质量审批处理"));
        List<UUID> taskIds = jdbc.sql("""
                SELECT id FROM test_task WHERE commission_id=:id AND state NOT IN ('COMPLETED','CANCELLED')
                ORDER BY created_at,id FOR UPDATE
                """).param("id", id).query(UUID.class).list();
        if (taskIds.isEmpty()) throw ApiException.blocked("COMMISSION_TEST_TERMINATION_NO_TASKS",
                "没有可终止的检测任务", before.version(), List.of("任务尚未创建或均已结束"));
        int cancelled = jdbc.sql("""
                UPDATE test_task SET state='CANCELLED',rejection_reason=:comment,version=version+1,updated_at=now()
                WHERE id IN (:ids) AND state NOT IN ('COMPLETED','CANCELLED')
                """).param("comment", comment).param("ids", taskIds).update();
        jdbc.sql("""
                INSERT INTO commission_test_termination(id,commission_id,reason_code,comment,cancelled_task_count,initiated_by)
                VALUES (:id,:commission,:reasonCode,:comment,:count,:actor)
                """).param("id", UUID.randomUUID()).param("commission", id).param("reasonCode", reasonCode)
                .param("comment", comment).param("count", cancelled).param("actor", actor.require().id()).update();
        int changed = jdbc.sql("""
                UPDATE commission SET state='CANCELLED',version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("version", command.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        View after = require(id);
        TerminationResult result = new TerminationResult(id, cancelled, after.state().name(), after.version());
        audit.record("COMMISSION_TESTS_TERMINATED", "COMMISSION", id, before,
                Map.of("result", result, "taskIds", taskIds), reasonCode, comment);
        return result;
    }

    @Transactional
    public JobQueued enqueueExport(ExportWrite command, String idempotencyKey) {
        if (command == null || command.targets() == null || command.targets().isEmpty()
                || command.targets().size() > 200 || command.targets().stream().map(BatchTarget::id).distinct().count()
                != command.targets().size()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_EXPORT_TARGETS_INVALID", "导出必须包含1至200条不重复委托");
        }
        List<UUID> ids = new ArrayList<>();
        for (BatchTarget target : command.targets()) {
            View current = require(target.id());
            if (current.version() != target.expectedVersion()) throw ApiException.conflict(
                    "VERSION_CONFLICT", "委托已被其他用户更新", current.version());
            ids.add(current.id());
        }
        AuthenticatedUser user = actor.require();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"actorId\":\"" + user.id() + "\",\"organizationId\":\""
                + user.organizationId() + "\",\"commissionIds\":" + jsonIds(ids) + "}";
        jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type)
                VALUES (:id,'EXPORT_COMMISSION_XLSX',CAST(:payload AS jsonb),:key,'READY',:actor,'COMMISSION')
                """).param("id", jobId).param("payload", payload).param("key", idempotencyKey)
                .param("actor", user.id()).update();
        JobQueued result = new JobQueued(jobId, "EXPORT_COMMISSION_XLSX", "READY");
        audit.record("COMMISSION_EXPORT_QUEUED", "BACKGROUND_JOB", jobId, null,
                Map.of("job", result, "commissionIds", ids), null, nullable(command.comment()));
        return result;
    }

    public ElectronicDocumentReadiness electronicDocumentReadiness(UUID id) {
        require(id);
        return latestCommissionTemplate().isPresent()
                ? new ElectronicDocumentReadiness(true, List.of())
                : new ElectronicDocumentReadiness(false, List.of(
                        "尚无 rendererVersion=1 且仅含受控 HEADING/FIELD 区块的已发布委托模板",
                        "正式委托模板资料尚未提供，禁止生成占位电子委托单"));
    }

    public List<ElectronicDocumentView> electronicDocuments(UUID id) {
        require(id);
        return jdbc.sql("""
                SELECT document.id,document.revision,document.template_version_id,document.file_id,document.job_id,
                  creator.display_name created_by,document.created_at
                FROM commission_electronic_document document
                JOIN app_user creator ON creator.id=document.created_by
                WHERE document.commission_id=:commission ORDER BY document.revision DESC
                """).param("commission", id).query((rs, row) -> new ElectronicDocumentView(
                        rs.getObject("id", UUID.class), rs.getInt("revision"),
                        rs.getObject("template_version_id", UUID.class), rs.getObject("file_id", UUID.class),
                        rs.getObject("job_id", UUID.class), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public CursorPage<FlowEvent> flowEvents(UUID id, String cursorValue, int requestedLimit) {
        require(id);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        String resourceFilter = """
                event.organization_id=:organization AND (
                  (event.resource_type='COMMISSION' AND event.resource_id=:commission)
                  OR (event.resource_type='COMMISSION_TRANSFER' AND event.resource_id IN
                    (SELECT transfer.id::text FROM commission_transfer transfer WHERE transfer.commission_id=:id))
                  OR (event.resource_type='COMMISSION_CHANGE_REQUEST' AND event.resource_id IN
                    (SELECT request.id::text FROM commission_change_request request WHERE request.commission_id=:id))
                )
                """;
        List<FlowEvent> fetched = jdbc.sql("""
                SELECT event.id,event.occurred_at,event.actor_name,event.action,event.resource_type,
                  event.reason_code,event.comment
                FROM audit_event event
                WHERE """ + resourceFilter + """
                  AND (event.occurred_at,event.id)<(:cursorAt,:cursorId)
                ORDER BY event.occurred_at DESC,event.id DESC LIMIT :limit
                """).param("organization", actor.require().organizationId()).param("commission", id.toString())
                .param("id", id).param("cursorAt", cursorAt).param("cursorId", cursorId)
                .param("limit", limit + 1).query((rs, row) -> new FlowEvent(rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor_name"),
                        rs.getString("action"), rs.getString("resource_type"), rs.getString("reason_code"),
                        rs.getString("comment"))).list();
        boolean hasMore = fetched.size() > limit;
        List<FlowEvent> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore
                ? new PageCursor(items.getLast().occurredAt(), items.getLast().id()).encode()
                : null;
        long total = jdbc.sql("SELECT count(*) FROM audit_event event WHERE " + resourceFilter)
                .param("organization", actor.require().organizationId()).param("commission", id.toString())
                .param("id", id).query(Long.class).single();
        return new CursorPage<>(items, next, hasMore, total);
    }

    @Transactional
    public JobQueued enqueueElectronicDocument(UUID id, long expectedVersion, String idempotencyKey) {
        View commission = require(id);
        if (commission.version() != expectedVersion) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", commission.version());
        if (List.of(CommissionState.REJECTED, CommissionState.CANCELLED).contains(commission.state())) {
            throw ApiException.blocked("COMMISSION_DOCUMENT_TERMINAL", "终态委托不能刷新电子委托单",
                    commission.version(), List.of("当前状态：" + commission.state().name()));
        }
        UUID templateId = latestCommissionTemplate().orElseThrow(() -> ApiException.blocked(
                "COMMISSION_TEMPLATE_NOT_READY", "正式电子委托单模板尚未就绪", commission.version(),
                electronicDocumentReadiness(id).blockers()));
        AuthenticatedUser user = actor.require();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"actorId\":\"" + user.id() + "\",\"organizationId\":\""
                + user.organizationId() + "\",\"commissionId\":\"" + id
                + "\",\"templateVersionId\":\"" + templateId + "\"}";
        jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type,resource_id)
                VALUES (:id,'RENDER_COMMISSION_DOCUMENT',CAST(:payload AS jsonb),:key,'READY',:actor,'COMMISSION',:commission)
                """).param("id", jobId).param("payload", payload).param("key", idempotencyKey)
                .param("actor", user.id()).param("commission", id).update();
        JobQueued result = new JobQueued(jobId, "RENDER_COMMISSION_DOCUMENT", "READY");
        audit.record("COMMISSION_DOCUMENT_QUEUED", "BACKGROUND_JOB", jobId, null, result, null, null);
        return result;
    }

    @Transactional
    public View copy(UUID id, long expectedVersion) {
        View source = require(id);
        if (source.version() != expectedVersion) throw ApiException.conflict(
                "VERSION_CONFLICT", "源委托已被其他用户更新", source.version());
        IntakeDetailsView detail = source.details();
        IntakeDetails copiedDetails = new IntakeDetails(detail.projectProgress(), detail.testingType(),
                detail.testingStandard(), detail.testingPlace(), detail.sampleHandling(), detail.reportDelivery(),
                detail.reportDeliveryAddress(), detail.reportCopies(), detail.projectPart(), detail.monomer(),
                detail.settlementContact(), detail.witnessUnit(), detail.witnessName(), detail.witnessPhone(),
                detail.witnessCertificate(), detail.samplingUnit(), detail.samplingPerson(), detail.samplingPhone(),
                detail.samplingCertificate(), detail.constructionUnit(), detail.needSampling(),
                detail.electronicSignature(), detail.cmaSeal(), detail.qualificationSeal(), detail.externalCommission(),
                detail.externalOrganization(), null, detail.sampleReceivingAddress(), detail.expectedReportOn(),
                detail.remark(), detail.sourceRemark(), detail.ageReportValues(),
                detail.attachments().stream().map(FileView::id).toList());
        List<DetectionSelection> copiedSelections = selections(id).stream().map(selection ->
                new DetectionSelection(selection.projectRevisionId(), selection.parameters().stream()
                        .map(ParameterSelectionView::parameterId).distinct().toList(),
                        selection.sampleGroups().stream().map(group -> new SampleGroup(group.clientKey(),
                                group.name(), group.description(), group.quantity(), group.unit(),
                                group.parameterIds(), group.fieldValues())).toList())).toList();
        LocalDate copiedDueOn = source.dueOn() == null || source.dueOn().isBefore(LocalDate.now())
                ? LocalDate.now().plusDays(15) : source.dueOn();
        View created = create(new Create(source.projectId(), source.customerId(), source.contactName(),
                source.contactPhone(), LocalDate.now(), copiedDueOn, copiedDetails, copiedSelections));
        jdbc.sql("""
                INSERT INTO commission_relation(id,source_commission_id,target_commission_id,relation_type,
                  comment,created_by) VALUES (:id,:source,:target,'COPIED_FROM',:comment,:actor)
                """).param("id", UUID.randomUUID()).param("source", id).param("target", created.id())
                .param("comment", "复制委托 " + source.number()).param("actor", actor.require().id()).update();
        audit.record("COMMISSION_COPIED", "COMMISSION", created.id(), source, created, null,
                "从 " + source.number() + " 复制为新草稿");
        return created;
    }

    @Transactional
    public RelationView relate(UUID sourceId, RelationWrite body) {
        View source = require(sourceId);
        View target = require(body.targetCommissionId());
        if (source.id().equals(target.id())) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "COMMISSION_RELATION_SELF", "委托不能关联自身");
        if (source.version() != body.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", source.version());
        String type = upper(body.relationType());
        if (!List.of("RELATED", "SPLIT_FROM").contains(type)) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_RELATION_TYPE_INVALID", "委托关联类型无效");
        UUID relationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO commission_relation(id,source_commission_id,target_commission_id,relation_type,
                  comment,created_by) VALUES (:id,:source,:target,:type,:comment,:actor)
                """).param("id", relationId).param("source", sourceId).param("target", target.id())
                .param("type", type).param("comment", nullable(body.comment()))
                .param("actor", actor.require().id()).update();
        touch(sourceId, body.expectedVersion());
        RelationView relation = relations(sourceId).stream().filter(item -> item.id().equals(relationId))
                .findFirst().orElseThrow();
        audit.record("COMMISSION_RELATED", "COMMISSION_RELATION", relationId, null, relation, null, body.comment());
        return relation;
    }

    public List<RelationView> relations(UUID commissionId) {
        require(commissionId);
        return jdbc.sql("""
                SELECT relation.id,relation.source_commission_id,source.number source_number,
                  relation.target_commission_id,target.number target_number,relation.relation_type,
                  relation.comment,creator.display_name created_by,relation.created_at
                FROM commission_relation relation
                JOIN commission source ON source.id=relation.source_commission_id
                JOIN commission target ON target.id=relation.target_commission_id
                JOIN app_user creator ON creator.id=relation.created_by
                WHERE relation.source_commission_id=:commission OR relation.target_commission_id=:commission
                ORDER BY relation.created_at DESC,relation.id DESC
                """).param("commission", commissionId).query((rs, row) -> new RelationView(
                        rs.getObject("id", UUID.class), rs.getObject("source_commission_id", UUID.class),
                        rs.getString("source_number"), rs.getObject("target_commission_id", UUID.class),
                        rs.getString("target_number"), rs.getString("relation_type"), rs.getString("comment"),
                        rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public TransferView transfer(UUID commissionId, TransferWrite body) {
        View commission = require(commissionId);
        if (commission.version() != body.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", commission.version());
        if (List.of(CommissionState.ARCHIVED, CommissionState.REJECTED, CommissionState.CANCELLED)
                .contains(commission.state())) throw ApiException.blocked("COMMISSION_TRANSFER_TERMINAL",
                "终态委托不能转交", commission.version(), List.of("当前状态：" + commission.state().name()));
        String toDepartment = jdbc.sql("""
                SELECT name FROM department WHERE id=:id AND organization_id=:organization AND active
                """).param("id", body.toDepartmentId()).param("organization", actor.require().organizationId())
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound(
                        "DEPARTMENT_NOT_FOUND", "目标部门不存在"));
        UUID currentDepartment = jdbc.sql("SELECT received_department_id FROM commission WHERE id=:id")
                .param("id", commissionId).query(UUID.class).optional().orElse(null);
        if (body.toDepartmentId().equals(currentDepartment)) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_TRANSFER_SAME_DEPARTMENT",
                "目标部门不能与当前受理部门相同");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO commission_transfer(id,commission_id,from_department_id,to_department_id,
                  comment,created_by) SELECT :id,c.id,c.received_department_id,:target,:comment,:actor
                FROM commission c WHERE c.id=:commission
                """).param("id", id).param("target", body.toDepartmentId()).param("comment", nullable(body.comment()))
                .param("actor", actor.require().id()).param("commission", commissionId).update();
        touch(commissionId, body.expectedVersion());
        TransferView transfer = transfers(commissionId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        audit.record("COMMISSION_TRANSFER_REQUESTED", "COMMISSION_TRANSFER", id, null, transfer, null,
                "转交至" + toDepartment + (body.comment() == null ? "" : "：" + body.comment()));
        return transfer;
    }

    public List<TransferView> transfers(UUID commissionId) {
        require(commissionId);
        return jdbc.sql(transferSelect() + " WHERE transfer.commission_id=:commission ORDER BY transfer.created_at DESC,transfer.id DESC")
                .param("commission", commissionId).query(this::mapTransfer).list();
    }

    @Transactional
    public TransferView transitionTransfer(UUID transferId, TransitionCommand command) {
        TransferView before = requireTransfer(transferId);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "转交申请已被更新", before.version());
        String action = upper(command.action());
        if (!before.allowedActions().contains(action)) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                "COMMISSION_TRANSFER_ACTION_INVALID", "当前转交状态不允许该操作");
        String next = switch (action) { case "ACCEPT" -> "ACCEPTED"; case "REJECT" -> "REJECTED"; case "CANCEL" -> "CANCELLED"; default -> throw new IllegalStateException(); };
        int changed = jdbc.sql("""
                UPDATE commission_transfer SET state=:state,reviewed_by=CASE WHEN :reviewed THEN :actor ELSE reviewed_by END,
                  reviewed_at=CASE WHEN :reviewed THEN now() ELSE reviewed_at END,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version AND state='PENDING'
                """).param("state", next).param("reviewed", !action.equals("CANCEL"))
                .param("actor", actor.require().id()).param("id", transferId).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "转交申请已被更新", requireTransfer(transferId).version());
        if (action.equals("ACCEPT")) jdbc.sql("""
                UPDATE commission SET received_department_id=:department,version=version+1,updated_at=now()
                WHERE id=:commission
                """).param("department", before.toDepartmentId()).param("commission", before.commissionId()).update();
        TransferView after = requireTransfer(transferId);
        audit.record("COMMISSION_TRANSFER_" + action, "COMMISSION_TRANSFER", transferId, before, after,
                command.reasonCode(), command.comment());
        return after;
    }

    @Transactional
    public PrintResult markPrinted(UUID id, String documentType, long expectedVersion) {
        View before = require(id);
        if (before.version() != expectedVersion) throw ApiException.conflict(
                "VERSION_CONFLICT", "委托已被其他用户更新", before.version());
        String type = upper(documentType);
        if (!List.of("COMMISSION", "SAMPLE_LABEL").contains(type)) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_PRINT_TYPE_INVALID", "打印文档类型无效");
        if (type.equals("SAMPLE_LABEL")) {
            long groups = jdbc.sql("SELECT count(*) FROM commission_sample_group WHERE commission_id=:id")
                    .param("id", id).query(Long.class).single();
            if (groups == 0) throw ApiException.blocked("COMMISSION_SAMPLE_LABEL_BLOCKED",
                    "没有可打印的样品组", before.version(), List.of("请先配置样品组"));
        }
        int changed = jdbc.sql("""
                UPDATE commission SET print_status='PRINTED',version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("version", expectedVersion).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
        View after = require(id);
        PrintResult result = new PrintResult(id, type, "PRINTED", after.version());
        audit.record("COMMISSION_" + type + "_PRINTED", "COMMISSION", id, before, result, null, null);
        return result;
    }

    public List<DetectionSelectionView> selections(UUID commissionId) {
        require(commissionId);
        List<DetectionSelectionView> selections = new ArrayList<>();
        List<SampleGroupView> allGroups = jdbc.sql("""
                SELECT g.id, g.client_key, g.name, g.description, g.quantity, g.unit, g.sort_order
                FROM commission_sample_group g WHERE g.commission_id=:commission ORDER BY g.sort_order, g.id
                """).param("commission", commissionId).query((rs, row) -> new SampleGroupView(
                        rs.getObject("id", UUID.class), rs.getString("client_key"), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),
                        rs.getString("unit"), rs.getInt("sort_order"), List.of(), Map.of())).list();
        Map<UUID, List<UUID>> groupParameters = new HashMap<>();
        jdbc.sql("""
                SELECT p.sample_group_id, p.parameter_id FROM commission_parameter_selection p
                JOIN commission_detection_selection d ON d.id=p.detection_selection_id
                WHERE d.commission_id=:commission AND p.sample_group_id IS NOT NULL
                ORDER BY p.sort_order
                """).param("commission", commissionId).query((rs, row) -> {
                    UUID groupId = rs.getObject("sample_group_id", UUID.class);
                    groupParameters.computeIfAbsent(groupId, ignored -> new ArrayList<>())
                            .add(rs.getObject("parameter_id", UUID.class));
                    return groupId;
                }).list();
        Map<UUID, Map<String, List<String>>> groupFields = sampleFieldValues(commissionId);
        List<SampleGroupView> groups = allGroups.stream().map(group -> new SampleGroupView(group.id(), group.clientKey(),
                group.name(), group.description(), group.quantity(), group.unit(), group.sortOrder(),
                groupParameters.getOrDefault(group.id(), List.of()),
                groupFields.getOrDefault(group.id(), Map.of()))).toList();
        List<UUID> detectionIds = jdbc.sql("""
                SELECT id FROM commission_detection_selection WHERE commission_id=:commission ORDER BY sort_order, id
                """).param("commission", commissionId).query(UUID.class).list();
        for (UUID detectionId : detectionIds) {
            List<ParameterSelectionView> parameters = jdbc.sql("""
                    SELECT id, parameter_id, parameter_code_snapshot, parameter_name_snapshot, unit_snapshot,
                      required_snapshot, default_snapshot, sort_order, sample_group_id
                    FROM commission_parameter_selection WHERE detection_selection_id=:id
                    ORDER BY sort_order, parameter_name_snapshot, id
                    """).param("id", detectionId).query((rs, row) -> new ParameterSelectionView(
                            rs.getObject("id", UUID.class), rs.getObject("parameter_id", UUID.class),
                            rs.getString("parameter_code_snapshot"), rs.getString("parameter_name_snapshot"),
                            rs.getString("unit_snapshot"), rs.getBoolean("required_snapshot"),
                            rs.getBoolean("default_snapshot"), rs.getInt("sort_order"),
                            rs.getObject("sample_group_id", UUID.class))).list();
            DetectionSelectionView view = jdbc.sql("""
                    SELECT id, project_revision_id, project_name_snapshot, project_symbol_snapshot,
                      revision_number_snapshot, sort_order FROM commission_detection_selection WHERE id=:id
                    """).param("id", detectionId).query((rs, row) -> new DetectionSelectionView(
                            rs.getObject("id", UUID.class), rs.getObject("project_revision_id", UUID.class),
                            rs.getString("project_name_snapshot"), rs.getString("project_symbol_snapshot"),
                            rs.getInt("revision_number_snapshot"), rs.getInt("sort_order"), parameters,
                            groups.stream().filter(group -> parameters.stream().anyMatch(
                                    parameter -> group.id().equals(parameter.sampleGroupId()))).toList())).single();
            selections.add(view);
        }
        return selections;
    }

    private record ParameterSnapshot(UUID id, String code, String name, String unit, boolean required,
                                     boolean defaultCommissioned, int sortOrder) {}
    private record RevisionSnapshot(UUID id, String projectName, String symbol, int revisionNumber) {}
    private record SampleFieldDefinition(UUID id, String code, String label, String fieldType, boolean required,
                                         String unit, List<String> options, String coreBinding,
                                         Set<UUID> standardIds) {}

    private void saveSelections(UUID commissionId, List<DetectionSelection> requestedSelections) {
        if (requestedSelections == null || requestedSelections.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "DETECTION_SELECTION_REQUIRED", "委托至少需要选择一个检测项目");
        }
        Set<UUID> seenRevisions = new LinkedHashSet<>();
        Set<String> seenGroupKeys = new LinkedHashSet<>();
        int detectionOrder = 0;
        int groupOrder = 0;
        for (DetectionSelection request : requestedSelections) {
            if (request == null || request.projectRevisionId() == null || !seenRevisions.add(request.projectRevisionId())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "DETECTION_SELECTION_DUPLICATE", "检测项目版本不能为空或重复");
            }
            RevisionSnapshot revision = jdbc.sql("""
                    SELECT r.id, n.name, n.symbol, r.revision_number
                    FROM detection_project_revision r JOIN detection_catalog_node n ON n.id=r.project_node_id
                    WHERE r.id=:id AND r.status='PUBLISHED' AND n.status='ACTIVE'
                    """).param("id", request.projectRevisionId()).query((rs, row) -> new RevisionSnapshot(
                            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4))).optional()
                    .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "DETECTION_PROJECT_NOT_SELECTABLE", "检测项目未发布、已停用或属于历史目录"));
            List<ParameterSnapshot> available = jdbc.sql("""
                    SELECT id, code, name, unit, required, default_commissioned, sort_order
                    FROM test_parameter WHERE project_revision_id=:revision AND status='PUBLISHED' AND enabled
                    ORDER BY sort_order, name, id
                    """).param("revision", revision.id()).query((rs, row) -> new ParameterSnapshot(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("unit"), rs.getBoolean("required"), rs.getBoolean("default_commissioned"),
                            rs.getInt("sort_order"))).list();
            Map<UUID, ParameterSnapshot> byId = available.stream().collect(java.util.stream.Collectors.toMap(
                    ParameterSnapshot::id, value -> value, (left, right) -> left, LinkedHashMap::new));
            LinkedHashSet<UUID> selectedIds = new LinkedHashSet<>();
            if (request.parameterIds() != null) selectedIds.addAll(request.parameterIds());
            available.stream().filter(parameter -> parameter.required() || parameter.defaultCommissioned())
                    .map(ParameterSnapshot::id).forEach(selectedIds::add);
            if (selectedIds.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "DETECTION_PARAMETER_REQUIRED", "检测项目至少需要选择一项参数");
            if (!byId.keySet().containsAll(selectedIds)) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "DETECTION_PARAMETER_NOT_SELECTABLE", "委托包含未发布、已停用或不属于所选项目的参数");
            UUID detectionId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO commission_detection_selection(id, commission_id, project_revision_id,
                      project_name_snapshot, project_symbol_snapshot, revision_number_snapshot, sort_order)
                    VALUES (:id,:commission,:revision,:name,:symbol,:revisionNumber,:sortOrder)
                    """).param("id", detectionId).param("commission", commissionId).param("revision", revision.id())
                    .param("name", revision.projectName()).param("symbol", revision.symbol())
                    .param("revisionNumber", revision.revisionNumber()).param("sortOrder", ++detectionOrder).update();
            if (request.sampleGroups() == null || request.sampleGroups().isEmpty()) {
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "SAMPLE_GROUP_REQUIRED", "每个检测项目至少需要配置一个样品组");
            }
            Map<String, SampleFieldDefinition> sampleFields = sampleFieldDefinitions(revision.id());
            List<SampleGroup> groups = request.sampleGroups();
            Map<String, UUID> groupIds = new LinkedHashMap<>();
            Map<String, LinkedHashSet<UUID>> groupSelections = new LinkedHashMap<>();
            for (SampleGroup group : groups) {
                if (group == null || group.clientKey() == null || group.clientKey().isBlank()
                        || !seenGroupKeys.add(group.clientKey())) {
                    throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                            "SAMPLE_GROUP_KEY_INVALID", "样品组标识不能为空或重复");
                }
                if (group.name() == null || group.name().isBlank() || group.unit() == null || group.unit().isBlank()) {
                    throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                            "SAMPLE_GROUP_REQUIRED_FIELDS", "样品组名称和单位不能为空");
                }
                BigDecimal quantity = positive(group.quantity());
                LinkedHashSet<UUID> groupParameterIds = new LinkedHashSet<>(
                        group.parameterIds() == null ? List.of() : group.parameterIds());
                if (!selectedIds.containsAll(groupParameterIds)) throw new ApiException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_GROUP_PARAMETER_OUT_OF_SCOPE",
                        "样品组包含未选择的检测参数");
                UUID groupId = UUID.randomUUID();
                groupIds.put(group.clientKey(), groupId);
                groupSelections.put(group.clientKey(), groupParameterIds);
                jdbc.sql("""
                        INSERT INTO commission_sample_group(id, commission_id, client_key, name, description,
                          quantity, unit, sort_order, snapshot_json)
                        VALUES (:id,:commission,:clientKey,:name,:description,:quantity,:unit,:sortOrder,
                          jsonb_build_object('source','commission-create','projectRevisionId',CAST(:revision AS text)))
                        """).param("id", groupId).param("commission", commissionId).param("clientKey", group.clientKey())
                        .param("name", group.name().trim()).param("description", group.description())
                        .param("quantity", quantity).param("unit", group.unit().trim()).param("sortOrder", ++groupOrder)
                        .param("revision", revision.id().toString()).update();
                saveSampleFieldValues(groupId, group, quantity, sampleFields);
            }
            Set<UUID> assigned = groupSelections.values().stream().flatMap(Set::stream)
                    .collect(java.util.stream.Collectors.toSet());
            groupSelections.values().iterator().next().addAll(selectedIds.stream().filter(id -> !assigned.contains(id)).toList());
            int parameterOrder = 0;
            for (Map.Entry<String, LinkedHashSet<UUID>> groupEntry : groupSelections.entrySet()) {
                UUID sampleGroupId = groupIds.get(groupEntry.getKey());
                for (UUID parameterId : groupEntry.getValue()) {
                    ParameterSnapshot parameter = byId.get(parameterId);
                    jdbc.sql("""
                            INSERT INTO commission_parameter_selection(id, detection_selection_id, parameter_id,
                              sample_group_id, parameter_code_snapshot, parameter_name_snapshot, unit_snapshot,
                              required_snapshot, default_snapshot, sort_order, snapshot_json)
                            SELECT :id,:selection,p.id,:sampleGroup,p.code,p.name,p.unit,p.required,
                              p.default_commissioned,:sortOrder,
                              jsonb_build_object('parameterId',p.id,'code',p.code,'name',p.name,
                                'versionNumber',p.version_number,'valueType',p.value_type,'definition',p.definition_json,
                                'formula',p.formula,'unit',p.unit,'conclusion',p.conclusion,'defaultNote',p.default_note,
                                'pricingMethod',p.pricing_method,'price',p.price,'sampleName',p.sample_name,
                                'promisedDays',p.promised_days,'sampleDescription',p.sample_description,
                                'sampleQuantity',p.sample_quantity,'postTestTreatment',p.post_test_treatment,
                                'retentionDays',p.retention_days,'retentionQuantity',p.retention_quantity,
                                'retentionNature',p.retention_nature,'required',p.required,
                                'defaultCommissioned',p.default_commissioned,
                                'standards',COALESCE((SELECT jsonb_agg(s.code ORDER BY ps.sort_order)
                                  FROM test_parameter_standard ps JOIN detection_standard s ON s.id=ps.standard_id
                                  WHERE ps.parameter_id=p.id),'[]'::jsonb))
                            FROM test_parameter p WHERE p.id=:parameter
                            """).param("id", UUID.randomUUID()).param("selection", detectionId)
                            .param("sampleGroup", sampleGroupId).param("sortOrder", ++parameterOrder)
                            .param("parameter", parameter.id()).update();
                }
            }
        }
    }

    private void generateExpectedSamples(UUID commissionId) {
        List<SampleGroupView> groups = jdbc.sql("""
                SELECT id, client_key, name, description, quantity, unit, sort_order
                FROM commission_sample_group g WHERE commission_id=:commission
                  AND NOT EXISTS(SELECT 1 FROM sample s WHERE s.sample_group_id=g.id)
                ORDER BY sort_order
                """).param("commission", commissionId).query((rs, row) -> new SampleGroupView(
                        rs.getObject("id", UUID.class), rs.getString("client_key"), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),
                        rs.getString("unit"), rs.getInt("sort_order"), List.of(), Map.of())).list();
        for (SampleGroupView group : groups) {
            jdbc.sql("""
                    INSERT INTO sample(id, commission_id, sample_group_id, number, name, specification,
                      received_quantity, available_quantity, unit, state)
                    VALUES (:id,:commission,:group,:number,:name,:description,:quantity,0,:unit,'EXPECTED')
                    """).param("id", UUID.randomUUID()).param("commission", commissionId).param("group", group.id())
                    .param("number", numbering.next("SAMPLE", "YP")).param("name", group.name())
                    .param("description", group.description()).param("quantity", new BigDecimal(group.quantity()))
                    .param("unit", group.unit()).update();
        }
    }

    private BigDecimal positive(String value) {
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.signum() <= 0 || number.scale() > 6) throw new NumberFormatException();
            return number;
        } catch (RuntimeException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_DECIMAL",
                    "样品数量必须是最多六位小数的正数");
        }
    }

    private void touch(UUID id, long expectedVersion) {
        int changed = jdbc.sql("""
                UPDATE commission SET version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("version", expectedVersion).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "委托已被其他用户更新", require(id).version());
    }

    private String transferSelect() {
        return """
                SELECT transfer.id,transfer.commission_id,commission.number commission_number,
                  transfer.from_department_id,from_department.name from_department,
                  transfer.to_department_id,to_department.name to_department,transfer.state,transfer.comment,
                  transfer.created_by created_by_id,creator.display_name created_by,
                  reviewer.display_name reviewed_by,transfer.version,
                  transfer.created_at,transfer.updated_at
                FROM commission_transfer transfer JOIN commission ON commission.id=transfer.commission_id
                LEFT JOIN department from_department ON from_department.id=transfer.from_department_id
                JOIN department to_department ON to_department.id=transfer.to_department_id
                JOIN app_user creator ON creator.id=transfer.created_by
                LEFT JOIN app_user reviewer ON reviewer.id=transfer.reviewed_by
                """;
    }

    private TransferView requireTransfer(UUID id) {
        TransferView value = jdbc.sql(transferSelect() + " WHERE transfer.id=:id AND commission.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapTransfer).optional().orElseThrow(() -> ApiException.notFound(
                        "COMMISSION_TRANSFER_NOT_FOUND", "委托转交申请不存在"));
        require(value.commissionId());
        return value;
    }

    private TransferView mapTransfer(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        AuthenticatedUser user = actor.require();
        List<String> actions = new ArrayList<>();
        if (state.equals("PENDING")) {
            if (user.id().equals(rs.getObject("created_by_id", UUID.class))) actions.add("CANCEL");
            if (user.departmentId() != null && user.departmentId().equals(rs.getObject("to_department_id", UUID.class))) {
                actions.add("ACCEPT");
                actions.add("REJECT");
            }
        }
        return new TransferView(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("commission_number"), rs.getObject("from_department_id", UUID.class),
                rs.getString("from_department"), rs.getObject("to_department_id", UUID.class),
                rs.getString("to_department"), state, rs.getString("comment"), rs.getString("created_by"),
                rs.getString("reviewed_by"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), actions);
    }

    public CommissionOptions options() {
        return new CommissionOptions(PROJECT_PROGRESS_OPTIONS, TESTING_TYPE_OPTIONS, TESTING_STANDARD_OPTIONS,
                TESTING_PLACE_OPTIONS, SAMPLE_HANDLING_OPTIONS, REPORT_DELIVERY_OPTIONS);
    }

    public ProjectDefaults projectDefaults(UUID projectId) {
        access.requireProject(projectId);
        record ProjectRow(UUID id, String number, String name, UUID customerId, String customerName,
                          String constructionUnit, String witnessUnit, String samplingUnit,
                          String entrusterName, String entrusterPhone, String witnessName, String witnessPhone,
                          String witnessCertificate, String samplerName, String samplerPhone,
                          String samplerCertificate, String settlementContact,
                          boolean electronicSignature, boolean cmaSeal, boolean qualificationSeal) {}
        ProjectRow project = jdbc.sql("""
                SELECT p.id,p.number,p.name,p.customer_id,c.name customer_name,
                  COALESCE(construction.name_snapshot,p.construction_name) construction_unit,
                  COALESCE(supervision.name_snapshot,p.supervision_name) witness_unit,
                  COALESCE(sampling.name_snapshot,p.sampling_unit_name) sampling_unit,
                  COALESCE(entrusting.contact_name,p.entruster_name,c.contact_name) entruster_name,
                  COALESCE(entrusting.contact_phone,p.entruster_phone,c.contact_phone) entruster_phone,
                  COALESCE(supervision.contact_name,p.witness_name) witness_name,
                  COALESCE(supervision.contact_phone,p.witness_phone) witness_phone,
                  supervision.certificate_number witness_certificate,
                  COALESCE(sampling.contact_name,p.sampler_name) sampler_name,
                  COALESCE(sampling.contact_phone,p.sampler_phone) sampler_phone,
                  COALESCE(sampling.certificate_number,p.sampler_certificate_number) sampler_certificate,
                  COALESCE(p.settlement_contact_name,settlement.name,c.contact_name) settlement_contact,
                  p.electronic_seal_policy='ALWAYS' electronic_signature,
                  p.cma_seal_policy='ALWAYS' cma_seal,p.qualification_seal_policy='ALWAYS' qualification_seal
                FROM project p JOIN customer c ON c.id=p.customer_id
                LEFT JOIN project_party entrusting ON entrusting.project_id=p.id AND entrusting.party_role='ENTRUSTING'
                LEFT JOIN project_party supervision ON supervision.project_id=p.id AND supervision.party_role='SUPERVISION'
                LEFT JOIN project_party sampling ON sampling.project_id=p.id AND sampling.party_role='SAMPLING'
                LEFT JOIN project_party construction ON construction.project_id=p.id AND construction.party_role='CONSTRUCTION'
                LEFT JOIN customer_settlement_contact settlement ON settlement.customer_id=c.id
                  AND settlement.active AND settlement.primary_contact
                WHERE p.id=:id AND p.organization_id=:organization
                """).param("id", projectId).param("organization", actor.require().organizationId())
                .query((rs, row) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getString("name"), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                        rs.getString("construction_unit"), rs.getString("witness_unit"), rs.getString("sampling_unit"),
                        rs.getString("entruster_name"), rs.getString("entruster_phone"), rs.getString("witness_name"),
                        rs.getString("witness_phone"), rs.getString("witness_certificate"), rs.getString("sampler_name"),
                        rs.getString("sampler_phone"), rs.getString("sampler_certificate"), rs.getString("settlement_contact"),
                        rs.getBoolean("electronic_signature"), rs.getBoolean("cma_seal"),
                        rs.getBoolean("qualification_seal"))).optional()
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "工程不存在"));
        List<Option> parts = jdbc.sql("""
                SELECT id::text code,name label FROM project_structure_part
                WHERE project_id=:project AND active ORDER BY sort_order,name,id
                """).param("project", projectId).query((rs, row) -> new Option(rs.getString("code"),
                        rs.getString("label"))).list();
        return new ProjectDefaults(project.id(), project.number(), project.name(), project.customerId(),
                project.customerName(), project.constructionUnit(), project.witnessUnit(), project.samplingUnit(),
                new PersonDefault(project.entrusterName(), project.entrusterPhone(), null),
                new PersonDefault(project.witnessName(), project.witnessPhone(), project.witnessCertificate()),
                new PersonDefault(project.samplerName(), project.samplerPhone(), project.samplerCertificate()),
                project.settlementContact(), project.electronicSignature(), project.cmaSeal(),
                project.qualificationSeal(), parts);
    }

    private IntakeDetails normalizedDetails(IntakeDetails value) {
        IntakeDetails details = value == null ? new IntakeDetails(
                "FOUNDATION_FACILITIES", "PARALLEL_SUPERVISION", "NATIONAL", "LAB",
                "CLIENT_DISPOSE", "PICKUP", null, 0, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, false, false, false, null, null, null,
                null, null, null, List.of(), List.of()) : value;
        String projectProgress = optionCode(details.projectProgress(), PROJECT_PROGRESS_OPTIONS,
                "COMMISSION_PROJECT_PROGRESS_INVALID", "工程进度选项无效");
        String testingType = optionCode(details.testingType(), TESTING_TYPE_OPTIONS,
                "COMMISSION_TESTING_TYPE_INVALID", "检测类型选项无效");
        String testingStandard = optionCode(details.testingStandard(), TESTING_STANDARD_OPTIONS,
                "COMMISSION_TESTING_STANDARD_INVALID", "检测标准类别无效");
        String testingPlace = optionCode(details.testingPlace(), TESTING_PLACE_OPTIONS,
                "COMMISSION_TESTING_PLACE_INVALID", "检测地点选项无效");
        String sampleHandling = optionCode(details.sampleHandling(), SAMPLE_HANDLING_OPTIONS,
                "COMMISSION_SAMPLE_HANDLING_INVALID", "样品处理方式无效");
        String reportDelivery = optionCode(details.reportDelivery(), REPORT_DELIVERY_OPTIONS,
                "COMMISSION_REPORT_DELIVERY_INVALID", "报告领取方式无效");
        if (details.reportCopies() < 0 || details.reportCopies() > 999) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_REPORT_COPIES_INVALID", "报告份数必须在0至999之间");
        if (List.of("MAIL", "DELIVERED", "EMAIL").contains(reportDelivery)
                && (details.reportDeliveryAddress() == null || details.reportDeliveryAddress().isBlank())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_REPORT_DELIVERY_ADDRESS_REQUIRED", "所选报告领取方式必须填写邮寄地址或邮箱");
        }
        if (details.externalCommission() && (details.externalOrganization() == null
                || details.externalOrganization().isBlank())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_EXTERNAL_ORGANIZATION_REQUIRED", "外委检测必须填写外委机构");
        }
        return new IntakeDetails(projectProgress, testingType, testingStandard, testingPlace, sampleHandling,
                reportDelivery, nullable(details.reportDeliveryAddress()), details.reportCopies(),
                nullable(details.projectPart()), nullable(details.monomer()), nullable(details.settlementContact()),
                nullable(details.witnessUnit()), nullable(details.witnessName()), nullable(details.witnessPhone()),
                nullable(details.witnessCertificate()), nullable(details.samplingUnit()), nullable(details.samplingPerson()),
                nullable(details.samplingPhone()), nullable(details.samplingCertificate()), nullable(details.constructionUnit()),
                details.needSampling(), details.electronicSignature(), details.cmaSeal(), details.qualificationSeal(),
                details.externalCommission(), nullable(details.externalOrganization()), nullable(details.externalNumber()),
                nullable(details.sampleReceivingAddress()), details.expectedReportOn(), nullable(details.remark()),
                nullable(details.sourceRemark()), cleanStrings(details.ageReportValues()),
                details.attachmentFileIds() == null ? List.of() : details.attachmentFileIds().stream()
                        .filter(java.util.Objects::nonNull).distinct().toList());
    }

    private void saveDetails(UUID commissionId, IntakeDetails details, boolean columnsAlreadyWritten) {
        if (!columnsAlreadyWritten) {
            var statement = jdbc.sql("""
                    UPDATE commission SET project_progress=:projectProgress,testing_type=:testingType,
                      testing_standard=:testingStandard,testing_place=:testingPlace,sample_handling=:sampleHandling,
                      report_delivery=:reportDelivery,report_delivery_address=:deliveryAddress,
                      report_copies=:reportCopies,project_part=:projectPart,monomer=:monomer,
                      settlement_contact=:settlementContact,witness_unit=:witnessUnit,witness_name=:witnessName,
                      witness_phone=:witnessPhone,witness_certificate=:witnessCertificate,
                      sampling_unit=:samplingUnit,sampling_person=:samplingPerson,sampling_phone=:samplingPhone,
                      sampling_certificate=:samplingCertificate,construction_unit=:constructionUnit,
                      need_sampling=:needSampling,electronic_signature=:electronicSignature,cma_seal=:cmaSeal,
                      qualification_seal=:qualificationSeal,external_commission=:externalCommission,
                      external_organization=:externalOrganization,external_number=:externalNumber,
                      sample_receiving_address=:receivingAddress,expected_report_on=:expectedReportOn,
                      remark=:remark,source_remark=:sourceRemark
                    WHERE id=:id AND organization_id=:organization
                    """);
            bindDetails(statement, details).param("id", commissionId)
                    .param("organization", actor.require().organizationId()).update();
        }
        saveAgeReportSelections(commissionId, details.ageReportValues());
        saveAttachments(commissionId, details.attachmentFileIds());
    }

    private JdbcClient.StatementSpec bindDetails(JdbcClient.StatementSpec statement, IntakeDetails details) {
        return statement.param("projectProgress", details.projectProgress()).param("testingType", details.testingType())
                .param("testingStandard", details.testingStandard()).param("testingPlace", details.testingPlace())
                .param("sampleHandling", details.sampleHandling()).param("reportDelivery", details.reportDelivery())
                .param("deliveryAddress", details.reportDeliveryAddress()).param("reportCopies", details.reportCopies())
                .param("projectPart", details.projectPart()).param("monomer", details.monomer())
                .param("settlementContact", details.settlementContact()).param("witnessUnit", details.witnessUnit())
                .param("witnessName", details.witnessName()).param("witnessPhone", details.witnessPhone())
                .param("witnessCertificate", details.witnessCertificate()).param("samplingUnit", details.samplingUnit())
                .param("samplingPerson", details.samplingPerson()).param("samplingPhone", details.samplingPhone())
                .param("samplingCertificate", details.samplingCertificate()).param("constructionUnit", details.constructionUnit())
                .param("needSampling", details.needSampling()).param("electronicSignature", details.electronicSignature())
                .param("cmaSeal", details.cmaSeal()).param("qualificationSeal", details.qualificationSeal())
                .param("externalCommission", details.externalCommission()).param("externalOrganization", details.externalOrganization())
                .param("externalNumber", details.externalNumber()).param("receivingAddress", details.sampleReceivingAddress())
                .param("expectedReportOn", details.expectedReportOn()).param("remark", details.remark())
                .param("sourceRemark", details.sourceRemark());
    }

    private void saveAgeReportSelections(UUID commissionId, List<String> requested) {
        List<String> values = cleanStrings(requested);
        if (values.isEmpty()) values = jdbc.sql("""
                SELECT DISTINCT option.value FROM detection_age_report_option option
                JOIN commission_detection_selection selection
                  ON selection.project_revision_id=option.project_revision_id
                WHERE selection.commission_id=:commission AND option.default_selected
                ORDER BY option.value
                """).param("commission", commissionId).query(String.class).list();
        if (!values.isEmpty()) {
            List<String> valid = jdbc.sql("""
                    SELECT DISTINCT option.value FROM detection_age_report_option option
                    JOIN commission_detection_selection selection
                      ON selection.project_revision_id=option.project_revision_id
                    WHERE selection.commission_id=:commission AND option.value IN (:values)
                    """).param("commission", commissionId).param("values", values).query(String.class).list();
            if (valid.size() != values.size()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_AGE_REPORT_INVALID", "龄期报告选项不属于当前委托的检测项目");
        }
        jdbc.sql("DELETE FROM commission_age_report_selection WHERE commission_id=:commission")
                .param("commission", commissionId).update();
        for (int index = 0; index < values.size(); index++) {
            jdbc.sql("""
                    INSERT INTO commission_age_report_selection(commission_id,value,sort_order)
                    VALUES (:commission,:value,:sortOrder)
                    """).param("commission", commissionId).param("value", values.get(index))
                    .param("sortOrder", index + 1).update();
        }
    }

    private void saveAttachments(UUID commissionId, List<UUID> requested) {
        List<UUID> ids = requested == null ? List.of() : requested.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.size() > 20) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "COMMISSION_ATTACHMENTS_TOO_MANY", "单份委托最多关联20个附件");
        if (!ids.isEmpty()) {
            long valid = jdbc.sql("SELECT count(*) FROM stored_file WHERE id IN (:ids)")
                    .param("ids", ids).query(Long.class).single();
            if (valid != ids.size()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_ATTACHMENT_INVALID", "委托附件不存在或已失效");
        }
        jdbc.sql("DELETE FROM commission_attachment WHERE commission_id=:commission AND purpose='INTAKE'")
                .param("commission", commissionId).update();
        for (int index = 0; index < ids.size(); index++) {
            jdbc.sql("""
                    INSERT INTO commission_attachment(commission_id,file_id,purpose,sort_order,created_by)
                    VALUES (:commission,:file,'INTAKE',:sortOrder,:actor)
                    """).param("commission", commissionId).param("file", ids.get(index))
                    .param("sortOrder", index + 1).param("actor", actor.require().id()).update();
        }
    }

    private Map<String, SampleFieldDefinition> sampleFieldDefinitions(UUID revisionId) {
        List<SampleFieldDefinition> definitions = jdbc.sql("""
                SELECT id,code,label,field_type,required,unit,core_binding
                FROM detection_sample_field_definition
                WHERE project_revision_id=:revision ORDER BY sort_order,label,id
                """).param("revision", revisionId).query((rs, row) -> new SampleFieldDefinition(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("label"),
                        rs.getString("field_type"), rs.getBoolean("required"), rs.getString("unit"),
                        List.of(), rs.getString("core_binding"), Set.of())).list();
        if (definitions.isEmpty()) return Map.of();
        Map<UUID, List<String>> options = new HashMap<>();
        jdbc.sql("""
                SELECT field.id,jsonb_array_elements_text(field.options_json) value
                FROM detection_sample_field_definition field
                WHERE field.project_revision_id=:revision
                """).param("revision", revisionId).query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    options.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rs.getString("value"));
                    return id;
                }).list();
        Map<UUID, Set<UUID>> standards = new HashMap<>();
        jdbc.sql("""
                SELECT field.id,link.standard_id FROM detection_sample_field_definition field
                JOIN detection_sample_field_standard link ON link.field_definition_id=field.id
                WHERE field.project_revision_id=:revision
                ORDER BY field.id,link.sort_order,link.standard_id
                """).param("revision", revisionId).query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    standards.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                            .add(rs.getObject("standard_id", UUID.class));
                    return id;
                }).list();
        Map<String, SampleFieldDefinition> result = new LinkedHashMap<>();
        for (SampleFieldDefinition field : definitions) {
            result.put(field.code(), new SampleFieldDefinition(field.id(), field.code(), field.label(),
                    field.fieldType(), field.required(), field.unit(),
                    List.copyOf(options.getOrDefault(field.id(), List.of())), field.coreBinding(),
                    Set.copyOf(standards.getOrDefault(field.id(), Set.of()))));
        }
        return result;
    }

    private void saveSampleFieldValues(UUID groupId, SampleGroup group, BigDecimal quantity,
                                       Map<String, SampleFieldDefinition> definitions) {
        Map<String, List<String>> provided = new LinkedHashMap<>();
        if (group.fieldValues() != null) {
            group.fieldValues().forEach((code, values) -> {
                if (code != null && !code.isBlank()) provided.put(code.trim(), cleanStrings(values));
            });
        }
        if (!definitions.keySet().containsAll(provided.keySet())) throw new ApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "COMMISSION_SAMPLE_FIELD_UNKNOWN",
                "样品组包含不属于当前检测项目的字段");
        for (SampleFieldDefinition field : definitions.values()) {
            String core = switch (field.coreBinding()) {
                case "NAME" -> group.name().trim();
                case "DESCRIPTION" -> nullable(group.description());
                case "QUANTITY" -> quantity.stripTrailingZeros().toPlainString();
                case "UNIT" -> group.unit().trim();
                default -> null;
            };
            List<String> values = provided.getOrDefault(field.code(), List.of());
            if (core != null) {
                if (!values.isEmpty() && (values.size() != 1 || !values.getFirst().equals(core))) {
                    throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                            "COMMISSION_SAMPLE_CORE_FIELD_CONFLICT", field.label() + "与样品组基本信息不一致");
                }
                values = List.of(core);
            }
            if (field.required() && values.isEmpty()) throw new ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "COMMISSION_SAMPLE_FIELD_REQUIRED",
                    "样品组“" + group.name().trim() + "”缺少必填字段：“" + field.label() + "”");
            if (values.isEmpty()) continue;
            if (values.size() > 200 || values.stream().anyMatch(value -> value.length() > 2000)) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "COMMISSION_SAMPLE_FIELD_VALUE_INVALID", field.label() + "的取值数量或长度超限");
            }
            switch (field.fieldType()) {
                case "TEXT" -> {
                    requireSingle(field, values);
                    insertSampleValue(groupId, field.id(), values.getFirst(), null, null, null, null, 1);
                }
                case "DECIMAL" -> {
                    requireSingle(field, values);
                    BigDecimal decimal = decimal(field, values.getFirst());
                    insertSampleValue(groupId, field.id(), null, decimal, null, null, null, 1);
                }
                case "DATE" -> {
                    requireSingle(field, values);
                    LocalDate date;
                    try { date = LocalDate.parse(values.getFirst()); }
                    catch (RuntimeException exception) { throw invalidField(field, "必须是有效日期"); }
                    insertSampleValue(groupId, field.id(), null, null, date, null, null, 1);
                }
                case "SINGLE_SELECT" -> {
                    requireSingle(field, values);
                    validateOptions(field, values);
                    insertSampleValue(groupId, field.id(), null, null, null, values.getFirst(), null, 1);
                }
                case "MULTI_SELECT" -> {
                    validateOptions(field, values);
                    for (int index = 0; index < values.size(); index++) {
                        insertSampleValue(groupId, field.id(), null, null, null, values.get(index), null, index + 1);
                    }
                }
                case "STANDARD_SELECT", "STANDARD_MULTI" -> {
                    if (field.fieldType().equals("STANDARD_SELECT")) requireSingle(field, values);
                    List<UUID> standardIds = values.stream().map(value -> {
                        try { return UUID.fromString(value); }
                        catch (RuntimeException exception) { throw invalidField(field, "包含无效检测依据"); }
                    }).distinct().toList();
                    if (standardIds.size() != values.size() || !field.standardIds().containsAll(standardIds)) {
                        throw invalidField(field, "包含未绑定或已停用的检测依据");
                    }
                    for (int index = 0; index < standardIds.size(); index++) {
                        insertSampleValue(groupId, field.id(), null, null, null, null, standardIds.get(index), index + 1);
                    }
                }
                default -> throw invalidField(field, "字段类型无效");
            }
        }
    }

    private void insertSampleValue(UUID groupId, UUID fieldId, String text, BigDecimal decimal, LocalDate date,
                                   String option, UUID standard, int sortOrder) {
        jdbc.sql("""
                INSERT INTO commission_sample_field_value(id,sample_group_id,field_definition_id,
                  text_value,decimal_value,date_value,option_value,standard_id,sort_order)
                VALUES (:id,:group,:field,:text,:decimal,:date,:option,:standard,:sortOrder)
                """).param("id", UUID.randomUUID()).param("group", groupId).param("field", fieldId)
                .param("text", text).param("decimal", decimal).param("date", date).param("option", option)
                .param("standard", standard).param("sortOrder", sortOrder).update();
    }

    private Map<UUID, Map<String, List<String>>> sampleFieldValues(UUID commissionId) {
        Map<UUID, Map<String, List<String>>> result = new HashMap<>();
        jdbc.sql("""
                SELECT value.sample_group_id,field.code,value.text_value,value.decimal_value,value.date_value,
                  value.option_value,value.standard_id
                FROM commission_sample_field_value value
                JOIN detection_sample_field_definition field ON field.id=value.field_definition_id
                JOIN commission_sample_group sample_group ON sample_group.id=value.sample_group_id
                WHERE sample_group.commission_id=:commission
                ORDER BY sample_group.sort_order,field.sort_order,value.sort_order,value.id
                """).param("commission", commissionId).query((rs, row) -> {
                    UUID groupId = rs.getObject("sample_group_id", UUID.class);
                    String value = rs.getString("text_value");
                    if (value == null && rs.getBigDecimal("decimal_value") != null) {
                        value = rs.getBigDecimal("decimal_value").stripTrailingZeros().toPlainString();
                    }
                    if (value == null && rs.getObject("date_value", LocalDate.class) != null) {
                        value = rs.getObject("date_value", LocalDate.class).toString();
                    }
                    if (value == null) value = rs.getString("option_value");
                    if (value == null && rs.getObject("standard_id", UUID.class) != null) {
                        value = rs.getObject("standard_id", UUID.class).toString();
                    }
                    result.computeIfAbsent(groupId, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("code"), ignored -> new ArrayList<>()).add(value);
                    return groupId;
                }).list();
        result.replaceAll((group, fields) -> {
            Map<String, List<String>> immutable = new LinkedHashMap<>();
            fields.forEach((code, values) -> immutable.put(code, List.copyOf(values)));
            return Map.copyOf(immutable);
        });
        return result;
    }

    private void requireSingle(SampleFieldDefinition field, List<String> values) {
        if (values.size() != 1) throw invalidField(field, "只能填写一个值");
    }

    private void validateOptions(SampleFieldDefinition field, List<String> values) {
        if (!field.options().containsAll(values) || values.stream().distinct().count() != values.size()) {
            throw invalidField(field, "包含未配置或重复的选项");
        }
    }

    private BigDecimal decimal(SampleFieldDefinition field, String value) {
        try {
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.precision() > 30 || decimal.scale() > 10) throw new NumberFormatException();
            return decimal;
        } catch (RuntimeException exception) {
            throw invalidField(field, "必须是最多30位、10位小数的数值");
        }
    }

    private ApiException invalidField(SampleFieldDefinition field, String reason) {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "COMMISSION_SAMPLE_FIELD_VALUE_INVALID", field.label() + reason);
    }

    private List<String> blockers(UUID id, String action) {
        List<String> blockers = new ArrayList<>();
        if (action.equals("SUBMIT")) {
            long projects = jdbc.sql("SELECT count(*) FROM commission_detection_selection WHERE commission_id=:id")
                    .param("id", id).query(Long.class).single();
            long parameters = jdbc.sql("""
                    SELECT count(*) FROM commission_parameter_selection p
                    JOIN commission_detection_selection d ON d.id=p.detection_selection_id
                    WHERE d.commission_id=:id
                    """).param("id", id).query(Long.class).single();
            long groups = jdbc.sql("SELECT count(*) FROM commission_sample_group WHERE commission_id=:id")
                    .param("id", id).query(Long.class).single();
            if (projects == 0) blockers.add("至少选择一个检测项目");
            if (parameters == 0) blockers.add("至少选择一项检测参数");
            if (groups == 0) blockers.add("至少配置一个样品组");
            List<String> missingFields = jdbc.sql("""
                    SELECT DISTINCT group_row.name || '：' || field.label
                    FROM commission_sample_group group_row
                    JOIN commission_parameter_selection parameter ON parameter.sample_group_id=group_row.id
                    JOIN commission_detection_selection selection ON selection.id=parameter.detection_selection_id
                    JOIN detection_sample_field_definition field
                      ON field.project_revision_id=selection.project_revision_id AND field.required
                    LEFT JOIN commission_sample_field_value value
                      ON value.sample_group_id=group_row.id AND value.field_definition_id=field.id
                    WHERE group_row.commission_id=:commission AND value.id IS NULL AND field.core_binding='NONE'
                    ORDER BY 1 LIMIT 20
                    """).param("commission", id).query(String.class).list();
            missingFields.forEach(field -> blockers.add("样品字段未填写：" + field));
        }
        if (action.equals("COMPLETE_SAMPLING")) {
            long samples = jdbc.sql("SELECT count(*) FROM sample WHERE commission_id=:id AND state <> 'EXPECTED'")
                    .param("id", id).query(Long.class).single();
            if (samples == 0) blockers.add("至少接收一份样品");
        }
        if (action.equals("START_TESTING")) {
            long tasks = jdbc.sql("SELECT count(*) FROM test_task WHERE commission_id=:id AND state IN ('ASSIGNED','ACCEPTED')")
                    .param("id", id).query(Long.class).single();
            if (tasks == 0) blockers.add("至少分配一项检测任务");
        }
        if (action.equals("COMPLETE_TESTING")) {
            long unfinished = jdbc.sql("SELECT count(*) FROM test_task WHERE commission_id=:id AND state <> 'COMPLETED'")
                    .param("id", id).query(Long.class).single();
            long openNc = jdbc.sql("SELECT count(*) FROM nonconformance WHERE commission_id=:id AND state NOT IN ('CLOSED','REJECTED')")
                    .param("id", id).query(Long.class).single();
            if (unfinished > 0) blockers.add("仍有未完成检测任务：" + unfinished);
            if (openNc > 0) blockers.add("仍有未关闭不符合项：" + openNc);
        }
        if (action.equals("MARK_ISSUED")) {
            long issued = jdbc.sql("SELECT count(*) FROM report WHERE commission_id=:id AND state IN ('ISSUED','DISTRIBUTED','ARCHIVED')")
                    .param("id", id).query(Long.class).single();
            if (issued == 0) blockers.add("报告尚未签发");
        }
        if (action.equals("ARCHIVE")) {
            long archived = jdbc.sql("""
                    SELECT count(*) FROM archive_package a JOIN report r ON r.id=a.report_id WHERE r.commission_id=:id
                    """).param("id", id).query(Long.class).single();
            if (archived == 0) blockers.add("报告证据包尚未归档");
        }
        return blockers;
    }

    private String baseSelect() {
        return """
                SELECT c.id,c.number,c.precommission_number,c.project_id,p.name project_name,c.customer_id,u.name customer_name,
                  c.contact_name,c.contact_phone,c.commissioned_on,c.due_on,c.state,c.version,c.selection_frozen_at,
                  c.project_progress,c.testing_type,c.testing_standard,c.testing_place,c.sample_handling,
                  c.report_delivery,c.report_delivery_address,c.report_copies,c.project_part,c.monomer,
                  c.settlement_contact,c.witness_unit,c.witness_name,c.witness_phone,c.witness_certificate,
                  c.sampling_unit,c.sampling_person,c.sampling_phone,c.sampling_certificate,c.construction_unit,
                  c.need_sampling,c.electronic_signature,c.cma_seal,c.qualification_seal,c.external_commission,
                  c.external_organization,c.external_number,c.sample_receiving_address,c.expected_report_on,
                  c.remark,c.source_remark,
                  (SELECT count(*) FROM commission_parameter_selection ps
                    JOIN commission_detection_selection ds ON ds.id=ps.detection_selection_id
                    WHERE ds.commission_id=c.id) selection_count,c.updated_at
                FROM commission c JOIN project p ON p.id=c.project_id JOIN customer u ON u.id=c.customer_id
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        CommissionState state = CommissionState.valueOf(rs.getString("state"));
        IntakeDetailsView details = new IntakeDetailsView(rs.getString("project_progress"),
                rs.getString("testing_type"), rs.getString("testing_standard"), rs.getString("testing_place"),
                rs.getString("sample_handling"), rs.getString("report_delivery"),
                rs.getString("report_delivery_address"), rs.getInt("report_copies"), rs.getString("project_part"),
                rs.getString("monomer"), rs.getString("settlement_contact"), rs.getString("witness_unit"),
                rs.getString("witness_name"), rs.getString("witness_phone"), rs.getString("witness_certificate"),
                rs.getString("sampling_unit"), rs.getString("sampling_person"), rs.getString("sampling_phone"),
                rs.getString("sampling_certificate"), rs.getString("construction_unit"), rs.getBoolean("need_sampling"),
                rs.getBoolean("electronic_signature"), rs.getBoolean("cma_seal"),
                rs.getBoolean("qualification_seal"), rs.getBoolean("external_commission"),
                rs.getString("external_organization"), rs.getString("external_number"),
                rs.getString("sample_receiving_address"), rs.getObject("expected_report_on", LocalDate.class),
                rs.getString("remark"), rs.getString("source_remark"), List.of(), List.of());
        return new View(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("precommission_number"),
                rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getString("contact_name"),
                rs.getString("contact_phone"), rs.getObject("commissioned_on", LocalDate.class),
                rs.getObject("due_on", LocalDate.class), state, rs.getLong("version"), rs.getLong("selection_count"),
                rs.getObject("selection_frozen_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), details, state.allowedActions());
    }

    private View withCollections(View view) {
        List<String> ages = jdbc.sql("""
                SELECT value FROM commission_age_report_selection WHERE commission_id=:commission
                ORDER BY sort_order,value
                """).param("commission", view.id()).query(String.class).list();
        List<FileView> files = jdbc.sql("""
                SELECT file.id,file.original_name FROM commission_attachment attachment
                JOIN stored_file file ON file.id=attachment.file_id
                WHERE attachment.commission_id=:commission AND attachment.purpose='INTAKE'
                ORDER BY attachment.sort_order,file.id
                """).param("commission", view.id()).query((rs, row) -> new FileView(
                        rs.getObject("id", UUID.class), rs.getString("original_name"))).list();
        IntakeDetailsView old = view.details();
        IntakeDetailsView details = new IntakeDetailsView(old.projectProgress(), old.testingType(),
                old.testingStandard(), old.testingPlace(), old.sampleHandling(), old.reportDelivery(),
                old.reportDeliveryAddress(), old.reportCopies(), old.projectPart(), old.monomer(),
                old.settlementContact(), old.witnessUnit(), old.witnessName(), old.witnessPhone(),
                old.witnessCertificate(), old.samplingUnit(), old.samplingPerson(), old.samplingPhone(),
                old.samplingCertificate(), old.constructionUnit(), old.needSampling(), old.electronicSignature(),
                old.cmaSeal(), old.qualificationSeal(), old.externalCommission(), old.externalOrganization(),
                old.externalNumber(), old.sampleReceivingAddress(), old.expectedReportOn(), old.remark(),
                old.sourceRemark(), List.copyOf(ages), List.copyOf(files));
        return new View(view.id(), view.number(), view.precommissionNumber(), view.projectId(), view.projectName(),
                view.customerId(), view.customerName(), view.contactName(), view.contactPhone(), view.commissionedOn(),
                view.dueOn(), view.state(), view.version(), view.selectionCount(), view.selectionFrozenAt(),
                view.updatedAt(), details, view.allowedActions());
    }

    private String summarySelect() {
        return """
                SELECT view_row.row_id,view_row.sample_id,view_row.sample_group_id,view_row.sample_number,
                  view_row.sample_name,view_row.sample_quantity,view_row.sample_unit,
                  c.id,c.number,c.precommission_number,c.management_sequence,c.internal_sequence,c.external_number,
                  c.project_id,p.number project_number,p.name project_name,c.customer_id,u.name customer_name,
                  c.contact_name,c.commissioned_on,c.due_on,c.state,c.testing_place,c.external_commission,
                  COALESCE(selection.projects,'') detection_projects,COALESCE(selection.parameter_count,0) parameter_count,
                  COALESCE(sample_summary.group_count,0) sample_group_count,flow_summary.status flow_status,
                  task_summary.status task_status,
                  report_summary.status report_status,c.print_status,acceptor.display_name receiver_name,
                  department.name department_name,c.version,c.created_at,view_row.row_updated_at
                FROM commission c JOIN project p ON p.id=c.project_id JOIN customer u ON u.id=c.customer_id
                """ + listViewJoin() + flowSummaryJoin() + """
                LEFT JOIN app_user acceptor ON acceptor.id=c.accepted_by
                LEFT JOIN department ON department.id=c.received_department_id
                LEFT JOIN LATERAL (SELECT string_agg(DISTINCT ds.project_name_snapshot,'、'
                    ORDER BY ds.project_name_snapshot) projects,count(ps.id) parameter_count
                  FROM commission_detection_selection ds LEFT JOIN commission_parameter_selection ps
                    ON ps.detection_selection_id=ds.id WHERE ds.commission_id=c.id) selection ON true
                LEFT JOIN LATERAL (SELECT count(*) group_count FROM commission_sample_group sample_group
                  WHERE sample_group.commission_id=c.id) sample_summary ON true
                LEFT JOIN LATERAL (SELECT CASE WHEN count(*)=0 THEN 'NOT_ASSIGNED'
                  WHEN bool_and(t.state='COMPLETED') THEN 'COMPLETED'
                  WHEN bool_or(t.state='RETURNED') THEN 'RETURNED'
                  WHEN bool_or(t.state IN ('IN_PROGRESS','SUBMITTED')) THEN 'IN_PROGRESS'
                  WHEN bool_or(t.state IN ('ASSIGNED','ACCEPTED')) THEN 'ASSIGNED' ELSE 'UNASSIGNED' END status
                  FROM test_task t WHERE t.commission_id=c.id) task_summary ON true
                LEFT JOIN LATERAL (SELECT CASE WHEN count(*)=0 THEN 'NOT_CREATED'
                  WHEN bool_and(report.state='VOID') THEN 'VOID'
                  WHEN bool_or(report.state='ARCHIVED') THEN 'ARCHIVED'
                  WHEN bool_or(report.state='DISTRIBUTED') THEN 'DISTRIBUTED'
                  WHEN bool_or(report.state='ISSUED') THEN 'ISSUED'
                  WHEN bool_or(report.state='APPROVED') THEN 'APPROVED'
                  WHEN bool_or(report.state='IN_REVIEW') THEN 'IN_REVIEW'
                  WHEN bool_or(report.state='RETURNED') THEN 'RETURNED' ELSE 'DRAFT' END status
                  FROM report WHERE report.commission_id=c.id) report_summary ON true
                """;
    }

    private String listViewJoin() {
        return """
                JOIN LATERAL (
                  SELECT c.id row_id,NULL::uuid sample_id,NULL::uuid sample_group_id,NULL::varchar sample_number,
                    NULL::varchar sample_name,NULL::varchar sample_quantity,NULL::varchar sample_unit,
                    c.updated_at row_updated_at
                  WHERE NOT :sampleView
                  UNION ALL
                  SELECT COALESCE(sample_row.id,sample_group.id),sample_row.id,sample_group.id,
                    sample_row.number,COALESCE(sample_row.name,sample_group.name),
                    COALESCE(sample_row.received_quantity::text,sample_group.quantity::text),
                    COALESCE(sample_row.unit,sample_group.unit),
                    GREATEST(c.updated_at,COALESCE(sample_row.updated_at,c.updated_at))
                  FROM commission_sample_group sample_group
                  LEFT JOIN sample sample_row ON sample_row.sample_group_id=sample_group.id
                  WHERE :sampleView AND sample_group.commission_id=c.id
                ) view_row ON true
                """;
    }

    private String flowSummaryJoin() {
        return """
                LEFT JOIN LATERAL (SELECT CASE
                  WHEN c.state IN ('DRAFT','PRE_ACCEPTED') OR EXISTS(SELECT 1 FROM commission_transfer pending_transfer
                    WHERE pending_transfer.commission_id=c.id AND pending_transfer.state='PENDING')
                  THEN 'PENDING' ELSE 'TRANSFERRED' END status) flow_summary ON true
                """;
    }

    private Summary mapSummary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Summary(rs.getObject("row_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("sample_id", UUID.class), rs.getObject("sample_group_id", UUID.class),
                rs.getString("sample_number"), rs.getString("sample_name"), rs.getString("sample_quantity"),
                rs.getString("sample_unit"), rs.getString("number"),
                rs.getString("precommission_number"), rs.getLong("management_sequence"),
                rs.getObject("internal_sequence", Long.class),
                rs.getString("external_number"), rs.getObject("project_id", UUID.class),
                rs.getString("project_number"), rs.getString("project_name"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("contact_name"), rs.getObject("commissioned_on", LocalDate.class),
                rs.getObject("due_on", LocalDate.class), CommissionState.valueOf(rs.getString("state")),
                rs.getString("testing_place"), rs.getBoolean("external_commission"),
                rs.getString("detection_projects"), rs.getLong("parameter_count"),
                rs.getLong("sample_group_count"), rs.getString("flow_status"),
                rs.getString("task_status"), rs.getString("report_status"),
                rs.getString("print_status"), rs.getString("receiver_name"), rs.getString("department_name"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("row_updated_at", OffsetDateTime.class));
    }

    private String listFilters() {
        return """
                  AND (CAST(:from AS date) IS NULL OR c.commissioned_on>=CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR c.commissioned_on<=CAST(:to AS date))
                  AND (:states='' OR c.state=ANY(string_to_array(:states,',')))
                  AND (:flowStates='' OR flow_summary.status=ANY(string_to_array(:flowStates,',')))
                  AND (:taskStates='' OR task_summary.status=ANY(string_to_array(:taskStates,',')))
                  AND (:testingPlace='' OR c.testing_place=:testingPlace)
                  AND (CAST(:external AS boolean) IS NULL OR c.external_commission=CAST(:external AS boolean))
                  AND (:detectionQuery='' OR EXISTS(SELECT 1 FROM commission_detection_selection ds
                    WHERE ds.commission_id=c.id AND lower(ds.project_name_snapshot) LIKE :detectionPattern))
                  AND (:commissionNumberQuery='' OR lower(c.number) LIKE :commissionNumberPattern)
                  AND (:sampleNumberQuery='' OR (:sampleView AND lower(COALESCE(view_row.sample_number,'')) LIKE :sampleNumberPattern)
                    OR (NOT :sampleView AND EXISTS(SELECT 1 FROM sample filter_sample
                      WHERE filter_sample.commission_id=c.id AND lower(filter_sample.number) LIKE :sampleNumberPattern)))
                  AND (:precommissionNumberQuery='' OR lower(COALESCE(c.precommission_number,'')) LIKE :precommissionNumberPattern)
                  AND (:customerQuery='' OR lower(u.name) LIKE :customerPattern)
                  AND (:projectNumberQuery='' OR lower(p.number) LIKE :projectNumberPattern)
                  AND (:projectNameQuery='' OR lower(p.name) LIKE :projectNamePattern)
                  AND (CAST(:sequenceMin AS bigint) IS NULL OR c.management_sequence>=CAST(:sequenceMin AS bigint))
                  AND (CAST(:sequenceMax AS bigint) IS NULL OR c.management_sequence<=CAST(:sequenceMax AS bigint))
                  AND (CAST(:filterDepartment AS uuid) IS NULL OR c.received_department_id=CAST(:filterDepartment AS uuid))
                  AND (CAST(:waitingFlow AS boolean) IS NULL OR NOT CAST(:waitingFlow AS boolean)
                    OR flow_summary.status='PENDING')
                  AND (:query='' OR lower(c.number) LIKE :pattern OR lower(COALESCE(c.precommission_number,'')) LIKE :pattern
                    OR lower(p.number) LIKE :pattern OR lower(p.name) LIKE :pattern OR lower(u.name) LIKE :pattern
                    OR lower(c.contact_name) LIKE :pattern OR EXISTS(SELECT 1 FROM commission_detection_selection query_ds
                      WHERE query_ds.commission_id=c.id AND lower(query_ds.project_name_snapshot) LIKE :pattern))
                """;
    }

    private void bindList(JdbcClient.StatementSpec statement, AuthenticatedUser user, ListQuery filters) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("from", filters.commissionedFrom()).param("to", filters.commissionedTo())
                .param("states", upperCsv(filters.states())).param("flowStates", upperCsv(filters.flowStates()))
                .param("taskStates", upperCsv(filters.taskStates()))
                .param("testingPlace", upper(filters.testingPlace())).param("external", filters.externalCommission())
                .param("detectionQuery", text(filters.detectionProject())).param("detectionPattern", like(filters.detectionProject()))
                .param("commissionNumberQuery", text(filters.commissionNumber())).param("commissionNumberPattern", like(filters.commissionNumber()))
                .param("sampleNumberQuery", text(filters.sampleNumber())).param("sampleNumberPattern", like(filters.sampleNumber()))
                .param("precommissionNumberQuery", text(filters.precommissionNumber())).param("precommissionNumberPattern", like(filters.precommissionNumber()))
                .param("customerQuery", text(filters.customer())).param("customerPattern", like(filters.customer()))
                .param("projectNumberQuery", text(filters.projectNumber())).param("projectNumberPattern", like(filters.projectNumber()))
                .param("projectNameQuery", text(filters.projectName())).param("projectNamePattern", like(filters.projectName())).param("sequenceMin", filters.managementSequenceMin())
                .param("sequenceMax", filters.managementSequenceMax()).param("filterDepartment", filters.departmentId())
                .param("waitingFlow", filters.waitingFlow()).param("query", text(filters.query()))
                .param("pattern", like(filters.query()));
    }

    private String scopeClause() {
        return """
                 AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=c.created_by AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM test_task task_row JOIN person person_row ON person_row.id=task_row.assigned_to
                      WHERE task_row.commission_id=c.id AND person_row.department_id=:department)))
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=c.project_id AND member.user_id=:actor))
                  OR (:scope='SELF' AND (c.created_by=:actor OR EXISTS(SELECT 1 FROM test_task task_row
                    WHERE task_row.commission_id=c.id AND task_row.assigned_to=:person))))
                """;
    }

    private java.util.Optional<UUID> latestCommissionTemplate() {
        List<UUID> candidates = jdbc.sql("""
                SELECT configuration.id FROM configuration_version configuration
                WHERE configuration.configuration_type='COMMISSION_TEMPLATE' AND configuration.state='PUBLISHED'
                  AND configuration.content_json->>'rendererVersion'='1'
                  AND jsonb_typeof(configuration.content_json->'blocks')='array'
                  AND jsonb_array_length(CASE
                    WHEN jsonb_typeof(configuration.content_json->'blocks')='array'
                    THEN configuration.content_json->'blocks' ELSE '[]'::jsonb END) BETWEEN 1 AND 200
                  AND NOT EXISTS (
                    SELECT 1 FROM jsonb_array_elements(CASE
                      WHEN jsonb_typeof(configuration.content_json->'blocks')='array'
                    THEN configuration.content_json->'blocks' ELSE '[]'::jsonb END) block
                    WHERE block->>'type' NOT IN ('HEADING','FIELD')
                      OR NOT jsonb_exists(block,'label')
                      OR length(trim(block->>'label')) NOT BETWEEN 1 AND 120
                      OR (block->>'type'='FIELD' AND NOT jsonb_exists(block,'key'))
                      OR (block->>'type'='FIELD' AND block->>'key' NOT IN
                        ('number','precommissionNumber','projectNumber','projectName','customerName',
                         'contactName','contactPhone','commissionedOn','dueOn','state','testingPlace',
                         'detectionProjects','projectPart','monomer','remark'))
                  )
                ORDER BY configuration.version_number DESC,configuration.id DESC LIMIT 1
                """).query(UUID.class).list();
        return candidates.stream().findFirst();
    }

    private String jsonIds(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<Option> options(String... values) {
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) options.add(new Option(values[index], values[index + 1]));
        return List.copyOf(options);
    }

    private String optionCode(String value, List<Option> options, String code, String message) {
        String normalized = upper(value);
        if (options.stream().noneMatch(option -> option.code().equals(normalized))) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, code, message);
        }
        return normalized;
    }

    private void validateDates(LocalDate commissionedOn, LocalDate dueOn) {
        if (commissionedOn == null || dueOn == null || dueOn.isBefore(commissionedOn)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMISSION_DATES_INVALID", "委托日期不能为空，计划完成日期不得早于委托日期");
        }
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "COMMISSION_REQUIRED_FIELD", message);
        return value.trim();
    }

    private List<String> cleanStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private String upper(String value) { return text(value).toUpperCase(Locale.ROOT); }
    private String upperCsv(String value) {
        return String.join(",", java.util.Arrays.stream(text(value).split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).map(item -> item.toUpperCase(Locale.ROOT)).distinct().toList());
    }
    private String like(String value) { return "%" + text(value).toLowerCase(Locale.ROOT) + "%"; }
}
