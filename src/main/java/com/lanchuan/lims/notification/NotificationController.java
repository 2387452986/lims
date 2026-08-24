package com.lanchuan.lims.notification;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public NotificationController(JdbcClient jdbc, CurrentActor actor, AuditService audit, IdempotencyService idempotency) {
        this.jdbc = jdbc; this.actor = actor; this.audit = audit; this.idempotency = idempotency;
    }

    public record Notification(UUID id, String category, String title, String body, String resourceType,
                               UUID resourceId, boolean read, long version, OffsetDateTime createdAt) {}

    @GetMapping
    public List<Notification> list() {
        return jdbc.sql("""
                SELECT id, category, title, body, resource_type, resource_id, read_at, version, created_at
                FROM notification WHERE recipient_id=:recipient ORDER BY created_at DESC LIMIT 200
                """).param("recipient", actor.require().id()).query((rs, row) -> new Notification(
                        rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("title"),
                        rs.getString("body"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getObject("read_at") != null, rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        long count = jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id=:recipient AND read_at IS NULL")
                .param("recipient", actor.require().id()).query(Long.class).single();
        return Map.of("count", count);
    }

    @PostMapping("/{id}/transitions")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "notification-transition:" + id, body, TransitionResult.class,
                () -> transitionInternal(id, body));
    }

    @Transactional
    protected TransitionResult transitionInternal(UUID id, TransitionCommand body) {
        Notification before = list().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "通知不存在"));
        if (before.version() != body.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "通知已被更新", before.version());
        if (!body.action().equalsIgnoreCase("READ") || before.read()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "INVALID_NOTIFICATION_TRANSITION", "通知当前状态不能执行该动作");
        }
        jdbc.sql("""
                UPDATE notification SET read_at=now(), version=version+1
                WHERE id=:id AND recipient_id=:recipient AND version=:version
                """).param("id", id).param("recipient", actor.require().id()).param("version", before.version()).update();
        audit.record("NOTIFICATION_READ", "NOTIFICATION", id, before, Map.of("read", true), body.reasonCode(), body.comment());
        return new TransitionResult("READ", before.version() + 1, List.of());
    }
}
