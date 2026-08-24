package com.lanchuan.lims.finance;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/finance")
public class ContractFinanceController {
    private final ContractFinanceService service;
    private final IdempotencyService idempotency;

    public ContractFinanceController(ContractFinanceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record TransitionRequest(@NotBlank String action, @NotNull Long expectedVersion,
                                    @Size(max = 80) String reasonCode, @Size(max = 2000) String comment) {}
    public record VoidEntryRequest(@NotNull Long expectedVersion, @NotNull Long entryExpectedVersion,
                                   @NotBlank @Size(max = 1000) String reason) {}

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ContractFinanceService.Options options() { return service.options(); }

    @GetMapping("/contracts")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ContractFinanceService.ContractView> contracts(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String contractType, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedTo,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.contracts(q, state, contractType, customerId, projectId, signedFrom, signedTo, cursor, limit);
    }

    @GetMapping("/contracts/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ContractFinanceService.ContractView contract(@PathVariable UUID id) { return service.contract(id); }

    @PostMapping("/contracts")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.ContractView createContract(@RequestHeader("Idempotency-Key") String key,
                                                               @Valid @RequestBody ContractFinanceService.ContractWrite body) {
        return idempotency.execute(key, "contract-create", body, ContractFinanceService.ContractView.class,
                () -> service.createContract(body));
    }

    @PutMapping("/contracts/{id}")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.ContractView updateContract(@PathVariable UUID id,
                                                               @Valid @RequestBody ContractFinanceService.ContractWrite body) {
        return service.updateContract(id, body);
    }

    @PostMapping("/contracts/{id}/transitions")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public TransitionResult transitionContract(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionRequest body) {
        return idempotency.execute(key, "contract-transition:" + id, body, TransitionResult.class,
                () -> service.transitionContract(id, body.action(), body.expectedVersion(), body.reasonCode(), body.comment()));
    }

    @GetMapping("/contracts/{id}/entries")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<ContractFinanceService.EntryView> entries(@PathVariable UUID id) { return service.entries(id); }

    @PostMapping("/contracts/{id}/entries")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.EntryView addEntry(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody ContractFinanceService.EntryWrite body) {
        return idempotency.execute(key, "contract-entry-create:" + id, body,
                ContractFinanceService.EntryView.class, () -> service.addEntry(id, body));
    }

    @PostMapping("/contracts/{contractId}/entries/{entryId}/void")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.EntryView voidEntry(@PathVariable UUID contractId, @PathVariable UUID entryId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody VoidEntryRequest body) {
        return idempotency.execute(key, "contract-entry-void:" + entryId, body,
                ContractFinanceService.EntryView.class,
                () -> service.voidEntry(contractId, entryId, body.expectedVersion(), body.entryExpectedVersion(), body.reason()));
    }

    @GetMapping("/price-templates")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ContractFinanceService.TemplateView> templates(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.templates(q, state, cursor, limit);
    }

    @GetMapping("/price-templates/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ContractFinanceService.TemplateView template(@PathVariable UUID id) { return service.template(id); }

    @PostMapping("/price-templates")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.TemplateView createTemplate(@RequestHeader("Idempotency-Key") String key,
                                                               @Valid @RequestBody ContractFinanceService.TemplateWrite body) {
        return idempotency.execute(key, "price-template-create", body, ContractFinanceService.TemplateView.class,
                () -> service.createTemplate(body));
    }

    @PutMapping("/price-templates/{id}")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.TemplateView updateTemplate(@PathVariable UUID id,
                                                               @Valid @RequestBody ContractFinanceService.TemplateWrite body) {
        return service.updateTemplate(id, body);
    }

    @PostMapping("/price-templates/{id}/revisions")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public ContractFinanceService.TemplateView reviseTemplate(@PathVariable UUID id,
                                                               @RequestHeader("Idempotency-Key") String key,
                                                               @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "price-template-revise:" + id, body,
                ContractFinanceService.TemplateView.class, () -> service.reviseTemplate(id, body.expectedVersion()));
    }

    @PostMapping("/price-templates/{id}/transitions")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    public TransitionResult transitionTemplate(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody TransitionRequest body) {
        return idempotency.execute(key, "price-template-transition:" + id, body, TransitionResult.class,
                () -> service.transitionTemplate(id, body.action(), body.expectedVersion(), body.comment()));
    }

    @GetMapping("/fees")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ContractFinanceService.FeeView> fees(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) UUID contractId, @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.fees(q, state, contractId, projectId, cursor, limit);
    }

    @PostMapping("/contracts/{id}/fees/recalculate")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public List<ContractFinanceService.FeeView> recalculateFees(@PathVariable UUID id,
                                                                @RequestHeader("Idempotency-Key") String key,
                                                                @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "fee-recalculate:" + id, body,
                new tools.jackson.core.type.TypeReference<List<ContractFinanceService.FeeView>>() {},
                () -> service.recalculateFees(id, body.expectedVersion()));
    }

    public record VersionRequest(@NotNull Long expectedVersion) {}

    @PostMapping("/fees/{id}/transitions")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public TransitionResult transitionFee(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody ContractFinanceService.FeeTransition body) {
        return idempotency.execute(key, "fee-transition:" + id, body, TransitionResult.class,
                () -> service.transitionFee(id, body));
    }

    @GetMapping("/receipts")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ContractFinanceService.ReceiptView> receipts(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) UUID contractId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.receipts(q, state, contractId, cursor, limit);
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public ContractFinanceService.ReceiptView createReceipt(@RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody ContractFinanceService.ReceiptWrite body) {
        return idempotency.execute(key, "receipt-create", body, ContractFinanceService.ReceiptView.class,
                () -> service.createReceipt(body));
    }

    @PutMapping("/receipts/{id}")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public ContractFinanceService.ReceiptView updateReceipt(@PathVariable UUID id,
                                                             @Valid @RequestBody ContractFinanceService.ReceiptWrite body) {
        return service.updateReceipt(id, body);
    }

    @PostMapping("/receipts/{id}/transitions")
    @PreAuthorize("hasAnyAuthority('FINANCE_MANAGE','FINANCE_APPROVE')")
    public TransitionResult transitionReceipt(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionRequest body) {
        return idempotency.execute(key, "receipt-transition:" + id, body, TransitionResult.class,
                () -> service.transitionReceipt(id, body.action(), body.expectedVersion(), body.comment()));
    }

    @PostMapping("/receipts/{id}/refunds")
    @PreAuthorize("hasAuthority('FINANCE_APPROVE')")
    public ContractFinanceService.ReceiptView refundReceipt(@PathVariable UUID id,
                                                             @RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody ContractFinanceService.RefundWrite body) {
        return idempotency.execute(key, "receipt-refund:" + id, body, ContractFinanceService.ReceiptView.class,
                () -> service.refundReceipt(id, body));
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<ContractFinanceService.PaymentView> payments(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) UUID contractId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.payments(q, state, contractId, cursor, limit);
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public ContractFinanceService.PaymentView createPayment(@RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody ContractFinanceService.PaymentWrite body) {
        return idempotency.execute(key, "payment-create", body, ContractFinanceService.PaymentView.class,
                () -> service.createPayment(body));
    }

    @PutMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('FINANCE_MANAGE')")
    public ContractFinanceService.PaymentView updatePayment(@PathVariable UUID id,
                                                             @Valid @RequestBody ContractFinanceService.PaymentWrite body) {
        return service.updatePayment(id, body);
    }

    @PostMapping("/payments/{id}/transitions")
    @PreAuthorize("hasAnyAuthority('FINANCE_MANAGE','FINANCE_APPROVE')")
    public TransitionResult transitionPayment(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody ContractFinanceService.PaymentTransition body) {
        return idempotency.execute(key, "payment-transition:" + id, body, TransitionResult.class,
                () -> service.transitionPayment(id, body));
    }

    @GetMapping("/analysis")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ContractFinanceService.Analysis analysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.analysis(from, to);
    }

    @GetMapping("/my-projects")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<ContractFinanceService.MyProject> myProjects(@RequestParam(defaultValue = "") String q) {
        return service.myProjects(q);
    }

    @GetMapping("/my-projects/{projectId}/reports")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<ContractFinanceService.ProjectReport> projectReports(@PathVariable UUID projectId) {
        return service.projectReports(projectId);
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ContractFinanceService.JobQueued export(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody ContractFinanceService.FinanceExportWrite body) {
        return idempotency.execute(key, "finance-export", body, ContractFinanceService.JobQueued.class,
                () -> service.enqueueExport(body, key));
    }
}
