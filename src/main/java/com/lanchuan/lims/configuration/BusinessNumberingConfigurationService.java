package com.lanchuan.lims.configuration;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BusinessNumberingConfigurationService {
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "PROJECT", "CONTRACT", "QUALITY_ASSESSMENT", "PRECOMMISSION", "COMMISSION");
    private static final Set<String> TEMPLATE_TYPES = Set.of(
            "CONCRETE_ASSESSMENT", "REPORT_DISTRIBUTION", "EQUIPMENT_BORROW_RETURN", "REPORT_ARCHIVE_FLOW");
    private static final Set<String> DATE_PATTERNS = Set.of("NONE", "Y", "y", "Ym", "ym", "Ymd", "ymd");
    private static final Set<String> PREFIX_POSITIONS = Set.of("NUMBER_START", "BEFORE_SEQUENCE");
    private static final Set<String> SUFFIX_POSITIONS = Set.of("NUMBER_END", "AFTER_DATE");
    private static final Pattern RULE_CODE = Pattern.compile("[A-Za-z0-9]{6}");
    private static final Pattern SAFE_LITERAL = Pattern.compile("[A-Z0-9\\p{IsHan}\\-_/ .()\\uff08\\uff09]*");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final NumberPatternRenderer renderer;

    public BusinessNumberingConfigurationService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                                  NumberPatternRenderer renderer) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.renderer = renderer;
    }

    public record RuleWrite(String ruleCode, String resourceType, String name, String prefix,
                            String prefixPosition, String datePattern, String suffix, String suffixPosition,
                            int sequenceWidth, long initialSequence, long expectedVersion) {}
    public record RuleView(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber, String ruleCode,
                           String resourceType, String name, String prefix, String prefixPosition,
                           String datePattern, String suffix, String suffixPosition, int sequenceWidth,
                           long initialSequence, long nextSequence, String nextPreview, String state, long version,
                           OffsetDateTime publishedAt, OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record TemplateWrite(String code, String templateType, long expectedVersion) {}
    public record TemplateView(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber, String code,
                               String templateType, String state, long version, OffsetDateTime publishedAt,
                               OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record Summary(long published, long draft, long history, long series) {}

    public List<RuleView> rules(String query, String resourceType, String state) {
        AuthenticatedUser user = actor.require();
        String needle = clean(query, 120);
        String pattern = "%" + escapeLike(needle) + "%";
        String resource = upper(resourceType);
        String normalizedState = upper(state);
        if (!resource.isEmpty() && !RESOURCE_TYPES.contains(resource)) invalid("NUMBERING_RESOURCE_INVALID", "编号规则类型无效");
        if (!normalizedState.isEmpty() && !Set.of("DRAFT", "PUBLISHED", "RETIRED", "DISCARDED").contains(normalizedState)) {
            invalid("NUMBERING_STATE_INVALID", "编号规则状态无效");
        }
        return jdbc.sql(ruleSelect() + """
                WHERE rule.organization_id=:organization
                  AND (:query='' OR rule.rule_code ILIKE :pattern ESCAPE '\\' OR rule.name ILIKE :pattern ESCAPE '\\')
                  AND (:resource='' OR rule.resource_type=:resource)
                  AND (:state='' OR rule.state=:state)
                  AND (:state<>'' OR rule.state IN ('DRAFT','PUBLISHED'))
                ORDER BY CASE rule.state WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
                  rule.resource_type,rule.revision_number DESC
                """).param("organization", user.organizationId()).param("query", needle).param("pattern", pattern)
                .param("resource", resource).param("state", normalizedState).query(this::mapRule).list().stream()
                .map(this::deriveRule).toList();
    }

    public Summary ruleSummary() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE state='PUBLISHED') published,
                  count(*) FILTER (WHERE state='DRAFT') draft,
                  count(*) FILTER (WHERE state IN ('RETIRED','DISCARDED')) history,
                  count(DISTINCT series_id) series
                FROM business_numbering_rule WHERE organization_id=:organization
                """).param("organization", actor.require().organizationId()).query(this::mapSummary).single();
    }

    public RuleView rule(UUID id) { return deriveRule(requireRule(id)); }

    public List<RuleView> ruleRevisions(UUID id) {
        RuleView current = rule(id);
        return jdbc.sql(ruleSelect() + " WHERE rule.organization_id=:organization AND rule.series_id=:series "
                        + "ORDER BY rule.revision_number DESC")
                .param("organization", actor.require().organizationId()).param("series", current.seriesId())
                .query(this::mapRule).list().stream().map(this::deriveRule).toList();
    }

    @Transactional
    public RuleView createRule(RuleWrite command) {
        AuthenticatedUser user = actor.require();
        ValidatedRule value = validateRule(command);
        lockResource(user.organizationId(), value.resourceType());
        lockResource(user.organizationId(), "RULE_CODE:" + value.ruleCode());
        ensureNoCurrentRule(user.organizationId(), value.resourceType(), null);
        ensureRuleCodeAvailable(user.organizationId(), value.ruleCode(), null);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO business_numbering_rule(id,organization_id,series_id,revision_number,rule_code,
                  resource_type,name,prefix,prefix_position,date_pattern,suffix,suffix_position,sequence_width,
                  initial_sequence,state,source_system,source_record_id,source_path,created_by,updated_by)
                VALUES (:id,:organization,:id,1,:code,:resource,:name,:prefix,:prefixPosition,:datePattern,
                  :suffix,:suffixPosition,:width,:initial,'DRAFT','lims',:sourceRecord,
                  '系统设置/基础数据/编号配置规则',:actor,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("code", value.ruleCode())
                .param("resource", value.resourceType()).param("name", value.name()).param("prefix", value.prefix())
                .param("prefixPosition", value.prefixPosition()).param("datePattern", value.datePattern())
                .param("suffix", value.suffix()).param("suffixPosition", value.suffixPosition())
                .param("width", value.sequenceWidth()).param("initial", value.initialSequence())
                .param("sourceRecord", "local:" + id).param("actor", user.id()).update();
        RuleView created = rule(id);
        audit.record("BUSINESS_NUMBERING_RULE_CREATED", "BUSINESS_NUMBERING_RULE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public RuleView updateRule(UUID id, RuleWrite command) {
        RuleView before = rule(id);
        if (!before.state().equals("DRAFT")) invalid(HttpStatus.CONFLICT, "NUMBERING_RULE_IMMUTABLE", "只有草稿编号规则可以修改");
        if (before.version() != command.expectedVersion()) throw conflict("编号规则已被其他用户更新", before.version());
        ValidatedRule value = validateRule(command);
        if (!before.resourceType().equals(value.resourceType())) {
            invalid(HttpStatus.UNPROCESSABLE_ENTITY, "NUMBERING_RESOURCE_IMMUTABLE", "编号规则类型创建后不可变更");
        }
        lockResource(actor.require().organizationId(), "RULE_CODE:" + value.ruleCode());
        ensureRuleCodeAvailable(actor.require().organizationId(), value.ruleCode(), before.seriesId());
        int changed = jdbc.sql("""
                UPDATE business_numbering_rule SET rule_code=:code,name=:name,prefix=:prefix,
                  prefix_position=:prefixPosition,date_pattern=:datePattern,suffix=:suffix,
                  suffix_position=:suffixPosition,sequence_width=:width,initial_sequence=:initial,
                  lock_version=lock_version+1,updated_by=:actor,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND lock_version=:version
                """).param("code", value.ruleCode()).param("name", value.name()).param("prefix", value.prefix())
                .param("prefixPosition", value.prefixPosition()).param("datePattern", value.datePattern())
                .param("suffix", value.suffix()).param("suffixPosition", value.suffixPosition())
                .param("width", value.sequenceWidth()).param("initial", value.initialSequence())
                .param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw conflict("编号规则已被其他用户更新", rule(id).version());
        RuleView after = rule(id);
        audit.record("BUSINESS_NUMBERING_RULE_UPDATED", "BUSINESS_NUMBERING_RULE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public RuleView reviseRule(UUID id, long expectedVersion) {
        RuleView before = rule(id);
        if (before.version() != expectedVersion) throw conflict("编号规则已被其他用户更新", before.version());
        if (!Set.of("PUBLISHED", "RETIRED").contains(before.state())) {
            invalid(HttpStatus.CONFLICT, "NUMBERING_REVISION_INVALID", "只有已发布或已停用规则可以创建修订");
        }
        lockSeries(before.seriesId());
        RuleView locked = rule(id);
        if (locked.version() != expectedVersion) throw conflict("编号规则已被其他用户更新", locked.version());
        boolean draftExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM business_numbering_rule WHERE series_id=:series AND state='DRAFT')")
                .param("series", before.seriesId()).query(Boolean.class).single();
        if (draftExists) invalid(HttpStatus.CONFLICT, "NUMBERING_DRAFT_EXISTS", "该编号规则已有修订草稿");
        int revision = jdbc.sql("SELECT max(revision_number)+1 FROM business_numbering_rule WHERE series_id=:series")
                .param("series", before.seriesId()).query(Integer.class).single();
        UUID createdId = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO business_numbering_rule(id,organization_id,series_id,predecessor_id,revision_number,
                  rule_code,resource_type,name,prefix,prefix_position,date_pattern,suffix,suffix_position,
                  sequence_width,initial_sequence,state,lock_version,source_system,source_record_id,source_path,
                  created_by,updated_by)
                SELECT :id,organization_id,series_id,id,:revision,rule_code,resource_type,name,prefix,
                  prefix_position,date_pattern,suffix,suffix_position,sequence_width,initial_sequence,'DRAFT',
                  :version,'lims',:sourceRecord,'系统设置/基础数据/编号配置规则',:actor,:actor
                FROM business_numbering_rule WHERE id=:source AND organization_id=:organization
                """).param("id", createdId).param("revision", revision).param("version", (long) revision - 1)
                .param("sourceRecord", "series:" + before.seriesId()).param("actor", user.id()).param("source", id)
                .param("organization", user.organizationId()).update();
        RuleView created = rule(createdId);
        audit.record("BUSINESS_NUMBERING_RULE_REVISED", "BUSINESS_NUMBERING_RULE", createdId, before, created,
                "CONTROLLED_REVISION", "从 R" + before.revisionNumber() + " 创建修订");
        return created;
    }

    @Transactional
    public TransitionResult transitionRule(UUID id, TransitionCommand command) {
        RuleView before = rule(id);
        if (before.version() != command.expectedVersion()) throw conflict("编号规则已被其他用户更新", before.version());
        String action = upper(command.action());
        String next = switch (before.state()) {
            case "DRAFT" -> action.equals("PUBLISH") ? "PUBLISHED"
                    : action.equals("DISCARD") ? "DISCARDED" : invalidTransition(before.state(), action);
            case "PUBLISHED" -> action.equals("RETIRE") ? "RETIRED" : invalidTransition(before.state(), action);
            default -> invalidTransition(before.state(), action);
        };
        if (!action.equals("PUBLISH") && clean(command.comment(), 1000).isEmpty()) {
            invalid("NUMBERING_REASON_REQUIRED", "停用或废弃编号规则必须填写原因");
        }
        lockSeries(before.seriesId());
        lockResource(actor.require().organizationId(), before.resourceType());
        lockResource(actor.require().organizationId(), "RULE_CODE:" + before.ruleCode());
        RuleView locked = rule(id);
        if (locked.version() != command.expectedVersion()) throw conflict("编号规则已被其他用户更新", locked.version());
        if (action.equals("PUBLISH")) {
            ensureNoPublishedOtherSeries(actor.require().organizationId(), before.resourceType(), before.seriesId());
            jdbc.sql("""
                    UPDATE business_numbering_rule SET state='RETIRED',lock_version=lock_version+1,
                      updated_by=:actor,updated_at=now()
                    WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("actor", actor.require().id()).param("series", before.seriesId()).param("id", id).update();
        }
        int changed = jdbc.sql("""
                UPDATE business_numbering_rule SET state=:state,lock_version=lock_version+1,updated_by=:actor,
                  updated_at=now(),published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END
                WHERE id=:id AND organization_id=:organization AND lock_version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", locked.version()).update();
        if (changed == 0) throw conflict("编号规则已被其他用户更新", rule(id).version());
        RuleView after = rule(id);
        audit.record("BUSINESS_NUMBERING_RULE_" + action, "BUSINESS_NUMBERING_RULE", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    @Transactional
    public RuleView resetSequence(UUID id, long expectedVersion, long nextSequence, String reasonCode, String comment) {
        RuleView before = rule(id);
        if (!before.state().equals("PUBLISHED")) invalid(HttpStatus.CONFLICT, "NUMBERING_RESET_STATE_INVALID", "只有已发布编号规则可以重置流水号");
        if (before.version() != expectedVersion) throw conflict("编号规则已被其他用户更新", before.version());
        if (nextSequence < 1 || nextSequence > maxSequence(before.sequenceWidth())) {
            invalid("NUMBERING_SEQUENCE_INVALID", "下一份流水号超出当前位数可容纳范围");
        }
        if (clean(comment, 1000).isEmpty()) invalid("NUMBERING_REASON_REQUIRED", "重置流水号必须填写原因");
        int changed = jdbc.sql("""
                UPDATE business_numbering_rule SET lock_version=lock_version+1,updated_by=:actor,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='PUBLISHED' AND lock_version=:version
                """).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", expectedVersion).update();
        if (changed == 0) throw conflict("编号规则已被其他用户更新", rule(id).version());
        jdbc.sql("""
                INSERT INTO business_number_sequence(rule_series_id,current_value,lock_version,reset_count,
                  last_reset_by,last_reset_at)
                VALUES (:series,:current,1,1,:actor,now())
                ON CONFLICT(rule_series_id) DO UPDATE SET current_value=EXCLUDED.current_value,
                  lock_version=business_number_sequence.lock_version+1,
                  reset_count=business_number_sequence.reset_count+1,last_reset_by=EXCLUDED.last_reset_by,
                  last_reset_at=now(),updated_at=now()
                """).param("series", before.seriesId()).param("current", nextSequence - 1)
                .param("actor", actor.require().id()).update();
        RuleView after = rule(id);
        audit.record("BUSINESS_NUMBERING_SEQUENCE_RESET", "BUSINESS_NUMBERING_RULE", id, before, after,
                clean(reasonCode, 80).isEmpty() ? "MANUAL_SEQUENCE_RESET" : reasonCode, comment);
        return after;
    }

    public String preview(RuleWrite command, long sequence, LocalDate date) {
        ValidatedRule value = validateRule(command);
        if (sequence < 1 || sequence > maxSequence(value.sequenceWidth())) {
            invalid("NUMBERING_SEQUENCE_INVALID", "预览流水号超出当前位数可容纳范围");
        }
        return renderer.render(pattern(value.prefix(), value.prefixPosition(), value.datePattern(), value.suffix(),
                value.suffixPosition(), value.sequenceWidth()), "", date == null ? LocalDate.now() : date, sequence);
    }

    public List<TemplateView> templates(String query, String templateType, String state) {
        String needle = clean(query, 120);
        String pattern = "%" + escapeLike(needle) + "%";
        String type = upper(templateType);
        String normalizedState = upper(state);
        if (!type.isEmpty() && !TEMPLATE_TYPES.contains(type)) invalid("TEMPLATE_NUMBERING_TYPE_INVALID", "模板编号类型无效");
        if (!normalizedState.isEmpty() && !Set.of("DRAFT", "PUBLISHED", "RETIRED", "DISCARDED").contains(normalizedState)) {
            invalid("TEMPLATE_NUMBERING_STATE_INVALID", "模板编号状态无效");
        }
        return jdbc.sql(templateSelect() + """
                WHERE rule.organization_id=:organization
                  AND (:query='' OR rule.code ILIKE :pattern ESCAPE '\\')
                  AND (:type='' OR rule.template_type=:type)
                  AND (:state='' OR rule.state=:state)
                  AND (:state<>'' OR rule.state IN ('DRAFT','PUBLISHED'))
                ORDER BY CASE rule.state WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
                  rule.template_type,rule.revision_number DESC
                """).param("organization", actor.require().organizationId()).param("query", needle)
                .param("pattern", pattern).param("type", type).param("state", normalizedState)
                .query(this::mapTemplate).list();
    }

    public Summary templateSummary() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE state='PUBLISHED') published,
                  count(*) FILTER (WHERE state='DRAFT') draft,
                  count(*) FILTER (WHERE state IN ('RETIRED','DISCARDED')) history,
                  count(DISTINCT series_id) series
                FROM template_numbering_rule WHERE organization_id=:organization
                """).param("organization", actor.require().organizationId()).query(this::mapSummary).single();
    }

    public TemplateView template(UUID id) { return requireTemplate(id); }

    public List<TemplateView> templateRevisions(UUID id) {
        TemplateView current = template(id);
        return jdbc.sql(templateSelect() + " WHERE rule.organization_id=:organization AND rule.series_id=:series "
                        + "ORDER BY rule.revision_number DESC")
                .param("organization", actor.require().organizationId()).param("series", current.seriesId())
                .query(this::mapTemplate).list();
    }

    @Transactional
    public TemplateView createTemplate(TemplateWrite command) {
        AuthenticatedUser user = actor.require();
        ValidatedTemplate value = validateTemplate(command);
        lockResource(user.organizationId(), "TEMPLATE:" + value.templateType());
        lockResource(user.organizationId(), "TEMPLATE_CODE:" + value.code().toUpperCase(Locale.ROOT));
        ensureNoCurrentTemplate(user.organizationId(), value.templateType(), null);
        ensureTemplateCodeAvailable(user.organizationId(), value.code(), null);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO template_numbering_rule(id,organization_id,series_id,revision_number,code,
                  template_type,state,source_system,source_record_id,source_path,created_by,updated_by)
                VALUES (:id,:organization,:id,1,:code,:type,'DRAFT','lims',:sourceRecord,
                  '系统设置/基础数据/模板编号配置',:actor,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("code", value.code())
                .param("type", value.templateType()).param("sourceRecord", "local:" + id)
                .param("actor", user.id()).update();
        TemplateView created = template(id);
        audit.record("TEMPLATE_NUMBERING_RULE_CREATED", "TEMPLATE_NUMBERING_RULE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public TemplateView updateTemplate(UUID id, TemplateWrite command) {
        TemplateView before = template(id);
        if (!before.state().equals("DRAFT")) invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_IMMUTABLE", "只有草稿模板编号可以修改");
        if (before.version() != command.expectedVersion()) throw conflict("模板编号已被其他用户更新", before.version());
        ValidatedTemplate value = validateTemplate(command);
        if (!before.templateType().equals(value.templateType())) {
            invalid(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NUMBERING_TYPE_IMMUTABLE", "模板编号类型创建后不可变更");
        }
        lockResource(actor.require().organizationId(), "TEMPLATE_CODE:" + value.code().toUpperCase(Locale.ROOT));
        ensureTemplateCodeAvailable(actor.require().organizationId(), value.code(), before.seriesId());
        int changed = jdbc.sql("""
                UPDATE template_numbering_rule SET code=:code,lock_version=lock_version+1,
                  updated_by=:actor,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND lock_version=:version
                """).param("code", value.code()).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw conflict("模板编号已被其他用户更新", template(id).version());
        TemplateView after = template(id);
        audit.record("TEMPLATE_NUMBERING_RULE_UPDATED", "TEMPLATE_NUMBERING_RULE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TemplateView reviseTemplate(UUID id, long expectedVersion) {
        TemplateView before = template(id);
        if (before.version() != expectedVersion) throw conflict("模板编号已被其他用户更新", before.version());
        if (!Set.of("PUBLISHED", "RETIRED").contains(before.state())) {
            invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_REVISION_INVALID", "只有已发布或已停用模板编号可以创建修订");
        }
        lockSeries(before.seriesId());
        TemplateView locked = template(id);
        if (locked.version() != expectedVersion) throw conflict("模板编号已被其他用户更新", locked.version());
        boolean draftExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM template_numbering_rule WHERE series_id=:series AND state='DRAFT')")
                .param("series", before.seriesId()).query(Boolean.class).single();
        if (draftExists) invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_DRAFT_EXISTS", "该模板编号已有修订草稿");
        int revision = jdbc.sql("SELECT max(revision_number)+1 FROM template_numbering_rule WHERE series_id=:series")
                .param("series", before.seriesId()).query(Integer.class).single();
        UUID createdId = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO template_numbering_rule(id,organization_id,series_id,predecessor_id,revision_number,
                  code,template_type,state,lock_version,source_system,source_record_id,source_path,created_by,updated_by)
                SELECT :id,organization_id,series_id,id,:revision,code,template_type,'DRAFT',:version,'lims',
                  :sourceRecord,'系统设置/基础数据/模板编号配置',:actor,:actor
                FROM template_numbering_rule WHERE id=:source AND organization_id=:organization
                """).param("id", createdId).param("revision", revision).param("version", (long) revision - 1)
                .param("sourceRecord", "series:" + before.seriesId()).param("actor", user.id()).param("source", id)
                .param("organization", user.organizationId()).update();
        TemplateView created = template(createdId);
        audit.record("TEMPLATE_NUMBERING_RULE_REVISED", "TEMPLATE_NUMBERING_RULE", createdId, before, created,
                "CONTROLLED_REVISION", "从 R" + before.revisionNumber() + " 创建修订");
        return created;
    }

    @Transactional
    public TransitionResult transitionTemplate(UUID id, TransitionCommand command) {
        TemplateView before = template(id);
        if (before.version() != command.expectedVersion()) throw conflict("模板编号已被其他用户更新", before.version());
        String action = upper(command.action());
        String next = switch (before.state()) {
            case "DRAFT" -> action.equals("PUBLISH") ? "PUBLISHED"
                    : action.equals("DISCARD") ? "DISCARDED" : invalidTransition(before.state(), action);
            case "PUBLISHED" -> action.equals("RETIRE") ? "RETIRED" : invalidTransition(before.state(), action);
            default -> invalidTransition(before.state(), action);
        };
        if (!action.equals("PUBLISH") && clean(command.comment(), 1000).isEmpty()) {
            invalid("TEMPLATE_NUMBERING_REASON_REQUIRED", "停用或废弃模板编号必须填写原因");
        }
        lockSeries(before.seriesId());
        lockResource(actor.require().organizationId(), "TEMPLATE:" + before.templateType());
        lockResource(actor.require().organizationId(), "TEMPLATE_CODE:" + before.code().toUpperCase(Locale.ROOT));
        TemplateView locked = template(id);
        if (locked.version() != command.expectedVersion()) throw conflict("模板编号已被其他用户更新", locked.version());
        if (action.equals("PUBLISH")) {
            ensureNoPublishedTemplateOtherSeries(actor.require().organizationId(), before.templateType(), before.seriesId());
            jdbc.sql("""
                    UPDATE template_numbering_rule SET state='RETIRED',lock_version=lock_version+1,
                      updated_by=:actor,updated_at=now()
                    WHERE series_id=:series AND state='PUBLISHED' AND id<>:id
                    """).param("actor", actor.require().id()).param("series", before.seriesId()).param("id", id).update();
        }
        int changed = jdbc.sql("""
                UPDATE template_numbering_rule SET state=:state,lock_version=lock_version+1,updated_by=:actor,
                  updated_at=now(),published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END
                WHERE id=:id AND organization_id=:organization AND lock_version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", locked.version()).update();
        if (changed == 0) throw conflict("模板编号已被其他用户更新", template(id).version());
        TemplateView after = template(id);
        audit.record("TEMPLATE_NUMBERING_RULE_" + action, "TEMPLATE_NUMBERING_RULE", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private RuleView requireRule(UUID id) {
        return jdbc.sql(ruleSelect() + " WHERE rule.id=:id AND rule.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::mapRule).optional()
                .orElseThrow(() -> ApiException.notFound("BUSINESS_NUMBERING_RULE_NOT_FOUND", "编号规则不存在"));
    }

    private TemplateView requireTemplate(UUID id) {
        return jdbc.sql(templateSelect() + " WHERE rule.id=:id AND rule.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::mapTemplate).optional()
                .orElseThrow(() -> ApiException.notFound("TEMPLATE_NUMBERING_RULE_NOT_FOUND", "模板编号不存在"));
    }

    private RuleView deriveRule(RuleView raw) {
        long next = raw.nextSequence();
        String preview;
        try {
            preview = renderer.render(pattern(raw.prefix(), raw.prefixPosition(), raw.datePattern(), raw.suffix(),
                    raw.suffixPosition(), raw.sequenceWidth()), "", LocalDate.now(), next);
        } catch (ApiException exhausted) { preview = "流水号已用尽"; }
        return new RuleView(raw.id(), raw.seriesId(), raw.predecessorId(), raw.revisionNumber(), raw.ruleCode(),
                raw.resourceType(), raw.name(), raw.prefix(), raw.prefixPosition(), raw.datePattern(), raw.suffix(),
                raw.suffixPosition(), raw.sequenceWidth(), raw.initialSequence(), next, preview, raw.state(),
                raw.version(), raw.publishedAt(), raw.updatedAt(), allowed(raw.state(), true));
    }

    private RuleView mapRule(ResultSet rs, int row) throws SQLException {
        return new RuleView(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), rs.getString("rule_code"),
                rs.getString("resource_type"), rs.getString("name"), rs.getString("prefix"),
                rs.getString("prefix_position"), rs.getString("date_pattern"), rs.getString("suffix"),
                rs.getString("suffix_position"), rs.getInt("sequence_width"), rs.getLong("initial_sequence"),
                rs.getLong("next_sequence"), "", rs.getString("state"), rs.getLong("lock_version"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), List.of());
    }

    private TemplateView mapTemplate(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new TemplateView(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), rs.getString("code"),
                rs.getString("template_type"), state, rs.getLong("lock_version"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                allowed(state, false));
    }

    private Summary mapSummary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getLong("published"), rs.getLong("draft"), rs.getLong("history"),
                rs.getLong("series"));
    }

    private String ruleSelect() {
        return """
                SELECT rule.*,COALESCE(sequence.current_value+1,rule.initial_sequence) next_sequence
                FROM business_numbering_rule rule
                LEFT JOIN business_number_sequence sequence ON sequence.rule_series_id=rule.series_id
                """;
    }

    private String templateSelect() { return "SELECT rule.* FROM template_numbering_rule rule "; }

    private ValidatedRule validateRule(RuleWrite command) {
        String code = clean(command.ruleCode(), 6);
        String resource = upper(command.resourceType());
        String name = clean(command.name(), 200);
        String prefix = literal(command.prefix(), "前缀");
        String suffix = literal(command.suffix(), "后缀");
        String prefixPosition = upper(command.prefixPosition());
        String suffixPosition = upper(command.suffixPosition());
        String datePattern = command.datePattern() == null ? "" : command.datePattern().trim();
        if (!RULE_CODE.matcher(code).matches()) invalid("NUMBERING_RULE_CODE_INVALID", "流水号规则编号必须为 6 位数字或字母");
        if (!RESOURCE_TYPES.contains(resource)) invalid("NUMBERING_RESOURCE_INVALID", "编号规则类型无效");
        if (name.isEmpty()) invalid("NUMBERING_NAME_REQUIRED", "编号规则名称不能为空");
        if (!DATE_PATTERNS.contains(datePattern)) invalid("NUMBERING_DATE_PATTERN_INVALID", "日期规则无效");
        if (prefix.isEmpty()) prefixPosition = "";
        else if (!PREFIX_POSITIONS.contains(prefixPosition)) invalid("NUMBERING_PREFIX_POSITION_INVALID", "请选择前缀位置");
        if (suffix.isEmpty()) suffixPosition = "";
        else if (!SUFFIX_POSITIONS.contains(suffixPosition)) invalid("NUMBERING_SUFFIX_POSITION_INVALID", "请选择后缀位置");
        if (command.sequenceWidth() < 1 || command.sequenceWidth() > 12) invalid("NUMBERING_WIDTH_INVALID", "流水号位数必须在 1 到 12 之间");
        if (command.initialSequence() < 1 || command.initialSequence() > maxSequence(command.sequenceWidth())) {
            invalid("NUMBERING_SEQUENCE_INVALID", "下一份流水号超出当前位数可容纳范围");
        }
        return new ValidatedRule(code.toUpperCase(Locale.ROOT), resource, name, prefix, emptyToNull(prefixPosition),
                datePattern, suffix, emptyToNull(suffixPosition), command.sequenceWidth(), command.initialSequence());
    }

    private ValidatedTemplate validateTemplate(TemplateWrite command) {
        String code = clean(command.code(), 80);
        String type = upper(command.templateType());
        if (code.isEmpty()) invalid("TEMPLATE_NUMBERING_CODE_REQUIRED", "模板编号不能为空");
        if (!SAFE_LITERAL.matcher(code.toUpperCase(Locale.ROOT)).matches()) invalid("TEMPLATE_NUMBERING_CODE_INVALID", "模板编号包含不支持的字符");
        if (!TEMPLATE_TYPES.contains(type)) invalid("TEMPLATE_NUMBERING_TYPE_INVALID", "模板编号类型无效");
        return new ValidatedTemplate(code, type);
    }

    private String literal(String value, String label) {
        String clean = clean(value, 80);
        if (!SAFE_LITERAL.matcher(clean.toUpperCase(Locale.ROOT)).matches()) invalid("NUMBERING_LITERAL_INVALID", label + "包含不支持的字符");
        return clean;
    }

    static String pattern(String prefix, String prefixPosition, String datePattern, String suffix,
                          String suffixPosition, int sequenceWidth) {
        StringBuilder value = new StringBuilder();
        if ("NUMBER_START".equals(prefixPosition)) value.append(prefix);
        if (!"NONE".equals(datePattern)) value.append("{date:").append(datePattern).append('}');
        if ("AFTER_DATE".equals(suffixPosition)) value.append(suffix);
        if ("BEFORE_SEQUENCE".equals(prefixPosition)) value.append(prefix);
        value.append("{sequence:").append(sequenceWidth).append('}');
        if ("NUMBER_END".equals(suffixPosition)) value.append(suffix);
        return value.toString();
    }

    private void ensureNoCurrentRule(UUID organization, String resource, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM business_numbering_rule
                  WHERE organization_id=:organization AND resource_type=:resource
                    AND state IN ('DRAFT','PUBLISHED') AND (CAST(:series AS uuid) IS NULL OR series_id<>:series))
                """).param("organization", organization).param("resource", resource).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "NUMBERING_RESOURCE_EXISTS", "该规则类型已存在，请在现有版本上创建修订");
    }

    private void ensureNoPublishedOtherSeries(UUID organization, String resource, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM business_numbering_rule
                  WHERE organization_id=:organization AND resource_type=:resource
                    AND state='PUBLISHED' AND series_id<>:series)
                """).param("organization", organization).param("resource", resource).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "NUMBERING_RESOURCE_PUBLISHED", "该规则类型已有其他已发布系列");
    }

    private void ensureRuleCodeAvailable(UUID organization, String code, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM business_numbering_rule WHERE organization_id=:organization
                  AND lower(rule_code)=lower(:code) AND state IN ('DRAFT','PUBLISHED')
                  AND (CAST(:series AS uuid) IS NULL OR series_id<>:series))
                """).param("organization", organization).param("code", code).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "NUMBERING_RULE_CODE_EXISTS", "流水号规则编号已存在");
    }

    private void ensureNoCurrentTemplate(UUID organization, String type, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM template_numbering_rule WHERE organization_id=:organization
                  AND template_type=:type AND state IN ('DRAFT','PUBLISHED')
                  AND (CAST(:series AS uuid) IS NULL OR series_id<>:series))
                """).param("organization", organization).param("type", type).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_TYPE_EXISTS", "该模板类型已存在，请在现有版本上创建修订");
    }

    private void ensureNoPublishedTemplateOtherSeries(UUID organization, String type, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM template_numbering_rule WHERE organization_id=:organization
                  AND template_type=:type AND state='PUBLISHED' AND series_id<>:series)
                """).param("organization", organization).param("type", type).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_TYPE_PUBLISHED", "该模板类型已有其他已发布系列");
    }

    private void ensureTemplateCodeAvailable(UUID organization, String code, UUID series) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM template_numbering_rule WHERE organization_id=:organization
                  AND lower(code)=lower(:code) AND state IN ('DRAFT','PUBLISHED')
                  AND (CAST(:series AS uuid) IS NULL OR series_id<>:series))
                """).param("organization", organization).param("code", code).param("series", series)
                .query(Boolean.class).single();
        if (exists) invalid(HttpStatus.CONFLICT, "TEMPLATE_NUMBERING_CODE_EXISTS", "模板编号已存在");
    }

    private void lockSeries(UUID series) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:series AS text),0))")
                .param("series", series).query((rs, row) -> 1).single();
    }

    private void lockResource(UUID organization, String resource) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:organization AS text)||':'||:resource,0))")
                .param("organization", organization).param("resource", resource).query((rs, row) -> 1).single();
    }

    private List<String> allowed(String state, boolean reset) {
        return switch (state) {
            case "DRAFT" -> List.of("EDIT", "PUBLISH", "DISCARD");
            case "PUBLISHED" -> reset ? List.of("REVISE", "RETIRE", "RESET") : List.of("REVISE", "RETIRE");
            case "RETIRED" -> List.of("REVISE");
            default -> List.of();
        };
    }

    private String invalidTransition(String state, String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_NUMBERING_TRANSITION",
                "状态 %s 不允许动作 %s".formatted(state, action));
    }

    private ApiException conflict(String message, long currentVersion) {
        return ApiException.conflict("VERSION_CONFLICT", message, currentVersion);
    }

    private long maxSequence(int width) {
        long value = 1;
        for (int index = 0; index < width; index++) value *= 10;
        return value - 1;
    }

    private String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() > max) invalid("VALUE_TOO_LONG", "输入内容超过 " + max + " 个字符");
        return clean;
    }

    private void invalid(String code, String message) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
    private void invalid(HttpStatus status, String code, String message) { throw new ApiException(status, code, message); }

    private record ValidatedRule(String ruleCode, String resourceType, String name, String prefix,
                                 String prefixPosition, String datePattern, String suffix, String suffixPosition,
                                 int sequenceWidth, long initialSequence) {}
    private record ValidatedTemplate(String code, String templateType) {}
}
