package com.lanchuan.lims.sample;

import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SampleLedgerService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> VIEWS = Set.of("SAMPLES", "SAMPLE_LEDGER", "CONCRETE",
            "MANAGEMENT", "RETAINED", "AGE_SCHEDULE");
    private static final Set<String> STATES = Set.of("EXPECTED", "RECEIVED", "HANDED_OVER", "IN_TEST",
            "RETAINED", "RETURNED", "DISPOSED");

    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public SampleLedgerService(JdbcClient jdbc, CurrentActor actor) {
        this.jdbc = jdbc;
        this.actor = actor;
    }

    public record Filters(String view, String q, String state, LocalDate from, LocalDate to,
                          String cursor, int limit) {}
    public record Row(UUID id, UUID commissionId, String commissionNumber, String projectNumber,
                      String projectName, String customerName, String detectionProjects,
                      String sampleNumber, String sampleName, String specification,
                      String receivedQuantity, String availableQuantity, String unit,
                      LocalDate samplingDate, LocalDate moldingDate, LocalDate plannedTestDate,
                      LocalDate actualTestDate, Integer ageDays, String curingMethod,
                      String strengthGrade, String structurePart, String chipCode,
                      String state, long version, OffsetDateTime receivedAt, OffsetDateTime updatedAt) {}

    public CursorPage<Row> list(Filters filters) {
        return listFor(actor.require(), filters);
    }

    public CursorPage<Row> listFor(AuthenticatedUser user, Filters requested) {
        Filters filters = validate(requested);
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String viewCondition = viewCondition(filters.view());
        String conditions = """
                  AND (:state='' OR sample.state=:state)
                  AND (CAST(:fromDate AS date) IS NULL OR commission.commissioned_on>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR commission.commissioned_on<=CAST(:toDate AS date))
                  AND (:q='' OR sample.number ILIKE :pattern ESCAPE '\\'
                    OR sample.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(sample.specification,'') ILIKE :pattern ESCAPE '\\'
                    OR commission.number ILIKE :pattern ESCAPE '\\'
                    OR project.number ILIKE :pattern ESCAPE '\\'
                    OR project.name ILIKE :pattern ESCAPE '\\'
                    OR customer.name ILIKE :pattern ESCAPE '\\')
                """ + viewCondition;
        var statement = jdbc.sql(select() + " WHERE " + scope("commission") + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (sample.updated_at,sample.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY sample.updated_at DESC,sample.id DESC LIMIT :fetchLimit
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
                SELECT sample.id,commission.id commission_id,commission.number commission_number,
                  project.number project_number,project.name project_name,customer.name customer_name,
                  COALESCE((SELECT string_agg(DISTINCT selection.project_name_snapshot,'、'
                    ORDER BY selection.project_name_snapshot)
                    FROM commission_detection_selection selection
                    WHERE selection.commission_id=commission.id),'') detection_projects,
                  sample.number sample_number,sample.name sample_name,sample.specification,
                  sample.received_quantity,sample.available_quantity,sample.unit,
                  sample.sampling_date,sample.molding_date,sample.planned_test_date,test_summary.actual_test_date,
                  sample.age_days,sample.curing_method,sample.strength_grade,sample.structure_part,
                  sample.chip_code,sample.state,sample.version,sample.received_at,sample.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM sample sample
                JOIN commission commission ON commission.id=sample.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                LEFT JOIN LATERAL (
                  SELECT max((record.confirmed_at AT TIME ZONE 'Asia/Shanghai')::date) actual_test_date
                  FROM test_task task
                  LEFT JOIN test_record record ON record.task_id=task.id AND record.state<>'SUPERSEDED'
                  WHERE task.sample_id=sample.id
                ) test_summary ON true
                """;
    }

    private String viewCondition(String view) {
        return switch (view) {
            case "CONCRETE" -> " AND (sample.strength_grade IS NOT NULL OR sample.molding_date IS NOT NULL) ";
            case "RETAINED" -> " AND sample.state='RETAINED' ";
            case "AGE_SCHEDULE" -> " AND sample.planned_test_date IS NOT NULL ";
            default -> " ";
        };
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, PageCursor cursor) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("state", filters.state()).param("fromDate", filters.from()).param("toDate", filters.to())
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
        if (!VIEWS.contains(view)) throw invalid("SAMPLE_LEDGER_VIEW_INVALID", "样品视图无效");
        String state = upper(requested.state());
        if (!state.isEmpty() && !STATES.contains(state)) throw invalid("SAMPLE_STATE_FILTER_INVALID", "样品状态筛选无效");
        String q = clean(requested.q());
        if (requested.from() != null && requested.to() != null && requested.from().isAfter(requested.to())) {
            throw invalid("SAMPLE_DATE_RANGE_INVALID", "委托日期起始值不能晚于结束值");
        }
        return new Filters(view, q, state, requested.from(), requested.to(), requested.cursor(), requested.limit());
    }

    private Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("commission_number"), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("customer_name"), rs.getString("detection_projects"), rs.getString("sample_number"),
                rs.getString("sample_name"), rs.getString("specification"), decimal(rs.getBigDecimal("received_quantity")),
                decimal(rs.getBigDecimal("available_quantity")), rs.getString("unit"),
                rs.getObject("sampling_date", LocalDate.class), rs.getObject("molding_date", LocalDate.class),
                rs.getObject("planned_test_date", LocalDate.class), rs.getObject("actual_test_date", LocalDate.class),
                rs.getObject("age_days", Integer.class), rs.getString("curing_method"), rs.getString("strength_grade"),
                rs.getString("structure_part"), rs.getString("chip_code"), rs.getString("state"), rs.getLong("version"),
                rs.getObject("received_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private String pattern(String value) { return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private String clean(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > 100) throw invalid("SAMPLE_QUERY_TOO_LONG", "查询内容不能超过100个字符");
        return cleaned;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
