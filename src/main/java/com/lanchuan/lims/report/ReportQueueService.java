package com.lanchuan.lims.report;

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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportQueueService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> VIEWS = Set.of("REPORT_EDITING", "REPORT_REVIEW", "REPORT_ISSUE",
            "REPORT_SEAL", "REPORT_ARCHIVE", "REPORT_REVISION", "REPORT_LEDGER", "REPORT_DISTRIBUTION",
            "REPORT_PRINT", "VOID_REPORTS", "REPORT_CHANGE_REQUESTS", "REPORT_RETURN_RECORDS", "REPORT_SEARCH");
    private static final Set<String> STATES = Set.of("DRAFT", "IN_REVIEW", "RETURNED", "APPROVED", "ISSUED",
            "DISTRIBUTED", "ARCHIVED", "VOID");

    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public ReportQueueService(JdbcClient jdbc, CurrentActor actor) { this.jdbc = jdbc; this.actor = actor; }

    public record Filters(String view, String q, String states, LocalDate from, LocalDate to,
                          String cursor, int limit) {}
    public record Row(UUID id, UUID versionId, UUID commissionId, String reportNumber, String title,
                      String commissionNumber, String projectNumber, String projectName,
                      String customerName, int revision, String revisionLabel, String state,
                      String pdfStatus, boolean signed, boolean sealed, long distributedCount,
                      long acknowledgedCount, String archiveNumber, long version,
                      OffsetDateTime updatedAt) {}

    public CursorPage<Row> list(Filters filters) { return listFor(actor.require(), filters); }

    public CursorPage<Row> listFor(AuthenticatedUser user, Filters requested) {
        Filters filters = validate(requested);
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        boolean history = filters.view().equals("REPORT_LEDGER");
        String conditions = """
                  AND (:states='' OR report.state=ANY(string_to_array(:states,',')))
                  AND (CAST(:fromDate AS date) IS NULL OR report.created_at>=CAST(:fromDate AS date))
                  AND (CAST(:toDate AS date) IS NULL OR report.created_at<CAST(:toDate AS date)+interval '1 day')
                  AND (:q='' OR report.number ILIKE :pattern ESCAPE '\\'
                    OR report.title ILIKE :pattern ESCAPE '\\'
                    OR commission.number ILIKE :pattern ESCAPE '\\'
                    OR project.number ILIKE :pattern ESCAPE '\\'
                    OR project.name ILIKE :pattern ESCAPE '\\'
                    OR customer.name ILIKE :pattern ESCAPE '\\')
                  AND (:history OR reportVersion.revision=report.current_revision)
                """ + viewCondition(filters.view());
        var statement = jdbc.sql(select() + " WHERE " + scope("commission") + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (report.updated_at,reportVersion.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY report.updated_at DESC,reportVersion.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, filters, history, cursor).param("fetchLimit", limit + 1);
        List<Row> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<Row> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + from() + " WHERE " + scope("commission") + conditions);
        bind(count, user, filters, history, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().versionId()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    private String select() {
        return """
                SELECT report.id,reportVersion.id version_id,commission.id commission_id,
                  report.number report_number,report.title,commission.number commission_number,
                  project.number project_number,project.name project_name,customer.name customer_name,
                  reportVersion.revision,reportVersion.revision_label,
                  CASE WHEN archive.id IS NOT NULL THEN 'ARCHIVED'
                    WHEN reportVersion.revision<>report.current_revision THEN 'SUPERSEDED'
                    ELSE report.state END state,
                  CASE WHEN reportVersion.final_pdf_at IS NOT NULL THEN 'FINAL'
                    WHEN reportVersion.pdf_file_id IS NOT NULL THEN 'REVIEW' ELSE 'PENDING' END pdf_status,
                  reportVersion.signed_at IS NOT NULL signed,reportVersion.sealed_at IS NOT NULL sealed,
                  (SELECT count(*) FROM report_distribution distribution
                    WHERE distribution.report_version_id=reportVersion.id) distributed_count,
                  (SELECT count(*) FROM report_distribution distribution
                    WHERE distribution.report_version_id=reportVersion.id AND distribution.acknowledged_at IS NOT NULL) acknowledged_count,
                  archive.archive_number,report.version,report.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM report report
                JOIN commission commission ON commission.id=report.commission_id
                JOIN project project ON project.id=commission.project_id
                JOIN customer customer ON customer.id=commission.customer_id
                JOIN report_version reportVersion ON reportVersion.report_id=report.id
                LEFT JOIN archive_package archive ON archive.report_version_id=reportVersion.id
                """;
    }

    private String viewCondition(String view) {
        return switch (view) {
            case "REPORT_EDITING" -> " AND report.state IN ('DRAFT','RETURNED') ";
            case "REPORT_REVIEW" -> " AND report.state='IN_REVIEW' ";
            case "REPORT_ISSUE" -> " AND report.state='APPROVED' ";
            case "REPORT_SEAL" -> " AND report.state IN ('ISSUED','DISTRIBUTED') AND reportVersion.sealed_at IS NULL ";
            case "REPORT_DISTRIBUTION" -> " AND report.state IN ('ISSUED','DISTRIBUTED') ";
            case "REPORT_PRINT" -> " AND report.state IN ('ISSUED','DISTRIBUTED','ARCHIVED') ";
            case "REPORT_ARCHIVE" -> " AND report.state IN ('DISTRIBUTED','ARCHIVED') ";
            case "REPORT_REVISION", "REPORT_CHANGE_REQUESTS" -> " AND report.state IN ('ISSUED','DISTRIBUTED','ARCHIVED','VOID') ";
            case "REPORT_RETURN_RECORDS" -> " AND report.state='RETURNED' ";
            case "VOID_REPORTS" -> " AND report.state='VOID' ";
            default -> " ";
        };
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, boolean history, PageCursor cursor) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("states", filters.states()).param("fromDate", filters.from()).param("toDate", filters.to())
                .param("q", filters.q()).param("pattern", pattern(filters.q())).param("history", history)
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
        if (!VIEWS.contains(view)) throw invalid("REPORT_QUEUE_VIEW_INVALID", "报告视图无效");
        String states = normalizeStates(requested.states());
        String q = clean(requested.q());
        if (requested.from() != null && requested.to() != null && requested.from().isAfter(requested.to())) {
            throw invalid("REPORT_DATE_RANGE_INVALID", "报告日期起始值不能晚于结束值");
        }
        return new Filters(view, q, states, requested.from(), requested.to(), requested.cursor(), requested.limit());
    }

    private String normalizeStates(String value) {
        if (value == null || value.isBlank()) return "";
        List<String> values = Arrays.stream(value.split(",")).map(this::upper).filter(item -> !item.isBlank()).distinct().toList();
        if (values.isEmpty() || !STATES.containsAll(values)) throw invalid("REPORT_STATE_FILTER_INVALID", "报告状态筛选无效");
        return String.join(",", values);
    }

    private Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getObject("commission_id", UUID.class), rs.getString("report_number"), rs.getString("title"),
                rs.getString("commission_number"), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("customer_name"), rs.getInt("revision"), rs.getString("revision_label"),
                rs.getString("state"), rs.getString("pdf_status"), rs.getBoolean("signed"), rs.getBoolean("sealed"),
                rs.getLong("distributed_count"), rs.getLong("acknowledged_count"), rs.getString("archive_number"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String pattern(String value) { return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private String clean(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > 100) throw invalid("REPORT_QUERY_TOO_LONG", "查询内容不能超过100个字符");
        return cleaned;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
