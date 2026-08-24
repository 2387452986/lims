package com.lanchuan.lims.equipment;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/equipment")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class EquipmentController {
    private final EquipmentService service;
    private final EquipmentOperationService operations;
    private final EquipmentWorkbookService workbooks;
    private final IdempotencyService idempotency;

    public EquipmentController(EquipmentService service, EquipmentOperationService operations,
                               EquipmentWorkbookService workbooks,
                               IdempotencyService idempotency) {
        this.service = service;
        this.operations = operations;
        this.workbooks = workbooks;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotBlank @Size(max = 64) String code, @Size(max = 80) String assetNumber,
                               @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 120) String model,
                               UUID categoryId, LocalDate purchasedOn, @PositiveOrZero BigDecimal purchasePrice,
                               @Size(max = 240) String manufacturer, @Size(max = 120) String serialNumber,
                               @Min(1) Integer verificationCycleMonths, @Size(max = 32) String verificationType,
                               @Size(max = 1000) String technicalSpecification, @Size(max = 240) String nominalRange,
                               @Size(max = 240) String measurementRange, @Size(max = 120) String resolution,
                               @Size(max = 120) String accuracy, UUID custodianId, boolean primaryEquipment,
                               boolean enabled, @NotBlank @Size(max = 16) String performanceStatus,
                               @Size(max = 160) String location, @Size(max = 40) String equipmentType,
                               @Size(max = 40) String pileEquipmentType, UUID attachmentFileId, UUID photoFileId,
                               @Size(max = 2000) String remark, @Size(max = 2000) List<@NotNull UUID> detectionProjectIds,
                               @PositiveOrZero long expectedVersion) {
        EquipmentService.Write command() {
            return new EquipmentService.Write(code, assetNumber, name, model, categoryId, purchasedOn, purchasePrice,
                    manufacturer, serialNumber, verificationCycleMonths, verificationType, technicalSpecification,
                    nominalRange, measurementRange, resolution, accuracy, custodianId, primaryEquipment, enabled,
                    performanceStatus, location, equipmentType, pileEquipmentType, attachmentFileId, photoFileId,
                    remark, detectionProjectIds == null ? List.of() : detectionProjectIds, expectedVersion);
        }
    }

    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @PositiveOrZero long expectedVersion, @Size(max = 80) String reasonCode,
                                    @Size(max = 1000) String comment, UUID taskId, Map<String, Object> details) {
        EquipmentService.Transition command() {
            return new EquipmentService.Transition(action, expectedVersion, reasonCode, comment, taskId,
                    details == null ? Map.of() : details);
        }
    }

    public record BatchTargetRequest(@NotNull UUID id, @PositiveOrZero long expectedVersion) {
        EquipmentService.BatchTarget command() { return new EquipmentService.BatchTarget(id, expectedVersion); }
    }

    public record BatchTransitionRequest(@NotBlank @Size(max = 80) String action,
                                         @Size(max = 80) String reasonCode,
                                         @Size(max = 1000) String comment,
                                         @NotNull @Size(min = 1, max = 500)
                                         List<@Valid @NotNull BatchTargetRequest> items) {}

    public record CategoryRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
                                  UUID parentId, int sortOrder, @PositiveOrZero long expectedVersion) {
        EquipmentService.CategoryWrite command() { return new EquipmentService.CategoryWrite(code, name, parentId, sortOrder, expectedVersion); }
    }
    public record CategoryAssignmentRequest(@NotNull @Size(min = 1, max = 500) List<@NotNull UUID> equipmentIds,
                                            @NotNull UUID categoryId) {}

    public record CertificateRequest(@NotBlank @Size(max = 24) String certificateType,
                                     @Size(max = 32) String inspectionType,
                                     @NotBlank @Size(max = 120) String certificateNumber,
                                     @NotNull LocalDate issuedOn, @NotNull LocalDate validUntil,
                                     LocalDate confirmedOn, @NotBlank @Size(max = 20) String result,
                                     @NotBlank @Size(max = 240) String issuingOrganization,
                                     UUID fileId, @Size(max = 2000) String remark,
                                     @PositiveOrZero long expectedVersion) {
        EquipmentService.CertificateWrite command() {
            return new EquipmentService.CertificateWrite(certificateType, inspectionType, certificateNumber,
                    issuedOn, validUntil, confirmedOn, result, issuingOrganization, fileId, remark, expectedVersion);
        }
    }

    public record InspectionCreateRequest(@NotNull UUID equipmentId, @NotNull UUID outboundPersonId,
                                          @NotNull OffsetDateTime outboundAt,
                                          @PositiveOrZero BigDecimal outboundAccessoryQuantity,
                                          @Size(max = 500) String destination, UUID photoFileId,
                                          UUID attachmentFileId, @Size(max = 2000) String remark,
                                          @PositiveOrZero long expectedVersion) {
        EquipmentOperationService.InspectionCreate command() {
            return new EquipmentOperationService.InspectionCreate(equipmentId, outboundPersonId, outboundAt,
                    outboundAccessoryQuantity, destination, photoFileId, attachmentFileId, remark, expectedVersion);
        }
    }
    public record InspectionTransitionRequest(@NotBlank @Size(max = 40) String action,
                                              @PositiveOrZero long expectedVersion,
                                              @Size(max = 80) String reasonCode, @Size(max = 1000) String comment,
                                              UUID personId, OffsetDateTime occurredAt,
                                              @PositiveOrZero BigDecimal accessoryQuantity,
                                              @Size(max = 32) String inspectionType,
                                              @Size(max = 120) String inspectionNumber,
                                              @Size(max = 240) String inspectionOrganization,
                                              LocalDate inspectionDate, LocalDate validUntil,
                                              @Size(max = 20) String result, UUID certificateFileId) {
        EquipmentOperationService.InspectionTransition command() {
            return new EquipmentOperationService.InspectionTransition(action, expectedVersion, reasonCode, comment,
                    personId, occurredAt, accessoryQuantity, inspectionType, inspectionNumber,
                    inspectionOrganization, inspectionDate, validUntil, result, certificateFileId);
        }
    }

    public record BorrowCreateRequest(@NotNull UUID equipmentId, @NotNull UUID borrowerId,
                                      @Size(max = 20) List<@NotNull UUID> companionIds,
                                      @NotNull OffsetDateTime borrowedAt, @NotBlank @Size(max = 8) String borrowedPeriod,
                                      @NotNull OffsetDateTime plannedReturnAt,
                                      @NotBlank @Size(max = 8) String plannedReturnPeriod,
                                      @NotBlank @Size(max = 16) String beforeCondition,
                                      @PositiveOrZero BigDecimal accessoryQuantity,
                                      @Size(max = 80) String province, @Size(max = 80) String city,
                                      @Size(max = 80) String district, @Size(max = 500) String address,
                                      @Size(max = 500) String destination, UUID photoFileId,
                                      @Size(max = 2000) String remark, @PositiveOrZero long expectedVersion) {
        EquipmentOperationService.BorrowCreate command() {
            return new EquipmentOperationService.BorrowCreate(equipmentId, borrowerId,
                    companionIds == null ? List.of() : companionIds, borrowedAt, borrowedPeriod, plannedReturnAt,
                    plannedReturnPeriod, beforeCondition, accessoryQuantity, province, city, district, address,
                    destination, photoFileId, remark, expectedVersion);
        }
    }
    public record BorrowTransitionRequest(@NotBlank @Size(max = 40) String action,
                                          @PositiveOrZero long expectedVersion, @Size(max = 80) String reasonCode,
                                          @Size(max = 1000) String comment, UUID personId,
                                          OffsetDateTime occurredAt, @Size(max = 8) String period,
                                          @Size(max = 16) String condition,
                                          @PositiveOrZero BigDecimal accessoryQuantity) {
        EquipmentOperationService.BorrowTransition command() {
            return new EquipmentOperationService.BorrowTransition(action, expectedVersion, reasonCode, comment,
                    personId, occurredAt, period, condition, accessoryQuantity);
        }
    }

    public record UsageCreateRequest(@NotNull UUID equipmentId, UUID taskId, @Size(max = 240) String sampleName,
                                     @Size(max = 80) String commissionNumber, @NotNull UUID usedBy,
                                     @NotNull OffsetDateTime startedAt, BigDecimal temperature,
                                     @PositiveOrZero BigDecimal humidity, @Size(max = 240) String location,
                                     @NotBlank @Size(max = 16) String beforeCondition,
                                     @Size(max = 2000) String remark, @PositiveOrZero long expectedVersion) {
        EquipmentOperationService.UsageCreate command() {
            return new EquipmentOperationService.UsageCreate(equipmentId, taskId, sampleName, commissionNumber,
                    usedBy, startedAt, temperature, humidity, location, beforeCondition, remark, expectedVersion);
        }
    }
    public record UsageTransitionRequest(@NotBlank @Size(max = 40) String action,
                                         @PositiveOrZero long expectedVersion, @Size(max = 80) String reasonCode,
                                         @Size(max = 1000) String comment, OffsetDateTime endedAt,
                                         @Size(max = 16) String afterCondition) {
        EquipmentOperationService.UsageTransition command() {
            return new EquipmentOperationService.UsageTransition(action, expectedVersion, reasonCode, comment,
                    endedAt, afterCondition);
        }
    }

    @GetMapping
    public CursorPage<EquipmentService.Summary> list(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) LocalDate verificationFrom,
            @RequestParam(required = false) LocalDate verificationTo,
            @RequestParam(defaultValue = "") String equipmentType,
            @RequestParam(defaultValue = "") String enabled,
            @RequestParam(defaultValue = "") String performanceStatus,
            @RequestParam(defaultValue = "") String approvalState,
            @RequestParam(defaultValue = "") String verificationStatus,
            @RequestParam(defaultValue = "") String scrapped,
            @RequestParam(defaultValue = "") String inventoryStatus,
            @RequestParam(defaultValue = "") @Size(max = 64) String code,
            @RequestParam(defaultValue = "") @Size(max = 80) String assetNumber,
            @RequestParam(defaultValue = "") @Size(max = 200) String name,
            @RequestParam(defaultValue = "") @Size(max = 240) String manufacturer,
            @RequestParam(defaultValue = "") @Size(max = 240) String verificationOrganization,
            @RequestParam(defaultValue = "") @Size(max = 1000) String technicalSpecification,
            @RequestParam(defaultValue = "") @Size(max = 160) String location,
            @RequestParam(defaultValue = "") @Size(max = 120) String serialNumber,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(query, categoryId, verificationFrom, verificationTo, equipmentType, enabled,
                performanceStatus, approvalState, verificationStatus, scrapped, inventoryStatus, code,
                assetNumber, name, manufacturer, verificationOrganization, technicalSpecification, location,
                serialNumber, cursor, limit);
    }

    @GetMapping("/{id}")
    public EquipmentService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return xlsx(workbooks.equipmentTemplate(), "设备管理-受控导入模板-V1.xlsx");
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentWorkbookService.ImportResult importEquipment(@RequestHeader("Idempotency-Key") String key,
                                                                 @RequestParam MultipartFile file) throws IOException {
        byte[] content = file.getBytes(); String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        EquipmentWorkbookService.Fingerprint fingerprint = workbooks.fingerprint(content, filename);
        return idempotency.execute(key, "equipment-import", fingerprint, EquipmentWorkbookService.ImportResult.class,
                () -> workbooks.importEquipment(content, filename));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") @Size(max = 120) String query) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportEquipment(query);
        return xlsx(file.content(), file.filename());
    }

    @GetMapping("/{id}/label")
    public ResponseEntity<String> label(@PathVariable UUID id) {
        EquipmentService.View value = service.require(id);
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>设备标签</title>
                <style>@page{size:90mm 55mm;margin:4mm}*{box-sizing:border-box}body{margin:0;font-family:'Noto Sans CJK SC','PingFang SC',sans-serif;color:#10213c}
                .label{width:82mm;height:47mm;border:1.2px solid #10213c;padding:4mm;display:grid;grid-template-rows:auto 1fr auto;gap:2mm}
                h1{font-size:14px;margin:0;border-bottom:1px solid #aebbd0;padding-bottom:2mm}.name{font-size:16px;font-weight:700;margin:0 0 2mm}
                dl{display:grid;grid-template-columns:18mm 1fr;margin:0;font-size:11px;line-height:1.55}dt{color:#60708a}dd{margin:0;font-weight:600}
                footer{display:flex;justify-content:space-between;font-size:9px;color:#60708a}</style></head><body><section class="label">
                <h1>澜川智检科技有限公司</h1><div><p class="name">%s</p><dl><dt>设备编号</dt><dd>%s</dd><dt>规格型号</dt><dd>%s</dd><dt>存放位置</dt><dd>%s</dd><dt>有效期至</dt><dd>%s</dd></dl></div>
                <footer><span>内部受控设备标签</span><span>版本 %d</span></footer></section></body></html>
                """.formatted(escape(value.name()), escape(value.code()), escape(value.model()),
                escape(value.location() == null ? "未设定" : value.location()),
                escape(value.certificateValidUntil() == null ? "未录入" : value.certificateValidUntil().toString()), value.version());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff").body(html);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.View create(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody WriteRequest body) {
        EquipmentService.Write command = body.command();
        return idempotency.execute(key, "equipment-create", command, EquipmentService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.View update(@PathVariable UUID id, @Valid @RequestBody WriteRequest body) {
        return service.update(id, body.command());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public void delete(@PathVariable UUID id, @RequestParam @PositiveOrZero long expectedVersion,
                       @RequestParam @NotBlank @Size(max = 1000) String reason) {
        service.delete(id, expectedVersion, reason);
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        EquipmentService.Transition command = body.command();
        return idempotency.execute(key, "equipment-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }

    @PostMapping("/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public EquipmentService.BatchResult transitionBatch(@RequestHeader("Idempotency-Key") String key,
                                                         @Valid @RequestBody BatchTransitionRequest body) {
        List<EquipmentService.BatchTarget> targets = body.items().stream().map(BatchTargetRequest::command).toList();
        return idempotency.execute(key, "equipment-batch-transition", body,
                EquipmentService.BatchResult.class,
                () -> service.transitionBatch(targets, body.action(), body.reasonCode(), body.comment()));
    }

    @GetMapping("/categories")
    public List<EquipmentService.CategoryView> categories() { return service.categories(); }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.CategoryView createCategory(@RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody CategoryRequest body) {
        EquipmentService.CategoryWrite command = body.command();
        return idempotency.execute(key, "equipment-category-create", command, EquipmentService.CategoryView.class,
                () -> service.createCategory(command));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.CategoryView updateCategory(@PathVariable UUID id,
                                                        @Valid @RequestBody CategoryRequest body) {
        return service.updateCategory(id, body.command());
    }

    @PostMapping("/categories/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionCategory(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionRequest body) {
        EquipmentService.Transition command = body.command();
        return idempotency.execute(key, "equipment-category-transition:" + id, command, TransitionResult.class,
                () -> service.transitionCategory(id, command));
    }

    @PostMapping("/category-assignments")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public void assignCategory(@RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody CategoryAssignmentRequest body) {
        idempotency.execute(key, "equipment-category-assignment", body, Void.class,
                () -> { service.assignCategory(body.equipmentIds(), body.categoryId()); return null; });
    }

    @GetMapping("/certificates")
    public CursorPage<EquipmentService.CertificateView> certificates(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") String certificateType,
            @RequestParam(defaultValue = "") String inspectionType,
            @RequestParam(defaultValue = "") String verificationStatus,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String uploaded,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            @RequestParam(defaultValue = "") @Size(max = 64) String equipmentCode,
            @RequestParam(defaultValue = "") @Size(max = 120) String certificateNumber,
            @RequestParam(defaultValue = "") @Size(max = 200) String equipmentName,
            @RequestParam(defaultValue = "") @Size(max = 120) String model,
            @RequestParam(defaultValue = "") @Size(max = 240) String manufacturer,
            @RequestParam(defaultValue = "") @Size(max = 240) String issuingOrganization,
            @RequestParam(defaultValue = "") @Size(max = 160) String operatorName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.certificates(query, certificateType, inspectionType, verificationStatus, result, state, uploaded,
                issuedFrom, issuedTo, equipmentCode, certificateNumber, equipmentName, model, manufacturer,
                issuingOrganization, operatorName, cursor, limit);
    }

    @GetMapping("/certificates/import-template")
    public ResponseEntity<byte[]> certificateImportTemplate() {
        return xlsx(workbooks.certificateTemplate(), "设备检定-受控导入模板-V1.xlsx");
    }

    @PostMapping("/certificates/imports")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentWorkbookService.ImportResult importCertificates(@RequestHeader("Idempotency-Key") String key,
                                                                    @RequestParam MultipartFile file) throws IOException {
        byte[] content = file.getBytes(); String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        EquipmentWorkbookService.Fingerprint fingerprint = workbooks.fingerprint(content, filename);
        return idempotency.execute(key, "equipment-certificate-import", fingerprint,
                EquipmentWorkbookService.ImportResult.class, () -> workbooks.importCertificates(content, filename));
    }

    @GetMapping("/certificates/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> exportCertificates(@RequestParam(defaultValue = "") @Size(max = 120) String query,
                                                     @RequestParam(defaultValue = "") String certificateType) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportCertificates(query, certificateType);
        return xlsx(file.content(), file.filename());
    }

    @GetMapping("/certificates/{id}")
    public EquipmentService.CertificateView certificate(@PathVariable UUID id) { return service.requireCertificate(id); }

    @PostMapping("/{id}/certificates")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.CertificateView certificate(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody CertificateRequest body) {
        EquipmentService.CertificateWrite command = body.command();
        return idempotency.execute(key, "equipment-certificate:" + id, command,
                EquipmentService.CertificateView.class, () -> service.addCertificate(id, command));
    }

    @PutMapping("/certificates/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public EquipmentService.CertificateView updateCertificate(@PathVariable UUID id,
                                                              @Valid @RequestBody CertificateRequest body) {
        return service.updateCertificate(id, body.command());
    }

    @PostMapping("/certificates/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionCertificate(@PathVariable UUID id,
                                                  @RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody TransitionRequest body) {
        EquipmentService.Transition command = body.command();
        return idempotency.execute(key, "equipment-certificate-transition:" + id, command,
                TransitionResult.class, () -> service.transitionCertificate(id, command));
    }

    @GetMapping("/inspections")
    public CursorPage<EquipmentOperationService.InspectionView> inspections(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") @Size(max = 160) String states,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(required = false) LocalDate outboundFrom,
            @RequestParam(required = false) LocalDate outboundTo,
            @RequestParam(defaultValue = "") @Size(max = 64) String equipmentCode,
            @RequestParam(defaultValue = "") @Size(max = 200) String equipmentName,
            @RequestParam(defaultValue = "") @Size(max = 120) String model,
            @RequestParam(defaultValue = "") @Size(max = 240) String inspectionOrganization,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return operations.inspections(query, states, result, outboundFrom, outboundTo, equipmentCode,
                equipmentName, model, inspectionOrganization, cursor, limit);
    }

    @GetMapping("/inspections/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> exportInspections(@RequestParam(defaultValue = "") @Size(max = 120) String query) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportInspections(query);
        return xlsx(file.content(), file.filename());
    }

    @GetMapping("/inspections/{id}")
    public EquipmentOperationService.InspectionView inspection(@PathVariable UUID id) {
        return operations.requireInspection(id);
    }

    @PostMapping("/inspections")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public EquipmentOperationService.InspectionView createInspection(@RequestHeader("Idempotency-Key") String key,
                                                                     @Valid @RequestBody InspectionCreateRequest body) {
        EquipmentOperationService.InspectionCreate command = body.command();
        return idempotency.execute(key, "equipment-inspection-create", command,
                EquipmentOperationService.InspectionView.class, () -> operations.createInspection(command));
    }

    @PostMapping("/inspections/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transitionInspection(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody InspectionTransitionRequest body) {
        EquipmentOperationService.InspectionTransition command = body.command();
        return idempotency.execute(key, "equipment-inspection-transition:" + id, command,
                TransitionResult.class, () -> operations.transitionInspection(id, command));
    }

    @GetMapping("/borrows")
    public CursorPage<EquipmentOperationService.BorrowView> borrows(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") String beforeCondition,
            @RequestParam(defaultValue = "") String afterCondition,
            @RequestParam(required = false) LocalDate borrowedFrom,
            @RequestParam(required = false) LocalDate borrowedTo,
            @RequestParam(defaultValue = "") @Size(max = 64) String equipmentCode,
            @RequestParam(defaultValue = "") @Size(max = 200) String equipmentName,
            @RequestParam(defaultValue = "") @Size(max = 120) String model,
            @RequestParam(defaultValue = "") @Size(max = 160) String borrowerName,
            @RequestParam(defaultValue = "") @Size(max = 160) String returnerName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return operations.borrows(query, beforeCondition, afterCondition, borrowedFrom, borrowedTo, equipmentCode,
                equipmentName, model, borrowerName, returnerName, cursor, limit);
    }

    @GetMapping("/borrows/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> exportBorrows(@RequestParam(defaultValue = "") @Size(max = 120) String query) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportBorrows(query);
        return xlsx(file.content(), file.filename());
    }

    @GetMapping("/borrows/active")
    public EquipmentOperationService.BorrowView activeBorrow(@RequestParam UUID equipmentId) {
        return operations.requireActiveBorrow(equipmentId);
    }

    @GetMapping("/borrows/{id}")
    public EquipmentOperationService.BorrowView borrow(@PathVariable UUID id) { return operations.requireBorrow(id); }

    @PostMapping("/borrows")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public EquipmentOperationService.BorrowView createBorrow(@RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody BorrowCreateRequest body) {
        EquipmentOperationService.BorrowCreate command = body.command();
        return idempotency.execute(key, "equipment-borrow-create", command,
                EquipmentOperationService.BorrowView.class, () -> operations.createBorrow(command));
    }

    @PutMapping("/borrows/{id}")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public EquipmentOperationService.BorrowView updateBorrow(@PathVariable UUID id,
                                                             @Valid @RequestBody BorrowCreateRequest body) {
        return operations.updateBorrow(id, body.command());
    }

    @PostMapping("/borrows/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transitionBorrow(@PathVariable UUID id,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody BorrowTransitionRequest body) {
        EquipmentOperationService.BorrowTransition command = body.command();
        return idempotency.execute(key, "equipment-borrow-transition:" + id, command,
                TransitionResult.class, () -> operations.transitionBorrow(id, command));
    }

    @GetMapping("/usages")
    public CursorPage<EquipmentOperationService.UsageView> usages(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") String beforeCondition,
            @RequestParam(defaultValue = "") String afterCondition,
            @RequestParam(defaultValue = "") String equipmentType,
            @RequestParam(required = false) LocalDate startedFrom,
            @RequestParam(required = false) LocalDate startedTo,
            @RequestParam(defaultValue = "") @Size(max = 64) String equipmentCode,
            @RequestParam(defaultValue = "") @Size(max = 200) String equipmentName,
            @RequestParam(defaultValue = "") @Size(max = 120) String model,
            @RequestParam(defaultValue = "") @Size(max = 80) String commissionNumber,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return operations.usages(query, beforeCondition, afterCondition, equipmentType,
                startedFrom, startedTo, equipmentCode, equipmentName, model, commissionNumber, cursor, limit);
    }

    @GetMapping("/usages/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> exportUsages(@RequestParam(defaultValue = "") @Size(max = 120) String query) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportUsages(query);
        return xlsx(file.content(), file.filename());
    }

    @GetMapping("/usages/{id}")
    public EquipmentOperationService.UsageView usage(@PathVariable UUID id) { return operations.requireUsage(id); }

    @PostMapping("/usages")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public EquipmentOperationService.UsageView createUsage(@RequestHeader("Idempotency-Key") String key,
                                                           @Valid @RequestBody UsageCreateRequest body) {
        EquipmentOperationService.UsageCreate command = body.command();
        return idempotency.execute(key, "equipment-usage-create", command,
                EquipmentOperationService.UsageView.class, () -> operations.createUsage(command));
    }

    @PostMapping("/usages/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transitionUsage(@PathVariable UUID id,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody UsageTransitionRequest body) {
        EquipmentOperationService.UsageTransition command = body.command();
        return idempotency.execute(key, "equipment-usage-transition:" + id, command,
                TransitionResult.class, () -> operations.transitionUsage(id, command));
    }

    @GetMapping("/movements")
    public CursorPage<EquipmentOperationService.MovementView> movements(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") String operationType,
            @RequestParam(defaultValue = "") String movementType,
            @RequestParam(required = false) LocalDate occurredFrom,
            @RequestParam(required = false) LocalDate occurredTo,
            @RequestParam(defaultValue = "") @Size(max = 64) String equipmentCode,
            @RequestParam(defaultValue = "") @Size(max = 200) String equipmentName,
            @RequestParam(defaultValue = "") @Size(max = 120) String model,
            @RequestParam(defaultValue = "") @Size(max = 160) String personName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return operations.movements(query, operationType, movementType, occurredFrom, occurredTo,
                equipmentCode, equipmentName, model, personName, cursor, limit);
    }

    @GetMapping("/movements/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> exportMovements(@RequestParam(defaultValue = "") @Size(max = 120) String query) {
        EquipmentWorkbookService.ExportFile file = workbooks.exportMovements(query);
        return xlsx(file.content(), file.filename());
    }

    private ResponseEntity<byte[]> xlsx(byte[] content, String filename) {
        MediaType type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(content);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
