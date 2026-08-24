package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/commission-change-requests")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class CommissionChangeRequestController {
    private final CommissionChangeRequestService service;
    private final IdempotencyService idempotency;

    public CommissionChangeRequestController(CommissionChangeRequestService service,
                                             IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotNull UUID commissionId,
                               @NotBlank @Size(max = 32) String requestType,
                               @NotBlank @Size(max = 32) String processingMethod,
                               @NotBlank @Size(max = 2000) String reason,
                               @Size(max = 120) String requestedContactName,
                               @Size(max = 40) String requestedContactPhone,
                               LocalDate requestedDueOn,
                               @Size(max = 4000) String requestedSummary,
                               UUID attachmentFileId,
                               @PositiveOrZero Long expectedVersion) {
        CommissionChangeRequestService.Write command() {
            return new CommissionChangeRequestService.Write(commissionId, requestType, processingMethod, reason,
                    requestedContactName, requestedContactPhone, requestedDueOn, requestedSummary,
                    attachmentFileId, expectedVersion);
        }
    }
    public record BatchTargetRequest(@NotNull UUID commissionId,
                                     @NotNull @PositiveOrZero Long expectedVersion) {
        CommissionChangeRequestService.BatchTarget command() {
            return new CommissionChangeRequestService.BatchTarget(commissionId, expectedVersion);
        }
    }
    public record BatchWriteRequest(@NotNull @Size(min = 1, max = 200)
                                    List<@Valid BatchTargetRequest> targets,
                                    @NotBlank @Size(max = 32) String requestType,
                                    @NotBlank @Size(max = 32) String processingMethod,
                                    @NotBlank @Size(max = 2000) String reason,
                                    @Size(max = 120) String requestedContactName,
                                    @Size(max = 40) String requestedContactPhone,
                                    LocalDate requestedDueOn,
                                    @Size(max = 4000) String requestedSummary,
                                    UUID attachmentFileId) {
        CommissionChangeRequestService.BatchWrite command() {
            return new CommissionChangeRequestService.BatchWrite(targets.stream()
                    .map(BatchTargetRequest::command).toList(), requestType, processingMethod, reason,
                    requestedContactName, requestedContactPhone, requestedDueOn, requestedSummary,
                    attachmentFileId);
        }
    }

    @GetMapping
    public CursorPage<CommissionChangeRequestService.View> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "") @Size(max = 240) String detectionProject,
            @RequestParam(defaultValue = "") @Size(max = 80) String commissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 20) String state,
            @RequestParam(defaultValue = "") @Size(max = 120) String applicant,
            @RequestParam(defaultValue = "") @Size(max = 120) String reviewer,
            @RequestParam(defaultValue = "") @Size(max = 32) String processingMethod,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(from, to, detectionProject, commissionNumber, state, applicant, reviewer,
                processingMethod, cursor, limit);
    }

    @GetMapping("/{id}")
    public CommissionChangeRequestService.View detail(@PathVariable UUID id) { return service.require(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionChangeRequestService.View create(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "commission-change-create", body.command(),
                CommissionChangeRequestService.View.class, () -> service.create(body.command()));
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionChangeRequestService.BatchCreated batchCreate(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BatchWriteRequest body) {
        return idempotency.execute(key, "commission-change-batch-create", body.command(),
                CommissionChangeRequestService.BatchCreated.class, () -> service.batchCreate(body.command()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionChangeRequestService.View update(@PathVariable UUID id,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "commission-change-update:" + id, body.command(),
                CommissionChangeRequestService.View.class, () -> service.update(id, body.command()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "commission-change-transition:" + id, body,
                TransitionResult.class, () -> service.transition(id, body));
    }
}
