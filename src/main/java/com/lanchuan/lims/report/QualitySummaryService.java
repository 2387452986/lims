package com.lanchuan.lims.report;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class QualitySummaryService {
    private static final UUID NO_ID = new UUID(0, 0);
    private static final Set<String> TYPES = Set.of("STAGE", "COMPREHENSIVE");
    private static final Set<String> CONTENT_CODES = Set.of(
            "FOUNDATION_ENGINEERING", "EARTHWORK", "MASONRY_ENGINEERING", "CONCRETE_ENGINEERING",
            "METAL_STRUCTURE", "MECHANICAL_ELECTRICAL", "AUTOMATION", "BRIDGE_ENGINEERING",
            "ROAD_ENGINEERING", "BUILDING_ENGINEERING", "TEMPORARY_ENGINEERING");
    private static final Set<String> ATTACHMENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final DataScopeService access;
    private final AuditService audit;

    public QualitySummaryService(JdbcClient jdbc, CurrentActor actor, DataScopeService access, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.access = access;
        this.audit = audit;
    }

    public record Filters(String documentType, String number, String projectNumber, String projectName,
                          String customerName, String contentCode, String state,
                          LocalDate reportFrom, LocalDate reportTo, String cursor, int limit) {}
    public record Write(String documentType, String number, UUID projectId, UUID customerId,
                        String sectionName, LocalDate periodStart, LocalDate periodEnd, LocalDate reportOn,
                        List<String> contentCodes, String conclusion, List<UUID> attachmentFileIds,
                        Long expectedVersion) {}
    public record Attachment(UUID id, String name, String mediaType, long sizeBytes) {}
    public record View(UUID id, String documentType, String number, UUID projectId, UUID customerId,
                       String projectNumber, String projectName, String customerName, String sectionName,
                       LocalDate periodStart, LocalDate periodEnd, LocalDate reportOn,
                       List<String> contentCodes, String conclusion, List<Attachment> attachments,
                       String state, long version, UUID createdBy, String creatorName,
                       UUID publishedBy, String publisherName, OffsetDateTime publishedAt,
                       OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record ProjectOption(UUID id, String number, String name, UUID customerId,
                                String customerName, String sectionName) {}
    public record CustomerOption(UUID id, String code, String name) {}
    public record Options(List<ProjectOption> projects, List<CustomerOption> customers) {}

    public CursorPage<View> list(Filters filters) {
        AuthenticatedUser user = actor.require();
        String type = normalizeType(filters.documentType());
        String state = upper(filters.state());
        if (!state.isEmpty() && !Set.of("DRAFT", "PUBLISHED", "RETIRED").contains(state)) {
            throw invalid("QUALITY_SUMMARY_STATE_INVALID", "汇总报告状态无效");
        }
        validateRange(filters.reportFrom(), filters.reportTo(), "报告日期");
        int limit = Math.max(1, Math.min(filters.limit(), 100));
        PageCursor cursor = PageCursor.decode(filters.cursor());
        String conditions = conditions();
        var statement = jdbc.sql(select() + " WHERE " + projectScope() + conditions + """
                AND (CAST(:cursorTime AS timestamptz) IS NULL OR (document.updated_at,document.id)<
                    (CAST(:cursorTime AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY document.updated_at DESC,document.id DESC LIMIT :fetchLimit
                """);
        bind(statement, user, filters, type, state, cursor).param("fetchLimit", limit + 1);
        List<View> fetched = statement.query(this::map).list();
        boolean hasMore = fetched.size() > limit;
        List<View> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*) " + from() + " WHERE " + projectScope() + conditions);
        bind(count, user, filters, type, state, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    public View require(UUID id) {
        AuthenticatedUser user = actor.require();
        var statement = jdbc.sql(select() + " WHERE document.id=:id AND " + projectScope()).param("id", id);
        bindActor(statement, user);
        View row = statement.query(this::map).optional().orElseThrow(() ->
                ApiException.notFound("QUALITY_SUMMARY_NOT_FOUND", "汇总报告不存在或不在当前数据范围内"));
        return withAttachments(row);
    }

    public Options options(String query) {
        AuthenticatedUser user = actor.require();
        String q = clean(query, 100, "查询内容");
        var projects = jdbc.sql("""
                SELECT project.id,project.number,project.name,project.customer_id,customer.name customer_name,
                  COALESCE(project.section_number,'') section_name
                FROM project JOIN customer ON customer.id=project.customer_id
                WHERE %s AND project.status IN ('DRAFT','ACTIVE','SUSPENDED','COMPLETED')
                  AND (:q='' OR project.number ILIKE :pattern OR project.name ILIKE :pattern
                    OR customer.name ILIKE :pattern)
                ORDER BY project.updated_at DESC,project.id DESC LIMIT 100
                """.formatted(projectScope())).param("q", q).param("pattern", pattern(q));
        bindActor(projects, user);
        List<ProjectOption> projectOptions = projects.query((rs, row) -> new ProjectOption(
                rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("section_name"))).list();
        List<CustomerOption> customers = jdbc.sql("""
                SELECT id,code,name FROM customer WHERE organization_id=:organization AND active
                  AND (:q='' OR code ILIKE :pattern OR name ILIKE :pattern)
                ORDER BY updated_at DESC,id DESC LIMIT 100
                """).param("organization", user.organizationId()).param("q", q).param("pattern", pattern(q))
                .query((rs, row) -> new CustomerOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name"))).list();
        return new Options(projectOptions, customers);
    }

    @Transactional
    public View create(Write body) {
        requirePermission("MODULE_WRITE");
        Validated validated = validate(body, false);
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        try {
            jdbc.sql("""
                    INSERT INTO quality_summary_document(id,organization_id,document_type,number,project_id,
                      customer_id,project_number_snapshot,project_name_snapshot,customer_name_snapshot,
                      section_name,period_start,period_end,report_on,conclusion,created_by)
                    VALUES (:id,:organization,:type,:number,:project,:customer,:projectNumber,:projectName,
                      :customerName,:section,:periodStart,:periodEnd,:reportOn,:conclusion,:actor)
                    """).param("id", id).param("organization", user.organizationId())
                    .param("type", validated.type()).param("number", validated.number())
                    .param("project", body.projectId()).param("customer", body.customerId())
                    .param("projectNumber", validated.references().projectNumber())
                    .param("projectName", validated.references().projectName())
                    .param("customerName", validated.references().customerName())
                    .param("section", validated.section()).param("periodStart", validated.periodStart())
                    .param("periodEnd", validated.periodEnd()).param("reportOn", validated.reportOn())
                    .param("conclusion", validated.conclusion()).param("actor", user.id()).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_SUMMARY_NUMBER_CONFLICT",
                    "同一类型下汇总报告编号已经存在");
        }
        replaceChildren(id, validated.contents(), validated.files());
        View created = require(id);
        audit.record("QUALITY_SUMMARY_CREATED", "QUALITY_SUMMARY_DOCUMENT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Write body) {
        requirePermission("MODULE_WRITE");
        View before = require(id);
        if (!before.state().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "QUALITY_SUMMARY_IMMUTABLE", "已发布或已停用汇总报告不能原地修改");
        requireVersion(body.expectedVersion(), before.version());
        Validated validated = validate(body, true);
        int changed;
        try {
            changed = jdbc.sql("""
                    UPDATE quality_summary_document SET document_type=:type,number=:number,project_id=:project,
                      customer_id=:customer,project_number_snapshot=:projectNumber,
                      project_name_snapshot=:projectName,customer_name_snapshot=:customerName,
                      section_name=:section,period_start=:periodStart,period_end=:periodEnd,report_on=:reportOn,
                      conclusion=:conclusion,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND version=:version
                    """).param("type", validated.type()).param("number", validated.number())
                    .param("project", body.projectId()).param("customer", body.customerId())
                    .param("projectNumber", validated.references().projectNumber())
                    .param("projectName", validated.references().projectName())
                    .param("customerName", validated.references().customerName())
                    .param("section", validated.section()).param("periodStart", validated.periodStart())
                    .param("periodEnd", validated.periodEnd()).param("reportOn", validated.reportOn())
                    .param("conclusion", validated.conclusion()).param("id", id)
                    .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_SUMMARY_NUMBER_CONFLICT",
                    "同一类型下汇总报告编号已经存在");
        }
        if (changed == 0) throw conflict(id);
        replaceChildren(id, validated.contents(), validated.files());
        View after = require(id);
        audit.record("QUALITY_SUMMARY_UPDATED", "QUALITY_SUMMARY_DOCUMENT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, String requestedAction, long expectedVersion,
                                       String reasonCode, String comment) {
        View before = require(id);
        requireVersion(expectedVersion, before.version());
        String action = upper(requestedAction);
        if (action.equals("PUBLISH")) {
            requirePermission("REPORT_REVIEW");
            if (!before.state().equals("DRAFT")) throw invalidAction(action);
            if (before.contentCodes().isEmpty()) throw ApiException.blocked("QUALITY_SUMMARY_CONTENT_REQUIRED",
                    "发布前必须至少选择一类检测内容", before.version(), List.of("检测内容为空"));
            int changed = jdbc.sql("""
                    UPDATE quality_summary_document SET state='PUBLISHED',published_by=:actor,published_at=now(),
                      version=version+1,updated_at=now() WHERE id=:id AND state='DRAFT' AND version=:version
                    """).param("actor", actor.require().id()).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else if (action.equals("RETIRE") || action.equals("DISCARD")) {
            requirePermission(action.equals("RETIRE") ? "REPORT_REVIEW" : "MODULE_WRITE");
            if (action.equals("RETIRE") && !before.state().equals("PUBLISHED")) throw invalidAction(action);
            if (action.equals("DISCARD") && !before.state().equals("DRAFT")) throw invalidAction(action);
            String reason = clean(comment, 1000, "停用原因");
            if (reason.isEmpty()) throw invalid("QUALITY_SUMMARY_RETIRE_REASON_REQUIRED", "停用或作废必须填写原因");
            int changed = jdbc.sql("""
                    UPDATE quality_summary_document SET state='RETIRED',retired_by=:actor,retired_at=now(),
                      retirement_reason=:reason,version=version+1,updated_at=now()
                    WHERE id=:id AND state=:state AND version=:version
                    """).param("actor", actor.require().id()).param("reason", reason).param("id", id)
                    .param("state", before.state()).param("version", before.version()).update();
            if (changed == 0) throw conflict(id);
        } else throw invalidAction(action);
        View after = require(id);
        audit.record("QUALITY_SUMMARY_" + action, "QUALITY_SUMMARY_DOCUMENT", id, before, after,
                reasonCode, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private Validated validate(Write body, boolean update) {
        if (body == null) throw invalid("QUALITY_SUMMARY_REQUEST_REQUIRED", "汇总报告不能为空");
        String type = normalizeType(body.documentType());
        String number = required(body.number(), 80, "编号");
        if (body.projectId() == null || body.customerId() == null) {
            throw invalid("QUALITY_SUMMARY_PARTY_REQUIRED", "工程和委托单位不能为空");
        }
        access.requireProject(body.projectId());
        References references = references(body.projectId(), body.customerId());
        String section = required(body.sectionName(), 240, "标段");
        LocalDate start = body.periodStart();
        LocalDate end = body.periodEnd();
        LocalDate reportOn = body.reportOn();
        if (type.equals("STAGE")) {
            if (start == null || end == null) throw invalid("QUALITY_SUMMARY_PERIOD_REQUIRED", "阶段汇总必须填写开始和结束日期");
            validateRange(start, end, "阶段检测时段");
            reportOn = end;
        } else {
            if (reportOn == null) throw invalid("QUALITY_SUMMARY_REPORT_DATE_REQUIRED", "综合报告必须填写报告时间");
            start = null;
            end = null;
        }
        List<String> contents = normalizedContents(body.contentCodes());
        if (contents.isEmpty()) throw invalid("QUALITY_SUMMARY_CONTENT_REQUIRED", "至少选择一类检测内容");
        List<UUID> files = body.attachmentFileIds() == null ? List.of()
                : body.attachmentFileIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (files.size() > 20) throw invalid("QUALITY_SUMMARY_ATTACHMENTS_LIMIT", "单份汇总报告最多 20 个附件");
        validateFiles(files);
        if (update && body.expectedVersion() == null) throw invalid("EXPECTED_VERSION_REQUIRED", "修改时必须提供当前版本");
        return new Validated(type, number, references, section, start, end, reportOn,
                contents, clean(body.conclusion(), 5000, "检测结论"), files);
    }

    private References references(UUID projectId, UUID customerId) {
        return jdbc.sql("""
                SELECT project.number project_number,project.name project_name,customer.name customer_name
                FROM project JOIN customer ON customer.id=:customer
                WHERE project.id=:project AND project.organization_id=:organization
                  AND customer.organization_id=:organization AND customer.active
                """).param("project", projectId).param("customer", customerId)
                .param("organization", actor.require().organizationId())
                .query((rs, row) -> new References(rs.getString("project_number"),
                        rs.getString("project_name"), rs.getString("customer_name"))).optional()
                .orElseThrow(() -> invalid("QUALITY_SUMMARY_REFERENCE_INVALID", "工程或委托单位不存在、已停用或不属于当前机构"));
    }

    private void replaceChildren(UUID id, List<String> contents, List<UUID> files) {
        jdbc.sql("DELETE FROM quality_summary_content WHERE document_id=:id").param("id", id).update();
        for (int index = 0; index < contents.size(); index++) {
            jdbc.sql("INSERT INTO quality_summary_content(document_id,content_code,sort_order) VALUES (:id,:code,:sort)")
                    .param("id", id).param("code", contents.get(index)).param("sort", index).update();
        }
        jdbc.sql("DELETE FROM quality_summary_attachment WHERE document_id=:id").param("id", id).update();
        for (int index = 0; index < files.size(); index++) {
            jdbc.sql("""
                    INSERT INTO quality_summary_attachment(document_id,file_id,sort_order,attached_by)
                    VALUES (:id,:file,:sort,:actor)
                    """).param("id", id).param("file", files.get(index)).param("sort", index)
                    .param("actor", actor.require().id()).update();
        }
    }

    private void validateFiles(List<UUID> files) {
        if (files.isEmpty()) return;
        long valid = jdbc.sql("""
                SELECT count(*) FROM stored_file file JOIN app_user uploader ON uploader.id=file.uploaded_by
                WHERE file.id IN (:files) AND uploader.organization_id=:organization
                  AND file.media_type IN (:mediaTypes)
                """).param("files", files).param("organization", actor.require().organizationId())
                .param("mediaTypes", ATTACHMENT_TYPES).query(Long.class).single();
        if (valid != files.size()) throw invalid("QUALITY_SUMMARY_ATTACHMENT_INVALID",
                "附件不存在、格式不受支持或不在当前机构范围内");
    }

    private View withAttachments(View row) {
        List<Attachment> attachments = jdbc.sql("""
                SELECT file.id,file.original_name,file.media_type,file.size_bytes
                FROM quality_summary_attachment attachment JOIN stored_file file ON file.id=attachment.file_id
                WHERE attachment.document_id=:id ORDER BY attachment.sort_order,file.id
                """).param("id", row.id()).query((rs, index) -> new Attachment(rs.getObject("id", UUID.class),
                        rs.getString("original_name"), rs.getString("media_type"), rs.getLong("size_bytes"))).list();
        return new View(row.id(), row.documentType(), row.number(), row.projectId(), row.customerId(),
                row.projectNumber(), row.projectName(), row.customerName(), row.sectionName(), row.periodStart(),
                row.periodEnd(), row.reportOn(), row.contentCodes(), row.conclusion(), attachments, row.state(),
                row.version(), row.createdBy(), row.creatorName(), row.publishedBy(), row.publisherName(),
                row.publishedAt(), row.updatedAt(), row.allowedActions());
    }

    private String select() {
        return """
                SELECT document.id,document.document_type,document.number,document.project_id,document.customer_id,
                  document.project_number_snapshot,document.project_name_snapshot,document.customer_name_snapshot,
                  document.section_name,document.period_start,document.period_end,document.report_on,
                  content.content_codes,document.conclusion,document.state,document.version,document.created_by,
                  creator.display_name creator_name,document.published_by,publisher.display_name publisher_name,
                  document.published_at,document.updated_at
                """ + from();
    }

    private String from() {
        return """
                FROM quality_summary_document document JOIN project ON project.id=document.project_id
                JOIN app_user creator ON creator.id=document.created_by
                LEFT JOIN app_user publisher ON publisher.id=document.published_by
                LEFT JOIN LATERAL (
                  SELECT string_agg(item.content_code,',' ORDER BY item.sort_order,item.content_code) content_codes
                  FROM quality_summary_content item WHERE item.document_id=document.id
                ) content ON true
                """;
    }

    private String conditions() {
        return """
                  AND document.document_type=:type
                  AND (:number='' OR document.number ILIKE :numberPattern)
                  AND (:projectNumber='' OR document.project_number_snapshot ILIKE :projectNumberPattern)
                  AND (:projectName='' OR document.project_name_snapshot ILIKE :projectNamePattern)
                  AND (:customerName='' OR document.customer_name_snapshot ILIKE :customerPattern)
                  AND (:contentCode='' OR EXISTS(SELECT 1 FROM quality_summary_content contentFilter
                    WHERE contentFilter.document_id=document.id AND contentFilter.content_code=:contentCode))
                  AND (:state='' OR document.state=:state)
                  AND (CAST(:reportFrom AS date) IS NULL OR document.report_on>=CAST(:reportFrom AS date))
                  AND (CAST(:reportTo AS date) IS NULL OR document.report_on<=CAST(:reportTo AS date))
                """;
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, AuthenticatedUser user,
                                          Filters filters, String type, String state, PageCursor cursor) {
        bindActor(statement, user);
        String content = upper(filters.contentCode());
        if (!content.isEmpty() && !CONTENT_CODES.contains(content)) {
            throw invalid("QUALITY_SUMMARY_CONTENT_INVALID", "检测内容分类无效");
        }
        statement.param("type", type).param("number", clean(filters.number(), 80, "编号"))
                .param("numberPattern", pattern(filters.number()))
                .param("projectNumber", clean(filters.projectNumber(), 64, "工程编号"))
                .param("projectNumberPattern", pattern(filters.projectNumber()))
                .param("projectName", clean(filters.projectName(), 240, "工程名称"))
                .param("projectNamePattern", pattern(filters.projectName()))
                .param("customerName", clean(filters.customerName(), 240, "委托方"))
                .param("customerPattern", pattern(filters.customerName()))
                .param("contentCode", content).param("state", state)
                .param("reportFrom", filters.reportFrom()).param("reportTo", filters.reportTo())
                .param("cursorTime", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
        return statement;
    }

    private String projectScope() {
        return """
                project.organization_id=:organization AND (
                  :scope IN ('ALL','ORGANIZATION')
                  OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member
                    WHERE member.project_id=project.id AND member.user_id=:actor))
                  OR (:scope='DEPARTMENT' AND (
                    EXISTS(SELECT 1 FROM app_user creatorScope WHERE creatorScope.id=project.created_by
                      AND creatorScope.department_id=:department)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN app_user commissionCreator
                      ON commissionCreator.id=commissionScope.created_by
                      WHERE commissionScope.project_id=project.id AND commissionCreator.department_id=:department)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN test_task taskScope
                      ON taskScope.commission_id=commissionScope.id JOIN person personScope ON personScope.id=taskScope.assigned_to
                      WHERE commissionScope.project_id=project.id AND personScope.department_id=:department)))
                  OR (:scope='SELF' AND (project.created_by=:actor
                    OR EXISTS(SELECT 1 FROM project_member member WHERE member.project_id=project.id AND member.user_id=:actor)
                    OR EXISTS(SELECT 1 FROM commission commissionScope WHERE commissionScope.project_id=project.id
                      AND commissionScope.created_by=:actor)
                    OR EXISTS(SELECT 1 FROM commission commissionScope JOIN test_task taskScope
                      ON taskScope.commission_id=commissionScope.id WHERE commissionScope.project_id=project.id
                      AND taskScope.assigned_to=:person)))
                )
                """;
    }

    private void bindActor(JdbcClient.StatementSpec statement, AuthenticatedUser user) {
        statement.param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? NO_ID : user.departmentId())
                .param("actor", user.id()).param("person", user.personId() == null ? NO_ID : user.personId());
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String values = rs.getString("content_codes");
        List<String> contents = values == null || values.isBlank() ? List.of() : Arrays.asList(values.split(","));
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getString("document_type"), rs.getString("number"),
                rs.getObject("project_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("project_number_snapshot"), rs.getString("project_name_snapshot"),
                rs.getString("customer_name_snapshot"), rs.getString("section_name"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getObject("report_on", LocalDate.class), List.copyOf(contents), rs.getString("conclusion"),
                List.of(), state, rs.getLong("version"), rs.getObject("created_by", UUID.class),
                rs.getString("creator_name"), rs.getObject("published_by", UUID.class),
                rs.getString("publisher_name"), rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), actions(state));
    }

    private List<String> normalizedContents(List<String> supplied) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (supplied != null) supplied.forEach(value -> normalized.add(upper(value)));
        if (normalized.contains("") || !CONTENT_CODES.containsAll(normalized)) {
            throw invalid("QUALITY_SUMMARY_CONTENT_INVALID", "检测内容包含未受控分类");
        }
        return List.copyOf(normalized);
    }

    private List<String> actions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("UPDATE", "PUBLISH", "DISCARD");
            case "PUBLISHED" -> List.of("RETIRE");
            default -> List.of();
        };
    }

    private String normalizeType(String value) {
        String type = upper(value);
        if (!TYPES.contains(type)) throw invalid("QUALITY_SUMMARY_TYPE_INVALID", "汇总报告类型无效");
        return type;
    }

    private void requireVersion(Long expected, long current) {
        if (expected == null) throw invalid("EXPECTED_VERSION_REQUIRED", "必须提供当前版本");
        if (expected != current) throw ApiException.conflict("VERSION_CONFLICT", "汇总报告已被其他用户更新", current);
    }

    private ApiException conflict(UUID id) {
        return ApiException.conflict("VERSION_CONFLICT", "汇总报告已被其他用户更新", require(id).version());
    }

    private ApiException invalidAction(String action) {
        return new ApiException(HttpStatus.CONFLICT, "QUALITY_SUMMARY_ACTION_INVALID", "当前状态不允许执行 " + action);
    }

    private void requirePermission(String permission) {
        AuthenticatedUser user = actor.require();
        if (!user.permissions().contains(permission) && !user.permissions().contains("SYSTEM_ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号无权执行该汇总报告动作");
        }
    }

    private void validateRange(LocalDate from, LocalDate to, String label) {
        if (from != null && to != null && to.isBefore(from)) throw invalid("DATE_RANGE_INVALID", label + "结束日期不得早于开始日期");
    }

    private String required(String value, int max, String label) {
        String result = clean(value, max, label);
        if (result.isEmpty()) throw invalid("QUALITY_SUMMARY_FIELD_REQUIRED", label + "不能为空");
        return result;
    }

    private String clean(String value, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw invalid("QUALITY_SUMMARY_FIELD_TOO_LONG", label + "最多 " + max + " 个字符");
        return result;
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String pattern(String value) { return "%" + (value == null ? "" : value.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

    private record References(String projectNumber, String projectName, String customerName) {}
    private record Validated(String type, String number, References references, String section,
                             LocalDate periodStart, LocalDate periodEnd, LocalDate reportOn,
                             List<String> contents, String conclusion, List<UUID> files) {}
}
