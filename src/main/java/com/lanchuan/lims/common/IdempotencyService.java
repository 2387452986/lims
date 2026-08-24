package com.lanchuan.lims.common;

import com.lanchuan.lims.identity.CurrentActor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CurrentActor actor;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper json, CurrentActor actor) {
        this.jdbc = jdbc;
        this.json = json;
        this.actor = actor;
    }

    @Transactional
    public <T> T execute(String key, String operation, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key");
        }
        String fingerprint = sha256(operation + "\n" + encode(request));
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record(idempotency_key, actor_id, request_fingerprint, state, expires_at)
                VALUES (:key, :actor, :fingerprint, 'PROCESSING', now()+interval '24 hours')
                ON CONFLICT (idempotency_key) DO NOTHING
                """).param("key", key).param("actor", actor.require().id()).param("fingerprint", fingerprint).update();
        if (inserted == 0) {
            Existing existing = existing(key, fingerprint);
            if (existing.state().equals("COMPLETED")) return decode(existing.body(), responseType);
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理，请稍后查询结果");
        }
        T result = action.get();
        jdbc.sql("""
                UPDATE idempotency_record SET state='COMPLETED', response_status=200,
                    response_body=CAST(:body AS jsonb) WHERE idempotency_key=:key
                """).param("body", encode(result)).param("key", key).update();
        return result;
    }

    @Transactional
    public <T> T execute(String key, String operation, Object request, TypeReference<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key");
        }
        String fingerprint = sha256(operation + "\n" + encode(request));
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record(idempotency_key, actor_id, request_fingerprint, state, expires_at)
                VALUES (:key, :actor, :fingerprint, 'PROCESSING', now()+interval '24 hours')
                ON CONFLICT (idempotency_key) DO NOTHING
                """).param("key", key).param("actor", actor.require().id()).param("fingerprint", fingerprint).update();
        if (inserted == 0) {
            Existing existing = existing(key, fingerprint);
            if (existing.state().equals("COMPLETED")) return decode(existing.body(), responseType);
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理，请稍后查询结果");
        }
        T result = action.get();
        jdbc.sql("""
                UPDATE idempotency_record SET state='COMPLETED', response_status=200,
                    response_body=CAST(:body AS jsonb) WHERE idempotency_key=:key
                """).param("body", encode(result)).param("key", key).update();
        return result;
    }

    public void purgeExpired() {
        jdbc.sql("DELETE FROM idempotency_record WHERE expires_at < now()").update();
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法生成请求指纹", exception); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException exception) { throw new IllegalStateException("无法读取已完成的幂等响应", exception); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException exception) { throw new IllegalStateException("无法读取已完成的幂等响应", exception); }
    }

    private Existing existing(String key, String fingerprint) {
        Existing existing = jdbc.sql("""
                SELECT request_fingerprint, state, response_body::text AS response_body
                FROM idempotency_record WHERE idempotency_key=:key AND actor_id=:actor
                """).param("key", key).param("actor", actor.require().id())
                .query((rs, row) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_OWNED", "幂等键已被其他账号使用"));
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "幂等键不能用于不同请求");
        }
        return existing;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Existing(String fingerprint, String state, String body) {}
}
