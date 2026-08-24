package com.lanchuan.lims.audit;

import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuditQueryService {
    private static final Set<String> OPERATION_TYPES = Set.of("LOGIN", "BROWSE", "CREATE", "UPDATE", "DELETE", "LOGOUT");
    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public AuditQueryService(JdbcClient jdbc, CurrentActor actor) {
        this.jdbc = jdbc;
        this.actor = actor;
    }

    public record Option(String code, String label, boolean connected) {}
    public record Facets(List<Option> businesses, List<Option> operations) {}
    public record Event(UUID id, OffsetDateTime occurredAt, String operationType, String action,
                        String operationData, String actorName, String actorAccount, String remoteAddress,
                        String businessCode, String businessLabel, String description, String requestId,
                        String resourceType, String resourceId, String reasonCode, String comment) {}
    public record Filters(String account, OffsetDateTime from, OffsetDateTime to, String operationData,
                          String business, String operationType, String resourceType, String cursor, int limit) {}

    public Facets facets() {
        List<Option> businesses = jdbc.sql("""
                SELECT code,label,cardinality(resource_types)>0 connected
                FROM audit_business_category ORDER BY sort_order
                """).query((rs, row) -> new Option(rs.getString("code"), rs.getString("label"),
                rs.getBoolean("connected"))).list();
        List<Option> operations = List.of(
                new Option("LOGIN", "登录", true), new Option("BROWSE", "浏览", true),
                new Option("CREATE", "新增", true), new Option("UPDATE", "修改", true),
                new Option("DELETE", "删除", true), new Option("LOGOUT", "退出登录", true));
        return new Facets(businesses, operations);
    }

    public CursorPage<Event> list(Filters requested) {
        int limit = Math.max(1, Math.min(requested.limit(), 100));
        PageCursor cursor = PageCursor.decode(requested.cursor());
        String account = clean(requested.account(), 120);
        String data = clean(requested.operationData(), 160);
        String business = clean(requested.business(), 16).toUpperCase(Locale.ROOT);
        String operation = clean(requested.operationType(), 16).toUpperCase(Locale.ROOT);
        String resource = clean(requested.resourceType(), 60).toUpperCase(Locale.ROOT);
        if (!operation.isEmpty() && !OPERATION_TYPES.contains(operation)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIT_OPERATION_TYPE_INVALID", "操作类型无效");
        }
        if (requested.from() != null && requested.to() != null && requested.from().isAfter(requested.to())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIT_DATE_RANGE_INVALID", "开始时间不能晚于结束时间");
        }
        if (!business.isEmpty()) {
            boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM audit_business_category WHERE code=:code)")
                    .param("code", business).query(Boolean.class).single();
            if (!exists) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIT_BUSINESS_INVALID", "操作业务无效");
        }
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("organization", actor.require().organizationId());
        params.put("account", account); params.put("accountPattern", "%" + escapeLike(account) + "%");
        params.put("from", requested.from()); params.put("to", requested.to());
        params.put("data", data); params.put("dataPattern", "%" + escapeLike(data) + "%");
        params.put("business", business); params.put("operation", operation); params.put("resource", resource);
        params.put("cursorAt", cursor == null ? null : cursor.updatedAt());
        params.put("cursorId", cursor == null ? null : cursor.id());
        params.put("fetch", limit + 1);

        List<Event> fetched = jdbc.sql(select() + filters(true) + " ORDER BY event.occurred_at DESC,event.id DESC LIMIT :fetch")
                .params(params).query(this::map).list();
        long total = jdbc.sql("SELECT count(*) " + from() + filters(false))
                .params(withoutPaging(params)).query(Long.class).single();
        boolean hasMore = fetched.size() > limit;
        List<Event> items = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
        String next = hasMore ? new PageCursor(items.getLast().occurredAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(List.copyOf(items), next, hasMore, total);
    }

    private String select() {
        return """
                SELECT event.id,event.occurred_at,audit_operation_type(event.action) operation_type,event.action,
                  COALESCE(NULLIF(event.after_json->>'number',''),NULLIF(event.after_json->>'code',''),
                    NULLIF(event.after_json->>'name',''),NULLIF(event.after_json->>'username',''),
                    NULLIF(event.before_json->>'number',''),NULLIF(event.before_json->>'code',''),
                    NULLIF(event.before_json->>'name',''),event.resource_id,'') operation_data,
                  COALESCE(event.actor_name,'系统') actor_name,COALESCE(account.username,'') actor_account,
                  COALESCE(event.remote_address,'') remote_address,COALESCE(category.code,'') business_code,
                  COALESCE(category.label,event.resource_type) business_label,
                  event.action description,
                  event.request_id,event.resource_type,event.resource_id,event.reason_code,event.comment
                """ + from();
    }

    private String from() {
        return """
                FROM audit_event event
                LEFT JOIN app_user account ON account.id=event.actor_id
                LEFT JOIN LATERAL (
                  SELECT category.code,category.label FROM audit_business_category category
                  WHERE event.resource_type=ANY(category.resource_types)
                  ORDER BY category.sort_order LIMIT 1
                ) category ON true
                """;
    }

    private String filters(boolean includeCursor) {
        return """
                WHERE event.organization_id=:organization
                  AND (:account='' OR COALESCE(account.username,'') ILIKE :accountPattern ESCAPE '\\'
                    OR COALESCE(event.actor_name,'') ILIKE :accountPattern ESCAPE '\\')
                  AND (CAST(:from AS timestamptz) IS NULL OR event.occurred_at>=:from)
                  AND (CAST(:to AS timestamptz) IS NULL OR event.occurred_at<=:to)
                  AND (:data='' OR COALESCE(event.after_json->>'number',event.after_json->>'code',
                    event.after_json->>'name',event.after_json->>'username',event.before_json->>'number',
                    event.before_json->>'code',event.before_json->>'name',event.resource_id,'')
                    ILIKE :dataPattern ESCAPE '\\')
                  AND (:business='' OR EXISTS(SELECT 1 FROM audit_business_category selected
                    WHERE selected.code=:business AND event.resource_type=ANY(selected.resource_types)))
                  AND (:operation='' OR audit_operation_type(event.action)=:operation)
                  AND (:resource='' OR event.resource_type=:resource)
                """ + (includeCursor ? """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (event.occurred_at,event.id)<(:cursorAt,:cursorId))
                """ : "");
    }

    private Event map(ResultSet rs, int row) throws SQLException {
        String action = rs.getString("action");
        String businessLabel = rs.getString("business_label");
        String comment = rs.getString("comment");
        return new Event(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("operation_type"), action, rs.getString("operation_data"),
                rs.getString("actor_name"), rs.getString("actor_account"), rs.getString("remote_address"),
                rs.getString("business_code"), businessLabel, descriptionFor(action, businessLabel, comment),
                rs.getString("request_id"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getString("reason_code"), comment);
    }

    static String descriptionFor(String action, String businessLabel, String comment) {
        if (comment != null && !comment.isBlank()) return comment.trim();
        String code = action == null ? "" : action.toUpperCase(Locale.ROOT);
        String target = businessLabel == null || businessLabel.isBlank() ? "业务数据" : businessLabel;
        if (code.contains("LOGIN_SUCCESS")) return "登录成功";
        if (code.contains("LOGIN_FAILURE") || code.contains("LOGIN_FAILED")) return "登录失败";
        if (code.contains("LOGOUT")) return "退出登录";
        if (code.contains("PASSWORD_REVERIFIED")) return "已完成密码复核";
        if (code.contains("PUBLISH")) return "发布" + target;
        if (code.contains("REVISION") || code.contains("REVISED")) return "创建" + target + "修订";
        if (code.contains("RETIRE") || code.contains("DISABLE")) return "停用" + target;
        if (code.contains("ACTIVATE") || code.contains("ENABLE")) return "启用" + target;
        if (code.contains("RESET")) return "重置" + target;
        if (code.contains("DISCARD") || code.contains("VOID")) return "作废" + target;
        if (code.contains("CANCEL")) return "撤销" + target;
        if (code.contains("WITHDRAW")) return "撤回" + target;
        if (code.contains("REJECT")) return "退回" + target;
        if (code.contains("APPROVE")) return "审核通过" + target;
        if (code.contains("SUBMIT")) return "提交" + target;
        if (code.contains("ARCHIVE")) return "归档" + target;
        if (code.contains("DISTRIBUT")) return "发放" + target;
        if (code.contains("SEAL")) return "签章" + target;
        if (code.contains("PRINT")) return "打印" + target;
        if (code.contains("EXPORT")) return "导出" + target;
        if (code.contains("DOWNLOAD")) return "下载" + target;
        if (code.contains("ASSIGN")) return "分配" + target;
        if (code.contains("HANDOVER")) return "交接" + target;
        if (code.contains("RECEIVE")) return "接收" + target;
        if (code.contains("COMPLETE")) return "完成" + target;
        if (code.contains("CONFIRM")) return "确认" + target;
        if (code.contains("START")) return "开始" + target;
        if (code.contains("CREATED") || code.contains("_CREATE") || code.contains("_ADD")) return "新增" + target;
        if (code.contains("UPDATED") || code.contains("_UPDATE") || code.contains("_EDIT")
                || code.contains("_MODIFY") || code.contains("_BOUND")) return "修改" + target;
        if (code.contains("DELETE") || code.contains("REMOVED")) return "删除" + target;
        return "操作" + target;
    }

    private Map<String, Object> withoutPaging(Map<String, Object> params) {
        Map<String, Object> copy = new java.util.HashMap<>(params);
        copy.remove("cursorAt"); copy.remove("cursorId"); copy.remove("fetch");
        return copy;
    }

    private String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() > max) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "AUDIT_FILTER_TOO_LONG", "审计筛选内容过长");
        return clean;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
