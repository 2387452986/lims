package com.lanchuan.lims.notification;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/notification-rules")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class NotificationRuleController {
    private final NotificationRuleService service;
    private final IdempotencyService idempotency;

    public NotificationRuleController(NotificationRuleService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping("/event-types")
    public List<NotificationRuleService.EventTypeView> eventTypes() {
        return service.eventTypes();
    }

    @GetMapping
    public NotificationRuleService.RulePage list(
            @RequestParam(defaultValue = "") @Size(max = 48) String eventType,
            @RequestParam(defaultValue = "ALL") @Size(max = 16) String state,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(eventType, state, page, pageSize);
    }

    @GetMapping("/{id}")
    public NotificationRuleService.RuleView detail(@PathVariable UUID id) {
        return service.require(id);
    }

    @GetMapping("/{id}/history")
    public List<NotificationRuleService.RuleView> history(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('NOTIFICATION_RULE_MANAGE')")
    public NotificationRuleService.RuleView create(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody NotificationRuleService.CreateCommand body) {
        return idempotency.execute(key, "notification-rule-create", body,
                NotificationRuleService.RuleView.class, () -> service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('NOTIFICATION_RULE_MANAGE')")
    public NotificationRuleService.RuleView update(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody NotificationRuleService.UpdateCommand body) {
        return idempotency.execute(key, "notification-rule-update:" + id, body,
                NotificationRuleService.RuleView.class, () -> service.update(id, body));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('NOTIFICATION_RULE_MANAGE')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "notification-rule-transition:" + id, body,
                TransitionResult.class, () -> service.transition(id, body));
    }
}
