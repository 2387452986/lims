package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/configurations")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ConfigurationController {
    private static final List<String> TYPES = List.of("REPORT_TEMPLATE", "COMMISSION_TEMPLATE", "TASK_TEMPLATE",
            "NOTIFICATION", "NUMBERING_RULE", "TEMPLATE_NUMBERING");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public ConfigurationController(JdbcClient jdbc, ObjectMapper json, CurrentActor actor,
                                   AuditService audit, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.json = json;
        this.actor = actor;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    public record Create(@NotBlank String type, @NotBlank @Size(max = 80) String code,
                         @NotBlank @Size(max = 200) String name, @NotEmpty Map<String, Object> content) {}
    public record Update(@NotBlank @Size(max = 200) String name, @NotEmpty Map<String, Object> content,
                         @NotNull @PositiveOrZero Long expectedVersion) {}
    public record View(UUID id, String type, String code, int versionNumber, String name,
                       Map<String, Object> content, String state, long version,
                       OffsetDateTime publishedAt, OffsetDateTime createdAt) {}

    @GetMapping
    public List<View> list(@RequestParam String type) {
        requireType(type);
        return jdbc.sql(baseSelect() + " WHERE configuration_type=:type ORDER BY code, version_number DESC")
                .param("type", type).query(this::map).list();
    }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) { return require(id); }

    @PostMapping
    public View create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create body) {
        requireType(body.type());
        return idempotency.execute(key, "configuration-create", body, View.class, () -> createInternal(body));
    }

    @PutMapping("/{id}")
    @Transactional
    public View update(@PathVariable UUID id, @Valid @RequestBody Update body) {
        View before = require(id);
        if (!before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISHED_CONFIGURATION_IMMUTABLE", "已发布配置只能创建新版本");
        }
        if (before.version() != body.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "配置已被更新", before.version());
        int updated = jdbc.sql("""
                UPDATE configuration_version SET name=:name, content_json=CAST(:content AS jsonb),
                  lock_version=lock_version+1 WHERE id=:id AND lock_version=:version AND state='DRAFT'
                """).param("name", body.name()).param("content", encode(body.content())).param("id", id)
                .param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "配置已被更新", require(id).version());
        View after = require(id);
        audit.record("CONFIGURATION_UPDATED", "CONFIGURATION", id, before, after, null, null);
        return after;
    }

    @PostMapping("/{id}/transitions")
    @Transactional
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "configuration-transition:" + id, body, TransitionResult.class,
                () -> transitionInternal(id, body));
    }

    @PostMapping("/{id}/revisions")
    public View revision(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "configuration-revision:" + id, Map.of("id", id), View.class,
                () -> revisionInternal(id));
    }

    @Transactional
    protected View createInternal(Create body) {
        int versionNumber = jdbc.sql("SELECT COALESCE(max(version_number),0)+1 FROM configuration_version WHERE configuration_type=:type AND code=:code")
                .param("type", body.type()).param("code", body.code()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO configuration_version(id, configuration_type, code, version_number, name,
                  content_json, state, created_by)
                VALUES (:id, :type, :code, :versionNumber, :name, CAST(:content AS jsonb), 'DRAFT', :actor)
                """).param("id", id).param("type", body.type()).param("code", body.code())
                .param("versionNumber", versionNumber).param("name", body.name()).param("content", encode(body.content()))
                .param("actor", actor.require().id()).update();
        View created = require(id);
        audit.record("CONFIGURATION_CREATED", "CONFIGURATION", id, null, created, null, null);
        return created;
    }

    @Transactional
    protected TransitionResult transitionInternal(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "配置已被更新", before.version());
        String action = command.action().toUpperCase();
        String next = switch (before.state()) {
            case "DRAFT" -> action.equals("PUBLISH") ? "PUBLISHED" : invalid(before.state(), action);
            case "PUBLISHED" -> action.equals("RETIRE") ? "RETIRED" : invalid(before.state(), action);
            default -> invalid(before.state(), action);
        };
        if (next.equals("PUBLISHED")) {
            jdbc.sql("""
                    UPDATE configuration_version SET state='RETIRED', lock_version=lock_version+1
                    WHERE configuration_type=:type AND code=:code AND state='PUBLISHED' AND id<>:id
                    """).param("type", before.type()).param("code", before.code()).param("id", id).update();
        }
        int updated = jdbc.sql("""
                UPDATE configuration_version SET state=:state,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,
                  lock_version=lock_version+1 WHERE id=:id AND lock_version=:version
                """).param("state", next).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "配置已被更新", require(id).version());
        View after = require(id);
        audit.record("CONFIGURATION_" + action, "CONFIGURATION", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.state().equals("PUBLISHED") ? List.of("RETIRE") : List.of());
    }

    @Transactional
    protected View revisionInternal(UUID id) {
        View source = require(id);
        if (!List.of("PUBLISHED", "RETIRED").contains(source.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIGURATION_NOT_REVISIONABLE", "仅已发布或已停用配置可以创建新版本");
        }
        return createInternal(new Create(source.type(), source.code(), source.name(), source.content()));
    }

    private View require(UUID id) {
        return jdbc.sql(baseSelect() + " WHERE id=:id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("CONFIGURATION_NOT_FOUND", "配置版本不存在"));
    }

    private void requireType(String type) {
        if (!TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIGURATION_TYPE_INVALID", "配置类型无效");
    }

    private String invalid(String state, String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_CONFIGURATION_TRANSITION", "配置状态 %s 不允许动作 %s".formatted(state, action));
    }

    private String baseSelect() {
        return """
                SELECT id, configuration_type, code, version_number, name, content_json::text, state,
                  lock_version, published_at, created_at FROM configuration_version
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            Map<String, Object> content = json.readValue(rs.getString("content_json"), new TypeReference<>() {});
            return new View(rs.getObject("id", UUID.class), rs.getString("configuration_type"), rs.getString("code"),
                    rs.getInt("version_number"), rs.getString("name"), content, rs.getString("state"),
                    rs.getLong("lock_version"), rs.getObject("published_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class));
        } catch (JacksonException exception) { throw new java.sql.SQLException("Invalid configuration JSON", exception); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("配置内容无法序列化", exception); }
    }
}
