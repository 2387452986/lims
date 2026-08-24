package com.lanchuan.lims.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class BackgroundJobController {
    private final BackgroundJobService service;
    private final IdempotencyService idempotency;

    public BackgroundJobController(BackgroundJobService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    public List<BackgroundJobService.View> list(@RequestParam(defaultValue = "") String resourceType,
                                                @RequestParam(required = false) UUID resourceId,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(resourceType, resourceId, limit);
    }

    @GetMapping("/{id}")
    public BackgroundJobService.View detail(@PathVariable UUID id) { return service.require(id); }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public BackgroundJobService.View transition(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "background-job-transition:" + id, command,
                BackgroundJobService.View.class, () -> service.transition(id, command));
    }
}
