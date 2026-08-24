package com.lanchuan.lims.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationProjectionService {
    private static final long PROJECTION_LOCK = 711151851234567890L;
    private static final int CANDIDATE_LIMIT = 200;
    private final JdbcClient jdbc;

    public NotificationProjectionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ProjectionResult(boolean lockAcquired, int ruleCount, int candidateCount, int deliveryCount) {}

    private record Rule(UUID seriesId, UUID revisionId, UUID organizationId, String eventType,
                        String label, String recipientKind, int conditionDays) {}

    private record Candidate(UUID resourceId, String resourceType, long resourceVersion, UUID organizationId,
                             UUID commissionId, UUID projectId, UUID creatorUserId, UUID creatorDepartmentId,
                             UUID assignedPersonId, UUID assignedDepartmentId, UUID primaryUserId,
                             UUID custodianPersonId, UUID custodianDepartmentId, String requiredPermission,
                             String number, String name, LocalDate dueOn) {}

    private record Account(UUID userId, UUID personId, UUID departmentId, String dataScope,
                           Set<String> permissions, Set<UUID> projectIds) {}

    @Transactional
    public ProjectionResult projectAll() {
        boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", PROJECTION_LOCK).query(Boolean.class).single();
        if (!acquired) return new ProjectionResult(false, 0, 0, 0);
        List<Rule> rules = jdbc.sql("""
                SELECT series.id series_id,revision.id revision_id,series.organization_id,series.event_type,
                  event.label,event.selectable_recipient_kind,revision.condition_days
                FROM notification_rule_series series
                JOIN notification_rule_revision revision ON revision.series_id=series.id AND revision.state='ACTIVE'
                JOIN notification_event_type event ON event.code=series.event_type
                ORDER BY series.organization_id,event.sort_order
                """).query((rs, row) -> new Rule(rs.getObject("series_id", UUID.class),
                        rs.getObject("revision_id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getString("event_type"), rs.getString("label"),
                        rs.getString("selectable_recipient_kind"), rs.getInt("condition_days"))).list();
        Map<UUID, Map<UUID, Account>> accountsByOrganization = new LinkedHashMap<>();
        int candidates = 0;
        int deliveries = 0;
        for (Rule rule : rules) {
            List<Candidate> pending = candidates(rule);
            candidates += pending.size();
            Map<UUID, Account> accounts = accountsByOrganization.computeIfAbsent(
                    rule.organizationId(), this::accounts);
            Set<UUID> configured = configuredRecipients(rule.revisionId());
            int produced = 0;
            for (Candidate candidate : pending) {
                Set<UUID> recipients = recipients(rule, candidate, configured, accounts);
                for (UUID recipient : recipients) produced += insert(rule, candidate, recipient);
            }
            deliveries += produced;
            if (produced > 0) audit(rule, pending.size(), produced);
        }
        return new ProjectionResult(true, rules.size(), candidates, deliveries);
    }

    private List<Candidate> candidates(Rule rule) {
        return switch (rule.eventType()) {
            case "COMMISSION_PENDING_SUBMISSION" -> jdbc.sql("""
                    SELECT commission.id resource_id,'COMMISSION' resource_type,commission.version resource_version,
                      commission.organization_id,commission.id commission_id,commission.project_id,
                      commission.created_by creator_user_id,creator.department_id creator_department_id,
                      NULL::uuid assigned_person_id,NULL::uuid assigned_department_id,
                      commission.created_by primary_user_id,NULL::uuid custodian_person_id,
                      NULL::uuid custodian_department_id,NULL::varchar required_permission,
                      commission.number,project.name,NULL::date due_on
                    FROM commission JOIN project ON project.id=commission.project_id
                    JOIN app_user creator ON creator.id=commission.created_by
                    WHERE commission.organization_id=:organization AND commission.state='DRAFT'
                      AND commission.updated_at<=now()-make_interval(days=>:days)
                    ORDER BY commission.updated_at,commission.id LIMIT :limit
                    """).param("organization", rule.organizationId()).param("days", rule.conditionDays())
                    .param("limit", CANDIDATE_LIMIT).query(this::mapCandidate).list();
            case "TASK_PENDING_ASSIGNMENT" -> taskCandidates(rule, "UNASSIGNED", false);
            case "TASK_PENDING_ACCEPTANCE" -> taskCandidates(rule, "ASSIGNED", true);
            case "TASK_PENDING_TEST" -> taskCandidates(rule, "ACCEPTED", true);
            case "REPORT_PENDING_DRAFT" -> jdbc.sql("""
                    SELECT commission.id resource_id,'COMMISSION' resource_type,commission.version resource_version,
                      commission.organization_id,commission.id commission_id,commission.project_id,
                      commission.created_by creator_user_id,creator.department_id creator_department_id,
                      NULL::uuid assigned_person_id,NULL::uuid assigned_department_id,
                      commission.created_by primary_user_id,NULL::uuid custodian_person_id,
                      NULL::uuid custodian_department_id,NULL::varchar required_permission,
                      commission.number,project.name,NULL::date due_on
                    FROM commission JOIN project ON project.id=commission.project_id
                    JOIN app_user creator ON creator.id=commission.created_by
                    WHERE commission.organization_id=:organization AND commission.state='REPORTING'
                      AND commission.updated_at<=now()-make_interval(days=>:days)
                      AND NOT EXISTS(SELECT 1 FROM report WHERE report.commission_id=commission.id
                        AND report.state<>'VOID')
                    ORDER BY commission.updated_at,commission.id LIMIT :limit
                    """).param("organization", rule.organizationId()).param("days", rule.conditionDays())
                    .param("limit", CANDIDATE_LIMIT).query(this::mapCandidate).list();
            case "REPORT_PENDING_CHECK" -> reportCandidates(rule, List.of("DRAFT", "RETURNED"), "REPORT_REVIEW");
            case "REPORT_PENDING_REVIEW" -> reportCandidates(rule, List.of("IN_REVIEW"), "REPORT_REVIEW");
            case "REPORT_PENDING_ISSUE" -> reportCandidates(rule, List.of("APPROVED"), "REPORT_ISSUE");
            case "EQUIPMENT_VERIFICATION" -> jdbc.sql("""
                    WITH latest AS (
                      SELECT DISTINCT ON (certificate.equipment_id)
                        certificate.id,certificate.equipment_id,certificate.valid_until
                      FROM equipment_certificate certificate
                      WHERE certificate.certificate_type='VERIFICATION'
                        AND certificate.result IN ('PASS','LIMITED')
                      ORDER BY certificate.equipment_id,certificate.issued_on DESC,certificate.created_at DESC,certificate.id
                    )
                    SELECT latest.id resource_id,'EQUIPMENT_CERTIFICATE' resource_type,0::bigint resource_version,
                      equipment.organization_id,NULL::uuid commission_id,NULL::uuid project_id,
                      NULL::uuid creator_user_id,NULL::uuid creator_department_id,
                      NULL::uuid assigned_person_id,NULL::uuid assigned_department_id,
                      NULL::uuid primary_user_id,equipment.custodian_id custodian_person_id,
                      custodian.department_id custodian_department_id,NULL::varchar required_permission,
                      equipment.code number,equipment.name,latest.valid_until due_on
                    FROM latest JOIN equipment ON equipment.id=latest.equipment_id
                    LEFT JOIN person custodian ON custodian.id=equipment.custodian_id
                    WHERE equipment.organization_id=:organization
                      AND equipment.state NOT IN ('SCRAPPED','OUT_OF_SERVICE')
                      AND latest.valid_until<=current_date+:days
                    ORDER BY latest.valid_until,equipment.id LIMIT :limit
                    """).param("organization", rule.organizationId()).param("days", rule.conditionDays())
                    .param("limit", CANDIDATE_LIMIT).query(this::mapCandidate).list();
            case "SAMPLE_RETENTION_CLEANUP" -> jdbc.sql("""
                    SELECT sample.id resource_id,'SAMPLE' resource_type,sample.version resource_version,
                      commission.organization_id,commission.id commission_id,commission.project_id,
                      commission.created_by creator_user_id,creator.department_id creator_department_id,
                      NULL::uuid assigned_person_id,NULL::uuid assigned_department_id,
                      commission.created_by primary_user_id,NULL::uuid custodian_person_id,
                      NULL::uuid custodian_department_id,NULL::varchar required_permission,
                      sample.number,sample.name,
                      (sample.updated_at::date+COALESCE(retention.days,0)) due_on
                    FROM sample JOIN commission ON commission.id=sample.commission_id
                    JOIN app_user creator ON creator.id=commission.created_by
                    LEFT JOIN LATERAL (
                      SELECT max(COALESCE(NULLIF(selection.snapshot_json->>'retentionDays','')::integer,0)) days
                      FROM commission_parameter_selection selection
                      WHERE selection.sample_group_id=sample.sample_group_id
                    ) retention ON true
                    WHERE commission.organization_id=:organization AND sample.state='RETAINED'
                      AND sample.updated_at::date+COALESCE(retention.days,0)<=current_date+:days
                    ORDER BY due_on,sample.id LIMIT :limit
                    """).param("organization", rule.organizationId()).param("days", rule.conditionDays())
                    .param("limit", CANDIDATE_LIMIT).query(this::mapCandidate).list();
            default -> List.of();
        };
    }

    private List<Candidate> taskCandidates(Rule rule, String state, boolean assignedPrimary) {
        return jdbc.sql("""
                SELECT task.id resource_id,'TEST_TASK' resource_type,task.version resource_version,
                  commission.organization_id,commission.id commission_id,commission.project_id,
                  commission.created_by creator_user_id,creator.department_id creator_department_id,
                  task.assigned_to assigned_person_id,assignee.department_id assigned_department_id,
                  CASE WHEN :assignedPrimary THEN assigned_account.id ELSE NULL END primary_user_id,
                  NULL::uuid custodian_person_id,NULL::uuid custodian_department_id,
                  NULL::varchar required_permission,task.number,parameter.name,NULL::date due_on
                FROM test_task task JOIN commission ON commission.id=task.commission_id
                JOIN app_user creator ON creator.id=commission.created_by
                JOIN test_parameter parameter ON parameter.id=task.parameter_id
                LEFT JOIN person assignee ON assignee.id=task.assigned_to
                LEFT JOIN app_user assigned_account ON assigned_account.person_id=task.assigned_to
                WHERE commission.organization_id=:organization AND task.state=:state
                  AND task.updated_at<=now()-make_interval(days=>:days)
                ORDER BY task.updated_at,task.id LIMIT :limit
                """).param("assignedPrimary", assignedPrimary).param("organization", rule.organizationId())
                .param("state", state).param("days", rule.conditionDays()).param("limit", CANDIDATE_LIMIT)
                .query(this::mapCandidate).list();
    }

    private List<Candidate> reportCandidates(Rule rule, List<String> states, String permission) {
        return jdbc.sql("""
                SELECT report.id resource_id,'REPORT' resource_type,report.version resource_version,
                  commission.organization_id,commission.id commission_id,commission.project_id,
                  commission.created_by creator_user_id,creator.department_id creator_department_id,
                  NULL::uuid assigned_person_id,NULL::uuid assigned_department_id,
                  NULL::uuid primary_user_id,NULL::uuid custodian_person_id,
                  NULL::uuid custodian_department_id,:permission required_permission,
                  report.number,report.title name,NULL::date due_on
                FROM report JOIN commission ON commission.id=report.commission_id
                JOIN app_user creator ON creator.id=commission.created_by
                WHERE commission.organization_id=:organization AND report.state IN (:states)
                  AND report.updated_at<=now()-make_interval(days=>:days)
                ORDER BY report.updated_at,report.id LIMIT :limit
                """).param("permission", permission).param("organization", rule.organizationId())
                .param("states", states).param("days", rule.conditionDays()).param("limit", CANDIDATE_LIMIT)
                .query(this::mapCandidate).list();
    }

    private Candidate mapCandidate(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Candidate(rs.getObject("resource_id", UUID.class), rs.getString("resource_type"),
                rs.getLong("resource_version"), rs.getObject("organization_id", UUID.class),
                rs.getObject("commission_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("creator_user_id", UUID.class), rs.getObject("creator_department_id", UUID.class),
                rs.getObject("assigned_person_id", UUID.class), rs.getObject("assigned_department_id", UUID.class),
                rs.getObject("primary_user_id", UUID.class), rs.getObject("custodian_person_id", UUID.class),
                rs.getObject("custodian_department_id", UUID.class), rs.getString("required_permission"),
                rs.getString("number"), rs.getString("name"), rs.getObject("due_on", LocalDate.class));
    }

    private Map<UUID, Account> accounts(UUID organizationId) {
        Map<UUID, Set<UUID>> projects = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT member.user_id,member.project_id FROM project_member member
                JOIN app_user account ON account.id=member.user_id
                WHERE account.organization_id=:organization
                """).param("organization", organizationId).query((rs, row) -> {
                    projects.computeIfAbsent(rs.getObject("user_id", UUID.class), ignored -> new LinkedHashSet<>())
                            .add(rs.getObject("project_id", UUID.class));
                    return 0;
                }).list();
        Map<UUID, Account> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT account.id,account.person_id,account.department_id,
                  COALESCE((SELECT role.data_scope FROM app_role role JOIN user_role user_role_row
                    ON user_role_row.role_id=role.id WHERE user_role_row.user_id=account.id
                    ORDER BY CASE role.data_scope WHEN 'ALL' THEN 1 WHEN 'ORGANIZATION' THEN 2
                      WHEN 'DEPARTMENT' THEN 3 WHEN 'PROJECT' THEN 4 ELSE 5 END LIMIT 1),'SELF') data_scope,
                  COALESCE((SELECT string_agg(DISTINCT role_permission.permission_code,','
                    ORDER BY role_permission.permission_code)
                    FROM role_permission JOIN user_role user_role_row ON user_role_row.role_id=role_permission.role_id
                    WHERE user_role_row.user_id=account.id),'') permissions
                FROM app_user account
                WHERE account.organization_id=:organization AND account.enabled
                """).param("organization", organizationId).query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String value = rs.getString("permissions");
                    Set<String> permissions = value == null || value.isBlank()
                            ? Set.of() : Set.copyOf(Arrays.asList(value.split(",")));
                    result.put(id, new Account(id, rs.getObject("person_id", UUID.class),
                            rs.getObject("department_id", UUID.class), rs.getString("data_scope"), permissions,
                            Set.copyOf(projects.getOrDefault(id, Set.of()))));
                    return 0;
                }).list();
        return result;
    }

    private Set<UUID> configuredRecipients(UUID revisionId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT account.id FROM notification_rule_recipient recipient
                JOIN app_user account ON account.person_id=recipient.person_id
                WHERE recipient.revision_id=:revision AND account.enabled
                ORDER BY recipient.sort_order,account.id
                """).param("revision", revisionId).query(UUID.class).list());
    }

    private Set<UUID> recipients(Rule rule, Candidate candidate, Set<UUID> configured,
                                 Map<UUID, Account> accounts) {
        LinkedHashSet<UUID> requested = new LinkedHashSet<>();
        if (rule.recipientKind().equals("PRIMARY")) {
            requested.addAll(configured);
        } else {
            if (candidate.primaryUserId() != null) requested.add(candidate.primaryUserId());
            if (candidate.requiredPermission() != null) accounts.values().stream()
                    .filter(account -> account.permissions().contains(candidate.requiredPermission()))
                    .map(Account::userId).forEach(requested::add);
            requested.addAll(configured);
        }
        requested.removeIf(id -> {
            Account account = accounts.get(id);
            return account == null || !account.permissions().contains("MODULE_READ") || !inScope(account, candidate);
        });
        return requested;
    }

    private boolean inScope(Account account, Candidate candidate) {
        if (Set.of("ALL", "ORGANIZATION").contains(account.dataScope())) return true;
        if (candidate.commissionId() != null) {
            return switch (account.dataScope()) {
                case "DEPARTMENT" -> account.departmentId() != null &&
                        (account.departmentId().equals(candidate.creatorDepartmentId())
                                || account.departmentId().equals(candidate.assignedDepartmentId()));
                case "PROJECT" -> candidate.projectId() != null && account.projectIds().contains(candidate.projectId());
                case "SELF" -> account.userId().equals(candidate.creatorUserId())
                        || account.personId() != null && account.personId().equals(candidate.assignedPersonId());
                default -> false;
            };
        }
        return switch (account.dataScope()) {
            case "DEPARTMENT" -> account.departmentId() != null
                    && account.departmentId().equals(candidate.custodianDepartmentId());
            case "SELF" -> account.personId() != null && account.personId().equals(candidate.custodianPersonId());
            default -> false;
        };
    }

    private int insert(Rule rule, Candidate candidate, UUID recipient) {
        String deadline = candidate.dueOn() == null ? "" : "，到期日 " + candidate.dueOn();
        String body = "%s：%s · %s%s，请及时处理。".formatted(
                rule.label(), candidate.number(), candidate.name(), deadline);
        return jdbc.sql("""
                INSERT INTO notification(id,recipient_id,organization_id,category,title,body,resource_type,
                  resource_id,rule_series_id,rule_revision_id,resource_state_version)
                VALUES (:id,:recipient,:organization,'WORKFLOW',:title,:body,:resourceType,:resourceId,
                  :series,:revision,:resourceVersion)
                ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("recipient", recipient)
                .param("organization", candidate.organizationId()).param("title", rule.label() + "提醒")
                .param("body", body).param("resourceType", candidate.resourceType())
                .param("resourceId", candidate.resourceId()).param("series", rule.seriesId())
                .param("revision", rule.revisionId()).param("resourceVersion", candidate.resourceVersion()).update();
    }

    private void audit(Rule rule, int candidates, int deliveries) {
        jdbc.sql("""
                INSERT INTO audit_event(id,request_id,actor_name,action,resource_type,resource_id,
                  organization_id,after_json,reason_code,comment)
                VALUES (:id,:requestId,'系统通知投影','NOTIFICATION_PROJECTED','NOTIFICATION_RULE_REVISION',
                  :resourceId,:organization,jsonb_build_object('eventType',:eventType,'candidateCount',:candidates,
                  'deliveryCount',:deliveries),'SCHEDULED_PROJECTION','站内消息按活动规则幂等投影')
                """).param("id", UUID.randomUUID()).param("requestId", "notification-worker:" + UUID.randomUUID())
                .param("resourceId", rule.revisionId().toString()).param("organization", rule.organizationId())
                .param("eventType", rule.eventType()).param("candidates", candidates)
                .param("deliveries", deliveries).update();
    }
}
