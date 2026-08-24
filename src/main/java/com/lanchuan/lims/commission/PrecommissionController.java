package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/precommissions")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class PrecommissionController {
    private final PrecommissionService service;
    private final IdempotencyService idempotency;

    public PrecommissionController(PrecommissionService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record BatchTargetRequest(@NotNull UUID id, @PositiveOrZero long expectedVersion) {
        PrecommissionService.BatchTarget command() { return new PrecommissionService.BatchTarget(id, expectedVersion); }
    }
    public record BatchAcceptRequest(@NotNull @Size(min = 1, max = 200)
                                     List<@Valid @NotNull BatchTargetRequest> items,
                                     @Size(max = 80) String reasonCode,
                                     @Size(max = 1000) String comment) {}
    public record PrintRequest(@PositiveOrZero long expectedVersion) {}

    @GetMapping
    public CursorPage<PrecommissionService.View> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "") @Size(max = 32) String state,
            @RequestParam(defaultValue = "") @Size(max = 240) String detectionProject,
            @RequestParam(defaultValue = "") @Size(max = 80) String precommissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 80) String commissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String customer,
            @RequestParam(defaultValue = "") @Size(max = 120) String entruster,
            @RequestParam(defaultValue = "") @Size(max = 80) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String projectName,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(from, to, state, detectionProject, precommissionNumber, commissionNumber,
                customer, entruster, projectNumber, projectName, cursor, limit);
    }

    @GetMapping("/{id}")
    public PrecommissionService.View detail(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/scan-readiness")
    public PrecommissionService.ScanReadiness scanReadiness() { return service.scanReadiness(); }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "precommission-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @PostMapping("/batch-accept")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public PrecommissionService.BatchResult batchAccept(@RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody BatchAcceptRequest body) {
        List<PrecommissionService.BatchTarget> targets = body.items().stream()
                .map(BatchTargetRequest::command).toList();
        return idempotency.execute(key, "precommission-batch-accept", body,
                PrecommissionService.BatchResult.class,
                () -> service.batchAccept(targets, body.reasonCode(), body.comment()));
    }

    @PostMapping("/{id}/prints")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public PrecommissionService.View markPrinted(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody PrintRequest body) {
        return idempotency.execute(key, "precommission-print:" + id, body,
                PrecommissionService.View.class, () -> service.markPrinted(id, body.expectedVersion()));
    }
}
