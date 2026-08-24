package com.lanchuan.lims.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Account(UUID id, UUID personId, UUID organizationId, UUID departmentId, String username, String passwordHash,
                          String displayName, boolean enabled, boolean mustChangePassword, int failedLoginCount,
                          OffsetDateTime lockedUntil, long sessionVersion) {}

    public Optional<Account> find(String username) {
        return jdbc.sql("""
                SELECT id, person_id, organization_id, department_id, username, password_hash, display_name, enabled,
                       must_change_password, failed_login_count, locked_until, session_version
                FROM app_user WHERE lower(username) = lower(:username)
                """)
                .param("username", username)
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("department_id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("display_name"), rs.getBoolean("enabled"),
                        rs.getBoolean("must_change_password"), rs.getInt("failed_login_count"),
                        rs.getObject("locked_until", OffsetDateTime.class), rs.getLong("session_version")))
                .optional();
    }

    public List<String> roles(UUID userId) {
        return jdbc.sql("""
                SELECT r.code FROM app_role r JOIN user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=:id AND r.status='ACTIVE' ORDER BY r.code
                """)
                .param("id", userId).query(String.class).list();
    }

    public List<String> permissions(UUID userId) {
        return jdbc.sql("""
                SELECT DISTINCT rp.permission_code FROM role_permission rp
                JOIN user_role ur ON ur.role_id=rp.role_id
                JOIN app_role role ON role.id=rp.role_id AND role.status='ACTIVE'
                WHERE ur.user_id=:id ORDER BY rp.permission_code
                """).param("id", userId).query(String.class).list();
    }

    public String dataScope(UUID userId) {
        return jdbc.sql("""
                SELECT r.data_scope FROM app_role r JOIN user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=:id AND r.status='ACTIVE'
                ORDER BY CASE r.data_scope WHEN 'ALL' THEN 1 WHEN 'ORGANIZATION' THEN 2
                  WHEN 'DEPARTMENT' THEN 3 WHEN 'PROJECT' THEN 4 ELSE 5 END
                LIMIT 1
                """).param("id", userId).query(String.class).optional().orElse("SELF");
    }

    public String departmentName(UUID departmentId) {
        if (departmentId == null) return "未分配部门";
        return jdbc.sql("SELECT name FROM department WHERE id=:id")
                .param("id", departmentId).query(String.class).optional().orElse("未分配部门");
    }

    public boolean rateLimited(String username, String remoteAddress) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM login_attempt WHERE username=:username AND remote_address=:remote
                              AND locked_until > now())
                """).param("username", username).param("remote", remoteAddress).query(Boolean.class).single();
    }

    public void failed(Account account, String remoteAddress) {
        jdbc.sql("""
                UPDATE app_user SET failed_login_count=failed_login_count+1,
                    locked_until=CASE WHEN failed_login_count+1 >= 5 THEN now()+interval '15 minutes' ELSE locked_until END,
                    updated_at=now() WHERE id=:id
                """).param("id", account.id()).update();
        jdbc.sql("""
                INSERT INTO login_attempt(username, remote_address, window_started_at, failure_count, locked_until)
                VALUES (:username, :remote, now(), 1, NULL)
                ON CONFLICT(username, remote_address) DO UPDATE SET
                  failure_count=CASE WHEN login_attempt.window_started_at < now()-interval '10 minutes'
                                     THEN 1 ELSE login_attempt.failure_count+1 END,
                  window_started_at=CASE WHEN login_attempt.window_started_at < now()-interval '10 minutes'
                                         THEN now() ELSE login_attempt.window_started_at END,
                  locked_until=CASE WHEN (CASE WHEN login_attempt.window_started_at < now()-interval '10 minutes'
                                               THEN 1 ELSE login_attempt.failure_count+1 END) >= 10
                                    THEN now()+interval '15 minutes' ELSE login_attempt.locked_until END
                """).param("username", account.username()).param("remote", remoteAddress).update();
    }

    public void succeeded(Account account, String remoteAddress) {
        jdbc.sql("""
                UPDATE app_user SET failed_login_count=0, locked_until=NULL, last_login_at=now(), updated_at=now()
                WHERE id=:id
                """).param("id", account.id()).update();
        jdbc.sql("DELETE FROM login_attempt WHERE username=:username AND remote_address=:remote")
                .param("username", account.username()).param("remote", remoteAddress).update();
    }

    public void changePassword(UUID id, String hash) {
        jdbc.sql("""
                UPDATE app_user SET password_hash=:hash, must_change_password=false, failed_login_count=0,
                  locked_until=NULL, session_version=session_version+1, updated_at=now() WHERE id=:id
                """).param("hash", hash).param("id", id).update();
    }

    public Optional<Long> activeSessionVersion(UUID id) {
        return jdbc.sql("SELECT session_version FROM app_user WHERE id=:id AND enabled=true")
                .param("id", id).query(Long.class).optional();
    }

}
