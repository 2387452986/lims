package com.lanchuan.lims.test;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.FormulaEvaluator;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.equipment.EquipmentOperationService;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import com.lanchuan.lims.nonconformance.ExceptionApprovalService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class TestRecordService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final ObjectMapper json;
    private final FormulaEvaluator formulas;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ExceptionApprovalService exceptions;
    private final DataScopeService access;
    private final EquipmentOperationService equipmentOperations;

    public TestRecordService(JdbcClient jdbc, CurrentActor actor, ObjectMapper json, FormulaEvaluator formulas,
                             NumberingService numbering, AuditService audit, ExceptionApprovalService exceptions,
                             DataScopeService access, EquipmentOperationService equipmentOperations) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.json = json;
        this.formulas = formulas;
        this.numbering = numbering;
        this.audit = audit;
        this.exceptions = exceptions;
        this.access = access;
        this.equipmentOperations = equipmentOperations;
    }

    public record Create(UUID taskId, UUID equipmentId, Map<String, Object> values) {}
    public record Update(UUID equipmentId, Map<String, Object> values, long expectedVersion) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment, String result) {}
    public record View(UUID id, UUID taskId, String taskNumber, String parameterName,
                       int revision, Map<String, Object> values, String result,
                       TestRecordState state, UUID recordedBy, UUID equipmentId, long version,
                       OffsetDateTime submittedAt, OffsetDateTime confirmedAt, OffsetDateTime updatedAt) {}

    @Transactional
    public View create(Create body) {
        UUID personId = actorPerson();
        TaskInput task = jdbc.sql("""
                SELECT t.state, t.commission_id FROM test_task t JOIN commission c ON c.id=t.commission_id
                WHERE t.id=:task AND c.organization_id=:organization
                """).param("task", body.taskId()).param("organization", actor.require().organizationId())
                .query((rs, row) -> new TaskInput(rs.getString("state"), rs.getObject("commission_id", UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "检测任务不存在"));
        access.requireCommission(task.commissionId());
        if (!List.of("IN_PROGRESS", "RETURNED").contains(task.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_RECORDABLE", "任务当前状态不能录入试验数据");
        }
        long existing = jdbc.sql("SELECT count(*) FROM test_record WHERE task_id=:task AND state <> 'SUPERSEDED'")
                .param("task", body.taskId()).query(Long.class).single();
        if (existing > 0) throw new ApiException(HttpStatus.CONFLICT, "TEST_RECORD_EXISTS", "任务已有活动试验记录");
        UUID id = UUID.randomUUID();
        Map<String, Object> values = validateAndCalculate(body.taskId(), body.values());
        jdbc.sql("""
                INSERT INTO test_record(id, task_id, revision, values_json, result, state, recorded_by, equipment_id)
                VALUES (:id, :task, 0, CAST(:values AS jsonb), 'PENDING', 'DRAFT', :person, :equipment)
                """).param("id", id).param("task", body.taskId()).param("values", encode(values))
                .param("person", personId).param("equipment", body.equipmentId()).update();
        View created = require(id);
        audit.record("TEST_RECORD_CREATED", "TEST_RECORD", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Update body) {
        View before = require(id);
        if (before.state() != TestRecordState.DRAFT && before.state() != TestRecordState.RETURNED) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_RECORD_IMMUTABLE", "已提交或确认的试验记录不能直接修改");
        }
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "试验记录已被其他用户更新", before.version());
        }
        Map<String, Object> values = validateAndCalculate(before.taskId(), body.values());
        int updated = jdbc.sql("""
                UPDATE test_record SET values_json=CAST(:values AS jsonb), equipment_id=:equipment,
                  version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("values", encode(values)).param("equipment", body.equipmentId())
                .param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "试验记录已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("TEST_RECORD_UPDATED", "TEST_RECORD", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "试验记录已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        if (action.equals("CONFIRM") && Objects.equals(before.recordedBy(), actor.require().personId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEST_REVIEWER_MUST_DIFFER",
                    "试验记录必须由录入人员以外的复核人员确认");
        }
        TestRecordState next = before.state().transition(action);
        if (List.of("SUBMIT", "RESUBMIT").contains(action)) ensureEquipmentValid(before.equipmentId(), before.id());
        String result = before.result();
        if (action.equals("CONFIRM")) {
            if (!List.of("CONFORMING", "NONCONFORMING", "INVALID").contains(command.result())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_RESULT_REQUIRED", "确认记录时必须给出受控结果");
            }
            result = command.result();
        }
        int updated = jdbc.sql("""
                UPDATE test_record SET state=:state, result=:result,
                  submitted_at=CASE WHEN :state='SUBMITTED' THEN now() ELSE submitted_at END,
                  confirmed_at=CASE WHEN :state='CONFIRMED' THEN now() ELSE confirmed_at END,
                  version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next.name()).param("result", result).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "试验记录已被其他用户更新", require(id).version());
        if (next == TestRecordState.SUBMITTED) {
            equipmentOperations.recordCompletedTestUsage(before.taskId(), before.equipmentId(), before.recordedBy());
            jdbc.sql("UPDATE test_task SET state='SUBMITTED', version=version+1, updated_at=now() WHERE id=:task AND state IN ('IN_PROGRESS','RETURNED')")
                    .param("task", before.taskId()).update();
        } else if (next == TestRecordState.RETURNED) {
            jdbc.sql("UPDATE test_task SET state='RETURNED', version=version+1, updated_at=now() WHERE id=:task")
                    .param("task", before.taskId()).update();
        } else if (next == TestRecordState.CONFIRMED) {
            jdbc.sql("UPDATE test_task SET state='COMPLETED', version=version+1, updated_at=now() WHERE id=:task")
                    .param("task", before.taskId()).update();
            if (result.equals("NONCONFORMING") || result.equals("INVALID")) createNonconformance(before, result, command.comment());
        }
        View after = require(id);
        audit.record("TEST_RECORD_" + action, "TEST_RECORD", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions());
    }

    public View require(UUID id) {
        View view = jdbc.sql("""
                SELECT r.id, r.task_id, t.number task_number,
                  COALESCE(selection.parameter_name_snapshot, p.name) parameter_name,
                  r.revision, r.values_json::text, r.result, r.state, r.recorded_by,
                  r.equipment_id, r.version, r.submitted_at, r.confirmed_at, r.updated_at
                FROM test_record r JOIN test_task t ON t.id=r.task_id JOIN commission c ON c.id=t.commission_id
                  JOIN test_parameter p ON p.id=t.parameter_id
                  LEFT JOIN commission_parameter_selection selection ON selection.id=t.selection_id
                WHERE r.id=:id AND c.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("TEST_RECORD_NOT_FOUND", "试验记录不存在"));
        UUID commissionId = jdbc.sql("SELECT commission_id FROM test_task WHERE id=:task")
                .param("task", view.taskId()).query(UUID.class).single();
        access.requireCommission(commissionId);
        return view;
    }

    public List<View> list(UUID commissionId) {
        access.requireCommission(commissionId);
        return jdbc.sql("""
                SELECT r.id, r.task_id, t.number task_number,
                  COALESCE(selection.parameter_name_snapshot, p.name) parameter_name,
                  r.revision, r.values_json::text, r.result, r.state, r.recorded_by,
                  r.equipment_id, r.version, r.submitted_at, r.confirmed_at, r.updated_at
                FROM test_record r JOIN test_task t ON t.id=r.task_id JOIN commission c ON c.id=t.commission_id
                  JOIN test_parameter p ON p.id=t.parameter_id
                  LEFT JOIN commission_parameter_selection selection ON selection.id=t.selection_id
                WHERE t.commission_id=:commission AND c.organization_id=:organization ORDER BY r.updated_at DESC
                """).param("commission", commissionId).param("organization", actor.require().organizationId())
                .query(this::map).list();
    }

    Map<String, Object> validateAndCalculate(UUID taskId, Map<String, Object> supplied) {
        if (supplied == null || supplied.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_VALUES_REQUIRED", "试验数据不能为空");
        }
        Parameter parameter = jdbc.sql("""
                SELECT p.definition_json::text, p.formula FROM test_parameter p JOIN test_task t ON t.parameter_id=p.id
                WHERE t.id=:task
                """).param("task", taskId).query((rs, row) -> new Parameter(rs.getString(1), rs.getString(2))).single();
        try {
            JsonNode definition = json.readTree(parameter.definition());
            JsonNode fields = definition.path("fields");
            if (fields.isArray()) {
                for (JsonNode field : fields) {
                    String key = field.path("key").asText();
                    if (field.path("required").asBoolean(false) && !supplied.containsKey(key)) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_FIELD_REQUIRED", "缺少必填检测字段：" + key);
                    }
                }
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("已发布参数定义无效", exception);
        }
        Map<String, Object> result = new LinkedHashMap<>(supplied);
        if (parameter.formula() != null && !parameter.formula().isBlank()) {
            Map<String, BigDecimal> numbers = new LinkedHashMap<>();
            supplied.forEach((key, value) -> {
                if (value instanceof Number || value instanceof String) {
                    try { numbers.put(key, new BigDecimal(value.toString())); } catch (NumberFormatException ignored) { }
                }
            });
            result.put("calculatedResult", formulas.evaluate(parameter.formula(), numbers).toPlainString());
        }
        return result;
    }

    private void ensureEquipmentValid(UUID equipmentId, UUID recordId) {
        if (equipmentId == null) throw ApiException.blocked("EQUIPMENT_REQUIRED", "试验记录必须关联设备", 0, List.of("未选择检测设备"));
        boolean valid = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM equipment e WHERE e.id=:id AND e.state IN ('AVAILABLE','IN_USE')
                  AND EXISTS(SELECT 1 FROM equipment_certificate c WHERE c.equipment_id=e.id AND c.result IN ('PASS','LIMITED')
                    AND c.issued_on<=current_date AND c.valid_until>=current_date))
                """).param("id", equipmentId).query(Boolean.class).single();
        if (!valid && !exceptions.allows("TEST_RECORD", recordId, "EQUIPMENT_NOT_VALID")) {
            throw ApiException.blocked("EQUIPMENT_NOT_VALID", "设备无有效检定或校准，禁止提交试验记录", 0,
                    List.of("设备状态不可用或有效证书已过期；如确需继续，应申请24小时内有效的质量例外"));
        }
    }

    private UUID actorPerson() {
        return jdbc.sql("SELECT person_id FROM app_user WHERE id=:id")
                .param("id", actor.require().id()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_PERSON_REQUIRED", "当前账号尚未关联人员档案"));
    }

    private void createNonconformance(View record, String result, String comment) {
        UUID commissionId = jdbc.sql("SELECT commission_id FROM test_task WHERE id=:task")
                .param("task", record.taskId()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO nonconformance(id, number, source_type, source_id, commission_id, severity,
                  description, state, opened_by)
                VALUES (:id, :number, 'TEST_RECORD', :record, :commission, 'HIGH', :description, 'OPEN', :actor)
                """).param("id", UUID.randomUUID()).param("number", numbering.next("NONCONFORMANCE", "NCR"))
                .param("record", record.id()).param("commission", commissionId)
                .param("description", resultDescription(result) + (comment == null ? "" : "：" + comment))
                .param("actor", actor.require().id()).update();
    }

    private String resultDescription(String result) {
        return switch (result) {
            case "NONCONFORMING" -> "试验结果判定为不合格";
            case "INVALID" -> "试验数据判定为无效";
            case "CONFORMING" -> "试验结果判定为合格";
            default -> "试验结果已判定";
        };
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("试验数据无法序列化", exception); }
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            Map<String, Object> values = json.readValue(rs.getString("values_json"), new TypeReference<>() {});
            return new View(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                    rs.getString("task_number"), rs.getString("parameter_name"), rs.getInt("revision"),
                    values, rs.getString("result"), TestRecordState.valueOf(rs.getString("state")),
                    rs.getObject("recorded_by", UUID.class), rs.getObject("equipment_id", UUID.class),
                    rs.getLong("version"), rs.getObject("submitted_at", OffsetDateTime.class),
                    rs.getObject("confirmed_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JacksonException exception) {
            throw new java.sql.SQLException("Invalid test record JSON", exception);
        }
    }

    private record Parameter(String definition, String formula) {}
    private record TaskInput(String state, UUID commissionId) {}
}
