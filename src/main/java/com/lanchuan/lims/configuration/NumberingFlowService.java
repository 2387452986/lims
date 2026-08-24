package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NumberingFlowService {
    private static final List<String> RESOURCE_TYPES = List.of("COMMISSION", "SAMPLE", "TASK", "REPORT");
    private static final Set<String> MODES = Set.of("SEPARATE", "SAME_AS_COMMISSION", "REPLACE_PREFIX", "REMOVE_PREFIX");
    private static final Set<String> SCOPES = Set.of("SHARED", "PER_PROJECT");
    private static final Set<String> DATE_PATTERNS = Set.of("NONE", "y", "ym", "ymd", "Y", "Ym", "Ymd");
    private static final Set<String> PREFIX_POSITIONS = Set.of("BEFORE_DATE", "AFTER_DATE");
    private static final Set<String> SUFFIX_POSITIONS = Set.of("BEFORE_SEQUENCE", "AFTER_SEQUENCE");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final NumberPatternRenderer renderer;

    public NumberingFlowService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                NumberPatternRenderer renderer) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.renderer = renderer;
    }

    public record PatternWrite(String resourceType, String generationMode, String sequenceScope,
                               String seed, String pattern) {}
    public record PatternView(String resourceType, String generationMode, String sequenceScope,
                              String seed, String pattern, String preview) {}
    public record RuleWrite(String code, String name, boolean autoMatchProjectCode,
                            List<PatternWrite> patterns, List<UUID> projectIds, long expectedVersion) {}
    public record RuleView(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber, String code, String name,
                           boolean autoMatchProjectCode, String state, long version, String sourceSystem,
                           String sourceRecordId, String sourcePath, OffsetDateTime sourceCapturedAt,
                           OffsetDateTime publishedAt, OffsetDateTime updatedAt, List<PatternView> patterns,
                           List<UUID> projectIds, int projectCount, List<String> allowedActions) {}

    public record TemplateWrite(String code, String name, String prefix, String prefixPosition,
                                String datePattern, String suffix, String suffixPosition,
                                int sequenceWidth, long expectedVersion) {}
    public record TemplateView(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber,
                               String code, String name, String prefix, String prefixPosition,
                               String datePattern, String suffix, String suffixPosition, int sequenceWidth,
                               String state, long version, String sourceSystem, String sourceRecordId,
                               String sourcePath, OffsetDateTime sourceCapturedAt, OffsetDateTime publishedAt,
                               OffsetDateTime updatedAt, String preview, List<String> allowedActions) {}

    public List<RuleView> listRules(String query) {
        String needle = query == null ? "" : query.trim();
        String pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) rule.* FROM numbering_flow_rule rule
                  ORDER BY series_id, revision_number DESC
                )
                SELECT * FROM latest
                WHERE (:query='' OR COALESCE(code,'') ILIKE :pattern ESCAPE '\\' OR name ILIKE :pattern ESCAPE '\\')
                ORDER BY updated_at DESC, id DESC
                """).param("query", needle).param("pattern", pattern).query(this::mapRule).list().stream()
                .map(this::withRuleDetails).toList();
    }

    public RuleView requireRule(UUID id) {
        RuleView rule = jdbc.sql("SELECT * FROM numbering_flow_rule WHERE id=:id")
                .param("id", id).query(this::mapRule).optional()
                .orElseThrow(() -> ApiException.notFound("NUMBERING_FLOW_RULE_NOT_FOUND", "流水规则不存在"));
        return withRuleDetails(rule);
    }

    public List<RuleView> ruleRevisions(UUID id) {
        RuleView current = requireRule(id);
        return jdbc.sql("SELECT * FROM numbering_flow_rule WHERE series_id=:series ORDER BY revision_number DESC")
                .param("series", current.seriesId()).query(this::mapRule).list().stream()
                .map(this::withRuleDetails).toList();
    }

    @Transactional
    public RuleView createRule(RuleWrite command) {
        ValidatedRule value = validateRule(command, true);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO numbering_flow_rule(id,series_id,revision_number,code,name,auto_match_project_code,
                  state,source_system,source_record_id,created_by,updated_by)
                VALUES (:id,:id,1,:code,:name,:autoMatch,'DRAFT','lims',:sourceRecord,:actor,:actor)
                """).param("id", id).param("code", value.code()).param("name", value.name())
                .param("autoMatch", value.autoMatch()).param("sourceRecord", "local:" + id)
                .param("actor", actor.require().id()).update();
        replacePatterns(id, value.patterns());
        replaceProjects(id, value.projectIds());
        RuleView created = requireRule(id);
        audit.record("NUMBERING_FLOW_RULE_CREATED", "NUMBERING_FLOW_RULE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public RuleView updateRule(UUID id, RuleWrite command) {
        RuleView before = requireRule(id);
        requireDraft(before.state(), "流水规则");
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "流水规则已被其他用户更新", before.version());
        }
        ValidatedRule value = validateRule(command, true);
        int changed = jdbc.sql("""
                UPDATE numbering_flow_rule SET code=:code,name=:name,auto_match_project_code=:autoMatch,
                  lock_version=lock_version+1,updated_by=:actor,updated_at=now()
                WHERE id=:id AND state='DRAFT' AND lock_version=:version
                """).param("code", value.code()).param("name", value.name()).param("autoMatch", value.autoMatch())
                .param("actor", actor.require().id()).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "流水规则已被其他用户更新", requireRule(id).version());
        replacePatterns(id, value.patterns());
        replaceProjects(id, value.projectIds());
        RuleView after = requireRule(id);
        audit.record("NUMBERING_FLOW_RULE_UPDATED", "NUMBERING_FLOW_RULE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public RuleView reviseRule(UUID id) {
        RuleView before = requireRule(id);
        if (before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_RULE_REVISION_INVALID", "草稿规则可直接修改");
        }
        lockRuleSeries(before.seriesId());
        if (hasRuleDraft(before.seriesId())) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_RULE_DRAFT_EXISTS", "该规则已有修订草稿");
        }
        int revision = jdbc.sql("SELECT max(revision_number)+1 FROM numbering_flow_rule WHERE series_id=:series")
                .param("series", before.seriesId()).query(Integer.class).single();
        UUID idNew = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO numbering_flow_rule(id,series_id,predecessor_id,revision_number,code,name,
                  auto_match_project_code,state,source_system,source_record_id,created_by,updated_by)
                VALUES (:id,:series,:predecessor,:revision,:code,:name,:autoMatch,'DRAFT','lims',:sourceRecord,:actor,:actor)
                """).param("id", idNew).param("series", before.seriesId()).param("predecessor", before.id())
                .param("revision", revision).param("code", before.code()).param("name", before.name())
                .param("autoMatch", before.autoMatchProjectCode()).param("sourceRecord", "series:" + before.seriesId())
                .param("actor", actor.require().id()).update();
        replacePatterns(idNew, before.patterns().stream().map(item -> new PatternWrite(item.resourceType(),
                item.generationMode(), item.sequenceScope(), item.seed(), item.pattern())).toList());
        replaceProjects(idNew, before.projectIds());
        RuleView created = requireRule(idNew);
        audit.record("NUMBERING_FLOW_RULE_REVISED", "NUMBERING_FLOW_RULE", idNew, before, created,
                "CONTROLLED_REVISION", "从版本 " + before.revisionNumber() + " 创建修订");
        return created;
    }

    @Transactional
    public TransitionResult transitionRule(UUID id, TransitionCommand command) {
        RuleView before = requireRule(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "流水规则已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if (action.equals("DISCARD") && before.state().equals("DRAFT")) {
            requireReason(command.comment(), "废弃草稿必须填写原因");
            audit.record("NUMBERING_FLOW_RULE_DISCARDED", "NUMBERING_FLOW_RULE", id, before, null,
                    command.reasonCode(), command.comment());
            jdbc.sql("DELETE FROM numbering_flow_rule WHERE id=:id AND state='DRAFT' AND lock_version=:version")
                    .param("id", id).param("version", before.version()).update();
            return new TransitionResult("DISCARDED", before.version() + 1, List.of());
        }
        String next;
        if (action.equals("PUBLISH") && before.state().equals("DRAFT")) {
            validateRule(new RuleWrite(before.code(), before.name(), before.autoMatchProjectCode(),
                    before.patterns().stream().map(item -> new PatternWrite(item.resourceType(), item.generationMode(),
                            item.sequenceScope(), item.seed(), item.pattern())).toList(), before.projectIds(), before.version()), true);
            lockRuleSeries(before.seriesId());
            UUID duplicate = jdbc.sql("""
                    SELECT id FROM numbering_flow_rule WHERE state='PUBLISHED' AND series_id<>:series
                      AND lower(code)=lower(:code) LIMIT 1
                    """).param("series", before.seriesId()).param("code", before.code())
                    .query(UUID.class).optional().orElse(null);
            if (duplicate != null) throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_RULE_CODE_EXISTS", "已有发布规则使用该代码");
            jdbc.sql("""
                    UPDATE numbering_flow_rule SET state='RETIRED',lock_version=lock_version+1,
                      updated_by=:actor,updated_at=now()
                    WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("actor", actor.require().id()).param("series", before.seriesId()).param("id", id).update();
            next = "PUBLISHED";
        } else if (action.equals("RETIRE") && before.state().equals("PUBLISHED")) {
            requireReason(command.comment(), "停用发布规则必须填写原因");
            next = "RETIRED";
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_RULE_TRANSITION_INVALID", "当前规则不能执行该动作");
        }
        int changed = jdbc.sql("""
                UPDATE numbering_flow_rule SET state=:state,lock_version=lock_version+1,updated_by=:actor,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,updated_at=now()
                WHERE id=:id AND lock_version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "流水规则已被其他用户更新", requireRule(id).version());
        RuleView after = requireRule(id);
        audit.record("NUMBERING_FLOW_RULE_" + action, "NUMBERING_FLOW_RULE", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public Map<String, String> previewRule(UUID id, String symbol, LocalDate date, long sequence) {
        RuleView rule = requireRule(id);
        return previews(rule.patterns().stream().map(item -> new PatternWrite(item.resourceType(),
                item.generationMode(), item.sequenceScope(), item.seed(), item.pattern())).toList(), symbol, date, sequence);
    }

    @Transactional
    public String nextFlow(UUID ruleId, String resourceType, UUID projectId, String symbol) {
        RuleView rule = requireRule(ruleId);
        if (!rule.state().equals("PUBLISHED")) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_RULE_NOT_PUBLISHED", "仅发布规则可生成编号");
        }
        String type = requiredEnum(resourceType, Set.copyOf(RESOURCE_TYPES), "资源类型");
        PatternView selected = rule.patterns().stream().filter(item -> item.resourceType().equals(type)).findFirst().orElseThrow();
        if (!selected.generationMode().equals("SEPARATE")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RELATED_NUMBER_REQUIRES_COMMISSION",
                    "该资源编号继承委托编号，应从已冻结委托编号派生，不能单独取号");
        }
        if (!rule.projectIds().isEmpty() && (projectId == null || !rule.projectIds().contains(projectId))) {
            throw ApiException.blocked("NUMBERING_FLOW_PROJECT_OUT_OF_SCOPE", "流水规则不适用于该检测项目",
                    rule.version(), List.of("请选择项目已绑定的发布规则"));
        }
        String scope = selected.sequenceScope().equals("PER_PROJECT")
                ? projectId == null ? "missing" : projectId.toString().substring(0, 8) : "shared";
        String sequenceKey = "FLOW:" + rule.id().toString().substring(0, 8) + ":" + type + ":" + scope;
        String period = String.valueOf(LocalDate.now().getYear());
        long initial = initialValue(selected.seed());
        long value = jdbc.sql("""
                INSERT INTO number_sequence(rule_code,period_key,current_value) VALUES (:rule,:period,:initial)
                ON CONFLICT(rule_code,period_key) DO UPDATE SET current_value=number_sequence.current_value+1,
                  updated_at=now() RETURNING current_value
                """).param("rule", sequenceKey).param("period", period).param("initial", initial)
                .query(Long.class).single();
        return renderer.render(selected.pattern(), symbol, LocalDate.now(), value);
    }

    public List<TemplateView> listTemplates(String query) {
        String needle = query == null ? "" : query.trim();
        String pattern = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) template.* FROM numbering_flow_template template
                  ORDER BY series_id, revision_number DESC
                )
                SELECT * FROM latest WHERE (:query='' OR code ILIKE :pattern ESCAPE '\\' OR name ILIKE :pattern ESCAPE '\\')
                ORDER BY updated_at DESC,id DESC
                """).param("query", needle).param("pattern", pattern).query(this::mapTemplate).list();
    }

    public TemplateView requireTemplate(UUID id) {
        return jdbc.sql("SELECT * FROM numbering_flow_template WHERE id=:id").param("id", id)
                .query(this::mapTemplate).optional()
                .orElseThrow(() -> ApiException.notFound("NUMBERING_FLOW_TEMPLATE_NOT_FOUND", "流水模板不存在"));
    }

    @Transactional
    public TemplateView createTemplate(TemplateWrite command) {
        ValidatedTemplate value = validateTemplate(command);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO numbering_flow_template(id,series_id,revision_number,code,name,prefix,prefix_position,
                  date_pattern,suffix,suffix_position,sequence_width,state,source_system,source_record_id,created_by,updated_by)
                VALUES (:id,:id,1,:code,:name,:prefix,:prefixPosition,:datePattern,:suffix,:suffixPosition,
                  :width,'DRAFT','lims',:sourceRecord,:actor,:actor)
                """).param("id", id).param("code", value.code()).param("name", value.name())
                .param("prefix", value.prefix()).param("prefixPosition", value.prefixPosition())
                .param("datePattern", value.datePattern()).param("suffix", value.suffix())
                .param("suffixPosition", value.suffixPosition()).param("width", value.sequenceWidth())
                .param("sourceRecord", "local:" + id).param("actor", actor.require().id()).update();
        TemplateView created = requireTemplate(id);
        audit.record("NUMBERING_FLOW_TEMPLATE_CREATED", "NUMBERING_FLOW_TEMPLATE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public TemplateView updateTemplate(UUID id, TemplateWrite command) {
        TemplateView before = requireTemplate(id);
        requireDraft(before.state(), "流水模板");
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "流水模板已被其他用户更新", before.version());
        }
        ValidatedTemplate value = validateTemplate(command);
        int changed = jdbc.sql("""
                UPDATE numbering_flow_template SET code=:code,name=:name,prefix=:prefix,
                  prefix_position=:prefixPosition,date_pattern=:datePattern,suffix=:suffix,
                  suffix_position=:suffixPosition,sequence_width=:width,lock_version=lock_version+1,
                  updated_by=:actor,updated_at=now()
                WHERE id=:id AND state='DRAFT' AND lock_version=:version
                """).param("code", value.code()).param("name", value.name()).param("prefix", value.prefix())
                .param("prefixPosition", value.prefixPosition()).param("datePattern", value.datePattern())
                .param("suffix", value.suffix()).param("suffixPosition", value.suffixPosition())
                .param("width", value.sequenceWidth()).param("actor", actor.require().id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "流水模板已被其他用户更新", requireTemplate(id).version());
        TemplateView after = requireTemplate(id);
        audit.record("NUMBERING_FLOW_TEMPLATE_UPDATED", "NUMBERING_FLOW_TEMPLATE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TemplateView reviseTemplate(UUID id) {
        TemplateView before = requireTemplate(id);
        if (before.state().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "NUMBERING_FLOW_TEMPLATE_REVISION_INVALID", "草稿模板可直接修改");
        jdbc.sql("SELECT id FROM numbering_flow_template WHERE series_id=:series FOR UPDATE")
                .param("series", before.seriesId()).query(UUID.class).list();
        if (jdbc.sql("SELECT count(*) FROM numbering_flow_template WHERE series_id=:series AND state='DRAFT'")
                .param("series", before.seriesId()).query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_TEMPLATE_DRAFT_EXISTS", "该模板已有修订草稿");
        }
        int revision = jdbc.sql("SELECT max(revision_number)+1 FROM numbering_flow_template WHERE series_id=:series")
                .param("series", before.seriesId()).query(Integer.class).single();
        UUID idNew = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO numbering_flow_template(id,series_id,predecessor_id,revision_number,code,name,prefix,
                  prefix_position,date_pattern,suffix,suffix_position,sequence_width,state,source_system,
                  source_record_id,created_by,updated_by)
                VALUES (:id,:series,:predecessor,:revision,:code,:name,:prefix,:prefixPosition,:datePattern,
                  :suffix,:suffixPosition,:width,'DRAFT','lims',:sourceRecord,:actor,:actor)
                """).param("id", idNew).param("series", before.seriesId()).param("predecessor", before.id())
                .param("revision", revision).param("code", before.code()).param("name", before.name())
                .param("prefix", before.prefix()).param("prefixPosition", before.prefixPosition())
                .param("datePattern", before.datePattern()).param("suffix", before.suffix())
                .param("suffixPosition", before.suffixPosition()).param("width", before.sequenceWidth())
                .param("sourceRecord", "series:" + before.seriesId()).param("actor", actor.require().id()).update();
        TemplateView created = requireTemplate(idNew);
        audit.record("NUMBERING_FLOW_TEMPLATE_REVISED", "NUMBERING_FLOW_TEMPLATE", idNew, before, created,
                "CONTROLLED_REVISION", "从版本 " + before.revisionNumber() + " 创建修订");
        return created;
    }

    @Transactional
    public TransitionResult transitionTemplate(UUID id, TransitionCommand command) {
        TemplateView before = requireTemplate(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "流水模板已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if (action.equals("DISCARD") && before.state().equals("DRAFT")) {
            requireReason(command.comment(), "废弃草稿必须填写原因");
            audit.record("NUMBERING_FLOW_TEMPLATE_DISCARDED", "NUMBERING_FLOW_TEMPLATE", id, before, null,
                    command.reasonCode(), command.comment());
            jdbc.sql("DELETE FROM numbering_flow_template WHERE id=:id AND state='DRAFT' AND lock_version=:version")
                    .param("id", id).param("version", before.version()).update();
            return new TransitionResult("DISCARDED", before.version() + 1, List.of());
        }
        String next;
        if (action.equals("PUBLISH") && before.state().equals("DRAFT")) {
            jdbc.sql("SELECT id FROM numbering_flow_template WHERE series_id=:series FOR UPDATE")
                    .param("series", before.seriesId()).query(UUID.class).list();
            UUID duplicate = jdbc.sql("""
                    SELECT id FROM numbering_flow_template WHERE state='PUBLISHED' AND series_id<>:series
                      AND lower(code)=lower(:code) LIMIT 1
                    """).param("series", before.seriesId()).param("code", before.code())
                    .query(UUID.class).optional().orElse(null);
            if (duplicate != null) throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_TEMPLATE_CODE_EXISTS", "已有发布模板使用该代码");
            jdbc.sql("""
                    UPDATE numbering_flow_template SET state='RETIRED',lock_version=lock_version+1,
                      updated_by=:actor,updated_at=now()
                    WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("actor", actor.require().id()).param("series", before.seriesId()).param("id", id).update();
            next = "PUBLISHED";
        } else if (action.equals("RETIRE") && before.state().equals("PUBLISHED")) {
            requireReason(command.comment(), "停用发布模板必须填写原因");
            next = "RETIRED";
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBERING_FLOW_TEMPLATE_TRANSITION_INVALID", "当前模板不能执行该动作");
        }
        int changed = jdbc.sql("""
                UPDATE numbering_flow_template SET state=:state,lock_version=lock_version+1,updated_by=:actor,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,updated_at=now()
                WHERE id=:id AND lock_version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "流水模板已被其他用户更新", requireTemplate(id).version());
        TemplateView after = requireTemplate(id);
        audit.record("NUMBERING_FLOW_TEMPLATE_" + action, "NUMBERING_FLOW_TEMPLATE", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public String previewTemplate(TemplateWrite command, LocalDate date, long sequence) {
        ValidatedTemplate value = validateTemplate(command);
        return renderTemplate(value, date, sequence);
    }

    private RuleView mapRule(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new RuleView(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("auto_match_project_code"), state,
                rs.getLong("lock_version"), rs.getString("source_system"), rs.getString("source_record_id"),
                rs.getString("source_path"), rs.getObject("source_captured_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                List.of(), List.of(), 0, actions(state));
    }

    private RuleView withRuleDetails(RuleView rule) {
        List<PatternWrite> source = jdbc.sql("""
                SELECT resource_type,generation_mode,sequence_scope,seed,pattern
                FROM numbering_flow_rule_pattern WHERE rule_id=:id ORDER BY sort_order
                """).param("id", rule.id()).query((rs, row) -> new PatternWrite(rs.getString("resource_type"),
                        rs.getString("generation_mode"), rs.getString("sequence_scope"), rs.getString("seed"),
                        rs.getString("pattern"))).list();
        Map<String, String> previews = source.size() == 4 ? previews(source, "XM", LocalDate.now(), 1) : Map.of();
        List<PatternView> patterns = source.stream().map(item -> new PatternView(item.resourceType(),
                item.generationMode(), item.sequenceScope(), item.seed(), item.pattern(),
                previews.get(item.resourceType()))).toList();
        List<UUID> projectIds = jdbc.sql("""
                SELECT project_node_id FROM numbering_flow_rule_project WHERE rule_id=:id ORDER BY project_node_id
                """).param("id", rule.id()).query(UUID.class).list();
        return new RuleView(rule.id(), rule.seriesId(), rule.predecessorId(), rule.revisionNumber(), rule.code(),
                rule.name(), rule.autoMatchProjectCode(), rule.state(), rule.version(), rule.sourceSystem(),
                rule.sourceRecordId(), rule.sourcePath(), rule.sourceCapturedAt(), rule.publishedAt(), rule.updatedAt(),
                patterns, projectIds, projectIds.size(), rule.allowedActions());
    }

    private TemplateView mapTemplate(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        ValidatedTemplate template = new ValidatedTemplate(rs.getString("code"), rs.getString("name"),
                rs.getString("prefix"), rs.getString("prefix_position"), rs.getString("date_pattern"),
                rs.getString("suffix"), rs.getString("suffix_position"), rs.getInt("sequence_width"));
        return new TemplateView(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), template.code(),
                template.name(), template.prefix(), template.prefixPosition(), template.datePattern(),
                template.suffix(), template.suffixPosition(), template.sequenceWidth(), state,
                rs.getLong("lock_version"), rs.getString("source_system"), rs.getString("source_record_id"),
                rs.getString("source_path"), rs.getObject("source_captured_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                renderTemplate(template, LocalDate.now(), 1), actions(state));
    }

    private ValidatedRule validateRule(RuleWrite command, boolean requireCode) {
        String code = optional(command.code(), 80, "规则代码");
        if (requireCode && code == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_RULE_CODE_REQUIRED", "规则代码不能为空");
        String name = required(command.name(), 240, "规则名称");
        if (command.patterns() == null || command.patterns().size() != 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NUMBERING_FLOW_PATTERNS_INCOMPLETE", "委托、样品、任务和报告规则必须完整配置");
        }
        Map<String, PatternWrite> byType = new HashMap<>();
        for (PatternWrite item : command.patterns()) {
            String type = requiredEnum(item.resourceType(), Set.copyOf(RESOURCE_TYPES), "资源类型");
            String mode = requiredEnum(item.generationMode(), MODES, "生成方式");
            String scope = requiredEnum(item.sequenceScope(), SCOPES, "流水范围");
            if (type.equals("COMMISSION") && !mode.equals("SEPARATE")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMMISSION_NUMBER_MUST_BE_SEPARATE", "委托编号必须使用独立流水");
            }
            String seed = optional(item.seed(), 80, "流水种子");
            if (mode.equals("SEPARATE") && seed == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NUMBERING_FLOW_SEED_REQUIRED", type + " 独立流水必须配置种子");
            }
            String pattern = required(item.pattern(), 240, "编号模式");
            renderer.render(pattern, "CS", LocalDate.of(2026, 8, 19), Math.max(1, initialValue(seed)));
            if (byType.put(type, new PatternWrite(type, mode, scope, seed, pattern)) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NUMBERING_FLOW_RESOURCE_DUPLICATED", "同一资源类型不能重复配置");
            }
        }
        if (!byType.keySet().containsAll(RESOURCE_TYPES)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NUMBERING_FLOW_PATTERNS_INCOMPLETE", "流水规则配置不完整");
        }
        List<UUID> projectIds = command.projectIds() == null ? List.of()
                : command.projectIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (projectIds.size() > 1000) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_PROJECTS_EXCEEDED", "单个规则最多关联1000个检测项目");
        if (!projectIds.isEmpty()) {
            long existing = jdbc.sql("SELECT count(*) FROM detection_catalog_node WHERE id IN (:ids) AND node_type='PROJECT'")
                    .param("ids", projectIds).query(Long.class).single();
            if (existing != projectIds.size()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "NUMBERING_FLOW_PROJECT_INVALID", "适用范围包含非检测项目节点");
        }
        List<PatternWrite> ordered = RESOURCE_TYPES.stream().map(byType::get).toList();
        return new ValidatedRule(code, name, command.autoMatchProjectCode(), ordered, projectIds);
    }

    private ValidatedTemplate validateTemplate(TemplateWrite command) {
        String code = required(command.code(), 80, "模板代码");
        String name = required(command.name(), 240, "模板名称");
        String prefix = optional(command.prefix(), 160, "前缀");
        String prefixPosition = optionalEnum(command.prefixPosition(), PREFIX_POSITIONS, "前缀位置");
        String datePattern = command.datePattern() == null ? "NONE" : command.datePattern().trim();
        if (!DATE_PATTERNS.contains(datePattern)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_DATE_PATTERN_INVALID", "日期模式无效");
        String suffix = optional(command.suffix(), 160, "后缀");
        String suffixPosition = optionalEnum(command.suffixPosition(), SUFFIX_POSITIONS, "后缀位置");
        if (command.sequenceWidth() < 1 || command.sequenceWidth() > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NUMBERING_FLOW_SEQUENCE_WIDTH_INVALID", "流水宽度必须为1到12位");
        }
        return new ValidatedTemplate(code, name, prefix == null ? "" : prefix, prefixPosition,
                datePattern, suffix, suffixPosition, command.sequenceWidth());
    }

    private void replacePatterns(UUID ruleId, List<PatternWrite> patterns) {
        jdbc.sql("DELETE FROM numbering_flow_rule_pattern WHERE rule_id=:id").param("id", ruleId).update();
        for (int index = 0; index < patterns.size(); index++) {
            PatternWrite item = patterns.get(index);
            jdbc.sql("""
                    INSERT INTO numbering_flow_rule_pattern(rule_id,resource_type,generation_mode,
                      sequence_scope,seed,pattern,sort_order)
                    VALUES (:rule,:resource,:mode,:scope,:seed,:pattern,:sortOrder)
                    """).param("rule", ruleId).param("resource", item.resourceType())
                    .param("mode", item.generationMode()).param("scope", item.sequenceScope())
                    .param("seed", item.seed()).param("pattern", item.pattern()).param("sortOrder", index + 1).update();
        }
    }

    private void replaceProjects(UUID ruleId, List<UUID> projectIds) {
        jdbc.sql("DELETE FROM numbering_flow_rule_project WHERE rule_id=:id").param("id", ruleId).update();
        for (UUID projectId : projectIds) {
            jdbc.sql("INSERT INTO numbering_flow_rule_project(rule_id,project_node_id) VALUES (:rule,:project)")
                    .param("rule", ruleId).param("project", projectId).update();
        }
    }

    private Map<String, String> previews(List<PatternWrite> patterns, String symbol, LocalDate date, long sequence) {
        Map<String, PatternWrite> byType = new LinkedHashMap<>();
        patterns.forEach(item -> byType.put(item.resourceType(), item));
        String commission = renderer.render(byType.get("COMMISSION").pattern(), symbol, date, sequence);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("COMMISSION", commission);
        for (String type : RESOURCE_TYPES.subList(1, RESOURCE_TYPES.size())) {
            PatternWrite item = byType.get(type);
            String value = item.generationMode().equals("SAME_AS_COMMISSION") ? commission
                    : renderer.render(item.pattern(), symbol, date, sequence);
            values.put(type, value);
        }
        return values;
    }

    private String renderTemplate(ValidatedTemplate value, LocalDate date, long sequence) {
        if (date == null || sequence < 1) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_PREVIEW_INVALID", "预览日期或流水值无效");
        String sequenceText = Long.toString(sequence);
        if (sequenceText.length() > value.sequenceWidth()) throw new ApiException(HttpStatus.CONFLICT,
                "NUMBER_SEQUENCE_EXHAUSTED", "流水号已超过模板宽度");
        sequenceText = "%0" + value.sequenceWidth() + "d";
        sequenceText = sequenceText.formatted(sequence);
        String dateText = switch (value.datePattern()) {
            case "NONE" -> "";
            case "y" -> date.format(DateTimeFormatter.ofPattern("yy"));
            case "ym" -> date.format(DateTimeFormatter.ofPattern("yyMM"));
            case "ymd" -> date.format(DateTimeFormatter.ofPattern("yyMMdd"));
            case "Y" -> date.format(DateTimeFormatter.ofPattern("yyyy"));
            case "Ym" -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "Ymd" -> date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            default -> throw new IllegalStateException("Unsupported date pattern");
        };
        String prefixPosition = value.prefixPosition() == null ? "BEFORE_DATE" : value.prefixPosition();
        String base = prefixPosition.equals("AFTER_DATE") ? dateText + value.prefix() : value.prefix() + dateText;
        if (!base.isEmpty() && !base.matches(".*[-_/ ]$")) base += "-";
        String suffixPosition = value.suffixPosition() == null ? "AFTER_SEQUENCE" : value.suffixPosition();
        return suffixPosition.equals("BEFORE_SEQUENCE")
                ? base + (value.suffix() == null ? "" : value.suffix()) + sequenceText
                : base + sequenceText + (value.suffix() == null ? "" : value.suffix());
    }

    private long initialValue(String seed) {
        if (seed == null) return 1;
        Matcher matcher = TRAILING_NUMBER.matcher(seed);
        if (!matcher.find()) return 1;
        try { return Math.max(1, Long.parseLong(matcher.group(1))); }
        catch (NumberFormatException exception) { return 1; }
    }

    private void lockRuleSeries(UUID seriesId) {
        jdbc.sql("SELECT id FROM numbering_flow_rule WHERE series_id=:series FOR UPDATE")
                .param("series", seriesId).query(UUID.class).list();
    }

    private boolean hasRuleDraft(UUID seriesId) {
        return jdbc.sql("SELECT count(*) FROM numbering_flow_rule WHERE series_id=:series AND state='DRAFT'")
                .param("series", seriesId).query(Long.class).single() > 0;
    }

    private void requireDraft(String state, String label) {
        if (!state.equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "CONTROLLED_CONFIGURATION_IMMUTABLE", "已发布或停用" + label + "须创建修订后修改");
    }

    private String requiredEnum(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_FIELD_REQUIRED", label + "不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_ENUM_INVALID", label + "无效");
        return normalized;
    }

    private String optionalEnum(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) return null;
        return requiredEnum(value, allowed, label);
    }

    private String required(String value, int max, String label) {
        String result = optional(value, max, label);
        if (result == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_FIELD_REQUIRED", label + "不能为空");
        return result;
    }

    private String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST,
                "NUMBERING_FLOW_FIELD_TOO_LONG", label + "超过最大长度");
        return result;
    }

    private void requireReason(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "TRANSITION_REASON_REQUIRED", message);
    }

    private static List<String> actions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("UPDATE", "PUBLISH", "DISCARD");
            case "PUBLISHED" -> List.of("REVISE", "RETIRE");
            case "RETIRED" -> List.of("REVISE");
            default -> List.of();
        };
    }

    private record ValidatedRule(String code, String name, boolean autoMatch, List<PatternWrite> patterns,
                                 List<UUID> projectIds) {}
    private record ValidatedTemplate(String code, String name, String prefix, String prefixPosition,
                                     String datePattern, String suffix, String suffixPosition, int sequenceWidth) {}
}
