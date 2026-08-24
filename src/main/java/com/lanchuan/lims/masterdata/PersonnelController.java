package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/personnel")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class PersonnelController {
    private final PersonnelService service;
    private final PersonnelTransferService transfers;
    private final IdempotencyService idempotency;

    public PersonnelController(PersonnelService service, PersonnelTransferService transfers,
                               IdempotencyService idempotency) {
        this.service = service;
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @GetMapping
    public CursorPage<PersonnelService.PersonnelSummary> list(
            @RequestParam(defaultValue = "") @Size(max = 24) String accountState,
            @RequestParam(defaultValue = "") @Size(max = 24) String applicationState,
            @RequestParam(defaultValue = "") @Size(max = 24) String personType,
            @RequestParam(defaultValue = "") @Size(max = 120) String name,
            @RequestParam(defaultValue = "") @Size(max = 120) String position,
            @RequestParam(required = false) @Size(max = 2000) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(accountState, applicationState, personType, name, position, cursor, limit);
    }

    @GetMapping("/options")
    public PersonnelService.PersonnelOptions options() {
        return service.options();
    }

    @GetMapping("/{id}")
    public PersonnelService.PersonnelDetail detail(@PathVariable UUID id) {
        return service.require(id);
    }

    @GetMapping("/{id}/permission-overview")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public com.lanchuan.lims.identity.AccountAdministrationService.PermissionOverview permissionOverview(
            @PathVariable UUID id) {
        return service.permissionOverview(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERSON_ADMIN') and hasAuthority('PERSON_SENSITIVE_READ')")
    public PersonnelService.PersonnelDetail create(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody PersonnelService.PersonnelCreate body) {
        return idempotency.execute(key, "personnel-create", body, PersonnelService.PersonnelDetail.class,
                () -> service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERSON_ADMIN') and hasAuthority('PERSON_SENSITIVE_READ')")
    public PersonnelService.PersonnelDetail update(@PathVariable UUID id,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody PersonnelService.PersonnelUpdate body) {
        return idempotency.execute(key, "personnel-update:" + id, body, PersonnelService.PersonnelDetail.class,
                () -> service.update(id, body));
    }

    @PostMapping("/{id}/account")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public PersonnelService.PersonnelDetail openAccount(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody PersonnelService.AccountOpen body) {
        return idempotency.execute(key, "personnel-account-open:" + id, body,
                PersonnelService.PersonnelDetail.class, () -> service.openAccount(id, body));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "personnel-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @PostMapping("/batch-transitions")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public PersonnelService.BatchTransitionResult batchTransition(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PersonnelService.BatchTransitionCommand body) {
        return idempotency.execute(key, "personnel-batch-transition", body,
                PersonnelService.BatchTransitionResult.class, () -> service.batchTransition(body));
    }

    @PostMapping("/import-template")
    @PreAuthorize("hasAuthority('PERSON_IMPORT')")
    public com.lanchuan.lims.storedfile.StoredFileService.View importTemplate(
            @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "personnel-import-template", java.util.Map.of(),
                com.lanchuan.lims.storedfile.StoredFileService.View.class, transfers::createTemplate);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAuthority('PERSON_IMPORT') and hasAuthority('PERSON_ADMIN') and hasAuthority('PERSON_SENSITIVE_READ')")
    public PersonnelTransferService.ImportResult importWorkbook(@RequestHeader("Idempotency-Key") String key,
                                                                @RequestParam MultipartFile file) {
        byte[] content = transfers.readUpload(file);
        PersonnelTransferService.ImportFingerprint fingerprint = transfers.fingerprint(content);
        return idempotency.execute(key, "personnel-import", fingerprint,
                PersonnelTransferService.ImportResult.class, () -> transfers.importWorkbook(content));
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('PERSON_EXPORT')")
    public PersonnelTransferService.JobQueued export(@RequestHeader("Idempotency-Key") String key,
                                                     @RequestBody PersonnelTransferService.ExportRequest body) {
        return idempotency.execute(key, "personnel-export", body, PersonnelTransferService.JobQueued.class,
                () -> transfers.enqueueExport(body, key));
    }
}
