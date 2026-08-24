package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/numbering-configuration")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class BusinessNumberingConfigurationController {
    private final BusinessNumberingConfigurationService service;
    private final IdempotencyService idempotency;

    public BusinessNumberingConfigurationController(BusinessNumberingConfigurationService service,
                                                     IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record RuleRequest(@NotBlank @Size(min = 6, max = 6) String ruleCode,
                              @NotBlank @Size(max = 32) String resourceType,
                              @NotBlank @Size(max = 200) String name,
                              @Size(max = 80) String prefix,
                              @Size(max = 24) String prefixPosition,
                              @NotBlank @Size(max = 8) String datePattern,
                              @Size(max = 80) String suffix,
                              @Size(max = 24) String suffixPosition,
                              @Min(1) @Max(12) int sequenceWidth,
                              @Positive long initialSequence,
                              @PositiveOrZero long expectedVersion) {
        BusinessNumberingConfigurationService.RuleWrite command() {
            return new BusinessNumberingConfigurationService.RuleWrite(ruleCode, resourceType, name, prefix,
                    prefixPosition, datePattern, suffix, suffixPosition, sequenceWidth, initialSequence,
                    expectedVersion);
        }
    }

    public record VersionRequest(@PositiveOrZero long expectedVersion) {}
    public record ResetRequest(@PositiveOrZero long expectedVersion, @Positive long nextSequence,
                               @Size(max = 80) String reasonCode, @NotBlank @Size(max = 1000) String comment) {}
    public record TemplateRequest(@NotBlank @Size(max = 80) String code,
                                  @NotBlank @Size(max = 40) String templateType,
                                  @PositiveOrZero long expectedVersion) {
        BusinessNumberingConfigurationService.TemplateWrite command() {
            return new BusinessNumberingConfigurationService.TemplateWrite(code, templateType, expectedVersion);
        }
    }

    @GetMapping("/rules")
    public List<BusinessNumberingConfigurationService.RuleView> rules(
            @RequestParam(defaultValue = "") @Size(max = 120) String q,
            @RequestParam(defaultValue = "") @Size(max = 32) String resourceType,
            @RequestParam(defaultValue = "") @Size(max = 16) String state) {
        return service.rules(q, resourceType, state);
    }

    @GetMapping("/rules/summary")
    public BusinessNumberingConfigurationService.Summary ruleSummary() { return service.ruleSummary(); }

    @GetMapping("/rules/{id}")
    public BusinessNumberingConfigurationService.RuleView rule(@PathVariable UUID id) { return service.rule(id); }

    @GetMapping("/rules/{id}/revisions")
    public List<BusinessNumberingConfigurationService.RuleView> ruleRevisions(@PathVariable UUID id) {
        return service.ruleRevisions(id);
    }

    @PostMapping("/rules")
    public BusinessNumberingConfigurationService.RuleView createRule(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RuleRequest body) {
        var command = body.command();
        return idempotency.execute(key, "business-numbering-rule-create", command,
                BusinessNumberingConfigurationService.RuleView.class, () -> service.createRule(command));
    }

    @PutMapping("/rules/{id}")
    public BusinessNumberingConfigurationService.RuleView updateRule(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RuleRequest body) {
        var command = body.command();
        return idempotency.execute(key, "business-numbering-rule-update:" + id, command,
                BusinessNumberingConfigurationService.RuleView.class, () -> service.updateRule(id, command));
    }

    @PostMapping("/rules/{id}/revisions")
    public BusinessNumberingConfigurationService.RuleView reviseRule(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "business-numbering-rule-revise:" + id, body,
                BusinessNumberingConfigurationService.RuleView.class,
                () -> service.reviseRule(id, body.expectedVersion()));
    }

    @PostMapping("/rules/{id}/transitions")
    public TransitionResult transitionRule(@PathVariable UUID id,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "business-numbering-rule-transition:" + id, body,
                TransitionResult.class, () -> service.transitionRule(id, body));
    }

    @PostMapping("/rules/{id}/sequence-reset")
    public BusinessNumberingConfigurationService.RuleView resetRule(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ResetRequest body) {
        return idempotency.execute(key, "business-numbering-sequence-reset:" + id, body,
                BusinessNumberingConfigurationService.RuleView.class,
                () -> service.resetSequence(id, body.expectedVersion(), body.nextSequence(),
                        body.reasonCode(), body.comment()));
    }

    @PostMapping("/rules/preview")
    public Map<String, String> previewRule(@Valid @RequestBody RuleRequest body,
                                           @RequestParam(defaultValue = "1") @Positive long sequence,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("preview", service.preview(body.command(), sequence, date));
    }

    @GetMapping("/templates")
    public List<BusinessNumberingConfigurationService.TemplateView> templates(
            @RequestParam(defaultValue = "") @Size(max = 120) String q,
            @RequestParam(defaultValue = "") @Size(max = 40) String templateType,
            @RequestParam(defaultValue = "") @Size(max = 16) String state) {
        return service.templates(q, templateType, state);
    }

    @GetMapping("/templates/summary")
    public BusinessNumberingConfigurationService.Summary templateSummary() { return service.templateSummary(); }

    @GetMapping("/templates/{id}")
    public BusinessNumberingConfigurationService.TemplateView template(@PathVariable UUID id) {
        return service.template(id);
    }

    @GetMapping("/templates/{id}/revisions")
    public List<BusinessNumberingConfigurationService.TemplateView> templateRevisions(@PathVariable UUID id) {
        return service.templateRevisions(id);
    }

    @PostMapping("/templates")
    public BusinessNumberingConfigurationService.TemplateView createTemplate(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TemplateRequest body) {
        var command = body.command();
        return idempotency.execute(key, "template-numbering-rule-create", command,
                BusinessNumberingConfigurationService.TemplateView.class,
                () -> service.createTemplate(command));
    }

    @PutMapping("/templates/{id}")
    public BusinessNumberingConfigurationService.TemplateView updateTemplate(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TemplateRequest body) {
        var command = body.command();
        return idempotency.execute(key, "template-numbering-rule-update:" + id, command,
                BusinessNumberingConfigurationService.TemplateView.class,
                () -> service.updateTemplate(id, command));
    }

    @PostMapping("/templates/{id}/revisions")
    public BusinessNumberingConfigurationService.TemplateView reviseTemplate(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "template-numbering-rule-revise:" + id, body,
                BusinessNumberingConfigurationService.TemplateView.class,
                () -> service.reviseTemplate(id, body.expectedVersion()));
    }

    @PostMapping("/templates/{id}/transitions")
    public TransitionResult transitionTemplate(@PathVariable UUID id,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "template-numbering-rule-transition:" + id, body,
                TransitionResult.class, () -> service.transitionTemplate(id, body));
    }
}
