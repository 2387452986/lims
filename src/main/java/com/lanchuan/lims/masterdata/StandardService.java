package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class StandardService {
    private static final Set<String> OWNERSHIP_TYPES = Set.of("SYSTEM", "ORGANIZATION");
    private static final Set<String> BASIS_TYPES = Set.of("DETECTION", "JUDGEMENT", "BOTH");
    private static final Set<String> STANDARD_TYPES = Set.of(
            "NATIONAL", "INDUSTRY", "LOCAL", "ASSOCIATION", "ENTERPRISE", "OTHER");
    private static final Set<String> STANDARD_STATUSES = Set.of("CURRENT", "UPCOMING", "REPEALED");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;

    public StandardService(JdbcClient jdbc, CurrentActor actor, AuditService audit, StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
    }

    public record Summary(UUID id, UUID seriesId, int revisionNumber, String code, String name,
                          String ownershipType, String basisType, String standardType,
                          String issuingInstitution, LocalDate effectiveDate, LocalDate repealDate,
                          String standardStatus, String state, long version, int sourceAttachmentCount,
                          String sourceValidationWarning, boolean hasDocument, int parameterCount, OffsetDateTime updatedAt,
                          List<String> allowedActions) {}

    public record View(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber, String code, String name,
                       String ownershipType, String basisType, String standardType, String issuingInstitution,
                       String citedBasis, LocalDate effectiveDate, LocalDate repealDate, String standardStatus,
                       String state, long version, UUID documentFileId, String documentFileName,
                       int sourceAttachmentCount, String sourceValidationWarning, String sourceSystem, String sourceKey, String sourceRecordId,
                       String sourcePath, OffsetDateTime sourceCapturedAt, String contentSha256,
                       OffsetDateTime publishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       List<UUID> parameterIds, List<String> allowedActions) {}

    public record Write(String code, String name, String ownershipType, String basisType, String standardType,
                        String issuingInstitution, String citedBasis, LocalDate effectiveDate, LocalDate repealDate,
                        String standardStatus, UUID documentFileId, List<UUID> parameterIds, long expectedVersion) {}

    public CursorPage<Summary> list(String query, String basisType, String standardType, String standardStatus,
                                    String state, LocalDate effectiveFrom, LocalDate effectiveTo,
                                    String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" :
                " AND (standard.updated_at, standard.id) < (:cursorTime, :cursorId)";
        String fromClause = effectiveFrom == null ? "" : " AND standard.effective_date >= :effectiveFrom";
        String toClause = effectiveTo == null ? "" : " AND standard.effective_date <= :effectiveTo";
        String filters = """
                WHERE (:query = '' OR standard.code ILIKE :pattern ESCAPE '\\'
                  OR standard.name ILIKE :pattern ESCAPE '\\'
                  OR COALESCE(standard.issuing_institution,'') ILIKE :pattern ESCAPE '\\')
                  AND (:basisType = '' OR standard.basis_type=:basisType)
                  AND (:standardType = '' OR standard.standard_type=:standardType)
                  AND (:standardStatus = '' OR standard.status=:standardStatus)
                  AND (:state = '' OR standard.state=:state)
                """ + fromClause + toClause;
        String latest = """
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) standard.*
                  FROM detection_standard standard
                  ORDER BY series_id, revision_number DESC
                )
                """;
        String select = latest + """
                SELECT standard.id, standard.series_id, standard.revision_number, standard.code, standard.name,
                  standard.ownership_type, standard.basis_type, standard.standard_type,
                  standard.issuing_institution, standard.effective_date, standard.repeal_date,
                  standard.status, standard.state, standard.lock_version, standard.source_attachment_count,
                  standard.source_validation_warning,
                  standard.document_file_id IS NOT NULL has_document,
                  (SELECT count(*) FROM test_parameter_standard scope
                   WHERE scope.standard_id=standard.id) parameter_count,
                  standard.updated_at
                FROM latest standard
                """ + filters + cursorClause + " ORDER BY standard.updated_at DESC, standard.id DESC LIMIT :fetch";
        String needle = query == null ? "" : query.trim();
        String pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        JdbcClient.StatementSpec statement = jdbc.sql(select)
                .param("query", needle).param("pattern", pattern)
                .param("basisType", normalizedFilter(basisType, BASIS_TYPES))
                .param("standardType", normalizedFilter(standardType, STANDARD_TYPES))
                .param("standardStatus", normalizedFilter(standardStatus, STANDARD_STATUSES))
                .param("state", normalizedFilter(state, Set.of("DRAFT", "PUBLISHED", "RETIRED")))
                .param("fetch", limit + 1);
        if (effectiveFrom != null) statement = statement.param("effectiveFrom", effectiveFrom);
        if (effectiveTo != null) statement = statement.param("effectiveTo", effectiveTo);
        if (cursor != null) statement = statement.param("cursorTime", cursor.updatedAt()).param("cursorId", cursor.id());
        List<Summary> fetched = statement.query(this::mapSummary).list();
        boolean hasMore = fetched.size() > limit;
        List<Summary> items = hasMore ? fetched.subList(0, limit) : fetched;

        JdbcClient.StatementSpec countStatement = jdbc.sql(latest + "SELECT count(*) FROM latest standard " + filters)
                .param("query", needle).param("pattern", pattern)
                .param("basisType", normalizedFilter(basisType, BASIS_TYPES))
                .param("standardType", normalizedFilter(standardType, STANDARD_TYPES))
                .param("standardStatus", normalizedFilter(standardStatus, STANDARD_STATUSES))
                .param("state", normalizedFilter(state, Set.of("DRAFT", "PUBLISHED", "RETIRED")));
        if (effectiveFrom != null) countStatement = countStatement.param("effectiveFrom", effectiveFrom);
        if (effectiveTo != null) countStatement = countStatement.param("effectiveTo", effectiveTo);
        long total = countStatement.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(List.copyOf(items), next, hasMore, total);
    }

    public View require(UUID id) {
        View view = jdbc.sql("""
                SELECT standard.id, standard.series_id, standard.predecessor_id, standard.revision_number,
                  standard.code, standard.name, standard.ownership_type, standard.basis_type,
                  standard.standard_type, standard.issuing_institution, standard.cited_basis,
                  standard.effective_date, standard.repeal_date, standard.status, standard.state,
                  standard.lock_version, standard.document_file_id, file.original_name document_file_name,
                  standard.source_attachment_count, standard.source_validation_warning,
                  standard.source_system, standard.source_key,
                  standard.source_record_id, standard.source_path, standard.source_captured_at,
                  standard.content_sha256, standard.published_at, standard.created_at, standard.updated_at
                FROM detection_standard standard
                LEFT JOIN stored_file file ON file.id=standard.document_file_id
                WHERE standard.id=:id
                """).param("id", id).query(this::mapView).optional()
                .orElseThrow(() -> ApiException.notFound("DETECTION_STANDARD_NOT_FOUND", "标准不存在"));
        return withParameters(view);
    }

    Optional<View> latestByCode(String code) {
        UUID id = jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) id, code
                  FROM detection_standard ORDER BY series_id, revision_number DESC
                )
                SELECT id FROM latest WHERE lower(code)=lower(:code) LIMIT 1
                """).param("code", code.trim()).query(UUID.class).optional().orElse(null);
        return id == null ? Optional.empty() : Optional.of(require(id));
    }

    public List<View> revisions(UUID id) {
        View current = require(id);
        return jdbc.sql("""
                SELECT standard.id, standard.series_id, standard.predecessor_id, standard.revision_number,
                  standard.code, standard.name, standard.ownership_type, standard.basis_type,
                  standard.standard_type, standard.issuing_institution, standard.cited_basis,
                  standard.effective_date, standard.repeal_date, standard.status, standard.state,
                  standard.lock_version, standard.document_file_id, file.original_name document_file_name,
                  standard.source_attachment_count, standard.source_validation_warning,
                  standard.source_system, standard.source_key,
                  standard.source_record_id, standard.source_path, standard.source_captured_at,
                  standard.content_sha256, standard.published_at, standard.created_at, standard.updated_at
                FROM detection_standard standard LEFT JOIN stored_file file ON file.id=standard.document_file_id
                WHERE standard.series_id=:series ORDER BY standard.revision_number DESC
                """).param("series", current.seriesId()).query(this::mapView).list().stream()
                .map(this::withParameters).toList();
    }

    @Transactional
    public View create(Write command) {
        Validated value = validate(command);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_standard(id,series_id,revision_number,state,source_system,source_key,
                  code,name,ownership_type,basis_type,standard_type,issuing_institution,cited_basis,
                  effective_date,repeal_date,status,document_file_id,created_by,updated_by)
                VALUES (:id,:id,1,'DRAFT','lims',:sourceKey,:code,:name,:ownership,:basis,:type,
                  :issuer,:cited,:effective,:repeal,:status,:file,:actor,:actor)
                """).param("id", id).param("sourceKey", "standard:" + id)
                .param("code", value.code()).param("name", value.name()).param("ownership", value.ownershipType())
                .param("basis", value.basisType()).param("type", value.standardType())
                .param("issuer", value.issuingInstitution()).param("cited", value.citedBasis())
                .param("effective", value.effectiveDate()).param("repeal", value.repealDate())
                .param("status", value.standardStatus()).param("file", value.documentFileId())
                .param("actor", actor.require().id()).update();
        replaceParameters(id, value.parameterIds());
        View created = require(id);
        audit.record("DETECTION_STANDARD_CREATED", "DETECTION_STANDARD", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Write command) {
        View before = require(id);
        requireDraft(before);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "标准已被其他用户更新", before.version());
        }
        Validated value = validate(command);
        int changed = jdbc.sql("""
                UPDATE detection_standard SET code=:code,name=:name,ownership_type=:ownership,
                  basis_type=:basis,standard_type=:type,issuing_institution=:issuer,cited_basis=:cited,
                  effective_date=:effective,repeal_date=:repeal,status=:status,document_file_id=:file,
                  source_validation_warning=NULL,
                  lock_version=lock_version+1,updated_by=:actor,updated_at=now()
                WHERE id=:id AND state='DRAFT' AND lock_version=:version
                """).param("code", value.code()).param("name", value.name())
                .param("ownership", value.ownershipType()).param("basis", value.basisType())
                .param("type", value.standardType()).param("issuer", value.issuingInstitution())
                .param("cited", value.citedBasis()).param("effective", value.effectiveDate())
                .param("repeal", value.repealDate()).param("status", value.standardStatus())
                .param("file", value.documentFileId()).param("actor", actor.require().id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "标准已被其他用户更新", require(id).version());
        }
        replaceParameters(id, value.parameterIds());
        View after = require(id);
        audit.record("DETECTION_STANDARD_UPDATED", "DETECTION_STANDARD", id, before, after, null, null);
        return after;
    }

    @Transactional
    public View revise(UUID id) {
        View before = require(id);
        if (before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "DETECTION_STANDARD_REVISION_INVALID", "草稿可直接编辑，无需修订");
        }
        jdbc.sql("SELECT id FROM detection_standard WHERE series_id=:series FOR UPDATE")
                .param("series", before.seriesId()).query(UUID.class).list();
        if (jdbc.sql("SELECT count(*) FROM detection_standard WHERE series_id=:series AND state='DRAFT'")
                .param("series", before.seriesId()).query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DETECTION_STANDARD_DRAFT_EXISTS", "该标准已有修订草稿");
        }
        int nextRevision = jdbc.sql("SELECT max(revision_number)+1 FROM detection_standard WHERE series_id=:series")
                .param("series", before.seriesId()).query(Integer.class).single();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO detection_standard(id,series_id,predecessor_id,revision_number,state,source_system,source_key,
                  code,name,ownership_type,basis_type,standard_type,issuing_institution,cited_basis,effective_date,
                  repeal_date,status,document_file_id,source_attachment_count,source_validation_warning,created_by,updated_by)
                VALUES (:id,:series,:predecessor,:revision,'DRAFT','lims',:sourceKey,:code,:name,:ownership,:basis,
                  :type,:issuer,:cited,:effective,:repeal,:status,:file,:sourceAttachments,:sourceWarning,:actor,:actor)
                """).param("id", revisionId).param("series", before.seriesId()).param("predecessor", before.id())
                .param("revision", nextRevision).param("sourceKey", "standard:" + before.seriesId())
                .param("code", before.code()).param("name", before.name()).param("ownership", before.ownershipType())
                .param("basis", before.basisType()).param("type", before.standardType())
                .param("issuer", before.issuingInstitution()).param("cited", before.citedBasis())
                .param("effective", before.effectiveDate()).param("repeal", before.repealDate())
                .param("status", before.standardStatus()).param("file", before.documentFileId())
                .param("sourceAttachments", before.sourceAttachmentCount())
                .param("sourceWarning", before.sourceValidationWarning()).param("actor", actor.require().id()).update();
        replaceParameters(revisionId, before.parameterIds());
        View revision = require(revisionId);
        audit.record("DETECTION_STANDARD_REVISED", "DETECTION_STANDARD", revisionId, before, revision,
                "CONTROLLED_REVISION", "从版本 " + before.revisionNumber() + " 创建修订");
        return revision;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "标准已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if (action.equals("DISCARD") && before.state().equals("DRAFT")) {
            requireComment(command.comment(), "废弃草稿必须填写原因");
            audit.record("DETECTION_STANDARD_DISCARDED", "DETECTION_STANDARD", id, before, null,
                    command.reasonCode(), command.comment());
            jdbc.sql("DELETE FROM test_parameter_standard WHERE standard_id=:id")
                    .param("id", id).update();
            jdbc.sql("DELETE FROM detection_standard WHERE id=:id AND state='DRAFT' AND lock_version=:version")
                    .param("id", id).param("version", before.version()).update();
            return new TransitionResult("DISCARDED", before.version() + 1, List.of());
        }
        String next;
        if (action.equals("PUBLISH") && before.state().equals("DRAFT")) {
            jdbc.sql("SELECT id FROM detection_standard WHERE series_id=:series FOR UPDATE")
                    .param("series", before.seriesId()).query(UUID.class).list();
            UUID duplicate = jdbc.sql("""
                    SELECT id FROM detection_standard WHERE state='PUBLISHED' AND series_id<>:series
                      AND lower(code)=lower(:code) LIMIT 1
                    """).param("series", before.seriesId()).param("code", before.code())
                    .query(UUID.class).optional().orElse(null);
            if (duplicate != null) {
                throw new ApiException(HttpStatus.CONFLICT, "DETECTION_STANDARD_CODE_EXISTS", "已有发布标准使用该标准编号");
            }
            jdbc.sql("""
                    UPDATE detection_standard SET state='RETIRED',lock_version=lock_version+1,
                      updated_by=:actor,updated_at=now()
                    WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("actor", actor.require().id()).param("series", before.seriesId()).param("id", id).update();
            next = "PUBLISHED";
        } else if (action.equals("RETIRE") && before.state().equals("PUBLISHED")) {
            requireComment(command.comment(), "停用发布标准必须填写原因");
            next = "RETIRED";
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "DETECTION_STANDARD_TRANSITION_INVALID", "当前标准不能执行该动作");
        }
        int changed = jdbc.sql("""
                UPDATE detection_standard SET state=:state,lock_version=lock_version+1,updated_by=:actor,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,updated_at=now()
                WHERE id=:id AND lock_version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "标准已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("DETECTION_STANDARD_" + action, "DETECTION_STANDARD", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private Summary mapSummary(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new Summary(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getInt("revision_number"), rs.getString("code"), rs.getString("name"),
                rs.getString("ownership_type"), rs.getString("basis_type"), rs.getString("standard_type"),
                rs.getString("issuing_institution"), rs.getObject("effective_date", LocalDate.class),
                rs.getObject("repeal_date", LocalDate.class), rs.getString("status"), state,
                rs.getLong("lock_version"), rs.getInt("source_attachment_count"),
                rs.getString("source_validation_warning"), rs.getBoolean("has_document"),
                rs.getInt("parameter_count"), rs.getObject("updated_at", OffsetDateTime.class), actions(state));
    }

    private View mapView(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), rs.getString("code"),
                rs.getString("name"), rs.getString("ownership_type"), rs.getString("basis_type"),
                rs.getString("standard_type"), rs.getString("issuing_institution"), rs.getString("cited_basis"),
                rs.getObject("effective_date", LocalDate.class), rs.getObject("repeal_date", LocalDate.class),
                rs.getString("status"), state, rs.getLong("lock_version"),
                rs.getObject("document_file_id", UUID.class), rs.getString("document_file_name"),
                rs.getInt("source_attachment_count"), rs.getString("source_validation_warning"),
                rs.getString("source_system"), rs.getString("source_key"),
                rs.getString("source_record_id"), rs.getString("source_path"),
                rs.getObject("source_captured_at", OffsetDateTime.class), rs.getString("content_sha256"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), List.of(), actions(state));
    }

    private View withParameters(View value) {
        List<UUID> parameterIds = jdbc.sql("""
                SELECT parameter_id FROM test_parameter_standard
                WHERE standard_id=:id ORDER BY sort_order, parameter_id
                """).param("id", value.id()).query(UUID.class).list();
        return new View(value.id(), value.seriesId(), value.predecessorId(), value.revisionNumber(), value.code(),
                value.name(), value.ownershipType(), value.basisType(), value.standardType(),
                value.issuingInstitution(), value.citedBasis(), value.effectiveDate(), value.repealDate(),
                value.standardStatus(), value.state(), value.version(), value.documentFileId(),
                value.documentFileName(), value.sourceAttachmentCount(), value.sourceValidationWarning(),
                value.sourceSystem(), value.sourceKey(),
                value.sourceRecordId(), value.sourcePath(), value.sourceCapturedAt(), value.contentSha256(),
                value.publishedAt(), value.createdAt(), value.updatedAt(), parameterIds, value.allowedActions());
    }

    private Validated validate(Write command) {
        String code = required(command.code(), 240, "标准编号");
        String name = required(command.name(), 500, "标准名称");
        String ownership = requiredEnum(command.ownershipType(), OWNERSHIP_TYPES, "标准归属");
        String basis = requiredEnum(command.basisType(), BASIS_TYPES, "依据类型");
        String type = requiredEnum(command.standardType(), STANDARD_TYPES, "标准类型");
        String status = requiredEnum(command.standardStatus(), STANDARD_STATUSES, "标准状态");
        if (command.effectiveDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_EFFECTIVE_DATE_REQUIRED", "请选择实施日期");
        }
        if (command.repealDate() != null && command.repealDate().isBefore(command.effectiveDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_DATE_INVALID", "废止日期不能早于实施日期");
        }
        String issuer = optional(command.issuingInstitution(), 240, "发布机构");
        String cited = optional(command.citedBasis(), 1000, "引用依据");
        if (command.documentFileId() != null) {
            StoredFileService.View file = files.require(command.documentFileId());
            if (!file.mediaType().equals("application/pdf") || !file.purpose().equals("DETECTION_STANDARD_DOCUMENT")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DETECTION_STANDARD_DOCUMENT_INVALID",
                        "标准附件必须是通过标准文档入口上传的受控 PDF");
            }
        }
        List<UUID> parameterIds = command.parameterIds() == null ? List.of()
                : command.parameterIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (parameterIds.size() > 5000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_SCOPE_EXCEEDED", "单个标准最多关联5000条参数");
        }
        if (!parameterIds.isEmpty()) {
            long existing = jdbc.sql("SELECT count(*) FROM test_parameter WHERE id IN (:ids)")
                    .param("ids", parameterIds).query(Long.class).single();
            if (existing != parameterIds.size()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DETECTION_STANDARD_SCOPE_INVALID",
                        "适用范围包含不存在的检测参数");
            }
        }
        return new Validated(code, name, ownership, basis, type, issuer, cited, command.effectiveDate(),
                command.repealDate(), status, command.documentFileId(), parameterIds);
    }

    private void replaceParameters(UUID standardId, List<UUID> parameterIds) {
        jdbc.sql("DELETE FROM test_parameter_standard WHERE standard_id=:id")
                .param("id", standardId).update();
        for (int sortOrder = 0; sortOrder < parameterIds.size(); sortOrder++) {
            jdbc.sql("INSERT INTO test_parameter_standard(parameter_id,standard_id,sort_order) VALUES (:parameter,:standard,:sortOrder)")
                    .param("standard", standardId).param("parameter", parameterIds.get(sortOrder))
                    .param("sortOrder", sortOrder).update();
        }
    }

    private void requireDraft(View value) {
        if (!value.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "DETECTION_STANDARD_IMMUTABLE", "已发布或停用标准须创建修订后修改");
        }
    }

    private String normalizedFilter(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_FILTER_INVALID", "标准筛选条件无效");
        }
        return normalized;
    }

    private String requiredEnum(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_FIELD_REQUIRED", label + "不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_ENUM_INVALID", label + "无效");
        }
        return normalized;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_FIELD_REQUIRED", label + "不能为空");
        }
        String result = value.trim();
        if (result.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_FIELD_TOO_LONG", label + "超过最大长度");
        }
        return result;
    }

    private String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DETECTION_STANDARD_FIELD_TOO_LONG", label + "超过最大长度");
        }
        return result;
    }

    private void requireComment(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSITION_REASON_REQUIRED", message);
        }
    }

    private static List<String> actions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("UPDATE", "PUBLISH", "DISCARD");
            case "PUBLISHED" -> List.of("REVISE", "RETIRE");
            case "RETIRED" -> List.of("REVISE");
            default -> List.of();
        };
    }

    private record Validated(String code, String name, String ownershipType, String basisType,
                             String standardType, String issuingInstitution, String citedBasis,
                             LocalDate effectiveDate, LocalDate repealDate, String standardStatus,
                             UUID documentFileId, List<UUID> parameterIds) {}
}
