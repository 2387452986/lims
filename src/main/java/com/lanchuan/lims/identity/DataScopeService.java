package com.lanchuan.lims.identity;

import com.lanchuan.lims.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DataScopeService {
    private static final UUID NO_ID = new UUID(0, 0);
    private final JdbcClient jdbc;
    private final CurrentActor actor;

    public DataScopeService(JdbcClient jdbc, CurrentActor actor) {
        this.jdbc = jdbc;
        this.actor = actor;
    }

    public void requireCommission(UUID commissionId) {
        if (!canAccessCommission(commissionId)) {
            throw ApiException.notFound("COMMISSION_NOT_FOUND", "委托不存在或不在当前数据范围内");
        }
    }

    public void requireProject(UUID projectId) {
        if (!canAccessProject(projectId)) {
            throw ApiException.notFound("PROJECT_NOT_FOUND", "工程不存在或不在当前数据范围内");
        }
    }

    public boolean canAccessProject(UUID projectId) {
        AuthenticatedUser user = actor.require();
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM project p WHERE p.id=:project AND p.organization_id=:organization AND (
                    :scope IN ('ALL','ORGANIZATION')
                    OR (:scope='PROJECT' AND EXISTS(
                      SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))
                    OR (:scope='DEPARTMENT' AND (
                      EXISTS(SELECT 1 FROM app_user creator
                        WHERE creator.id=p.created_by AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM commission c JOIN app_user creator ON creator.id=c.created_by
                        WHERE c.project_id=p.id AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM commission c JOIN test_task t ON t.commission_id=c.id
                        JOIN person pe ON pe.id=t.assigned_to
                        WHERE c.project_id=p.id AND pe.department_id=:department)))
                    OR (:scope='SELF' AND (p.created_by=:actor OR EXISTS(
                      SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)
                      OR EXISTS(SELECT 1 FROM commission c WHERE c.project_id=p.id AND c.created_by=:actor)
                      OR EXISTS(SELECT 1 FROM commission c JOIN test_task t ON t.commission_id=c.id
                        WHERE c.project_id=p.id AND t.assigned_to=:person)))
                  ))
                """).param("project", projectId).param("organization", user.organizationId())
                .param("scope", user.dataScope()).param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .query(Boolean.class).single();
    }

    public boolean canAccessCommission(UUID commissionId) {
        AuthenticatedUser user = actor.require();
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM commission c WHERE c.id=:commission AND c.organization_id=:organization AND (
                    :scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND (
                      EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=c.created_by AND creator.department_id=:department)
                      OR EXISTS(SELECT 1 FROM test_task t JOIN person p ON p.id=t.assigned_to
                                WHERE t.commission_id=c.id AND p.department_id=:department)))
                    OR (:scope='PROJECT' AND EXISTS(
                      SELECT 1 FROM project_member pm WHERE pm.project_id=c.project_id AND pm.user_id=:actor))
                    OR (:scope='SELF' AND (
                      c.created_by=:actor OR EXISTS(SELECT 1 FROM test_task t
                        WHERE t.commission_id=c.id AND t.assigned_to=:person)))
                  ))
                """).param("commission", commissionId).param("organization", user.organizationId())
                .param("scope", user.dataScope()).param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId())
                .query(Boolean.class).single();
    }
}
