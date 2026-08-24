package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
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

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/exception-approvals")
public class ExceptionApprovalController {
    private final ExceptionApprovalService service;
    private final IdempotencyService idempotency;

    public ExceptionApprovalController(ExceptionApprovalService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotBlank String resourceType, @NotNull UUID resourceId,
                                @NotBlank String blockerCode, @NotBlank @Size(max = 1000) String reason,
                                @NotNull @Future OffsetDateTime expiresAt) {}
    public record DecisionRequest(@NotBlank String action, @NotNull @PositiveOrZero Long expectedVersion,
                                  @Size(max = 1000) String comment) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ExceptionApprovalService.View> list(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(cursor, limit);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public ExceptionApprovalService.View create(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CreateRequest body) {
        ExceptionApprovalService.Create command = new ExceptionApprovalService.Create(body.resourceType(),
                body.resourceId(), body.blockerCode(), body.reason(), body.expiresAt());
        return idempotency.execute(key, "quality-exception-create", command, ExceptionApprovalService.View.class,
                () -> service.create(command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('QUALITY_EXCEPTION_APPROVE')")
    public TransitionResult decide(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody DecisionRequest body) {
        ExceptionApprovalService.Decision command = new ExceptionApprovalService.Decision(
                body.action(), body.expectedVersion(), body.comment());
        return idempotency.execute(key, "quality-exception-transition:" + id, command, TransitionResult.class,
                () -> service.decide(id, command));
    }
}
