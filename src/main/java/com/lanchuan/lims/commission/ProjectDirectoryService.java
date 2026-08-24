package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.AdministrativeDivisionService;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectDirectoryService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final NumberingService numbering;
    private final AdministrativeDivisionService divisions;

    public ProjectDirectoryService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                   NumberingService numbering, AdministrativeDivisionService divisions) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.numbering = numbering;
        this.divisions = divisions;
    }

    public record Summary(UUID id, String number, String pileSerialNumber, String name,
                          UUID customerId, String customerName, String ownerName,
                          String supervisionName, String constructionName, LocalDate handledOn,
                          String progressPercent, String status, UUID projectTeamId, String projectTeamName,
                          boolean regulatoryLinked, String externalContractNumber, long version,
                          OffsetDateTime updatedAt) {}
    public record PartyView(UUID id, String partyRole, UUID customerId, String name,
                            String contactName, String contactPhone, String certificateNumber,
                            boolean manualEntry, boolean reportAccess, long version) {}
    public record Information(UUID id, String number, String name, UUID customerId, String location,
                              String pileSerialNumber, String projectType, String projectGrade,
                              String regulatoryOrganization, String regulatoryProjectCode,
                              String regulatoryProjectName, boolean regulatoryLinked,
                              String entrusterName, String entrusterPhone, String settlementContactName,
                              String settlementContactPhone, String samplingUnitName, String samplerName,
                              String samplerPhone, String samplerCertificateNumber, String province,
                              String city, String district, String detailedAddress, String sectionNumber,
                              String regionCode, String defaultDetectionCategory, String remark,
                              String ownerName, boolean ownerReportAccess, String supervisionName,
                              String witnessName, String witnessPhone, boolean supervisionReportAccess,
                              String constructionName, boolean constructionReportAccess, String surveyName,
                              String designName, String generalContractorName, boolean customerReportAccess,
                              String cmaSealPolicy, String qualificationSealPolicy, String electronicSealPolicy,
                              boolean customerPrintAllowed, Integer customerPrintLimit, String settlementMethod,
                              UUID projectTeamId, boolean reportPrintRestricted, String externalContractNumber,
                              String status, String progressPercent, LocalDate handledOn, String latitude,
                              String longitude, long version, OffsetDateTime updatedAt) {}
    public record Detail(Information information, List<PartyView> parties, List<String> allowedActions) {}
    public record PartyWrite(String partyRole, UUID customerId, String name, String contactName,
                             String contactPhone, String certificateNumber, boolean manualEntry,
                             boolean reportAccess) {}
    public record Write(String number, String name, UUID customerId, String location,
                        String pileSerialNumber, String projectType, String projectGrade,
                        String regulatoryOrganization, String regulatoryProjectCode,
                        String regulatoryProjectName, boolean regulatoryLinked,
                        String entrusterName, String entrusterPhone, String settlementContactName,
                        String settlementContactPhone, String samplingUnitName, String samplerName,
                        String samplerPhone, String samplerCertificateNumber, String province,
                        String city, String district, String detailedAddress, String sectionNumber,
                        String regionCode, String defaultDetectionCategory, String remark,
                        String ownerName, boolean ownerReportAccess, String supervisionName,
                        String witnessName, String witnessPhone, boolean supervisionReportAccess,
                        String constructionName, boolean constructionReportAccess, String surveyName,
                        String designName, String generalContractorName, boolean customerReportAccess,
                        String cmaSealPolicy, String qualificationSealPolicy, String electronicSealPolicy,
                        boolean customerPrintAllowed, Integer customerPrintLimit, String settlementMethod,
                        UUID projectTeamId, boolean reportPrintRestricted, String externalContractNumber,
                        LocalDate handledOn, String latitude, String longitude, List<PartyWrite> parties,
                        Long expectedVersion) {}
    public record PlanView(UUID id, UUID detectionProjectRevisionId, UUID parameterId, String categoryName,
                           String projectName, String parameterName, String plannedQuantity, String unit,
                           String bindingParameter, String detectionMethod, LocalDate plannedCompletionOn,
                           String unitPrice, String totalPrice, String completedQuantity,
                           String completionPercent, String completedContractPrice, String lagStatus,
                           boolean active, int sortOrder, long version, OffsetDateTime updatedAt) {}
    public record PlanWrite(UUID detectionProjectRevisionId, UUID parameterId, String categoryName,
                            String projectName, String parameterName, String plannedQuantity, String unit,
                            String bindingParameter, String detectionMethod, LocalDate plannedCompletionOn,
                            String unitPrice, boolean active, int sortOrder, Long expectedVersion) {}
    public record ProgressMarkView(UUID id, String progressPercent, String progressLabel, LocalDate occurredOn,
                                   String comment, String createdBy, OffsetDateTime createdAt) {}
    public record ProgressMarkWrite(String progressPercent, String progressLabel, LocalDate occurredOn,
                                    String comment, long expectedVersion) {}
    public record PartView(UUID id, UUID parentId, String code, String name, int sortOrder,
                           boolean active, long version, OffsetDateTime updatedAt) {}
    public record PartWrite(UUID parentId, String code, String name, int sortOrder,
                            boolean active, Long expectedVersion) {}
    public record NumberGeneration(String number) {}
    public record Options(List<String> projectTypes, List<String> projectGrades,
                          List<String> defaultDetectionCategories) {}

    public Options options() {
        return new Options(optionValues("PROJECT_TYPE"), optionValues("PROJECT_GRADE"),
                optionValues("DEFAULT_DETECTION_CATEGORY"));
    }

    @Transactional
    public NumberGeneration generateNumber() {
        NumberGeneration generated = new NumberGeneration(numbering.next("PROJECT", "GC"));
        audit.record("PROJECT_NUMBER_GENERATED", "PROJECT_NUMBER", generated.number(), null, generated,
                null, "工程自编号按受控序列生成");
        return generated;
    }

    public CursorPage<Summary> list(String query, String regulatoryOrganization, String statuses,
                                    Boolean regulatoryLinked, UUID projectTeamId, String number,
                                    String pileSerialNumber, String name, String entrustingName,
                                    String ownerName, String supervisionName, String constructionName,
                                    String cursorValue, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        String pattern = "%" + text(query).toLowerCase() + "%";
        String scope = scopeClause();
        String sql = baseSummarySelect() + " WHERE p.organization_id=:organization " + scope + """
                  AND (:regulatory='' OR lower(COALESCE(p.regulatory_organization,'')) LIKE :regulatory)
                  AND (:statuses='' OR p.status = ANY(string_to_array(:statuses, ',')))
                  AND (CAST(:linked AS boolean) IS NULL OR p.regulatory_linked=CAST(:linked AS boolean))
                  AND (CAST(:team AS uuid) IS NULL OR p.project_team_id=CAST(:team AS uuid))
                  AND (:number='' OR lower(p.number) LIKE :number)
                  AND (:pile='' OR lower(COALESCE(p.pile_serial_number,'')) LIKE :pile)
                  AND (:name='' OR lower(p.name) LIKE :name)
                  AND (:entrusting='' OR lower(CASE WHEN entrusting.manual_entry
                    THEN entrusting.name_snapshot ELSE c.name END) LIKE :entrusting)
                  AND (:owner='' OR lower(COALESCE(p.owner_name,'')) LIKE :owner)
                  AND (:supervision='' OR lower(COALESCE(p.supervision_name,'')) LIKE :supervision)
                  AND (:construction='' OR lower(COALESCE(p.construction_name,'')) LIKE :construction)
                  AND (:query='' OR lower(p.number) LIKE :pattern OR lower(p.name) LIKE :pattern
                    OR lower(c.name) LIKE :pattern OR lower(COALESCE(p.pile_serial_number,'')) LIKE :pattern)
                  AND (p.updated_at,p.id)<(:cursorAt,:cursorId)
                ORDER BY p.updated_at DESC,p.id DESC LIMIT :limit
                """;
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(sql).param("organization", user.organizationId())
                .param("scope", user.dataScope()).param("actor", user.id())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("regulatory", like(regulatoryOrganization)).param("statuses", upperCsv(statuses))
                .param("linked", regulatoryLinked).param("team", projectTeamId).param("number", like(number))
                .param("pile", like(pileSerialNumber)).param("name", like(name)).param("entrusting", like(entrustingName))
                .param("owner", like(ownerName)).param("supervision", like(supervisionName))
                .param("construction", like(constructionName)).param("query", text(query)).param("pattern", pattern)
                .param("cursorAt", cursorAt).param("cursorId", cursorId).param("limit", limit + 1);
        List<Summary> fetched = statement.query(this::mapSummary).list();
        boolean hasMore = fetched.size() > limit;
        List<Summary> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        long total = jdbc.sql("SELECT count(*) FROM project p JOIN customer c ON c.id=p.customer_id "
                        + "LEFT JOIN project_party entrusting ON entrusting.project_id=p.id AND entrusting.party_role='ENTRUSTING' "
                        + "WHERE p.organization_id=:organization " + scope
                        + " AND (:regulatory='' OR lower(COALESCE(p.regulatory_organization,'')) LIKE :regulatory)"
                        + " AND (:statuses='' OR p.status = ANY(string_to_array(:statuses, ',')))"
                        + " AND (CAST(:linked AS boolean) IS NULL OR p.regulatory_linked=CAST(:linked AS boolean))"
                        + " AND (CAST(:team AS uuid) IS NULL OR p.project_team_id=CAST(:team AS uuid))"
                        + " AND (:number='' OR lower(p.number) LIKE :number)"
                        + " AND (:pile='' OR lower(COALESCE(p.pile_serial_number,'')) LIKE :pile)"
                        + " AND (:name='' OR lower(p.name) LIKE :name)"
                        + " AND (:entrusting='' OR lower(CASE WHEN entrusting.manual_entry"
                        + " THEN entrusting.name_snapshot ELSE c.name END) LIKE :entrusting)"
                        + " AND (:owner='' OR lower(COALESCE(p.owner_name,'')) LIKE :owner)"
                        + " AND (:supervision='' OR lower(COALESCE(p.supervision_name,'')) LIKE :supervision)"
                        + " AND (:construction='' OR lower(COALESCE(p.construction_name,'')) LIKE :construction)"
                        + " AND (:query='' OR lower(p.number) LIKE :pattern OR lower(p.name) LIKE :pattern"
                        + " OR lower(c.name) LIKE :pattern OR lower(COALESCE(p.pile_serial_number,'')) LIKE :pattern)")
                .param("organization", user.organizationId()).param("scope", user.dataScope()).param("actor", user.id())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("regulatory", like(regulatoryOrganization)).param("statuses", upperCsv(statuses))
                .param("linked", regulatoryLinked).param("team", projectTeamId).param("number", like(number))
                .param("pile", like(pileSerialNumber)).param("name", like(name)).param("entrusting", like(entrustingName))
                .param("owner", like(ownerName)).param("supervision", like(supervisionName))
                .param("construction", like(constructionName)).param("query", text(query)).param("pattern", pattern)
                .query(Long.class).single();
        return new CursorPage<>(items, next, hasMore, total);
    }

    public Detail detail(UUID id) {
        Information information = information(id);
        return new Detail(information, parties(id), allowedActions(information.status()));
    }

    @Transactional
    public Detail create(Write body) {
        validate(body, true);
        requireCustomer(body.customerId());
        requireTeam(body.projectTeamId());
        ensureNumber(body.number(), null);
        UUID id = UUID.randomUUID();
        executeWrite(insertSql(), id, body, null, true);
        saveParties(id, body.parties());
        Detail created = detail(id);
        audit.record("PROJECT_CREATED", "PROJECT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public Detail update(UUID id, Write body) {
        validate(body, false);
        Detail before = detail(id);
        requireCustomer(body.customerId());
        requireTeam(body.projectTeamId());
        ensureNumber(body.number(), id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新工程必须携带当前版本");
        int changed = executeWrite(updateSql(), id, body, body.expectedVersion(), false);
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "工程信息已被其他用户更新",
                information(id).version());
        saveParties(id, body.parties());
        Detail after = detail(id);
        audit.record("PROJECT_UPDATED", "PROJECT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        Information before = information(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "工程状态已被其他用户更新", before.version());
        }
        String action = upper(command.action());
        String next = ProjectState.valueOf(before.status()).transition(action).name();
        if (action.equals("SUBMIT")) {
            List<String> blockers = new ArrayList<>();
            if (blank(before.ownerName())) blockers.add("建设单位不能为空");
            if (blank(before.supervisionName())) blockers.add("监理单位不能为空");
            if (blank(before.constructionName())) blockers.add("施工单位不能为空");
            if (!blockers.isEmpty()) throw ApiException.blocked("PROJECT_SUBMIT_BLOCKED",
                    "工程信息尚不完整", before.version(), blockers);
        }
        int changed = jdbc.sql("""
                UPDATE project SET status=:status,progress_percent=CASE WHEN :status='COMPLETED' THEN 100 ELSE progress_percent END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("status", next).param("id", id).param("organization", actor.require().organizationId())
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "工程状态已被其他用户更新", information(id).version());
        Information after = information(id);
        audit.record("PROJECT_" + action, "PROJECT", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), allowedActions(after.status()));
    }

    public List<PlanView> plans(UUID projectId, String query) {
        information(projectId);
        return jdbc.sql("""
                SELECT pl.id,pl.detection_project_revision_id,pl.parameter_id,pl.category_name,pl.project_name,
                  pl.parameter_name,pl.planned_quantity,pl.unit,pl.binding_parameter,pl.detection_method,
                  pl.planned_completion_on,pl.unit_price,(pl.planned_quantity*pl.unit_price) total_price,
                  COALESCE(done.completed_quantity,0) completed_quantity,
                  CASE WHEN pl.planned_quantity=0 THEN 0 ELSE LEAST(100,done.completed_quantity/pl.planned_quantity*100) END completion_percent,
                  COALESCE(done.completed_quantity,0)*pl.unit_price completed_contract_price,
                  CASE WHEN pl.planned_completion_on<CURRENT_DATE AND COALESCE(done.completed_quantity,0)<pl.planned_quantity
                    THEN 'DELAYED' ELSE 'NORMAL' END lag_status,
                  pl.active,pl.sort_order,pl.version,pl.updated_at
                FROM project_detection_plan pl
                LEFT JOIN LATERAL (
                  SELECT COALESCE(sum(g.quantity),0) completed_quantity
                  FROM commission_parameter_selection cps
                  JOIN commission_detection_selection cds ON cds.id=cps.detection_selection_id
                  JOIN commission c ON c.id=cds.commission_id
                  JOIN commission_sample_group g ON g.id=cps.sample_group_id
                  WHERE c.project_id=pl.project_id AND cps.parameter_id=pl.parameter_id
                    AND EXISTS(SELECT 1 FROM test_task t WHERE t.selection_id=cps.id AND t.state='COMPLETED')
                ) done ON true
                WHERE pl.project_id=:project AND (:query='' OR lower(pl.project_name) LIKE :pattern
                  OR lower(pl.parameter_name) LIKE :pattern OR lower(COALESCE(pl.detection_method,'')) LIKE :pattern)
                ORDER BY pl.sort_order,pl.updated_at DESC,pl.id
                """).param("project", projectId).param("query", text(query)).param("pattern", like(query))
                .query((rs, row) -> new PlanView(rs.getObject("id", UUID.class),
                        rs.getObject("detection_project_revision_id", UUID.class), rs.getObject("parameter_id", UUID.class),
                        rs.getString("category_name"), rs.getString("project_name"), rs.getString("parameter_name"),
                        decimal(rs.getBigDecimal("planned_quantity")), rs.getString("unit"),
                        rs.getString("binding_parameter"), rs.getString("detection_method"),
                        rs.getObject("planned_completion_on", LocalDate.class), decimal(rs.getBigDecimal("unit_price")),
                        decimal(rs.getBigDecimal("total_price")), decimal(rs.getBigDecimal("completed_quantity")),
                        decimal(rs.getBigDecimal("completion_percent")), decimal(rs.getBigDecimal("completed_contract_price")),
                        rs.getString("lag_status"), rs.getBoolean("active"), rs.getInt("sort_order"),
                        rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public PlanView savePlan(UUID projectId, UUID planId, PlanWrite body) {
        information(projectId);
        validatePlan(body);
        validatePlanBinding(body.detectionProjectRevisionId(), body.parameterId());
        BigDecimal quantity = positive(body.plannedQuantity(), "计划量");
        BigDecimal price = nonNegative(body.unitPrice(), "单价", 2);
        UUID id = planId == null ? UUID.randomUUID() : planId;
        if (planId == null) {
            jdbc.sql("""
                    INSERT INTO project_detection_plan(id,project_id,detection_project_revision_id,parameter_id,
                      category_name,project_name,parameter_name,planned_quantity,unit,binding_parameter,detection_method,
                      planned_completion_on,unit_price,active,sort_order,created_by)
                    VALUES (:id,:project,:revision,:parameter,:category,:projectName,:parameterName,:quantity,:unit,
                      :binding,:method,:plannedOn,:price,:active,:sortOrder,:actor)
                    """).param("id", id).param("project", projectId).param("revision", body.detectionProjectRevisionId())
                    .param("parameter", body.parameterId()).param("category", nullable(body.categoryName()))
                    .param("projectName", body.projectName().trim()).param("parameterName", body.parameterName().trim())
                    .param("quantity", quantity).param("unit", body.unit().trim())
                    .param("binding", nullable(body.bindingParameter())).param("method", nullable(body.detectionMethod()))
                    .param("plannedOn", body.plannedCompletionOn()).param("price", price).param("active", body.active())
                    .param("sortOrder", body.sortOrder()).param("actor", actor.require().id()).update();
            PlanView created = plan(projectId, id);
            audit.record("PROJECT_PLAN_CREATED", "PROJECT", projectId, null, created, null, null);
            return created;
        }
        PlanView before = plan(projectId, id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新检测计划必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE project_detection_plan SET detection_project_revision_id=:revision,parameter_id=:parameter,
                  category_name=:category,project_name=:projectName,parameter_name=:parameterName,
                  planned_quantity=:quantity,unit=:unit,binding_parameter=:binding,detection_method=:method,
                  planned_completion_on=:plannedOn,unit_price=:price,active=:active,sort_order=:sortOrder,
                  version=version+1,updated_at=now()
                WHERE id=:id AND project_id=:project AND version=:version
                """).param("revision", body.detectionProjectRevisionId()).param("parameter", body.parameterId())
                .param("category", nullable(body.categoryName())).param("projectName", body.projectName().trim())
                .param("parameterName", body.parameterName().trim()).param("quantity", quantity)
                .param("unit", body.unit().trim()).param("binding", nullable(body.bindingParameter()))
                .param("method", nullable(body.detectionMethod())).param("plannedOn", body.plannedCompletionOn())
                .param("price", price).param("active", body.active()).param("sortOrder", body.sortOrder())
                .param("id", id).param("project", projectId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测计划已被更新", plan(projectId, id).version());
        PlanView after = plan(projectId, id);
        audit.record("PROJECT_PLAN_UPDATED", "PROJECT", projectId, before, after, null, null);
        return after;
    }

    public List<ProgressMarkView> progressMarks(UUID projectId) {
        information(projectId);
        return jdbc.sql("""
                SELECT m.id,m.progress_percent,m.progress_label,m.occurred_on,m.comment,u.display_name,m.created_at
                FROM project_progress_mark m JOIN app_user u ON u.id=m.created_by
                WHERE m.project_id=:project ORDER BY m.occurred_on DESC,m.created_at DESC,m.id DESC
                """).param("project", projectId).query((rs, row) -> new ProgressMarkView(
                        rs.getObject("id", UUID.class), decimal(rs.getBigDecimal("progress_percent")),
                        rs.getString("progress_label"), rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("comment"), rs.getString("display_name"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public ProgressMarkView addProgressMark(UUID projectId, ProgressMarkWrite body) {
        Information before = information(projectId);
        if (before.version() != body.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT",
                "工程进度已被其他用户更新", before.version());
        BigDecimal progress = percentage(body.progressPercent(), "工程进度");
        if (body.progressLabel() == null || body.progressLabel().isBlank() || body.occurredOn() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_PROGRESS_REQUIRED", "进度说明和经办日期不能为空");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO project_progress_mark(id,project_id,progress_percent,progress_label,occurred_on,comment,created_by)
                VALUES (:id,:project,:progress,:label,:occurredOn,:comment,:actor)
                """).param("id", id).param("project", projectId).param("progress", progress)
                .param("label", body.progressLabel().trim()).param("occurredOn", body.occurredOn())
                .param("comment", nullable(body.comment())).param("actor", actor.require().id()).update();
        int changed = jdbc.sql("""
                UPDATE project SET progress_percent=:progress,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """)
                .param("progress", progress).param("id", projectId)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "工程进度已被其他用户更新",
                information(projectId).version());
        ProgressMarkView created = progressMarks(projectId).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        audit.record("PROJECT_PROGRESS_MARKED", "PROJECT", projectId, before, created, null, body.comment());
        return created;
    }

    public List<PartView> parts(UUID projectId) {
        information(projectId);
        return jdbc.sql("""
                SELECT id,parent_id,code,name,sort_order,active,version,updated_at
                FROM project_structure_part WHERE project_id=:project
                ORDER BY parent_id NULLS FIRST,sort_order,name,id
                """).param("project", projectId).query((rs, row) -> new PartView(
                        rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public PartView savePart(UUID projectId, UUID partId, PartWrite body) {
        information(projectId);
        if (body.code() == null || body.code().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_PART_REQUIRED", "单体部位代码和名称不能为空");
        }
        validatePartParent(projectId, partId, body.parentId());
        UUID id = partId == null ? UUID.randomUUID() : partId;
        if (partId == null) {
            jdbc.sql("""
                    INSERT INTO project_structure_part(id,project_id,parent_id,code,name,sort_order,active)
                    VALUES (:id,:project,:parent,:code,:name,:sortOrder,:active)
                    """).param("id", id).param("project", projectId).param("parent", body.parentId())
                    .param("code", body.code().trim()).param("name", body.name().trim())
                    .param("sortOrder", body.sortOrder()).param("active", body.active()).update();
            PartView created = part(projectId, id);
            audit.record("PROJECT_PART_CREATED", "PROJECT", projectId, null, created, null, null);
            return created;
        }
        PartView before = part(projectId, id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新单体部位必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE project_structure_part SET parent_id=:parent,code=:code,name=:name,sort_order=:sortOrder,
                  active=:active,version=version+1,updated_at=now()
                WHERE id=:id AND project_id=:project AND version=:version
                """).param("parent", body.parentId()).param("code", body.code().trim())
                .param("name", body.name().trim()).param("sortOrder", body.sortOrder())
                .param("active", body.active()).param("id", id).param("project", projectId)
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "单体部位已被更新", part(projectId, id).version());
        PartView after = part(projectId, id);
        audit.record("PROJECT_PART_UPDATED", "PROJECT", projectId, before, after, null, null);
        return after;
    }

    private Information information(UUID id) {
        AuthenticatedUser user = actor.require();
        return jdbc.sql("""
                SELECT p.id,p.number,p.name,p.customer_id,p.location,p.pile_serial_number,p.project_type,p.project_grade,
                  p.regulatory_organization,p.regulatory_project_code,p.regulatory_project_name,p.regulatory_linked,
                  p.entruster_name,p.entruster_phone,p.settlement_contact_name,p.settlement_contact_phone,
                  p.sampling_unit_name,p.sampler_name,p.sampler_phone,p.sampler_certificate_number,p.province,p.city,
                  p.district,p.detailed_address,p.section_number,p.region_code,p.default_detection_category,p.remark,
                  p.owner_name,p.owner_report_access,p.supervision_name,p.witness_name,p.witness_phone,
                  p.supervision_report_access,p.construction_name,p.construction_report_access,p.survey_name,p.design_name,
                  p.general_contractor_name,p.customer_report_access,p.cma_seal_policy,p.qualification_seal_policy,
                  p.electronic_seal_policy,p.customer_print_allowed,p.customer_print_limit,p.settlement_method,
                  p.project_team_id,p.report_print_restricted,p.external_contract_number,p.status,p.progress_percent,
                  p.handled_on,p.latitude,p.longitude,p.version,p.updated_at
                FROM project p WHERE p.id=:id AND p.organization_id=:organization
                """ + scopeClause()).param("id", id).param("organization", user.organizationId())
                .param("scope", user.dataScope()).param("actor", user.id())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .query((rs, row) -> new Information(
                        rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"),
                        rs.getObject("customer_id", UUID.class), rs.getString("location"), rs.getString("pile_serial_number"),
                        rs.getString("project_type"), rs.getString("project_grade"), rs.getString("regulatory_organization"),
                        rs.getString("regulatory_project_code"), rs.getString("regulatory_project_name"),
                        rs.getBoolean("regulatory_linked"), rs.getString("entruster_name"), rs.getString("entruster_phone"),
                        rs.getString("settlement_contact_name"), rs.getString("settlement_contact_phone"),
                        rs.getString("sampling_unit_name"), rs.getString("sampler_name"), rs.getString("sampler_phone"),
                        rs.getString("sampler_certificate_number"), rs.getString("province"), rs.getString("city"),
                        rs.getString("district"), rs.getString("detailed_address"), rs.getString("section_number"),
                        rs.getString("region_code"), rs.getString("default_detection_category"), rs.getString("remark"),
                        rs.getString("owner_name"), rs.getBoolean("owner_report_access"), rs.getString("supervision_name"),
                        rs.getString("witness_name"), rs.getString("witness_phone"), rs.getBoolean("supervision_report_access"),
                        rs.getString("construction_name"), rs.getBoolean("construction_report_access"), rs.getString("survey_name"),
                        rs.getString("design_name"), rs.getString("general_contractor_name"),
                        rs.getBoolean("customer_report_access"), rs.getString("cma_seal_policy"),
                        rs.getString("qualification_seal_policy"), rs.getString("electronic_seal_policy"),
                        rs.getBoolean("customer_print_allowed"), rs.getObject("customer_print_limit", Integer.class),
                        rs.getString("settlement_method"), rs.getObject("project_team_id", UUID.class),
                        rs.getBoolean("report_print_restricted"), rs.getString("external_contract_number"),
                        rs.getString("status"), decimal(rs.getBigDecimal("progress_percent")),
                        rs.getObject("handled_on", LocalDate.class), decimal(rs.getBigDecimal("latitude")),
                        decimal(rs.getBigDecimal("longitude")), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "工程不存在"));
    }

    private List<PartyView> parties(UUID projectId) {
        return jdbc.sql("""
                SELECT id,party_role,customer_id,name_snapshot,contact_name,contact_phone,certificate_number,
                  manual_entry,report_access,version FROM project_party WHERE project_id=:project ORDER BY party_role
                """).param("project", projectId).query((rs, row) -> new PartyView(
                        rs.getObject("id", UUID.class), rs.getString("party_role"), rs.getObject("customer_id", UUID.class),
                        rs.getString("name_snapshot"), rs.getString("contact_name"), rs.getString("contact_phone"),
                        rs.getString("certificate_number"), rs.getBoolean("manual_entry"), rs.getBoolean("report_access"),
                        rs.getLong("version"))).list();
    }

    private void saveParties(UUID projectId, List<PartyWrite> requested) {
        jdbc.sql("DELETE FROM project_party WHERE project_id=:project").param("project", projectId).update();
        if (requested == null) return;
        for (PartyWrite party : requested) {
            if (party == null || blank(party.partyRole()) || blank(party.name())) continue;
            String role = upper(party.partyRole());
            if (!List.of("ENTRUSTING","SAMPLING","OWNER","SUPERVISION","CONSTRUCTION","SURVEY","DESIGN","GENERAL_CONTRACTOR").contains(role)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_PARTY_ROLE_INVALID", "参建单位角色无效");
            }
            if (party.customerId() != null) requireCustomer(party.customerId());
            jdbc.sql("""
                    INSERT INTO project_party(id,project_id,party_role,customer_id,name_snapshot,contact_name,
                      contact_phone,certificate_number,manual_entry,report_access)
                    VALUES (:id,:project,:role,:customer,:name,:contact,:phone,:certificate,:manual,:reportAccess)
                    """).param("id", UUID.randomUUID()).param("project", projectId).param("role", role)
                    .param("customer", party.customerId()).param("name", party.name().trim())
                    .param("contact", nullable(party.contactName())).param("phone", nullable(party.contactPhone()))
                    .param("certificate", nullable(party.certificateNumber())).param("manual", party.manualEntry())
                    .param("reportAccess", party.reportAccess()).update();
        }
    }

    private int executeWrite(String sql, UUID id, Write body, Long version, boolean create) {
        var statement = jdbc.sql(sql).param("id", id).param("organization", actor.require().organizationId())
                .param("customer", body.customerId())
                .param("number", body.number().trim()).param("name", body.name().trim())
                .param("location", nullable(body.location())).param("pile", nullable(body.pileSerialNumber()))
                .param("projectType", nullable(body.projectType())).param("projectGrade", nullable(body.projectGrade()))
                .param("regulatory", nullable(body.regulatoryOrganization())).param("regulatoryCode", nullable(body.regulatoryProjectCode()))
                .param("regulatoryName", nullable(body.regulatoryProjectName())).param("linked", body.regulatoryLinked())
                .param("entruster", nullable(body.entrusterName())).param("entrusterPhone", nullable(body.entrusterPhone()))
                .param("settlement", nullable(body.settlementContactName())).param("settlementPhone", nullable(body.settlementContactPhone()))
                .param("samplingUnit", nullable(body.samplingUnitName())).param("sampler", nullable(body.samplerName()))
                .param("samplerPhone", nullable(body.samplerPhone())).param("samplerCertificate", nullable(body.samplerCertificateNumber()))
                .param("province", nullable(body.province())).param("city", nullable(body.city())).param("district", nullable(body.district()))
                .param("address", nullable(body.detailedAddress())).param("section", nullable(body.sectionNumber()))
                .param("region", nullable(body.regionCode())).param("defaultCategory", nullable(body.defaultDetectionCategory()))
                .param("remark", nullable(body.remark())).param("owner", body.ownerName().trim())
                .param("ownerAccess", body.ownerReportAccess()).param("supervision", body.supervisionName().trim())
                .param("witness", nullable(body.witnessName())).param("witnessPhone", nullable(body.witnessPhone()))
                .param("supervisionAccess", body.supervisionReportAccess()).param("construction", body.constructionName().trim())
                .param("constructionAccess", body.constructionReportAccess()).param("survey", nullable(body.surveyName()))
                .param("design", nullable(body.designName())).param("generalContractor", nullable(body.generalContractorName()))
                .param("customerAccess", body.customerReportAccess()).param("cma", upper(body.cmaSealPolicy()))
                .param("qualification", upper(body.qualificationSealPolicy())).param("electronic", upper(body.electronicSealPolicy()))
                .param("customerPrint", body.customerPrintAllowed()).param("printLimit", body.customerPrintLimit())
                .param("settlementMethod", nullable(body.settlementMethod())).param("team", body.projectTeamId())
                .param("restricted", body.reportPrintRestricted()).param("contract", nullable(body.externalContractNumber()))
                .param("handledOn", body.handledOn() == null ? LocalDate.now() : body.handledOn())
                .param("latitude", optionalDecimal(body.latitude(), "纬度", 7)).param("longitude", optionalDecimal(body.longitude(), "经度", 7));
        if (create) statement.param("createdBy", actor.require().id());
        else statement.param("version", version);
        return statement.update();
    }

    private String insertSql() {
        return """
                INSERT INTO project(id,organization_id,customer_id,number,name,location,pile_serial_number,project_type,
                  project_grade,regulatory_organization,regulatory_project_code,regulatory_project_name,regulatory_linked,
                  entruster_name,entruster_phone,settlement_contact_name,settlement_contact_phone,sampling_unit_name,
                  sampler_name,sampler_phone,sampler_certificate_number,province,city,district,detailed_address,
                  section_number,region_code,default_detection_category,remark,owner_name,owner_report_access,
                  supervision_name,witness_name,witness_phone,supervision_report_access,construction_name,
                  construction_report_access,survey_name,design_name,general_contractor_name,customer_report_access,
                  cma_seal_policy,qualification_seal_policy,electronic_seal_policy,customer_print_allowed,
                  customer_print_limit,settlement_method,project_team_id,report_print_restricted,external_contract_number,
                  status,handled_on,latitude,longitude,created_by)
                VALUES (:id,:organization,:customer,:number,:name,:location,:pile,:projectType,:projectGrade,
                  :regulatory,:regulatoryCode,:regulatoryName,:linked,:entruster,:entrusterPhone,:settlement,
                  :settlementPhone,:samplingUnit,:sampler,:samplerPhone,:samplerCertificate,:province,:city,:district,
                  :address,:section,:region,:defaultCategory,:remark,:owner,:ownerAccess,:supervision,:witness,
                  :witnessPhone,:supervisionAccess,:construction,:constructionAccess,:survey,:design,:generalContractor,
                  :customerAccess,:cma,:qualification,:electronic,:customerPrint,:printLimit,:settlementMethod,:team,
                  :restricted,:contract,'DRAFT',:handledOn,:latitude,:longitude,:createdBy)
                """;
    }

    private String updateSql() {
        return """
                UPDATE project SET customer_id=:customer,number=:number,name=:name,location=:location,
                  pile_serial_number=:pile,project_type=:projectType,project_grade=:projectGrade,
                  regulatory_organization=:regulatory,regulatory_project_code=:regulatoryCode,
                  regulatory_project_name=:regulatoryName,regulatory_linked=:linked,entruster_name=:entruster,
                  entruster_phone=:entrusterPhone,settlement_contact_name=:settlement,
                  settlement_contact_phone=:settlementPhone,sampling_unit_name=:samplingUnit,sampler_name=:sampler,
                  sampler_phone=:samplerPhone,sampler_certificate_number=:samplerCertificate,province=:province,
                  city=:city,district=:district,detailed_address=:address,section_number=:section,region_code=:region,
                  default_detection_category=:defaultCategory,remark=:remark,owner_name=:owner,
                  owner_report_access=:ownerAccess,supervision_name=:supervision,witness_name=:witness,
                  witness_phone=:witnessPhone,supervision_report_access=:supervisionAccess,
                  construction_name=:construction,construction_report_access=:constructionAccess,survey_name=:survey,
                  design_name=:design,general_contractor_name=:generalContractor,customer_report_access=:customerAccess,
                  cma_seal_policy=:cma,qualification_seal_policy=:qualification,electronic_seal_policy=:electronic,
                  customer_print_allowed=:customerPrint,customer_print_limit=:printLimit,
                  settlement_method=:settlementMethod,project_team_id=:team,report_print_restricted=:restricted,
                  external_contract_number=:contract,handled_on=:handledOn,latitude=:latitude,longitude=:longitude,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """;
    }

    private String baseSummarySelect() {
        return """
                SELECT p.id,p.number,p.pile_serial_number,p.name,p.customer_id,
                  CASE WHEN entrusting.manual_entry THEN entrusting.name_snapshot ELSE c.name END customer_name,p.owner_name,
                  p.supervision_name,p.construction_name,p.handled_on,p.progress_percent,p.status,p.project_team_id,
                  team.name project_team_name,p.regulatory_linked,p.external_contract_number,p.version,p.updated_at
                FROM project p JOIN customer c ON c.id=p.customer_id
                LEFT JOIN project_party entrusting ON entrusting.project_id=p.id AND entrusting.party_role='ENTRUSTING'
                LEFT JOIN project_team team ON team.id=p.project_team_id
                """;
    }

    private Summary mapSummary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("pile_serial_number"),
                rs.getString("name"), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("owner_name"), rs.getString("supervision_name"), rs.getString("construction_name"),
                rs.getObject("handled_on", LocalDate.class), decimal(rs.getBigDecimal("progress_percent")),
                rs.getString("status"), rs.getObject("project_team_id", UUID.class), rs.getString("project_team_name"),
                rs.getBoolean("regulatory_linked"), rs.getString("external_contract_number"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String scopeClause() {
        return """
                 AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))
                  OR (:scope='SELF' AND (p.created_by=:actor OR EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)))
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=p.created_by AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM commission co JOIN app_user u ON u.id=co.created_by
                      WHERE co.project_id=p.id AND u.department_id=:department))))
                 """;
    }

    private void validate(Write body, boolean creating) {
        if (blank(body.number()) || blank(body.name()) || body.customerId() == null) throw new ApiException(
                HttpStatus.BAD_REQUEST, "PROJECT_REQUIRED_FIELDS", "工程自编号、工程名称和委托单位不能为空");
        if (blank(body.ownerName()) || blank(body.supervisionName()) || blank(body.constructionName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_PARTIES_REQUIRED", "建设单位、监理单位和施工单位不能为空");
        }
        for (String policy : List.of(body.cmaSealPolicy(), body.qualificationSealPolicy(), body.electronicSealPolicy())) {
            if (!List.of("FOLLOW_PROJECT","ALWAYS","NEVER").contains(upper(policy))) throw new ApiException(
                    HttpStatus.BAD_REQUEST, "PROJECT_SEAL_POLICY_INVALID", "印章策略无效");
        }
        if (body.customerPrintLimit() != null && body.customerPrintLimit() < 0) throw new ApiException(
                HttpStatus.BAD_REQUEST, "PROJECT_PRINT_LIMIT_INVALID", "报告打印次数不能小于零");
        validateOption("PROJECT_TYPE", body.projectType(), "工程类型");
        validateOption("PROJECT_GRADE", body.projectGrade(), "工程等级");
        validateOption("DEFAULT_DETECTION_CATEGORY", body.defaultDetectionCategory(), "默认检测类别");
        divisions.validate(body.province(), body.city(), body.district());
    }

    private List<String> optionValues(String code) {
        return jdbc.sql("""
                SELECT option.value
                FROM configuration_version configuration
                CROSS JOIN LATERAL jsonb_array_elements_text(configuration.content_json->'values')
                  WITH ORDINALITY AS option(value, position)
                WHERE configuration.id=(
                  SELECT id FROM configuration_version
                  WHERE configuration_type='DICTIONARY' AND code=:code AND state='PUBLISHED'
                  ORDER BY version_number DESC,id DESC LIMIT 1)
                ORDER BY option.position
                """).param("code", code).query(String.class).list();
    }

    private void validateOption(String code, String value, String label) {
        if (blank(value)) return;
        if (!optionValues(code).contains(value.trim())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECT_OPTION_INVALID",
                    label + "不属于当前已发布数据字典");
        }
    }

    private void validatePlan(PlanWrite body) {
        if (blank(body.projectName()) || blank(body.parameterName()) || blank(body.unit())) throw new ApiException(
                HttpStatus.BAD_REQUEST, "PROJECT_PLAN_REQUIRED", "检测项目、检测参数和单位不能为空");
    }

    private void validatePlanBinding(UUID revisionId, UUID parameterId) {
        if (parameterId == null) return;
        long count = jdbc.sql("""
                SELECT count(*) FROM test_parameter p WHERE p.id=:parameter AND p.project_revision_id=:revision
                  AND p.status='PUBLISHED' AND p.enabled
                """).param("parameter", parameterId).param("revision", revisionId).query(Long.class).single();
        if (count == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROJECT_PLAN_PARAMETER_INVALID", "绑定检测参数未发布、已停用或不属于所选检测项目");
    }

    private void validatePartParent(UUID projectId, UUID partId, UUID parentId) {
        if (parentId == null) return;
        PartView parent = part(projectId, parentId);
        if (!parent.active()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROJECT_PART_PARENT_INACTIVE", "不能移动到已停用的父节点");
        if (partId != null) {
            if (partId.equals(parentId)) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "PROJECT_PART_CYCLE", "单体部位不能作为自己的父节点");
            long cycle = jdbc.sql("""
                    WITH RECURSIVE descendants AS (
                      SELECT id FROM project_structure_part WHERE parent_id=:id
                      UNION ALL SELECT p.id FROM project_structure_part p JOIN descendants d ON p.parent_id=d.id
                    ) SELECT count(*) FROM descendants WHERE id=:parent
                    """).param("id", partId).param("parent", parentId).query(Long.class).single();
            if (cycle > 0) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "PROJECT_PART_CYCLE", "移动后会形成循环层级");
        }
    }

    private PlanView plan(UUID projectId, UUID id) {
        return plans(projectId, "").stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PROJECT_PLAN_NOT_FOUND", "检测计划不存在"));
    }

    private PartView part(UUID projectId, UUID id) {
        return parts(projectId).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PROJECT_PART_NOT_FOUND", "单体部位不存在"));
    }

    private void requireCustomer(UUID customerId) {
        long count = jdbc.sql("SELECT count(*) FROM customer WHERE id=:id AND organization_id=:organization AND active")
                .param("id", customerId).param("organization", actor.require().organizationId()).query(Long.class).single();
        if (count == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROJECT_CUSTOMER_INVALID", "委托单位不存在、已停用或不属于当前机构");
    }

    private void requireTeam(UUID teamId) {
        if (teamId == null) return;
        long count = jdbc.sql("SELECT count(*) FROM project_team WHERE id=:id AND organization_id=:organization AND state='ACTIVE'")
                .param("id", teamId).param("organization", actor.require().organizationId()).query(Long.class).single();
        if (count == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROJECT_TEAM_INVALID", "归属项目组不存在或已停用");
    }

    private void ensureNumber(String number, UUID ignoredId) {
        long count = jdbc.sql("""
                SELECT count(*) FROM project WHERE organization_id=:organization AND lower(number)=lower(:number)
                  AND (CAST(:ignored AS uuid) IS NULL OR id<>CAST(:ignored AS uuid))
                """).param("organization", actor.require().organizationId()).param("number", number.trim())
                .param("ignored", ignoredId).query(Long.class).single();
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "PROJECT_NUMBER_EXISTS", "工程自编号已存在");
    }

    private List<String> allowedActions(String status) {
        return ProjectState.valueOf(status).allowedActions();
    }

    private BigDecimal positive(String value, String label) {
        BigDecimal number = decimalValue(value, label, 6);
        if (number.signum() <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECIMAL", label + "必须大于零");
        return number;
    }

    private BigDecimal nonNegative(String value, String label, int scale) {
        BigDecimal number = decimalValue(value == null || value.isBlank() ? "0" : value, label, scale);
        if (number.signum() < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECIMAL", label + "不能小于零");
        return number;
    }

    private BigDecimal percentage(String value, String label) {
        BigDecimal number = decimalValue(value, label, 2);
        if (number.signum() < 0 || number.compareTo(new BigDecimal("100")) > 0) throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_PERCENTAGE", label + "必须在 0 到 100 之间");
        return number;
    }

    private BigDecimal optionalDecimal(String value, String label, int scale) {
        return blank(value) ? null : decimalValue(value, label, scale);
    }

    private BigDecimal decimalValue(String value, String label, int scale) {
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.scale() > scale) throw new NumberFormatException();
            return number;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECIMAL", label + "格式无效");
        }
    }

    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String upper(String value) { return text(value).toUpperCase(); }
    private String upperCsv(String value) { return text(value).replace(" ", "").toUpperCase(); }
    private String like(String value) { return "%" + text(value).toLowerCase() + "%"; }
    private String nullable(String value) { return blank(value) ? null : value.trim(); }
}
