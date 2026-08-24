package com.lanchuan.lims.notification;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationRuleService {
    private static final Set<String> STATES = Set.of("ALL", "ACTIVE", "RETIRED");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public NotificationRuleService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record EventTypeView(String code, int sourceKey, String label, String selectableRecipientKind,
                                String selectableRecipientLabel, String implicitRecipientDescription,
                                String triggerDescription, boolean configured, boolean active) {}

    public record RecipientView(UUID id, String employeeNumber, String name, String departmentName,
                                String organizationName, String position, String title, String accountState) {}

    public record RuleView(UUID id, UUID revisionId, int revisionNumber, String eventType, int sourceKey,
                           String messageType, String selectableRecipientKind, String selectableRecipientLabel,
                           String implicitRecipientDescription, String triggerDescription, int conditionDays,
                           String state, long version, List<RecipientView> primaryRecipients,
                           List<RecipientView> additionalRecipients, OffsetDateTime activatedAt,
                           OffsetDateTime retiredAt, String retiredReason, OffsetDateTime updatedAt,
                           List<String> allowedActions) {}

    public record RulePage(List<RuleView> items, long total, int page, int pageSize,
                           long activeCount, long retiredCount, long unconfiguredCount) {}

    public record CreateCommand(@NotBlank @Size(max = 48) String eventType,
                                @Min(0) @Max(3650) int conditionDays,
                                @Size(max = 100) List<@NotNull UUID> recipientPersonIds) {}
    public record UpdateCommand(@Min(0) @Max(3650) int conditionDays,
                                @Size(max = 100) List<@NotNull UUID> recipientPersonIds,
                                @Min(0) long expectedVersion) {}

    private record EventType(String code, int sourceKey, String label, String recipientKind,
                             String recipientLabel, String implicitDescription, String triggerDescription) {}

    private record RuleRow(UUID seriesId, UUID revisionId, int revisionNumber, String eventType, int sourceKey,
                           String label, String recipientKind, String recipientLabel, String implicitDescription,
                           String triggerDescription, int conditionDays, String state, long version,
                           OffsetDateTime activatedAt, OffsetDateTime retiredAt, String retiredReason,
                           OffsetDateTime updatedAt) {}

    public List<EventTypeView> eventTypes() {
        UUID organization = actor.require().organizationId();
        return jdbc.sql("""
                SELECT event.code,event.source_key,event.label,event.selectable_recipient_kind,
                  event.selectable_recipient_label,event.implicit_recipient_description,event.trigger_description,
                  series.id IS NOT NULL configured,active.id IS NOT NULL active
                FROM notification_event_type event
                LEFT JOIN notification_rule_series series
                  ON series.event_type=event.code AND series.organization_id=:organization
                LEFT JOIN notification_rule_revision active
                  ON active.series_id=series.id AND active.state='ACTIVE'
                ORDER BY event.sort_order
                """).param("organization", organization).query((rs, row) -> new EventTypeView(
                rs.getString("code"), rs.getInt("source_key"), rs.getString("label"),
                rs.getString("selectable_recipient_kind"), rs.getString("selectable_recipient_label"),
                rs.getString("implicit_recipient_description"), rs.getString("trigger_description"),
                rs.getBoolean("configured"), rs.getBoolean("active"))).list();
    }

    public RulePage list(String eventType, String state, int page, int pageSize) {
        if (page < 1) throw bad("NOTIFICATION_RULE_PAGE_INVALID", "页码必须从 1 开始");
        if (pageSize < 1 || pageSize > 100) throw bad("NOTIFICATION_RULE_PAGE_SIZE_INVALID", "单页数量须为 1–100");
        String normalizedState = normalize(state, "ALL");
        if (!STATES.contains(normalizedState)) throw bad("NOTIFICATION_RULE_STATE_INVALID", "消息规则状态筛选无效");
        String normalizedType = normalize(eventType, "");
        if (!normalizedType.isEmpty()) requireEventType(normalizedType);
        UUID organization = actor.require().organizationId();
        int offset = (page - 1) * pageSize;
        List<RuleRow> rows = jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (revision.series_id)
                    revision.id,revision.series_id,revision.revision_number,revision.condition_days,
                    revision.state,revision.lock_version,revision.activated_at,revision.retired_at,
                    revision.retired_reason,revision.updated_at
                  FROM notification_rule_revision revision
                  ORDER BY revision.series_id,revision.revision_number DESC
                )
                SELECT series.id series_id,latest.id revision_id,latest.revision_number,series.event_type,
                  event.source_key,event.label,event.selectable_recipient_kind,event.selectable_recipient_label,
                  event.implicit_recipient_description,event.trigger_description,latest.condition_days,
                  latest.state,latest.lock_version,latest.activated_at,latest.retired_at,latest.retired_reason,
                  latest.updated_at
                FROM notification_rule_series series
                JOIN latest ON latest.series_id=series.id
                JOIN notification_event_type event ON event.code=series.event_type
                WHERE series.organization_id=:organization
                  AND (:eventType='' OR series.event_type=:eventType)
                  AND (:state='ALL' OR latest.state=:state)
                ORDER BY event.sort_order
                LIMIT :limit OFFSET :offset
                """).param("organization", organization).param("eventType", normalizedType)
                .param("state", normalizedState).param("limit", pageSize).param("offset", offset)
                .query(this::mapRow).list();
        long total = jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) series_id,state
                  FROM notification_rule_revision ORDER BY series_id,revision_number DESC
                )
                SELECT count(*) FROM notification_rule_series series JOIN latest ON latest.series_id=series.id
                WHERE series.organization_id=:organization
                  AND (:eventType='' OR series.event_type=:eventType)
                  AND (:state='ALL' OR latest.state=:state)
                """).param("organization", organization).param("eventType", normalizedType)
                .param("state", normalizedState).query(Long.class).single();
        Map<UUID, List<RecipientView>> recipients = recipients(rows.stream().map(RuleRow::revisionId).toList(), null);
        List<RuleView> items = rows.stream().map(row -> view(row, recipients.getOrDefault(row.revisionId(), List.of())))
                .toList();
        Map<String, Long> counts = currentCounts(organization);
        return new RulePage(items, total, page, pageSize, counts.get("ACTIVE"), counts.get("RETIRED"),
                10 - counts.get("TOTAL"));
    }

    public RuleView require(UUID seriesId) {
        RuleRow row = latest(seriesId, false);
        return view(row, recipients(List.of(row.revisionId()), null).getOrDefault(row.revisionId(), List.of()));
    }

    public List<RuleView> history(UUID seriesId) {
        requireOwnedSeries(seriesId);
        List<RuleRow> rows = jdbc.sql("""
                SELECT series.id series_id,revision.id revision_id,revision.revision_number,series.event_type,
                  event.source_key,event.label,event.selectable_recipient_kind,event.selectable_recipient_label,
                  event.implicit_recipient_description,event.trigger_description,revision.condition_days,
                  revision.state,revision.lock_version,revision.activated_at,revision.retired_at,
                  revision.retired_reason,revision.updated_at
                FROM notification_rule_series series
                JOIN notification_rule_revision revision ON revision.series_id=series.id
                JOIN notification_event_type event ON event.code=series.event_type
                WHERE series.id=:series AND series.organization_id=:organization
                ORDER BY revision.revision_number DESC
                """).param("series", seriesId).param("organization", actor.require().organizationId())
                .query(this::mapRow).list();
        Map<UUID, List<RecipientView>> recipients = recipients(rows.stream().map(RuleRow::revisionId).toList(), null);
        return rows.stream().map(row -> view(row, recipients.getOrDefault(row.revisionId(), List.of()))).toList();
    }

    @Transactional
    public RuleView create(CreateCommand command) {
        EventType type = requireEventType(normalize(command.eventType(), ""));
        validateCondition(command.conditionDays());
        List<UUID> people = validateRecipients(command.recipientPersonIds(), type);
        AuthenticatedUser user = actor.require();
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM notification_rule_series
                  WHERE organization_id=:organization AND event_type=:eventType)
                """).param("organization", user.organizationId()).param("eventType", type.code())
                .query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "NOTIFICATION_RULE_EXISTS",
                "该消息类型已经建立规则，可修改或重新启用现有规则");
        UUID seriesId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification_rule_series(id,organization_id,event_type,created_by)
                VALUES (:id,:organization,:eventType,:actor)
                """).param("id", seriesId).param("organization", user.organizationId())
                .param("eventType", type.code()).param("actor", user.id()).update();
        insertRevision(revisionId, seriesId, 1, command.conditionDays(), user.id(), "lims", null, null);
        insertRecipients(revisionId, people, type.recipientKind());
        RuleView after = require(seriesId);
        audit.record("NOTIFICATION_RULE_CREATED", "NOTIFICATION_RULE", seriesId, null, auditView(after), null, null);
        return after;
    }

    @Transactional
    public RuleView update(UUID seriesId, UpdateCommand command) {
        validateCondition(command.conditionDays());
        RuleRow before = latest(seriesId, true);
        if (!before.state().equals("ACTIVE")) throw new ApiException(HttpStatus.CONFLICT,
                "NOTIFICATION_RULE_NOT_ACTIVE", "仅启用中的消息规则可以修改");
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "消息规则已被其他用户更新", before.version());
        EventType type = requireEventType(before.eventType());
        List<UUID> people = validateRecipients(command.recipientPersonIds(), type);
        retire(before, "由 R" + (before.revisionNumber() + 1) + " 受控替代");
        UUID revisionId = UUID.randomUUID();
        insertRevision(revisionId, seriesId, before.revisionNumber() + 1, command.conditionDays(),
                actor.require().id(), "lims", null, null);
        insertRecipients(revisionId, people, type.recipientKind());
        RuleView after = require(seriesId);
        audit.record("NOTIFICATION_RULE_REVISED", "NOTIFICATION_RULE", seriesId,
                auditView(view(before, recipients(List.of(before.revisionId()), null)
                        .getOrDefault(before.revisionId(), List.of()))), auditView(after), null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID seriesId, TransitionCommand command) {
        String action = normalize(command.action(), "");
        RuleRow before = latest(seriesId, true);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict(
                "VERSION_CONFLICT", "消息规则已被其他用户更新", before.version());
        String comment = command.comment() == null ? "" : command.comment().trim();
        RuleView beforeView = view(before, recipients(List.of(before.revisionId()), null)
                .getOrDefault(before.revisionId(), List.of()));
        RuleView after;
        if (action.equals("RETIRE")) {
            if (!before.state().equals("ACTIVE")) throw new ApiException(HttpStatus.CONFLICT,
                    "NOTIFICATION_RULE_TRANSITION_INVALID", "当前消息规则不能执行停用");
            if (comment.isEmpty()) throw bad("NOTIFICATION_RULE_RETIRE_REASON_REQUIRED", "停用消息规则必须填写原因");
            retire(before, comment);
            after = require(seriesId);
        } else if (action.equals("REACTIVATE")) {
            if (!before.state().equals("RETIRED")) throw new ApiException(HttpStatus.CONFLICT,
                    "NOTIFICATION_RULE_TRANSITION_INVALID", "当前消息规则不能重新启用");
            EventType type = requireEventType(before.eventType());
            List<UUID> people = recipientIds(before.revisionId());
            UUID revisionId = UUID.randomUUID();
            insertRevision(revisionId, seriesId, before.revisionNumber() + 1, before.conditionDays(),
                    actor.require().id(), "lims", null, null);
            insertRecipients(revisionId, people, type.recipientKind());
            after = require(seriesId);
        } else {
            throw bad("NOTIFICATION_RULE_ACTION_INVALID", "消息规则状态动作无效");
        }
        audit.record("NOTIFICATION_RULE_" + action, "NOTIFICATION_RULE", seriesId,
                auditView(beforeView), auditView(after), command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    private void retire(RuleRow before, String reason) {
        int changed = jdbc.sql("""
                UPDATE notification_rule_revision
                SET state='RETIRED',lock_version=lock_version+1,retired_by=:actor,retired_at=now(),
                  retired_reason=:reason,updated_at=now()
                WHERE id=:id AND state='ACTIVE' AND lock_version=:version
                """).param("actor", actor.require().id()).param("reason", reason)
                .param("id", before.revisionId()).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "消息规则已被其他用户更新",
                latest(before.seriesId(), false).version());
    }

    private void insertRevision(UUID id, UUID seriesId, int revision, int days, UUID userId,
                                String sourceSystem, String sourceRecordId, OffsetDateTime capturedAt) {
        jdbc.sql("""
                INSERT INTO notification_rule_revision(id,series_id,revision_number,condition_days,state,lock_version,
                  source_system,source_record_id,source_captured_at,created_by)
                VALUES (:id,:series,:revision,:days,'ACTIVE',:lockVersion,
                  :sourceSystem,:sourceRecordId,:capturedAt,:actor)
                """).param("id", id).param("series", seriesId).param("revision", revision).param("days", days)
                .param("lockVersion", revision - 1L)
                .param("sourceSystem", sourceSystem).param("sourceRecordId", sourceRecordId)
                .param("capturedAt", capturedAt).param("actor", userId).update();
    }

    private void insertRecipients(UUID revisionId, List<UUID> people, String kind) {
        for (int index = 0; index < people.size(); index++) {
            jdbc.sql("""
                    INSERT INTO notification_rule_recipient(revision_id,person_id,recipient_kind,sort_order)
                    VALUES (:revision,:person,:kind,:sortOrder)
                    """).param("revision", revisionId).param("person", people.get(index))
                    .param("kind", kind).param("sortOrder", index).update();
        }
    }

    private List<UUID> validateRecipients(List<UUID> requested, EventType type) {
        List<UUID> people = requested == null ? List.of() : List.copyOf(requested);
        if (people.size() > 100) throw bad("NOTIFICATION_RULE_RECIPIENT_LIMIT", "单条消息规则最多配置 100 名人员");
        if (people.stream().distinct().count() != people.size()) throw bad(
                "NOTIFICATION_RULE_RECIPIENT_DUPLICATE", "通知人员不能重复选择");
        if (type.recipientKind().equals("PRIMARY") && people.isEmpty()) throw bad(
                "NOTIFICATION_RULE_PRIMARY_REQUIRED", "该消息类型必须至少选择一名通知人员");
        if (people.isEmpty()) return people;
        AuthenticatedUser user = actor.require();
        long valid = jdbc.sql("""
                SELECT count(*) FROM person person_row
                JOIN department department_row ON department_row.id=person_row.department_id
                JOIN app_user account ON account.person_id=person_row.id
                WHERE person_row.id IN (:people) AND person_row.active AND account.enabled
                  AND account.organization_id=:organization AND department_row.organization_id=:organization
                  AND (:scope IN ('ALL','ORGANIZATION')
                    OR (:scope='DEPARTMENT' AND person_row.department_id=:department)
                    OR (:scope IN ('SELF','PROJECT') AND person_row.id=:actorPerson))
                """).param("people", people).param("organization", user.organizationId())
                .param("scope", user.dataScope()).param("department", user.departmentId())
                .param("actorPerson", user.personId()).query(Long.class).single();
        if (valid != people.size()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "NOTIFICATION_RULE_RECIPIENT_INVALID",
                "所选通知人员必须在当前数据范围内、处于启用状态并已开通可用账号");
        return people;
    }

    private RuleRow latest(UUID seriesId, boolean lock) {
        requireOwnedSeries(seriesId);
        return jdbc.sql("""
                SELECT series.id series_id,revision.id revision_id,revision.revision_number,series.event_type,
                  event.source_key,event.label,event.selectable_recipient_kind,event.selectable_recipient_label,
                  event.implicit_recipient_description,event.trigger_description,revision.condition_days,
                  revision.state,revision.lock_version,revision.activated_at,revision.retired_at,
                  revision.retired_reason,revision.updated_at
                FROM notification_rule_series series
                JOIN notification_rule_revision revision ON revision.series_id=series.id
                JOIN notification_event_type event ON event.code=series.event_type
                WHERE series.id=:series AND series.organization_id=:organization
                ORDER BY revision.revision_number DESC LIMIT 1
                """ + (lock ? " FOR UPDATE OF revision" : ""))
                .param("series", seriesId).param("organization", actor.require().organizationId())
                .query(this::mapRow).optional().orElseThrow(() -> ApiException.notFound(
                        "NOTIFICATION_RULE_NOT_FOUND", "消息规则不存在"));
    }

    private void requireOwnedSeries(UUID seriesId) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM notification_rule_series
                  WHERE id=:series AND organization_id=:organization)
                """).param("series", seriesId).param("organization", actor.require().organizationId())
                .query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("NOTIFICATION_RULE_NOT_FOUND", "消息规则不存在");
    }

    private EventType requireEventType(String code) {
        return jdbc.sql("""
                SELECT code,source_key,label,selectable_recipient_kind,selectable_recipient_label,
                  implicit_recipient_description,trigger_description
                FROM notification_event_type WHERE code=:code
                """).param("code", code).query((rs, row) -> new EventType(rs.getString("code"),
                        rs.getInt("source_key"), rs.getString("label"),
                        rs.getString("selectable_recipient_kind"), rs.getString("selectable_recipient_label"),
                        rs.getString("implicit_recipient_description"), rs.getString("trigger_description")))
                .optional().orElseThrow(() -> bad("NOTIFICATION_EVENT_TYPE_INVALID", "消息类型无效"));
    }

    private Map<UUID, List<RecipientView>> recipients(List<UUID> revisionIds, String kind) {
        if (revisionIds.isEmpty()) return Map.of();
        String kindFilter = kind == null ? "" : kind;
        Map<UUID, List<RecipientView>> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT recipient.revision_id,recipient.recipient_kind,recipient.sort_order,person_row.id,
                  person_row.employee_number,person_row.name,department_row.name department_name,
                  organization.name organization_name,person_row.position,person_row.title,
                  CASE WHEN account.id IS NULL THEN 'NO_ACCOUNT'
                    WHEN NOT account.enabled THEN 'DISABLED'
                    WHEN account.locked_until>now() THEN 'LOCKED' ELSE 'ENABLED' END account_state
                FROM notification_rule_recipient recipient
                JOIN person person_row ON person_row.id=recipient.person_id
                JOIN department department_row ON department_row.id=person_row.department_id
                JOIN organization ON organization.id=department_row.organization_id
                LEFT JOIN app_user account ON account.person_id=person_row.id
                WHERE recipient.revision_id IN (:revisions)
                  AND (:kind='' OR recipient.recipient_kind=:kind)
                ORDER BY recipient.revision_id,recipient.sort_order,person_row.id
                """).param("revisions", revisionIds).param("kind", kindFilter)
                .query((rs, row) -> {
                    UUID revisionId = rs.getObject("revision_id", UUID.class);
                    RecipientView person = new RecipientView(rs.getObject("id", UUID.class),
                            rs.getString("employee_number"), rs.getString("name"),
                            rs.getString("department_name"), rs.getString("organization_name"),
                            rs.getString("position"), rs.getString("title"), rs.getString("account_state"));
                    result.computeIfAbsent(revisionId, ignored -> new ArrayList<>()).add(person);
                    return person;
                }).list();
        return result;
    }

    private List<UUID> recipientIds(UUID revisionId) {
        return jdbc.sql("""
                SELECT person_id FROM notification_rule_recipient
                WHERE revision_id=:revision ORDER BY sort_order,person_id
                """).param("revision", revisionId).query(UUID.class).list();
    }

    private RuleRow mapRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RuleRow(rs.getObject("series_id", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getInt("revision_number"), rs.getString("event_type"), rs.getInt("source_key"),
                rs.getString("label"), rs.getString("selectable_recipient_kind"),
                rs.getString("selectable_recipient_label"), rs.getString("implicit_recipient_description"),
                rs.getString("trigger_description"), rs.getInt("condition_days"), rs.getString("state"),
                rs.getLong("lock_version"), rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("retired_at", OffsetDateTime.class), rs.getString("retired_reason"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RuleView view(RuleRow row, List<RecipientView> selected) {
        List<RecipientView> primary = row.recipientKind().equals("PRIMARY") ? selected : List.of();
        List<RecipientView> additional = row.recipientKind().equals("ADDITIONAL") ? selected : List.of();
        return new RuleView(row.seriesId(), row.revisionId(), row.revisionNumber(), row.eventType(),
                row.sourceKey(), row.label(), row.recipientKind(), row.recipientLabel(),
                row.implicitDescription(), row.triggerDescription(), row.conditionDays(), row.state(),
                row.version(), primary, additional, row.activatedAt(), row.retiredAt(), row.retiredReason(),
                row.updatedAt(), row.state().equals("ACTIVE") ? List.of("EDIT", "RETIRE") : List.of("REACTIVATE"));
    }

    private Map<String, Long> currentCounts(UUID organization) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (series_id) series_id,state
                  FROM notification_rule_revision ORDER BY series_id,revision_number DESC
                )
                SELECT latest.state,count(*) quantity
                FROM notification_rule_series series JOIN latest ON latest.series_id=series.id
                WHERE series.organization_id=:organization GROUP BY latest.state
                """).param("organization", organization).query((rs, row) -> {
                    result.put(rs.getString("state"), rs.getLong("quantity"));
                    return 0;
                }).list();
        result.putIfAbsent("ACTIVE", 0L);
        result.putIfAbsent("RETIRED", 0L);
        result.put("TOTAL", result.get("ACTIVE") + result.get("RETIRED"));
        return result;
    }

    private Map<String, Object> auditView(RuleView value) {
        return Map.of("seriesId", value.id(), "revisionId", value.revisionId(),
                "revisionNumber", value.revisionNumber(), "eventType", value.eventType(),
                "conditionDays", value.conditionDays(), "state", value.state(),
                "recipientIds", selected(value).stream().map(RecipientView::id).toList());
    }

    private List<RecipientView> selected(RuleView value) {
        return value.selectableRecipientKind().equals("PRIMARY")
                ? value.primaryRecipients() : value.additionalRecipients();
    }

    private void validateCondition(int value) {
        if (value < 0 || value > 3650) throw bad("NOTIFICATION_RULE_CONDITION_INVALID",
                "条件限制须为 0–3650 天");
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
