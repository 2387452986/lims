package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class ProjectController {
    private final ProjectDirectoryService service;
    private final IdempotencyService idempotency;

    public ProjectController(ProjectDirectoryService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record PartyRequest(@NotBlank @Size(max = 32) String partyRole,
                               UUID customerId,
                               @NotBlank @Size(max = 240) String name,
                               @Size(max = 120) String contactName,
                               @Size(max = 40) String contactPhone,
                               @Size(max = 120) String certificateNumber,
                               boolean manualEntry,
                               boolean reportAccess) {
        ProjectDirectoryService.PartyWrite command() {
            return new ProjectDirectoryService.PartyWrite(partyRole, customerId, name, contactName,
                    contactPhone, certificateNumber, manualEntry, reportAccess);
        }
    }

    public record WriteRequest(
            @NotBlank @Size(max = 64) String number,
            @NotBlank @Size(max = 240) String name,
            @NotNull UUID customerId,
            @Size(max = 240) String location,
            @Size(max = 80) String pileSerialNumber,
            @Size(max = 80) String projectType,
            @Size(max = 80) String projectGrade,
            @Size(max = 240) String regulatoryOrganization,
            @Size(max = 120) String regulatoryProjectCode,
            @Size(max = 240) String regulatoryProjectName,
            boolean regulatoryLinked,
            @Size(max = 120) String entrusterName,
            @Size(max = 40) String entrusterPhone,
            @Size(max = 120) String settlementContactName,
            @Size(max = 40) String settlementContactPhone,
            @Size(max = 240) String samplingUnitName,
            @Size(max = 120) String samplerName,
            @Size(max = 40) String samplerPhone,
            @Size(max = 120) String samplerCertificateNumber,
            @Size(max = 80) String province,
            @Size(max = 80) String city,
            @Size(max = 80) String district,
            @Size(max = 500) String detailedAddress,
            @Size(max = 120) String sectionNumber,
            @Size(max = 80) String regionCode,
            @Size(max = 120) String defaultDetectionCategory,
            @Size(max = 2000) String remark,
            @NotBlank @Size(max = 240) String ownerName,
            boolean ownerReportAccess,
            @NotBlank @Size(max = 240) String supervisionName,
            @Size(max = 120) String witnessName,
            @Size(max = 40) String witnessPhone,
            boolean supervisionReportAccess,
            @NotBlank @Size(max = 240) String constructionName,
            boolean constructionReportAccess,
            @Size(max = 240) String surveyName,
            @Size(max = 240) String designName,
            @Size(max = 240) String generalContractorName,
            boolean customerReportAccess,
            @NotBlank @Size(max = 24) String cmaSealPolicy,
            @NotBlank @Size(max = 24) String qualificationSealPolicy,
            @NotBlank @Size(max = 24) String electronicSealPolicy,
            boolean customerPrintAllowed,
            @PositiveOrZero Integer customerPrintLimit,
            @Size(max = 40) String settlementMethod,
            UUID projectTeamId,
            boolean reportPrintRestricted,
            @Size(max = 120) String externalContractNumber,
            LocalDate handledOn,
            @Size(max = 32) String latitude,
            @Size(max = 32) String longitude,
            @NotNull List<@Valid PartyRequest> parties,
            @PositiveOrZero Long expectedVersion) {
        ProjectDirectoryService.Write command() {
            return new ProjectDirectoryService.Write(number, name, customerId, location, pileSerialNumber,
                    projectType, projectGrade, regulatoryOrganization, regulatoryProjectCode,
                    regulatoryProjectName, regulatoryLinked, entrusterName, entrusterPhone,
                    settlementContactName, settlementContactPhone, samplingUnitName, samplerName,
                    samplerPhone, samplerCertificateNumber, province, city, district, detailedAddress,
                    sectionNumber, regionCode, defaultDetectionCategory, remark, ownerName,
                    ownerReportAccess, supervisionName, witnessName, witnessPhone,
                    supervisionReportAccess, constructionName, constructionReportAccess, surveyName,
                    designName, generalContractorName, customerReportAccess, cmaSealPolicy,
                    qualificationSealPolicy, electronicSealPolicy, customerPrintAllowed,
                    customerPrintLimit, settlementMethod, projectTeamId, reportPrintRestricted,
                    externalContractNumber, handledOn, latitude, longitude,
                    parties.stream().map(PartyRequest::command).toList(), expectedVersion);
        }
    }

    public record PlanRequest(UUID detectionProjectRevisionId,
                              UUID parameterId,
                              @Size(max = 240) String categoryName,
                              @NotBlank @Size(max = 240) String projectName,
                              @NotBlank @Size(max = 240) String parameterName,
                              @NotBlank @Size(max = 80) String plannedQuantity,
                              @NotBlank @Size(max = 40) String unit,
                              @Size(max = 240) String bindingParameter,
                              @Size(max = 500) String detectionMethod,
                              LocalDate plannedCompletionOn,
                              @NotBlank @Size(max = 80) String unitPrice,
                              boolean active,
                              int sortOrder,
                              @PositiveOrZero Long expectedVersion) {
        ProjectDirectoryService.PlanWrite command() {
            return new ProjectDirectoryService.PlanWrite(detectionProjectRevisionId, parameterId, categoryName,
                    projectName, parameterName, plannedQuantity, unit, bindingParameter, detectionMethod,
                    plannedCompletionOn, unitPrice, active, sortOrder, expectedVersion);
        }
    }

    public record ProgressRequest(@NotBlank @Size(max = 16) String progressPercent,
                                  @NotBlank @Size(max = 120) String progressLabel,
                                  @NotNull LocalDate occurredOn,
                                  @Size(max = 1000) String comment,
                                  @NotNull @PositiveOrZero Long expectedVersion) {
        ProjectDirectoryService.ProgressMarkWrite command() {
            return new ProjectDirectoryService.ProgressMarkWrite(progressPercent, progressLabel,
                    occurredOn, comment, expectedVersion);
        }
    }

    public record PartRequest(UUID parentId,
                              @NotBlank @Size(max = 80) String code,
                              @NotBlank @Size(max = 240) String name,
                              int sortOrder,
                              boolean active,
                              @PositiveOrZero Long expectedVersion) {
        ProjectDirectoryService.PartWrite command() {
            return new ProjectDirectoryService.PartWrite(parentId, code, name, sortOrder, active, expectedVersion);
        }
    }

    @GetMapping
    public CursorPage<ProjectDirectoryService.Summary> list(
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = "") @Size(max = 240) String regulatoryOrganization,
            @RequestParam(defaultValue = "") @Size(max = 160) String statuses,
            @RequestParam(required = false) Boolean regulatoryLinked,
            @RequestParam(required = false) UUID projectTeamId,
            @RequestParam(defaultValue = "") @Size(max = 64) String number,
            @RequestParam(defaultValue = "") @Size(max = 80) String pileSerialNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String name,
            @RequestParam(defaultValue = "") @Size(max = 240) String entrustingName,
            @RequestParam(defaultValue = "") @Size(max = 240) String ownerName,
            @RequestParam(defaultValue = "") @Size(max = 240) String supervisionName,
            @RequestParam(defaultValue = "") @Size(max = 240) String constructionName,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(query, regulatoryOrganization, statuses, regulatoryLinked, projectTeamId,
                number, pileSerialNumber, name, entrustingName, ownerName, supervisionName,
                constructionName, cursor, limit);
    }

    @GetMapping("/options")
    public ProjectDirectoryService.Options options() { return service.options(); }

    @GetMapping("/{id}")
    public ProjectDirectoryService.Detail detail(@PathVariable UUID id) { return service.detail(id); }

    @PostMapping("/number-generations")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.NumberGeneration generateNumber(
            @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "project-number-generate", Map.of("rule", "PROJECT"),
                ProjectDirectoryService.NumberGeneration.class, service::generateNumber);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.Detail create(@RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "project-directory-create", body.command(),
                ProjectDirectoryService.Detail.class, () -> service.create(body.command()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.Detail update(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "project-directory-update:" + id, body.command(),
                ProjectDirectoryService.Detail.class, () -> service.update(id, body.command()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "project-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @GetMapping("/{id}/plans")
    public List<ProjectDirectoryService.PlanView> plans(@PathVariable UUID id,
                                                       @RequestParam(defaultValue = "") @Size(max = 100) String query) {
        return service.plans(id, query);
    }

    @PostMapping("/{id}/plans")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.PlanView addPlan(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody PlanRequest body) {
        return idempotency.execute(key, "project-plan-create:" + id, body.command(),
                ProjectDirectoryService.PlanView.class, () -> service.savePlan(id, null, body.command()));
    }

    @PutMapping("/{id}/plans/{planId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.PlanView updatePlan(@PathVariable UUID id, @PathVariable UUID planId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody PlanRequest body) {
        return idempotency.execute(key, "project-plan-update:" + planId, body.command(),
                ProjectDirectoryService.PlanView.class, () -> service.savePlan(id, planId, body.command()));
    }

    @GetMapping("/{id}/progress-marks")
    public List<ProjectDirectoryService.ProgressMarkView> progressMarks(@PathVariable UUID id) {
        return service.progressMarks(id);
    }

    @PostMapping("/{id}/progress-marks")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.ProgressMarkView addProgressMark(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ProgressRequest body) {
        return idempotency.execute(key, "project-progress-create:" + id, body.command(),
                ProjectDirectoryService.ProgressMarkView.class,
                () -> service.addProgressMark(id, body.command()));
    }

    @GetMapping("/{id}/parts")
    public List<ProjectDirectoryService.PartView> parts(@PathVariable UUID id) { return service.parts(id); }

    @PostMapping("/{id}/parts")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.PartView addPart(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody PartRequest body) {
        return idempotency.execute(key, "project-part-create:" + id, body.command(),
                ProjectDirectoryService.PartView.class, () -> service.savePart(id, null, body.command()));
    }

    @PutMapping("/{id}/parts/{partId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectDirectoryService.PartView updatePart(@PathVariable UUID id, @PathVariable UUID partId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody PartRequest body) {
        return idempotency.execute(key, "project-part-update:" + partId, body.command(),
                ProjectDirectoryService.PartView.class, () -> service.savePart(id, partId, body.command()));
    }
}
