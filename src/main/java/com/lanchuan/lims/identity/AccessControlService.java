package com.lanchuan.lims.identity;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AccessControlService {
    private static final Set<String> DATA_SCOPES = Set.of("ALL", "ORGANIZATION", "DEPARTMENT", "PROJECT", "SELF");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public AccessControlService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record CatalogNode(String key, String parentKey, String label, String nodeType, String origin,
                              String sourcePath, int sortOrder, boolean leaf, String enforcementState,
                              String permissionCode, String permissionName, String permissionCategory,
                              String blockingReason, List<CatalogNode> children) {}
    public record ClientCatalog(String code, int sourceKey, String label, String availability,
                                String blockingReason, List<CatalogNode> tree) {}
    public record Catalog(int sourceNodeCount, int enforcedLeafCount, int blockedLeafCount,
                          String sourceManifestSha256, List<ClientCatalog> clients) {}
    public record RoleView(UUID id, String code, String name, String dataScope, String description,
                           boolean systemRole, String status, long version, int revisionNumber,
                           List<String> clientCodes, List<String> catalogGrantKeys,
                           List<String> permissions, int assignedUserCount, OffsetDateTime updatedAt,
                           List<String> allowedActions) {}
    public record RoleRevisionView(UUID id, int revisionNumber, String name, String dataScope,
                                   String description, String status, long version, String reasonCode,
                                   String comment, List<String> clientCodes, List<String> catalogGrantKeys,
                                   List<String> permissions, OffsetDateTime createdAt) {}
    public record UserView(UUID id, String username, String displayName, boolean enabled, long version,
                           List<String> roles) {}

    public record CreateRole(@NotBlank @Size(max = 10) String name,
                             @NotBlank @Size(max = 24) String dataScope,
                             @Size(max = 300) String description,
                             @NotEmpty @Size(max = 4) List<@NotBlank String> clientCodes,
                             @NotEmpty @Size(max = 200) List<@NotBlank String> catalogGrantKeys) {}
    public record UpdateRole(@NotBlank @Size(max = 10) String name,
                             @NotBlank @Size(max = 24) String dataScope,
                             @Size(max = 300) String description,
                             @NotEmpty @Size(max = 4) List<@NotBlank String> clientCodes,
                             @NotEmpty @Size(max = 200) List<@NotBlank String> catalogGrantKeys,
                             @NotNull @PositiveOrZero Long expectedVersion) {}

    private record NodeRow(String key, String clientCode, String parentKey, String nodeType, String origin,
                           String label, String sourcePath, int sortOrder, boolean leaf,
                           String enforcementState, String permissionCode, String permissionName,
                           String permissionCategory, String blockingReason) {}
    private record RoleRow(UUID id, String code, String name, String dataScope, String description,
                           boolean systemRole, String status, long version, UUID revisionId,
                           int revisionNumber, int assignedUserCount, OffsetDateTime updatedAt) {}

    public Catalog catalog() {
        List<NodeRow> rows = jdbc.sql("""
                SELECT node.catalog_key,node.client_code,node.parent_key,node.node_type,node.origin,node.label,
                  node.source_path,node.sort_order,node.leaf,
                  COALESCE(binding.enforcement_state,'CONTAINER') enforcement_state,
                  binding.permission_code,permission.name permission_name,permission.category permission_category,
                  binding.blocking_reason
                FROM permission_catalog_node node
                LEFT JOIN permission_catalog_binding binding ON binding.catalog_key=node.catalog_key
                LEFT JOIN permission ON permission.code=binding.permission_code
                ORDER BY node.client_code,node.sort_order,node.catalog_key
                """).query((rs, row) -> new NodeRow(
                rs.getString("catalog_key"), rs.getString("client_code"), rs.getString("parent_key"),
                rs.getString("node_type"), rs.getString("origin"), rs.getString("label"),
                rs.getString("source_path"), rs.getInt("sort_order"), rs.getBoolean("leaf"),
                rs.getString("enforcement_state"), rs.getString("permission_code"),
                rs.getString("permission_name"), rs.getString("permission_category"),
                rs.getString("blocking_reason"))).list();
        Map<String, NodeBuilder> builders = new LinkedHashMap<>();
        rows.forEach(row -> builders.put(row.key(), new NodeBuilder(row)));
        Map<String, List<NodeBuilder>> roots = new LinkedHashMap<>();
        for (NodeBuilder builder : builders.values()) {
            if (builder.row.parentKey() == null) {
                roots.computeIfAbsent(builder.row.clientCode(), ignored -> new ArrayList<>()).add(builder);
            } else {
                NodeBuilder parent = builders.get(builder.row.parentKey());
                if (parent == null) throw new IllegalStateException("权限目录父节点不存在: " + builder.row.parentKey());
                parent.children.add(builder);
            }
        }
        Comparator<NodeBuilder> order = Comparator.comparingInt((NodeBuilder value) -> value.row.sortOrder())
                .thenComparing(value -> value.row.label()).thenComparing(value -> value.row.key());
        builders.values().forEach(builder -> builder.children.sort(order));
        roots.values().forEach(values -> values.sort(order));

        List<ClientCatalog> clients = jdbc.sql("""
                SELECT client_code,source_key,label,availability,blocking_reason
                FROM permission_client ORDER BY sort_order
                """).query((rs, row) -> {
                    String code = rs.getString("client_code");
                    return new ClientCatalog(code, rs.getInt("source_key"), rs.getString("label"),
                            rs.getString("availability"), rs.getString("blocking_reason"),
                            roots.getOrDefault(code, List.of()).stream().map(NodeBuilder::view).toList());
                }).list();
        Map<String, Object> counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE node.origin='SOURCE') source_nodes,
                  count(*) FILTER (WHERE binding.enforcement_state='ENFORCED') enforced_leaves,
                  count(*) FILTER (WHERE binding.enforcement_state='BLOCKED') blocked_leaves,
                  max(node.source_manifest_sha256) FILTER (WHERE node.origin='SOURCE') manifest
                FROM permission_catalog_node node
                LEFT JOIN permission_catalog_binding binding ON binding.catalog_key=node.catalog_key
                """).query((rs, row) -> Map.<String, Object>of(
                        "source", rs.getInt("source_nodes"), "enforced", rs.getInt("enforced_leaves"),
                        "blocked", rs.getInt("blocked_leaves"), "manifest", rs.getString("manifest"))).single();
        return new Catalog((Integer) counts.get("source"), (Integer) counts.get("enforced"),
                (Integer) counts.get("blocked"), (String) counts.get("manifest"), clients);
    }

    public List<RoleView> roles() {
        return jdbc.sql("""
                SELECT role.id,role.code,role.name,role.data_scope,role.description,role.system_role,
                  role.status,role.version,role.current_policy_revision_id,revision.revision_number,
                  count(user_role.user_id)::int assigned_user_count,role.updated_at
                FROM app_role role
                JOIN role_policy_revision revision ON revision.id=role.current_policy_revision_id
                LEFT JOIN user_role ON user_role.role_id=role.id
                GROUP BY role.id,revision.revision_number
                ORDER BY role.status,role.system_role DESC,role.name,role.code
                """).query((rs, row) -> view(new RoleRow(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("data_scope"), rs.getString("description"), rs.getBoolean("system_role"),
                rs.getString("status"), rs.getLong("version"),
                rs.getObject("current_policy_revision_id", UUID.class), rs.getInt("revision_number"),
                rs.getInt("assigned_user_count"), rs.getObject("updated_at", OffsetDateTime.class)))).list();
    }

    public RoleView require(UUID id) {
        return view(row(id, false));
    }

    public List<RoleRevisionView> history(UUID roleId) {
        row(roleId, false);
        return jdbc.sql("""
                SELECT id,revision_number,role_name,data_scope,description,status,lock_version,
                  reason_code,comment,created_at
                FROM role_policy_revision WHERE role_id=:role ORDER BY revision_number DESC
                """).param("role", roleId).query((rs, row) -> {
                    UUID revisionId = rs.getObject("id", UUID.class);
                    return new RoleRevisionView(revisionId, rs.getInt("revision_number"),
                            rs.getString("role_name"), rs.getString("data_scope"),
                            rs.getString("description"), rs.getString("status"), rs.getLong("lock_version"),
                            rs.getString("reason_code"), rs.getString("comment"), clients(revisionId),
                            catalogGrants(revisionId), permissions(revisionId),
                            rs.getObject("created_at", OffsetDateTime.class));
                }).list();
    }

    public List<UserView> users() {
        Map<UUID, List<String>> roles = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT user_role.user_id,role.code FROM user_role
                JOIN app_role role ON role.id=user_role.role_id AND role.status='ACTIVE'
                ORDER BY role.code
                """).query((rs, row) -> {
                    roles.computeIfAbsent(rs.getObject("user_id", UUID.class), ignored -> new ArrayList<>())
                            .add(rs.getString("code"));
                    return 0;
                }).list();
        return jdbc.sql("""
                SELECT id,username,display_name,enabled,version FROM app_user ORDER BY username
                """).query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new UserView(id, rs.getString("username"), rs.getString("display_name"),
                            rs.getBoolean("enabled"), rs.getLong("version"),
                            List.copyOf(roles.getOrDefault(id, List.of())));
                }).list();
    }

    @Transactional
    public RoleView create(CreateRole command) {
        PolicyInput input = validate(command.name(), command.dataScope(), command.description(),
                command.clientCodes(), command.catalogGrantKeys(), null);
        UUID id = UUID.randomUUID();
        String code = "CUSTOM_" + id.toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_role(id,code,name,data_scope,system_role,status,description,current_policy_revision_id)
                VALUES (:id,:code,:name,:scope,false,'ACTIVE',:description,:revision)
                """).param("id", id).param("code", code).param("name", input.name())
                .param("scope", input.dataScope()).param("description", input.description())
                .param("revision", revisionId).update();
        insertRevision(revisionId, id, 1, input, "ACTIVE", 0, null, null);
        updateCurrentProjection(id, input.permissions());
        RoleView created = require(id);
        audit.record("ROLE_CREATED", "ROLE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public RoleView update(UUID id, UpdateRole command) {
        RoleRow beforeRow = row(id, true);
        if (!beforeRow.status().equals("ACTIVE")) throw invalid("ROLE_NOT_ACTIVE", "停用角色不能修改");
        if (beforeRow.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "角色已被其他用户更新", beforeRow.version());
        }
        PolicyInput input = validate(command.name(), command.dataScope(), command.description(),
                command.clientCodes(), command.catalogGrantKeys(), id);
        if (beforeRow.code().equals("SYSTEM_ADMIN") && !input.permissions().contains("SYSTEM_ADMIN")) {
            throw invalid("SYSTEM_ADMIN_PERMISSION_REQUIRED", "系统管理员角色必须保留系统管理权限");
        }
        RoleView before = view(beforeRow);
        UUID revisionId = UUID.randomUUID();
        int nextRevision = beforeRow.revisionNumber() + 1;
        insertRevision(revisionId, id, nextRevision, input, "ACTIVE", beforeRow.version() + 1,
                "ROLE_POLICY_REVISION", "角色策略修订");
        int changed = jdbc.sql("""
                UPDATE app_role SET name=:name,data_scope=:scope,description=:description,
                  current_policy_revision_id=:revision,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version AND status='ACTIVE'
                """).param("name", input.name()).param("scope", input.dataScope())
                .param("description", input.description()).param("revision", revisionId)
                .param("id", id).param("version", beforeRow.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "角色已被其他用户更新",
                row(id, false).version());
        updateCurrentProjection(id, input.permissions());
        revokeAssignedSessions(id);
        RoleView after = require(id);
        audit.record("ROLE_POLICY_REVISED", "ROLE", id, before, after, "ROLE_POLICY_REVISION", null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        RoleRow beforeRow = row(id, true);
        if (beforeRow.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "角色已被其他用户更新", beforeRow.version());
        }
        String action = normalized(command.action());
        String comment = normalized(command.comment());
        if (action.equals("RETIRE")) {
            if (beforeRow.systemRole()) throw invalid("SYSTEM_ROLE_RETIRE_BLOCKED", "系统预置角色不能停用");
            if (!beforeRow.status().equals("ACTIVE")) throw invalid("ROLE_TRANSITION_INVALID", "当前角色不能停用");
            if (comment.isEmpty()) throw invalid("ROLE_RETIRE_REASON_REQUIRED", "停用角色必须填写原因");
            int assigned = jdbc.sql("SELECT count(*) FROM user_role WHERE role_id=:role")
                    .param("role", id).query(Integer.class).single();
            if (assigned > 0) throw ApiException.blocked("ROLE_IN_USE", "角色仍有在用账号，不能停用",
                    beforeRow.version(), List.of("请先移除 " + assigned + " 个账号的该角色"));
        } else if (action.equals("REACTIVATE")) {
            if (!beforeRow.status().equals("RETIRED")) throw invalid("ROLE_TRANSITION_INVALID", "当前角色不能重新启用");
            ensureNameAvailable(beforeRow.name(), id);
        } else {
            throw invalid("ROLE_ACTION_INVALID", "角色状态动作无效");
        }
        RoleView before = view(beforeRow);
        PolicyInput input = currentInput(beforeRow.revisionId());
        String nextState = action.equals("RETIRE") ? "RETIRED" : "ACTIVE";
        UUID revisionId = UUID.randomUUID();
        insertRevision(revisionId, id, beforeRow.revisionNumber() + 1, input, nextState,
                beforeRow.version() + 1, command.reasonCode(), command.comment());
        jdbc.sql("""
                UPDATE app_role SET status=:state,current_policy_revision_id=:revision,
                  retired_at=CASE WHEN :state='RETIRED' THEN now() ELSE NULL END,
                  retired_reason=CASE WHEN :state='RETIRED' THEN :comment ELSE NULL END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("state", nextState).param("revision", revisionId).param("comment", comment)
                .param("id", id).param("version", beforeRow.version()).update();
        RoleView after = require(id);
        audit.record("ROLE_" + action, "ROLE", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), after.allowedActions());
    }

    private RoleView view(RoleRow row) {
        return new RoleView(row.id(), row.code(), row.name(), row.dataScope(), row.description(),
                row.systemRole(), row.status(), row.version(), row.revisionNumber(), clients(row.revisionId()),
                catalogGrants(row.revisionId()), permissions(row.revisionId()), row.assignedUserCount(),
                row.updatedAt(), allowedActions(row));
    }

    private RoleRow row(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT role.id,role.code,role.name,role.data_scope,role.description,role.system_role,
                  role.status,role.version,role.current_policy_revision_id,revision.revision_number,
                  (SELECT count(*) FROM user_role WHERE role_id=role.id)::int assigned_user_count,
                  role.updated_at
                FROM app_role role
                JOIN role_policy_revision revision ON revision.id=role.current_policy_revision_id
                WHERE role.id=:id
                """ + (lock ? " FOR UPDATE OF role" : ""))
                .param("id", id).query((rs, index) -> new RoleRow(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("data_scope"), rs.getString("description"), rs.getBoolean("system_role"),
                        rs.getString("status"), rs.getLong("version"),
                        rs.getObject("current_policy_revision_id", UUID.class), rs.getInt("revision_number"),
                        rs.getInt("assigned_user_count"), rs.getObject("updated_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> ApiException.notFound("ROLE_NOT_FOUND", "角色不存在"));
    }

    private PolicyInput validate(String name, String dataScope, String description,
                                 List<String> requestedClients, List<String> requestedGrants, UUID exceptRoleId) {
        String normalizedName = normalized(name);
        if (normalizedName.isEmpty() || normalizedName.codePointCount(0, normalizedName.length()) > 10) {
            throw invalid("ROLE_NAME_INVALID", "角色名称须为 1 至 10 个字符");
        }
        String scope = normalized(dataScope).toUpperCase();
        if (!DATA_SCOPES.contains(scope)) throw invalid("ROLE_DATA_SCOPE_INVALID", "角色数据范围无效");
        ensureNameAvailable(normalizedName, exceptRoleId);
        List<String> clients = distinct(requestedClients);
        if (clients.isEmpty()) throw invalid("ROLE_CLIENT_REQUIRED", "至少选择一个客户端");
        int knownClients = jdbc.sql("""
                SELECT count(*) FROM permission_client
                WHERE client_code IN (:codes) AND availability='ACTIVE'
                """)
                .param("codes", clients).query(Integer.class).single();
        if (knownClients != clients.size()) {
            throw invalid("ROLE_CLIENT_NOT_READY", "只能为已交付并启用的客户端配置角色");
        }
        List<String> grants = distinct(requestedGrants);
        if (grants.isEmpty()) throw invalid("ROLE_PERMISSION_REQUIRED", "角色至少保留一项可执行权限");
        List<Map<String, Object>> bindings = jdbc.sql("""
                SELECT node.catalog_key,node.client_code,binding.permission_code
                FROM permission_catalog_node node
                JOIN permission_catalog_binding binding ON binding.catalog_key=node.catalog_key
                WHERE node.catalog_key IN (:keys) AND node.leaf=true
                  AND binding.enforcement_state='ENFORCED' AND binding.permission_code IS NOT NULL
                """).param("keys", grants).query().listOfRows();
        if (bindings.size() != grants.size()) {
            throw invalid("ROLE_PERMISSION_NOT_ENFORCED", "只能选择已接入后端门禁的叶子权限");
        }
        for (Map<String, Object> binding : bindings) {
            if (!clients.contains(String.valueOf(binding.get("client_code")))) {
                throw invalid("ROLE_PERMISSION_CLIENT_MISMATCH", "所选权限不属于已启用客户端");
            }
        }
        List<String> permissions = bindings.stream().map(value -> String.valueOf(value.get("permission_code")))
                .distinct().sorted().toList();
        return new PolicyInput(normalizedName, scope, nullable(description), clients, grants, permissions);
    }

    private void ensureNameAvailable(String name, UUID exceptId) {
        boolean exists = exceptId == null
                ? jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM app_role
                          WHERE status='ACTIVE' AND lower(name)=lower(:name))
                        """).param("name", normalized(name)).query(Boolean.class).single()
                : jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM app_role
                          WHERE status='ACTIVE' AND lower(name)=lower(:name) AND id<>:exceptId)
                        """).param("name", normalized(name)).param("exceptId", exceptId)
                        .query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "ROLE_NAME_EXISTS", "已存在同名启用角色");
    }

    private PolicyInput currentInput(UUID revisionId) {
        return jdbc.sql("""
                SELECT role_name,data_scope,description FROM role_policy_revision WHERE id=:id
                """).param("id", revisionId).query((rs, row) -> new PolicyInput(
                        rs.getString("role_name"), rs.getString("data_scope"), rs.getString("description"),
                        clients(revisionId), catalogGrants(revisionId), permissions(revisionId))).single();
    }

    private void insertRevision(UUID revisionId, UUID roleId, int revisionNumber, PolicyInput input,
                                String status, long version, String reasonCode, String comment) {
        jdbc.sql("""
                INSERT INTO role_policy_revision(id,role_id,revision_number,role_name,data_scope,description,
                  status,lock_version,reason_code,comment,created_by)
                VALUES (:id,:role,:revision,:name,:scope,:description,:state,:version,:reason,:comment,:actor)
                """).param("id", revisionId).param("role", roleId).param("revision", revisionNumber)
                .param("name", input.name()).param("scope", input.dataScope())
                .param("description", input.description()).param("state", status).param("version", version)
                .param("reason", nullable(reasonCode)).param("comment", nullable(comment))
                .param("actor", actor.require().id()).update();
        input.clientCodes().forEach(client -> jdbc.sql("""
                INSERT INTO role_policy_client(revision_id,client_code) VALUES (:revision,:client)
                """).param("revision", revisionId).param("client", client).update());
        input.catalogGrantKeys().forEach(key -> jdbc.sql("""
                INSERT INTO role_policy_catalog_grant(revision_id,catalog_key) VALUES (:revision,:key)
                """).param("revision", revisionId).param("key", key).update());
        input.permissions().forEach(permission -> jdbc.sql("""
                INSERT INTO role_policy_permission_grant(revision_id,permission_code)
                VALUES (:revision,:permission)
                """).param("revision", revisionId).param("permission", permission).update());
    }

    private void updateCurrentProjection(UUID roleId, List<String> permissions) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id=:role").param("role", roleId).update();
        permissions.forEach(permission -> jdbc.sql("""
                INSERT INTO role_permission(role_id,permission_code) VALUES (:role,:permission)
                """).param("role", roleId).param("permission", permission).update());
    }

    private void revokeAssignedSessions(UUID roleId) {
        jdbc.sql("""
                UPDATE app_user SET session_version=session_version+1,updated_at=now()
                WHERE id IN (SELECT user_id FROM user_role WHERE role_id=:role)
                """).param("role", roleId).update();
    }

    private List<String> clients(UUID revisionId) {
        return jdbc.sql("""
                SELECT client_code FROM role_policy_client WHERE revision_id=:revision ORDER BY client_code
                """).param("revision", revisionId).query(String.class).list();
    }

    private List<String> catalogGrants(UUID revisionId) {
        return jdbc.sql("""
                SELECT catalog_key FROM role_policy_catalog_grant
                WHERE revision_id=:revision ORDER BY catalog_key
                """).param("revision", revisionId).query(String.class).list();
    }

    private List<String> permissions(UUID revisionId) {
        return jdbc.sql("""
                SELECT permission_code FROM role_policy_permission_grant
                WHERE revision_id=:revision ORDER BY permission_code
                """).param("revision", revisionId).query(String.class).list();
    }

    private List<String> allowedActions(RoleRow row) {
        if (row.status().equals("RETIRED")) return List.of("REACTIVATE");
        return row.systemRole() ? List.of("UPDATE") : List.of("UPDATE", "RETIRE");
    }

    private List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().map(this::normalized).filter(value -> !value.isEmpty()).forEach(result::add);
        return List.copyOf(result);
    }

    private String normalized(String value) { return value == null ? "" : value.trim(); }
    private String nullable(String value) { String normalized = normalized(value); return normalized.isEmpty() ? null : normalized; }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }

    private record PolicyInput(String name, String dataScope, String description, List<String> clientCodes,
                               List<String> catalogGrantKeys, List<String> permissions) {}

    private static final class NodeBuilder {
        private final NodeRow row;
        private final List<NodeBuilder> children = new ArrayList<>();
        private NodeBuilder(NodeRow row) { this.row = row; }
        private CatalogNode view() {
            return new CatalogNode(row.key(), row.parentKey(), row.label(), row.nodeType(), row.origin(),
                    row.sourcePath(), row.sortOrder(), row.leaf(), row.enforcementState(),
                    row.permissionCode(), row.permissionName(), row.permissionCategory(), row.blockingReason(),
                    children.stream().map(NodeBuilder::view).toList());
        }
    }
}
