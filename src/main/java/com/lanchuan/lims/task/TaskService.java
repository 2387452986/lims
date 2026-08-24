package com.lanchuan.lims.task;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.configuration.DocumentControlSnapshotService;
import com.lanchuan.lims.configuration.SystemExperienceService;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import com.lanchuan.lims.nonconformance.ExceptionApprovalService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ExceptionApprovalService exceptions;
    private final DataScopeService access;
    private final SystemExperienceService experience;
    private final DocumentControlSnapshotService documentControls;

    public TaskService(JdbcClient jdbc, CurrentActor actor, NumberingService numbering, AuditService audit,
                       ExceptionApprovalService exceptions, DataScopeService access,
                       SystemExperienceService experience, DocumentControlSnapshotService documentControls) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.numbering = numbering;
        this.audit = audit;
        this.exceptions = exceptions;
        this.access = access;
        this.experience = experience;
        this.documentControls = documentControls;
    }

    public record Create(UUID commissionId, UUID sampleId, UUID parameterId, OffsetDateTime dueAt) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment, UUID assignedTo) {}
    public record View(UUID id, UUID commissionId, UUID sampleId, String sampleNumber, UUID parameterId,
                       String parameterName, String number, UUID assignedTo, String assigneeName,
                       OffsetDateTime dueAt, TaskState state, long version, OffsetDateTime updatedAt) {}

    @Transactional
    public View create(Create body) {
        access.requireCommission(body.commissionId());
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO test_task(id, commission_id, sample_id, parameter_id, selection_id, number, due_at, state)
                SELECT :id, c.id, s.id, p.id, selection.id, :number, :dueAt, 'UNASSIGNED'
                FROM commission c JOIN sample s ON s.commission_id=c.id CROSS JOIN test_parameter p
                  JOIN commission_parameter_selection selection
                    ON selection.parameter_id=p.id AND selection.sample_group_id=s.sample_group_id
                  JOIN commission_detection_selection project_selection
                    ON project_selection.id=selection.detection_selection_id AND project_selection.commission_id=c.id
                WHERE c.id=:commission AND s.id=:sample AND p.id=:parameter
                  AND c.organization_id=:organization AND c.state IN ('ASSIGNED','TESTING')
                """).param("id", id).param("number", numbering.next("TASK", "RW"))
                .param("dueAt", body.dueAt()).param("commission", body.commissionId()).param("sample", body.sampleId())
                .param("parameter", body.parameterId()).param("organization", actor.require().organizationId()).update();
        if (inserted == 0) {
            audit.recordRejected("TASK_SOURCE_REJECTED", "COMMISSION", body.commissionId(), body,
                    "TASK_SOURCE_INVALID", "任务参数不属于委托冻结快照或当前样品组");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_SOURCE_INVALID",
                    "任务只能使用该委托已冻结且分配到当前样品组的检测参数");
        }
        documentControls.snapshotTask(id);
        View created = require(id);
        audit.record("TASK_CREATED", "TEST_TASK", id, null, created, null, null);
        return created;
    }

    public List<View> list(UUID commissionId) {
        access.requireCommission(commissionId);
        return jdbc.sql(baseSelect() + " WHERE t.commission_id=:commission AND c.organization_id=:organization ORDER BY t.created_at")
                .param("commission", commissionId).param("organization", actor.require().organizationId())
                .query(this::map).list();
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + " WHERE t.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "检测任务不存在"));
        access.requireCommission(view.commissionId());
        return view;
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "任务已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        TaskState next = before.state().transition(action);
        UUID nextAssignee = before.assignedTo();
        if (action.equals("ASSIGN") || action.equals("REASSIGN")) {
            if (command.assignedTo() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSIGNEE_REQUIRED", "分配或改派必须指定检测人员");
            }
            ensureDepartmentProjectAllowed(command.assignedTo(), before.parameterId(), before.id(),
                    before.version(), action);
            ensureQualified(command.assignedTo(), before.parameterId(), before.id());
            nextAssignee = command.assignedTo();
        }
        if (action.equals("REJECT")) {
            if (command.comment() == null || command.comment().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTION_REASON_REQUIRED", "拒绝任务必须填写原因");
            }
            nextAssignee = null;
        }
        if (action.equals("ACCEPT")) experience.requireTaskAcceptanceBriefings(id);
        List<String> blockers = blockers(before, action);
        if (!blockers.isEmpty()) throw ApiException.blocked("TASK_BLOCKED", "任务尚不满足迁移条件", before.version(), blockers);
        int changed = jdbc.sql("""
                UPDATE test_task SET state=:state, assigned_to=:assignedTo,
                  assigned_by=CASE WHEN :assignmentAction THEN :actor ELSE assigned_by END,
                  rejection_reason=CASE WHEN :action='REJECT' THEN :comment ELSE rejection_reason END,
                  accepted_at=CASE WHEN :action='ACCEPT' THEN now() ELSE accepted_at END,
                  started_at=CASE WHEN :action='START' THEN now() ELSE started_at END,
                  completed_at=CASE WHEN :action='COMPLETE' THEN now() ELSE completed_at END,
                  version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next.name()).param("assignedTo", nextAssignee)
                .param("assignmentAction", action.equals("ASSIGN") || action.equals("REASSIGN"))
                .param("actor", actor.require().id()).param("action", action).param("comment", command.comment())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "任务已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("TASK_" + action, "TEST_TASK", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions());
    }

    private void ensureQualified(UUID personId, UUID parameterId, UUID taskId) {
        boolean qualified = jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM person p JOIN test_parameter tp ON tp.id=:parameter
                  WHERE p.id=:person AND p.active
                    AND (tp.definition_json->>'qualificationCode' IS NULL OR EXISTS(
                      SELECT 1 FROM person_qualification q WHERE q.person_id=p.id AND q.status='VALID'
                        AND q.valid_from<=current_date AND q.valid_until>=current_date
                        AND q.qualification_code=tp.definition_json->>'qualificationCode')))
                """).param("parameter", parameterId).param("person", personId).query(Boolean.class).single();
        if (!qualified && !exceptions.allows("TEST_TASK", taskId, "PERSON_NOT_QUALIFIED")) {
            throw ApiException.blocked("PERSON_NOT_QUALIFIED", "人员资格无效，禁止分配检测任务", 0,
                    List.of("检测人员缺少当前有效的参数所需资格；如确需继续，应申请24小时内有效的质量例外"));
        }
    }

    private void ensureDepartmentProjectAllowed(UUID personId, UUID parameterId, UUID taskId,
                                                long currentVersion, String action) {
        boolean allowed = jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1
                  FROM person person
                  JOIN department department ON department.id=person.department_id
                  JOIN test_parameter parameter ON parameter.id=:parameter
                  JOIN detection_project_revision revision ON revision.id=parameter.project_revision_id
                  JOIN department_detection_project scope
                    ON scope.department_id=department.id AND scope.project_node_id=revision.project_node_id
                  WHERE person.id=:person AND person.active AND department.active)
                """).param("person", personId).param("parameter", parameterId).query(Boolean.class).single();
        if (!allowed) {
            audit.recordRejected("TASK_DEPARTMENT_PROJECT_SCOPE_REJECTED", "TEST_TASK", taskId,
                    Map.of("action", action, "assigneeId", personId, "parameterId", parameterId,
                            "expectedVersion", currentVersion),
                    "ASSIGNEE_DEPARTMENT_PROJECT_NOT_ALLOWED",
                    "检测人员所属部门未关联当前检测项目");
            throw ApiException.blocked("ASSIGNEE_DEPARTMENT_PROJECT_NOT_ALLOWED",
                    "检测人员所属部门未关联当前检测项目，禁止分配任务", currentVersion,
                    List.of("请在基础数据 → 部门配置中为该部门勾选当前检测项目"));
        }
    }

    private List<String> blockers(View before, String action) {
        List<String> blockers = new ArrayList<>();
        if (action.equals("START")) {
            ensureDepartmentProjectAllowed(before.assignedTo(), before.parameterId(), before.id(),
                    before.version(), action);
            ensureQualified(before.assignedTo(), before.parameterId(), before.id());
            String sampleState = jdbc.sql("SELECT state FROM sample WHERE id=:id")
                    .param("id", before.sampleId()).query(String.class).single();
            if (!List.of("HANDED_OVER", "IN_TEST").contains(sampleState)) blockers.add("样品尚未交接到检测环节");
        }
        if (action.equals("COMPLETE")) {
            long confirmed = jdbc.sql("SELECT count(*) FROM test_record WHERE task_id=:id AND state='CONFIRMED'")
                    .param("id", before.id()).query(Long.class).single();
            if (confirmed == 0) blockers.add("尚无已确认的试验记录");
        }
        return blockers;
    }

    private String baseSelect() {
        return """
                SELECT t.id, t.commission_id, t.sample_id, s.number sample_number, t.parameter_id,
                  COALESCE(selection.parameter_name_snapshot,p.name) parameter_name,
                  t.number, t.assigned_to, pe.name assignee_name, t.due_at,
                  t.state, t.version, t.updated_at
                FROM test_task t JOIN commission c ON c.id=t.commission_id JOIN sample s ON s.id=t.sample_id
                  JOIN test_parameter p ON p.id=t.parameter_id
                  LEFT JOIN commission_parameter_selection selection ON selection.id=t.selection_id
                  LEFT JOIN person pe ON pe.id=t.assigned_to
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new View(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getObject("sample_id", UUID.class), rs.getString("sample_number"),
                rs.getObject("parameter_id", UUID.class), rs.getString("parameter_name"), rs.getString("number"),
                rs.getObject("assigned_to", UUID.class), rs.getString("assignee_name"),
                rs.getObject("due_at", OffsetDateTime.class), TaskState.valueOf(rs.getString("state")),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
