package com.lanchuan.lims.report;

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
import java.util.UUID;

@RestController
@RequestMapping("/api/concrete-strength-assessments")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class ConcreteStrengthAssessmentController {
    private final ConcreteStrengthAssessmentService service;
    private final IdempotencyService idempotency;

    public ConcreteStrengthAssessmentController(ConcreteStrengthAssessmentService service,
                                                IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotNull UUID projectId,
                               @NotNull UUID structurePartId,
                               @NotNull UUID detectionProjectRevisionId,
                               @NotNull UUID parameterId,
                               @NotNull UUID standardId,
                               @NotBlank @Size(max = 80) String designRequirement,
                               @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
                               @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
                               @NotBlank @Size(max = 80) String concreteQuantity,
                               @Size(max = 80) String standardDeviationAverageLevel,
                               @Size(max = 2000) String remark,
                               @PositiveOrZero Long expectedVersion) {
        ConcreteStrengthAssessmentService.Write command() {
            return new ConcreteStrengthAssessmentService.Write(projectId, structurePartId,
                    detectionProjectRevisionId, parameterId, standardId, designRequirement,
                    periodStart, periodEnd, concreteQuantity, standardDeviationAverageLevel,
                    remark, expectedVersion);
        }
    }

    @GetMapping
    public CursorPage<ConcreteStrengthAssessmentService.View> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo,
            @RequestParam(required = false) UUID detectionProjectRevisionId,
            @RequestParam(defaultValue = "") @Size(max = 80) String designRequirement,
            @RequestParam(defaultValue = "") @Size(max = 64) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String projectName,
            @RequestParam(defaultValue = "") @Size(max = 240) String structurePart,
            @RequestParam(defaultValue = "") @Size(max = 24) String state,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(new ConcreteStrengthAssessmentService.Filters(periodFrom, periodTo,
                detectionProjectRevisionId, designRequirement, projectNumber, projectName,
                structurePart, state, cursor, limit));
    }

    @GetMapping("/options")
    public ConcreteStrengthAssessmentService.Options options(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(required = false) UUID projectId) {
        return service.options(q, projectId);
    }

    @GetMapping("/{id}")
    public ConcreteStrengthAssessmentService.View detail(@PathVariable UUID id) {
        return service.require(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ConcreteStrengthAssessmentService.View create(@RequestHeader("Idempotency-Key") String key,
                                                         @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "concrete-strength-assessment-create", body,
                ConcreteStrengthAssessmentService.View.class, () -> service.create(body.command()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ConcreteStrengthAssessmentService.View update(@PathVariable UUID id,
                                                         @RequestHeader("Idempotency-Key") String key,
                                                         @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "concrete-strength-assessment-update:" + id, body,
                ConcreteStrengthAssessmentService.View.class, () -> service.update(id, body.command()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAnyAuthority('MODULE_WRITE','REPORT_REVIEW','REPORT_ISSUE')")
    public TransitionResult transition(@PathVariable UUID id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "concrete-strength-assessment-transition:" + id, body,
                TransitionResult.class, () -> service.transition(id, body.action(), body.expectedVersion(),
                        body.reasonCode(), body.comment()));
    }
}
