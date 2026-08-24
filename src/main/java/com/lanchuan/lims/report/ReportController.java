package com.lanchuan.lims.report;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    private final IdempotencyService idempotency;

    public ReportController(ReportService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotNull UUID commissionId, @NotBlank @Size(max = 240) String title,
                                @NotEmpty Map<String, Object> content) {}
    public record UpdateRequest(@NotEmpty Map<String, Object> content,
                                @NotNull @PositiveOrZero Long expectedVersion) {}
    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @NotNull @PositiveOrZero Long expectedVersion,
                                    @Size(max = 80) String reasonCode, @Size(max = 1000) String comment) {}
    public record DistributionRequest(@NotBlank @Size(max = 240) String recipient, @NotBlank String method,
                                      @Size(max = 64) String copyNumber,
                                      @NotNull @PositiveOrZero Long expectedVersion) {}
    public record AcknowledgementRequest(@NotBlank @Size(max = 120) String acknowledgedBy,
                                         @NotNull @PositiveOrZero Long expectedVersion) {}
    public record RevisionRequest(@NotNull @PositiveOrZero Long expectedVersion,
                                  @NotBlank @Size(max = 80) String reasonCode,
                                  @NotBlank @Size(max = 1000) String comment) {}
    public record PrintAuthorizationRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<ReportService.View> list(@RequestParam(required = false) UUID commissionId) { return service.list(commissionId); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ReportService.View get(@PathVariable UUID id) { return service.require(id); }

    @PostMapping("/{id}/print-authorizations")
    @PreAuthorize("hasAuthority('FILE_DOWNLOAD')")
    public ReportService.PrintAuthorization authorizePrint(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PrintAuthorizationRequest body) {
        return idempotency.execute(key, "report-print-authorization:" + id, body,
                ReportService.PrintAuthorization.class,
                () -> service.authorizePrint(id, body.expectedVersion()));
    }

    @GetMapping("/{id}/distributions")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<ReportService.DistributionView> distributions(@PathVariable UUID id) {
        return service.distributions(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ReportService.View create(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CreateRequest body) {
        ReportService.Create command = new ReportService.Create(body.commissionId(), body.title(), body.content());
        return idempotency.execute(key, "report-create", command, ReportService.View.class, () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ReportService.View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return service.update(id, new ReportService.Update(body.content(), body.expectedVersion()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION') or hasAuthority('REPORT_REVIEW') or hasAuthority('REPORT_ISSUE') or hasAuthority('REPORT_SEAL')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        ReportService.Transition command = new ReportService.Transition(body.action(), body.expectedVersion(),
                body.reasonCode(), body.comment());
        return idempotency.execute(key, "report-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }

    @PostMapping("/{id}/distributions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public ReportService.View distribute(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody DistributionRequest body) {
        ReportService.Distribution command = new ReportService.Distribution(body.recipient(), body.method(),
                body.copyNumber(), body.expectedVersion());
        return idempotency.execute(key, "report-distribution:" + id, command, ReportService.View.class,
                () -> service.distribute(id, command));
    }

    @PostMapping("/{id}/distributions/{distributionId}/acknowledgements")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public ReportService.DistributionView acknowledge(@PathVariable UUID id, @PathVariable UUID distributionId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody AcknowledgementRequest body) {
        ReportService.Acknowledgement command = new ReportService.Acknowledgement(body.acknowledgedBy(), body.expectedVersion());
        return idempotency.execute(key, "report-distribution-acknowledgement:" + distributionId, command,
                ReportService.DistributionView.class, () -> service.acknowledge(id, distributionId, command));
    }

    @PostMapping("/{id}/revisions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public ReportService.View revise(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody RevisionRequest body) {
        ReportService.Revision command = new ReportService.Revision(body.expectedVersion(), body.reasonCode(), body.comment());
        return idempotency.execute(key, "report-revision:" + id, command, ReportService.View.class,
                () -> service.revise(id, command));
    }
}
