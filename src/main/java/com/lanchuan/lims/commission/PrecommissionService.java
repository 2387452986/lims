package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
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
public class PrecommissionService {
    private static final UUID NO_ID = new UUID(0, 0);
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final CommissionService commissions;
    private final AuditService audit;

    public PrecommissionService(JdbcClient jdbc, CurrentActor actor, CommissionService commissions, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.commissions = commissions;
        this.audit = audit;
    }

    public record View(UUID id, String precommissionNumber, String commissionNumber, UUID customerId,
                       String customerName, UUID projectId, String projectNumber, String projectName,
                       String detectionProjects, LocalDate commissionedOn, String sampleQuantity,
                       LocalDate moldingDate, LocalDate expectedTestDate, String samplingStatus,
                       String commissionState, String printStatus, String acceptedBy, String entruster,
                       OffsetDateTime submittedAt, long version, OffsetDateTime updatedAt,
                       List<String> allowedActions) {}
    public record BatchTarget(UUID id, long expectedVersion) {}
    public record BatchResult(List<TransitionResult> results) {}
    public record ScanReadiness(boolean enabled, String code, String reason) {}

    public CursorPage<View> list(LocalDate from, LocalDate to, String state, String detectionProject,
                                 String precommissionNumber, String commissionNumber, String customer,
                                 String entruster, String projectNumber, String projectName,
                                 String cursorValue, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        AuthenticatedUser user = actor.require();
        String scope = scopeClause();
        String filters = """
                  AND c.state IN ('PRE_ACCEPTED','ACCEPTED','REJECTED')
                  AND (CAST(:from AS date) IS NULL OR c.commissioned_on>=CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR c.commissioned_on<=CAST(:to AS date))
                  AND (:state='' OR c.state=:state)
                  AND (:detection='' OR EXISTS(SELECT 1 FROM commission_detection_selection ds
                    WHERE ds.commission_id=c.id AND lower(ds.project_name_snapshot) LIKE :detection))
                  AND (:precommission='' OR lower(COALESCE(c.precommission_number,'')) LIKE :precommission)
                  AND (:commission='' OR lower(c.number) LIKE :commission)
                  AND (:customer='' OR lower(customer.name) LIKE :customer)
                  AND (:entruster='' OR lower(c.contact_name) LIKE :entruster)
                  AND (:projectNumber='' OR lower(project.number) LIKE :projectNumber)
                  AND (:projectName='' OR lower(project.name) LIKE :projectName)
                """;
        var statement = jdbc.sql(baseSelect() + " WHERE c.organization_id=:organization " + scope + filters + """
                  AND (COALESCE(c.submitted_at,c.updated_at),c.id)<(:cursorAt,:cursorId)
                ORDER BY COALESCE(c.submitted_at,c.updated_at) DESC,c.id DESC LIMIT :limit
                """);
        bind(statement, user, from, to, state, detectionProject, precommissionNumber, commissionNumber,
                customer, entruster, projectNumber, projectName);
        List<View> fetched = statement.param("cursorAt", cursorAt).param("cursorId", cursorId)
                .param("limit", limit + 1).query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? new PageCursor(items.getLast().submittedAt() == null
                ? items.getLast().updatedAt() : items.getLast().submittedAt(), items.getLast().id()).encode() : null;
        var countStatement = jdbc.sql("""
                SELECT count(*) FROM commission c
                JOIN project project ON project.id=c.project_id
                JOIN customer customer ON customer.id=c.customer_id
                WHERE c.organization_id=:organization
                """ + scope + filters);
        bind(countStatement, user, from, to, state, detectionProject, precommissionNumber, commissionNumber,
                customer, entruster, projectNumber, projectName);
        long total = countStatement.query(Long.class).single();
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(baseSelect() + " WHERE c.id=:id AND c.organization_id=:organization " + scopeClause())
                .param("id", id).param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
        return statement.query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("PRECOMMISSION_NOT_FOUND", "预委托不存在或不在当前数据范围内"));
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (!before.allowedActions().contains(command.action().trim().toUpperCase())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECOMMISSION_ACTION_INVALID",
                    "当前预委托状态不允许该操作");
        }
        return commissions.transition(id, command);
    }

    @Transactional
    public BatchResult batchAccept(List<BatchTarget> targets, String reasonCode, String comment) {
        if (targets == null || targets.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PRECOMMISSION_BATCH_EMPTY", "请至少选择一条待受理委托");
        if (targets.size() > 200) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PRECOMMISSION_BATCH_TOO_LARGE", "单次批量受理不得超过200条");
        List<TransitionResult> results = new ArrayList<>();
        for (BatchTarget target : targets) {
            View value = require(target.id());
            if (!"PRE_ACCEPTED".equals(value.commissionState())) throw new ApiException(HttpStatus.CONFLICT,
                    "PRECOMMISSION_BATCH_STATE_INVALID", value.precommissionNumber() + " 不是待受理状态");
            results.add(commissions.transition(target.id(),
                    new TransitionCommand("ACCEPT", target.expectedVersion(), reasonCode, comment)));
        }
        audit.record("PRECOMMISSION_BATCH_ACCEPTED", "COMMISSION", null, null,
                java.util.Map.of("count", results.size()), reasonCode, comment);
        return new BatchResult(List.copyOf(results));
    }

    @Transactional
    public View markPrinted(UUID id, long expectedVersion) {
        View before = require(id);
        if (!List.of("PRE_ACCEPTED", "ACCEPTED").contains(before.commissionState())) {
            throw ApiException.blocked("PRECOMMISSION_PRINT_STATE_INVALID",
                    "当前预委托状态不允许打印受理单", before.version(),
                    List.of("当前状态：" + before.commissionState()));
        }
        if (before.version() != expectedVersion) throw ApiException.conflict("VERSION_CONFLICT",
                "预委托已被其他用户更新", before.version());
        int changed = jdbc.sql("""
                UPDATE commission SET print_status='PRINTED',version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("version", expectedVersion).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "预委托已被其他用户更新",
                require(id).version());
        View after = require(id);
        audit.record("PRECOMMISSION_PRINTED", "COMMISSION", id, before, after, null, "受控预委托受理单");
        return after;
    }

    public ScanReadiness scanReadiness() {
        return new ScanReadiness(false, "SCAN_INTAKE_NOT_CONFIGURED",
                "一期未配置二维码、条码、扫码枪或打印硬件协议；手工受理流程可正常使用。");
    }

    private void bind(JdbcClient.StatementSpec statement, AuthenticatedUser user, LocalDate from, LocalDate to,
                      String state, String detectionProject, String precommissionNumber, String commissionNumber,
                      String customer, String entruster, String projectNumber, String projectName) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("from", from).param("to", to).param("state", upper(state))
                .param("detection", like(detectionProject)).param("precommission", like(precommissionNumber))
                .param("commission", like(commissionNumber)).param("customer", like(customer))
                .param("entruster", like(entruster)).param("projectNumber", like(projectNumber))
                .param("projectName", like(projectName));
    }

    private String baseSelect() {
        return """
                SELECT c.id,c.precommission_number,c.number,c.customer_id,customer.name customer_name,
                  c.project_id,project.number project_number,project.name project_name,
                  COALESCE(selection.projects,'') detection_projects,c.commissioned_on,
                  COALESCE(c.sample_quantity,groups.quantity) sample_quantity,c.molding_date,c.expected_test_date,
                  c.sampling_status,c.state,c.print_status,acceptor.display_name accepted_by,c.contact_name entruster,
                  c.submitted_at,c.version,c.updated_at
                FROM commission c
                JOIN project project ON project.id=c.project_id
                JOIN customer customer ON customer.id=c.customer_id
                LEFT JOIN app_user acceptor ON acceptor.id=c.accepted_by
                LEFT JOIN LATERAL (SELECT string_agg(DISTINCT ds.project_name_snapshot,'、'
                    ORDER BY ds.project_name_snapshot) projects
                  FROM commission_detection_selection ds WHERE ds.commission_id=c.id) selection ON true
                LEFT JOIN LATERAL (SELECT sum(g.quantity) quantity FROM commission_sample_group g
                  WHERE g.commission_id=c.id) groups ON true
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getString("precommission_number"),
                rs.getString("number"), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getObject("project_id", UUID.class), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("detection_projects"), rs.getObject("commissioned_on", LocalDate.class),
                decimal(rs.getBigDecimal("sample_quantity")), rs.getObject("molding_date", LocalDate.class),
                rs.getObject("expected_test_date", LocalDate.class), rs.getString("sampling_status"), state,
                rs.getString("print_status"), rs.getString("accepted_by"), rs.getString("entruster"),
                rs.getObject("submitted_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), allowed(state));
    }

    private List<String> allowed(String state) {
        if ("PRE_ACCEPTED".equals(state)) return List.of("ACCEPT", "REJECT", "WITHDRAW");
        return List.of();
    }

    private String scopeClause() {
        return """
                 AND (:scope IN ('ALL','ORGANIZATION')
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=c.created_by AND creator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM test_task t JOIN person pe ON pe.id=t.assigned_to
                      WHERE t.commission_id=c.id AND pe.department_id=:department)))
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm
                    WHERE pm.project_id=c.project_id AND pm.user_id=:actor))
                  OR (:scope='SELF' AND (c.created_by=:actor OR EXISTS(SELECT 1 FROM test_task t
                    WHERE t.commission_id=c.id AND t.assigned_to=:person))))
                """;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private String upper(String value) { return text(value).toUpperCase(); }
    private String like(String value) { return "%" + text(value).toLowerCase() + "%"; }
    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
