package com.lanchuan.lims.report;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ConcreteStrengthAssessmentService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;
    private static final List<String> FORMAL_RULE_BLOCKERS = List.of(
            "未安装经业务签字的混凝土强度评定规则",
            "验收系数、验收常数和判定公式必须来自受控正式资料，系统不会推测生成");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final DataScopeService access;
    private final AuditService audit;

    public ConcreteStrengthAssessmentService(JdbcClient jdbc, CurrentActor actor,
                                             DataScopeService access, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.access = access;
        this.audit = audit;
    }

    public record Filters(LocalDate periodFrom, LocalDate periodTo, UUID detectionProjectRevisionId,
                          String designRequirement, String projectNumber, String projectName,
                          String structurePart, String state, String cursor, int limit) {}
    public record Write(UUID projectId, UUID structurePartId, UUID detectionProjectRevisionId,
                        UUID parameterId, UUID standardId, String designRequirement,
                        LocalDate periodStart, LocalDate periodEnd, String concreteQuantity,
                        String standardDeviationAverageLevel, String remark, Long expectedVersion) {}
    public record ResultSnapshot(UUID testRecordId, String reportNumber, LocalDate constructionOn,
                                 String strengthValue, int sortOrder) {}
    public record View(UUID id, UUID projectId, String projectNumber, String projectName,
                       UUID structurePartId, String structurePart, UUID detectionProjectRevisionId,
                       String detectionProject, UUID parameterId, String parameterName,
                       UUID standardId, String standardCode, String standardName,
                       String designRequirement, LocalDate periodStart, LocalDate periodEnd,
                       String concreteQuantity, String standardDeviationAverageLevel,
                       Integer specimenGroupCount, String averageStrength, String standardDeviation,
                       String minimumStrength, String acceptanceCoefficient, String acceptanceConstant,
                       String statisticsText, String standardText, String calculationText,
                       String conclusion, String remark, String ruleReadiness, String state,
                       long version, UUID testedBy, String testerName, UUID reviewedBy,
                       String reviewerName, UUID approvedBy, String approverName,
                       OffsetDateTime calculatedAt, OffsetDateTime reviewedAt,
                       OffsetDateTime approvedAt, OffsetDateTime updatedAt,
                       List<ResultSnapshot> results, List<String> blockers, List<String> allowedActions) {}
    public record ProjectOption(UUID id, String number, String name) {}
    public record StructurePartOption(UUID id, String code, String name) {}
    public record StandardOption(UUID id, String code, String name) {}
    public record ParameterOption(UUID id, String name, UUID projectRevisionId,
                                  String detectionProject, List<StandardOption> standards) {}
    public record Options(List<ProjectOption> projects, List<StructurePartOption> structureParts,
                          List<ParameterOption> parameters, List<String> designRequirements) {}

    public CursorPage<View> list(Filters filters) {
        AuthenticatedUser user = actor.require();
        validateRange(filters.periodFrom(), filters.periodTo(), "验收时段");
        String state = upper(filters.state());
        if (!state.isEmpty() && !Set.of("DRAFT", "CALCULATED", "IN_REVIEW", "REVIEWED", "APPROVED", "RETIRED").contains(state)) {
            throw invalid("CONCRETE_ASSESSMENT_STATE_INVALID", "评定状态无效");
        }
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String conditions = conditions();
        var statement = jdbc.sql(select() + " WHERE " + projectScope() + conditions + """
                AND (CAST(:cursorTime AS timestamptz) IS NULL OR (assessment.updated_at,assessment.id)<
                    (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY assessment.updated_at DESC,assessment.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, filters, state, cursor).param("fetchLimit", limit + 1);
        List<View> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + from() + " WHERE " + projectScope() + conditions);
        bind(count, user, filters, state, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(select() + " WHERE assessment.id=:id AND " + projectScope()).param("id", id);
        bindActor(statement, user);
        View row = statement.query(this::map).optional().orElseThrow(() ->
                ApiException.notFound("CONCRETE_ASSESSMENT_NOT_FOUND", "强度评定不存在或不在当前数据范围内"));
        return withResults(row);
    }

    public Options options(String query, UUID projectId) {
        AuthenticatedUser user = actor.require();
        String q = clean(query, 100, "查询内容");
        var projectsStatement = jdbc.sql("""
                SELECT project.id,project.number,project.name FROM project WHERE %s
                  AND project.status IN ('DRAFT','ACTIVE','SUSPENDED','COMPLETED')
                  AND (:q='' OR project.number ILIKE :pattern OR project.name ILIKE :pattern)
                ORDER BY project.updated_at DESC,project.id DESC LIMIT 100
                """.formatted(projectScope())).param("q", q).param("pattern", pattern(q));
        bindActor(projectsStatement, user);
        List<ProjectOption> projects = projectsStatement.query((rs, row) -> new ProjectOption(
                rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"))).list();
        List<StructurePartOption> parts = List.of();
        List<String> requirements = List.of();
        if (projectId != null) {
            access.requireProject(projectId);
            parts = jdbc.sql("""
                    SELECT id,code,name FROM project_structure_part
                    WHERE project_id=:project AND active ORDER BY sort_order,name,id LIMIT 500
                    """).param("project", projectId).query((rs, row) -> new StructurePartOption(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"))).list();
            requirements = jdbc.sql("""
                    SELECT DISTINCT strength_grade FROM sample sample JOIN commission ON commission.id=sample.commission_id
                    WHERE commission.project_id=:project AND strength_grade IS NOT NULL AND btrim(strength_grade)<>''
                    ORDER BY strength_grade LIMIT 100
                    """).param("project", projectId).query(String.class).list();
        }
        List<ParameterRow> rows = jdbc.sql("""
                SELECT parameter.id,parameter.name,revision.id revision_id,node.name project_name,
                  standard.id standard_id,standard.code standard_code,standard.name standard_name
                FROM test_parameter parameter JOIN detection_project_revision revision
                  ON revision.id=parameter.project_revision_id
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                JOIN test_parameter_standard relation ON relation.parameter_id=parameter.id
                JOIN detection_standard standard ON standard.id=relation.standard_id
                WHERE parameter.status='PUBLISHED' AND parameter.enabled AND revision.status='PUBLISHED'
                  AND node.status='ACTIVE' AND standard.state='PUBLISHED' AND standard.status='CURRENT'
                  AND (:q='' OR parameter.name ILIKE :pattern OR node.name ILIKE :pattern
                    OR standard.code ILIKE :pattern OR standard.name ILIKE :pattern)
                ORDER BY parameter.name,node.name,relation.sort_order,standard.code LIMIT 500
                """).param("q", q).param("pattern", pattern(q)).query((rs, row) -> new ParameterRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("revision_id", UUID.class),
                rs.getString("project_name"), rs.getObject("standard_id", UUID.class),
                rs.getString("standard_code"), rs.getString("standard_name"))).list();
        Map<UUID, ParameterAccumulator> grouped = new LinkedHashMap<>();
        for (ParameterRow row : rows) {
            ParameterAccumulator accumulator = grouped.computeIfAbsent(row.id(), ignored ->
                    new ParameterAccumulator(row.id(), row.name(), row.revisionId(), row.projectName(), new ArrayList<>()));
            accumulator.standards().add(new StandardOption(row.standardId(), row.standardCode(), row.standardName()));
        }
        List<ParameterOption> parameters = grouped.values().stream().limit(100).map(value ->
                new ParameterOption(value.id(), value.name(), value.revisionId(), value.projectName(),
                        List.copyOf(value.standards()))).toList();
        return new Options(projects, parts, parameters, requirements);
    }

    @Transactional
    public View create(Write body) {
        requirePermission("MODULE_WRITE");
        Validated validated = validate(body, false);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO concrete_strength_assessment(id,organization_id,project_id,project_number_snapshot,
                  project_name_snapshot,structure_part_id,structure_part_snapshot,detection_project_revision_id,
                  detection_project_snapshot,parameter_id,parameter_snapshot,standard_id,standard_code_snapshot,
                  standard_name_snapshot,design_requirement,period_start,period_end,concrete_quantity,
                  standard_deviation_average_level,remark,created_by)
                VALUES (:id,:organization,:project,:projectNumber,:projectName,:part,:partName,:revision,
                  :detectionProject,:parameter,:parameterName,:standard,:standardCode,:standardName,
                  :requirement,:periodStart,:periodEnd,:quantity,:deviationLevel,:remark,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("project", body.projectId())
                .param("projectNumber", validated.references().projectNumber())
                .param("projectName", validated.references().projectName()).param("part", body.structurePartId())
                .param("partName", validated.references().partName()).param("revision", body.detectionProjectRevisionId())
                .param("detectionProject", validated.references().detectionProject())
                .param("parameter", body.parameterId()).param("parameterName", validated.references().parameterName())
                .param("standard", body.standardId()).param("standardCode", validated.references().standardCode())
                .param("standardName", validated.references().standardName())
                .param("requirement", validated.requirement()).param("periodStart", body.periodStart())
                .param("periodEnd", body.periodEnd()).param("quantity", validated.quantity())
                .param("deviationLevel", validated.deviationLevel()).param("remark", validated.remark())
                .param("actor", user.id()).update();
        View created = require(id);
        audit.record("CONCRETE_ASSESSMENT_CREATED", "CONCRETE_STRENGTH_ASSESSMENT", id,
                null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Write body) {
        requirePermission("MODULE_WRITE");
        View before = require(id);
        if (!before.state().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "CONCRETE_ASSESSMENT_IMMUTABLE", "已计算或已审批评定不能原地修改，请先按状态退回");
        requireVersion(body.expectedVersion(), before.version());
        Validated validated = validate(body, true);
        int changed = jdbc.sql("""
                UPDATE concrete_strength_assessment SET project_id=:project,project_number_snapshot=:projectNumber,
                  project_name_snapshot=:projectName,structure_part_id=:part,structure_part_snapshot=:partName,
                  detection_project_revision_id=:revision,detection_project_snapshot=:detectionProject,
                  parameter_id=:parameter,parameter_snapshot=:parameterName,standard_id=:standard,
                  standard_code_snapshot=:standardCode,standard_name_snapshot=:standardName,
                  design_requirement=:requirement,period_start=:periodStart,period_end=:periodEnd,
                  concrete_quantity=:quantity,standard_deviation_average_level=:deviationLevel,
                  remark=:remark,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND version=:version
                """).param("project", body.projectId()).param("projectNumber", validated.references().projectNumber())
                .param("projectName", validated.references().projectName()).param("part", body.structurePartId())
                .param("partName", validated.references().partName()).param("revision", body.detectionProjectRevisionId())
                .param("detectionProject", validated.references().detectionProject()).param("parameter", body.parameterId())
                .param("parameterName", validated.references().parameterName()).param("standard", body.standardId())
                .param("standardCode", validated.references().standardCode())
                .param("standardName", validated.references().standardName()).param("requirement", validated.requirement())
                .param("periodStart", body.periodStart()).param("periodEnd", body.periodEnd())
                .param("quantity", validated.quantity()).param("deviationLevel", validated.deviationLevel())
                .param("remark", validated.remark()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw conflict(id);
        View after = require(id);
        audit.record("CONCRETE_ASSESSMENT_UPDATED", "CONCRETE_STRENGTH_ASSESSMENT", id,
                before, after, null, null);
        return after;
    }

    @Transactional
    public View calculate(UUID id, long expectedVersion) {
        requirePermission("MODULE_WRITE");
        View before = require(id);
        requireVersion(expectedVersion, before.version());
        if (!before.state().equals("DRAFT")) throw invalidAction("CALCULATE");
        List<SourceResult> source = sourceResults(before);
        if (source.isEmpty()) throw ApiException.blocked("CONCRETE_ASSESSMENT_NO_RESULTS",
                "所选工程、构件、项目和时段内没有可用于统计的已确认强度结果", before.version(),
                List.of("需要状态为已确认的试验记录", "试验记录必须包含受控 calculatedResult 数值"));
        Statistics statistics = statistics(source.stream().map(SourceResult::value).toList());
        jdbc.sql("DELETE FROM concrete_strength_result_snapshot WHERE assessment_id=:id").param("id", id).update();
        for (int index = 0; index < source.size(); index++) {
            SourceResult row = source.get(index);
            jdbc.sql("""
                    INSERT INTO concrete_strength_result_snapshot(assessment_id,test_record_id,report_number,
                      construction_on,strength_value,sort_order)
                    VALUES (:assessment,:record,:report,:constructionOn,:value,:sort)
                    """).param("assessment", id).param("record", row.testRecordId())
                    .param("report", row.reportNumber()).param("constructionOn", row.constructionOn())
                    .param("value", row.value()).param("sort", index).update();
        }
        String statisticsText = "从 " + statistics.count() + " 组已确认记录生成描述性统计：平均值 "
                + plain(statistics.average()) + " MPa，样本标准差 " + plain(statistics.standardDeviation())
                + " MPa，最小值 " + plain(statistics.minimum()) + " MPa。";
        String standardText = before.standardCode() + " " + before.standardName()
                + "；当前仅冻结标准选择，正式评定规则尚未发布。";
        String calculationText = "已完成来源记录汇总和描述性统计；验收系数、常数及合格判定等待受控正式规则。";
        int changed = jdbc.sql("""
                UPDATE concrete_strength_assessment SET specimen_group_count=:count,average_strength=:average,
                  standard_deviation=:deviation,minimum_strength=:minimum,statistics_text=:statistics,
                  standard_text=:standardText,calculation_text=:calculation,state='CALCULATED',
                  tested_by=:actor,calculated_at=now(),version=version+1,updated_at=now()
                WHERE id=:id AND state='DRAFT' AND version=:version
                """).param("count", statistics.count()).param("average", statistics.average())
                .param("deviation", statistics.standardDeviation()).param("minimum", statistics.minimum())
                .param("statistics", statisticsText).param("standardText", standardText)
                .param("calculation", calculationText).param("actor", actor.require().id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw conflict(id);
        View after = require(id);
        audit.record("CONCRETE_ASSESSMENT_CALCULATED", "CONCRETE_STRENGTH_ASSESSMENT", id,
                before, after, "DESCRIPTIVE_STATISTICS", "冻结已确认试验结果快照；正式判定规则仍受控阻断");
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, String requestedAction, long expectedVersion,
                                       String reasonCode, String comment) {
        View before = require(id);
        requireVersion(expectedVersion, before.version());
        String action = upper(requestedAction);
        if (action.equals("CALCULATE")) {
            View calculated = calculate(id, expectedVersion);
            return new TransitionResult(calculated.state(), calculated.version(), calculated.allowedActions());
        } else if (action.equals("REOPEN")) {
            requirePermission("MODULE_WRITE");
            if (!before.state().equals("CALCULATED")) throw invalidAction(action);
            reopen(id, before.version());
        } else if (action.equals("SUBMIT_REVIEW")) {
            requirePermission("MODULE_WRITE");
            if (!before.state().equals("CALCULATED")) throw invalidAction(action);
            if (!before.ruleReadiness().equals("READY")) throw ApiException.blocked(
                    "CONCRETE_ASSESSMENT_RULE_NOT_READY", "正式评定规则尚未就绪，禁止提交审核",
                    before.version(), FORMAL_RULE_BLOCKERS);
            int changed = jdbc.sql("""
                    UPDATE concrete_strength_assessment SET state='IN_REVIEW',
                      version=version+1,updated_at=now() WHERE id=:id AND state='CALCULATED' AND version=:version
                    """).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else if (action.equals("REVIEW")) {
            requirePermission("REPORT_REVIEW");
            if (!before.state().equals("IN_REVIEW") || !before.ruleReadiness().equals("READY")) {
                throw invalidAction(action);
            }
            UUID currentActor = actor.require().id();
            if (repeatsDuty(currentActor, before.testedBy())) throw new ApiException(HttpStatus.CONFLICT,
                    "CONCRETE_ASSESSMENT_DUTY_CONFLICT", "试验人与审核人必须由不同账号承担");
            int changed = jdbc.sql("""
                    UPDATE concrete_strength_assessment SET state='REVIEWED',reviewed_by=:actor,reviewed_at=now(),
                      version=version+1,updated_at=now() WHERE id=:id AND state='IN_REVIEW' AND version=:version
                    """).param("actor", currentActor).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else if (action.equals("RETURN")) {
            requirePermission("REPORT_REVIEW");
            if (!Set.of("IN_REVIEW", "REVIEWED").contains(before.state())) throw invalidAction(action);
            String reason = clean(comment, 1000, "退回原因");
            if (reason.isEmpty()) throw invalid("CONCRETE_ASSESSMENT_RETURN_REASON_REQUIRED", "退回必须填写原因");
            int changed = jdbc.sql("""
                    UPDATE concrete_strength_assessment SET state='CALCULATED',reviewed_by=NULL,reviewed_at=NULL,
                      version=version+1,updated_at=now() WHERE id=:id AND state=:state AND version=:version
                    """).param("id", id).param("state", before.state()).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else if (action.equals("APPROVE")) {
            requirePermission("REPORT_ISSUE");
            if (!before.state().equals("REVIEWED") || !before.ruleReadiness().equals("READY")
                    || before.conclusion() == null || before.conclusion().isBlank()) throw invalidAction(action);
            UUID currentActor = actor.require().id();
            if (repeatsDuty(currentActor, before.testedBy(), before.reviewedBy())) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCRETE_ASSESSMENT_DUTY_CONFLICT",
                        "批准人不得与试验人或审核人为同一账号");
            }
            int changed = jdbc.sql("""
                    UPDATE concrete_strength_assessment SET state='APPROVED',approved_by=:actor,approved_at=now(),
                      version=version+1,updated_at=now() WHERE id=:id AND state='REVIEWED' AND version=:version
                    """).param("actor", currentActor).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else if (action.equals("RETIRE") || action.equals("DISCARD")) {
            requirePermission(action.equals("RETIRE") ? "REPORT_REVIEW" : "MODULE_WRITE");
            if (action.equals("DISCARD") && !before.state().equals("DRAFT")) throw invalidAction(action);
            if (action.equals("RETIRE") && !actions(before.state()).contains("RETIRE")) {
                throw invalidAction(action);
            }
            String reason = clean(comment, 1000, "停用原因");
            if (reason.isEmpty()) throw invalid("CONCRETE_ASSESSMENT_RETIRE_REASON_REQUIRED", "停用或作废必须填写原因");
            int changed = jdbc.sql("""
                    UPDATE concrete_strength_assessment SET state='RETIRED',retired_by=:actor,retired_at=now(),
                      retirement_reason=:reason,version=version+1,updated_at=now()
                    WHERE id=:id AND state=:state AND version=:version
                    """).param("actor", actor.require().id()).param("reason", reason).param("id", id)
                    .param("state", before.state()).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else throw invalidAction(action);
        View after = require(id);
        audit.record("CONCRETE_ASSESSMENT_" + action, "CONCRETE_STRENGTH_ASSESSMENT", id,
                before, after, reasonCode, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private void reopen(UUID id, long version) {
        int changed = jdbc.sql("""
                UPDATE concrete_strength_assessment SET state='DRAFT',specimen_group_count=NULL,
                  average_strength=NULL,standard_deviation=NULL,minimum_strength=NULL,
                  acceptance_coefficient=NULL,acceptance_constant=NULL,statistics_text=NULL,
                  standard_text=NULL,calculation_text=NULL,conclusion=NULL,tested_by=NULL,
                  reviewed_by=NULL,approved_by=NULL,calculated_at=NULL,reviewed_at=NULL,approved_at=NULL,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("id", id).param("version", version).update();
        if (changed == 0) throw conflict(id);
        jdbc.sql("DELETE FROM concrete_strength_result_snapshot WHERE assessment_id=:id").param("id", id).update();
    }

    private List<SourceResult> sourceResults(View assessment) {
        return jdbc.sql("""
                SELECT record.id,latest_report.number report_number,
                  COALESCE(sample.molding_date,commission.commissioned_on) construction_on,
                  (record.values_json->>'calculatedResult')::numeric strength_value
                FROM test_record record JOIN test_task task ON task.id=record.task_id
                JOIN commission ON commission.id=task.commission_id JOIN sample ON sample.id=task.sample_id
                LEFT JOIN LATERAL (
                  SELECT report.number FROM report WHERE report.commission_id=commission.id
                  ORDER BY report.updated_at DESC,report.id DESC LIMIT 1
                ) latest_report ON true
                WHERE commission.project_id=:project AND task.parameter_id=:parameter
                  AND record.state='CONFIRMED' AND record.result IN ('CONFORMING','NONCONFORMING')
                  AND COALESCE(sample.molding_date,commission.commissioned_on) BETWEEN :periodStart AND :periodEnd
                  AND sample.structure_part=:part
                  AND COALESCE(record.values_json->>'calculatedResult','') ~ '^[+]?[0-9]+([.][0-9]+)?$'
                  AND (record.values_json->>'calculatedResult')::numeric>0
                ORDER BY construction_on,record.id
                """).param("project", assessment.projectId()).param("parameter", assessment.parameterId())
                .param("periodStart", assessment.periodStart()).param("periodEnd", assessment.periodEnd())
                .param("part", assessment.structurePart()).query((rs, row) -> new SourceResult(
                        rs.getObject("id", UUID.class), rs.getString("report_number"),
                        rs.getObject("construction_on", LocalDate.class), rs.getBigDecimal("strength_value"))).list();
    }

    static Statistics statistics(List<BigDecimal> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(values.size()), CALCULATION_CONTEXT);
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal delta = value.subtract(average, CALCULATION_CONTEXT);
            variance = variance.add(delta.multiply(delta, CALCULATION_CONTEXT), CALCULATION_CONTEXT);
        }
        if (values.size() > 1) variance = variance.divide(BigDecimal.valueOf(values.size() - 1L), CALCULATION_CONTEXT);
        BigDecimal deviation = values.size() == 1 ? BigDecimal.ZERO : variance.sqrt(CALCULATION_CONTEXT);
        BigDecimal minimum = values.stream().min(BigDecimal::compareTo).orElseThrow();
        return new Statistics(values.size(), scale(average), scale(deviation), scale(minimum));
    }

    private Validated validate(Write body, boolean update) {
        if (body == null) throw invalid("CONCRETE_ASSESSMENT_REQUEST_REQUIRED", "强度评定不能为空");
        if (body.projectId() == null || body.structurePartId() == null || body.detectionProjectRevisionId() == null
                || body.parameterId() == null || body.standardId() == null) {
            throw invalid("CONCRETE_ASSESSMENT_REFERENCE_REQUIRED", "工程、构件、检测项目、参数和标准不能为空");
        }
        access.requireProject(body.projectId());
        validateRange(body.periodStart(), body.periodEnd(), "验收时段");
        if (body.periodStart() == null || body.periodEnd() == null) {
            throw invalid("CONCRETE_ASSESSMENT_PERIOD_REQUIRED", "验收开始和结束日期不能为空");
        }
        References references = references(body);
        BigDecimal quantity = positive(body.concreteQuantity(), "混凝土数量");
        BigDecimal deviationLevel = optionalNonNegative(body.standardDeviationAverageLevel(), "标准差平均水平");
        String requirement = required(body.designRequirement(), 80, "设计要求");
        if (update && body.expectedVersion() == null) throw invalid("EXPECTED_VERSION_REQUIRED", "修改时必须提供当前版本");
        return new Validated(references, requirement, quantity, deviationLevel,
                clean(body.remark(), 2000, "备注"));
    }

    private References references(Write body) {
        return jdbc.sql("""
                SELECT project.number project_number,project.name project_name,part.name part_name,
                  node.name detection_project,parameter.name parameter_name,
                  standard.code standard_code,standard.name standard_name
                FROM project JOIN project_structure_part part ON part.project_id=project.id AND part.id=:part AND part.active
                JOIN detection_project_revision revision ON revision.id=:revision AND revision.status='PUBLISHED'
                JOIN detection_catalog_node node ON node.id=revision.project_node_id AND node.status='ACTIVE'
                JOIN test_parameter parameter ON parameter.id=:parameter AND parameter.project_revision_id=revision.id
                  AND parameter.status='PUBLISHED' AND parameter.enabled
                JOIN test_parameter_standard relation ON relation.parameter_id=parameter.id AND relation.standard_id=:standard
                JOIN detection_standard standard ON standard.id=relation.standard_id
                  AND standard.state='PUBLISHED' AND standard.status='CURRENT'
                WHERE project.id=:project AND project.organization_id=:organization
                """).param("part", body.structurePartId()).param("revision", body.detectionProjectRevisionId())
                .param("parameter", body.parameterId()).param("standard", body.standardId())
                .param("project", body.projectId()).param("organization", actor.require().organizationId())
                .query((rs, row) -> new References(rs.getString("project_number"), rs.getString("project_name"),
                        rs.getString("part_name"), rs.getString("detection_project"),
                        rs.getString("parameter_name"), rs.getString("standard_code"),
                        rs.getString("standard_name"))).optional().orElseThrow(() -> invalid(
                        "CONCRETE_ASSESSMENT_REFERENCE_INVALID",
                        "构件、检测项目、参数或标准不存在、未发布或关联关系无效"));
    }

    private View withResults(View row) {
        List<ResultSnapshot> results = jdbc.sql("""
                SELECT test_record_id,report_number,construction_on,strength_value,sort_order
                FROM concrete_strength_result_snapshot WHERE assessment_id=:id ORDER BY sort_order,test_record_id
                """).param("id", row.id()).query((rs, index) -> new ResultSnapshot(
                        rs.getObject("test_record_id", UUID.class), rs.getString("report_number"),
                        rs.getObject("construction_on", LocalDate.class), plain(rs.getBigDecimal("strength_value")),
                        rs.getInt("sort_order"))).list();
        return new View(row.id(), row.projectId(), row.projectNumber(), row.projectName(), row.structurePartId(),
                row.structurePart(), row.detectionProjectRevisionId(), row.detectionProject(), row.parameterId(),
                row.parameterName(), row.standardId(), row.standardCode(), row.standardName(), row.designRequirement(),
                row.periodStart(), row.periodEnd(), row.concreteQuantity(), row.standardDeviationAverageLevel(),
                row.specimenGroupCount(), row.averageStrength(), row.standardDeviation(), row.minimumStrength(),
                row.acceptanceCoefficient(), row.acceptanceConstant(), row.statisticsText(), row.standardText(),
                row.calculationText(), row.conclusion(), row.remark(), row.ruleReadiness(), row.state(), row.version(),
                row.testedBy(), row.testerName(), row.reviewedBy(), row.reviewerName(), row.approvedBy(),
                row.approverName(), row.calculatedAt(), row.reviewedAt(), row.approvedAt(), row.updatedAt(),
                results, row.blockers(), row.allowedActions());
    }

    private String select() {
        return """
                SELECT assessment.id,assessment.project_id,assessment.project_number_snapshot,
                  assessment.project_name_snapshot,assessment.structure_part_id,assessment.structure_part_snapshot,
                  assessment.detection_project_revision_id,assessment.detection_project_snapshot,
                  assessment.parameter_id,assessment.parameter_snapshot,assessment.standard_id,
                  assessment.standard_code_snapshot,assessment.standard_name_snapshot,assessment.design_requirement,
                  assessment.period_start,assessment.period_end,assessment.concrete_quantity,
                  assessment.standard_deviation_average_level,assessment.specimen_group_count,
                  assessment.average_strength,assessment.standard_deviation,assessment.minimum_strength,
                  assessment.acceptance_coefficient,assessment.acceptance_constant,assessment.statistics_text,
                  assessment.standard_text,assessment.calculation_text,assessment.conclusion,assessment.remark,
                  assessment.rule_readiness,assessment.state,assessment.version,assessment.tested_by,
                  tester.display_name tester_name,assessment.reviewed_by,reviewer.display_name reviewer_name,
                  assessment.approved_by,approver.display_name approver_name,assessment.calculated_at,
                  assessment.reviewed_at,assessment.approved_at,assessment.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM concrete_strength_assessment assessment JOIN project ON project.id=assessment.project_id
                LEFT JOIN app_user tester ON tester.id=assessment.tested_by
                LEFT JOIN app_user reviewer ON reviewer.id=assessment.reviewed_by
                LEFT JOIN app_user approver ON approver.id=assessment.approved_by
                """;
    }

    private String conditions() {
        return """
                  AND (CAST(:periodFrom AS date) IS NULL OR assessment.period_end>=CAST(:periodFrom AS date))
                  AND (CAST(:periodTo AS date) IS NULL OR assessment.period_start<=CAST(:periodTo AS date))
                  AND (CAST(:revision AS uuid) IS NULL OR assessment.detection_project_revision_id=CAST(:revision AS uuid))
                  AND (:requirement='' OR assessment.design_requirement=:requirement)
                  AND (:projectNumber='' OR assessment.project_number_snapshot ILIKE :projectNumberPattern)
                  AND (:projectName='' OR assessment.project_name_snapshot ILIKE :projectNamePattern)
                  AND (:part='' OR assessment.structure_part_snapshot ILIKE :partPattern)
                  AND (:state='' OR assessment.state=:state)
                """;
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, String state, PageCursor cursor) {
        bindActor(statement, user);
        statement.param("periodFrom", filters.periodFrom()).param("periodTo", filters.periodTo())
                .param("revision", filters.detectionProjectRevisionId())
                .param("requirement", clean(filters.designRequirement(), 80, "设计要求"))
                .param("projectNumber", clean(filters.projectNumber(), 64, "工程编号"))
                .param("projectNumberPattern", pattern(filters.projectNumber()))
                .param("projectName", clean(filters.projectName(), 240, "工程名称"))
                .param("projectNamePattern", pattern(filters.projectName()))
                .param("part", clean(filters.structurePart(), 240, "分项工程或构件名称"))
                .param("partPattern", pattern(filters.structurePart())).param("state", state)
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
        return statement;
    }

    private String projectScope() {
        return """
                project.organization_id=:organization AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=project.id AND member.user_id=:actor))
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creatorScope WHERE creatorScope.id=project.created_by
                      AND creatorScope.department_id=:department)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN app_user commissionCreator
                      ON commissionCreator.id=commissionScope.created_by
                      WHERE commissionScope.project_id=project.id AND commissionCreator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN test_task taskScope
                      ON taskScope.commission_id=commissionScope.id JOIN person personScope ON personScope.id=taskScope.assigned_to
                      WHERE commissionScope.project_id=project.id AND personScope.department_id=:department)))
                  OR (:scope='SELF' AND (project.created_by=:actor
                    OR EXISTS(SELECT 1 FROM project_member member WHERE member.project_id=project.id AND member.user_id=:actor)
                    OR EXISTS(SELECT 1 FROM commission commissionScope WHERE commissionScope.project_id=project.id
                      AND commissionScope.created_by=:actor)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN test_task taskScope
                      ON taskScope.commission_id=commissionScope.id WHERE commissionScope.project_id=project.id
                      AND taskScope.assigned_to=:person)))
                )
                """;
    }

    private void bindActor(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String readiness = rs.getString("rule_readiness");
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_number_snapshot"), rs.getString("project_name_snapshot"),
                rs.getObject("structure_part_id", UUID.class), rs.getString("structure_part_snapshot"),
                rs.getObject("detection_project_revision_id", UUID.class), rs.getString("detection_project_snapshot"),
                rs.getObject("parameter_id", UUID.class), rs.getString("parameter_snapshot"),
                rs.getObject("standard_id", UUID.class), rs.getString("standard_code_snapshot"),
                rs.getString("standard_name_snapshot"), rs.getString("design_requirement"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                decimal(rs, "concrete_quantity"), decimal(rs, "standard_deviation_average_level"),
                (Integer) rs.getObject("specimen_group_count"), decimal(rs, "average_strength"),
                decimal(rs, "standard_deviation"), decimal(rs, "minimum_strength"),
                decimal(rs, "acceptance_coefficient"), decimal(rs, "acceptance_constant"),
                rs.getString("statistics_text"), rs.getString("standard_text"), rs.getString("calculation_text"),
                rs.getString("conclusion"), rs.getString("remark"), readiness, state, rs.getLong("version"),
                rs.getObject("tested_by", UUID.class), rs.getString("tester_name"),
                rs.getObject("reviewed_by", UUID.class), rs.getString("reviewer_name"),
                rs.getObject("approved_by", UUID.class), rs.getString("approver_name"),
                rs.getObject("calculated_at", OffsetDateTime.class), rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                List.of(), readiness.equals("READY") ? List.of() : FORMAL_RULE_BLOCKERS, actions(state));
    }

    static List<String> actions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("UPDATE", "CALCULATE", "DISCARD");
            case "CALCULATED" -> List.of("REOPEN", "SUBMIT_REVIEW", "RETIRE");
            case "IN_REVIEW" -> List.of("RETURN", "REVIEW", "RETIRE");
            case "REVIEWED" -> List.of("RETURN", "APPROVE", "RETIRE");
            case "APPROVED" -> List.of("RETIRE");
            default -> List.of();
        };
    }

    static boolean repeatsDuty(UUID currentActor, UUID... priorActors) {
        for (UUID priorActor : priorActors) if (currentActor.equals(priorActor)) return true;
        return false;
    }

    private void requirePermission(String permission) {
        if (!actor.require().permissions().contains(permission) && !actor.require().permissions().contains("SYSTEM_ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号无权执行该评定动作");
        }
    }

    private void requireVersion(Long expected, long current) {
        if (expected == null) throw invalid("EXPECTED_VERSION_REQUIRED", "必须提供当前版本");
        if (expected != current) throw ApiException.conflict("VERSION_CONFLICT", "强度评定已被其他用户更新", current);
    }

    private ApiException conflict(UUID id) {
        return ApiException.conflict("VERSION_CONFLICT", "强度评定已被其他用户更新", require(id).version());
    }

    private ApiException invalidAction(String action) {
        return new ApiException(HttpStatus.CONFLICT, "CONCRETE_ASSESSMENT_ACTION_INVALID", "当前状态或规则就绪度不允许执行 " + action);
    }

    private BigDecimal positive(String value, String label) {
        BigDecimal number = decimal(value, label);
        if (number.signum() <= 0) throw invalid("CONCRETE_ASSESSMENT_NUMBER_INVALID", label + "必须大于 0");
        return number;
    }

    private BigDecimal optionalNonNegative(String value, String label) {
        if (value == null || value.isBlank()) return null;
        BigDecimal number = decimal(value, label);
        if (number.signum() < 0) throw invalid("CONCRETE_ASSESSMENT_NUMBER_INVALID", label + "不得小于 0");
        return number;
    }

    private BigDecimal decimal(String value, String label) {
        try { return new BigDecimal(required(value, 80, label)); }
        catch (NumberFormatException exception) { throw invalid("CONCRETE_ASSESSMENT_NUMBER_INVALID", label + "必须是十进制数值"); }
    }

    private static BigDecimal scale(BigDecimal value) { return value.setScale(6, RoundingMode.HALF_UP); }
    private String plain(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private String decimal(java.sql.ResultSet rs, String column) throws java.sql.SQLException { return plain(rs.getBigDecimal(column)); }

    private void validateRange(LocalDate from, LocalDate to, String label) {
        if (from != null && to != null && to.isBefore(from)) throw invalid("DATE_RANGE_INVALID", label + "结束日期不得早于开始日期");
    }

    private String required(String value, int max, String label) {
        String result = clean(value, max, label);
        if (result.isEmpty()) throw invalid("CONCRETE_ASSESSMENT_FIELD_REQUIRED", label + "不能为空");
        return result;
    }

    private String clean(String value, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw invalid("CONCRETE_ASSESSMENT_FIELD_TOO_LONG", label + "最多 " + max + " 个字符");
        return result;
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String pattern(String value) { return "%" + (value == null ? "" : value.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

    private record References(String projectNumber, String projectName, String partName,
                              String detectionProject, String parameterName,
                              String standardCode, String standardName) {}
    private record Validated(References references, String requirement, BigDecimal quantity,
                             BigDecimal deviationLevel, String remark) {}
    private record ParameterRow(UUID id, String name, UUID revisionId, String projectName,
                                UUID standardId, String standardCode, String standardName) {}
    private record ParameterAccumulator(UUID id, String name, UUID revisionId, String projectName,
                                        List<StandardOption> standards) {}
    private record SourceResult(UUID testRecordId, String reportNumber, LocalDate constructionOn, BigDecimal value) {}
    record Statistics(int count, BigDecimal average, BigDecimal standardDeviation, BigDecimal minimum) {}
}
