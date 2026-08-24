package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class WarningRecordService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> TYPES = Set.of("EQUIPMENT_VERIFICATION_EXPIRED",
            "EQUIPMENT_CALIBRATION_EXPIRED", "DETECTION_NONCONFORMING", "SAMPLE_TEST_OVERDUE");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public WarningRecordService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record Filters(OffsetDateTime from, OffsetDateTime to, String warningType, String state,
                          String query, String cursor, int limit) {}
    public record Resolve(long expectedVersion, String description, List<UUID> attachmentFileIds) {}
    public record View(UUID id, String warningType, String sourceType, UUID sourceId, UUID projectId,
                       String projectName, UUID commissionId, String commissionNumber, String content,
                       OffsetDateTime occurredAt, String state, UUID handledBy, String handlerName,
                       OffsetDateTime handledAt, String handlingDescription, int attachmentCount,
                       long version, OffsetDateTime updatedAt, List<String> allowedActions) {}

    public CursorPage<View> list(Filters filters) {
        AuthenticatedUser user = actor.require();
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        String type = upper(filters.warningType());
        String state = upper(filters.state());
        if (!type.isEmpty() && !TYPES.contains(type)) throw invalid("WARNING_TYPE_INVALID", "预警类型无效");
        if (!state.isEmpty() && !Set.of("OPEN", "RESOLVED").contains(state)) throw invalid("WARNING_STATE_INVALID", "预警状态无效");
        if (filters.from() != null && filters.to() != null && filters.to().isBefore(filters.from())) {
            throw invalid("WARNING_TIME_RANGE_INVALID", "预警结束时间不得早于开始时间");
        }
        PageCursor cursor = PageCursor.decode(filters.cursor());
        var statement = jdbc.sql(select() + """
                WHERE %s
                  AND (CAST(:fromTime AS timestamptz) IS NULL OR warning.occurred_at>=CAST(:fromTime AS timestamptz))
                  AND (CAST(:toTime AS timestamptz) IS NULL OR warning.occurred_at<=CAST(:toTime AS timestamptz))
                  AND (:warningType='' OR warning.warning_type=:warningType)
                  AND (:state='' OR warning.state=:state)
                  AND (:query='' OR warning.content ILIKE :pattern OR project.name ILIKE :pattern
                       OR commission.number ILIKE :pattern)
                  AND (CAST(:cursorTime AS timestamptz) IS NULL OR (warning.occurred_at,warning.id)<
                       (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                GROUP BY warning.id,project.name,commission.number,handler.display_name
                ORDER BY warning.occurred_at DESC,warning.id DESC LIMIT :fetchLimit
                """.formatted(scope()));
        bind(statement, user, filters, type, state, cursor);
        statement.param("fetchLimit", limit + 1);
        List<View> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        long total = count(user, filters, type, state);
        String next = hasMore ? new PageCursor(items.getLast().occurredAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(select() + " WHERE warning.id=:id AND " + scope()
                + " GROUP BY warning.id,project.name,commission.number,handler.display_name")
                .param("id", id);
        bindActor(statement, user);
        return statement.query(this::map).optional().orElseThrow(() ->
                ApiException.notFound("WARNING_NOT_FOUND", "预警不存在或不在当前数据范围内"));
    }

    @Transactional
    public View resolve(UUID id, Resolve command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "预警已被其他用户更新", before.version());
        if (!before.state().equals("OPEN")) throw new ApiException(HttpStatus.CONFLICT,
                "WARNING_ALREADY_RESOLVED", "该预警已经完成处置");
        String description = command.description() == null ? "" : command.description().trim();
        if (description.isEmpty() || description.length() > 2000) throw invalid(
                "WARNING_DESCRIPTION_REQUIRED", "处理描述不能为空且最多 2000 个字符");
        List<UUID> files = command.attachmentFileIds() == null ? List.of()
                : command.attachmentFileIds().stream().distinct().toList();
        if (files.size() > 20) throw invalid("WARNING_ATTACHMENTS_LIMIT", "单条预警最多关联 20 个处置附件");
        validateFiles(files);
        int changed = jdbc.sql("""
                UPDATE warning_record SET state='RESOLVED',handled_by=:actor,handled_at=now(),
                  handling_description=:description,version=version+1,updated_at=now()
                WHERE id=:id AND state='OPEN' AND version=:version
                """).param("actor", actor.require().id()).param("description", description)
                .param("id", id).param("version", command.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "预警已被其他用户更新", require(id).version());
        for (int index = 0; index < files.size(); index++) {
            jdbc.sql("""
                    INSERT INTO warning_record_attachment(warning_id,file_id,sort_order,attached_by)
                    VALUES (:warning,:file,:sort,:actor)
                    """).param("warning", id).param("file", files.get(index)).param("sort", index)
                    .param("actor", actor.require().id()).update();
        }
        View after = require(id);
        audit.record("WARNING_RESOLVED", "WARNING_RECORD", id, before, after,
                "WARNING_HANDLING", description);
        return after;
    }

    private void validateFiles(List<UUID> fileIds) {
        if (fileIds.isEmpty()) return;
        AuthenticatedUser user = actor.require();
        long valid = jdbc.sql("""
                SELECT count(*) FROM stored_file file JOIN app_user uploader ON uploader.id=file.uploaded_by
                WHERE file.id IN (:ids) AND uploader.organization_id=:organization
                  AND file.media_type IN ('application/pdf','image/png','image/jpeg')
                  AND (file.uploaded_by=:actor OR :admin)
                """).param("ids", fileIds).param("organization", user.organizationId()).param("actor", user.id())
                .param("admin", user.permissions().contains("SYSTEM_ADMIN")).query(Long.class).single();
        if (valid != fileIds.size()) throw invalid("WARNING_ATTACHMENT_INVALID",
                "处置附件不存在、格式不受支持或不在当前访问范围内");
    }

    private long count(AuthenticatedUser user, Filters filters, String type, String state) {
        var statement = jdbc.sql("""
                SELECT count(*) FROM warning_record warning
                LEFT JOIN project ON project.id=warning.project_id
                LEFT JOIN commission ON commission.id=warning.commission_id
                WHERE %s
                  AND (CAST(:fromTime AS timestamptz) IS NULL OR warning.occurred_at>=CAST(:fromTime AS timestamptz))
                  AND (CAST(:toTime AS timestamptz) IS NULL OR warning.occurred_at<=CAST(:toTime AS timestamptz))
                  AND (:warningType='' OR warning.warning_type=:warningType)
                  AND (:state='' OR warning.state=:state)
                  AND (:query='' OR warning.content ILIKE :pattern OR project.name ILIKE :pattern
                       OR commission.number ILIKE :pattern)
                """.formatted(scope()));
        bind(statement, user, filters, type, state, null);
        return statement.query(Long.class).single();
    }

    private String select() {
        return """
                SELECT warning.id,warning.warning_type,warning.source_type,warning.source_id,
                  warning.project_id,project.name project_name,warning.commission_id,
                  commission.number commission_number,warning.content,warning.occurred_at,warning.state,
                  warning.handled_by,handler.display_name handler_name,warning.handled_at,
                  warning.handling_description,count(attachment.file_id) attachment_count,
                  warning.version,warning.updated_at
                FROM warning_record warning
                LEFT JOIN project ON project.id=warning.project_id
                LEFT JOIN commission ON commission.id=warning.commission_id
                LEFT JOIN app_user handler ON handler.id=warning.handled_by
                LEFT JOIN warning_record_attachment attachment ON attachment.warning_id=warning.id
                """;
    }

    private String scope() {
        return """
                warning.organization_id=:organization AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (warning.commission_id IS NOT NULL AND (
                    (:scope='DEPARTMENT' AND (
                      EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=commission.created_by
                        AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM test_task task JOIN person assignee ON assignee.id=task.assigned_to
                        WHERE task.commission_id=commission.id AND assignee.department_id=:department)))
                    OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                      WHERE member.project_id=commission.project_id AND member.user_id=:actor))
                    OR (:scope='SELF' AND (commission.created_by=:actor OR EXISTS(
                      SELECT 1 FROM test_task task WHERE task.commission_id=commission.id
                        AND task.assigned_to=:person)))))
                  OR (warning.source_type='EQUIPMENT_CERTIFICATE' AND EXISTS(
                    SELECT 1 FROM equipment_certificate certificate JOIN equipment equipment
                      ON equipment.id=certificate.equipment_id
                    WHERE certificate.id=warning.source_id AND (
                      (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM person custodian
                        WHERE custodian.id=equipment.custodian_id AND custodian.department_id=:department))
                      OR (:scope='SELF' AND equipment.custodian_id=:person))))
                )
                """;
    }

    private void bind(JdbcClient.StatementSpec statement, AuthenticatedUser user, Filters filters,
                      String type, String state, PageCursor cursor) {
        bindActor(statement, user);
        String query = filters.query() == null ? "" : filters.query().trim();
        if (query.length() > 120) throw invalid("WARNING_QUERY_TOO_LONG", "查询内容最多 120 个字符");
        statement.param("fromTime", filters.from()).param("toTime", filters.to())
                .param("warningType", type).param("state", state).param("query", query)
                .param("pattern", "%" + escapeLike(query) + "%")
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
    }

    private void bindActor(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getString("warning_type"),
                rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                rs.getObject("commission_id", UUID.class), rs.getString("commission_number"),
                rs.getString("content"), rs.getObject("occurred_at", OffsetDateTime.class), state,
                rs.getObject("handled_by", UUID.class), rs.getString("handler_name"),
                rs.getObject("handled_at", OffsetDateTime.class), rs.getString("handling_description"),
                rs.getInt("attachment_count"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), state.equals("OPEN") ? List.of("RESOLVE") : List.of());
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
