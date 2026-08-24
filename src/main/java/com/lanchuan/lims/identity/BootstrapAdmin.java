package com.lanchuan.lims.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final String secret;
    private final String username;

    public BootstrapAdmin(JdbcClient jdbc, PasswordEncoder encoder, PasswordPolicy policy,
                          @Value("${lims.security.bootstrap-admin-secret:}") String secret,
                          @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}") String username) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.secret = secret;
        this.username = username;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long count = jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
        if (count > 0) return;
        if (secret == null || secret.isBlank()) {
            log.warn("No users exist and BOOTSTRAP_ADMIN_SECRET is empty; login remains disabled until a one-time secret is supplied");
            return;
        }
        policy.validate(username, secret);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_user(id, organization_id, department_id, username, password_hash, display_name)
                VALUES (:id, '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000011', :username, :hash, '系统管理员')
                """).param("id", id).param("username", username).param("hash", encoder.encode(secret)).update();
        jdbc.sql("""
                INSERT INTO user_role(user_id, role_id)
                VALUES (:userId, '00000000-0000-0000-0000-000000000101')
                """).param("userId", id).update();
        log.info("Created the one-time bootstrap administrator account; a password change is required at first login");
    }
}
