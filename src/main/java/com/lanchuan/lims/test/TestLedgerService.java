package com.lanchuan.lims.test;

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
import java.util.Set;
import java.util.UUID;

@Service
public class TestLedgerService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> VIEWS = Set.of("TESTS", "TEST_LEDGER", "RETURNED");
    private static final Set<String> STATES = Set.of("DRAFT", "SUBMITTED", "RETURNED", "CONFIRMED", "SUPERSEDED");
    private static final Set<String> RESULTS = Set.of("PENDING", "CONFORMING", "NONCONFORMING", "INVALID");

    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public TestLedgerService(JdbcClient jdbc, CurrentActor actor) { this.jdbc = jdbc; this.actor = actor; }

    public record Filters(String view, String q, String state, String result, LocalDate from,
                          LocalDate to, String cursor, int limit) {}
    public record Row(UUID id, UUID commissionId, UUID taskId, String commissionNumber,
                      String projectNumber, String projectName, String customerName,
                      String taskNumber, String sampleNumber, String sampleName,
                      String parameterCode, String parameterName, String testerName,
                      String equipmentCode, String equipmentName, int revision, String result,
                      String state, long version, OffsetDateTime submittedAt,
                      OffsetDateTime confirmedAt, OffsetDateTime updatedAt) {}

    public CursorPage<Row> list(Filters filters) { return listFor(actor.require(), filters); }

    public CursorPage<Row> listFor(AuthenticatedUser user, Filters requested) {
        Filters filters = validate(requested);
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String viewCondition = filters.view().equals("RETURNED") ? " AND record.state='RETURNED' "
                : filters.view().equals("TESTS") ? " AND record.state<>'SUPERSEDED' " : " ";
        String conditions = """
                  AND (:state='' OR record.state=:state)
                  AND (:result='' OR record.result=:result)
                  AND (CAST(:fromDate AS date) IS NULL OR record.updated_at>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR record.updated_at<CAST(:toDate AS date)+interval '1 day')
                  AND (:q='' OR task.number ILIKE :pattern ESCAPE '\\'
                    OR commission.number ILIKE :pattern ESCAPE '\\'
                    OR sample.number ILIKE :pattern ESCAPE '\\'
                    OR sample.name ILIKE :pattern ESCAPE '\\'
                    OR parameter.code ILIKE :pattern ESCAPE '\\'
                    OR parameter.name ILIKE :pattern ESCAPE '\\'
                    OR project.number ILIKE :pattern ESCAPE '\\'
                    OR project.name ILIKE :pattern ESCAPE '\\'
                    OR customer.name ILIKE :pattern ESCAPE '\\'
                    OR person.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.code,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.name,'') ILIKE :pattern ESCAPE '\\')
                """ + viewCondition;
        var statement = jdbc.sql(select() + " WHERE " + scope("commission") + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (record.updated_at,record.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY record.updated_at DESC,record.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, filters, cursor).param("fetchLimit", limit + 1);
        List<Row> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<Row> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + from() + " WHERE " + scope("commission") + conditions);
        bind(count, user, filters, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    private String select() {
        return """
                SELECT record.id,commission.id commission_id,task.id task_id,
                  commission.number commission_number,project.number project_number,project.name project_name,
                  customer.name customer_name,task.number task_number,sample.number sample_number,
                  sample.name sample_name,parameter.code parameter_code,
                  COALESCE(selection.parameter_name_snapshot,parameter.name) parameter_name,
                  person.name tester_name,equipment.code equipment_code,equipment.name equipment_name,
                  record.revision,record.result,record.state,record.version,record.submitted_at,
                  record.confirmed_at,record.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM test_record record
                JOIN test_task task ON task.id=record.task_id
                JOIN commission commission ON commission.id=task.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                JOIN sample sample ON sample.id=task.sample_id
                JOIN test_parameter parameter ON parameter.id=task.parameter_id
                LEFT JOIN commission_parameter_selection selection ON selection.id=task.selection_id
                JOIN person person ON person.id=record.recorded_by
                LEFT JOIN equipment equipment ON equipment.id=record.equipment_id
                """;
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, PageCursor cursor) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("state", filters.state()).param("result", filters.result())
                .param("fromDate", filters.from()).param("toDate", filters.to())
                .param("q", filters.q()).param("pattern", pattern(filters.q()))
                .param("cursorAt", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
    }

    private String scope(String alias) {
        return """
                %1$s.organization_id=:organization AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=%1$s.created_by AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM test_task scoped_task JOIN person scoped_person ON scoped_person.id=scoped_task.assigned_to
                      WHERE scoped_task.commission_id=%1$s.id AND scoped_person.department_id=:department)))
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=%1$s.project_id AND member.user_id=:actor))
                  OR (:scope='SELF' AND (%1$s.created_by=:actor OR EXISTS(SELECT 1 FROM test_task scoped_task
                    WHERE scoped_task.commission_id=%1$s.id AND scoped_task.assigned_to=:person))))
                """.formatted(alias);
    }

    private Filters validate(Filters requested) {
        String view = upper(requested.view());
        if (!VIEWS.contains(view)) throw invalid("TEST_LEDGER_VIEW_INVALID", "试验视图无效");
        String state = upper(requested.state());
        if (!state.isEmpty() && !STATES.contains(state)) throw invalid("TEST_STATE_FILTER_INVALID", "试验状态筛选无效");
        String result = upper(requested.result());
        if (!result.isEmpty() && !RESULTS.contains(result)) throw invalid("TEST_RESULT_FILTER_INVALID", "检测结论筛选无效");
        String q = clean(requested.q());
        if (requested.from() != null && requested.to() != null && requested.from().isAfter(requested.to())) {
            throw invalid("TEST_DATE_RANGE_INVALID", "检测日期起始值不能晚于结束值");
        }
        return new Filters(view, q, state, result, requested.from(), requested.to(), requested.cursor(), requested.limit());
    }

    private Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("commission_number"), rs.getString("project_number"),
                rs.getString("project_name"), rs.getString("customer_name"), rs.getString("task_number"),
                rs.getString("sample_number"), rs.getString("sample_name"), rs.getString("parameter_code"),
                rs.getString("parameter_name"), rs.getString("tester_name"), rs.getString("equipment_code"),
                rs.getString("equipment_name"), rs.getInt("revision"), rs.getString("result"),
                rs.getString("state"), rs.getLong("version"), rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("confirmed_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String pattern(String value) { return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private String clean(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > 100) throw invalid("TEST_QUERY_TOO_LONG", "查询内容不能超过100个字符");
        return cleaned;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
