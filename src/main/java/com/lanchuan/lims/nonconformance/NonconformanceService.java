package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NonconformanceService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ObjectMapper json;
    private final DataScopeService access;

    public NonconformanceService(JdbcClient jdbc, CurrentActor actor, NumberingService numbering,
                                 AuditService audit, ObjectMapper json, DataScopeService access) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.numbering = numbering;
        this.audit = audit;
        this.json = json;
        this.access = access;
    }

    public record Create(String sourceType, UUID sourceId, UUID commissionId, String severity, String description) {}
    public record AddAction(String actionType, String description, UUID ownerId, LocalDate dueOn) {}
    public record ActionTransition(String action, String comment, List<String> evidence) {}
    public record View(UUID id, String number, String sourceType, UUID sourceId, UUID commissionId,
                       String severity, String description, NonconformanceState state, UUID ownerId,
                       long version, OffsetDateTime updatedAt) {}
    public record ActionView(UUID id, UUID nonconformanceId, String actionType, String description, UUID ownerId,
                             LocalDate dueOn, String state, List<String> evidence, UUID verifiedBy, OffsetDateTime verifiedAt) {}

    @Transactional
    public View create(Create body) {
        if (!List.of("SAMPLE", "TEST_RECORD", "REPORT", "EQUIPMENT", "PROCESS").contains(body.sourceType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NONCONFORMANCE_SOURCE_INVALID", "不符合项来源类型无效");
        }
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(body.severity())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NONCONFORMANCE_SEVERITY_INVALID", "不符合项严重程度无效");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO nonconformance(id, number, source_type, source_id, commission_id, severity,
                  description, state, opened_by)
                VALUES (:id, :number, :sourceType, :sourceId, :commission, :severity, :description, 'OPEN', :actor)
                """).param("id", id).param("number", numbering.next("NONCONFORMANCE", "NCR"))
                .param("sourceType", body.sourceType()).param("sourceId", body.sourceId())
                .param("commission", body.commissionId()).param("severity", body.severity())
                .param("description", body.description()).param("actor", actor.require().id()).update();
        View created = require(id);
        audit.record("NONCONFORMANCE_CREATED", "NONCONFORMANCE", id, null, created, null, null);
        return created;
    }

    public List<View> list() {
        return jdbc.sql(baseSelect() + " WHERE c.organization_id=:organization ORDER BY n.created_at DESC")
                .param("organization", actor.require().organizationId()).query(this::map).list().stream()
                .filter(view -> access.canAccessCommission(view.commissionId())).toList();
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + " WHERE n.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("NONCONFORMANCE_NOT_FOUND", "不符合项不存在"));
        access.requireCommission(view.commissionId());
        return view;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "不符合项已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        NonconformanceState next = before.state().transition(action);
        List<String> blockers = blockers(id, before, action);
        if (!blockers.isEmpty()) throw ApiException.blocked("NONCONFORMANCE_BLOCKED", "不符合项尚不满足迁移条件", before.version(), blockers);
        int updated = jdbc.sql("""
                UPDATE nonconformance SET state=:state,
                  closed_at=CASE WHEN :state='CLOSED' THEN now() ELSE closed_at END,
                  version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next.name()).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "不符合项已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("NONCONFORMANCE_" + action, "NONCONFORMANCE", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions());
    }

    @Transactional
    public ActionView addAction(UUID nonconformanceId, AddAction body) {
        View nonconformance = require(nonconformanceId);
        if (!List.of(NonconformanceState.QUALITY_CONFIRMED, NonconformanceState.DISPOSITION_IN_PROGRESS)
                .contains(nonconformance.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTIVE_ACTION_PHASE_INVALID",
                    "仅质量确认后、提交独立验证前可以新增处置措施");
        }
        if (!List.of("CORRECTION", "CORRECTIVE", "PREVENTIVE").contains(body.actionType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACTION_TYPE_INVALID", "处置措施类型无效");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO corrective_action(id, nonconformance_id, action_type, description, owner_id, due_on, state)
                VALUES (:id, :nonconformance, :type, :description, :owner, :dueOn, 'PLANNED')
                """).param("id", id).param("nonconformance", nonconformanceId).param("type", body.actionType())
                .param("description", body.description()).param("owner", body.ownerId()).param("dueOn", body.dueOn()).update();
        ActionView created = action(id);
        audit.record("CORRECTIVE_ACTION_CREATED", "CORRECTIVE_ACTION", id, null, created, null, null);
        return created;
    }

    @Transactional
    public ActionView transitionAction(UUID id, ActionTransition body) {
        ActionView before = action(id);
        String action = body.action().trim().toUpperCase();
        NonconformanceState nonconformanceState = require(before.nonconformanceId()).state();
        if (List.of("START", "COMPLETE", "RESTART").contains(action)
                && nonconformanceState != NonconformanceState.DISPOSITION_IN_PROGRESS) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTIVE_ACTION_PHASE_INVALID",
                    "处置措施只能在不符合项处置阶段执行");
        }
        if (List.of("START", "COMPLETE", "RESTART").contains(action)
                && !actorPerson().equals(before.ownerId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CORRECTIVE_ACTION_OWNER_REQUIRED",
                    "只有措施责任人可以执行或重新执行处置措施");
        }
        if (List.of("VERIFY", "REJECT").contains(action)
                && nonconformanceState != NonconformanceState.PENDING_VERIFICATION) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTIVE_ACTION_VERIFICATION_PHASE_REQUIRED",
                    "不符合项提交独立验证后才能验证处置措施");
        }
        String next = switch (before.state()) {
            case "PLANNED" -> action.equals("START") ? "IN_PROGRESS" : invalidAction(before.state(), action);
            case "IN_PROGRESS" -> action.equals("COMPLETE") ? "COMPLETED" : invalidAction(before.state(), action);
            case "COMPLETED" -> switch (action) { case "VERIFY" -> "VERIFIED"; case "REJECT" -> "REJECTED"; default -> invalidAction(before.state(), action); };
            case "REJECTED" -> action.equals("RESTART") ? "IN_PROGRESS" : invalidAction(before.state(), action);
            default -> invalidAction(before.state(), action);
        };
        if (action.equals("COMPLETE") && (body.evidence() == null || body.evidence().isEmpty())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACTION_EVIDENCE_REQUIRED", "完成处置措施必须提交证据");
        }
        UUID verifier = null;
        if (action.equals("VERIFY")) {
            verifier = actorPerson();
            if (verifier.equals(before.ownerId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "INDEPENDENT_VERIFIER_REQUIRED", "措施责任人不能验证自己的处置结果");
            }
        }
        jdbc.sql("""
                UPDATE corrective_action SET state=:state,
                  evidence_json=COALESCE(CAST(:evidence AS jsonb), evidence_json),
                  verified_by=:verifier, verified_at=CASE WHEN :state='VERIFIED' THEN now() ELSE NULL END,
                  updated_at=now() WHERE id=:id
                """).param("state", next).param("evidence", body.evidence() == null ? null : encode(body.evidence()))
                .param("verifier", verifier).param("id", id).update();
        ActionView after = action(id);
        audit.record("CORRECTIVE_ACTION_" + action, "CORRECTIVE_ACTION", id, before, after, null, body.comment());
        return after;
    }

    public List<ActionView> actions(UUID nonconformanceId) {
        require(nonconformanceId);
        return jdbc.sql(actionSelect() + " WHERE nonconformance_id=:id ORDER BY created_at")
                .param("id", nonconformanceId).query(this::mapAction).list();
    }

    private ActionView action(UUID id) {
        return jdbc.sql(actionSelect() + " WHERE id=:id").param("id", id).query(this::mapAction).optional()
                .orElseThrow(() -> ApiException.notFound("CORRECTIVE_ACTION_NOT_FOUND", "处置措施不存在"));
    }

    private List<String> blockers(UUID id, View before, String action) {
        List<String> blockers = new ArrayList<>();
        if (action.equals("SUBMIT_VERIFICATION")) {
            long incomplete = jdbc.sql("SELECT count(*) FROM corrective_action WHERE nonconformance_id=:id AND state <> 'COMPLETED'")
                    .param("id", id).query(Long.class).single();
            long count = jdbc.sql("SELECT count(*) FROM corrective_action WHERE nonconformance_id=:id")
                    .param("id", id).query(Long.class).single();
            if (count == 0) blockers.add("至少需要一项处置措施");
            if (incomplete > 0) blockers.add("仍有未完成的处置措施：" + incomplete);
        }
        if (action.equals("CLOSE")) {
            long unverified = jdbc.sql("SELECT count(*) FROM corrective_action WHERE nonconformance_id=:id AND state <> 'VERIFIED'")
                    .param("id", id).query(Long.class).single();
            if (unverified > 0) blockers.add("仍有未独立验证的处置措施：" + unverified);
        }
        return blockers;
    }

    private UUID actorPerson() {
        return jdbc.sql("SELECT person_id FROM app_user WHERE id=:id")
                .param("id", actor.require().id()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_PERSON_REQUIRED", "账号尚未关联人员档案"));
    }

    private String invalidAction(String state, String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_CORRECTIVE_ACTION_TRANSITION",
                "处置措施状态 %s 不允许动作 %s".formatted(state, action));
    }

    private String baseSelect() {
        return """
                SELECT n.id, n.number, n.source_type, n.source_id, n.commission_id, n.severity, n.description,
                  n.state, n.owner_id, n.version, n.updated_at FROM nonconformance n
                  JOIN commission c ON c.id=n.commission_id
                """;
    }

    private String actionSelect() {
        return """
                SELECT id, nonconformance_id, action_type, description, owner_id, due_on, state,
                  evidence_json::text, verified_by, verified_at FROM corrective_action
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new View(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getString("severity"), localizeGeneratedDescription(rs.getString("description")), NonconformanceState.valueOf(rs.getString("state")),
                rs.getObject("owner_id", UUID.class), rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String localizeGeneratedDescription(String description) {
        return description.replace("试验结果为NONCONFORMING", "试验结果判定为不合格")
                .replace("试验结果为INVALID", "试验数据判定为无效")
                .replace("试验结果为CONFORMING", "试验结果判定为合格");
    }

    private ActionView mapAction(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            List<String> evidence = json.readValue(rs.getString("evidence_json"), json.getTypeFactory().constructCollectionType(List.class, String.class));
            return new ActionView(rs.getObject("id", UUID.class), rs.getObject("nonconformance_id", UUID.class),
                    rs.getString("action_type"), rs.getString("description"), rs.getObject("owner_id", UUID.class),
                    rs.getObject("due_on", LocalDate.class), rs.getString("state"), evidence,
                    rs.getObject("verified_by", UUID.class), rs.getObject("verified_at", OffsetDateTime.class));
        } catch (JacksonException exception) {
            throw new java.sql.SQLException("Invalid corrective action evidence", exception);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("证据无法序列化", exception); }
    }
}
