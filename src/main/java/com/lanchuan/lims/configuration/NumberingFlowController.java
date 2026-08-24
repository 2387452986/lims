package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.report.ReportRevisionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/numbering-flows")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class NumberingFlowController {
    private final NumberingFlowService service;
    private final IdempotencyService idempotency;
    private final ReportRevisionPolicy reportRevisionPolicy;

    public NumberingFlowController(NumberingFlowService service, IdempotencyService idempotency,
                                   ReportRevisionPolicy reportRevisionPolicy) {
        this.service = service;
        this.idempotency = idempotency;
        this.reportRevisionPolicy = reportRevisionPolicy;
    }

    public record PatternRequest(@NotBlank @Size(max = 16) String resourceType,
                                 @NotBlank @Size(max = 32) String generationMode,
                                 @NotBlank @Size(max = 24) String sequenceScope,
                                 @Size(max = 80) String seed,
                                 @NotBlank @Size(max = 240) String pattern) {
        NumberingFlowService.PatternWrite command() {
            return new NumberingFlowService.PatternWrite(resourceType, generationMode, sequenceScope, seed, pattern);
        }
    }

    public record RuleRequest(@Size(max = 80) String code,
                              @NotBlank @Size(max = 240) String name,
                              boolean autoMatchProjectCode,
                              @NotNull @Size(min = 4, max = 4) List<@Valid PatternRequest> patterns,
                              @NotNull @Size(max = 1000) List<@NotNull UUID> projectIds,
                              @PositiveOrZero long expectedVersion) {
        NumberingFlowService.RuleWrite command() {
            return new NumberingFlowService.RuleWrite(code, name, autoMatchProjectCode,
                    patterns.stream().map(PatternRequest::command).toList(), projectIds, expectedVersion);
        }
    }

    public record TemplateRequest(@NotBlank @Size(max = 80) String code,
                                  @NotBlank @Size(max = 240) String name,
                                  @Size(max = 160) String prefix,
                                  @Size(max = 24) String prefixPosition,
                                  @NotBlank @Size(max = 16) String datePattern,
                                  @Size(max = 160) String suffix,
                                  @Size(max = 24) String suffixPosition,
                                  @Min(1) @Max(12) int sequenceWidth,
                                  @PositiveOrZero long expectedVersion) {
        NumberingFlowService.TemplateWrite command() {
            return new NumberingFlowService.TemplateWrite(code, name, prefix, prefixPosition, datePattern,
                    suffix, suffixPosition, sequenceWidth, expectedVersion);
        }
    }

    @GetMapping("/rules")
    public List<NumberingFlowService.RuleView> rules(
            @RequestParam(defaultValue = "") @Size(max = 120) String query) {
        return service.listRules(query);
    }

    @GetMapping("/rules/{id}")
    public NumberingFlowService.RuleView rule(@PathVariable UUID id) { return service.requireRule(id); }

    @GetMapping("/rules/{id}/revisions")
    public List<NumberingFlowService.RuleView> ruleRevisions(@PathVariable UUID id) {
        return service.ruleRevisions(id);
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.RuleView createRule(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody RuleRequest body) {
        NumberingFlowService.RuleWrite command = body.command();
        return idempotency.execute(key, "numbering-flow-rule-create", command,
                NumberingFlowService.RuleView.class, () -> service.createRule(command));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.RuleView updateRule(@PathVariable UUID id,
                                                    @Valid @RequestBody RuleRequest body) {
        return service.updateRule(id, body.command());
    }

    @PostMapping("/rules/{id}/revisions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.RuleView reviseRule(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "numbering-flow-rule-revise:" + id, id,
                NumberingFlowService.RuleView.class, () -> service.reviseRule(id));
    }

    @PostMapping("/rules/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionRule(@PathVariable UUID id,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "numbering-flow-rule-transition:" + id, command,
                TransitionResult.class, () -> service.transitionRule(id, command));
    }

    @GetMapping("/rules/{id}/preview")
    public Map<String, String> previewRule(@PathVariable UUID id,
                                           @RequestParam(defaultValue = "XM") @Size(max = 80) String symbol,
                                           @RequestParam(defaultValue = "1") @Min(1) long sequence,
                                           @RequestParam(required = false) LocalDate date) {
        return service.previewRule(id, symbol, date == null ? LocalDate.now() : date, sequence);
    }

    @GetMapping("/templates")
    public List<NumberingFlowService.TemplateView> templates(
            @RequestParam(defaultValue = "") @Size(max = 120) String query) {
        return service.listTemplates(query);
    }

    @GetMapping("/templates/{id}")
    public NumberingFlowService.TemplateView template(@PathVariable UUID id) {
        return service.requireTemplate(id);
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.TemplateView createTemplate(@RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody TemplateRequest body) {
        NumberingFlowService.TemplateWrite command = body.command();
        return idempotency.execute(key, "numbering-flow-template-create", command,
                NumberingFlowService.TemplateView.class, () -> service.createTemplate(command));
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.TemplateView updateTemplate(@PathVariable UUID id,
                                                            @Valid @RequestBody TemplateRequest body) {
        return service.updateTemplate(id, body.command());
    }

    @PostMapping("/templates/{id}/revisions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public NumberingFlowService.TemplateView reviseTemplate(@PathVariable UUID id,
                                                            @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "numbering-flow-template-revise:" + id, id,
                NumberingFlowService.TemplateView.class, () -> service.reviseTemplate(id));
    }

    @PostMapping("/templates/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionTemplate(@PathVariable UUID id,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "numbering-flow-template-transition:" + id, command,
                TransitionResult.class, () -> service.transitionTemplate(id, command));
    }

    @PostMapping("/templates/preview")
    public Map<String, String> previewTemplate(@Valid @RequestBody TemplateRequest body,
                                               @RequestParam(defaultValue = "1") @Min(1) long sequence,
                                               @RequestParam(required = false) LocalDate date) {
        return Map.of("preview", service.previewTemplate(body.command(),
                date == null ? LocalDate.now() : date, sequence));
    }

    @GetMapping("/report-revision-policy")
    public ReportRevisionPolicy.PolicyView reportRevisionPolicy() { return reportRevisionPolicy.view(); }
}
