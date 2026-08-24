package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.storedfile.StoredFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.util.Map;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/person-qualifications")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class PersonnelQualificationController {
    private final PersonnelQualificationService service;
    private final PersonnelQualificationTransferService transfers;
    private final IdempotencyService idempotency;

    public PersonnelQualificationController(PersonnelQualificationService service,
                                            PersonnelQualificationTransferService transfers,
                                            IdempotencyService idempotency) {
        this.service = service;
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @GetMapping
    public CursorPage<PersonnelQualificationService.View> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String specialty,
            @RequestParam(defaultValue = "") String hasFile,
            @RequestParam(defaultValue = "") String issuer,
            @RequestParam(defaultValue = "") String personName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(status, specialty, hasFile, issuer, personName, cursor, limit);
    }

    @GetMapping("/options")
    public PersonnelQualificationService.Options options() {
        return service.options();
    }

    @GetMapping("/{id}")
    public PersonnelQualificationService.View detail(@PathVariable UUID id) {
        return service.require(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public PersonnelQualificationService.View create(@RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody PersonnelQualificationService.Create body) {
        return idempotency.execute(key, "person-qualification-create", body,
                PersonnelQualificationService.View.class, () -> service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public PersonnelQualificationService.View update(@PathVariable UUID id,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody PersonnelQualificationService.Update body) {
        return idempotency.execute(key, "person-qualification-update:" + id, body,
                PersonnelQualificationService.View.class, () -> service.update(id, body));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "person-qualification-transition:" + id, body,
                TransitionResult.class, () -> service.transition(id, body));
    }

    @PostMapping("/batch-transitions")
    @PreAuthorize("hasAuthority('PERSON_ADMIN')")
    public PersonnelQualificationService.BatchResult batch(@RequestHeader("Idempotency-Key") String key,
                                                           @Valid @RequestBody PersonnelQualificationService.BatchCommand body) {
        return idempotency.execute(key, "person-qualification-batch-transition", body,
                PersonnelQualificationService.BatchResult.class, () -> service.batch(body));
    }

    @PostMapping("/import-template")
    @PreAuthorize("hasAuthority('PERSON_IMPORT')")
    public StoredFileService.View importTemplate(@RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "person-qualification-import-template", Map.of(),
                StoredFileService.View.class, transfers::createTemplate);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAuthority('PERSON_IMPORT') and hasAuthority('PERSON_ADMIN')")
    public PersonnelQualificationTransferService.ImportResult importWorkbook(
            @RequestHeader("Idempotency-Key") String key, @RequestParam MultipartFile file) {
        byte[] content = transfers.readUpload(file);
        PersonnelQualificationTransferService.Fingerprint fingerprint = transfers.fingerprint(content);
        return idempotency.execute(key, "person-qualification-import", fingerprint,
                PersonnelQualificationTransferService.ImportResult.class,
                () -> transfers.importWorkbook(content));
    }
}
