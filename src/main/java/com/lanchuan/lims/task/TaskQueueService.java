package com.lanchuan.lims.task;

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
public class TaskQueueService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> VIEWS = Set.of("TASKS", "RETURNED");
    private static final Set<String> STATES = Set.of("UNASSIGNED", "ASSIGNED", "ACCEPTED", "IN_PROGRESS",
            "SUBMITTED", "RETURNED", "COMPLETED", "CANCELLED");

    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public TaskQueueService(JdbcClient jdbc, CurrentActor actor) { this.jdbc = jdbc; this.actor = actor; }

    public record Filters(String view, String q, String state, LocalDate dueFrom, LocalDate dueTo,
                          String cursor, int limit) {}
    public record Row(UUID id, UUID commissionId, String commissionNumber, String projectNumber,
                      String projectName, String customerName, String taskNumber, String sampleNumber,
                      String sampleName, String parameterCode, String parameterName, String assigneeName,
                      OffsetDateTime dueAt, String state, long version, OffsetDateTime acceptedAt,
                      OffsetDateTime startedAt, OffsetDateTime completedAt, OffsetDateTime updatedAt) {}

    public CursorPage<Row> list(Filters filters) { return listFor(actor.require(), filters); }

    public CursorPage<Row> listFor(AuthenticatedUser user, Filters requested) {
        Filters filters = validate(requested);
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String conditions = """
                  AND (:state='' OR task.state=:state)
                  AND (CAST(:dueFrom AS date) IS NULL OR task.due_at>=CAST(:dueFrom AS date))
                  AND (CAST(:dueTo AS date) IS NULL OR task.due_at<CAST(:dueTo AS date)+interval '1 day')
                  AND (:q='' OR task.number ILIKE :pattern ESCAPE '\\'
                    OR commission.number ILIKE :pattern ESCAPE '\\'
                    OR sample.number ILIKE :pattern ESCAPE '\\'
                    OR sample.name ILIKE :pattern ESCAPE '\\'
                    OR parameter.code ILIKE :pattern ESCAPE '\\'
                    OR parameter.name ILIKE :pattern ESCAPE '\\'
                    OR project.number ILIKE :pattern ESCAPE '\\'
                    OR project.name ILIKE :pattern ESCAPE '\\'
                    OR customer.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(person.name,'') ILIKE :pattern ESCAPE '\\')
                """ + (filters.view().equals("RETURNED") ? " AND task.state='RETURNED' " : " ");
        var statement = jdbc.sql(select() + " WHERE " + scope("commission") + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (task.updated_at,task.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY task.updated_at DESC,task.id DESC LIMIT :fetchLimit
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
                SELECT task.id,commission.id commission_id,commission.number commission_number,
                  project.number project_number,project.name project_name,customer.name customer_name,
                  task.number task_number,sample.number sample_number,sample.name sample_name,
                  parameter.code parameter_code,COALESCE(selection.parameter_name_snapshot,parameter.name) parameter_name,
                  COALESCE(person.name,'待分配') assignee_name,task.due_at,task.state,task.version,
                  task.accepted_at,task.started_at,task.completed_at,task.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM test_task task
                JOIN commission commission ON commission.id=task.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                JOIN sample sample ON sample.id=task.sample_id
                JOIN test_parameter parameter ON parameter.id=task.parameter_id
                LEFT JOIN commission_parameter_selection selection ON selection.id=task.selection_id
                LEFT JOIN person person ON person.id=task.assigned_to
                """;
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, PageCursor cursor) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("state", filters.state()).param("dueFrom", filters.dueFrom()).param("dueTo", filters.dueTo())
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
        if (!VIEWS.contains(view)) throw invalid("TASK_QUEUE_VIEW_INVALID", "任务视图无效");
        String state = upper(requested.state());
        if (!state.isEmpty() && !STATES.contains(state)) throw invalid("TASK_STATE_FILTER_INVALID", "任务状态筛选无效");
        String q = clean(requested.q());
        if (requested.dueFrom() != null && requested.dueTo() != null && requested.dueFrom().isAfter(requested.dueTo())) {
            throw invalid("TASK_DATE_RANGE_INVALID", "计划完成日期起始值不能晚于结束值");
        }
        return new Filters(view, q, state, requested.dueFrom(), requested.dueTo(), requested.cursor(), requested.limit());
    }

    private Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("commission_number"), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("customer_name"), rs.getString("task_number"), rs.getString("sample_number"),
                rs.getString("sample_name"), rs.getString("parameter_code"), rs.getString("parameter_name"),
                rs.getString("assignee_name"), rs.getObject("due_at", OffsetDateTime.class), rs.getString("state"),
                rs.getLong("version"), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String pattern(String value) { return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private String clean(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > 100) throw invalid("TASK_QUERY_TOO_LONG", "查询内容不能超过100个字符");
        return cleaned;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
