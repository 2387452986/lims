package com.lanchuan.lims.identity;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountAdministrationService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final PasswordPolicy passwordPolicy;
    private final CurrentActor actor;
    private final AuditService audit;

    public AccountAdministrationService(JdbcClient jdbc, PasswordEncoder passwords, PasswordPolicy passwordPolicy,
                                        CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.passwordPolicy = passwordPolicy;
        this.actor = actor;
        this.audit = audit;
    }

    public record RoleOption(UUID id, String code, String name, String dataScope) {}
    public record PermissionOption(String code, String name, String category) {}
    public record RolePermissionView(UUID id, String code, String name, String dataScope,
                                     List<PermissionOption> permissions) {}
    public record PermissionOverview(UUID personId, UUID accountId, String username,
                                     List<RolePermissionView> roles,
                                     List<PermissionOption> effectivePermissions,
                                     OffsetDateTime generatedAt) {}
    public record ManagedAccount(UUID id, UUID personId, String username, String displayName, boolean enabled,
                                 boolean mustChangePassword, long version, List<UUID> roleIds,
                                 List<String> roleCodes) {}

    public List<RoleOption> roles() {
        return jdbc.sql("""
                SELECT id,code,name,data_scope FROM app_role
                WHERE status='ACTIVE' ORDER BY system_role DESC,name,code
                """)
                .query((rs, row) -> new RoleOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("data_scope"))).list();
    }

    public ManagedAccount forPerson(UUID personId) {
        return jdbc.sql("SELECT id FROM app_user WHERE person_id=:person")
                .param("person", personId).query(UUID.class).optional().map(this::require)
                .orElse(null);
    }

    public PermissionOverview permissionOverviewForPerson(UUID personId) {
        ManagedAccount account = forPerson(personId);
        if (account == null) {
            throw ApiException.notFound("PERSON_ACCOUNT_NOT_FOUND", "该人员尚未开通登录账号");
        }
        List<RolePermissionView> roles = jdbc.sql("""
                SELECT r.id,r.code,r.name,r.data_scope
                FROM app_role r JOIN user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=:user AND r.status='ACTIVE'
                ORDER BY r.system_role DESC,r.name,r.code
                """).param("user", account.id()).query((rs, row) -> {
                    UUID roleId = rs.getObject("id", UUID.class);
                    List<PermissionOption> permissions = jdbc.sql("""
                            SELECT p.code,p.name,p.category
                            FROM permission p JOIN role_permission rp ON rp.permission_code=p.code
                            WHERE rp.role_id=:role ORDER BY p.category,p.name,p.code
                            """).param("role", roleId).query((permission, index) -> new PermissionOption(
                            permission.getString("code"), permission.getString("name"),
                            permission.getString("category"))).list();
                    return new RolePermissionView(roleId, rs.getString("code"), rs.getString("name"),
                            rs.getString("data_scope"), permissions);
                }).list();
        Map<String, PermissionOption> effective = new LinkedHashMap<>();
        roles.stream().flatMap(role -> role.permissions().stream())
                .forEach(permission -> effective.putIfAbsent(permission.code(), permission));
        return new PermissionOverview(personId, account.id(), account.username(), roles,
                List.copyOf(effective.values()), OffsetDateTime.now());
    }

    @Transactional
    public ManagedAccount createForPerson(UUID personId, UUID organizationId, UUID departmentId,
                                          String username, String displayName, String password,
                                          List<UUID> roleIds, boolean enabled) {
        if (forPerson(personId) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PERSON_ACCOUNT_EXISTS", "该人员已经绑定登录账号");
        }
        String normalizedUsername = validateUsername(username);
        passwordPolicy.validate(normalizedUsername, password);
        List<UUID> roles = validateRoles(roleIds);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_user(id,person_id,organization_id,department_id,username,password_hash,
                  display_name,enabled,must_change_password)
                VALUES (:id,:person,:organization,:department,:username,:password,:displayName,:enabled,true)
                """).param("id", id).param("person", personId).param("organization", organizationId)
                .param("department", departmentId).param("username", normalizedUsername)
                .param("password", passwords.encode(password)).param("displayName", displayName.trim())
                .param("enabled", enabled).update();
        assignRoles(id, roles);
        ManagedAccount created = require(id);
        audit.record("PERSON_ACCOUNT_CREATED", "USER", id, null, created, null,
                "人员档案开户，首次登录强制改密");
        return created;
    }

    @Transactional
    public ManagedAccount synchronizeProfile(UUID personId, UUID departmentId, String displayName,
                                             List<UUID> roleIds, Long expectedAccountVersion) {
        ManagedAccount before = forPerson(personId);
        if (before == null) return null;
        if (expectedAccountVersion == null || before.version() != expectedAccountVersion) {
            throw ApiException.conflict("ACCOUNT_VERSION_CONFLICT", "人员账号已被其他操作更新", before.version());
        }
        List<UUID> roles = roleIds == null ? before.roleIds() : validateRoles(roleIds);
        int changed = jdbc.sql("""
                UPDATE app_user SET department_id=:department,display_name=:displayName,
                  version=version+1,session_version=session_version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("department", departmentId).param("displayName", displayName.trim())
                .param("id", before.id()).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("ACCOUNT_VERSION_CONFLICT",
                "人员账号已被其他操作更新", require(before.id()).version());
        if (!roles.equals(before.roleIds())) assignRoles(before.id(), roles);
        ManagedAccount after = require(before.id());
        audit.record("PERSON_ACCOUNT_UPDATED", "USER", before.id(), before, after, null,
                "人员档案同步账号和角色");
        return after;
    }

    @Transactional
    public ManagedAccount setEnabledForPerson(UUID personId, boolean enabled) {
        ManagedAccount before = forPerson(personId);
        if (before == null) throw ApiException.notFound("PERSON_ACCOUNT_NOT_FOUND", "该人员尚未开通登录账号");
        if (!enabled && before.id().equals(actor.require().id())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELF_DISABLE_BLOCKED", "不能停用当前登录账号");
        }
        jdbc.sql("""
                UPDATE app_user SET enabled=:enabled,failed_login_count=0,locked_until=NULL,
                  version=version+1,session_version=session_version+1,updated_at=now() WHERE id=:id
                """).param("enabled", enabled).param("id", before.id()).update();
        ManagedAccount after = require(before.id());
        audit.record(enabled ? "PERSON_ACCOUNT_ENABLED" : "PERSON_ACCOUNT_DISABLED", "USER", before.id(),
                before, after, "PERSON_STATUS_CONTROL", null);
        return after;
    }

    @Transactional
    public ManagedAccount updateRoles(UUID userId, List<UUID> roleIds, long expectedVersion) {
        ManagedAccount before = require(userId);
        if (before.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "账号已被更新", before.version());
        }
        List<UUID> roles = validateRoles(roleIds);
        if (userId.equals(actor.require().id())) {
            boolean keepsAdministration = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM role_permission
                      WHERE role_id IN (:roles) AND permission_code='SYSTEM_ADMIN')
                    """).param("roles", roles).query(Boolean.class).single();
            if (!keepsAdministration) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELF_ADMIN_REMOVAL_BLOCKED",
                        "不能移除当前账号自己的系统管理权限");
            }
        }
        int changed = jdbc.sql("""
                UPDATE app_user SET version=version+1,session_version=session_version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("id", userId).param("version", expectedVersion).update();
        if (changed == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "账号已被更新", require(userId).version());
        }
        assignRoles(userId, roles);
        ManagedAccount after = require(userId);
        audit.record("USER_ROLES_UPDATED", "USER", userId, before, after, null, null);
        return after;
    }

    @Transactional
    public ManagedAccount revokeSessions(UUID userId, long expectedVersion) {
        ManagedAccount before = require(userId);
        if (before.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "账号已被更新", before.version());
        }
        int changed = jdbc.sql("""
                UPDATE app_user SET version=version+1,session_version=session_version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("id", userId).param("version", expectedVersion).update();
        if (changed == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "账号已被更新", require(userId).version());
        }
        ManagedAccount after = require(userId);
        audit.record("USER_SESSIONS_REVOKED", "USER", userId, before, after, null, null);
        return after;
    }

    public ManagedAccount require(UUID id) {
        return jdbc.sql("""
                SELECT id,person_id,username,display_name,enabled,must_change_password,version
                FROM app_user WHERE id=:id
                """).param("id", id).query((rs, row) -> new ManagedAccount(
                        rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                        rs.getString("username"), rs.getString("display_name"), rs.getBoolean("enabled"),
                        rs.getBoolean("must_change_password"), rs.getLong("version"),
                        jdbc.sql("""
                                SELECT role.id FROM app_role role
                                JOIN user_role ON user_role.role_id=role.id
                                WHERE user_role.user_id=:id AND role.status='ACTIVE'
                                ORDER BY role.id
                                """).param("id", id).query(UUID.class).list(),
                        jdbc.sql("""
                                SELECT r.code FROM app_role r JOIN user_role ur ON ur.role_id=r.id
                                WHERE ur.user_id=:id AND r.status='ACTIVE' ORDER BY r.code
                                """).param("id", id).query(String.class).list()))
                .optional().orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "账号不存在"));
    }

    private void assignRoles(UUID userId, List<UUID> roles) {
        jdbc.sql("DELETE FROM user_role WHERE user_id=:id").param("id", userId).update();
        roles.forEach(role -> jdbc.sql("INSERT INTO user_role(user_id,role_id) VALUES (:user,:role)")
                .param("user", userId).param("role", role).update());
    }

    private List<UUID> validateRoles(List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_REQUIRED", "至少选择一个角色");
        }
        List<UUID> distinct = roleIds.stream().distinct().sorted().toList();
        long known = jdbc.sql("SELECT count(*) FROM app_role WHERE status='ACTIVE' AND id IN (:ids)")
                .param("ids", distinct).query(Long.class).single();
        if (known != distinct.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_UNKNOWN", "包含未知角色");
        return distinct;
    }

    private String validateUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!username.matches("[A-Za-z0-9._-]{4,80}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "登录账号须为4至80位字母、数字、点、下划线或短横线");
        }
        return username;
    }
}
