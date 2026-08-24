package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class QualityLedgerService {
    private static final UUID NO_ID = new UUID(0, 0);
    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public QualityLedgerService(JdbcClient jdbc, CurrentActor actor) {
        this.jdbc = jdbc;
        this.actor = actor;
    }

    public record SpecimenFilters(String view, LocalDate commissionedFrom, LocalDate commissionedTo,
                                  String region, String detectionProject, String commissionNumber,
                                  String sampleNumber, String reportNumber, String projectNumber,
                                  String projectName, String customerName, String conclusion,
                                  String taskState, String curingMethod, String strengthGrade,
                                  String cursor, int limit) {}
    public record SpecimenRow(UUID id, UUID commissionId, String sampleNumber, String commissionNumber,
                              String reportNumber, String detectionProject, String taskState,
                              String conclusion, String customerName, String projectNumber,
                              String projectName, String constructionUnit, String structurePart,
                              LocalDate commissionedOn, LocalDate samplingDate, LocalDate moldingDate,
                              Integer ageDays, LocalDate plannedTestDate, LocalDate actualTestDate,
                              String curingMethod, String strengthGrade, String chipCode,
                              OffsetDateTime updatedAt) {}

    public record ReboundFilters(LocalDate commissionedFrom, LocalDate commissionedTo,
                                 String commissionNumber, String sampleNumber, String projectNumber,
                                 String projectName, String customerName, String cursor, int limit) {}
    public record ReboundRow(UUID id, UUID commissionId, String sampleNumber, String commissionNumber,
                             Integer componentCount, Integer measurementAreaCount, LocalDate commissionedOn,
                             String detectionProject, String specification, String strengthGrade,
                             Integer ageDays, String structurePart, String customerName,
                             String projectNumber, String projectName, OffsetDateTime updatedAt) {}

    public record FieldTestFilters(LocalDate from, LocalDate to, String progress, String detectionProject,
                                   String taskNumber, String projectName, String testerName,
                                   String cursor, int limit) {}
    public record FieldTestRow(UUID id, UUID commissionId, String taskNumber, String projectName,
                               String detectionProject, OffsetDateTime startedAt, OffsetDateTime completedAt,
                               String testerName, String progress, OffsetDateTime updatedAt) {}

    public CursorPage<SpecimenRow> specimens(SpecimenFilters filters) {
        return specimensFor(actor.require(), filters);
    }

    CursorPage<SpecimenRow> specimensFor(AuthenticatedUser user, SpecimenFilters filters) {
        String view = upper(filters.view());
        if (!Set.of("OVERDUE", "DUE").contains(view)) throw invalid("SPECIMEN_LEDGER_VIEW_INVALID", "试块台账视图无效");
        validateRange(filters.commissionedFrom(), filters.commissionedTo());
        int limit = bounded(filters.limit());
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String conditions = specimenConditions();
        var statement = jdbc.sql(specimenSelect() + " WHERE " + scope("commission") + conditions + """
                AND (CAST(:cursorTime AS timestamptz) IS NULL OR (sample.updated_at,sample.id)<
                    (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY sample.updated_at DESC,sample.id DESC LIMIT :fetchLimit
                """);
        bindSpecimen(statement, user, filters, view, cursor).param("fetchLimit", limit + 1);
        List<SpecimenRow> fetched = statement.query(this::mapSpecimen).list();
        boolean hasMore = fetched.size() > limit;
        List<SpecimenRow> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var countStatement = jdbc.sql("SELECT count(*) " + specimenFrom() + " WHERE "
                + scope("commission") + conditions);
        bindSpecimen(countStatement, user, filters, view, null);
        long total = countStatement.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public CursorPage<ReboundRow> rebounds(ReboundFilters filters) {
        return reboundsFor(actor.require(), filters);
    }

    CursorPage<ReboundRow> reboundsFor(AuthenticatedUser user, ReboundFilters filters) {
        validateRange(filters.commissionedFrom(), filters.commissionedTo());
        int limit = bounded(filters.limit());
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String conditions = reboundConditions();
        var statement = jdbc.sql(reboundSelect() + " WHERE " + scope("commission") + conditions + """
                AND (CAST(:cursorTime AS timestamptz) IS NULL OR (record.updated_at,record.id)<
                    (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY record.updated_at DESC,record.id DESC LIMIT :fetchLimit
                """);
        bindRebound(statement, user, filters, cursor).param("fetchLimit", limit + 1);
        List<ReboundRow> fetched = statement.query(this::mapRebound).list();
        boolean hasMore = fetched.size() > limit;
        List<ReboundRow> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + reboundFrom() + " WHERE " + scope("commission") + conditions);
        bindRebound(count, user, filters, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public CursorPage<FieldTestRow> fieldTests(FieldTestFilters filters) {
        return fieldTestsFor(actor.require(), filters);
    }

    CursorPage<FieldTestRow> fieldTestsFor(AuthenticatedUser user, FieldTestFilters filters) {
        validateRange(filters.from(), filters.to());
        int limit = bounded(filters.limit());
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String progress = upper(filters.progress());
        if (!progress.isEmpty() && !Set.of("UNASSIGNED","ASSIGNED","ACCEPTED","IN_PROGRESS",
                "SUBMITTED","RETURNED","COMPLETED","CANCELLED").contains(progress)) {
            throw invalid("FIELD_TEST_PROGRESS_INVALID", "检测进度无效");
        }
        String conditions = fieldConditions();
        var statement = jdbc.sql(fieldSelect() + " WHERE " + scope("commission") + conditions + """
                AND (CAST(:cursorTime AS timestamptz) IS NULL OR (task.updated_at,task.id)<
                    (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY task.updated_at DESC,task.id DESC LIMIT :fetchLimit
                """);
        bindField(statement, user, filters, progress, cursor).param("fetchLimit", limit + 1);
        List<FieldTestRow> fetched = statement.query(this::mapField).list();
        boolean hasMore = fetched.size() > limit;
        List<FieldTestRow> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + fieldFrom() + " WHERE " + scope("commission") + conditions);
        bindField(count, user, filters, progress, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    private String specimenSelect() {
        return """
                SELECT sample.id,sample.commission_id,sample.number sample_number,
                  commission.number commission_number,latest_report.number report_number,
                  selection.projects detection_project,latest_task.state task_state,test_summary.conclusion,
                  customer.name customer_name,project.number project_number,project.name project_name,
                  commission.construction_unit,sample.structure_part,commission.commissioned_on,
                  sample.sampling_date,sample.molding_date,sample.age_days,sample.planned_test_date,
                  test_summary.actual_test_date,sample.curing_method,sample.strength_grade,sample.chip_code,
                  sample.updated_at
                """ + specimenFrom();
    }

    private String specimenFrom() {
        return """
                FROM sample sample JOIN commission commission ON commission.id=sample.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                LEFT JOIN LATERAL (
                  SELECT string_agg(DISTINCT selected.project_name_snapshot,'、'
                    ORDER BY selected.project_name_snapshot) projects
                  FROM commission_detection_selection selected WHERE selected.commission_id=commission.id
                ) selection ON true
                LEFT JOIN LATERAL (
                  SELECT task.state FROM test_task task WHERE task.sample_id=sample.id
                  ORDER BY task.updated_at DESC,task.id DESC LIMIT 1
                ) latest_task ON true
                LEFT JOIN LATERAL (
                  SELECT CASE WHEN bool_or(record.result='NONCONFORMING') THEN 'NONCONFORMING'
                              WHEN bool_or(record.result='CONFORMING') THEN 'CONFORMING'
                              WHEN count(record.id)>0 THEN 'PENDING' END conclusion,
                         max((record.confirmed_at AT TIME ZONE 'Asia/Shanghai')::date) actual_test_date
                  FROM test_task task LEFT JOIN test_record record ON record.task_id=task.id
                    AND record.state<>'SUPERSEDED' WHERE task.sample_id=sample.id
                ) test_summary ON true
                LEFT JOIN LATERAL (
                  SELECT report.number FROM report WHERE report.commission_id=commission.id
                  ORDER BY report.updated_at DESC,report.id DESC LIMIT 1
                ) latest_report ON true
                """;
    }

    private String specimenConditions() {
        return """
                  AND sample.planned_test_date IS NOT NULL
                  AND (:view='DUE' OR (:view='OVERDUE' AND sample.planned_test_date<current_date
                    AND NOT EXISTS(SELECT 1 FROM test_task completed
                      WHERE completed.sample_id=sample.id AND completed.state='COMPLETED')))
                  AND (CAST(:fromDate AS date) IS NULL OR commission.commissioned_on>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR commission.commissioned_on<=CAST(:toDate AS date))
                  AND (:region='' OR project.location ILIKE :regionPattern)
                  AND (:detectionProject='' OR selection.projects ILIKE :detectionPattern)
                  AND (:commissionNumber='' OR commission.number ILIKE :commissionPattern)
                  AND (:sampleNumber='' OR sample.number ILIKE :samplePattern)
                  AND (:reportNumber='' OR latest_report.number ILIKE :reportPattern)
                  AND (:projectNumber='' OR project.number ILIKE :projectNumberPattern)
                  AND (:projectName='' OR project.name ILIKE :projectNamePattern)
                  AND (:customerName='' OR customer.name ILIKE :customerPattern)
                  AND (:conclusion='' OR test_summary.conclusion=:conclusion)
                  AND (:taskState='' OR latest_task.state=:taskState)
                  AND (:curingMethod='' OR sample.curing_method ILIKE :curingPattern)
                  AND (:strengthGrade='' OR sample.strength_grade ILIKE :strengthPattern)
                """;
    }

    private String reboundSelect() {
        return """
                SELECT record.id,commission.id commission_id,sample.number sample_number,
                  commission.number commission_number,
                  CASE WHEN COALESCE(record.values_json->>'componentCount',record.values_json->>'构件总数') ~ '^\\d+$'
                    THEN COALESCE(record.values_json->>'componentCount',record.values_json->>'构件总数')::integer END component_count,
                  CASE WHEN COALESCE(record.values_json->>'measurementAreaCount',record.values_json->>'测区总数') ~ '^\\d+$'
                    THEN COALESCE(record.values_json->>'measurementAreaCount',record.values_json->>'测区总数')::integer END measurement_area_count,
                  commission.commissioned_on,COALESCE(selection.parameter_name_snapshot,parameter.name) detection_project,
                  sample.specification,sample.strength_grade,sample.age_days,sample.structure_part,
                  customer.name customer_name,project.number project_number,project.name project_name,record.updated_at
                """ + reboundFrom();
    }

    private String reboundFrom() {
        return """
                FROM test_record record JOIN test_task task ON task.id=record.task_id
                JOIN commission commission ON commission.id=task.commission_id
                JOIN sample sample ON sample.id=task.sample_id JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                JOIN test_parameter parameter ON parameter.id=task.parameter_id
                LEFT JOIN commission_parameter_selection selection ON selection.id=task.selection_id
                """;
    }

    private String reboundConditions() {
        return """
                  AND record.state='CONFIRMED'
                  AND (COALESCE(selection.parameter_name_snapshot,parameter.name) ILIKE '%回弹%'
                    OR jsonb_exists(record.values_json,'componentCount')
                    OR jsonb_exists(record.values_json,'构件总数'))
                  AND (CAST(:fromDate AS date) IS NULL OR commission.commissioned_on>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR commission.commissioned_on<=CAST(:toDate AS date))
                  AND (:commissionNumber='' OR commission.number ILIKE :commissionPattern)
                  AND (:sampleNumber='' OR sample.number ILIKE :samplePattern)
                  AND (:projectNumber='' OR project.number ILIKE :projectNumberPattern)
                  AND (:projectName='' OR project.name ILIKE :projectNamePattern)
                  AND (:customerName='' OR customer.name ILIKE :customerPattern)
                """;
    }

    private String fieldSelect() {
        return """
                SELECT task.id,commission.id commission_id,task.number task_number,project.name project_name,
                  COALESCE(selection.parameter_name_snapshot,parameter.name) detection_project,
                  task.started_at,task.completed_at,person.name tester_name,task.state progress,task.updated_at
                """ + fieldFrom();
    }

    private String fieldFrom() {
        return """
                FROM test_task task JOIN commission commission ON commission.id=task.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN test_parameter parameter ON parameter.id=task.parameter_id
                LEFT JOIN commission_parameter_selection selection ON selection.id=task.selection_id
                LEFT JOIN person ON person.id=task.assigned_to
                """;
    }

    private String fieldConditions() {
        return """
                  AND commission.testing_place='FIELD'
                  AND (CAST(:fromDate AS date) IS NULL OR (task.started_at AT TIME ZONE 'Asia/Shanghai')::date>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR (task.started_at AT TIME ZONE 'Asia/Shanghai')::date<=CAST(:toDate AS date))
                  AND (:progress='' OR task.state=:progress)
                  AND (:detectionProject='' OR COALESCE(selection.parameter_name_snapshot,parameter.name) ILIKE :detectionPattern)
                  AND (:taskNumber='' OR task.number ILIKE :taskPattern)
                  AND (:projectName='' OR project.name ILIKE :projectPattern)
                  AND (:testerName='' OR person.name ILIKE :testerPattern)
                """;
    }

    private JdbcClient.StatementSpec bindSpecimen(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                                   SpecimenFilters filters, String view, PageCursor cursor) {
        bindActor(statement, user);
        statement.param("view", view).param("fromDate", filters.commissionedFrom())
                .param("toDate", filters.commissionedTo()).param("region", text(filters.region()))
                .param("regionPattern", pattern(filters.region())).param("detectionProject", text(filters.detectionProject()))
                .param("detectionPattern", pattern(filters.detectionProject()))
                .param("commissionNumber", text(filters.commissionNumber())).param("commissionPattern", pattern(filters.commissionNumber()))
                .param("sampleNumber", text(filters.sampleNumber())).param("samplePattern", pattern(filters.sampleNumber()))
                .param("reportNumber", text(filters.reportNumber())).param("reportPattern", pattern(filters.reportNumber()))
                .param("projectNumber", text(filters.projectNumber())).param("projectNumberPattern", pattern(filters.projectNumber()))
                .param("projectName", text(filters.projectName())).param("projectNamePattern", pattern(filters.projectName()))
                .param("customerName", text(filters.customerName())).param("customerPattern", pattern(filters.customerName()))
                .param("conclusion", upper(filters.conclusion())).param("taskState", upper(filters.taskState()))
                .param("curingMethod", text(filters.curingMethod())).param("curingPattern", pattern(filters.curingMethod()))
                .param("strengthGrade", text(filters.strengthGrade())).param("strengthPattern", pattern(filters.strengthGrade()))
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
        return statement;
    }

    private JdbcClient.StatementSpec bindRebound(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                                  ReboundFilters filters, PageCursor cursor) {
        bindActor(statement, user);
        statement.param("fromDate", filters.commissionedFrom()).param("toDate", filters.commissionedTo())
                .param("commissionNumber", text(filters.commissionNumber())).param("commissionPattern", pattern(filters.commissionNumber()))
                .param("sampleNumber", text(filters.sampleNumber())).param("samplePattern", pattern(filters.sampleNumber()))
                .param("projectNumber", text(filters.projectNumber())).param("projectNumberPattern", pattern(filters.projectNumber()))
                .param("projectName", text(filters.projectName())).param("projectNamePattern", pattern(filters.projectName()))
                .param("customerName", text(filters.customerName())).param("customerPattern", pattern(filters.customerName()))
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
        return statement;
    }

    private JdbcClient.StatementSpec bindField(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                                FieldTestFilters filters, String progress, PageCursor cursor) {
        bindActor(statement, user);
        statement.param("fromDate", filters.from()).param("toDate", filters.to()).param("progress", progress)
                .param("detectionProject", text(filters.detectionProject())).param("detectionPattern", pattern(filters.detectionProject()))
                .param("taskNumber", text(filters.taskNumber())).param("taskPattern", pattern(filters.taskNumber()))
                .param("projectName", text(filters.projectName())).param("projectPattern", pattern(filters.projectName()))
                .param("testerName", text(filters.testerName())).param("testerPattern", pattern(filters.testerName()))
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
        return statement;
    }

    private void bindActor(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
    }

    private String scope(String alias) {
        return """
                %1$s.organization_id=:organization AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=%1$s.created_by
                      AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM test_task scoped_task JOIN person scoped_person
                      ON scoped_person.id=scoped_task.assigned_to
                      WHERE scoped_task.commission_id=%1$s.id AND scoped_person.department_id=:department)))
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=%1$s.project_id AND member.user_id=:actor))
                  OR (:scope='SELF' AND (%1$s.created_by=:actor OR EXISTS(SELECT 1 FROM test_task scoped_task
                    WHERE scoped_task.commission_id=%1$s.id AND scoped_task.assigned_to=:person))))
                """.formatted(alias);
    }

    private SpecimenRow mapSpecimen(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SpecimenRow(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("sample_number"), rs.getString("commission_number"), rs.getString("report_number"),
                rs.getString("detection_project"), rs.getString("task_state"), rs.getString("conclusion"),
                rs.getString("customer_name"), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("construction_unit"), rs.getString("structure_part"),
                rs.getObject("commissioned_on", LocalDate.class), rs.getObject("sampling_date", LocalDate.class),
                rs.getObject("molding_date", LocalDate.class), rs.getObject("age_days", Integer.class),
                rs.getObject("planned_test_date", LocalDate.class), rs.getObject("actual_test_date", LocalDate.class),
                rs.getString("curing_method"), rs.getString("strength_grade"), rs.getString("chip_code"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ReboundRow mapRebound(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReboundRow(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("sample_number"), rs.getString("commission_number"),
                rs.getObject("component_count", Integer.class), rs.getObject("measurement_area_count", Integer.class),
                rs.getObject("commissioned_on", LocalDate.class), rs.getString("detection_project"),
                rs.getString("specification"), rs.getString("strength_grade"), rs.getObject("age_days", Integer.class),
                rs.getString("structure_part"), rs.getString("customer_name"), rs.getString("project_number"),
                rs.getString("project_name"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private FieldTestRow mapField(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new FieldTestRow(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("task_number"), rs.getString("project_name"), rs.getString("detection_project"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("tester_name"), rs.getString("progress"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private int bounded(int limit) { return Math.max(1, Math.min(limit, 100)); }
    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) throw invalid("DATE_RANGE_INVALID", "结束日期不得早于开始日期");
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String text(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() > 120) throw invalid("FILTER_TOO_LONG", "单个查询条件最多 120 个字符");
        return result;
    }
    private String pattern(String value) { return "%" + text(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
