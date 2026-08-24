package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentTemplateControlService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public DocumentTemplateControlService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                          IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    public record PageView(List<ItemView> items, long total, int page, int limit,
                           long linkedCount, long ambiguousCount, long sourceOnlyCount,
                           long configuredCount) {}
    public record ItemView(UUID id, DocumentType documentType, String sourceFieldCode,
                           UUID projectRevisionId, UUID projectNodeId,
                           String projectName, String parentName, String controlledNumber,
                           String templateVersionNumber, String linkStatus, String catalogStatus,
                           int sourceOccurrence, String sourceSystem, OffsetDateTime sourceCapturedAt,
                           String sourceManifestSha256, long version, OffsetDateTime updatedAt) {}
    public record UpdateCommand(String controlledNumber, String templateVersionNumber, Long expectedVersion) {}

    public PageView list(String requestedDocumentType, String query, UUID catalogNodeId,
                         String requestedLinkStatus, int page, int limit) {
        AuthenticatedUser user = actor.require();
        DocumentType documentType = DocumentType.parse(requestedDocumentType);
        int safePage = Math.max(1, page);
        if (limit < 1 || limit > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TEMPLATE_CONTROL_LIMIT_INVALID",
                    "每页条数必须在 1 到 200 之间");
        }
        String linkStatus = normalizeLinkStatus(requestedLinkStatus);
        String pattern = "%" + escapeLike(query == null ? "" : query.trim()) + "%";
        QueryFilter filter = new QueryFilter(user.organizationId(), documentType.name(), pattern,
                catalogNodeId, linkStatus);
        Stats stats = jdbc.sql(filteredCte() + """
                SELECT count(*) total,
                  count(*) FILTER (WHERE link_status='LINKED') linked_count,
                  count(*) FILTER (WHERE link_status='AMBIGUOUS') ambiguous_count,
                  count(*) FILTER (WHERE link_status='SOURCE_ONLY') source_only_count,
                  count(*) FILTER (WHERE controlled_number IS NOT NULL
                    AND template_version_number IS NOT NULL) configured_count
                FROM filtered
                """).params(filter.params()).query((rs, row) -> new Stats(
                        rs.getLong("total"), rs.getLong("linked_count"), rs.getLong("ambiguous_count"),
                        rs.getLong("source_only_count"), rs.getLong("configured_count"))).single();
        long offset = (long) (safePage - 1) * limit;
        List<ItemView> items = jdbc.sql(filteredCte() + """
                SELECT id,document_type,source_field_code,project_revision_id,project_node_id,
                  project_name_snapshot,parent_name_snapshot,
                  controlled_number,template_version_number,link_status,catalog_status,source_occurrence,
                  source_system,source_captured_at,source_manifest_sha256,lock_version,updated_at
                FROM filtered ORDER BY source_occurrence,id LIMIT :limit OFFSET :offset
                """).params(filter.params()).param("limit", limit).param("offset", offset)
                .query(this::map).list();
        return new PageView(items, stats.total(), safePage, limit, stats.linked(), stats.ambiguous(),
                stats.sourceOnly(), stats.configured());
    }

    public ItemView update(UUID id, String key, UpdateCommand command) {
        return idempotency.execute(key, "document-template-control-update:" + id, command, ItemView.class,
                () -> updateInternal(id, command));
    }

    @Transactional
    protected ItemView updateInternal(UUID id, UpdateCommand command) {
        ItemView before = require(id);
        if (command.expectedVersion() == null || command.expectedVersion() != before.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", before.documentType().label + "版本配置已被其他用户更新",
                    before.version());
        }
        String controlledNumber = optionalText(command.controlledNumber(), 160,
                "DOCUMENT_CONTROLLED_NUMBER_TOO_LONG", "受控号最多 160 字");
        String templateVersion = optionalText(command.templateVersionNumber(), 120,
                "DOCUMENT_TEMPLATE_VERSION_TOO_LONG", "版本号最多 120 字");
        int updated = jdbc.sql("""
                UPDATE document_template_control
                SET controlled_number=:controlledNumber,template_version_number=:templateVersion,
                  lock_version=lock_version+1,updated_by=:actor,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND lock_version=:version
                """).param("controlledNumber", controlledNumber).param("templateVersion", templateVersion)
                .param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", before.documentType().label + "版本配置已被其他用户更新",
                    require(id).version());
        }
        ItemView after = require(id);
        audit.record("DOCUMENT_TEMPLATE_CONTROL_UPDATED", "DOCUMENT_TEMPLATE_CONTROL", id,
                before, after, null, after.documentType().label + " · " + after.projectName());
        return after;
    }

    public ItemView require(UUID id) {
        return jdbc.sql("""
                SELECT control.id,control.document_type,control.source_field_code,
                  control.project_revision_id,revision.project_node_id,
                  control.project_name_snapshot,control.parent_name_snapshot,control.controlled_number,
                  control.template_version_number,control.link_status,node.status catalog_status,
                  control.source_occurrence,control.source_system,control.source_captured_at,
                  control.source_manifest_sha256,control.lock_version,control.updated_at
                FROM document_template_control control
                LEFT JOIN detection_project_revision revision ON revision.id=control.project_revision_id
                LEFT JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE control.id=:id AND control.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(this::map).optional().orElseThrow(() -> ApiException.notFound(
                        "DOCUMENT_TEMPLATE_CONTROL_NOT_FOUND", "文档版本配置不存在"));
    }

    private String filteredCte() {
        return """
                WITH RECURSIVE selected_node(id) AS (
                  SELECT CAST(:catalogNodeId AS uuid)
                  WHERE CAST(:catalogNodeId AS uuid) IS NOT NULL
                  UNION ALL
                  SELECT child.id FROM detection_catalog_node child
                  JOIN selected_node parent ON child.parent_id=parent.id
                ), filtered AS (
                  SELECT control.id,control.document_type,control.source_field_code,
                    control.project_revision_id,revision.project_node_id,
                    control.project_name_snapshot,control.parent_name_snapshot,control.controlled_number,
                    control.template_version_number,control.link_status,node.status catalog_status,
                    control.source_occurrence,control.source_system,control.source_captured_at,
                    control.source_manifest_sha256,control.lock_version,control.updated_at
                  FROM document_template_control control
                  LEFT JOIN detection_project_revision revision ON revision.id=control.project_revision_id
                  LEFT JOIN detection_catalog_node node ON node.id=revision.project_node_id
                  WHERE control.organization_id=:organization
                    AND control.document_type=:documentType
                    AND (:linkStatus='ALL' OR control.link_status=:linkStatus)
                    AND (control.project_name_snapshot ILIKE :pattern ESCAPE '\\'
                      OR control.parent_name_snapshot ILIKE :pattern ESCAPE '\\'
                      OR COALESCE(control.controlled_number,'') ILIKE :pattern ESCAPE '\\'
                      OR COALESCE(control.template_version_number,'') ILIKE :pattern ESCAPE '\\')
                    AND (CAST(:catalogNodeId AS uuid) IS NULL
                      OR revision.project_node_id IN (SELECT id FROM selected_node))
                )
                """;
    }

    private ItemView map(ResultSet rs, int row) throws SQLException {
        return new ItemView(rs.getObject("id", UUID.class),
                DocumentType.valueOf(rs.getString("document_type")), rs.getString("source_field_code"),
                rs.getObject("project_revision_id", UUID.class),
                rs.getObject("project_node_id", UUID.class), rs.getString("project_name_snapshot"),
                rs.getString("parent_name_snapshot"), rs.getString("controlled_number"),
                rs.getString("template_version_number"), rs.getString("link_status"),
                rs.getString("catalog_status"), rs.getInt("source_occurrence"),
                rs.getString("source_system"), rs.getObject("source_captured_at", OffsetDateTime.class),
                rs.getString("source_manifest_sha256"), rs.getLong("lock_version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String normalizeLinkStatus(String value) {
        String normalized = value == null || value.isBlank() ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "LINKED", "AMBIGUOUS", "SOURCE_ONLY").contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TEMPLATE_LINK_STATUS_INVALID",
                    "项目关联状态无效");
        }
        return normalized;
    }

    private String optionalText(String value, int max, String code, String message) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        return normalized;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Stats(long total, long linked, long ambiguous, long sourceOnly, long configured) {}
    private record QueryFilter(UUID organization, String documentType, String pattern,
                               UUID catalogNodeId, String linkStatus) {
        java.util.Map<String, Object> params() {
            java.util.Map<String, Object> values = new java.util.HashMap<>();
            values.put("organization", organization);
            values.put("documentType", documentType);
            values.put("pattern", pattern);
            values.put("catalogNodeId", catalogNodeId);
            values.put("linkStatus", linkStatus);
            return values;
        }
    }

    public enum DocumentType {
        REPORT("报告"), COMMISSION("委托单"), TASK("任务单");

        private final String label;

        DocumentType(String label) { this.label = label; }

        static DocumentType parse(String value) {
            try {
                return DocumentType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_TEMPLATE_TYPE_INVALID",
                        "文档版本类型无效");
            }
        }
    }
}
