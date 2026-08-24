package com.lanchuan.lims.report;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/quality-summary-documents")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class QualitySummaryController {
    private final QualitySummaryService service;
    private final IdempotencyService idempotency;

    public QualitySummaryController(QualitySummaryService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotBlank @Size(max = 20) String documentType,
                               @NotBlank @Size(max = 80) String number,
                               @NotNull UUID projectId,
                               @NotNull UUID customerId,
                               @NotBlank @Size(max = 240) String sectionName,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportOn,
                               @NotEmpty @Size(max = 11) List<@NotBlank String> contentCodes,
                               @Size(max = 5000) String conclusion,
                               @Size(max = 20) List<UUID> attachmentFileIds,
                               @PositiveOrZero Long expectedVersion) {
        QualitySummaryService.Write command() {
            return new QualitySummaryService.Write(documentType, number, projectId, customerId,
                    sectionName, periodStart, periodEnd, reportOn, contentCodes, conclusion,
                    attachmentFileIds, expectedVersion);
        }
    }

    @GetMapping
    public CursorPage<QualitySummaryService.View> list(
            @RequestParam String documentType,
            @RequestParam(defaultValue = "") @Size(max = 80) String number,
            @RequestParam(defaultValue = "") @Size(max = 64) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String projectName,
            @RequestParam(defaultValue = "") @Size(max = 240) String customerName,
            @RequestParam(defaultValue = "") @Size(max = 40) String contentCode,
            @RequestParam(defaultValue = "") @Size(max = 20) String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportTo,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(new QualitySummaryService.Filters(documentType, number, projectNumber,
                projectName, customerName, contentCode, state, reportFrom, reportTo, cursor, limit));
    }

    @GetMapping("/options")
    public QualitySummaryService.Options options(@RequestParam(defaultValue = "") @Size(max = 100) String q) {
        return service.options(q);
    }

    @GetMapping("/{id}")
    public QualitySummaryService.View detail(@PathVariable UUID id) { return service.require(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public QualitySummaryService.View create(@RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "quality-summary-create", body,
                QualitySummaryService.View.class, () -> service.create(body.command()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public QualitySummaryService.View update(@PathVariable UUID id,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "quality-summary-update:" + id, body,
                QualitySummaryService.View.class, () -> service.update(id, body.command()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAnyAuthority('MODULE_WRITE','REPORT_REVIEW')")
    public TransitionResult transition(@PathVariable UUID id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "quality-summary-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body.action(), body.expectedVersion(), body.reasonCode(), body.comment()));
    }
}
