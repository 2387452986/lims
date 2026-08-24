package com.lanchuan.lims.workbench;

import com.lanchuan.lims.configuration.SystemExperienceService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {
    private static final UUID NO_ID = new UUID(0, 0);
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final SystemExperienceService experience;
    private final WorkbenchSnapshotCache cache;

    public WorkbenchController(JdbcClient jdbc, CurrentActor actor, SystemExperienceService experience,
                               WorkbenchSnapshotCache cache) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.experience = experience;
        this.cache = cache;
    }

    public record QuickAction(String key, String label, String description, String path, String tone) {}
    public record StageCount(String key, String label, long total, long pending, String path) {}
    public record Alert(String key, String label, long total, long warning, String description,
                        String path, String severity) {}
    public record CommissionQueueItem(String id, String number, String projectName, String customerName,
                                      String detectionProjects, String state, String flowStatus, String dueOn) {}
    public record ReportQueueItem(String id, String commissionId, String number, String title,
                                  String commissionNumber, String projectName, String state,
                                  String actionLabel, String path, String updatedAt) {}
    public record RoleTodoItem(String id, String businessNumber, String title, String secondary,
                               String state, String stateLabel, String allowedAction, String path,
                               String dueAt) {}
    public record RoleTodoGroup(String key, String label, long total, String path,
                                List<RoleTodoItem> items) {}
    public record Notice(String id, String category, String title, String body, String resourceType,
                         String resourceId, boolean read, long version, String createdAt) {}
    public record RecentEvent(String id, String title, String resourceType, String actor,
                              String occurredAt, String comment) {}
    public record CommissionCard(String id, String number, String projectName, String customerName,
                                 String commissionedOn, String dueOn, String contact) {}
    public record Step(String key, String label, String state, long count, long total, String date) {}
    public record Summary(long total, long completed, long active, long pending, long blocked) {}
    public record Evidence(String name, String status, long collected, long required) {}
    public record Task(String id, String project, String sampleNumber, String assignee, String dueOn, String status) {}
    public record Timeline(String title, String actor, String occurredAt, boolean active) {}
    public record Workbench(List<QuickAction> quickActions, List<StageCount> stageCounts,
                            List<Alert> alerts, List<CommissionQueueItem> pendingCommissions,
                            List<ReportQueueItem> pendingReports, List<RoleTodoGroup> roleTodos,
                            List<Notice> notifications,
                            List<RecentEvent> recentEvents, CommissionCard spotlightCommission,
                            List<Step> steps, Summary summary, List<Evidence> evidence,
                            List<Task> tasks, List<Timeline> timeline,
                            List<SystemExperienceService.TransitionWarning> transitionWarnings) {}

    @GetMapping
    @PreAuthorize("hasAuthority('WORKBENCH_READ')")
    public Workbench get(@RequestParam(defaultValue = "ALL") String status,
                         @RequestParam(required = false) LocalDate from,
                         @RequestParam(required = false) LocalDate to,
                         @RequestParam(defaultValue = "") String q) {
        AuthenticatedUser user = actor.require();
        DateWindow window = dateWindow(from, to);
        String keyword = q == null ? "" : q.trim();
        String normalizedStatus = switch (status == null ? "ALL" : status.trim().toUpperCase()) {
            case "IN_PROGRESS", "BLOCKED" -> status.trim().toUpperCase();
            default -> "ALL";
        };
        WorkbenchSnapshotCache.Key key = new WorkbenchSnapshotCache.Key(user.id(), user.sessionVersion(),
                user.organizationId(), user.departmentId(), user.personId(), user.dataScope(), user.permissions(),
                normalizedStatus, window.from(), window.to(), keyword);
        return cache.get(key, Workbench.class,
                () -> load(user, normalizedStatus, window.from(), window.to(), keyword));
    }

    record DateWindow(LocalDate from, LocalDate to) {}

    static DateWindow dateWindow(LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : to;
        LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(30) : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKBENCH_DATE_RANGE_INVALID",
                    "开始日期不能晚于结束日期");
        }
        return new DateWindow(effectiveFrom, effectiveTo);
    }

    private Workbench load(AuthenticatedUser user, String status, LocalDate from, LocalDate to, String keyword) {
        CommissionQueues commissions = commissions(user, status, from, to, keyword);
        List<CommissionQueueItem> pendingCommissions = commissions.pending();
        CommissionRow spotlight = commissions.spotlight();

        List<Step> steps = List.of();
        Summary summary = new Summary(0, 0, 0, 0, 0);
        List<Evidence> evidenceItems = List.of();
        List<Task> taskItems = List.of();
        List<Timeline> timelineItems = List.of();
        List<SystemExperienceService.TransitionWarning> warnings = List.of();
        CommissionCard spotlightCard = null;
        if (spotlight != null) {
            Counts counts = counts(spotlight.id());
            steps = steps(spotlight, counts);
            long complete = steps.stream().filter(step -> step.state().equals("COMPLETED")).count();
            long blocked = steps.stream().filter(step -> step.state().equals("BLOCKED")).count();
            long active = steps.stream().filter(step -> step.state().equals("ACTIVE")).count();
            summary = new Summary(steps.size(), complete, active, steps.size() - complete - active - blocked, blocked);
            spotlightCard = new CommissionCard(spotlight.id().toString(), spotlight.number(), spotlight.projectName(),
                    spotlight.customerName(), spotlight.commissionedOn().toString(),
                    spotlight.dueOn() == null ? "—" : spotlight.dueOn().toString(), spotlight.contact());
            evidenceItems = evidence(spotlight.id(), counts);
            taskItems = tasks(spotlight.id());
            timelineItems = timeline(spotlight.id());
            warnings = experience.transitionWarnings(spotlight.id());
        }
        return new Workbench(quickActions(user), stageCounts(user), alerts(user), pendingCommissions,
                pendingReports(user, from, to, keyword), roleTodos(user), notifications(user), recentEvents(user), spotlightCard,
                steps, summary, evidenceItems, taskItems, timelineItems, warnings);
    }

    private List<RoleTodoGroup> roleTodos(AuthenticatedUser user) {
        boolean moduleRead = user.permissions().contains("MODULE_READ");
        boolean review = user.permissions().contains("REPORT_REVIEW");
        boolean issue = user.permissions().contains("REPORT_ISSUE");
        List<TodoDefinition> definitions = new ArrayList<>();
        if (moduleRead) {
            definitions.add(new TodoDefinition("task-assignment", "待派发任务", "/modules/tasks?state=UNASSIGNED", "派发"));
            if (user.personId() != null) definitions.add(new TodoDefinition("my-tests", "我的检测任务", "/modules/tests?state=ASSIGNED,ACCEPTED,IN_PROGRESS,RETURNED", "处理"));
            definitions.add(new TodoDefinition("test-check", "待校核", "/modules/tests?state=SUBMITTED", "校核"));
        }
        if (review) definitions.add(new TodoDefinition("report-review", "待审核", "/modules/report-review?state=IN_REVIEW", "审核"));
        if (issue) definitions.add(new TodoDefinition("report-issue", "待签发", "/modules/report-issue?state=APPROVED", "签发"));
        if (moduleRead) {
            definitions.add(new TodoDefinition("report-distribution", "待发放", "/modules/report-distribution?state=ISSUED", "发放"));
            definitions.add(new TodoDefinition("equipment-due", "到期应检", "/modules/equipment-certificates?validity=DUE", "处理"));
        }
        if (definitions.isEmpty()) return List.of();

        List<TodoRow> rows = scoped(jdbc.sql("""
                WITH scoped_task AS MATERIALIZED (
                  SELECT t.id,t.number,COALESCE(parameter.name,project.name) title,
                    c.number||' · '||project.name secondary,t.state,t.due_at,t.assigned_to
                  FROM test_task t JOIN commission c ON c.id=t.commission_id
                  JOIN project project ON project.id=c.project_id
                  LEFT JOIN test_parameter parameter ON parameter.id=t.parameter_id
                  WHERE c.organization_id=:organization %s
                ), scoped_report AS MATERIALIZED (
                  SELECT report.id,report.number,report.title,c.number||' · '||project.name secondary,
                    report.state,report.updated_at due_at
                  FROM report report JOIN commission c ON c.id=report.commission_id
                  JOIN project project ON project.id=c.project_id
                  WHERE c.organization_id=:organization %s
                ), equipment_due AS MATERIALIZED (
                  SELECT equipment.id,equipment.code number,equipment.name title,
                    COALESCE(equipment.location,'未登记位置') secondary,
                    CASE WHEN certificate.valid_until IS NULL THEN 'MISSING' ELSE 'DUE' END state,
                    COALESCE(certificate.valid_until,current_date)::timestamptz due_at
                  FROM equipment equipment
                  LEFT JOIN LATERAL (
                    SELECT max(certificate.valid_until) valid_until FROM equipment_certificate certificate
                    WHERE certificate.equipment_id=equipment.id AND certificate.state='CONFIRMED'
                      AND certificate.result IN ('PASS','LIMITED')
                  ) certificate ON true
                  WHERE equipment.organization_id=:organization AND equipment.enabled
                    AND equipment.state<>'SCRAPPED'
                    AND (certificate.valid_until IS NULL OR certificate.valid_until<current_date+30)
                ), todo AS (
                  SELECT 'task-assignment' group_key,id,number,title,secondary,state,due_at FROM scoped_task
                    WHERE :moduleRead AND state='UNASSIGNED'
                  UNION ALL SELECT 'my-tests',id,number,title,secondary,state,due_at FROM scoped_task
                    WHERE :moduleRead AND assigned_to=:person AND state IN ('ASSIGNED','ACCEPTED','IN_PROGRESS','RETURNED')
                  UNION ALL SELECT 'test-check',id,number,title,secondary,state,due_at FROM scoped_task
                    WHERE :moduleRead AND state='SUBMITTED'
                  UNION ALL SELECT 'report-review',id,number,title,secondary,state,due_at FROM scoped_report
                    WHERE :review AND state='IN_REVIEW'
                  UNION ALL SELECT 'report-issue',id,number,title,secondary,state,due_at FROM scoped_report
                    WHERE :issue AND state='APPROVED'
                  UNION ALL SELECT 'report-distribution',id,number,title,secondary,state,due_at FROM scoped_report
                    WHERE :moduleRead AND state='ISSUED'
                  UNION ALL SELECT 'equipment-due',id,number,title,secondary,state,due_at FROM equipment_due
                    WHERE :moduleRead
                ), ranked AS (
                  SELECT todo.*,count(*) OVER (PARTITION BY group_key) total,
                    row_number() OVER (PARTITION BY group_key ORDER BY due_at NULLS LAST,id) position
                  FROM todo
                )
                SELECT group_key,id,number,title,secondary,state,due_at,total
                FROM ranked WHERE position<=6 ORDER BY group_key,position
                """.formatted(scopeClause(), scopeClause())), user)
                .param("moduleRead", moduleRead).param("review", review).param("issue", issue)
                .query((rs, row) -> new TodoRow(rs.getString("group_key"), rs.getObject("id", UUID.class),
                        rs.getString("number"), rs.getString("title"), rs.getString("secondary"),
                        rs.getString("state"), rs.getObject("due_at", OffsetDateTime.class), rs.getLong("total"))).list();
        return definitions.stream().map(definition -> {
            List<TodoRow> groupRows = rows.stream().filter(row -> row.key().equals(definition.key())).toList();
            List<RoleTodoItem> items = groupRows.stream().map(row -> new RoleTodoItem(row.id().toString(),
                    row.number(), row.title(), row.secondary(), row.state(), todoStateLabel(row.state()),
                    definition.action(), definition.path(), format(row.dueAt()))).toList();
            return new RoleTodoGroup(definition.key(), definition.label(),
                    groupRows.isEmpty() ? 0 : groupRows.getFirst().total(), definition.path(), items);
        }).toList();
    }

    private CommissionQueues commissions(AuthenticatedUser user, String status, LocalDate from, LocalDate to,
                                          String keyword) {
        String normalized = keyword.toLowerCase();
        String pattern = "%" + escapeLike(normalized) + "%";
        List<WorkbenchCommission> rows = scoped(jdbc.sql("""
                WITH candidates AS MATERIALIZED (
                  SELECT c.id,c.number,p.name project_name,u.name customer_name,c.contact_name,
                    c.commissioned_on,c.due_on,c.state,c.updated_at,
                    COALESCE(selection.projects,'') detection_projects,
                    CASE WHEN c.state IN ('DRAFT','PRE_ACCEPTED') OR EXISTS(
                      SELECT 1 FROM commission_transfer transfer
                      WHERE transfer.commission_id=c.id AND transfer.state='PENDING')
                    THEN 'PENDING' ELSE 'TRANSFERRED' END flow_status,
                    EXISTS(SELECT 1 FROM nonconformance nc WHERE nc.commission_id=c.id
                      AND nc.state NOT IN ('CLOSED','REJECTED')) blocked
                  FROM commission c JOIN project p ON p.id=c.project_id JOIN customer u ON u.id=c.customer_id
                  LEFT JOIN LATERAL (
                    SELECT string_agg(DISTINCT selection.project_name_snapshot,'、'
                      ORDER BY selection.project_name_snapshot) projects
                    FROM commission_detection_selection selection WHERE selection.commission_id=c.id
                  ) selection ON true
                  WHERE c.organization_id=:organization %s
                    AND c.commissioned_on BETWEEN :from AND :to
                    AND (:q='' OR lower(c.number) LIKE :pattern ESCAPE '\\'
                      OR lower(p.name) LIKE :pattern ESCAPE '\\'
                      OR lower(u.name) LIKE :pattern ESCAPE '\\'
                      OR lower(COALESCE(c.contact_name,'')) LIKE :pattern ESCAPE '\\')
                ), pending AS (
                  SELECT 'PENDING' row_kind,candidate.* FROM candidates candidate
                  WHERE candidate.state NOT IN ('ARCHIVED','REJECTED','CANCELLED')
                  ORDER BY candidate.updated_at DESC,candidate.id DESC LIMIT 8
                ), spotlight AS (
                  SELECT 'SPOTLIGHT' row_kind,candidate.* FROM candidates candidate
                  WHERE :status='ALL'
                    OR (:status='IN_PROGRESS' AND candidate.state NOT IN ('ARCHIVED','REJECTED','CANCELLED'))
                    OR (:status='BLOCKED' AND candidate.blocked)
                  ORDER BY candidate.updated_at DESC,candidate.id DESC LIMIT 1
                )
                SELECT * FROM pending UNION ALL SELECT * FROM spotlight
                """.formatted(scopeClause())), user).param("from", from).param("to", to)
                .param("q", normalized).param("pattern", pattern).param("status", status)
                .query((rs, row) -> new WorkbenchCommission(rs.getString("row_kind"),
                        rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("project_name"),
                        rs.getString("customer_name"), rs.getString("contact_name"),
                        rs.getObject("commissioned_on", LocalDate.class), rs.getObject("due_on", LocalDate.class),
                        rs.getString("state"), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("detection_projects"), rs.getString("flow_status"))).list();
        List<CommissionQueueItem> pending = rows.stream().filter(row -> row.kind().equals("PENDING"))
                .map(this::queueItem).toList();
        CommissionRow spotlight = rows.stream().filter(row -> row.kind().equals("SPOTLIGHT"))
                .map(row -> new CommissionRow(row.id(), row.number(), row.projectName(), row.customerName(),
                        row.commissionedOn(), row.dueOn(), row.contact(), row.state(), row.updatedAt()))
                .findFirst().orElse(null);
        return new CommissionQueues(pending, spotlight);
    }

    private List<QuickAction> quickActions(AuthenticatedUser user) {
        List<QuickAction> actions = new ArrayList<>();
        if (user.permissions().contains("MODULE_WRITE")) {
            actions.add(new QuickAction("commission-create", "新建委托", "选择检测项目、参数与样品组", "/modules/commissions?create=true", "primary"));
        }
        if (user.permissions().contains("MODULE_READ")) {
            actions.add(new QuickAction("precommission", "预委托受理", "核对并批量受理预委托", "/modules/precommissions", "cyan"));
            actions.add(new QuickAction("tasks", "任务派发", "处理未派发、退回与改派任务", "/modules/tasks?state=UNASSIGNED", "violet"));
            actions.add(new QuickAction("tests", "试验检测", "录入原始记录并提交", "/modules/tests?state=IN_PROGRESS", "teal"));
            actions.add(new QuickAction("parameters", "检测参数", "维护目录、版本、标准与样品配置", "/modules/parameters", "blue"));
            actions.add(new QuickAction("equipment", "设备管理", "检定、借还、使用与库存", "/modules/equipment", "amber"));
        }
        if (user.permissions().contains("REPORT_REVIEW")) {
            actions.add(new QuickAction("report-review", "报告审核", "审核或退回待审报告", "/modules/report-review?state=IN_REVIEW", "orange"));
        }
        if (user.permissions().contains("REPORT_ISSUE")) {
            actions.add(new QuickAction("report-issue", "报告签发", "签发已批准报告", "/modules/report-issue?state=APPROVED", "red"));
        }
        return List.copyOf(actions.stream().limit(8).toList());
    }

    private List<StageCount> stageCounts(AuthenticatedUser user) {
        StageTotals totals = scoped(jdbc.sql("""
                WITH scoped_commission AS (
                  SELECT c.id,c.state,c.precommission_number FROM commission c
                  WHERE c.organization_id=:organization %s
                ), task_stat AS (
                  SELECT count(*) total,
                    count(*) FILTER (WHERE t.state='UNASSIGNED') unassigned,
                    count(*) FILTER (WHERE t.state IN ('ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED')) testing
                  FROM test_task t JOIN scoped_commission c ON c.id=t.commission_id
                ), report_stat AS (
                  SELECT count(*) total,
                    count(*) FILTER (WHERE r.state IN ('DRAFT','RETURNED')) editing,
                    count(*) FILTER (WHERE r.state='IN_REVIEW') reviewing,
                    count(*) FILTER (WHERE r.state='APPROVED') issuing,
                    count(*) FILTER (WHERE r.state='ISSUED') distributing
                  FROM report r JOIN scoped_commission c ON c.id=r.commission_id
                )
                SELECT
                  (SELECT count(*) FROM scoped_commission
                    WHERE precommission_number IS NOT NULL OR state='PRE_ACCEPTED') pre_total,
                  (SELECT count(*) FROM scoped_commission WHERE state='PRE_ACCEPTED') pre_pending,
                  (SELECT count(*) FROM scoped_commission
                    WHERE state NOT IN ('REJECTED','CANCELLED')) commission_total,
                  (SELECT count(*) FROM scoped_commission
                    WHERE state IN ('PRE_ACCEPTED','ACCEPTED','SAMPLING')) commission_pending,
                  task_stat.total task_total,task_stat.unassigned task_pending,task_stat.testing test_pending,
                  report_stat.total report_total,report_stat.editing,report_stat.reviewing,
                  report_stat.issuing,report_stat.distributing
                FROM task_stat CROSS JOIN report_stat
                """.formatted(scopeClause())), user).query((rs, row) -> new StageTotals(
                rs.getLong("pre_total"), rs.getLong("pre_pending"), rs.getLong("commission_total"),
                rs.getLong("commission_pending"), rs.getLong("task_total"), rs.getLong("task_pending"),
                rs.getLong("test_pending"), rs.getLong("report_total"), rs.getLong("editing"),
                rs.getLong("reviewing"), rs.getLong("issuing"), rs.getLong("distributing"))).single();
        return List.of(
                new StageCount("precommission", "预委托", totals.preTotal(), totals.prePending(), "/modules/precommissions?state=PENDING"),
                new StageCount("commission", "委托收样", totals.commissionTotal(), totals.commissionPending(), "/modules/commissions?flow=PENDING"),
                new StageCount("task", "任务派发", totals.taskTotal(), totals.taskPending(), "/modules/tasks?state=UNASSIGNED"),
                new StageCount("test", "试验检测", totals.taskTotal(), totals.testPending(), "/modules/tests?state=IN_PROGRESS"),
                new StageCount("editing", "报告编辑", totals.reportTotal(), totals.editing(), "/modules/report-editing?state=DRAFT,RETURNED"),
                new StageCount("review", "报告审核", totals.reportTotal(), totals.reviewing(), "/modules/report-review?state=IN_REVIEW"),
                new StageCount("issue", "报告签发", totals.reportTotal(), totals.issuing(), "/modules/report-issue?state=APPROVED"),
                new StageCount("distribution", "报告发放", totals.reportTotal(), totals.distributing(), "/modules/report-distribution?state=ISSUED"));
    }

    private List<Alert> alerts(AuthenticatedUser user) {
        AlertTotals totals = scoped(jdbc.sql("""
                WITH scoped_commission AS (
                  SELECT c.id FROM commission c WHERE c.organization_id=:organization %s
                )
                SELECT
                  (SELECT count(*) FROM person_qualification qualification JOIN person person ON person.id=qualification.person_id
                    JOIN department department ON department.id=person.department_id
                    WHERE department.organization_id=:organization AND qualification.status='VALID') person_total,
                  (SELECT count(*) FROM person_qualification qualification JOIN person person ON person.id=qualification.person_id
                    JOIN department department ON department.id=person.department_id
                    WHERE department.organization_id=:organization AND qualification.status='VALID'
                      AND qualification.valid_until<current_date+30) person_warning,
                  (SELECT count(*) FROM organization_qualification_record qualification
                    WHERE qualification.organization_id=:organization AND qualification.state='SUBMITTED') organization_total,
                  (SELECT count(*) FROM organization_qualification_record qualification
                    WHERE qualification.organization_id=:organization AND qualification.state='SUBMITTED'
                      AND qualification.valid_until<current_date+30) organization_warning,
                  (SELECT count(*) FROM equipment equipment WHERE equipment.organization_id=:organization
                    AND equipment.enabled AND equipment.state<>'SCRAPPED') equipment_total,
                  (SELECT count(*) FROM equipment equipment WHERE equipment.organization_id=:organization
                    AND equipment.enabled AND equipment.state<>'SCRAPPED' AND (equipment.performance_status='ABNORMAL'
                      OR NOT EXISTS (SELECT 1 FROM equipment_certificate certificate
                        WHERE certificate.equipment_id=equipment.id AND certificate.state='CONFIRMED'
                          AND certificate.result IN ('PASS','LIMITED') AND certificate.valid_until>=current_date+30))) equipment_warning,
                  (SELECT count(*) FROM test_record record JOIN test_task task ON task.id=record.task_id
                    JOIN scoped_commission commission ON commission.id=task.commission_id
                    WHERE record.state<>'SUPERSEDED') test_total,
                  (SELECT count(*) FROM test_record record JOIN test_task task ON task.id=record.task_id
                    JOIN scoped_commission commission ON commission.id=task.commission_id
                    WHERE record.state<>'SUPERSEDED' AND record.result IN ('NONCONFORMING','INVALID')) test_warning
                """.formatted(scopeClause())), user).query((rs, row) -> new AlertTotals(
                rs.getLong("person_total"), rs.getLong("person_warning"),
                rs.getLong("organization_total"), rs.getLong("organization_warning"),
                rs.getLong("equipment_total"), rs.getLong("equipment_warning"),
                rs.getLong("test_total"), rs.getLong("test_warning"))).single();
        return List.of(
                alert("person", "人员资格", totals.personTotal(), totals.personWarning(), "30 天内到期或已逾期", "/modules/qualifications"),
                alert("organization", "单位资质", totals.organizationTotal(), totals.organizationWarning(), "30 天内到期或已逾期", "/modules/organization-qualifications"),
                alert("equipment", "设备状态", totals.equipmentTotal(), totals.equipmentWarning(), "性能异常、缺证或 30 天内到期", "/modules/equipment"),
                alert("testing", "检测异常", totals.testTotal(), totals.testWarning(), "不合格或无效检测记录", "/modules/nonconforming-samples"));
    }

    private Alert alert(String key, String label, long total, long warning, String description, String path) {
        return new Alert(key, label, total, warning, description, path, warning > 0 ? "WARNING" : "NORMAL");
    }

    private List<ReportQueueItem> pendingReports(AuthenticatedUser user, LocalDate from, LocalDate to, String q) {
        String normalized = q.toLowerCase();
        String pattern = "%" + escapeLike(normalized) + "%";
        return scoped(jdbc.sql("""
                SELECT r.id,c.id commission_id,r.number,r.title,c.number commission_number,p.name project_name,
                  r.state,r.updated_at
                FROM report r JOIN commission c ON c.id=r.commission_id JOIN project p ON p.id=c.project_id
                WHERE c.organization_id=:organization %s
                  AND r.state IN ('DRAFT','RETURNED','IN_REVIEW','APPROVED','ISSUED')
                  AND c.commissioned_on BETWEEN :from AND :to
                  AND (:q='' OR lower(r.number) LIKE :pattern ESCAPE '\\' OR lower(r.title) LIKE :pattern ESCAPE '\\'
                    OR lower(c.number) LIKE :pattern ESCAPE '\\' OR lower(p.name) LIKE :pattern ESCAPE '\\')
                ORDER BY CASE r.state WHEN 'IN_REVIEW' THEN 0 WHEN 'APPROVED' THEN 1 WHEN 'ISSUED' THEN 2 ELSE 3 END,
                  r.updated_at DESC,r.id DESC LIMIT 8
                """.formatted(scopeClause())), user).param("from", from).param("to", to)
                .param("q", normalized).param("pattern", pattern)
                .query((rs, row) -> {
                    String state = rs.getString("state");
                    UUID commissionId = rs.getObject("commission_id", UUID.class);
                    return new ReportQueueItem(rs.getObject("id", UUID.class).toString(), commissionId.toString(),
                            rs.getString("number"), rs.getString("title"), rs.getString("commission_number"),
                            rs.getString("project_name"), state, reportActionLabel(state), reportPath(state),
                            format(rs.getObject("updated_at", OffsetDateTime.class)));
                }).list();
    }

    private List<Notice> notifications(AuthenticatedUser user) {
        return jdbc.sql("""
                SELECT id,category,title,body,resource_type,resource_id,read_at,version,created_at
                FROM notification WHERE recipient_id=:recipient ORDER BY read_at NULLS FIRST,created_at DESC LIMIT 6
                """).param("recipient", user.id()).query((rs, row) -> new Notice(
                rs.getObject("id", UUID.class).toString(), rs.getString("category"), rs.getString("title"),
                rs.getString("body"), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class) == null ? null : rs.getObject("resource_id", UUID.class).toString(),
                rs.getObject("read_at") != null, rs.getLong("version"),
                format(rs.getObject("created_at", OffsetDateTime.class)))).list();
    }

    private List<RecentEvent> recentEvents(AuthenticatedUser user) {
        return jdbc.sql("""
                SELECT id,action,resource_type,COALESCE(actor_name,'系统') actor,occurred_at,comment
                FROM audit_event WHERE organization_id=:organization
                  AND (:wideScope OR actor_id=:actor)
                ORDER BY occurred_at DESC,id DESC LIMIT 8
                """).param("organization", user.organizationId())
                .param("wideScope", Set.of("ALL", "ORGANIZATION").contains(user.dataScope()))
                .param("actor", user.id()).query((rs, row) -> new RecentEvent(
                        rs.getObject("id", UUID.class).toString(), actionLabel(rs.getString("action")),
                        resourceTypeLabel(rs.getString("resource_type")), rs.getString("actor"),
                        format(rs.getObject("occurred_at", OffsetDateTime.class)), rs.getString("comment"))).list();
    }

    private CommissionQueueItem queueItem(WorkbenchCommission item) {
        return new CommissionQueueItem(item.id().toString(), item.number(), item.projectName(), item.customerName(),
                item.detectionProjects(), item.state(), item.flowStatus(),
                item.dueOn() == null ? "—" : item.dueOn().toString());
    }

    private Counts counts(UUID commissionId) {
        return jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM sample WHERE commission_id=:id) samples,
                  (SELECT count(*) FROM sample WHERE commission_id=:id AND state<>'EXPECTED') samples_received,
                  (SELECT count(*) FROM test_task WHERE commission_id=:id) tasks,
                  (SELECT count(*) FROM test_task WHERE commission_id=:id AND state<>'UNASSIGNED') tasks_assigned,
                  (SELECT count(*) FROM test_task WHERE commission_id=:id AND state='COMPLETED') tasks_completed,
                  (SELECT count(*) FROM report WHERE commission_id=:id) reports,
                  (SELECT count(DISTINCT r.id) FROM report r JOIN report_version v ON v.report_id=r.id
                    JOIN report_approval a ON a.report_version_id=v.id
                    WHERE r.commission_id=:id AND a.step='REVIEW' AND a.decision='APPROVED') reports_approved,
                  (SELECT count(DISTINCT r.id) FROM report r JOIN report_version v ON v.report_id=r.id
                    WHERE r.commission_id=:id AND v.signed_at IS NOT NULL) reports_issued,
                  (SELECT count(DISTINCT a.report_id) FROM archive_package a JOIN report r ON r.id=a.report_id
                    WHERE r.commission_id=:id) archived,
                  (SELECT count(*) FROM nonconformance WHERE commission_id=:id AND state NOT IN ('CLOSED','REJECTED')) open_nc
                """).param("id", commissionId).query((rs, row) -> new Counts(rs.getLong("samples"), rs.getLong("samples_received"),
                        rs.getLong("tasks"), rs.getLong("tasks_assigned"), rs.getLong("tasks_completed"),
                        rs.getLong("reports"), rs.getLong("reports_approved"), rs.getLong("reports_issued"),
                        rs.getLong("archived"), rs.getLong("open_nc"))).single();
    }

    private List<Step> steps(CommissionRow commission, Counts count) {
        int current = switch (commission.state()) {
            case "DRAFT", "PRE_ACCEPTED" -> 0;
            case "ACCEPTED", "SAMPLING" -> 1;
            case "ASSIGNED" -> 2;
            case "TESTING" -> 3;
            case "REPORTING" -> count.reports() == 0 ? 4 : 5;
            case "ISSUED" -> 6;
            case "ARCHIVED" -> 7;
            default -> 0;
        };
        String[] keys = {"commission", "sample", "task", "test", "editing", "review", "issue", "archive"};
        String[] labels = {"委托受理", "样品接收", "任务派发", "试验检测", "报告编制", "报告审核", "报告签发", "发放归档"};
        long[] done = {1, count.samplesReceived(), count.tasksAssigned(), count.tasksCompleted(), count.reports(),
                count.reportsApproved(), count.reportsIssued(), count.archived()};
        long[] totals = {1, Math.max(1, count.samples()), Math.max(1, count.tasks()), Math.max(1, count.tasks()),
                1, 1, 1, 1};
        List<Step> result = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            String state = i < current ? "COMPLETED" : i == current ? (count.openNc() > 0 ? "BLOCKED" : "ACTIVE") : "PENDING";
            result.add(new Step(keys[i], labels[i], state, done[i], totals[i],
                    i < current ? commission.updatedAt().toLocalDate().toString() : null));
        }
        return result;
    }

    private List<Evidence> evidence(UUID commissionId, Counts count) {
        long validEquipment = jdbc.sql("""
                SELECT count(DISTINCT e.id) FROM equipment e JOIN equipment_certificate c ON c.equipment_id=e.id
                WHERE e.organization_id=:organization AND c.result IN ('PASS','LIMITED') AND c.valid_until>=current_date
                """).param("organization", actor.require().organizationId()).query(Long.class).single();
        return List.of(
                new Evidence("委托与客户资料", "COMPLETE", 2, 2),
                new Evidence("样品接收与交接记录", count.samplesReceived() >= count.samples() ? "COMPLETE" : "PARTIAL", count.samplesReceived(), Math.max(1, count.samples())),
                new Evidence("检测任务与原始记录", count.tasksCompleted() == count.tasks() ? "COMPLETE" : "PARTIAL", count.tasksCompleted(), Math.max(1, count.tasks())),
                new Evidence("有效设备证书", validEquipment > 0 ? "COMPLETE" : "MISSING", validEquipment, 1));
    }

    private List<Task> tasks(UUID commissionId) {
        return jdbc.sql("""
                SELECT t.id, p.name project_name, s.number sample_number, COALESCE(pe.name,'待分配') assignee,
                  t.due_at, t.state FROM test_task t JOIN commission c ON c.id=t.commission_id
                  JOIN project p ON p.id=c.project_id JOIN sample s ON s.id=t.sample_id
                  LEFT JOIN person pe ON pe.id=t.assigned_to WHERE t.commission_id=:id
                ORDER BY CASE WHEN t.state='COMPLETED' THEN 1 ELSE 0 END, t.due_at NULLS LAST,t.id LIMIT 8
                """).param("id", commissionId).query((rs, row) -> new Task(rs.getObject("id", UUID.class).toString(),
                        rs.getString("project_name"), rs.getString("sample_number"), rs.getString("assignee"),
                        rs.getObject("due_at", OffsetDateTime.class) == null ? "—" : rs.getObject("due_at", OffsetDateTime.class).toLocalDate().toString(),
                        stateLabel(rs.getString("state")))).list();
    }

    private List<Timeline> timeline(UUID commissionId) {
        return jdbc.sql("""
                SELECT action, COALESCE(actor_name,'系统') actor, occurred_at FROM audit_event
                WHERE resource_id=:resource OR resource_id IN (
                  SELECT id::text FROM test_task WHERE commission_id=:id UNION SELECT id::text FROM sample WHERE commission_id=:id)
                ORDER BY occurred_at DESC,id DESC LIMIT 6
                """).param("resource", commissionId.toString()).param("id", commissionId)
                .query((rs, row) -> new Timeline(actionLabel(rs.getString("action")), rs.getString("actor"),
                        format(rs.getObject("occurred_at", OffsetDateTime.class)), row == 0)).list();
    }

    private JdbcClient.StatementSpec scoped(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
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

    private String stateLabel(String state) {
        return switch (state) {
            case "COMPLETED" -> "已完成";
            case "IN_PROGRESS" -> "进行中";
            case "ACCEPTED" -> "待检测";
            case "RETURNED" -> "已退回";
            case "UNASSIGNED" -> "待派发";
            default -> "待处理";
        };
    }

    private String reportActionLabel(String state) {
        return switch (state) {
            case "IN_REVIEW" -> "审核";
            case "APPROVED" -> "签发";
            case "ISSUED" -> "发放";
            default -> "编制";
        };
    }

    private String reportPath(String state) {
        return switch (state) {
            case "IN_REVIEW" -> "/modules/report-review";
            case "APPROVED" -> "/modules/report-issue";
            case "ISSUED" -> "/modules/report-distribution";
            default -> "/modules/report-editing";
        };
    }

    private String todoStateLabel(String state) {
        return switch (state) {
            case "UNASSIGNED" -> "待派发";
            case "ASSIGNED" -> "待接收";
            case "ACCEPTED" -> "待检测";
            case "IN_PROGRESS" -> "检测中";
            case "RETURNED" -> "已退回";
            case "SUBMITTED" -> "待校核";
            case "IN_REVIEW" -> "待审核";
            case "APPROVED" -> "待签发";
            case "ISSUED" -> "待发放";
            case "MISSING" -> "缺少有效证书";
            case "DUE" -> "30 天内到期或已逾期";
            default -> "待处理";
        };
    }

    static String actionLabel(String action) {
        if (action == null || action.isBlank()) return "业务记录已更新";
        String exact = switch (action) {
            case "COMMISSION_ARCHIVE" -> "委托归档完成";
            case "COMMISSION_MARK_ISSUED" -> "报告签发阶段完成";
            case "COMMISSION_COMPLETE_TESTING" -> "试验检测阶段完成";
            case "TASK_ASSIGN" -> "检测任务已派发";
            case "TASK_ACCEPT" -> "检测任务已接收";
            case "TASK_START" -> "试验检测开始";
            case "SAMPLE_RECEIVE" -> "样品接收完成";
            case "SAMPLE_HANDOVER" -> "样品交接完成";
            case "REPORT_APPROVE" -> "报告审核通过";
            case "REPORT_ISSUE" -> "报告已签发";
            case "REPORT_ARCHIVE" -> "报告已归档";
            case "LOGIN_SUCCESS" -> "登录成功";
            case "LOGOUT" -> "退出登录";
            case "PASSWORD_CHANGED" -> "登录密码已修改";
            case "PASSWORD_REVERIFIED" -> "高风险操作身份复核通过";
            case "TASK_SAFETY_BRIEFING_STARTED" -> "任务安全交底已开始";
            case "TASK_SAFETY_BRIEFING_ACKNOWLEDGED" -> "任务安全交底已确认";
            case "TASK_SAFETY_BRIEFING_EARLY_ACK_REJECTED" -> "任务安全交底提前确认被拒绝";
            case "TASK_ACCEPTANCE_BRIEFING_REJECTED" -> "任务接收被安全交底阻断";
            default -> null;
        };
        if (exact != null) return exact;
        String resource = resourceActionPrefix(action);
        String operation = operationLabel(action);
        return resource == null || operation == null ? "业务记录已更新" : resource + operation;
    }

    private static String resourceActionPrefix(String action) {
        if (action == null) return null;
        if (action.startsWith("BUSINESS_NUMBERING_")) return "业务编号规则";
        if (action.startsWith("COMMISSION_CHANGE_")) return "委托变更";
        if (action.startsWith("COMMISSION_")) return "委托";
        if (action.startsWith("CORRECTIVE_ACTION_")) return "处置措施";
        if (action.startsWith("DATA_DICTIONARY_")) return "数据字典";
        if (action.startsWith("DATA_CHANGE_")) return "数据修改申请";
        if (action.startsWith("DETECTION_CATALOG_")) return "检测目录";
        if (action.startsWith("DETECTION_STANDARD")) return "检测标准";
        if (action.startsWith("DOCUMENT_TEMPLATE_")) return "业务模板";
        if (action.startsWith("EQUIPMENT_INSPECTION_")) return "设备送检";
        if (action.startsWith("EQUIPMENT_")) return "设备";
        if (action.startsWith("METROLOGY_QUALIFICATION_")) return "计量资质";
        if (action.startsWith("NONCONFORMANCE_")) return "不符合项";
        if (action.startsWith("NUMBERING_FLOW_")) return "流水规则";
        if (action.startsWith("ORGANIZATION_")) return "单位信息";
        if (action.startsWith("PERSON_QUALIFICATION_")) return "人员资格";
        if (action.startsWith("PERSON_TITLE_")) return "人员职称";
        if (action.startsWith("PERSONNEL_")) return "人员信息";
        if (action.startsWith("PROJECT_TEAM_")) return "项目组";
        if (action.startsWith("PROJECT_")) return "工程";
        if (action.startsWith("QUALITY_EXCEPTION_")) return "质量例外";
        if (action.startsWith("REPORT_")) return "报告";
        if (action.startsWith("ROLE_")) return "角色策略";
        if (action.startsWith("SAFETY_BRIEFING_")) return "安全交底";
        if (action.startsWith("SAMPLE_SHELF_")) return "样品货架";
        if (action.startsWith("SAMPLE_")) return "样品";
        if (action.startsWith("SIGNATURE_" ) || action.startsWith("SIGNING_")) return "签章配置";
        if (action.startsWith("TASK_")) return "检测任务";
        if (action.startsWith("TEMPLATE_NUMBERING_")) return "模板编号";
        if (action.startsWith("CONTRACT_")) return "合同";
        if (action.startsWith("PAYMENT_")) return "付款记录";
        if (action.startsWith("RECEIPT_")) return "收款记录";
        return null;
    }

    private static String operationLabel(String action) {
        if (action == null) return null;
        String[][] values = {
                {"_EXPORT_COMPLETED", "导出已完成"}, {"_EXPORT_QUEUED", "导出已排队"},
                {"_AUTO_RETIRED", "已自动停用"}, {"_ACKNOWLEDGED", "已确认"},
                {"_SUBMIT_VERIFICATION", "已提交验证"}, {"_START_DISPOSITION", "已开始处置"},
                {"_REACTIVATE", "已重新启用"}, {"_DEACTIVATE", "已停用"},
                {"_ACTIVATE", "已启用"}, {"_DISABLE", "已停用"}, {"_ENABLE", "已启用"},
                {"_CREATED", "已新增"}, {"_UPDATED", "已修改"}, {"_REVISED", "已创建修订"},
                {"_PUBLISH", "已发布"}, {"_RETIRE", "已停用"}, {"_DISCARD", "已废弃草稿"},
                {"_SUBMIT", "已提交"}, {"_WITHDRAW", "已撤回"}, {"_APPROVE", "已批准"},
                {"_REJECT", "已拒绝"}, {"_RESTORE", "已恢复"}, {"_CONFIRM", "已确认"},
                {"_RETURN", "已归还"}, {"_RECEIVE", "已接收"}, {"_COMPLETE", "已完成"},
                {"_VERIFY", "已验证"}, {"_ARCHIVE", "已归档"}, {"_ISSUE", "已签发"},
                {"_DISTRIBUTED", "已发放"}, {"_QUEUED", "已排队"}, {"_RESET", "已重置"},
                {"_STORED", "已保存"}, {"_DOWNLOADED", "已下载"}
        };
        for (String[] value : values) if (action.endsWith(value[0])) return value[1];
        return null;
    }

    private static String resourceTypeLabel(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) return "业务记录";
        return switch (resourceType) {
            case "COMMISSION" -> "委托";
            case "SAMPLE" -> "样品";
            case "TEST_TASK" -> "检测任务";
            case "TEST_RECORD" -> "试验记录";
            case "REPORT" -> "报告";
            case "EQUIPMENT", "EQUIPMENT_CERTIFICATE" -> "设备";
            case "PERSON", "PERSONNEL" -> "人员";
            case "ROLE" -> "角色";
            default -> "业务记录";
        };
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String format(OffsetDateTime value) {
        return value == null ? "—" : value.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private record CommissionRow(UUID id, String number, String projectName, String customerName,
                                 LocalDate commissionedOn, LocalDate dueOn, String contact, String state,
                                 OffsetDateTime updatedAt) {}
    private record WorkbenchCommission(String kind, UUID id, String number, String projectName,
                                       String customerName, String contact, LocalDate commissionedOn,
                                       LocalDate dueOn, String state, OffsetDateTime updatedAt,
                                       String detectionProjects, String flowStatus) {}
    private record CommissionQueues(List<CommissionQueueItem> pending, CommissionRow spotlight) {}
    private record Counts(long samples, long samplesReceived, long tasks, long tasksAssigned, long tasksCompleted,
                          long reports, long reportsApproved, long reportsIssued, long archived, long openNc) {}
    private record StageTotals(long preTotal, long prePending, long commissionTotal, long commissionPending,
                               long taskTotal, long taskPending, long testPending, long reportTotal,
                               long editing, long reviewing, long issuing, long distributing) {}
    private record AlertTotals(long personTotal, long personWarning, long organizationTotal,
                               long organizationWarning, long equipmentTotal, long equipmentWarning,
                               long testTotal, long testWarning) {}
    private record TodoDefinition(String key, String label, String path, String action) {}
    private record TodoRow(String key, UUID id, String number, String title, String secondary,
                           String state, OffsetDateTime dueAt, long total) {}
}
