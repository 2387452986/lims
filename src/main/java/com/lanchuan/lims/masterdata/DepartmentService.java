package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DepartmentService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public DepartmentService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record PersonOption(UUID id, String employeeNumber, String name, UUID departmentId,
                               String departmentName, String position, String personType) {}
    public record Options(List<PersonOption> people) {}
    public record View(UUID id, String code, String name, UUID parentId, String parentName, String path,
                       int sortOrder, UUID responsiblePersonId, String responsiblePersonName,
                       UUID supervisingPersonId, String supervisingPersonName, boolean active, long version,
                       OffsetDateTime retiredAt, String retiredReason, List<UUID> detectionProjectNodeIds,
                       long detectionProjectCount, long memberCount, long childCount,
                       List<String> allowedActions, List<String> retirementBlockers) {}
    public record Create(String code, String name, UUID parentId, int sortOrder,
                         UUID responsiblePersonId, UUID supervisingPersonId,
                         List<UUID> detectionProjectNodeIds) {}
    public record Update(String name, UUID parentId, int sortOrder,
                         UUID responsiblePersonId, UUID supervisingPersonId,
                         List<UUID> detectionProjectNodeIds, long expectedVersion) {}

    public List<View> list() {
        AuthenticatedUser user = actor.require();
        Map<UUID, List<UUID>> projects = projectBindings(user.organizationId());
        return jdbc.sql("""
                WITH RECURSIVE paths AS (
                  SELECT d.id,d.parent_id,d.name,d.name::text path
                  FROM department d WHERE d.organization_id=:organization AND d.parent_id IS NULL
                  UNION ALL
                  SELECT child.id,child.parent_id,child.name,paths.path || ' / ' || child.name
                  FROM department child JOIN paths ON paths.id=child.parent_id
                  WHERE child.organization_id=:organization
                )
                SELECT d.id,d.code,d.name,d.parent_id,parent.name parent_name,
                  COALESCE(paths.path,d.name) path,d.sort_order,
                  d.responsible_person_id,responsible.name responsible_person_name,
                  d.supervising_person_id,supervising.name supervising_person_name,
                  d.active,d.version,d.retired_at,d.retired_reason,
                  (SELECT count(*) FROM person member WHERE member.department_id=d.id AND member.active) member_count,
                  (SELECT count(*) FROM department child WHERE child.parent_id=d.id AND child.active) child_count,
                  (SELECT count(*) FROM app_user account
                    WHERE account.department_id=d.id AND account.enabled AND account.person_id IS NULL) account_count,
                  (SELECT count(*) FROM commission work
                    WHERE work.received_department_id=d.id
                      AND work.state NOT IN ('ARCHIVED','REJECTED','CANCELLED')) commission_count
                FROM department d
                LEFT JOIN department parent ON parent.id=d.parent_id
                LEFT JOIN paths ON paths.id=d.id
                LEFT JOIN person responsible ON responsible.id=d.responsible_person_id
                LEFT JOIN person supervising ON supervising.id=d.supervising_person_id
                WHERE d.organization_id=:organization
                ORDER BY d.active DESC,d.sort_order,lower(d.name),d.id
                """).param("organization", user.organizationId()).query((rs, row) -> map(rs, projects)).list();
    }

    public View require(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DEPARTMENT_NOT_FOUND", "部门不存在"));
    }

    public Options options() {
        AuthenticatedUser user = actor.require();
        List<PersonOption> people = jdbc.sql("""
                SELECT p.id,p.employee_number,p.name,p.department_id,d.name department_name,p.position,p.person_type
                FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND p.active AND d.active
                ORDER BY d.sort_order,lower(d.name),lower(p.name),p.employee_number
                """).param("organization", user.organizationId()).query((rs, row) -> new PersonOption(
                        rs.getObject("id", UUID.class), rs.getString("employee_number"), rs.getString("name"),
                        rs.getObject("department_id", UUID.class), rs.getString("department_name"),
                        rs.getString("position"), rs.getString("person_type"))).list();
        return new Options(people);
    }

    @Transactional
    public View create(Create body) {
        AuthenticatedUser user = actor.require();
        UUID id = UUID.randomUUID();
        String code = normalizeCode(body.code(), id);
        String name = requireText(body.name(), "DEPARTMENT_NAME_REQUIRED", "部门名称不能为空");
        validateSortOrder(body.sortOrder());
        validateParent(body.parentId(), null, user.organizationId());
        validatePeople(body.responsiblePersonId(), body.supervisingPersonId(), user.organizationId());
        List<UUID> projects = validateProjects(body.detectionProjectNodeIds());
        ensureCodeAvailable(code, null, user.organizationId());
        ensureNameAvailable(name, body.parentId(), null, user.organizationId());
        jdbc.sql("""
                INSERT INTO department(id,organization_id,parent_id,code,name,sort_order,
                  responsible_person_id,supervising_person_id)
                VALUES (:id,:organization,:parent,:code,:name,:sortOrder,:responsible,:supervising)
                """).param("id", id).param("organization", user.organizationId()).param("parent", body.parentId())
                .param("code", code).param("name", name).param("sortOrder", body.sortOrder())
                .param("responsible", body.responsiblePersonId()).param("supervising", body.supervisingPersonId())
                .update();
        replaceProjects(id, projects);
        View created = require(id);
        audit.record("DEPARTMENT_CREATED", "DEPARTMENT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Update body) {
        View before = require(id);
        if (!before.active()) throw invalidTransition(before, "UPDATE");
        String name = requireText(body.name(), "DEPARTMENT_NAME_REQUIRED", "部门名称不能为空");
        validateSortOrder(body.sortOrder());
        validateParent(body.parentId(), id, actor.require().organizationId());
        validatePeople(body.responsiblePersonId(), body.supervisingPersonId(), actor.require().organizationId());
        List<UUID> projects = validateProjects(body.detectionProjectNodeIds());
        ensureNameAvailable(name, body.parentId(), id, actor.require().organizationId());
        int changed = jdbc.sql("""
                UPDATE department SET name=:name,parent_id=:parent,sort_order=:sortOrder,
                  responsible_person_id=:responsible,supervising_person_id=:supervising,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("name", name).param("parent", body.parentId()).param("sortOrder", body.sortOrder())
                .param("responsible", body.responsiblePersonId()).param("supervising", body.supervisingPersonId())
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "部门配置已被其他用户更新", require(id).version());
        replaceProjects(id, projects);
        View after = require(id);
        audit.record("DEPARTMENT_UPDATED", "DEPARTMENT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "部门配置已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        if (action.equals("DELETE")) action = "RETIRE";
        if (action.equals("RETIRE")) {
            if (!before.active()) throw invalidTransition(before, action);
            String reason = requireText(command.comment(), "DEPARTMENT_RETIRE_REASON_REQUIRED", "停用部门必须填写原因");
            List<String> blockers = retirementBlockers(id);
            if (!blockers.isEmpty()) {
                throw ApiException.blocked("DEPARTMENT_RETIRE_BLOCKED", "部门仍有在用关联，不能停用", before.version(), blockers);
            }
            changeState(id, before.version(), false, reason);
        } else if (action.equals("RESTORE")) {
            if (before.active()) throw invalidTransition(before, action);
            if (before.parentId() != null) {
                View parent = require(before.parentId());
                if (!parent.active()) throw ApiException.blocked("DEPARTMENT_PARENT_INACTIVE", "上级部门已停用，不能恢复当前部门",
                        before.version(), List.of("请先恢复上级部门：" + parent.name()));
            }
            ensureNameAvailable(before.name(), before.parentId(), before.id(), actor.require().organizationId());
            changeState(id, before.version(), true, null);
        } else {
            throw invalidTransition(before, action);
        }
        View after = require(id);
        audit.record("DEPARTMENT_" + action, "DEPARTMENT", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.active() ? "ACTIVE" : "RETIRED", after.version(), after.allowedActions());
    }

    private void changeState(UUID id, long version, boolean active, String reason) {
        int changed = jdbc.sql("""
                UPDATE department SET active=:active,
                  retired_at=CASE WHEN :active THEN NULL ELSE now() END,
                  retired_reason=CASE WHEN :active THEN NULL ELSE :reason END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("active", active).param("reason", reason).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", version).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "部门配置已被其他用户更新", require(id).version());
    }

    private View map(ResultSet rs, Map<UUID, List<UUID>> projects) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        boolean active = rs.getBoolean("active");
        long memberCount = rs.getLong("member_count");
        long childCount = rs.getLong("child_count");
        long accountCount = rs.getLong("account_count");
        long commissionCount = rs.getLong("commission_count");
        List<UUID> bindings = projects.getOrDefault(id, List.of());
        List<String> blockers = active
                ? retirementBlockers(memberCount, childCount, accountCount, commissionCount) : List.of();
        return new View(id, rs.getString("code"), rs.getString("name"),
                rs.getObject("parent_id", UUID.class), rs.getString("parent_name"), rs.getString("path"),
                rs.getInt("sort_order"), rs.getObject("responsible_person_id", UUID.class),
                rs.getString("responsible_person_name"), rs.getObject("supervising_person_id", UUID.class),
                rs.getString("supervising_person_name"), active, rs.getLong("version"),
                rs.getObject("retired_at", OffsetDateTime.class), rs.getString("retired_reason"), bindings,
                bindings.size(), memberCount, childCount,
                active ? List.of("UPDATE", "ADD_CHILD", "RETIRE") : List.of("RESTORE"), blockers);
    }

    private Map<UUID, List<UUID>> projectBindings(UUID organizationId) {
        Map<UUID, List<UUID>> bindings = new HashMap<>();
        jdbc.sql("""
                SELECT link.department_id,link.project_node_id
                FROM department_detection_project link
                JOIN department d ON d.id=link.department_id
                JOIN detection_catalog_node node ON node.id=link.project_node_id
                WHERE d.organization_id=:organization
                  AND node.node_type='PROJECT' AND node.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM detection_project_revision revision
                    WHERE revision.project_node_id=node.id AND revision.status='PUBLISHED')
                ORDER BY link.department_id,node.sort_order,lower(node.name),node.id
                """).param("organization", organizationId).query((rs, row) -> {
                    bindings.computeIfAbsent(rs.getObject("department_id", UUID.class), ignored -> new ArrayList<>())
                            .add(rs.getObject("project_node_id", UUID.class));
                    return 0;
                }).list();
        return bindings;
    }

    private List<String> retirementBlockers(UUID id) {
        return jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM person WHERE department_id=:id AND active) members,
                  (SELECT count(*) FROM department WHERE parent_id=:id AND active) children,
                  (SELECT count(*) FROM app_user WHERE department_id=:id AND enabled AND person_id IS NULL) accounts,
                  (SELECT count(*) FROM commission WHERE received_department_id=:id
                    AND state NOT IN ('ARCHIVED','REJECTED','CANCELLED')) commissions
                """).param("id", id).query((rs, row) -> retirementBlockers(
                        rs.getLong("members"), rs.getLong("children"), rs.getLong("accounts"),
                        rs.getLong("commissions"))).single();
    }

    private List<String> retirementBlockers(long members, long children, long accounts, long commissions) {
        List<String> blockers = new ArrayList<>();
        if (children > 0) blockers.add("仍有 " + children + " 个启用的下级部门");
        if (members > 0) blockers.add("仍有 " + members + " 名在岗人员归属该部门");
        if (accounts > 0) blockers.add("仍有 " + accounts + " 个未绑定人员的启用账号归属该部门");
        if (commissions > 0) blockers.add("仍有 " + commissions + " 份在途委托归属该部门");
        return List.copyOf(blockers);
    }

    private void validateParent(UUID parentId, UUID self, UUID organizationId) {
        if (parentId == null) return;
        if (parentId.equals(self)) throw invalid("DEPARTMENT_PARENT_INVALID", "部门不能以自身作为上级");
        View parent = require(parentId);
        if (!parent.active()) throw invalid("DEPARTMENT_PARENT_INACTIVE", "上级部门已停用");
        if (self == null) return;
        boolean cycle = jdbc.sql("""
                WITH RECURSIVE descendants AS (
                  SELECT id FROM department WHERE parent_id=:self AND organization_id=:organization
                  UNION ALL
                  SELECT child.id FROM department child JOIN descendants d ON child.parent_id=d.id
                  WHERE child.organization_id=:organization)
                SELECT EXISTS(SELECT 1 FROM descendants WHERE id=:parent)
                """).param("self", self).param("organization", organizationId).param("parent", parentId)
                .query(Boolean.class).single();
        if (cycle) throw invalid("DEPARTMENT_CYCLE", "部门层级不能形成循环");
    }

    private void validatePeople(UUID responsible, UUID supervising, UUID organizationId) {
        Set<UUID> people = new LinkedHashSet<>();
        if (responsible != null) people.add(responsible);
        if (supervising != null) people.add(supervising);
        if (people.isEmpty()) return;
        long valid = jdbc.sql("""
                SELECT count(*) FROM person p JOIN department d ON d.id=p.department_id
                WHERE p.id IN (:people) AND p.active AND d.active AND d.organization_id=:organization
                """).param("people", people).param("organization", organizationId).query(Long.class).single();
        if (valid != people.size()) throw invalid("DEPARTMENT_PERSON_INVALID", "负责人必须是当前机构启用人员");
    }

    private List<UUID> validateProjects(List<UUID> requested) {
        List<UUID> projects = requested == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(requested));
        if (projects.isEmpty()) return projects;
        long valid = jdbc.sql("""
                SELECT count(*) FROM detection_catalog_node node
                WHERE node.id IN (:projects) AND node.node_type='PROJECT' AND node.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM detection_project_revision revision
                    WHERE revision.project_node_id=node.id AND revision.status='PUBLISHED')
                """).param("projects", projects).query(Long.class).single();
        if (valid != projects.size()) throw invalid("DEPARTMENT_PROJECT_INVALID", "只能关联启用且已发布的检测项目");
        return List.copyOf(projects);
    }

    private void replaceProjects(UUID departmentId, List<UUID> projects) {
        jdbc.sql("DELETE FROM department_detection_project WHERE department_id=:department")
                .param("department", departmentId).update();
        for (UUID project : projects) {
            jdbc.sql("INSERT INTO department_detection_project(department_id,project_node_id) VALUES (:department,:project)")
                    .param("department", departmentId).param("project", project).update();
        }
    }

    private void ensureCodeAvailable(String code, UUID self, UUID organizationId) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM department WHERE organization_id=:organization
                  AND lower(code)=lower(:code) AND (CAST(:self AS uuid) IS NULL OR id<>CAST(:self AS uuid)))
                """).param("organization", organizationId).param("code", code).param("self", self)
                .query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_CODE_EXISTS", "部门代码已存在");
    }

    private void ensureNameAvailable(String name, UUID parentId, UUID self, UUID organizationId) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM department WHERE organization_id=:organization AND active
                  AND parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid) AND lower(name)=lower(:name)
                  AND (CAST(:self AS uuid) IS NULL OR id<>CAST(:self AS uuid)))
                """).param("organization", organizationId).param("parent", parentId).param("name", name)
                .param("self", self).query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_NAME_EXISTS", "同级部门名称已存在");
    }

    private String normalizeCode(String value, UUID id) {
        if (value == null || value.isBlank()) return "DEP-" + id.toString().substring(0, 8).toUpperCase();
        String code = value.trim().toUpperCase();
        if (code.length() > 40) throw invalid("DEPARTMENT_CODE_TOO_LONG", "部门代码不能超过40个字符");
        return code;
    }

    private String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) throw invalid(code, message);
        return value.trim();
    }

    private void validateSortOrder(int value) {
        if (value < 0 || value > 999999) throw invalid("DEPARTMENT_SORT_INVALID", "排序必须在0到999999之间");
    }

    private ApiException invalidTransition(View before, String action) {
        return new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_TRANSITION_INVALID",
                "部门状态 %s 不允许动作 %s".formatted(before.active() ? "ACTIVE" : "RETIRED", action));
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
