package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reference-data")
public class ReferenceDataController {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final DataScopeService access;

    public ReferenceDataController(JdbcClient jdbc, CurrentActor actor, DataScopeService access) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.access = access;
    }

    public record Option(UUID id, String code, String label) {}
    public record ReferenceData(List<Option> projects, List<Option> customers, List<Option> parameters,
                                List<Option> people, List<Option> equipment) {}

    @GetMapping
    public ReferenceData get(@RequestParam(required = false) UUID commissionId) {
        AuthenticatedUser user = actor.require();
        UUID organization = user.organizationId();
        UUID noId = new UUID(0, 0);
        List<Option> projects = jdbc.sql("""
                SELECT p.id, p.number, p.name FROM project p
                WHERE p.organization_id=:organization AND p.status='ACTIVE' AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (:scope='PROJECT' AND EXISTS(
                    SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))
                  OR (:scope='DEPARTMENT' AND EXISTS(
                    SELECT 1 FROM commission c WHERE c.project_id=p.id AND (
                      EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=c.created_by AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM test_task t JOIN person pe ON pe.id=t.assigned_to
                                WHERE t.commission_id=c.id AND pe.department_id=:department))))
                  OR (:scope='SELF' AND EXISTS(
                    SELECT 1 FROM commission c WHERE c.project_id=p.id AND (c.created_by=:actor
                      OR EXISTS(SELECT 1 FROM test_task t WHERE t.commission_id=c.id AND t.assigned_to=:person))))
                ) ORDER BY p.name
                """).param("organization", organization).param("scope", user.dataScope())
                .param("actor", user.id()).param("department", user.departmentId() == null ? noId : user.departmentId())
                .param("person", user.personId() == null ? noId : user.personId())
                .query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        List<Option> customers = jdbc.sql("""
                SELECT u.id, u.code, u.name FROM customer u WHERE u.active AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR EXISTS(SELECT 1 FROM commission c WHERE c.customer_id=u.id AND c.organization_id=:organization AND (
                    (:scope='PROJECT' AND EXISTS(
                      SELECT 1 FROM project_member pm WHERE pm.project_id=c.project_id AND pm.user_id=:actor))
                    OR (:scope='DEPARTMENT' AND (
                      EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=c.created_by AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM test_task t JOIN person pe ON pe.id=t.assigned_to
                                WHERE t.commission_id=c.id AND pe.department_id=:department)))
                    OR (:scope='SELF' AND (c.created_by=:actor OR EXISTS(
                      SELECT 1 FROM test_task t WHERE t.commission_id=c.id AND t.assigned_to=:person)))
                  ))
                ) ORDER BY u.name
                """).param("organization", organization).param("scope", user.dataScope())
                .param("actor", user.id()).param("department", user.departmentId() == null ? noId : user.departmentId())
                .param("person", user.personId() == null ? noId : user.personId())
                .query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        List<Option> parameters;
        if (commissionId == null) {
            parameters = jdbc.sql("""
                    SELECT DISTINCT ON (p.code) p.id, p.code, p.name FROM test_parameter p
                    LEFT JOIN detection_project_revision r ON r.id=p.project_revision_id
                    LEFT JOIN detection_catalog_node n ON n.id=r.project_node_id
                    WHERE p.status='PUBLISHED' AND p.enabled
                      AND (n.id IS NULL OR n.status='ACTIVE')
                    ORDER BY p.code, p.version_number DESC
                    """).query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        } else {
            access.requireCommission(commissionId);
            parameters = jdbc.sql("""
                    SELECT DISTINCT ON (selection.parameter_id) selection.parameter_id,
                      selection.parameter_code_snapshot, selection.parameter_name_snapshot
                    FROM commission_parameter_selection selection
                    JOIN commission_detection_selection project_selection
                      ON project_selection.id=selection.detection_selection_id
                    WHERE project_selection.commission_id=:commission
                    ORDER BY selection.parameter_id, selection.sort_order
                    """).param("commission", commissionId)
                    .query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        }
        List<Option> people = jdbc.sql("""
                SELECT p.id, p.employee_number, p.name FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND p.active ORDER BY p.name
                """).param("organization", organization).query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        List<Option> equipment = jdbc.sql("""
                SELECT e.id, e.code, e.name FROM equipment e
                WHERE e.organization_id=:organization AND e.state<>'SCRAPPED'
                ORDER BY EXISTS(
                  SELECT 1 FROM equipment_certificate certificate
                  WHERE certificate.equipment_id=e.id AND certificate.result IN ('PASS','LIMITED')
                    AND certificate.issued_on<=current_date AND certificate.valid_until>=current_date
                ) DESC, e.code
                """)
                .param("organization", organization).query((rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        return new ReferenceData(projects, customers, parameters, people, equipment);
    }
}
