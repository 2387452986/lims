package com.lanchuan.lims.nonconformance;

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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ExceptionApprovalService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final List<String> RESOURCE_TYPES = List.of("TEST_TASK", "TEST_RECORD");
    private static final List<String> BLOCKER_CODES = List.of("PERSON_NOT_QUALIFIED", "EQUIPMENT_NOT_VALID");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final DataScopeService access;

    public ExceptionApprovalService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                    DataScopeService access) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.access = access;
    }

    public record Create(String resourceType, UUID resourceId, String blockerCode, String reason,
                         OffsetDateTime expiresAt) {}
    public record Decision(String action, long expectedVersion, String comment) {}
    public record View(UUID id, String resourceType, UUID resourceId, String blockerCode, String reason,
                       OffsetDateTime expiresAt, UUID requestedBy, UUID approvedBy, String state,
                       String decisionComment, long version, OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    @Transactional
    public View create(Create command) {
        if (!RESOURCE_TYPES.contains(command.resourceType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCEPTION_RESOURCE_INVALID", "质量例外资源类型无效");
        }
        if (!BLOCKER_CODES.contains(command.blockerCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCEPTION_BLOCKER_INVALID", "质量例外阻断类型无效");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (command.expiresAt() == null || !command.expiresAt().isAfter(now)
                || command.expiresAt().isAfter(now.plusHours(24))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCEPTION_EXPIRY_INVALID", "质量例外有效期必须在未来24小时内");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCEPTION_REASON_REQUIRED", "申请质量例外必须填写原因");
        }
        long active = jdbc.sql("""
                SELECT count(*) FROM exception_approval
                WHERE resource_type=:resourceType AND resource_id=:resourceId AND blocker_code=:blocker
                  AND organization_id=:organization AND state IN ('REQUESTED','APPROVED') AND expires_at>now()
                """).param("resourceType", command.resourceType()).param("resourceId", command.resourceId())
                .param("blocker", command.blockerCode()).param("organization", actor.require().organizationId())
                .query(Long.class).single();
        if (active > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "EXCEPTION_ALREADY_ACTIVE", "该阻断项已有待审或生效中的质量例外");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO exception_approval(id, resource_type, resource_id, blocker_code, reason,
                  expires_at, requested_by, organization_id, state)
                VALUES (:id, :resourceType, :resourceId, :blocker, :reason, :expiresAt, :actor, :organization, 'REQUESTED')
                """).param("id", id).param("resourceType", command.resourceType()).param("resourceId", command.resourceId())
                .param("blocker", command.blockerCode()).param("reason", command.reason().trim())
                .param("expiresAt", command.expiresAt()).param("actor", actor.require().id())
                .param("organization", actor.require().organizationId()).update();
        View created = require(id);
        audit.record("QUALITY_EXCEPTION_REQUESTED", "EXCEPTION_APPROVAL", id, null, created, command.blockerCode(), command.reason());
        return created;
    }

    public CursorPage<View> list(String cursorValue, int requestedLimit) {
        expire();
        AuthenticatedUser user = actor.require();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        String joins = """
                 FROM exception_approval exception
                 LEFT JOIN test_task direct_task ON exception.resource_type='TEST_TASK'
                   AND direct_task.id=exception.resource_id
                 LEFT JOIN test_record record ON exception.resource_type='TEST_RECORD'
                   AND record.id=exception.resource_id
                 LEFT JOIN test_task record_task ON record_task.id=record.task_id
                 JOIN commission commission ON commission.id=COALESCE(direct_task.commission_id,record_task.commission_id)
                """;
        String conditions = " WHERE exception.organization_id=:organization AND " + scope("commission");
        var statement = jdbc.sql(exceptionSelect() + joins + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (exception.created_at,exception.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY exception.created_at DESC,exception.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, cursor).param("fetchLimit", limit + 1);
        List<View> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*)" + joins + conditions);
        bind(count, user, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().createdAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        expire();
        View view = jdbc.sql(baseSelect() + " WHERE id=:id AND organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("EXCEPTION_NOT_FOUND", "质量例外申请不存在"));
        requireAccess(view);
        return view;
    }

    @Transactional
    public TransitionResult decide(UUID id, Decision command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "质量例外申请已被更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        String next = switch (before.state()) {
            case "REQUESTED" -> switch (action) {
                case "APPROVE" -> "APPROVED";
                case "REJECT" -> "REJECTED";
                default -> invalid(before.state(), action);
            };
            case "APPROVED" -> action.equals("REVOKE") ? "REVOKED" : invalid(before.state(), action);
            default -> invalid(before.state(), action);
        };
        if (action.equals("APPROVE") && before.requestedBy().equals(actor.require().id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INDEPENDENT_EXCEPTION_APPROVER_REQUIRED",
                    "质量例外申请人不能审批自己的申请");
        }
        if (!action.equals("APPROVE") && (command.comment() == null || command.comment().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCEPTION_DECISION_COMMENT_REQUIRED", "拒绝或撤销必须填写说明");
        }
        int updated = jdbc.sql("""
                UPDATE exception_approval SET state=:state, approved_by=CASE WHEN :state='APPROVED' THEN :actor ELSE approved_by END,
                  decision_comment=:comment, decided_at=now(), version=version+1
                WHERE id=:id AND version=:version
                """).param("state", next).param("actor", actor.require().id()).param("comment", command.comment())
                .param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "质量例外申请已被更新", require(id).version());
        View after = require(id);
        audit.record("QUALITY_EXCEPTION_" + action, "EXCEPTION_APPROVAL", id, before, after, before.blockerCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.state().equals("APPROVED") ? List.of("REVOKE") : List.of());
    }

    public boolean allows(String resourceType, UUID resourceId, String blockerCode) {
        View approval = jdbc.sql(baseSelect() + """
                 WHERE resource_type=:resourceType AND resource_id=:resourceId AND blocker_code=:blocker
                   AND organization_id=:organization AND state='APPROVED' AND expires_at>now()
                 ORDER BY decided_at DESC LIMIT 1
                """).param("resourceType", resourceType).param("resourceId", resourceId).param("blocker", blockerCode)
                .param("organization", actor.require().organizationId())
                .query(this::map).optional().orElse(null);
        if (approval == null) return false;
        audit.record("QUALITY_EXCEPTION_USED", resourceType, resourceId, null,
                java.util.Map.of("approvalId", approval.id(), "expiresAt", approval.expiresAt()), blockerCode, approval.reason());
        return true;
    }

    public void expire() {
        jdbc.sql("""
                UPDATE exception_approval SET state='EXPIRED', version=version+1
                WHERE state IN ('REQUESTED','APPROVED') AND expires_at<=now()
                """).update();
    }

    private String invalid(String state, String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_EXCEPTION_TRANSITION",
                "质量例外状态 %s 不允许动作 %s".formatted(state, action));
    }

    private boolean canAccess(View view) {
        UUID commissionId = commissionId(view.resourceType(), view.resourceId());
        return commissionId != null && access.canAccessCommission(commissionId);
    }

    private void requireAccess(View view) {
        UUID commissionId = commissionId(view.resourceType(), view.resourceId());
        if (commissionId == null) throw ApiException.notFound("EXCEPTION_RESOURCE_NOT_FOUND", "质量例外关联资源不存在");
        access.requireCommission(commissionId);
    }

    private UUID commissionId(String resourceType, UUID resourceId) {
        String sql = resourceType.equals("TEST_TASK")
                ? "SELECT commission_id FROM test_task WHERE id=:id"
                : "SELECT t.commission_id FROM test_record r JOIN test_task t ON t.id=r.task_id WHERE r.id=:id";
        return jdbc.sql(sql).param("id", resourceId).query(UUID.class).optional().orElse(null);
    }

    private String baseSelect() {
        return """
                SELECT id, resource_type, resource_id, blocker_code, reason, expires_at, requested_by,
                  approved_by, state, decision_comment, version, created_at, decided_at FROM exception_approval
                """;
    }

    private String exceptionSelect() {
        return """
                SELECT exception.id,exception.resource_type,exception.resource_id,exception.blocker_code,
                  exception.reason,exception.expires_at,exception.requested_by,exception.approved_by,
                  exception.state,exception.decision_comment,exception.version,exception.created_at,
                  exception.decided_at
                """;
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          PageCursor cursor) {
        return statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .param("cursorAt", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
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

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new View(rs.getObject("id", UUID.class), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class), rs.getString("blocker_code"), rs.getString("reason"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("requested_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getString("state"), rs.getString("decision_comment"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class));
    }
}
