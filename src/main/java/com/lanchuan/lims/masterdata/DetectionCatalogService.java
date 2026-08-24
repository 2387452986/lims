package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.FormulaEvaluator;
import com.lanchuan.lims.identity.CurrentActor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DetectionCatalogService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final ObjectMapper json;
    private final FormulaEvaluator formulas;

    public DetectionCatalogService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                   ObjectMapper json, FormulaEvaluator formulas) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.json = json;
        this.formulas = formulas;
    }

    public record CatalogNode(UUID id, UUID parentId, String type, String name, String symbol, int sortOrder,
                              String status, boolean legacy, long version, UUID projectRevisionId,
                              Integer revisionNumber, String revisionStatus, long parameterCount, String path,
                              List<CatalogNode> children) {}

    public record StandardView(UUID id, String code, String name, String status) {}

    public record ParameterView(UUID id, UUID projectRevisionId, String code, String name, int versionNumber,
                                String valueType, Map<String, Object> definition, String formula, String unit,
                                String status, boolean enabled, long version, int sortOrder, String conclusion,
                                String defaultNote, String pricingMethod, String price, String sampleName,
                                Integer promisedDays, String sampleDescription, String sampleQuantity,
                                String postTestTreatment, Integer retentionDays, String retentionQuantity,
                                String retentionNature, boolean defaultCommissioned, boolean required,
                                String remark, String sourceSystem, String sourceKey, List<StandardView> standards) {}

    public record ConfigurationView(UUID id, String type, String code, int versionNumber, String name,
                                    String state, String role) {}
    public record GroupView(UUID id, String code, String name, int sortOrder, String status,
                            long version, List<UUID> parameterIds) {}
    public record MarkerView(UUID id, String code, String name, Map<String, Object> content,
                             String status, long version) {}
    public record SampleFieldView(UUID id, String code, String label, String fieldType, boolean required,
                                  String unit, String placeholder, List<String> options, String coreBinding,
                                  int sortOrder, long version, String sourceSystem, String sourceKey,
                                  List<StandardView> standards) {}
    public record AgeReportOptionView(String value, String label, boolean defaultSelected, int sortOrder) {}
    public record ProjectRevisionView(UUID id, UUID projectNodeId, String projectName, String symbol, String path,
                                      int revisionNumber, String status, long version, String conclusion,
                                      String defaultNote, String sourceManifestSha256, OffsetDateTime sourceCapturedAt,
                                      OffsetDateTime publishedAt, List<String> allowedActions,
                                      List<ParameterView> parameters, List<ConfigurationView> configurations,
                                      List<GroupView> groups, List<MarkerView> markers,
                                      List<SampleFieldView> sampleFields,
                                      List<AgeReportOptionView> ageReportOptions) {}

    public record NodeUpdate(String name, String symbol, int sortOrder, long expectedVersion) {}
    public record NodeCreate(UUID parentId, String type, String name, String symbol, int sortOrder) {}
    public record NodeMove(UUID parentId, int sortOrder, long expectedVersion) {}
    public record RevisionUpdate(String conclusion, String defaultNote, long expectedVersion) {}
    public record ParameterMutation(String code, String name, String valueType, Map<String, Object> definition,
                                    String formula, String unit, int sortOrder, String conclusion, String defaultNote,
                                    String pricingMethod, String price, String sampleName, Integer promisedDays,
                                    String sampleDescription, String sampleQuantity, String postTestTreatment,
                                    Integer retentionDays, String retentionQuantity, String retentionNature,
                                    boolean defaultCommissioned, boolean required, boolean enabled, String remark,
                                    List<UUID> standardIds, long expectedVersion) {}
    public record BatchTarget(UUID id, long expectedVersion) {}
    public record BatchParameterMutation(List<BatchTarget> targets, Boolean defaultCommissioned, Boolean required,
                                         Boolean enabled, String pricingMethod, String price) {}
    public record PricingMutation(UUID id, String pricingMethod, String price, long expectedVersion) {}
    public record PricingQuoteItem(UUID parameterId, String quantity) {}
    public record PricingQuoteLine(UUID parameterId, String code, String name, String pricingMethod,
                                   String unitPrice, String quantity, String lineTotal) {}
    public record PricingQuote(List<PricingQuoteLine> lines, String total) {}
    public record GroupMutation(String code, String name, int sortOrder, String status,
                                List<UUID> parameterIds, long expectedVersion) {}
    public record MarkerMutation(String code, String name, Map<String, Object> content,
                                 String status, long expectedVersion) {}
    public record SampleFieldMutation(String code, String label, String fieldType, boolean required,
                                      String unit, String placeholder, List<String> options, String coreBinding,
                                      int sortOrder, List<UUID> standardIds, long expectedVersion) {}
    public record ConfigurationLink(UUID configurationId, String role, long expectedRevisionVersion) {}

    private static final class MutableNode {
        private final FlatNode source;
        private final List<MutableNode> children = new ArrayList<>();
        private MutableNode(FlatNode source) { this.source = source; }
    }
    private record FlatNode(UUID id, UUID parentId, String type, String name, String symbol, int sortOrder,
                            String status, boolean legacy, long version, UUID revisionId, Integer revisionNumber,
                            String revisionStatus, long parameterCount) {}
    private record RevisionRow(UUID id, UUID projectNodeId, String projectName, String symbol, String path,
                               int revisionNumber, String status, long version, String conclusion, String defaultNote,
                               String sourceManifestSha256, OffsetDateTime sourceCapturedAt, OffsetDateTime publishedAt) {}

    public List<CatalogNode> tree(String query, boolean includeInactive, boolean includeHistory, UUID parentId) {
        List<FlatNode> rows = jdbc.sql("""
                SELECT n.id, n.parent_id, n.node_type, n.name, n.symbol, n.sort_order, n.status, n.legacy,
                  n.lock_version, current_revision.id revision_id, current_revision.revision_number,
                  current_revision.status revision_status,
                  COALESCE((SELECT count(*) FROM test_parameter p
                    WHERE p.project_revision_id=current_revision.id), 0) parameter_count
                FROM detection_catalog_node n
                LEFT JOIN LATERAL (
                  SELECT r.id, r.revision_number, r.status
                  FROM detection_project_revision r WHERE r.project_node_id=n.id
                  ORDER BY CASE r.status WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
                           r.revision_number DESC LIMIT 1
                ) current_revision ON true
                WHERE (:includeInactive OR n.status <> 'INACTIVE')
                  AND (:includeHistory OR n.status <> 'HISTORICAL')
                ORDER BY n.sort_order, n.name, n.id
                """).param("includeInactive", includeInactive).param("includeHistory", includeHistory)
                .query(this::mapFlatNode).list();
        Map<UUID, MutableNode> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.id(), new MutableNode(row)));
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : byId.values()) {
            MutableNode parent = node.source.parentId() == null ? null : byId.get(node.source.parentId());
            if (parent == null) roots.add(node); else parent.children.add(node);
        }
        Comparator<MutableNode> ordering = Comparator.comparingInt((MutableNode node) -> node.source.sortOrder())
                .thenComparing(node -> node.source.name()).thenComparing(node -> node.source.id());
        byId.values().forEach(node -> node.children.sort(ordering));
        roots.sort(ordering);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<UUID, String> paths = new HashMap<>();
        for (MutableNode root : roots) buildPaths(root, "", paths);
        List<CatalogNode> result = new ArrayList<>();
        List<MutableNode> start = parentId == null ? roots : byId.containsKey(parentId)
                ? byId.get(parentId).children : List.of();
        for (MutableNode node : start) {
            CatalogNode view = toView(node, needle, paths);
            if (view != null) result.add(view);
        }
        return result;
    }

    public ProjectRevisionView requireRevision(UUID id) {
        RevisionRow revision = jdbc.sql("""
                WITH RECURSIVE ancestry AS (
                  SELECT n.id, n.parent_id, n.name, 0 depth FROM detection_catalog_node n
                  JOIN detection_project_revision r ON r.project_node_id=n.id WHERE r.id=:id
                  UNION ALL
                  SELECT parent.id, parent.parent_id, parent.name, ancestry.depth+1
                  FROM detection_catalog_node parent JOIN ancestry ON ancestry.parent_id=parent.id
                )
                SELECT r.id, r.project_node_id, n.name project_name, n.symbol,
                  (SELECT string_agg(name, ' / ' ORDER BY depth DESC) FROM ancestry) path,
                  r.revision_number, r.status, r.lock_version, r.conclusion, r.default_note,
                  r.source_manifest_sha256, r.source_captured_at, r.published_at
                FROM detection_project_revision r JOIN detection_catalog_node n ON n.id=r.project_node_id
                WHERE r.id=:id
                """).param("id", id).query((rs, row) -> new RevisionRow(
                        rs.getObject("id", UUID.class), rs.getObject("project_node_id", UUID.class),
                        rs.getString("project_name"), rs.getString("symbol"), rs.getString("path"),
                        rs.getInt("revision_number"), rs.getString("status"), rs.getLong("lock_version"),
                        rs.getString("conclusion"), rs.getString("default_note"),
                        rs.getString("source_manifest_sha256"),
                        rs.getObject("source_captured_at", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("DETECTION_PROJECT_REVISION_NOT_FOUND", "检测项目版本不存在"));
        List<ParameterView> parameters = parameters(id);
        return new ProjectRevisionView(revision.id(), revision.projectNodeId(), revision.projectName(),
                revision.symbol(), revision.path(), revision.revisionNumber(), revision.status(), revision.version(),
                revision.conclusion(), revision.defaultNote(), revision.sourceManifestSha256(),
                revision.sourceCapturedAt(), revision.publishedAt(), revisionActions(revision.status()), parameters,
                configurations(id), groups(id), markers(id), sampleFields(id), ageReportOptions(id));
    }

    public List<StandardView> standards(String query) {
        String pattern = "%" + (query == null ? "" : query.trim()).replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                SELECT id, code, name, status FROM detection_standard
                WHERE state='PUBLISHED' AND status='CURRENT'
                  AND (code ILIKE :pattern ESCAPE '\\' OR name ILIKE :pattern ESCAPE '\\')
                ORDER BY code LIMIT 1000
                """).param("pattern", pattern).query((rs, row) -> new StandardView(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("status"))).list();
    }

    private List<SampleFieldView> sampleFields(UUID revisionId) {
        List<SampleFieldView> fields = jdbc.sql("""
                SELECT id,code,label,field_type,required,unit,placeholder,options_json::text,core_binding,
                  sort_order,version,source_system,source_key
                FROM detection_sample_field_definition
                WHERE project_revision_id=:revision ORDER BY sort_order,label,id
                """).param("revision", revisionId).query((rs, row) -> new SampleFieldView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("label"),
                        rs.getString("field_type"), rs.getBoolean("required"), rs.getString("unit"),
                        rs.getString("placeholder"), decodeStringList(rs.getString("options_json")),
                        rs.getString("core_binding"), rs.getInt("sort_order"), rs.getLong("version"),
                        rs.getString("source_system"), rs.getString("source_key"), List.of())).list();
        if (fields.isEmpty()) return fields;
        Map<UUID, List<StandardView>> standardsByField = new HashMap<>();
        jdbc.sql("""
                SELECT link.field_definition_id,s.id,s.code,s.name,s.status
                FROM detection_sample_field_standard link
                JOIN detection_standard s ON s.id=link.standard_id
                WHERE link.field_definition_id IN (
                  SELECT id FROM detection_sample_field_definition WHERE project_revision_id=:revision)
                ORDER BY link.field_definition_id,link.sort_order,s.code,s.id
                """).param("revision", revisionId).query((rs, row) -> {
                    UUID fieldId = rs.getObject("field_definition_id", UUID.class);
                    standardsByField.computeIfAbsent(fieldId, ignored -> new ArrayList<>()).add(new StandardView(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("status")));
                    return fieldId;
                }).list();
        return fields.stream().map(field -> new SampleFieldView(field.id(), field.code(), field.label(),
                field.fieldType(), field.required(), field.unit(), field.placeholder(), field.options(),
                field.coreBinding(), field.sortOrder(), field.version(), field.sourceSystem(), field.sourceKey(),
                standardsByField.getOrDefault(field.id(), List.of()))).toList();
    }

    private List<AgeReportOptionView> ageReportOptions(UUID revisionId) {
        return jdbc.sql("""
                SELECT value,label,default_selected,sort_order FROM detection_age_report_option
                WHERE project_revision_id=:revision ORDER BY sort_order,value
                """).param("revision", revisionId).query((rs, row) -> new AgeReportOptionView(
                        rs.getString("value"), rs.getString("label"), rs.getBoolean("default_selected"),
                        rs.getInt("sort_order"))).list();
    }

    @Transactional
    public CatalogNode createNode(NodeCreate body) {
        String type = body.type() == null ? "" : body.type().trim().toUpperCase(Locale.ROOT);
        if (!List.of("DOMAIN", "CATEGORY", "PROJECT").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CATALOG_TYPE_INVALID", "目录类型无效");
        }
        FlatNode parent = body.parentId() == null ? null : requireNode(body.parentId());
        String requiredParent = type.equals("CATEGORY") ? "DOMAIN" : type.equals("PROJECT") ? "CATEGORY" : null;
        if ((requiredParent == null && parent != null) || (requiredParent != null && (parent == null || !requiredParent.equals(parent.type())))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CATALOG_PARENT_TYPE_INVALID", "目录层级必须保持领域、分类、检测项目顺序");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_catalog_node(id,parent_id,node_type,source_system,source_key,name,symbol,sort_order,status)
                VALUES (:id,:parent,:type,'lims',:sourceKey,:name,:symbol,:sortOrder,'ACTIVE')
                """).param("id", id).param("parent", body.parentId()).param("type", type)
                .param("sourceKey", "local:" + id).param("name", body.name().trim())
                .param("symbol", blank(body.symbol())).param("sortOrder", body.sortOrder()).update();
        if (type.equals("PROJECT")) {
            jdbc.sql("""
                    INSERT INTO detection_project_revision(id,project_node_id,revision_number,status,created_by)
                    VALUES (:id,:project,1,'DRAFT',:actor)
                    """).param("id", UUID.randomUUID()).param("project", id).param("actor", actor.require().id()).update();
        }
        CatalogNode created = tree("", true, true, body.parentId()).stream().filter(node -> node.id().equals(id))
                .findFirst().orElseGet(() -> flatView(requireNode(id)));
        audit.record("DETECTION_CATALOG_NODE_CREATED", "DETECTION_CATALOG_NODE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public CatalogNode updateNode(UUID id, NodeUpdate body) {
        FlatNode before = requireNode(id);
        int changed = jdbc.sql("""
                UPDATE detection_catalog_node SET name=:name, symbol=:symbol, sort_order=:sortOrder,
                  lock_version=lock_version+1, updated_at=now() WHERE id=:id AND lock_version=:version
                """).param("name", body.name().trim()).param("symbol", blank(body.symbol()))
                .param("sortOrder", body.sortOrder()).param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测目录节点已被更新", requireNode(id).version());
        FlatNode after = requireNode(id);
        audit.record("DETECTION_CATALOG_NODE_UPDATED", "DETECTION_CATALOG_NODE", id, before, after, null, null);
        return tree("", true, true, after.parentId()).stream().filter(node -> node.id().equals(id)).findFirst()
                .orElseGet(() -> flatView(after));
    }

    @Transactional
    public TransitionResult transitionNode(UUID id, TransitionCommand command) {
        FlatNode before = requireNode(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测目录节点已被更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        String next = switch (action) {
            case "DISABLE" -> "INACTIVE";
            case "ENABLE" -> "ACTIVE";
            default -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_CATALOG_TRANSITION", "目录节点不支持该状态动作");
        };
        if (before.status().equals("HISTORICAL")) {
            throw new ApiException(HttpStatus.CONFLICT, "HISTORICAL_NODE_IMMUTABLE", "历史目录节点不能直接启停");
        }
        int changed = jdbc.sql("""
                UPDATE detection_catalog_node SET status=:status, lock_version=lock_version+1, updated_at=now()
                WHERE id=:id AND lock_version=:version
                """).param("status", next).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测目录节点已被更新", requireNode(id).version());
        FlatNode after = requireNode(id);
        audit.record("DETECTION_CATALOG_NODE_" + action, "DETECTION_CATALOG_NODE", id,
                before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), List.of(after.status().equals("ACTIVE") ? "DISABLE" : "ENABLE"));
    }

    @Transactional
    public CatalogNode moveNode(UUID id, NodeMove body) {
        FlatNode before = requireNode(id);
        FlatNode parent = requireNode(body.parentId());
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测目录节点已被更新", before.version());
        }
        if (before.id().equals(parent.id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CATALOG_CYCLE", "目录节点不能移动到自身下级");
        }
        String expectedParentType = before.type().equals("CATEGORY") ? "DOMAIN" : before.type().equals("PROJECT") ? "CATEGORY" : null;
        if (expectedParentType == null || !parent.type().equals(expectedParentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CATALOG_PARENT_TYPE_INVALID", "目录层级必须保持领域、分类、检测项目顺序");
        }
        boolean cycle = jdbc.sql("""
                WITH RECURSIVE descendants AS (
                  SELECT id FROM detection_catalog_node WHERE parent_id=:id
                  UNION ALL SELECT n.id FROM detection_catalog_node n JOIN descendants d ON n.parent_id=d.id)
                SELECT EXISTS(SELECT 1 FROM descendants WHERE id=:parent)
                """).param("id", id).param("parent", body.parentId()).query(Boolean.class).single();
        if (cycle) throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CATALOG_CYCLE", "目录移动不能形成循环");
        int changed = jdbc.sql("""
                UPDATE detection_catalog_node SET parent_id=:parent, sort_order=:sortOrder,
                  lock_version=lock_version+1, updated_at=now() WHERE id=:id AND lock_version=:version
                """).param("parent", body.parentId()).param("sortOrder", body.sortOrder())
                .param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测目录节点已被更新", requireNode(id).version());
        FlatNode after = requireNode(id);
        audit.record("DETECTION_CATALOG_NODE_MOVED", "DETECTION_CATALOG_NODE", id, before, after, null, null);
        return flatView(after);
    }

    @Transactional
    public ProjectRevisionView updateRevision(UUID id, RevisionUpdate body) {
        RevisionRow before = requireRevisionRow(id);
        requireDraft(before);
        int changed = jdbc.sql("""
                UPDATE detection_project_revision SET conclusion=:conclusion, default_note=:defaultNote,
                  lock_version=lock_version+1, updated_at=now() WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("conclusion", blank(body.conclusion())).param("defaultNote", blank(body.defaultNote()))
                .param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", requireRevisionRow(id).version());
        ProjectRevisionView after = requireRevision(id);
        audit.record("DETECTION_PROJECT_REVISION_UPDATED", "DETECTION_PROJECT_REVISION", id, before, after, null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView revise(UUID sourceId) {
        RevisionRow source = requireRevisionRow(sourceId);
        if (!List.of("PUBLISHED", "RETIRED").contains(source.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "DETECTION_PROJECT_NOT_REVISIONABLE", "仅已发布或停用项目可以创建修订");
        }
        boolean hasDraft = jdbc.sql("SELECT EXISTS(SELECT 1 FROM detection_project_revision WHERE project_node_id=:project AND status='DRAFT')")
                .param("project", source.projectNodeId()).query(Boolean.class).single();
        if (hasDraft) throw new ApiException(HttpStatus.CONFLICT, "DETECTION_PROJECT_DRAFT_EXISTS", "该检测项目已有修订草稿");
        int revisionNumber = jdbc.sql("SELECT max(revision_number)+1 FROM detection_project_revision WHERE project_node_id=:project")
                .param("project", source.projectNodeId()).query(Integer.class).single();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_project_revision(id, project_node_id, revision_number, status,
                  conclusion, default_note, created_by)
                VALUES (:id,:project,:revision,'DRAFT',:conclusion,:defaultNote,:actor)
                """).param("id", revisionId).param("project", source.projectNodeId()).param("revision", revisionNumber)
                .param("conclusion", source.conclusion()).param("defaultNote", source.defaultNote())
                .param("actor", actor.require().id()).update();
        List<ParameterView> sourceParameters = parameters(sourceId);
        Map<UUID, UUID> parameterIds = new HashMap<>();
        for (ParameterView parameter : sourceParameters) {
            UUID nextId = UUID.randomUUID();
            parameterIds.put(parameter.id(), nextId);
            int versionNumber = jdbc.sql("SELECT COALESCE(max(version_number),0)+1 FROM test_parameter WHERE code=:code")
                    .param("code", parameter.code()).query(Integer.class).single();
            insertParameter(nextId, revisionId, versionNumber, parameter.code(), parameter.name(), parameter.valueType(),
                    parameter.definition(), parameter.formula(), parameter.unit(), parameter.sortOrder(), parameter.conclusion(),
                    parameter.defaultNote(), parameter.pricingMethod(), parameter.price(), parameter.sampleName(),
                    parameter.promisedDays(), parameter.sampleDescription(), parameter.sampleQuantity(),
                    parameter.postTestTreatment(), parameter.retentionDays(), parameter.retentionQuantity(),
                    parameter.retentionNature(), parameter.defaultCommissioned(), parameter.required(), parameter.enabled(),
                    parameter.remark());
            replaceStandards(nextId, parameter.standards().stream().map(StandardView::id).toList());
        }
        cloneGroups(sourceId, revisionId, parameterIds);
        cloneMarkers(sourceId, revisionId);
        cloneConfigurations(sourceId, revisionId);
        cloneSampleFields(sourceId, revisionId);
        jdbc.sql("""
                INSERT INTO detection_age_report_option(project_revision_id,value,label,default_selected,sort_order)
                SELECT :target,value,label,default_selected,sort_order FROM detection_age_report_option
                WHERE project_revision_id=:source
                """).param("target", revisionId).param("source", sourceId).update();
        ProjectRevisionView created = requireRevision(revisionId);
        audit.record("DETECTION_PROJECT_REVISION_CREATED", "DETECTION_PROJECT_REVISION", revisionId,
                source, created, null, "基于版本 R" + source.revisionNumber() + " 创建修订");
        return created;
    }

    @Transactional
    public TransitionResult transitionRevision(UUID id, TransitionCommand command) {
        RevisionRow before = requireRevisionRow(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        String next;
        if (before.status().equals("DRAFT") && action.equals("PUBLISH")) next = "PUBLISHED";
        else if (before.status().equals("PUBLISHED") && action.equals("RETIRE")) next = "RETIRED";
        else throw new ApiException(HttpStatus.CONFLICT, "INVALID_DETECTION_PROJECT_TRANSITION", "检测项目版本当前状态不允许该动作");
        if (action.equals("PUBLISH")) {
            long parameters = jdbc.sql("SELECT count(*) FROM test_parameter WHERE project_revision_id=:id")
                    .param("id", id).query(Long.class).single();
            if (parameters == 0) throw ApiException.blocked("DETECTION_PROJECT_EMPTY", "检测项目没有参数，不能发布", before.version(), List.of("至少配置一条检测参数"));
            jdbc.sql("""
                    UPDATE test_parameter SET status='RETIRED', lock_version=lock_version+1, updated_at=now()
                    WHERE project_revision_id IN (SELECT id FROM detection_project_revision
                      WHERE project_node_id=:project AND status='PUBLISHED' AND id<>:id)
                    """).param("project", before.projectNodeId()).param("id", id).update();
            jdbc.sql("""
                    UPDATE detection_project_revision SET status='RETIRED', lock_version=lock_version+1, updated_at=now()
                    WHERE project_node_id=:project AND status='PUBLISHED' AND id<>:id
                    """).param("project", before.projectNodeId()).param("id", id).update();
            jdbc.sql("UPDATE test_parameter SET status='PUBLISHED', published_at=now(), lock_version=lock_version+1, updated_at=now() WHERE project_revision_id=:id")
                    .param("id", id).update();
        } else {
            jdbc.sql("UPDATE test_parameter SET status='RETIRED', lock_version=lock_version+1, updated_at=now() WHERE project_revision_id=:id AND status='PUBLISHED'")
                    .param("id", id).update();
        }
        int changed = jdbc.sql("""
                UPDATE detection_project_revision SET status=:status,
                  published_at=CASE WHEN :status='PUBLISHED' THEN now() ELSE published_at END,
                  lock_version=lock_version+1, updated_at=now() WHERE id=:id AND lock_version=:version
                """).param("status", next).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", requireRevisionRow(id).version());
        RevisionRow after = requireRevisionRow(id);
        audit.record("DETECTION_PROJECT_REVISION_" + action, "DETECTION_PROJECT_REVISION", id,
                before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), revisionActions(after.status()));
    }

    @Transactional
    public ParameterView createParameter(UUID revisionId, ParameterMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        validateParameter(body);
        boolean duplicate = jdbc.sql("SELECT EXISTS(SELECT 1 FROM test_parameter WHERE project_revision_id=:revision AND code=:code)")
                .param("revision", revisionId).param("code", body.code().trim()).query(Boolean.class).single();
        if (duplicate) throw new ApiException(HttpStatus.CONFLICT, "PARAMETER_CODE_EXISTS", "当前项目版本已存在该参数代码");
        int versionNumber = jdbc.sql("SELECT COALESCE(max(version_number),0)+1 FROM test_parameter WHERE code=:code")
                .param("code", body.code().trim()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        insertParameter(id, revisionId, versionNumber, body.code(), body.name(), body.valueType(), body.definition(),
                body.formula(), body.unit(), body.sortOrder(), body.conclusion(), body.defaultNote(), body.pricingMethod(),
                body.price(), body.sampleName(), body.promisedDays(), body.sampleDescription(), body.sampleQuantity(),
                body.postTestTreatment(), body.retentionDays(), body.retentionQuantity(), body.retentionNature(),
                body.defaultCommissioned(), body.required(), body.enabled(), body.remark());
        replaceStandards(id, body.standardIds());
        ParameterView created = requireParameter(id);
        audit.record("TEST_PARAMETER_CREATED", "TEST_PARAMETER", id, null, created, null, null);
        return created;
    }

    @Transactional
    public ParameterView updateParameter(UUID revisionId, UUID parameterId, ParameterMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        ParameterView before = requireParameter(parameterId);
        if (!before.projectRevisionId().equals(revisionId)) {
            throw ApiException.notFound("PARAMETER_NOT_FOUND", "检测参数不属于当前项目版本");
        }
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", before.version());
        }
        validateParameter(body);
        int changed = jdbc.sql("""
                UPDATE test_parameter SET name=:name, value_type=:valueType, definition_json=CAST(:definition AS jsonb),
                  formula=:formula, unit=:unit, sort_order=:sortOrder, conclusion=:conclusion,
                  default_note=:defaultNote, pricing_method=:pricingMethod, price=:price, sample_name=:sampleName,
                  promised_days=:promisedDays, sample_description=:sampleDescription, sample_quantity=:sampleQuantity,
                  post_test_treatment=:treatment, retention_days=:retentionDays,
                  retention_quantity=:retentionQuantity, retention_nature=:retentionNature,
                  default_commissioned=:defaultCommissioned, required=:required, enabled=:enabled, remark=:remark,
                  lock_version=lock_version+1, updated_at=now()
                WHERE id=:id AND project_revision_id=:revision AND status='DRAFT' AND lock_version=:version
                """).param("name", body.name().trim()).param("valueType", body.valueType())
                .param("definition", encode(body.definition())).param("formula", blank(body.formula()))
                .param("unit", blank(body.unit())).param("sortOrder", body.sortOrder())
                .param("conclusion", blank(body.conclusion())).param("defaultNote", blank(body.defaultNote()))
                .param("pricingMethod", blank(body.pricingMethod())).param("price", decimal(body.price(), false))
                .param("sampleName", blank(body.sampleName())).param("promisedDays", nonNegative(body.promisedDays()))
                .param("sampleDescription", blank(body.sampleDescription()))
                .param("sampleQuantity", decimal(body.sampleQuantity(), true))
                .param("treatment", blank(body.postTestTreatment())).param("retentionDays", nonNegative(body.retentionDays()))
                .param("retentionQuantity", decimal(body.retentionQuantity(), false))
                .param("retentionNature", blank(body.retentionNature()))
                .param("defaultCommissioned", body.defaultCommissioned()).param("required", body.required())
                .param("enabled", body.enabled()).param("remark", blank(body.remark()))
                .param("id", parameterId).param("revision", revisionId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", requireParameter(parameterId).version());
        replaceStandards(parameterId, body.standardIds());
        ParameterView after = requireParameter(parameterId);
        audit.record("TEST_PARAMETER_UPDATED", "TEST_PARAMETER", parameterId, before, after, null, null);
        return after;
    }

    @Transactional
    public List<ParameterView> batchUpdateParameters(UUID revisionId, BatchParameterMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (body.targets() == null || body.targets().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_BATCH_EMPTY", "批量设置至少选择一条参数");
        }
        if (body.defaultCommissioned() == null && body.required() == null && body.enabled() == null
                && body.pricingMethod() == null && body.price() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_BATCH_NO_CHANGES", "批量设置至少选择一个要修改的字段");
        }
        List<BatchTarget> targets = body.targets().stream().filter(Objects::nonNull).toList();
        if (targets.size() != body.targets().size()
                || targets.stream().map(BatchTarget::id).distinct().count() != targets.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_BATCH_DUPLICATE", "批量设置包含重复参数");
        }
        BigDecimal nextPrice = body.price() == null || body.price().isBlank() ? null : decimal(body.price(), false);
        List<ParameterView> before = new ArrayList<>();
        for (BatchTarget target : targets) {
            ParameterView parameter = requireParameter(target.id());
            if (!revisionId.equals(parameter.projectRevisionId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PARAMETER_BATCH_OUT_OF_SCOPE", "批量设置包含其他项目的参数");
            }
            if (parameter.version() != target.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", parameter.version());
            }
            before.add(parameter);
        }
        for (BatchTarget target : targets) {
            int changed = jdbc.sql("""
                    UPDATE test_parameter SET
                      default_commissioned=COALESCE(:defaultCommissioned,default_commissioned),
                      required=COALESCE(:required,required), enabled=COALESCE(:enabled,enabled),
                      pricing_method=CASE WHEN :pricingMethod IS NULL THEN pricing_method ELSE :pricingMethod END,
                      price=CASE WHEN :applyPrice THEN :price ELSE price END,
                      lock_version=lock_version+1, updated_at=now()
                    WHERE id=:id AND project_revision_id=:revision AND status='DRAFT' AND lock_version=:version
                    """).param("defaultCommissioned", body.defaultCommissioned()).param("required", body.required())
                    .param("enabled", body.enabled()).param("pricingMethod", blank(body.pricingMethod()))
                    .param("applyPrice", body.price() != null).param("price", nextPrice)
                    .param("id", target.id()).param("revision", revisionId).param("version", target.expectedVersion()).update();
            if (changed == 0) {
                throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", requireParameter(target.id()).version());
            }
        }
        List<ParameterView> after = targets.stream().map(target -> requireParameter(target.id())).toList();
        audit.record("TEST_PARAMETER_BATCH_UPDATED", "DETECTION_PROJECT_REVISION", revisionId, before, after, null, null);
        return after;
    }

    @Transactional
    public List<ParameterView> updatePrices(UUID revisionId, List<PricingMutation> changes) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (changes == null || changes.isEmpty() || changes.size() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_PRICING_BATCH_INVALID", "每次须保存1到1000条参数价格");
        }
        List<PricingMutation> unique = changes.stream().filter(Objects::nonNull).toList();
        if (unique.size() != changes.size() || unique.stream().map(PricingMutation::id).distinct().count() != unique.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_PRICING_DUPLICATE", "价格保存包含重复参数");
        }
        List<ParameterView> before = new ArrayList<>();
        for (PricingMutation item : unique) {
            ParameterView parameter = requireParameter(item.id());
            if (!revisionId.equals(parameter.projectRevisionId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PARAMETER_PRICING_OUT_OF_SCOPE", "价格保存包含其他项目的参数");
            }
            if (parameter.version() != item.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT", "检测参数价格已被其他用户更新", parameter.version());
            }
            if (item.pricingMethod() == null || item.pricingMethod().isBlank() || item.pricingMethod().trim().length() > 80) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_PRICING_METHOD_REQUIRED", "请选择计价方式");
            }
            decimal(item.price(), false);
            before.add(parameter);
        }
        for (PricingMutation item : unique) {
            BigDecimal price = decimal(item.price(), false);
            int changed = jdbc.sql("""
                    UPDATE test_parameter SET pricing_method=:method,price=:price,
                      lock_version=lock_version+1,updated_at=now()
                    WHERE id=:id AND project_revision_id=:revision AND status='DRAFT' AND lock_version=:version
                    """).param("method", item.pricingMethod().trim()).param("price", price)
                    .param("id", item.id()).param("revision", revisionId).param("version", item.expectedVersion()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测参数价格已被其他用户更新",
                    requireParameter(item.id()).version());
        }
        List<ParameterView> after = unique.stream().map(item -> requireParameter(item.id())).toList();
        audit.record("TEST_PARAMETER_PRICING_UPDATED", "DETECTION_PROJECT_REVISION", revisionId,
                before, after, null, null);
        return after;
    }

    public PricingQuote quote(UUID revisionId, List<PricingQuoteItem> items) {
        requireRevisionRow(revisionId);
        if (items == null || items.isEmpty() || items.size() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_QUOTE_INVALID", "试算须包含1到1000条参数");
        }
        if (items.stream().map(PricingQuoteItem::parameterId).distinct().count() != items.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_QUOTE_DUPLICATE", "试算参数不能重复");
        }
        List<PricingQuoteLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PricingQuoteItem item : items) {
            ParameterView parameter = requireParameter(item.parameterId());
            if (!revisionId.equals(parameter.projectRevisionId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PARAMETER_QUOTE_OUT_OF_SCOPE", "试算包含其他项目的参数");
            }
            BigDecimal price = decimal(parameter.price(), false);
            if (price == null || parameter.pricingMethod() == null) {
                throw ApiException.blocked("PARAMETER_PRICE_MISSING", "存在未配置价格的参数", parameter.version(),
                        List.of(parameter.code() + " · " + parameter.name()));
            }
            BigDecimal quantity = decimal(item.quantity(), true);
            BigDecimal lineTotal = price.multiply(quantity);
            total = total.add(lineTotal);
            lines.add(new PricingQuoteLine(parameter.id(), parameter.code(), parameter.name(),
                    parameter.pricingMethod(), decimalString(price), decimalString(quantity), decimalString(lineTotal)));
        }
        return new PricingQuote(List.copyOf(lines), decimalString(total));
    }

    @Transactional
    public ProjectRevisionView createGroup(UUID revisionId, GroupMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (revision.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", revision.version());
        }
        validateGroup(revisionId, body);
        int bumped = jdbc.sql("""
                UPDATE detection_project_revision SET lock_version=lock_version+1, updated_at=now()
                WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("id", revisionId).param("version", body.expectedVersion()).update();
        if (bumped == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", requireRevisionRow(revisionId).version());
        UUID groupId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_parameter_group(id,project_revision_id,code,name,sort_order,status)
                VALUES (:id,:revision,:code,:name,:sortOrder,:status)
                """).param("id", groupId).param("revision", revisionId).param("code", body.code().trim())
                .param("name", body.name().trim()).param("sortOrder", body.sortOrder())
                .param("status", body.status().trim().toUpperCase(Locale.ROOT)).update();
        replaceGroupParameters(groupId, body.parameterIds());
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_PARAMETER_GROUP_CREATED", "DETECTION_PARAMETER_GROUP", groupId, null, after, null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView updateGroup(UUID revisionId, UUID groupId, GroupMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        validateGroup(revisionId, body);
        GroupView before = groups(revisionId).stream().filter(group -> group.id().equals(groupId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DETECTION_PARAMETER_GROUP_NOT_FOUND", "参数组合不存在"));
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "参数组合已被更新", before.version());
        }
        int changed = jdbc.sql("""
                UPDATE detection_parameter_group SET code=:code,name=:name,sort_order=:sortOrder,status=:status,
                  lock_version=lock_version+1 WHERE id=:id AND project_revision_id=:revision AND lock_version=:version
                """).param("code", body.code().trim()).param("name", body.name().trim())
                .param("sortOrder", body.sortOrder()).param("status", body.status().trim().toUpperCase(Locale.ROOT)).param("id", groupId)
                .param("revision", revisionId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "参数组合已被更新", before.version());
        replaceGroupParameters(groupId, body.parameterIds());
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_PARAMETER_GROUP_UPDATED", "DETECTION_PARAMETER_GROUP", groupId, before,
                after.groups().stream().filter(group -> group.id().equals(groupId)).findFirst().orElse(null), null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView createMarker(UUID revisionId, MarkerMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (revision.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", revision.version());
        }
        validateMarker(body);
        int bumped = jdbc.sql("""
                UPDATE detection_project_revision SET lock_version=lock_version+1, updated_at=now()
                WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("id", revisionId).param("version", body.expectedVersion()).update();
        if (bumped == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", requireRevisionRow(revisionId).version());
        UUID markerId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_sample_marker(id,project_revision_id,code,name,content_json,status)
                VALUES (:id,:revision,:code,:name,CAST(:content AS jsonb),:status)
                """).param("id", markerId).param("revision", revisionId).param("code", body.code().trim())
                .param("name", body.name().trim()).param("content", encode(body.content()))
                .param("status", body.status().trim().toUpperCase(Locale.ROOT)).update();
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_SAMPLE_MARKER_CREATED", "DETECTION_SAMPLE_MARKER", markerId, null, after, null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView updateMarker(UUID revisionId, UUID markerId, MarkerMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        validateMarker(body);
        MarkerView before = markers(revisionId).stream().filter(marker -> marker.id().equals(markerId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DETECTION_SAMPLE_MARKER_NOT_FOUND", "样本标记不存在"));
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "样本标记已被更新", before.version());
        }
        int changed = jdbc.sql("""
                UPDATE detection_sample_marker SET code=:code,name=:name,content_json=CAST(:content AS jsonb),status=:status,
                  lock_version=lock_version+1 WHERE id=:id AND project_revision_id=:revision AND lock_version=:version
                """).param("code", body.code().trim()).param("name", body.name().trim())
                .param("content", encode(body.content())).param("status", body.status().trim().toUpperCase(Locale.ROOT)).param("id", markerId)
                .param("revision", revisionId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "样本标记已被更新", before.version());
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_SAMPLE_MARKER_UPDATED", "DETECTION_SAMPLE_MARKER", markerId, before,
                after.markers().stream().filter(marker -> marker.id().equals(markerId)).findFirst().orElse(null), null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView createSampleField(UUID revisionId, SampleFieldMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (revision.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", revision.version());
        }
        validateSampleField(body);
        int bumped = jdbc.sql("""
                UPDATE detection_project_revision SET lock_version=lock_version+1,updated_at=now()
                WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("id", revisionId).param("version", body.expectedVersion()).update();
        if (bumped == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新",
                requireRevisionRow(revisionId).version());
        UUID fieldId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_sample_field_definition(id,project_revision_id,source_system,source_key,
                  code,label,field_type,required,unit,placeholder,options_json,core_binding,sort_order)
                VALUES (:id,:revision,'lims',:sourceKey,:code,:label,:fieldType,:required,:unit,:placeholder,
                  CAST(:options AS jsonb),:coreBinding,:sortOrder)
                """).param("id", fieldId).param("revision", revisionId)
                .param("sourceKey", "sample-field:" + fieldId).param("code", body.code().trim())
                .param("label", body.label().trim()).param("fieldType", normalizedFieldType(body.fieldType()))
                .param("required", body.required()).param("unit", blank(body.unit()))
                .param("placeholder", blank(body.placeholder())).param("options", encodeList(cleanOptions(body.options())))
                .param("coreBinding", normalizedCoreBinding(body.coreBinding())).param("sortOrder", body.sortOrder()).update();
        replaceSampleFieldStandards(fieldId, body.standardIds());
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_SAMPLE_FIELD_CREATED", "DETECTION_SAMPLE_FIELD", fieldId, null,
                after.sampleFields().stream().filter(field -> field.id().equals(fieldId)).findFirst().orElse(null), null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView updateSampleField(UUID revisionId, UUID fieldId, SampleFieldMutation body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        validateSampleField(body);
        SampleFieldView before = sampleFields(revisionId).stream().filter(field -> field.id().equals(fieldId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DETECTION_SAMPLE_FIELD_NOT_FOUND", "样品字段不存在"));
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "样品字段已被更新", before.version());
        }
        int changed = jdbc.sql("""
                UPDATE detection_sample_field_definition SET code=:code,label=:label,field_type=:fieldType,
                  required=:required,unit=:unit,placeholder=:placeholder,options_json=CAST(:options AS jsonb),
                  core_binding=:coreBinding,sort_order=:sortOrder,version=version+1,updated_at=now()
                WHERE id=:id AND project_revision_id=:revision AND version=:version
                """).param("code", body.code().trim()).param("label", body.label().trim())
                .param("fieldType", normalizedFieldType(body.fieldType())).param("required", body.required())
                .param("unit", blank(body.unit())).param("placeholder", blank(body.placeholder()))
                .param("options", encodeList(cleanOptions(body.options())))
                .param("coreBinding", normalizedCoreBinding(body.coreBinding())).param("sortOrder", body.sortOrder())
                .param("id", fieldId).param("revision", revisionId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "样品字段已被更新", before.version());
        replaceSampleFieldStandards(fieldId, body.standardIds());
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_SAMPLE_FIELD_UPDATED", "DETECTION_SAMPLE_FIELD", fieldId, before,
                after.sampleFields().stream().filter(field -> field.id().equals(fieldId)).findFirst().orElse(null), null, null);
        return after;
    }

    @Transactional
    public ProjectRevisionView linkConfiguration(UUID revisionId, ConfigurationLink body) {
        RevisionRow revision = requireRevisionRow(revisionId);
        requireDraft(revision);
        if (revision.version() != body.expectedRevisionVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", revision.version());
        }
        String role = body.role() == null ? "" : body.role().trim().toUpperCase(Locale.ROOT);
        if (!List.of("REPORT_TEMPLATE", "COMMISSION_TEMPLATE", "TASK_TEMPLATE", "NUMBERING_RULE", "TEMPLATE_NUMBERING").contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_CONFIGURATION_ROLE_INVALID", "配置绑定角色无效");
        }
        boolean valid = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM configuration_version
                  WHERE id=:id AND state='PUBLISHED' AND configuration_type=:type)
                """).param("id", body.configurationId()).param("type", role).query(Boolean.class).single();
        if (!valid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DETECTION_CONFIGURATION_NOT_PUBLISHED", "只能绑定同类型的已发布配置版本");
        }
        int bumped = jdbc.sql("""
                UPDATE detection_project_revision SET lock_version=lock_version+1,updated_at=now()
                WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("id", revisionId).param("version", body.expectedRevisionVersion()).update();
        if (bumped == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测项目版本已被更新", requireRevisionRow(revisionId).version());
        jdbc.sql("DELETE FROM detection_project_configuration WHERE project_revision_id=:revision AND role=:role")
                .param("revision", revisionId).param("role", role).update();
        jdbc.sql("""
                INSERT INTO detection_project_configuration(project_revision_id,configuration_id,role)
                VALUES (:revision,:configuration,:role)
                """).param("revision", revisionId).param("configuration", body.configurationId()).param("role", role).update();
        ProjectRevisionView after = requireRevision(revisionId);
        audit.record("DETECTION_PROJECT_CONFIGURATION_LINKED", "DETECTION_PROJECT_REVISION", revisionId,
                null, body, null, null);
        return after;
    }

    public ParameterView requireParameter(UUID id) {
        return jdbc.sql(parameterSelect() + " WHERE p.id=:id").param("id", id).query(this::mapParameter).optional()
                .map(parameter -> withStandards(parameter, standards(List.of(id)).getOrDefault(id, List.of())))
                .orElseThrow(() -> ApiException.notFound("PARAMETER_NOT_FOUND", "检测参数不存在"));
    }

    private List<ParameterView> parameters(UUID revisionId) {
        List<ParameterView> values = jdbc.sql(parameterSelect() + " WHERE p.project_revision_id=:revision ORDER BY p.sort_order, p.name, p.id")
                .param("revision", revisionId).query(this::mapParameter).list();
        Map<UUID, List<StandardView>> standards = standards(values.stream().map(ParameterView::id).toList());
        return values.stream().map(value -> withStandards(value, standards.getOrDefault(value.id(), List.of()))).toList();
    }

    private String parameterSelect() {
        return """
                SELECT p.id, p.project_revision_id, p.code, p.name, p.version_number, p.value_type,
                  p.definition_json::text, p.formula, p.unit, p.status, p.enabled, p.lock_version, p.sort_order,
                  p.conclusion, p.default_note, p.pricing_method, p.price, p.sample_name, p.promised_days,
                  p.sample_description, p.sample_quantity, p.post_test_treatment, p.retention_days,
                  p.retention_quantity, p.retention_nature, p.default_commissioned, p.required, p.remark,
                  p.source_system, p.source_key FROM test_parameter p
                """;
    }

    private ParameterView mapParameter(ResultSet rs, int row) throws SQLException {
        return new ParameterView(rs.getObject("id", UUID.class), rs.getObject("project_revision_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getInt("version_number"), rs.getString("value_type"),
                decodeMap(rs.getString("definition_json")), rs.getString("formula"), rs.getString("unit"),
                rs.getString("status"), rs.getBoolean("enabled"), rs.getLong("lock_version"), rs.getInt("sort_order"),
                rs.getString("conclusion"), rs.getString("default_note"), rs.getString("pricing_method"),
                decimalString(rs.getBigDecimal("price")), rs.getString("sample_name"), integerValue(rs, "promised_days"),
                rs.getString("sample_description"), decimalString(rs.getBigDecimal("sample_quantity")),
                rs.getString("post_test_treatment"), integerValue(rs, "retention_days"),
                decimalString(rs.getBigDecimal("retention_quantity")), rs.getString("retention_nature"),
                rs.getBoolean("default_commissioned"), rs.getBoolean("required"), rs.getString("remark"),
                rs.getString("source_system"), rs.getString("source_key"), List.of());
    }

    private Map<UUID, List<StandardView>> standards(List<UUID> parameterIds) {
        if (parameterIds.isEmpty()) return Map.of();
        Map<UUID, List<StandardView>> values = new HashMap<>();
        jdbc.sql("""
                SELECT ps.parameter_id, s.id, s.code, s.name, s.status
                FROM test_parameter_standard ps JOIN detection_standard s ON s.id=ps.standard_id
                WHERE ps.parameter_id IN (:ids) ORDER BY ps.parameter_id, ps.sort_order, s.code
                """).param("ids", parameterIds).query((rs, row) -> {
                    UUID parameterId = rs.getObject("parameter_id", UUID.class);
                    values.computeIfAbsent(parameterId, ignored -> new ArrayList<>()).add(new StandardView(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("status")));
                    return parameterId;
                }).list();
        return values;
    }

    private List<ConfigurationView> configurations(UUID revisionId) {
        return jdbc.sql("""
                SELECT c.id, c.configuration_type, c.code, c.version_number, c.name, c.state, link.role
                FROM detection_project_configuration link JOIN configuration_version c ON c.id=link.configuration_id
                WHERE link.project_revision_id=:id ORDER BY link.role, c.name
                """).param("id", revisionId).query((rs, row) -> new ConfigurationView(
                        rs.getObject("id", UUID.class), rs.getString("configuration_type"), rs.getString("code"),
                        rs.getInt("version_number"), rs.getString("name"), rs.getString("state"), rs.getString("role"))).list();
    }

    private List<GroupView> groups(UUID revisionId) {
        List<GroupView> groups = jdbc.sql("""
                SELECT id, code, name, sort_order, status, lock_version FROM detection_parameter_group
                WHERE project_revision_id=:id ORDER BY sort_order, name
                """).param("id", revisionId).query((rs, row) -> new GroupView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getInt("sort_order"), rs.getString("status"), rs.getLong("lock_version"), List.of())).list();
        return groups.stream().map(group -> new GroupView(group.id(), group.code(), group.name(), group.sortOrder(),
                group.status(), group.version(), jdbc.sql("""
                        SELECT parameter_id FROM detection_parameter_group_item WHERE group_id=:id ORDER BY sort_order
                        """).param("id", group.id()).query(UUID.class).list())).toList();
    }

    private List<MarkerView> markers(UUID revisionId) {
        return jdbc.sql("""
                SELECT id, code, name, content_json::text, status, lock_version FROM detection_sample_marker
                WHERE project_revision_id=:id ORDER BY code
                """).param("id", revisionId).query((rs, row) -> new MarkerView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        decodeMap(rs.getString("content_json")), rs.getString("status"), rs.getLong("lock_version"))).list();
    }

    private FlatNode requireNode(UUID id) {
        return jdbc.sql("""
                SELECT n.id, n.parent_id, n.node_type, n.name, n.symbol, n.sort_order, n.status, n.legacy,
                  n.lock_version, r.id revision_id, r.revision_number, r.status revision_status,
                  COALESCE((SELECT count(*) FROM test_parameter p WHERE p.project_revision_id=r.id),0) parameter_count
                FROM detection_catalog_node n LEFT JOIN LATERAL (
                  SELECT id, revision_number, status FROM detection_project_revision
                  WHERE project_node_id=n.id ORDER BY revision_number DESC LIMIT 1) r ON true WHERE n.id=:id
                """).param("id", id).query(this::mapFlatNode).optional()
                .orElseThrow(() -> ApiException.notFound("DETECTION_CATALOG_NODE_NOT_FOUND", "检测目录节点不存在"));
    }

    private FlatNode mapFlatNode(ResultSet rs, int row) throws SQLException {
        return new FlatNode(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("node_type"), rs.getString("name"), rs.getString("symbol"), rs.getInt("sort_order"),
                rs.getString("status"), rs.getBoolean("legacy"), rs.getLong("lock_version"),
                rs.getObject("revision_id", UUID.class), integerValue(rs, "revision_number"),
                rs.getString("revision_status"), rs.getLong("parameter_count"));
    }

    private RevisionRow requireRevisionRow(UUID id) {
        return jdbc.sql("""
                WITH RECURSIVE ancestry AS (
                  SELECT n.id, n.parent_id, n.name, 0 depth FROM detection_catalog_node n
                  JOIN detection_project_revision r ON r.project_node_id=n.id WHERE r.id=:id
                  UNION ALL SELECT p.id, p.parent_id, p.name, a.depth+1
                  FROM detection_catalog_node p JOIN ancestry a ON a.parent_id=p.id)
                SELECT r.id, r.project_node_id, n.name project_name, n.symbol,
                  (SELECT string_agg(name, ' / ' ORDER BY depth DESC) FROM ancestry) path,
                  r.revision_number, r.status, r.lock_version, r.conclusion, r.default_note,
                  r.source_manifest_sha256, r.source_captured_at, r.published_at
                FROM detection_project_revision r JOIN detection_catalog_node n ON n.id=r.project_node_id
                WHERE r.id=:id
                """).param("id", id).query((rs, row) -> new RevisionRow(
                        rs.getObject("id", UUID.class), rs.getObject("project_node_id", UUID.class),
                        rs.getString("project_name"), rs.getString("symbol"), rs.getString("path"),
                        rs.getInt("revision_number"), rs.getString("status"), rs.getLong("lock_version"),
                        rs.getString("conclusion"), rs.getString("default_note"),
                        rs.getString("source_manifest_sha256"), rs.getObject("source_captured_at", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("DETECTION_PROJECT_REVISION_NOT_FOUND", "检测项目版本不存在"));
    }

    private void insertParameter(UUID id, UUID revisionId, int versionNumber, String code, String name,
                                 String valueType, Map<String, Object> definition, String formula, String unit,
                                 int sortOrder, String conclusion, String defaultNote, String pricingMethod,
                                 String price, String sampleName, Integer promisedDays, String sampleDescription,
                                 String sampleQuantity, String treatment, Integer retentionDays,
                                 String retentionQuantity, String retentionNature, boolean defaultCommissioned,
                                 boolean required, boolean enabled, String remark) {
        jdbc.sql("""
                INSERT INTO test_parameter(id, code, name, version_number, value_type, definition_json, formula, unit,
                  status, created_by, project_revision_id, sort_order, conclusion, default_note, pricing_method,
                  price, sample_name, promised_days, sample_description, sample_quantity, post_test_treatment,
                  retention_days, retention_quantity, retention_nature, default_commissioned, required, enabled, remark)
                VALUES (:id,:code,:name,:versionNumber,:valueType,CAST(:definition AS jsonb),:formula,:unit,
                  'DRAFT',:actor,:revision,:sortOrder,:conclusion,:defaultNote,:pricingMethod,:price,:sampleName,
                  :promisedDays,:sampleDescription,:sampleQuantity,:treatment,:retentionDays,:retentionQuantity,
                  :retentionNature,:defaultCommissioned,:required,:enabled,:remark)
                """).param("id", id).param("code", code.trim()).param("name", name.trim())
                .param("versionNumber", versionNumber).param("valueType", valueType)
                .param("definition", encode(definition)).param("formula", blank(formula)).param("unit", blank(unit))
                .param("actor", actor.require().id()).param("revision", revisionId).param("sortOrder", sortOrder)
                .param("conclusion", blank(conclusion)).param("defaultNote", blank(defaultNote))
                .param("pricingMethod", blank(pricingMethod)).param("price", decimal(price, false))
                .param("sampleName", blank(sampleName)).param("promisedDays", nonNegative(promisedDays))
                .param("sampleDescription", blank(sampleDescription)).param("sampleQuantity", decimal(sampleQuantity, true))
                .param("treatment", blank(treatment)).param("retentionDays", nonNegative(retentionDays))
                .param("retentionQuantity", decimal(retentionQuantity, false)).param("retentionNature", blank(retentionNature))
                .param("defaultCommissioned", defaultCommissioned).param("required", required).param("enabled", enabled)
                .param("remark", blank(remark)).update();
    }

    private void replaceStandards(UUID parameterId, List<UUID> standardIds) {
        List<UUID> unique = standardIds == null ? List.of() : standardIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!unique.isEmpty()) {
            long valid = jdbc.sql("""
                    SELECT count(*) FROM detection_standard
                    WHERE id IN (:ids) AND state='PUBLISHED' AND status='CURRENT'
                    """)
                    .param("ids", unique).query(Long.class).single();
            if (valid != unique.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_INVALID", "检测依据不存在或已停用");
        }
        jdbc.sql("DELETE FROM test_parameter_standard WHERE parameter_id=:id").param("id", parameterId).update();
        for (int index = 0; index < unique.size(); index++) {
            jdbc.sql("INSERT INTO test_parameter_standard(parameter_id,standard_id,sort_order) VALUES (:parameter,:standard,:sortOrder)")
                    .param("parameter", parameterId).param("standard", unique.get(index)).param("sortOrder", index + 1).update();
        }
    }

    private void replaceSampleFieldStandards(UUID fieldId, List<UUID> standardIds) {
        List<UUID> unique = standardIds == null ? List.of() : standardIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (!unique.isEmpty()) {
            long valid = jdbc.sql("""
                    SELECT count(*) FROM detection_standard
                    WHERE id IN (:ids) AND state='PUBLISHED' AND status='CURRENT'
                    """).param("ids", unique).query(Long.class).single();
            if (valid != unique.size()) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "DETECTION_STANDARD_INVALID", "样品字段引用的依据不存在或已停用");
        }
        jdbc.sql("DELETE FROM detection_sample_field_standard WHERE field_definition_id=:id")
                .param("id", fieldId).update();
        for (int index = 0; index < unique.size(); index++) {
            jdbc.sql("""
                    INSERT INTO detection_sample_field_standard(field_definition_id,standard_id,sort_order)
                    VALUES (:field,:standard,:sortOrder)
                    """).param("field", fieldId).param("standard", unique.get(index))
                    .param("sortOrder", index + 1).update();
        }
    }

    private void validateSampleField(SampleFieldMutation body) {
        if (body.code() == null || !body.code().trim().matches("[a-z][a-z0-9_]{0,79}")
                || body.label() == null || body.label().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_REQUIRED",
                    "样品字段代码必须为小写字母、数字和下划线，显示名称不能为空");
        }
        String type = normalizedFieldType(body.fieldType());
        List<String> options = cleanOptions(body.options());
        List<UUID> standards = body.standardIds() == null ? List.of() : body.standardIds().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (List.of("SINGLE_SELECT", "MULTI_SELECT").contains(type) && options.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_OPTIONS_REQUIRED", "选择字段至少配置一个选项");
        }
        if (List.of("STANDARD_SELECT", "STANDARD_MULTI").contains(type) && standards.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_STANDARDS_REQUIRED", "依据字段至少配置一条已发布依据");
        }
        if (!List.of("STANDARD_SELECT", "STANDARD_MULTI").contains(type) && !standards.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_STANDARDS_NOT_ALLOWED", "仅依据字段可以绑定标准");
        }
        String binding = normalizedCoreBinding(body.coreBinding());
        if (!binding.equals("NONE") && !List.of("TEXT", "DECIMAL").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_BINDING_INVALID", "核心字段绑定仅支持文本或数值字段");
        }
    }

    private String normalizedFieldType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("TEXT", "DECIMAL", "DATE", "SINGLE_SELECT", "MULTI_SELECT", "STANDARD_SELECT", "STANDARD_MULTI").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_TYPE_INVALID", "样品字段类型无效");
        }
        return type;
    }

    private String normalizedCoreBinding(String value) {
        String binding = value == null || value.isBlank() ? "NONE" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "NAME", "DESCRIPTION", "QUANTITY", "UNIT").contains(binding)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_BINDING_INVALID", "样品字段核心绑定无效");
        }
        return binding;
    }

    private List<String> cleanOptions(List<String> values) {
        if (values == null) return List.of();
        List<String> options = values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (options.size() > 200 || options.stream().anyMatch(value -> value.length() > 240)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_FIELD_OPTIONS_INVALID", "样品字段选项数量或长度超限");
        }
        return options;
    }

    private void validateGroup(UUID revisionId, GroupMutation body) {
        if (body.code() == null || body.code().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_PARAMETER_GROUP_REQUIRED", "参数组合代码和名称不能为空");
        }
        String status = body.status() == null ? "" : body.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_PARAMETER_GROUP_STATUS_INVALID", "参数组合状态无效");
        }
        List<UUID> parameterIds = body.parameterIds() == null ? List.of() : body.parameterIds().stream()
                .filter(Objects::nonNull).distinct().toList();
        if (parameterIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_PARAMETER_GROUP_EMPTY", "参数组合至少包含一条参数");
        }
        long valid = jdbc.sql("SELECT count(*) FROM test_parameter WHERE project_revision_id=:revision AND id IN (:ids)")
                .param("revision", revisionId).param("ids", parameterIds).query(Long.class).single();
        if (valid != parameterIds.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DETECTION_PARAMETER_GROUP_OUT_OF_SCOPE", "参数组合包含其他项目的参数");
        }
    }

    private void replaceGroupParameters(UUID groupId, List<UUID> parameterIds) {
        List<UUID> unique = parameterIds.stream().distinct().toList();
        jdbc.sql("DELETE FROM detection_parameter_group_item WHERE group_id=:id").param("id", groupId).update();
        for (int index = 0; index < unique.size(); index++) {
            jdbc.sql("""
                    INSERT INTO detection_parameter_group_item(group_id,parameter_id,sort_order)
                    VALUES (:group,:parameter,:sortOrder)
                    """).param("group", groupId).param("parameter", unique.get(index)).param("sortOrder", index + 1).update();
        }
    }

    private void validateMarker(MarkerMutation body) {
        if (body.code() == null || body.code().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_MARKER_REQUIRED", "样本标记代码和名称不能为空");
        }
        String status = body.status() == null ? "" : body.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_MARKER_STATUS_INVALID", "样本标记状态无效");
        }
        if (body.content() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_SAMPLE_MARKER_CONTENT_REQUIRED", "样本标记内容不能为空");
        }
    }

    private void cloneGroups(UUID sourceId, UUID revisionId, Map<UUID, UUID> parameterIds) {
        for (GroupView group : groups(sourceId)) {
            UUID groupId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO detection_parameter_group(id,project_revision_id,code,name,sort_order,status)
                    VALUES (:id,:revision,:code,:name,:sortOrder,:status)
                    """).param("id", groupId).param("revision", revisionId).param("code", group.code())
                    .param("name", group.name()).param("sortOrder", group.sortOrder()).param("status", group.status()).update();
            int sort = 0;
            for (UUID sourceParameter : group.parameterIds()) {
                UUID nextParameter = parameterIds.get(sourceParameter);
                if (nextParameter != null) jdbc.sql("""
                        INSERT INTO detection_parameter_group_item(group_id,parameter_id,sort_order)
                        VALUES (:group,:parameter,:sortOrder)
                        """).param("group", groupId).param("parameter", nextParameter).param("sortOrder", ++sort).update();
            }
        }
    }

    private void cloneMarkers(UUID sourceId, UUID revisionId) {
        for (MarkerView marker : markers(sourceId)) jdbc.sql("""
                INSERT INTO detection_sample_marker(id,project_revision_id,code,name,content_json,status)
                VALUES (:id,:revision,:code,:name,CAST(:content AS jsonb),:status)
                """).param("id", UUID.randomUUID()).param("revision", revisionId).param("code", marker.code())
                .param("name", marker.name()).param("content", encode(marker.content())).param("status", marker.status()).update();
    }

    private void cloneConfigurations(UUID sourceId, UUID revisionId) {
        jdbc.sql("""
                INSERT INTO detection_project_configuration(project_revision_id,configuration_id,role)
                SELECT :target, configuration_id, role FROM detection_project_configuration WHERE project_revision_id=:source
                """).param("target", revisionId).param("source", sourceId).update();
    }

    private void cloneSampleFields(UUID sourceId, UUID revisionId) {
        for (SampleFieldView field : sampleFields(sourceId)) {
            UUID fieldId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO detection_sample_field_definition(id,project_revision_id,source_system,source_key,
                      code,label,field_type,required,unit,placeholder,options_json,core_binding,sort_order)
                    VALUES (:id,:revision,'lims',:sourceKey,:code,:label,:fieldType,:required,:unit,:placeholder,
                      CAST(:options AS jsonb),:coreBinding,:sortOrder)
                    """).param("id", fieldId).param("revision", revisionId)
                    .param("sourceKey", "revision:" + sourceId + ":" + field.code()).param("code", field.code())
                    .param("label", field.label()).param("fieldType", field.fieldType())
                    .param("required", field.required()).param("unit", field.unit())
                    .param("placeholder", field.placeholder()).param("options", encodeList(field.options()))
                    .param("coreBinding", field.coreBinding()).param("sortOrder", field.sortOrder()).update();
            replaceSampleFieldStandards(fieldId, field.standards().stream().map(StandardView::id).toList());
        }
    }

    private void validateParameter(ParameterMutation body) {
        if (body.code() == null || body.code().isBlank() || body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_REQUIRED_FIELDS", "参数代码和名称不能为空");
        }
        if (!List.of("UNSPECIFIED", "NUMBER", "TEXT", "DATE", "CHOICE", "TABLE", "REPEATING_GROUP", "ATTACHMENT").contains(body.valueType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_VALUE_TYPE_INVALID", "检测参数值类型无效");
        }
        if (body.formula() != null && !body.formula().isBlank()) formulas.validateSafety(body.formula());
        decimal(body.price(), false);
        decimal(body.sampleQuantity(), true);
        decimal(body.retentionQuantity(), false);
        nonNegative(body.promisedDays());
        nonNegative(body.retentionDays());
    }

    private void requireDraft(RevisionRow revision) {
        if (!revision.status().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISHED_DETECTION_PROJECT_IMMUTABLE", "已发布检测项目只能通过新修订修改");
        }
    }

    private List<String> revisionActions(String status) {
        return switch (status) {
            case "DRAFT" -> List.of("PUBLISH");
            case "PUBLISHED" -> List.of("REVISE", "RETIRE");
            case "RETIRED" -> List.of("REVISE");
            default -> List.of();
        };
    }

    private void buildPaths(MutableNode node, String parentPath, Map<UUID, String> paths) {
        String path = parentPath.isBlank() ? node.source.name() : parentPath + " / " + node.source.name();
        paths.put(node.source.id(), path);
        node.children.forEach(child -> buildPaths(child, path, paths));
    }

    private CatalogNode toView(MutableNode node, String needle, Map<UUID, String> paths) {
        List<CatalogNode> children = node.children.stream().map(child -> toView(child, needle, paths))
                .filter(Objects::nonNull).toList();
        boolean matches = needle.isBlank() || node.source.name().toLowerCase(Locale.ROOT).contains(needle)
                || (node.source.symbol() != null && node.source.symbol().toLowerCase(Locale.ROOT).contains(needle));
        if (!matches && children.isEmpty()) return null;
        FlatNode value = node.source;
        return new CatalogNode(value.id(), value.parentId(), value.type(), value.name(), value.symbol(),
                value.sortOrder(), value.status(), value.legacy(), value.version(), value.revisionId(),
                value.revisionNumber(), value.revisionStatus(), value.parameterCount(), paths.get(value.id()), children);
    }

    private CatalogNode flatView(FlatNode value) {
        return new CatalogNode(value.id(), value.parentId(), value.type(), value.name(), value.symbol(), value.sortOrder(),
                value.status(), value.legacy(), value.version(), value.revisionId(), value.revisionNumber(),
                value.revisionStatus(), value.parameterCount(), value.name(), List.of());
    }

    private ParameterView withStandards(ParameterView value, List<StandardView> standards) {
        return new ParameterView(value.id(), value.projectRevisionId(), value.code(), value.name(), value.versionNumber(),
                value.valueType(), value.definition(), value.formula(), value.unit(), value.status(), value.enabled(),
                value.version(), value.sortOrder(), value.conclusion(), value.defaultNote(), value.pricingMethod(),
                value.price(), value.sampleName(), value.promisedDays(), value.sampleDescription(), value.sampleQuantity(),
                value.postTestTreatment(), value.retentionDays(), value.retentionQuantity(), value.retentionNature(),
                value.defaultCommissioned(), value.required(), value.remark(), value.sourceSystem(), value.sourceKey(), standards);
    }

    private Integer integerValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nonNegative(Integer value) {
        if (value != null && value < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "NEGATIVE_VALUE", "天数不能为负数");
        return value;
    }

    private BigDecimal decimal(String value, boolean positive) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal result = new BigDecimal(value);
            if (result.scale() > 6 || (positive ? result.signum() <= 0 : result.signum() < 0)) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECIMAL", positive ? "数量必须是正数且最多六位小数" : "数值必须为非负数且最多六位小数");
        }
    }

    private String decimalString(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String encode(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("受控定义无法序列化", exception); }
    }

    private String encodeList(List<String> value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("受控选项无法序列化", exception); }
    }

    private Map<String, Object> decodeMap(String value) throws SQLException {
        try { return json.readValue(value, MAP_TYPE); }
        catch (JacksonException exception) { throw new SQLException("Invalid controlled JSON", exception); }
    }

    private List<String> decodeStringList(String value) throws SQLException {
        try { return json.readValue(value, STRING_LIST_TYPE); }
        catch (JacksonException exception) { throw new SQLException("Invalid controlled option list", exception); }
    }
}
