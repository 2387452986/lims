package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/nonconformances")
public class NonconformanceController {
    private final NonconformanceService service;
    private final IdempotencyService idempotency;

    public NonconformanceController(NonconformanceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotBlank String sourceType, @NotNull UUID sourceId, @NotNull UUID commissionId,
                                @NotBlank String severity, @NotBlank @Size(max = 2000) String description) {}
    public record AddActionRequest(@NotBlank String actionType, @NotBlank @Size(max = 2000) String description,
                                   @NotNull UUID ownerId, @NotNull @FutureOrPresent LocalDate dueOn) {}
    public record ActionTransitionRequest(@NotBlank String action, @Size(max = 1000) String comment,
                                          List<@NotBlank @Size(max = 500) String> evidence) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<NonconformanceService.View> list() { return service.list(); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public NonconformanceService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/{id}/actions")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<NonconformanceService.ActionView> actions(@PathVariable UUID id) { return service.actions(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public NonconformanceService.View create(@RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody CreateRequest body) {
        NonconformanceService.Create command = new NonconformanceService.Create(body.sourceType(), body.sourceId(),
                body.commissionId(), body.severity(), body.description());
        return idempotency.execute(key, "nonconformance-create", command, NonconformanceService.View.class,
                () -> service.create(command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('QUALITY_EXCEPTION_APPROVE')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "nonconformance-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public NonconformanceService.ActionView addAction(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody AddActionRequest body) {
        NonconformanceService.AddAction command = new NonconformanceService.AddAction(body.actionType(),
                body.description(), body.ownerId(), body.dueOn());
        return idempotency.execute(key, "corrective-action-create:" + id, command,
                NonconformanceService.ActionView.class, () -> service.addAction(id, command));
    }

    @PostMapping("/actions/{id}/transitions")
    @PreAuthorize("(hasAuthority('BUSINESS_TRANSITION') and "
            + "(#body.action == 'START' or #body.action == 'COMPLETE' or #body.action == 'RESTART')) or "
            + "(hasAuthority('CAPA_VERIFY') and (#body.action == 'VERIFY' or #body.action == 'REJECT'))")
    public NonconformanceService.ActionView transitionAction(@PathVariable UUID id,
                                                             @RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody ActionTransitionRequest body) {
        NonconformanceService.ActionTransition command = new NonconformanceService.ActionTransition(
                body.action(), body.comment(), body.evidence());
        return idempotency.execute(key, "corrective-action-transition:" + id, command,
                NonconformanceService.ActionView.class, () -> service.transitionAction(id, command));
    }
}
