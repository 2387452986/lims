package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SystemExperienceService {
    private static final Set<String> BRIEFING_TYPES = Set.of(
            "PROJECT_SAFETY", "EQUIPMENT_OPERATION", "SITE_SAFETY", "OTHER");
    private static final Map<String, String> STAGE_LABELS = Map.of(
            "SAMPLE_INBOUND", "样品入库",
            "SAMPLE_OUTBOUND", "样品出库",
            "REPORT_EDIT", "报告编制",
            "REPORT_REVIEW", "报告审核",
            "REPORT_ISSUE", "报告签发",
            "REPORT_SEAL", "报告签章",
            "REPORT_ARCHIVE", "报告归档",
            "NONCONFORMANCE_NOTICE", "不合格通知");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final DataScopeService access;
    private final AuditService audit;

    public SystemExperienceService(JdbcClient jdbc, CurrentActor actor, DataScopeService access, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.access = access;
        this.audit = audit;
    }

    public record ThemeWrite(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String remark,
            @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String brand900,
            @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String brand600,
            @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String brand100,
            @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String surfaceBackground,
            @NotBlank @Pattern(regexp = "COMPACT|COMFORTABLE") String density,
            @Min(4) @Max(16) int radiusPx,
            Long expectedVersion) {}
    public record ThemeView(UUID id, String code, String name, String remark, String brand900, String brand600,
                            String brand100, String surfaceBackground, String density, int radiusPx,
                            int revision, UUID supersedesId, String state, long version,
                            OffsetDateTime activatedAt, OffsetDateTime updatedAt, List<String> allowedActions) {}

    public record DetectionProjectOption(UUID id, String code, String label, int revision) {}
    public record BriefingWrite(
            @NotNull UUID detectionProjectRevisionId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String code,
            @NotBlank @Size(max = 240) String title,
            @NotBlank String briefingType,
            @NotBlank @Size(max = 10000) String content,
            @Min(5) @Max(1800) int readSeconds,
            Long expectedVersion) {}
    public record BriefingView(UUID id, UUID detectionProjectRevisionId, String projectCode, String projectName,
                               int projectRevision, String code, String title, String briefingType, String content, int readSeconds,
                               int revision, UUID supersedesId, String state, long version,
                               OffsetDateTime activatedAt, OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record TaskBriefing(UUID id, String code, String title, String briefingType, String content,
                               int readSeconds, long ruleVersion, OffsetDateTime startedAt,
                               OffsetDateTime eligibleAt, OffsetDateTime acknowledgedAt, long readVersion,
                               long remainingSeconds) {}

    public record TransitionTimeWrite(@NotNull Boolean enabled, @Min(1) @Max(3650) int allowedDays,
                                      @NotNull Long expectedVersion) {}
    public record TransitionTimeView(UUID id, String stageCode, String stageLabel, boolean enabled,
                                     int allowedDays, long version, OffsetDateTime updatedAt) {}
    public record TransitionWarning(String stageCode, String stageLabel, String resourceType, UUID resourceId,
                                    String businessNumber, String summary, OffsetDateTime deadline,
                                    String status, int overdueDays) {}

    public List<ThemeView> themes() {
        return jdbc.sql(themeSelect() + " WHERE theme.organization_id=:organization ORDER BY theme.updated_at DESC,theme.id DESC")
                .param("organization", actor.require().organizationId()).query(this::mapTheme).list();
    }

    public ThemeView activeTheme() {
        return jdbc.sql(themeSelect() + " WHERE theme.organization_id=:organization AND theme.state='ACTIVE'")
                .param("organization", actor.require().organizationId()).query(this::mapTheme).optional()
                .orElseThrow(() -> ApiException.notFound("ACTIVE_THEME_NOT_FOUND", "当前机构尚未配置已应用桌面风格"));
    }

    @Transactional
    public ThemeView createTheme(ThemeWrite body) {
        validateTheme(body);
        AuthenticatedUser user = actor.require();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO desktop_theme(id,organization_id,code,name,remark,brand_900,brand_600,brand_100,
                      surface_background,density,radius_px,created_by)
                    VALUES (:id,:organization,:code,:name,:remark,:brand900,:brand600,:brand100,
                      :surface,:density,:radius,:actor)
                    """).param("id", id).param("organization", user.organizationId())
                    .param("code", normalizedCode(body.code())).param("name", body.name().trim())
                    .param("remark", clean(body.remark())).param("brand900", upper(body.brand900()))
                    .param("brand600", upper(body.brand600())).param("brand100", upper(body.brand100()))
                    .param("surface", upper(body.surfaceBackground())).param("density", body.density())
                    .param("radius", body.radiusPx()).param("actor", user.id()).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "THEME_CODE_CONFLICT", "同一机构下桌面风格代码与版本不能重复");
        }
        ThemeView created = requireTheme(id);
        audit.record("DESKTOP_THEME_CREATED", "DESKTOP_THEME", id, null, created, null, body.remark());
        return created;
    }

    @Transactional
    public ThemeView updateTheme(UUID id, ThemeWrite body) {
        validateTheme(body);
        ThemeView before = requireTheme(id);
        requireDraft(before.state(), "已应用或已停用桌面风格不能原地修改，请创建修订");
        requireExpected(before.version(), body.expectedVersion(), "桌面风格已被其他用户修改");
        int changed = jdbc.sql("""
                UPDATE desktop_theme SET code=:code,name=:name,remark=:remark,brand_900=:brand900,
                  brand_600=:brand600,brand_100=:brand100,surface_background=:surface,density=:density,
                  radius_px=:radius,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version AND state='DRAFT'
                """).param("code", normalizedCode(body.code())).param("name", body.name().trim())
                .param("remark", clean(body.remark())).param("brand900", upper(body.brand900()))
                .param("brand600", upper(body.brand600())).param("brand100", upper(body.brand100()))
                .param("surface", upper(body.surfaceBackground())).param("density", body.density())
                .param("radius", body.radiusPx()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw themeConflict(id);
        ThemeView after = requireTheme(id);
        audit.record("DESKTOP_THEME_UPDATED", "DESKTOP_THEME", id, before, after, null, body.remark());
        return after;
    }

    @Transactional
    public ThemeView reviseTheme(UUID id, long expectedVersion) {
        ThemeView before = lockTheme(id);
        requireExpected(before.version(), expectedVersion, "桌面风格已被其他用户修改");
        if (before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "THEME_ALREADY_DRAFT", "草稿可直接修改，无需创建修订");
        }
        int revision = jdbc.sql("""
                SELECT COALESCE(max(revision),0)+1 FROM desktop_theme
                WHERE organization_id=:organization AND code=:code
                """).param("organization", actor.require().organizationId()).param("code", before.code())
                .query(Integer.class).single();
        UUID revisedId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO desktop_theme(id,organization_id,code,name,remark,brand_900,brand_600,brand_100,
                  surface_background,density,radius_px,revision,supersedes_id,state,created_by)
                SELECT :newId,organization_id,code,name,remark,brand_900,brand_600,brand_100,
                  surface_background,density,radius_px,:revision,id,'DRAFT',:actor
                FROM desktop_theme WHERE id=:id AND organization_id=:organization
                """).param("newId", revisedId).param("revision", revision).param("actor", actor.require().id())
                .param("id", id).param("organization", actor.require().organizationId()).update();
        ThemeView revised = requireTheme(revisedId);
        audit.record("DESKTOP_THEME_REVISED", "DESKTOP_THEME", revisedId, before, revised,
                "CONTROLLED_REVISION", "创建桌面风格受控修订");
        return revised;
    }

    @Transactional
    public TransitionResult transitionTheme(UUID id, String requestedAction, long expectedVersion,
                                            String reasonCode, String comment) {
        ThemeView before = requireTheme(id);
        requireExpected(before.version(), expectedVersion, "桌面风格已被其他用户修改");
        String action = action(requestedAction);
        if (action.equals("ACTIVATE")) {
            requireDraft(before.state(), "只有草稿桌面风格可以应用");
            List<ThemeView> active = jdbc.sql(themeSelect() + " WHERE theme.organization_id=:organization AND theme.state='ACTIVE' FOR UPDATE")
                    .param("organization", actor.require().organizationId()).query(this::mapTheme).list();
            jdbc.sql("""
                    UPDATE desktop_theme SET state='RETIRED',version=version+1,updated_at=now()
                    WHERE organization_id=:organization AND state='ACTIVE' AND id<>:id
                    """).param("organization", actor.require().organizationId()).param("id", id).update();
            for (ThemeView retired : active) {
                ThemeView afterRetire = requireTheme(retired.id());
                audit.record("DESKTOP_THEME_AUTO_RETIRED", "DESKTOP_THEME", retired.id(), retired, afterRetire,
                        reasonCode, "由新桌面风格替换");
            }
            int changed = jdbc.sql("""
                    UPDATE desktop_theme SET state='ACTIVE',activated_by=:actor,activated_at=now(),
                      version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version AND state='DRAFT'
                    """).param("actor", actor.require().id()).param("id", id)
                    .param("organization", actor.require().organizationId()).param("version", before.version()).update();
            if (changed == 0) throw themeConflict(id);
        } else if (action.equals("DISCARD")) {
            requireDraft(before.state(), "只有草稿桌面风格可以作废");
            int changed = jdbc.sql("""
                    UPDATE desktop_theme SET state='RETIRED',version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version AND state='DRAFT'
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("version", before.version()).update();
            if (changed == 0) throw themeConflict(id);
        } else throw invalidAction("桌面风格", action);
        ThemeView after = requireTheme(id);
        audit.record("DESKTOP_THEME_" + action, "DESKTOP_THEME", id, before, after, reasonCode, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public List<DetectionProjectOption> detectionProjects(String query) {
        String q = clean(query);
        return jdbc.sql("""
                SELECT revision.id,
                  COALESCE(NULLIF(node.symbol,''),NULLIF(node.source_record_id,''),node.source_key) code,
                  node.name,revision.revision_number
                FROM detection_project_revision revision
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                WHERE revision.status='PUBLISHED' AND node.status='ACTIVE'
                  AND (:q='' OR node.symbol ILIKE :pattern OR node.source_record_id ILIKE :pattern
                    OR node.source_key ILIKE :pattern OR node.name ILIKE :pattern)
                ORDER BY node.name,revision.revision_number DESC LIMIT 100
                """).param("q", q).param("pattern", "%" + q + "%")
                .query((rs, row) -> new DetectionProjectOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getInt("revision_number"))).list();
    }

    public CursorPage<BriefingView> briefings(String query, String state, String cursorValue, int requestedLimit) {
        String q = clean(query);
        String normalizedState = clean(state).toUpperCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        String conditions = """
                 WHERE rule.organization_id=:organization
                   AND (:q='' OR rule.code ILIKE :pattern OR rule.title ILIKE :pattern OR node.name ILIKE :pattern)
                   AND (:state='' OR rule.state=:state)
                """;
        var statement = jdbc.sql(briefingSelect() + conditions + """
                  AND (CAST(:cursorAt AS timestamptz) IS NULL OR (rule.updated_at,rule.id)<
                    (CAST(:cursorAt AS timestamptz),CAST(:cursorId AS uuid)))
                ORDER BY rule.updated_at DESC,rule.id DESC LIMIT :fetchLimit
                """);
        bindBriefing(statement, q, normalizedState, cursor).param("fetchLimit", limit + 1);
        List<BriefingView> fetched = statement.query(this::mapBriefing).list();
        boolean hasMore = fetched.size() > limit;
        List<BriefingView> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        var count = jdbc.sql("SELECT count(*)" + briefingFrom() + conditions);
        bindBriefing(count, q, normalizedState, null);
        long total = count.query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(items, next, hasMore, total);
    }

    @Transactional
    public BriefingView createBriefing(BriefingWrite body) {
        validateBriefing(body);
        requirePublishedProject(body.detectionProjectRevisionId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO safety_briefing_rule(id,organization_id,detection_project_revision_id,code,title,
                      briefing_type,content,read_seconds,created_by)
                    VALUES (:id,:organization,:project,:code,:title,:type,:content,:seconds,:actor)
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("project", body.detectionProjectRevisionId()).param("code", normalizedCode(body.code()))
                    .param("title", body.title().trim()).param("type", body.briefingType().trim().toUpperCase(Locale.ROOT))
                    .param("content", body.content().trim()).param("seconds", body.readSeconds())
                    .param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "BRIEFING_CODE_CONFLICT", "同一机构下安全交底代码与版本不能重复");
        }
        BriefingView created = requireBriefing(id);
        audit.record("SAFETY_BRIEFING_CREATED", "SAFETY_BRIEFING", id, null, created, null, null);
        return created;
    }

    @Transactional
    public BriefingView updateBriefing(UUID id, BriefingWrite body) {
        validateBriefing(body);
        requirePublishedProject(body.detectionProjectRevisionId());
        BriefingView before = requireBriefing(id);
        requireDraft(before.state(), "已启用或已停用安全交底不能原地修改，请创建修订");
        requireExpected(before.version(), body.expectedVersion(), "安全交底已被其他用户修改");
        int changed = jdbc.sql("""
                UPDATE safety_briefing_rule SET detection_project_revision_id=:project,code=:code,title=:title,
                  briefing_type=:type,content=:content,read_seconds=:seconds,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version AND state='DRAFT'
                """).param("project", body.detectionProjectRevisionId()).param("code", normalizedCode(body.code()))
                .param("title", body.title().trim()).param("type", body.briefingType().trim().toUpperCase(Locale.ROOT))
                .param("content", body.content().trim()).param("seconds", body.readSeconds()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw briefingConflict(id);
        BriefingView after = requireBriefing(id);
        audit.record("SAFETY_BRIEFING_UPDATED", "SAFETY_BRIEFING", id, before, after, null, null);
        return after;
    }

    @Transactional
    public BriefingView reviseBriefing(UUID id, long expectedVersion) {
        BriefingView before = lockBriefing(id);
        requireExpected(before.version(), expectedVersion, "安全交底已被其他用户修改");
        if (before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "BRIEFING_ALREADY_DRAFT", "草稿可直接修改，无需创建修订");
        }
        int revision = jdbc.sql("""
                SELECT COALESCE(max(revision),0)+1 FROM safety_briefing_rule
                WHERE organization_id=:organization AND code=:code
                """).param("organization", actor.require().organizationId()).param("code", before.code())
                .query(Integer.class).single();
        UUID revisedId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO safety_briefing_rule(id,organization_id,detection_project_revision_id,code,title,
                  briefing_type,content,read_seconds,revision,supersedes_id,state,created_by)
                SELECT :newId,organization_id,detection_project_revision_id,code,title,briefing_type,content,
                  read_seconds,:revision,id,'DRAFT',:actor FROM safety_briefing_rule
                WHERE id=:id AND organization_id=:organization
                """).param("newId", revisedId).param("revision", revision).param("actor", actor.require().id())
                .param("id", id).param("organization", actor.require().organizationId()).update();
        BriefingView revised = requireBriefing(revisedId);
        audit.record("SAFETY_BRIEFING_REVISED", "SAFETY_BRIEFING", revisedId, before, revised,
                "CONTROLLED_REVISION", "创建安全交底受控修订");
        return revised;
    }

    @Transactional
    public TransitionResult transitionBriefing(UUID id, String requestedAction, long expectedVersion,
                                               String reasonCode, String comment) {
        BriefingView before = requireBriefing(id);
        requireExpected(before.version(), expectedVersion, "安全交底已被其他用户修改");
        String action = action(requestedAction);
        if (action.equals("ACTIVATE")) {
            requireDraft(before.state(), "只有草稿安全交底可以启用");
            List<BriefingView> active = jdbc.sql(briefingSelect() + """
                     WHERE rule.organization_id=:organization AND rule.state='ACTIVE'
                       AND rule.detection_project_revision_id=:project AND rule.briefing_type=:type FOR UPDATE
                    """).param("organization", actor.require().organizationId())
                    .param("project", before.detectionProjectRevisionId()).param("type", before.briefingType())
                    .query(this::mapBriefing).list();
            jdbc.sql("""
                    UPDATE safety_briefing_rule SET state='RETIRED',version=version+1,updated_at=now()
                    WHERE organization_id=:organization AND state='ACTIVE'
                      AND detection_project_revision_id=:project AND briefing_type=:type AND id<>:id
                    """).param("organization", actor.require().organizationId())
                    .param("project", before.detectionProjectRevisionId()).param("type", before.briefingType())
                    .param("id", id).update();
            for (BriefingView retired : active) {
                BriefingView afterRetire = requireBriefing(retired.id());
                audit.record("SAFETY_BRIEFING_AUTO_RETIRED", "SAFETY_BRIEFING", retired.id(), retired, afterRetire,
                        reasonCode, "由新安全交底版本替换");
            }
            int changed = jdbc.sql("""
                    UPDATE safety_briefing_rule SET state='ACTIVE',activated_by=:actor,activated_at=now(),
                      version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version AND state='DRAFT'
                    """).param("actor", actor.require().id()).param("id", id)
                    .param("organization", actor.require().organizationId()).param("version", before.version()).update();
            if (changed == 0) throw briefingConflict(id);
        } else if (action.equals("DISABLE") || action.equals("DISCARD")) {
            if (action.equals("DISABLE") && !before.state().equals("ACTIVE")) throw invalidAction("安全交底", action);
            if (action.equals("DISCARD")) requireDraft(before.state(), "只有草稿安全交底可以作废");
            int changed = jdbc.sql("""
                    UPDATE safety_briefing_rule SET state='RETIRED',version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("version", before.version()).update();
            if (changed == 0) throw briefingConflict(id);
        } else throw invalidAction("安全交底", action);
        BriefingView after = requireBriefing(id);
        audit.record("SAFETY_BRIEFING_" + action, "SAFETY_BRIEFING", id, before, after, reasonCode, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public List<TaskBriefing> taskBriefings(UUID taskId) {
        TaskOwner owner = requireTaskOwner(taskId, false);
        return jdbc.sql("""
                SELECT rule.id,rule.code,rule.title,rule.briefing_type,rule.content,rule.read_seconds,
                  rule.version rule_version,reading.started_at,
                  reading.started_at + make_interval(secs=>rule.read_seconds) eligible_at,
                  reading.acknowledged_at,COALESCE(reading.version,0) read_version
                FROM test_task task JOIN test_parameter parameter ON parameter.id=task.parameter_id
                JOIN safety_briefing_rule rule ON rule.detection_project_revision_id=parameter.project_revision_id
                  AND rule.organization_id=:organization AND rule.state='ACTIVE'
                LEFT JOIN task_safety_briefing_read reading ON reading.task_id=task.id AND reading.rule_id=rule.id
                  AND reading.rule_version=rule.version AND reading.reader_id=:actor
                WHERE task.id=:task ORDER BY rule.briefing_type,rule.title
                """).param("organization", owner.organizationId()).param("actor", actor.require().id())
                .param("task", taskId).query((rs, row) -> {
                    OffsetDateTime eligibleAt = rs.getObject("eligible_at", OffsetDateTime.class);
                    OffsetDateTime acknowledgedAt = rs.getObject("acknowledged_at", OffsetDateTime.class);
                    long remaining = eligibleAt == null || acknowledgedAt != null ? 0
                            : Math.max(0, ChronoUnit.SECONDS.between(OffsetDateTime.now(), eligibleAt));
                    return new TaskBriefing(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("title"),
                            rs.getString("briefing_type"), rs.getString("content"), rs.getInt("read_seconds"),
                            rs.getLong("rule_version"), rs.getObject("started_at", OffsetDateTime.class), eligibleAt,
                            acknowledgedAt, rs.getLong("read_version"), remaining);
                }).list();
    }

    @Transactional
    public TaskBriefing startBriefingRead(UUID taskId, UUID ruleId, long ruleVersion) {
        requireTaskOwner(taskId, true);
        BriefingView rule = requireApplicableRule(taskId, ruleId, ruleVersion);
        UUID readingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO task_safety_briefing_read(id,task_id,rule_id,rule_version,reader_id)
                VALUES (:id,:task,:rule,:ruleVersion,:actor)
                ON CONFLICT (task_id,rule_id,rule_version,reader_id) DO NOTHING
                """).param("id", readingId).param("task", taskId).param("rule", rule.id())
                .param("ruleVersion", rule.version()).param("actor", actor.require().id()).update();
        TaskBriefing reading = taskBriefings(taskId).stream().filter(item -> item.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("TASK_BRIEFING_NOT_FOUND", "任务安全交底不存在"));
        audit.record("TASK_SAFETY_BRIEFING_STARTED", "TEST_TASK", taskId, null,
                Map.of("ruleId", ruleId, "ruleVersion", ruleVersion, "readSeconds", rule.readSeconds()), null, null);
        return reading;
    }

    @Transactional
    public TaskBriefing acknowledgeBriefing(UUID taskId, UUID ruleId, long ruleVersion, long expectedVersion) {
        requireTaskOwner(taskId, true);
        requireApplicableRule(taskId, ruleId, ruleVersion);
        TaskBriefing before = taskBriefings(taskId).stream().filter(item -> item.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("TASK_BRIEFING_NOT_FOUND", "任务安全交底不存在"));
        if (before.startedAt() == null) {
            throw ApiException.blocked("BRIEFING_NOT_STARTED", "请先开始阅读安全交底", expectedVersion,
                    List.of("安全交底尚未开始计时"));
        }
        if (before.readVersion() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "安全交底阅读状态已更新", before.readVersion());
        }
        if (before.acknowledgedAt() == null && before.eligibleAt().isAfter(OffsetDateTime.now())) {
            audit.recordRejected("TASK_SAFETY_BRIEFING_EARLY_ACK_REJECTED", "TEST_TASK", taskId,
                    Map.of("ruleId", ruleId, "remainingSeconds", before.remainingSeconds()),
                    "READ_TIME_NOT_REACHED", "安全交底最短阅读时间尚未达到");
            throw ApiException.blocked("READ_TIME_NOT_REACHED", "尚未达到安全交底最短阅读时间", before.readVersion(),
                    List.of("还需阅读 " + before.remainingSeconds() + " 秒"));
        }
        if (before.acknowledgedAt() == null) {
            int changed = jdbc.sql("""
                    UPDATE task_safety_briefing_read SET acknowledged_at=now(),version=version+1
                    WHERE task_id=:task AND rule_id=:rule AND rule_version=:ruleVersion
                      AND reader_id=:actor AND version=:version AND acknowledged_at IS NULL
                    """).param("task", taskId).param("rule", ruleId).param("ruleVersion", ruleVersion)
                    .param("actor", actor.require().id()).param("version", expectedVersion).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "安全交底阅读状态已更新",
                    taskBriefings(taskId).stream().filter(item -> item.id().equals(ruleId)).findFirst().orElseThrow().readVersion());
        }
        TaskBriefing after = taskBriefings(taskId).stream().filter(item -> item.id().equals(ruleId)).findFirst().orElseThrow();
        audit.record("TASK_SAFETY_BRIEFING_ACKNOWLEDGED", "TEST_TASK", taskId, before, after,
                "SAFETY_ACKNOWLEDGED", after.title());
        return after;
    }

    public void requireTaskAcceptanceBriefings(UUID taskId) {
        requireTaskOwner(taskId, true);
        List<TaskBriefing> pending = taskBriefings(taskId).stream()
                .filter(item -> item.acknowledgedAt() == null).toList();
        if (!pending.isEmpty()) {
            audit.recordRejected("TASK_ACCEPTANCE_BRIEFING_REJECTED", "TEST_TASK", taskId,
                    Map.of("pendingRuleIds", pending.stream().map(TaskBriefing::id).toList()),
                    "SAFETY_BRIEFING_REQUIRED", "任务接收前必须逐项阅读并确认安全交底");
            throw ApiException.blocked("SAFETY_BRIEFING_REQUIRED", "任务接收前必须完成安全交底", 0,
                    pending.stream().map(item -> "待确认：" + item.title()).toList());
        }
    }

    public List<TransitionTimeView> transitionTimes() {
        return jdbc.sql("""
                SELECT id,stage_code,enabled,allowed_days,version,updated_at FROM transition_time_setting
                WHERE organization_id=:organization ORDER BY stage_code
                """).param("organization", actor.require().organizationId())
                .query((rs, row) -> new TransitionTimeView(rs.getObject("id", UUID.class), rs.getString("stage_code"),
                        STAGE_LABELS.get(rs.getString("stage_code")), rs.getBoolean("enabled"),
                        rs.getInt("allowed_days"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public TransitionTimeView updateTransitionTime(UUID id, TransitionTimeWrite body) {
        TransitionTimeView before = requireTransitionTime(id);
        requireExpected(before.version(), body.expectedVersion(), "流转时间设置已被其他用户修改");
        int changed = jdbc.sql("""
                UPDATE transition_time_setting SET enabled=:enabled,allowed_days=:days,updated_by=:actor,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("enabled", body.enabled()).param("days", body.allowedDays())
                .param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) {
            TransitionTimeView current = requireTransitionTime(id);
            throw ApiException.conflict("VERSION_CONFLICT", "流转时间设置已被其他用户修改", current.version());
        }
        TransitionTimeView after = requireTransitionTime(id);
        audit.record("TRANSITION_TIME_UPDATED", "TRANSITION_TIME_SETTING", id, before, after, null, null);
        return after;
    }

    public List<TransitionWarning> transitionWarnings(UUID commissionId) {
        if (commissionId != null) access.requireCommission(commissionId);
        UUID noId = new UUID(0, 0);
        return jdbc.sql(warningSql()).param("organization", actor.require().organizationId())
                .param("commission", commissionId == null ? noId : commissionId)
                .param("allCommissions", commissionId == null)
                .query((rs, row) -> new TransitionWarning(rs.getString("stage_code"),
                        STAGE_LABELS.get(rs.getString("stage_code")), rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class), rs.getString("business_number"),
                        rs.getString("summary"), rs.getObject("deadline", OffsetDateTime.class),
                        rs.getString("warning_status"), rs.getInt("overdue_days"))).list();
    }

    private ThemeView requireTheme(UUID id) {
        return jdbc.sql(themeSelect() + " WHERE theme.id=:id AND theme.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapTheme).optional()
                .orElseThrow(() -> ApiException.notFound("THEME_NOT_FOUND", "桌面风格不存在"));
    }

    private ThemeView lockTheme(UUID id) {
        return jdbc.sql(themeSelect() + " WHERE theme.id=:id AND theme.organization_id=:organization FOR UPDATE")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapTheme).optional()
                .orElseThrow(() -> ApiException.notFound("THEME_NOT_FOUND", "桌面风格不存在"));
    }

    private BriefingView requireBriefing(UUID id) {
        return jdbc.sql(briefingSelect() + " WHERE rule.id=:id AND rule.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapBriefing).optional()
                .orElseThrow(() -> ApiException.notFound("BRIEFING_NOT_FOUND", "安全交底不存在"));
    }

    private BriefingView lockBriefing(UUID id) {
        return jdbc.sql(briefingSelect() + " WHERE rule.id=:id AND rule.organization_id=:organization FOR UPDATE")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapBriefing).optional()
                .orElseThrow(() -> ApiException.notFound("BRIEFING_NOT_FOUND", "安全交底不存在"));
    }

    private BriefingView requireApplicableRule(UUID taskId, UUID ruleId, long ruleVersion) {
        BriefingView rule = requireBriefing(ruleId);
        if (!rule.state().equals("ACTIVE") || rule.version() != ruleVersion) {
            throw ApiException.conflict("BRIEFING_VERSION_CHANGED", "安全交底版本已更新，请刷新后重新阅读", rule.version());
        }
        boolean applicable = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM test_task task
                  JOIN test_parameter parameter ON parameter.id=task.parameter_id
                  WHERE task.id=:task AND parameter.project_revision_id=:project)
                """).param("task", taskId).param("project", rule.detectionProjectRevisionId())
                .query(Boolean.class).single();
        if (!applicable) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BRIEFING_NOT_APPLICABLE",
                "该安全交底不适用于当前任务检测项目");
        return rule;
    }

    private TaskOwner requireTaskOwner(UUID taskId, boolean mustBeAssigned) {
        TaskOwner owner = jdbc.sql("""
                SELECT task.commission_id,commission.organization_id,task.assigned_to,task.state
                FROM test_task task JOIN commission ON commission.id=task.commission_id
                WHERE task.id=:task AND commission.organization_id=:organization
                """).param("task", taskId).param("organization", actor.require().organizationId())
                .query((rs, row) -> new TaskOwner(rs.getObject("commission_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("assigned_to", UUID.class),
                        rs.getString("state"))).optional()
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "检测任务不存在"));
        access.requireCommission(owner.commissionId());
        if (mustBeAssigned && !owner.state().equals("ASSIGNED")) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_ASSIGNED", "只有待接收任务可以阅读并确认安全交底");
        }
        AuthenticatedUser user = actor.require();
        if (mustBeAssigned && !user.permissions().contains("SYSTEM_ADMIN")
                && (user.personId() == null || !user.personId().equals(owner.assignedTo()))) {
            audit.recordRejected("TASK_READER_SCOPE_REJECTED", "TEST_TASK", taskId,
                    Map.of("assignedTo", String.valueOf(owner.assignedTo())), "TASK_ASSIGNEE_REQUIRED",
                    "只有任务责任人可以阅读安全交底并接收任务");
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_ASSIGNEE_REQUIRED", "只有任务责任人可以执行该操作");
        }
        return owner;
    }

    private TransitionTimeView requireTransitionTime(UUID id) {
        return jdbc.sql("""
                SELECT id,stage_code,enabled,allowed_days,version,updated_at FROM transition_time_setting
                WHERE id=:id AND organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> new TransitionTimeView(rs.getObject("id", UUID.class), rs.getString("stage_code"),
                        STAGE_LABELS.get(rs.getString("stage_code")), rs.getBoolean("enabled"),
                        rs.getInt("allowed_days"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("TRANSITION_TIME_NOT_FOUND", "流转时间设置不存在"));
    }

    private void requirePublishedProject(UUID id) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM detection_project_revision revision
                  JOIN detection_catalog_node node ON node.id=revision.project_node_id
                  WHERE revision.id=:id AND revision.status='PUBLISHED' AND node.status='ACTIVE')
                """).param("id", id).query(Boolean.class).single();
        if (!exists) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECT_REVISION_NOT_PUBLISHED",
                "安全交底只能绑定已发布且启用的检测项目版本");
    }

    private void validateBriefing(BriefingWrite body) {
        String type = body.briefingType() == null ? "" : body.briefingType().trim().toUpperCase(Locale.ROOT);
        if (!BRIEFING_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BRIEFING_TYPE_INVALID", "安全交底类型不受支持");
        }
    }

    private void validateTheme(ThemeWrite body) {
        if (contrast(body.brand900(), "#FFFFFF") < 4.5 || contrast(body.brand600(), "#FFFFFF") < 4.5
                || contrast(body.brand600(), body.brand100()) < 3.0
                || contrast("#213653", body.surfaceBackground()) < 4.5) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "THEME_CONTRAST_INVALID",
                    "桌面风格颜色未达到正文 4.5:1、界面图形 3:1 的最低对比度要求");
        }
    }

    private double contrast(String first, String second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double luminance(String hex) {
        double red = channel(Integer.parseInt(hex.substring(1, 3), 16) / 255.0);
        double green = channel(Integer.parseInt(hex.substring(3, 5), 16) / 255.0);
        double blue = channel(Integer.parseInt(hex.substring(5, 7), 16) / 255.0);
        return red * 0.2126 + green * 0.7152 + blue * 0.0722;
    }

    private double channel(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private ThemeView mapTheme(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new ThemeView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("remark"), rs.getString("brand_900"), rs.getString("brand_600"),
                rs.getString("brand_100"), rs.getString("surface_background"), rs.getString("density"),
                rs.getInt("radius_px"), rs.getInt("revision"), rs.getObject("supersedes_id", UUID.class), state,
                rs.getLong("version"), rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), themeActions(state));
    }

    private BriefingView mapBriefing(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new BriefingView(rs.getObject("id", UUID.class),
                rs.getObject("detection_project_revision_id", UUID.class), rs.getString("project_code"),
                rs.getString("project_name"), rs.getInt("project_revision"), rs.getString("code"), rs.getString("title"),
                rs.getString("briefing_type"), rs.getString("content"), rs.getInt("read_seconds"),
                rs.getInt("revision"), rs.getObject("supersedes_id", UUID.class), state, rs.getLong("version"),
                rs.getObject("activated_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                briefingActions(state));
    }

    private String themeSelect() {
        return """
                SELECT theme.id,theme.code,theme.name,theme.remark,theme.brand_900,theme.brand_600,
                  theme.brand_100,theme.surface_background,theme.density,theme.radius_px,theme.revision,
                  theme.supersedes_id,theme.state,theme.version,theme.activated_at,theme.updated_at
                FROM desktop_theme theme
                """;
    }

    private String briefingSelect() {
        return """
                SELECT rule.id,rule.detection_project_revision_id,
                  COALESCE(NULLIF(node.symbol,''),NULLIF(node.source_record_id,''),node.source_key) project_code,
                  node.name project_name,revision.revision_number project_revision,
                  rule.code,rule.title,rule.briefing_type,rule.content,rule.read_seconds,rule.revision,
                  rule.supersedes_id,rule.state,rule.version,rule.activated_at,rule.updated_at
                """ + briefingFrom();
    }

    private String briefingFrom() {
        return """
                FROM safety_briefing_rule rule
                JOIN detection_project_revision revision ON revision.id=rule.detection_project_revision_id
                JOIN detection_catalog_node node ON node.id=revision.project_node_id
                """;
    }

    private JdbcClient.StatementSpec bindBriefing(JdbcClient.StatementSpec statement, String query, String state,
                                                   PageCursor cursor) {
        return statement.param("organization", actor.require().organizationId()).param("q", query)
                .param("pattern", "%" + query + "%").param("state", state)
                .param("cursorAt", cursor == null ? null : cursor.updatedAt())
                .param("cursorId", cursor == null ? null : cursor.id());
    }

    private String warningSql() {
        return """
                WITH resources AS (
                  SELECT 'SAMPLE_INBOUND' stage_code,'SAMPLE' resource_type,sample.id resource_id,
                    sample.number business_number,sample.name summary,sample.created_at base_at,commission.id commission_id
                  FROM sample JOIN commission ON commission.id=sample.commission_id
                  WHERE commission.organization_id=:organization AND sample.state='EXPECTED'
                  UNION ALL
                  SELECT 'SAMPLE_OUTBOUND','SAMPLE',sample.id,sample.number,sample.name,sample.updated_at,commission.id
                  FROM sample JOIN commission ON commission.id=sample.commission_id
                  WHERE commission.organization_id=:organization AND sample.state='RETAINED'
                  UNION ALL
                  SELECT CASE report.state WHEN 'DRAFT' THEN 'REPORT_EDIT' WHEN 'RETURNED' THEN 'REPORT_EDIT'
                           WHEN 'IN_REVIEW' THEN 'REPORT_REVIEW' WHEN 'APPROVED' THEN 'REPORT_ISSUE'
                           WHEN 'ISSUED' THEN 'REPORT_SEAL' WHEN 'DISTRIBUTED' THEN 'REPORT_ARCHIVE' END,
                    'REPORT',report.id,report.number,report.title,report.updated_at,commission.id
                  FROM report JOIN commission ON commission.id=report.commission_id
                  LEFT JOIN report_version version ON version.report_id=report.id AND version.revision=report.current_revision
                  WHERE commission.organization_id=:organization
                    AND report.state IN ('DRAFT','RETURNED','IN_REVIEW','APPROVED','ISSUED','DISTRIBUTED')
                    AND (report.state<>'ISSUED' OR version.sealed_at IS NULL)
                  UNION ALL
                  SELECT 'NONCONFORMANCE_NOTICE','NONCONFORMANCE',nonconformance.id,nonconformance.number,
                    nonconformance.description,nonconformance.updated_at,nonconformance.commission_id
                  FROM nonconformance LEFT JOIN commission ON commission.id=nonconformance.commission_id
                  WHERE (commission.organization_id=:organization OR
                    (nonconformance.commission_id IS NULL AND nonconformance.opened_by IN
                      (SELECT id FROM app_user WHERE organization_id=:organization)))
                    AND nonconformance.state NOT IN ('CLOSED','REJECTED')
                ), deadlines AS (
                  SELECT resource.*,resource.base_at + make_interval(days=>setting.allowed_days) deadline
                  FROM resources resource JOIN transition_time_setting setting
                    ON setting.organization_id=:organization AND setting.stage_code=resource.stage_code AND setting.enabled
                  WHERE (:allCommissions OR resource.commission_id=:commission)
                )
                SELECT stage_code,resource_type,resource_id,business_number,summary,deadline,
                  CASE WHEN deadline<now() THEN 'OVERDUE' ELSE 'DUE_SOON' END warning_status,
                  GREATEST(0,FLOOR(EXTRACT(EPOCH FROM (now()-deadline))/86400))::integer overdue_days
                FROM deadlines WHERE deadline<=now()+interval '1 day'
                ORDER BY deadline,resource_id LIMIT 100
                """;
    }

    private List<String> themeActions(String state) {
        return state.equals("DRAFT") ? List.of("ACTIVATE", "DISCARD") : List.of();
    }

    private List<String> briefingActions(String state) {
        return switch (state) { case "DRAFT" -> List.of("ACTIVATE", "DISCARD"); case "ACTIVE" -> List.of("DISABLE"); default -> List.of(); };
    }

    private void requireExpected(long current, Long expected, String message) {
        if (expected == null) throw new ApiException(HttpStatus.BAD_REQUEST, "EXPECTED_VERSION_REQUIRED", "必须提供 expectedVersion");
        if (current != expected) throw ApiException.conflict("VERSION_CONFLICT", message, current);
    }

    private void requireDraft(String state, String message) {
        if (!state.equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT, "IMMUTABLE_PUBLISHED_CONFIGURATION", message);
    }

    private ApiException themeConflict(UUID id) {
        return ApiException.conflict("VERSION_CONFLICT", "桌面风格已被其他用户修改", requireTheme(id).version());
    }

    private ApiException briefingConflict(UUID id) {
        return ApiException.conflict("VERSION_CONFLICT", "安全交底已被其他用户修改", requireBriefing(id).version());
    }

    private ApiException invalidAction(String label, String action) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_CONFIGURATION_TRANSITION", label + "不允许动作 " + action);
    }

    private String action(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedCode(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private String upper(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private record TaskOwner(UUID commissionId, UUID organizationId, UUID assignedTo, String state) {}
}
