package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DataDictionaryService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public DataDictionaryService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                 IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    public record TreeResponse(List<NodeView> items, int nodeCount, int valueCount) {}
    public record NodeView(UUID id, UUID parentId, String code, int sortOrder, UUID revisionId,
                           int revisionNumber, String name, boolean allowsChildren, String state,
                           long version, int valueCount, int childCount, String sourceSystem,
                           String sourceRecordId, OffsetDateTime sourceCapturedAt, List<String> allowedActions,
                           List<NodeView> children) {}
    public record ValueView(UUID id, int sortOrder, String value, String remark) {}
    public record DetailView(UUID id, UUID parentId, String code, int sortOrder, UUID revisionId,
                             int revisionNumber, String name, boolean allowsChildren, String state,
                             long version, String sourceSystem, String sourceRecordId, String sourcePath,
                             OffsetDateTime sourceCapturedAt, String contentSha256,
                             List<String> allowedActions, List<ValueView> values) {}
    public record CreateCommand(UUID parentId, String name, boolean allowsChildren) {}
    public record ValueCommand(Integer sortOrder, String value, String remark) {}
    public record UpdateCommand(String name, boolean allowsChildren, List<ValueCommand> values,
                                Long expectedVersion) {}

    public TreeResponse tree(String query, boolean includeRetired) {
        AuthenticatedUser user = actor.require();
        List<FlatNode> all = jdbc.sql(currentNodeSelect() + " WHERE series.organization_id=:organization "
                        + "ORDER BY series.sort_order,upper(series.code),series.id")
                .param("organization", user.organizationId()).query(this::mapFlat).list().stream()
                .filter(node -> includeRetired || !node.state().equals("RETIRED"))
                .toList();

        String needle = normalize(query);
        Set<UUID> included = new HashSet<>();
        if (needle.isEmpty()) {
            all.forEach(node -> included.add(node.id()));
        } else {
            Map<UUID, FlatNode> byId = new HashMap<>();
            all.forEach(node -> byId.put(node.id(), node));
            for (FlatNode node : all) {
                if (normalize(node.code() + " " + node.name() + " " + node.searchValues()).contains(needle)) {
                    included.add(node.id());
                    if (node.parentId() != null) included.add(node.parentId());
                    else all.stream().filter(child -> node.id().equals(child.parentId()))
                            .forEach(child -> included.add(child.id()));
                }
            }
        }

        Map<UUID, List<FlatNode>> children = new LinkedHashMap<>();
        for (FlatNode node : all) {
            if (node.parentId() != null && included.contains(node.id())) {
                children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        Comparator<FlatNode> order = Comparator.comparingInt(FlatNode::sortOrder)
                .thenComparing(FlatNode::code, String.CASE_INSENSITIVE_ORDER).thenComparing(FlatNode::id);
        children.values().forEach(values -> values.sort(order));
        List<NodeView> roots = all.stream().filter(node -> node.parentId() == null && included.contains(node.id()))
                .sorted(order).map(node -> toNode(node, children.getOrDefault(node.id(), List.of()))).toList();
        int nodeCount = (int) all.stream().filter(node -> included.contains(node.id())).count();
        int valueCount = all.stream().filter(node -> included.contains(node.id())).mapToInt(FlatNode::valueCount).sum();
        return new TreeResponse(roots, nodeCount, valueCount);
    }

    public DetailView detail(UUID revisionId) {
        FlatNode node = requireRevision(revisionId);
        List<ValueView> values = jdbc.sql("""
                SELECT id,sort_order,value_text,remark FROM data_dictionary_value
                WHERE revision_id=:revision ORDER BY sort_order,id
                """).param("revision", revisionId)
                .query((rs, row) -> new ValueView(rs.getObject("id", UUID.class), rs.getInt("sort_order"),
                        rs.getString("value_text"), rs.getString("remark"))).list();
        return toDetail(node, values);
    }

    public DetailView create(String key, CreateCommand command) {
        return idempotency.execute(key, "data-dictionary-create", command, DetailView.class,
                () -> createInternal(command));
    }

    @Transactional
    protected DetailView createInternal(CreateCommand command) {
        AuthenticatedUser user = actor.require();
        String name = requireText(command.name(), "DATA_DICTIONARY_NAME_REQUIRED", "字典名称不能为空", 240);
        if (command.parentId() != null) {
            FlatNode parent = requireCurrent(command.parentId());
            if (parent.parentId() != null) throw invalid("DATA_DICTIONARY_DEPTH_INVALID", "数据字典最多支持两级");
            if (!parent.allowsChildren() || parent.state().equals("RETIRED")) {
                throw invalid("DATA_DICTIONARY_PARENT_UNAVAILABLE", "所选字典不允许添加子级");
            }
            if (command.allowsChildren()) throw invalid("DATA_DICTIONARY_CHILD_BRANCH_INVALID", "二级字典不能继续添加子级");
        }
        long sequence = jdbc.sql("SELECT nextval('data_dictionary_code_seq')").query(Long.class).single();
        String code = Long.toString(sequence);
        int sortOrder = jdbc.sql("""
                SELECT COALESCE(max(sort_order),-10)+10 FROM data_dictionary_series
                WHERE organization_id=:organization AND parent_id IS NOT DISTINCT FROM :parent
                """).param("organization", user.organizationId()).param("parent", command.parentId())
                .query(Integer.class).single();
        UUID seriesId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO data_dictionary_series(id,organization_id,code,parent_id,sort_order,created_by)
                VALUES (:id,:organization,:code,:parent,:sortOrder,:actor)
                """).param("id", seriesId).param("organization", user.organizationId()).param("code", code)
                .param("parent", command.parentId()).param("sortOrder", sortOrder).param("actor", user.id()).update();
        jdbc.sql("""
                INSERT INTO data_dictionary_revision(id,series_id,revision_number,name,allows_children,state,created_by)
                VALUES (:id,:series,1,:name,:allowsChildren,'DRAFT',:actor)
                """).param("id", revisionId).param("series", seriesId).param("name", name)
                .param("allowsChildren", command.allowsChildren()).param("actor", user.id()).update();
        DetailView created = detail(revisionId);
        audit.record("DATA_DICTIONARY_CREATED", "DATA_DICTIONARY", seriesId, null, created, null, null);
        return created;
    }

    public DetailView update(UUID revisionId, String key, UpdateCommand command) {
        return idempotency.execute(key, "data-dictionary-update:" + revisionId, command, DetailView.class,
                () -> updateInternal(revisionId, command));
    }

    @Transactional
    protected DetailView updateInternal(UUID revisionId, UpdateCommand command) {
        FlatNode beforeNode = requireRevision(revisionId);
        if (!beforeNode.state().equals("DRAFT")) {
            throw ApiException.conflict("DATA_DICTIONARY_PUBLISHED_IMMUTABLE",
                    "已发布或停用字典必须先创建修订", beforeNode.version());
        }
        if (command.expectedVersion() == null || beforeNode.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "字典草稿已被其他用户更新", beforeNode.version());
        }
        String name = requireText(command.name(), "DATA_DICTIONARY_NAME_REQUIRED", "字典名称不能为空", 240);
        List<ValueCommand> values = command.values() == null ? List.of() : command.values();
        if (values.size() > 1000) throw invalid("DATA_DICTIONARY_VALUE_LIMIT", "单个字典最多维护 1000 个值项");
        if (beforeNode.parentId() != null && command.allowsChildren()) {
            throw invalid("DATA_DICTIONARY_CHILD_BRANCH_INVALID", "二级字典不能继续添加子级");
        }
        if (command.allowsChildren() && !values.isEmpty()) {
            throw invalid("DATA_DICTIONARY_BRANCH_VALUE_CONFLICT", "允许添加子级的字典不能同时维护值项");
        }
        if (!command.allowsChildren() && beforeNode.childCount() > 0) {
            throw invalid("DATA_DICTIONARY_CHILDREN_EXIST", "存在子级时必须保持允许添加子级");
        }
        List<ValueCommand> normalized = normalizeValues(values);
        DetailView before = detail(revisionId);
        int updated = jdbc.sql("""
                UPDATE data_dictionary_revision SET name=:name,allows_children=:allowsChildren,
                  lock_version=lock_version+1,updated_at=now()
                WHERE id=:id AND state='DRAFT' AND lock_version=:version
                """).param("name", name).param("allowsChildren", command.allowsChildren())
                .param("id", revisionId).param("version", command.expectedVersion()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "字典草稿已被其他用户更新",
                requireRevision(revisionId).version());
        jdbc.sql("DELETE FROM data_dictionary_value WHERE revision_id=:revision")
                .param("revision", revisionId).update();
        for (ValueCommand value : normalized) {
            jdbc.sql("""
                    INSERT INTO data_dictionary_value(id,revision_id,sort_order,value_text,remark)
                    VALUES (:id,:revision,:sortOrder,:value,:remark)
                    """).param("id", UUID.randomUUID()).param("revision", revisionId)
                    .param("sortOrder", value.sortOrder()).param("value", value.value())
                    .param("remark", value.remark()).update();
        }
        DetailView after = detail(revisionId);
        audit.record("DATA_DICTIONARY_UPDATED", "DATA_DICTIONARY", beforeNode.id(), before, after, null, null);
        return after;
    }

    public DetailView revise(UUID seriesId, String key) {
        return idempotency.execute(key, "data-dictionary-revise:" + seriesId, Map.of("seriesId", seriesId),
                DetailView.class, () -> reviseInternal(seriesId));
    }

    @Transactional
    protected DetailView reviseInternal(UUID seriesId) {
        AuthenticatedUser user = actor.require();
        jdbc.sql("SELECT id FROM data_dictionary_series WHERE id=:series AND organization_id=:organization FOR UPDATE")
                .param("series", seriesId).param("organization", user.organizationId()).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound(
                        "DATA_DICTIONARY_NOT_FOUND", "数据字典不存在"));
        FlatNode source = requireCurrent(seriesId);
        if (!List.of("PUBLISHED", "RETIRED").contains(source.state())) {
            throw ApiException.conflict("DATA_DICTIONARY_NOT_REVISIONABLE",
                    "仅已发布或停用字典可以创建修订", source.version());
        }
        if (jdbc.sql("SELECT count(*) FROM data_dictionary_revision WHERE series_id=:series AND state='DRAFT'")
                .param("series", seriesId).query(Integer.class).single() > 0) {
            throw ApiException.conflict("DATA_DICTIONARY_DRAFT_EXISTS", "该字典已经存在草稿修订", source.version());
        }
        int revisionNumber = jdbc.sql("SELECT max(revision_number)+1 FROM data_dictionary_revision WHERE series_id=:series")
                .param("series", seriesId).query(Integer.class).single();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO data_dictionary_revision(id,series_id,predecessor_id,revision_number,name,
                  allows_children,state,created_by)
                VALUES (:id,:series,:predecessor,:revisionNumber,:name,:allowsChildren,'DRAFT',:actor)
                """).param("id", revisionId).param("series", seriesId).param("predecessor", source.revisionId())
                .param("revisionNumber", revisionNumber).param("name", source.name())
                .param("allowsChildren", source.allowsChildren()).param("actor", actor.require().id()).update();
        jdbc.sql("""
                INSERT INTO data_dictionary_value(id,revision_id,source_record_id,sort_order,value_text,remark)
                SELECT gen_random_uuid(),:target,source_record_id,sort_order,value_text,remark
                FROM data_dictionary_value WHERE revision_id=:source ORDER BY sort_order,id
                """).param("target", revisionId).param("source", source.revisionId()).update();
        DetailView created = detail(revisionId);
        audit.record("DATA_DICTIONARY_REVISED", "DATA_DICTIONARY", seriesId, toDetail(source, List.of()), created,
                null, null);
        return created;
    }

    public TransitionResult transition(UUID revisionId, String key, TransitionCommand command) {
        return idempotency.execute(key, "data-dictionary-transition:" + revisionId, command,
                TransitionResult.class, () -> transitionInternal(revisionId, command));
    }

    @Transactional
    protected TransitionResult transitionInternal(UUID revisionId, TransitionCommand command) {
        FlatNode beforeNode = requireRevision(revisionId);
        if (beforeNode.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "字典版本已被其他用户更新", beforeNode.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        String next;
        if (beforeNode.state().equals("DRAFT") && action.equals("PUBLISH")) next = "PUBLISHED";
        else if (beforeNode.state().equals("PUBLISHED") && action.equals("RETIRE")) next = "RETIRED";
        else throw ApiException.conflict("DATA_DICTIONARY_ACTION_INVALID", "当前字典状态不允许该操作",
                    beforeNode.version());
        if (next.equals("RETIRED")) {
            requireText(command.comment(), "DATA_DICTIONARY_RETIRE_REASON_REQUIRED", "停用字典必须填写原因", 1000);
            int activeChildren = jdbc.sql("""
                    SELECT count(*) FROM data_dictionary_series child
                    JOIN data_dictionary_revision revision ON revision.series_id=child.id
                    WHERE child.parent_id=:series AND revision.state IN ('DRAFT','PUBLISHED')
                    """).param("series", beforeNode.id()).query(Integer.class).single();
            if (activeChildren > 0) throw ApiException.blocked("DATA_DICTIONARY_RETIRE_BLOCKED",
                    "仍有可用子级字典，不能停用；请先停用全部子级字典", beforeNode.version(),
                    List.of("请先停用全部子级字典"));
        }
        DetailView before = detail(revisionId);
        if (next.equals("PUBLISHED")) {
            validatePublish(beforeNode, before.values());
            jdbc.sql("""
                    UPDATE data_dictionary_revision SET state='RETIRED',lock_version=lock_version+1,
                      updated_at=now() WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("series", beforeNode.id()).param("id", revisionId).update();
        }
        int changed = jdbc.sql("""
                UPDATE data_dictionary_revision SET state=:state,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,
                  lock_version=lock_version+1,updated_at=now()
                WHERE id=:id AND lock_version=:version AND state=:beforeState
                """).param("state", next).param("id", revisionId).param("version", beforeNode.version())
                .param("beforeState", beforeNode.state()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "字典版本已被其他用户更新",
                requireRevision(revisionId).version());
        DetailView after = detail(revisionId);
        audit.record("DATA_DICTIONARY_" + action, "DATA_DICTIONARY", beforeNode.id(), before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private void validatePublish(FlatNode node, List<ValueView> values) {
        if (node.allowsChildren() && !values.isEmpty()) {
            throw invalid("DATA_DICTIONARY_BRANCH_VALUE_CONFLICT", "允许添加子级的字典不能同时维护值项");
        }
        if (!node.allowsChildren() && node.childCount() > 0) {
            throw invalid("DATA_DICTIONARY_CHILDREN_EXIST", "存在子级时必须保持允许添加子级");
        }
        if (node.parentId() != null) {
            FlatNode parent = requireCurrent(node.parentId());
            if (!parent.allowsChildren() || !parent.state().equals("PUBLISHED")) {
                throw invalid("DATA_DICTIONARY_PARENT_UNAVAILABLE", "上级字典必须先发布，当前字典才能发布");
            }
        }
    }

    private List<ValueCommand> normalizeValues(List<ValueCommand> values) {
        List<ValueCommand> normalized = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            ValueCommand value = values.get(index);
            String text = requireText(value.value(), "DATA_DICTIONARY_VALUE_REQUIRED", "字典值不能为空", 1000);
            String remark = value.remark() == null ? "" : value.remark().trim();
            if (remark.length() > 2000) throw invalid("DATA_DICTIONARY_REMARK_TOO_LONG", "字典值备注最多 2000 字");
            int sortOrder = value.sortOrder() == null ? index + 1 : value.sortOrder();
            if (sortOrder < 0) throw invalid("DATA_DICTIONARY_SORT_INVALID", "排序不能小于 0");
            normalized.add(new ValueCommand(sortOrder, text, remark));
        }
        normalized.sort(Comparator.comparingInt(ValueCommand::sortOrder));
        return normalized;
    }

    private FlatNode requireCurrent(UUID seriesId) {
        return jdbc.sql(currentNodeSelect() + " WHERE series.organization_id=:organization AND series.id=:series")
                .param("organization", actor.require().organizationId()).param("series", seriesId)
                .query(this::mapFlat).optional()
                .orElseThrow(() -> ApiException.notFound("DATA_DICTIONARY_NOT_FOUND", "数据字典不存在"));
    }

    private FlatNode requireRevision(UUID revisionId) {
        return jdbc.sql(revisionNodeSelect() + " WHERE series.organization_id=:organization AND revision.id=:revision")
                .param("organization", actor.require().organizationId()).param("revision", revisionId)
                .query(this::mapFlat).optional()
                .orElseThrow(() -> ApiException.notFound("DATA_DICTIONARY_REVISION_NOT_FOUND", "数据字典版本不存在"));
    }

    private String currentNodeSelect() {
        return """
                SELECT series.id,series.parent_id,series.code,series.sort_order,series.source_system,
                  series.source_record_id,series.source_path,series.source_captured_at,series.content_sha256,
                  revision.id revision_id,revision.revision_number,revision.name,revision.allows_children,
                  revision.state,revision.lock_version,revision.published_at,revision.created_at,revision.updated_at,
                  (SELECT count(*) FROM data_dictionary_value value WHERE value.revision_id=revision.id) value_count,
                  (SELECT count(*) FROM data_dictionary_series child WHERE child.parent_id=series.id) child_count,
                  COALESCE((SELECT string_agg(value.value_text,' ') FROM data_dictionary_value value
                    WHERE value.revision_id=revision.id),'') search_values
                FROM data_dictionary_series series
                JOIN LATERAL (
                  SELECT candidate.* FROM data_dictionary_revision candidate
                  WHERE candidate.series_id=series.id
                  ORDER BY CASE candidate.state WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
                    candidate.revision_number DESC LIMIT 1
                ) revision ON true
                """;
    }

    private String revisionNodeSelect() {
        return """
                SELECT series.id,series.parent_id,series.code,series.sort_order,series.source_system,
                  series.source_record_id,series.source_path,series.source_captured_at,series.content_sha256,
                  revision.id revision_id,revision.revision_number,revision.name,revision.allows_children,
                  revision.state,revision.lock_version,revision.published_at,revision.created_at,revision.updated_at,
                  (SELECT count(*) FROM data_dictionary_value value WHERE value.revision_id=revision.id) value_count,
                  (SELECT count(*) FROM data_dictionary_series child WHERE child.parent_id=series.id) child_count,
                  COALESCE((SELECT string_agg(value.value_text,' ') FROM data_dictionary_value value
                    WHERE value.revision_id=revision.id),'') search_values
                FROM data_dictionary_series series
                JOIN data_dictionary_revision revision ON revision.series_id=series.id
                """;
    }

    private FlatNode mapFlat(ResultSet rs, int row) throws SQLException {
        return new FlatNode(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("code"), rs.getInt("sort_order"), rs.getObject("revision_id", UUID.class),
                rs.getInt("revision_number"), rs.getString("name"), rs.getBoolean("allows_children"),
                rs.getString("state"), rs.getLong("lock_version"), rs.getInt("value_count"),
                rs.getInt("child_count"), rs.getString("source_system"), rs.getString("source_record_id"),
                rs.getString("source_path"), rs.getObject("source_captured_at", OffsetDateTime.class),
                rs.getString("content_sha256"), rs.getString("search_values"));
    }

    private NodeView toNode(FlatNode node, List<FlatNode> children) {
        return new NodeView(node.id(), node.parentId(), node.code(), node.sortOrder(), node.revisionId(),
                node.revisionNumber(), node.name(), node.allowsChildren(), node.state(), node.version(),
                node.valueCount(), node.childCount(), node.sourceSystem(), node.sourceRecordId(),
                node.sourceCapturedAt(), allowedActions(node.state()),
                children.stream().map(child -> toNode(child, List.of())).toList());
    }

    private DetailView toDetail(FlatNode node, List<ValueView> values) {
        return new DetailView(node.id(), node.parentId(), node.code(), node.sortOrder(), node.revisionId(),
                node.revisionNumber(), node.name(), node.allowsChildren(), node.state(), node.version(),
                node.sourceSystem(), node.sourceRecordId(), node.sourcePath(), node.sourceCapturedAt(),
                node.contentSha256(), allowedActions(node.state()), List.copyOf(values));
    }

    private List<String> allowedActions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("UPDATE", "PUBLISH");
            case "PUBLISHED" -> List.of("REVISE", "RETIRE");
            case "RETIRED" -> List.of("REVISE");
            default -> List.of();
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String code, String message, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw invalid(code, message);
        if (normalized.length() > max) throw invalid(code, message + "，最多 " + max + " 字");
        return normalized;
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private record FlatNode(UUID id, UUID parentId, String code, int sortOrder, UUID revisionId,
                            int revisionNumber, String name, boolean allowsChildren, String state,
                            long version, int valueCount, int childCount, String sourceSystem,
                            String sourceRecordId, String sourcePath, OffsetDateTime sourceCapturedAt,
                            String contentSha256, String searchValues) {}
}
