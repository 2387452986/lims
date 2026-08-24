package com.lanchuan.lims.common;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BackgroundJobService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public BackgroundJobService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record View(UUID id, String jobType, String state, int attemptCount, String lastError,
                       UUID resultFileId, String resourceType, UUID resourceId, long version,
                       OffsetDateTime availableAt, OffsetDateTime createdAt, OffsetDateTime completedAt,
                       List<String> allowedActions) {}

    public List<View> list(String resourceType, UUID resourceId, int requestedLimit) {
        AuthenticatedUser user = actor.require();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.sql(select() + """
                WHERE (job.created_by=:actor OR :admin)
                  AND (:resourceType='' OR job.resource_type=:resourceType)
                  AND (CAST(:resourceId AS uuid) IS NULL OR job.resource_id=CAST(:resourceId AS uuid))
                ORDER BY job.created_at DESC,job.id DESC LIMIT :limit
                """).param("actor", user.id()).param("admin", user.permissions().contains("SYSTEM_ADMIN"))
                .param("resourceType", upper(resourceType)).param("resourceId", resourceId)
                .param("limit", limit).query(this::map).list();
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        return jdbc.sql(select() + " WHERE job.id=:id AND (job.created_by=:actor OR :admin)")
                .param("id", id).param("actor", user.id())
                .param("admin", user.permissions().contains("SYSTEM_ADMIN"))
                .query(this::map).optional().orElseThrow(() -> ApiException.notFound(
                        "BACKGROUND_JOB_NOT_FOUND", "作业不存在或不在当前访问范围内"));
    }

    @Transactional
    public View transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "作业已被其他用户更新", before.version());
        String action = upper(command.action());
        if (!action.equals("RETRY") || !before.state().equals("FAILED")) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKGROUND_JOB_ACTION_INVALID", "当前作业状态不允许该操作");
        }
        if (command.comment() == null || command.comment().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKGROUND_JOB_RETRY_REASON_REQUIRED", "重试作业必须填写原因");
        }
        int changed = jdbc.sql("""
                UPDATE background_job SET state='RETRY',attempt_count=0,available_at=now(),last_error=NULL,
                  locked_by=NULL,locked_at=NULL,completed_at=NULL,version=version+1
                WHERE id=:id AND state='FAILED' AND version=:version
                """).param("id", id).param("version", command.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "作业已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("BACKGROUND_JOB_RETRIED", "BACKGROUND_JOB", id, before, after,
                command.reasonCode(), command.comment());
        return after;
    }

    private String select() {
        return """
                SELECT job.id,job.job_type,job.state,job.attempt_count,job.last_error,job.result_file_id,
                  job.resource_type,job.resource_id,job.version,job.available_at,job.created_at,job.completed_at
                FROM background_job job
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getString("job_type"), state,
                rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getObject("result_file_id", UUID.class), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class), rs.getLong("version"),
                rs.getObject("available_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), state.equals("FAILED") ? List.of("RETRY") : List.of());
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
}
