package com.lanchuan.lims.test;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DataChangeRequestService {
    private static final UUID NO_ID = new UUID(0, 0);
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TestRecordService records;
    private final DataScopeService access;

    public DataChangeRequestService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                    ObjectMapper json, TestRecordService records, DataScopeService access) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.json = json;
        this.records = records;
        this.access = access;
    }

    public record Create(UUID testRecordId, String reasonCode, String reason, UUID equipmentId,
                         Map<String, Object> values) {}
    public record Decision(String action, long expectedVersion, String comment) {}
    public record View(UUID id, UUID testRecordId, String taskNumber, String commissionNumber,
                       String parameterName, String equipmentCode, String equipmentName,
                       String reasonCode, String reason, Map<String, Object> proposed,
                       String state, UUID requestedBy, UUID decidedBy,
                       String decisionComment, long version, OffsetDateTime createdAt,
                       OffsetDateTime decidedAt, OffsetDateTime appliedAt) {}

    @Transactional
    public View create(Create command) {
        TestRecordService.View source = records.require(command.testRecordId());
        if (!List.of(TestRecordState.SUBMITTED, TestRecordState.CONFIRMED).contains(source.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_CHANGE_SOURCE_INVALID",
                    "仅已提交或已确认的试验记录可以申请修改");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_CHANGE_REASON_REQUIRED", "数据修改申请必须填写原因");
        }
        Map<String, Object> calculated = records.validateAndCalculate(source.taskId(), command.values());
        UUID equipmentId = command.equipmentId() == null ? source.equipmentId() : command.equipmentId();
        long active = jdbc.sql("""
                SELECT count(*) FROM data_change_request
                WHERE resource_type='TEST_RECORD' AND resource_id=:resource AND state IN ('REQUESTED','APPROVED')
                """).param("resource", source.id()).query(Long.class).single();
        if (active > 0) throw new ApiException(HttpStatus.CONFLICT, "DATA_CHANGE_ALREADY_ACTIVE", "该记录已有待处理的数据修改申请");
        UUID id = UUID.randomUUID();
        Map<String, Object> proposed = Map.of("equipmentId", equipmentId == null ? "" : equipmentId.toString(), "values", calculated);
        jdbc.sql("""
                INSERT INTO data_change_request(id, resource_type, resource_id, reason_code, reason,
                  proposed_json, state, requested_by)
                VALUES (:id, 'TEST_RECORD', :resource, :reasonCode, :reason, CAST(:proposed AS jsonb), 'REQUESTED', :actor)
                """).param("id", id).param("resource", source.id()).param("reasonCode", command.reasonCode())
                .param("reason", command.reason().trim()).param("proposed", encode(proposed))
                .param("actor", actor.require().id()).update();
        View created = require(id);
        audit.record("DATA_CHANGE_REQUESTED", "DATA_CHANGE_REQUEST", id, source, created, command.reasonCode(), command.reason());
        return created;
    }

    public CursorPage<View> list(String cursorValue, int requestedLimit) {
        AuthenticatedUser user = actor.require();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        String joins = """
                 JOIN test_record tr ON tr.id=d.resource_id JOIN test_task t ON t.id=tr.task_id
                 JOIN commission c ON c.id=t.commission_id
                 JOIN test_parameter parameter ON parameter.id=t.parameter_id
                 LEFT JOIN equipment ON equipment.id=NULLIF(d.proposed_json->>'equipmentId','')::uuid
                """;
        String conditions = " WHERE " + scope("c");
        var statement = jdbc.sql(baseSelect() + joins + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (d.created_at,d.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY d.created_at DESC,d.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, cursor).param("fetchLimit", limit + 1);
        List<View> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) FROM data_change_request d" + joins + conditions);
        bind(count, user, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().createdAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + """
                 JOIN test_record tr ON tr.id=d.resource_id JOIN test_task t ON t.id=tr.task_id
                 JOIN commission c ON c.id=t.commission_id
                 JOIN test_parameter parameter ON parameter.id=t.parameter_id
                 LEFT JOIN equipment ON equipment.id=NULLIF(d.proposed_json->>'equipmentId','')::uuid
                 WHERE d.id=:id AND c.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("DATA_CHANGE_NOT_FOUND", "数据修改申请不存在"));
        access.requireCommission(commissionId(view.testRecordId()));
        return view;
    }

    @Transactional
    public TransitionResult decide(UUID id, Decision command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "数据修改申请已被更新", before.version());
        }
        if (!before.state().equals("REQUESTED")) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_CHANGE_NOT_DECIDABLE", "当前数据修改申请不可审批");
        }
        String action = command.action().trim().toUpperCase();
        String next = switch (action) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_DATA_CHANGE_TRANSITION", "数据修改申请动作无效");
        };
        if (before.requestedBy().equals(actor.require().id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INDEPENDENT_DATA_CHANGE_APPROVER_REQUIRED", "申请人不能审批自己的数据修改申请");
        }
        if (action.equals("REJECT") && (command.comment() == null || command.comment().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_CHANGE_DECISION_COMMENT_REQUIRED", "拒绝申请必须填写说明");
        }
        jdbc.sql("""
                UPDATE data_change_request SET state=:state, decided_by=:actor, decision_comment=:comment,
                  decided_at=now(), version=version+1 WHERE id=:id AND version=:version
                """).param("state", next).param("actor", actor.require().id()).param("comment", command.comment())
                .param("id", id).param("version", before.version()).update();
        View after = require(id);
        audit.record("DATA_CHANGE_" + action, "DATA_CHANGE_REQUEST", id, before, after, before.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.state().equals("APPROVED") ? List.of("APPLY") : List.of());
    }

    @Transactional
    public TestRecordService.View apply(UUID id, long expectedVersion) {
        View request = require(id);
        if (request.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "数据修改申请已被更新", request.version());
        }
        if (!request.state().equals("APPROVED")) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_CHANGE_NOT_APPROVED", "数据修改申请尚未批准");
        }
        TestRecordService.View source = records.require(request.testRecordId());
        long signedReports = jdbc.sql("""
                SELECT count(*) FROM report r JOIN test_task t ON t.commission_id=r.commission_id
                WHERE t.id=:task AND r.state IN ('ISSUED','DISTRIBUTED','ARCHIVED','VOID')
                """).param("task", source.taskId()).query(Long.class).single();
        if (signedReports > 0) {
            throw ApiException.blocked("SIGNED_REPORT_REVISION_REQUIRED",
                    "已签发报告关联的数据不能直接修改", request.version(),
                    List.of("请先创建报告修订版本，再应用检测数据修改"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) request.proposed().get("values");
        String equipmentText = String.valueOf(request.proposed().getOrDefault("equipmentId", ""));
        UUID equipmentId = equipmentText.isBlank() ? null : UUID.fromString(equipmentText);
        int revision = jdbc.sql("SELECT COALESCE(max(revision),-1)+1 FROM test_record WHERE task_id=:task")
                .param("task", source.taskId()).query(Integer.class).single();
        jdbc.sql("UPDATE test_record SET state='SUPERSEDED', version=version+1, updated_at=now() WHERE id=:id")
                .param("id", source.id()).update();
        UUID replacementId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO test_record(id, task_id, revision, values_json, result, state, recorded_by, equipment_id)
                VALUES (:id, :task, :revision, CAST(:values AS jsonb), 'PENDING', 'DRAFT', :person, :equipment)
                """).param("id", replacementId).param("task", source.taskId()).param("revision", revision)
                .param("values", encode(values)).param("person", source.recordedBy()).param("equipment", equipmentId).update();
        jdbc.sql("UPDATE test_task SET state='RETURNED', version=version+1, updated_at=now() WHERE id=:id")
                .param("id", source.taskId()).update();
        jdbc.sql("""
                UPDATE data_change_request SET state='APPLIED', applied_at=now(), version=version+1
                WHERE id=:id AND version=:version
                """).param("id", id).param("version", expectedVersion).update();
        TestRecordService.View replacement = records.require(replacementId);
        audit.record("DATA_CHANGE_APPLIED", "TEST_RECORD", source.id(), source, replacement,
                request.reasonCode(), request.reason());
        return replacement;
    }

    private String baseSelect() {
        return """
                SELECT d.id, d.resource_id, d.reason_code, d.reason, d.proposed_json::text, d.state,
                  d.requested_by, d.decided_by, d.decision_comment, d.version, d.created_at,
                  d.decided_at, d.applied_at, t.number task_number, c.number commission_number,
                  parameter.name parameter_name, equipment.code equipment_code, equipment.name equipment_name
                FROM data_change_request d
                """;
    }

    private UUID commissionId(UUID recordId) {
        return jdbc.sql("SELECT t.commission_id FROM test_record r JOIN test_task t ON t.id=r.task_id WHERE r.id=:id")
                .param("id", recordId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("TEST_RECORD_NOT_FOUND", "试验记录不存在"));
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
        try {
            Map<String, Object> proposed = json.readValue(rs.getString("proposed_json"), new TypeReference<>() {});
            return new View(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                    rs.getString("task_number"), rs.getString("commission_number"),
                    rs.getString("parameter_name"), rs.getString("equipment_code"), rs.getString("equipment_name"),
                    rs.getString("reason_code"), rs.getString("reason"), proposed, rs.getString("state"),
                    rs.getObject("requested_by", UUID.class), rs.getObject("decided_by", UUID.class),
                    rs.getString("decision_comment"), rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("decided_at", OffsetDateTime.class),
                    rs.getObject("applied_at", OffsetDateTime.class));
        } catch (JacksonException exception) {
            throw new java.sql.SQLException("Invalid data change proposal", exception);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("数据修改内容无法序列化", exception); }
    }
}
