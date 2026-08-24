package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/commissions")
public class CommissionController {
    private final CommissionService service;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public CommissionController(CommissionService service, IdempotencyService idempotency, AuditService audit) {
        this.service = service;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    public record SampleGroupRequest(@NotBlank @Size(max = 80) String clientKey,
                                     @NotBlank @Size(max = 240) String name,
                                     @Size(max = 1000) String description,
                                     @NotBlank String quantity,
                                     @NotBlank @Size(max = 40) String unit,
                                     @NotNull List<UUID> parameterIds,
                                     @NotNull Map<@Size(max = 80) String,
                                             @Size(max = 200) List<@Size(max = 2000) String>> fieldValues) {
        CommissionService.SampleGroup toCommand() {
            return new CommissionService.SampleGroup(clientKey, name, description, quantity, unit,
                    parameterIds, fieldValues);
        }
    }
    public record DetectionSelectionRequest(@NotNull UUID projectRevisionId,
                                            @NotNull List<UUID> parameterIds,
                                            @NotNull List<@Valid SampleGroupRequest> sampleGroups) {
        CommissionService.DetectionSelection toCommand() {
            return new CommissionService.DetectionSelection(projectRevisionId, parameterIds,
                    sampleGroups.stream().map(SampleGroupRequest::toCommand).toList());
        }
    }
    public record IntakeDetailsRequest(@NotBlank String projectProgress, @NotBlank String testingType,
                                       @NotBlank String testingStandard, @NotBlank String testingPlace,
                                       @NotBlank String sampleHandling, @NotBlank String reportDelivery,
                                       @Size(max = 500) String reportDeliveryAddress,
                                       @Min(0) @Max(999) int reportCopies,
                                       @Size(max = 500) String projectPart, @Size(max = 240) String monomer,
                                       @Size(max = 120) String settlementContact,
                                       @Size(max = 240) String witnessUnit, @Size(max = 120) String witnessName,
                                       @Size(max = 40) String witnessPhone, @Size(max = 120) String witnessCertificate,
                                       @Size(max = 240) String samplingUnit, @Size(max = 120) String samplingPerson,
                                       @Size(max = 40) String samplingPhone, @Size(max = 120) String samplingCertificate,
                                       @Size(max = 240) String constructionUnit,
                                       boolean needSampling, boolean electronicSignature, boolean cmaSeal,
                                       boolean qualificationSeal, boolean externalCommission,
                                       @Size(max = 240) String externalOrganization,
                                       @Size(max = 120) String externalNumber,
                                       @Size(max = 500) String sampleReceivingAddress, LocalDate expectedReportOn,
                                       @Size(max = 2000) String remark, @Size(max = 2000) String sourceRemark,
                                       @NotNull @Size(max = 100) List<@Size(max = 40) String> ageReportValues,
                                       @NotNull @Size(max = 20) List<UUID> attachmentFileIds) {
        CommissionService.IntakeDetails toCommand() {
            return new CommissionService.IntakeDetails(projectProgress, testingType, testingStandard,
                    testingPlace, sampleHandling, reportDelivery, reportDeliveryAddress, reportCopies,
                    projectPart, monomer, settlementContact, witnessUnit, witnessName, witnessPhone,
                    witnessCertificate, samplingUnit, samplingPerson, samplingPhone, samplingCertificate,
                    constructionUnit, needSampling, electronicSignature, cmaSeal, qualificationSeal,
                    externalCommission, externalOrganization, externalNumber, sampleReceivingAddress,
                    expectedReportOn, remark, sourceRemark, ageReportValues, attachmentFileIds);
        }
    }
    public record CreateRequest(@NotNull UUID projectId, @NotNull UUID customerId,
                                @NotBlank @Size(max = 120) String contactName,
                                @Size(max = 40) String contactPhone,
                                @NotNull LocalDate commissionedOn,
                                @NotNull LocalDate dueOn,
                                @NotNull @Valid IntakeDetailsRequest details,
                                @NotNull List<@Valid DetectionSelectionRequest> detectionSelections) {}
    public record UpdateRequest(@NotBlank @Size(max = 120) String contactName,
                                @Size(max = 40) String contactPhone,
                                @NotNull LocalDate commissionedOn,
                                @NotNull LocalDate dueOn,
                                @NotNull @Valid IntakeDetailsRequest details,
                                @NotNull List<@Valid DetectionSelectionRequest> detectionSelections,
                                @NotNull Long expectedVersion) {}
    public record PrintAuditRequest(@Size(max = 100) String query, @Size(max = 40) String state,
                                    UUID customerId, LocalDate from, LocalDate to, int rowCount) {}
    public record PrintAuditResponse(boolean recorded) {}
    public record BatchTargetRequest(@NotNull UUID id, @NotNull Long expectedVersion) {
        CommissionService.BatchTarget toCommand() { return new CommissionService.BatchTarget(id, expectedVersion); }
    }
    public record BatchTransitionRequest(@NotNull @Size(min = 1, max = 200) List<@Valid BatchTargetRequest> targets,
                                         @NotBlank @Size(max = 40) String action,
                                         @Size(max = 80) String reasonCode,
                                         @NotBlank @Size(max = 2000) String comment) {
        CommissionService.BatchTransition toCommand() {
            return new CommissionService.BatchTransition(targets.stream().map(BatchTargetRequest::toCommand).toList(),
                    action, reasonCode, comment);
        }
    }
    public record InternalSequenceTargetRequest(@NotNull UUID id, @NotNull Long expectedVersion,
                                                @Min(1) Long internalSequence) {
        CommissionService.InternalSequenceTarget toCommand() {
            return new CommissionService.InternalSequenceTarget(id, expectedVersion, internalSequence);
        }
    }
    public record BatchInternalSequenceRequest(
            @NotNull @Size(min = 1, max = 200) List<@Valid InternalSequenceTargetRequest> targets,
            @Size(max = 2000) String comment) {
        CommissionService.BatchInternalSequence toCommand() {
            return new CommissionService.BatchInternalSequence(
                    targets.stream().map(InternalSequenceTargetRequest::toCommand).toList(), comment);
        }
    }
    public record BatchCopyRequest(@NotNull @Size(min = 1, max = 50) List<@Valid BatchTargetRequest> targets,
                                   @Size(max = 2000) String comment) {
        CommissionService.BatchCopy toCommand() {
            return new CommissionService.BatchCopy(targets.stream().map(BatchTargetRequest::toCommand).toList(), comment);
        }
    }
    public record ExportRequest(@NotNull @Size(min = 1, max = 200) List<@Valid BatchTargetRequest> targets,
                                @Size(max = 2000) String comment) {
        CommissionService.ExportWrite toCommand() {
            return new CommissionService.ExportWrite(targets.stream().map(BatchTargetRequest::toCommand).toList(), comment);
        }
    }
    public record ExternalNumberRequest(@NotBlank @Size(max = 120) String externalNumber,
                                        @NotNull Long expectedVersion,
                                        @Size(max = 1000) String comment) {
        CommissionService.ExternalNumberWrite toCommand() {
            return new CommissionService.ExternalNumberWrite(externalNumber, expectedVersion, comment);
        }
    }
    public record PrintRestrictionRequest(boolean restricted, @Min(0) Integer printLimit,
                                          @NotBlank @Size(max = 1000) String reason,
                                          @NotNull Long expectedVersion) {
        CommissionService.PrintRestrictionWrite toCommand() {
            return new CommissionService.PrintRestrictionWrite(restricted, printLimit, reason, expectedVersion);
        }
    }
    public record SampleGroupPartBindingRequest(@NotNull UUID sampleGroupId,
                                                @NotNull @Size(max = 50) List<UUID> projectPartIds) {
        CommissionService.SampleGroupPartWrite toCommand() {
            return new CommissionService.SampleGroupPartWrite(sampleGroupId, projectPartIds);
        }
    }
    public record SampleGroupPartsRequest(
            @NotNull @Size(min = 1, max = 200) List<@Valid SampleGroupPartBindingRequest> bindings,
            @NotNull Long expectedVersion, @Size(max = 1000) String comment) {
        CommissionService.SampleGroupPartCommand toCommand() {
            return new CommissionService.SampleGroupPartCommand(
                    bindings.stream().map(SampleGroupPartBindingRequest::toCommand).toList(), expectedVersion, comment);
        }
    }
    public record TerminationRequest(@NotBlank @Size(max = 80) String reasonCode,
                                     @NotBlank @Size(max = 1000) String comment,
                                     @NotNull Long expectedVersion) {
        CommissionService.TerminationWrite toCommand() {
            return new CommissionService.TerminationWrite(reasonCode, comment, expectedVersion);
        }
    }
    public record VersionRequest(@NotNull Long expectedVersion) {}
    public record RelationRequest(@NotNull UUID targetCommissionId, @NotBlank @Size(max = 24) String relationType,
                                  @Size(max = 1000) String comment, @NotNull Long expectedVersion) {
        CommissionService.RelationWrite toCommand() {
            return new CommissionService.RelationWrite(targetCommissionId, relationType, comment, expectedVersion);
        }
    }
    public record TransferRequest(@NotNull UUID toDepartmentId, @Size(max = 1000) String comment,
                                  @NotNull Long expectedVersion) {
        CommissionService.TransferWrite toCommand() {
            return new CommissionService.TransferWrite(toDepartmentId, comment, expectedVersion);
        }
    }
    public record PrintRequest(@NotBlank @Size(max = 40) String documentType, @NotNull Long expectedVersion) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<CommissionService.Summary> list(
            @RequestParam(defaultValue = "") @Size(max = 200) String q,
            @RequestParam(required = false) LocalDate commissionedFrom,
            @RequestParam(required = false) LocalDate commissionedTo,
            @RequestParam(defaultValue = "") @Size(max = 400) String states,
            @RequestParam(defaultValue = "") @Size(max = 80) String flowStates,
            @RequestParam(defaultValue = "") @Size(max = 200) String taskStates,
            @RequestParam(defaultValue = "") @Size(max = 40) String testingPlace,
            @RequestParam(required = false) Boolean externalCommission,
            @RequestParam(defaultValue = "") @Size(max = 200) String detectionProject,
            @RequestParam(defaultValue = "") @Size(max = 120) String commissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String sampleNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String precommissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String customer,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 240) String projectName,
            @RequestParam(required = false) Long managementSequenceMin,
            @RequestParam(required = false) Long managementSequenceMax,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) Boolean waitingFlow,
            @RequestParam(defaultValue = "COMMISSION") String viewMode,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(new CommissionService.ListQuery(q, commissionedFrom, commissionedTo, states,
                flowStates, taskStates, testingPlace, externalCommission, detectionProject, commissionNumber, sampleNumber,
                precommissionNumber, customer, projectNumber, projectName, managementSequenceMin,
                managementSequenceMax, departmentId, waitingFlow, viewMode, cursor, limit));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CommissionService.CommissionOptions options() { return service.options(); }

    @GetMapping("/project-defaults/{projectId}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CommissionService.ProjectDefaults projectDefaults(@PathVariable UUID projectId) {
        return service.projectDefaults(projectId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CommissionService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/{id}/detection-selections")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<CommissionService.DetectionSelectionView> selections(@PathVariable UUID id) {
        return service.selections(id);
    }

    @PostMapping("/print-audits")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public PrintAuditResponse auditPrint(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody PrintAuditRequest body) {
        return idempotency.execute(key, "commission-list-print", body, PrintAuditResponse.class, () -> {
            audit.record("COMMISSION_LIST_PRINTED", "COMMISSION", null, null, body, null,
                    "受控委托清单打印，共 " + Math.max(0, body.rowCount()) + " 条");
            return new PrintAuditResponse(true);
        });
    }

    @PostMapping("/batch-transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public CommissionService.BatchResult batchTransition(@RequestHeader("Idempotency-Key") String key,
                                                          @Valid @RequestBody BatchTransitionRequest body) {
        CommissionService.BatchTransition command = body.toCommand();
        return idempotency.execute(key, "commission-batch-transition", command,
                CommissionService.BatchResult.class, () -> service.batchTransition(command));
    }

    @PostMapping("/batch-internal-sequences")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.BatchResult batchInternalSequence(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BatchInternalSequenceRequest body) {
        CommissionService.BatchInternalSequence command = body.toCommand();
        return idempotency.execute(key, "commission-batch-internal-sequence", command,
                CommissionService.BatchResult.class, () -> service.batchInternalSequence(command));
    }

    @PostMapping("/batch-copies")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.BatchResult batchCopy(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody BatchCopyRequest body) {
        CommissionService.BatchCopy command = body.toCommand();
        return idempotency.execute(key, "commission-batch-copy", command,
                CommissionService.BatchResult.class, () -> service.batchCopy(command));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public CommissionService.JobQueued export(@RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody ExportRequest body) {
        CommissionService.ExportWrite command = body.toCommand();
        return idempotency.execute(key, "commission-export", command,
                CommissionService.JobQueued.class, () -> service.enqueueExport(command, key));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.View create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateRequest body) {
        CommissionService.Create command = new CommissionService.Create(body.projectId(), body.customerId(),
                body.contactName(), body.contactPhone(), body.commissionedOn(), body.dueOn(),
                body.details().toCommand(),
                body.detectionSelections().stream().map(DetectionSelectionRequest::toCommand).toList());
        return idempotency.execute(key, "commission-create", command, CommissionService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.View update(@PathVariable UUID id,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody UpdateRequest body) {
        CommissionService.Update command = new CommissionService.Update(body.contactName(), body.contactPhone(),
                body.commissionedOn(), body.dueOn(), body.details().toCommand(),
                body.detectionSelections().stream().map(DetectionSelectionRequest::toCommand).toList(),
                body.expectedVersion());
        return idempotency.execute(key, "commission-update:" + id, command, CommissionService.View.class,
                () -> service.update(id, command));
    }

    @PostMapping("/{id}/copies")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.View copy(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "commission-copy:" + id, body, CommissionService.View.class,
                () -> service.copy(id, body.expectedVersion()));
    }

    @PostMapping("/{id}/external-number-bindings")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.View bindExternalNumber(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody ExternalNumberRequest body) {
        CommissionService.ExternalNumberWrite command = body.toCommand();
        return idempotency.execute(key, "commission-external-number:" + id, command,
                CommissionService.View.class, () -> service.bindExternalNumber(id, command));
    }

    @GetMapping("/{id}/sample-group-parts")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<CommissionService.SampleGroupPartView> sampleGroupParts(@PathVariable UUID id) {
        return service.sampleGroupParts(id);
    }

    @PostMapping("/{id}/sample-group-parts")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public List<CommissionService.SampleGroupPartView> bindSampleGroupParts(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SampleGroupPartsRequest body) {
        CommissionService.SampleGroupPartCommand command = body.toCommand();
        return idempotency.execute(key, "commission-sample-group-parts:" + id, command,
                CommissionService.SampleGroupPartsResult.class,
                () -> new CommissionService.SampleGroupPartsResult(service.bindSampleGroupParts(id, command))).bindings();
    }

    @GetMapping("/{id}/report-print-restriction")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CommissionService.PrintRestrictionView printRestriction(@PathVariable UUID id) {
        return service.printRestriction(id);
    }

    @PostMapping("/{id}/report-print-restrictions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.PrintRestrictionView updatePrintRestriction(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PrintRestrictionRequest body) {
        CommissionService.PrintRestrictionWrite command = body.toCommand();
        return idempotency.execute(key, "commission-report-print-restriction:" + id, command,
                CommissionService.PrintRestrictionView.class, () -> service.updatePrintRestriction(id, command));
    }

    @PostMapping("/{id}/test-terminations")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public CommissionService.TerminationResult terminateTests(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TerminationRequest body) {
        CommissionService.TerminationWrite command = body.toCommand();
        return idempotency.execute(key, "commission-test-termination:" + id, command,
                CommissionService.TerminationResult.class, () -> service.terminateTests(id, command));
    }

    @GetMapping("/{id}/electronic-document-readiness")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CommissionService.ElectronicDocumentReadiness electronicDocumentReadiness(@PathVariable UUID id) {
        return service.electronicDocumentReadiness(id);
    }

    @GetMapping("/{id}/flow-events")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<CommissionService.FlowEvent> flowEvents(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.flowEvents(id, cursor, limit);
    }

    @GetMapping("/{id}/electronic-documents")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<CommissionService.ElectronicDocumentView> electronicDocuments(@PathVariable UUID id) {
        return service.electronicDocuments(id);
    }

    @PostMapping("/{id}/electronic-document-refreshes")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public CommissionService.JobQueued refreshElectronicDocument(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "commission-electronic-document:" + id, body,
                CommissionService.JobQueued.class,
                () -> service.enqueueElectronicDocument(id, body.expectedVersion(), key));
    }

    @GetMapping("/{id}/relations")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<CommissionService.RelationView> relations(@PathVariable UUID id) { return service.relations(id); }

    @PostMapping("/{id}/relations")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.RelationView relate(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody RelationRequest body) {
        CommissionService.RelationWrite command = body.toCommand();
        return idempotency.execute(key, "commission-relate:" + id, command,
                CommissionService.RelationView.class, () -> service.relate(id, command));
    }

    @GetMapping("/{id}/transfers")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<CommissionService.TransferView> transfers(@PathVariable UUID id) { return service.transfers(id); }

    @PostMapping("/{id}/transfers")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CommissionService.TransferView transfer(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody TransferRequest body) {
        CommissionService.TransferWrite command = body.toCommand();
        return idempotency.execute(key, "commission-transfer:" + id, command,
                CommissionService.TransferView.class, () -> service.transfer(id, command));
    }

    @PostMapping("/transfers/{transferId}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public CommissionService.TransferView transitionTransfer(@PathVariable UUID transferId,
                                                              @RequestHeader("Idempotency-Key") String key,
                                                              @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "commission-transfer-transition:" + transferId, command,
                CommissionService.TransferView.class, () -> service.transitionTransfer(transferId, command));
    }

    @PostMapping("/{id}/prints")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public CommissionService.PrintResult markPrinted(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody PrintRequest body) {
        return idempotency.execute(key, "commission-print:" + id + ":" + body.documentType(), body,
                CommissionService.PrintResult.class,
                () -> service.markPrinted(id, body.documentType(), body.expectedVersion()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "commission-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }
}
