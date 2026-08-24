package com.lanchuan.lims.audit;

import com.lanchuan.lims.common.RequestIdFilter;
import com.lanchuan.lims.identity.AuthenticatedUser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final HttpServletRequest request;

    public AuditService(JdbcClient jdbc, ObjectMapper json, HttpServletRequest request) {
        this.jdbc = jdbc;
        this.json = json;
        this.request = request;
    }

    public void record(String action, String resourceType, Object resourceId, Object before, Object after,
                       String reasonCode, String comment) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser actor = authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user : null;
        jdbc.sql("""
                INSERT INTO audit_event(id, request_id, actor_id, actor_name, action, resource_type, resource_id,
                  organization_id, before_json, after_json, reason_code, comment, remote_address, user_agent)
                VALUES (:id, :requestId, :actorId, :actorName, :action, :resourceType, :resourceId,
                  :organizationId, CAST(:before AS jsonb), CAST(:after AS jsonb), :reasonCode, :comment,
                  :remoteAddress, :userAgent)
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)))
                .param("actorId", actor == null ? null : actor.id())
                .param("actorName", actor == null ? null : actor.displayName())
                .param("action", action).param("resourceType", resourceType)
                .param("resourceId", resourceId == null ? null : resourceId.toString())
                .param("organizationId", actor == null ? null : actor.organizationId())
                .param("before", encode(before)).param("after", encode(after))
                .param("reasonCode", reasonCode).param("comment", comment)
                .param("remoteAddress", request.getRemoteAddr())
                .param("userAgent", truncate(request.getHeader("User-Agent"), 500))
                .update();
    }

    /** Persist rejected security/business-boundary attempts even when the caller transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(String action, String resourceType, Object resourceId, Object attempted,
                               String reasonCode, String comment) {
        record(action, resourceType, resourceId, null, attempted, reasonCode, comment);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法序列化审计快照", exception); }
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
