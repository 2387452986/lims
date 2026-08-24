package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class CustomerController {
    private final CustomerDirectoryService service;
    private final IdempotencyService idempotency;

    public CustomerController(CustomerDirectoryService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotBlank @Size(max = 40) String code,
                               @NotBlank @Size(max = 120) String shortName,
                               @NotBlank @Size(max = 200) String name,
                               @NotBlank @Size(max = 32) String unitType,
                               @Size(max = 32) String unifiedCreditCode,
                               boolean commissioningUnit,
                               @Size(max = 120) String contactName,
                               @Size(max = 40) String contactPhone,
                               @Size(max = 40) String landline,
                               @Size(max = 500) String address,
                               @Size(max = 40) String fax,
                               @Email @Size(max = 254) String email,
                               @Size(max = 2000) String remark,
                               boolean active,
                               @PositiveOrZero Long expectedVersion) {
        CustomerDirectoryService.Write command() {
            return new CustomerDirectoryService.Write(code, shortName, name, unitType, unifiedCreditCode,
                    commissioningUnit, contactName, contactPhone, landline, address, fax, email, remark,
                    active, expectedVersion);
        }
    }

    public record SettlementContactRequest(@NotBlank @Size(max = 120) String name,
                                           @Size(max = 40) String phone,
                                           boolean otherContact,
                                           boolean primaryContact,
                                           boolean active,
                                           @PositiveOrZero Long expectedVersion) {
        CustomerDirectoryService.SettlementContactWrite command() {
            return new CustomerDirectoryService.SettlementContactWrite(name, phone, otherContact,
                    primaryContact, active, expectedVersion);
        }
    }

    public record PersonRequest(@NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 12) String gender,
                                @NotBlank @Size(max = 32) String personType,
                                @Size(max = 40) String phone,
                                @Size(max = 120) String certificateNumber,
                                @Size(max = 1000) String remark,
                                UUID avatarFileId,
                                boolean active,
                                @PositiveOrZero Long expectedVersion) {
        CustomerDirectoryService.PersonWrite command() {
            return new CustomerDirectoryService.PersonWrite(name, gender, personType, phone,
                    certificateNumber, remark, avatarFileId, active, expectedVersion);
        }
    }

    public record AccountLinkRequest(@NotNull UUID userId, @NotBlank @Size(max = 24) String relationship) {}

    @GetMapping
    public CursorPage<CustomerDirectoryService.Summary> list(
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = "") @Size(max = 32) String unitType,
            @RequestParam(defaultValue = "") @Size(max = 40) String code,
            @RequestParam(defaultValue = "") @Size(max = 200) String name,
            @RequestParam(defaultValue = "") @Size(max = 120) String settlementName,
            @RequestParam(defaultValue = "") @Size(max = 40) String settlementPhone,
            @RequestParam(defaultValue = "") @Size(max = 120) String contactName,
            @RequestParam(defaultValue = "") @Size(max = 40) String contactPhone,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(query, unitType, code, name, settlementName, settlementPhone,
                contactName, contactPhone, active, cursor, limit);
    }

    @GetMapping("/{id}")
    public CustomerDirectoryService.Detail detail(@PathVariable UUID id) { return service.detail(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.Detail create(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "customer-directory-create", body.command(),
                CustomerDirectoryService.Detail.class, () -> service.create(body.command()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.Detail update(@PathVariable UUID id,
                                                  @RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody WriteRequest body) {
        return idempotency.execute(key, "customer-directory-update:" + id, body.command(),
                CustomerDirectoryService.Detail.class, () -> service.update(id, body.command()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "customer-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @PostMapping("/{id}/settlement-contacts")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.SettlementContactView addSettlementContact(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SettlementContactRequest body) {
        return idempotency.execute(key, "customer-settlement-create:" + id, body.command(),
                CustomerDirectoryService.SettlementContactView.class,
                () -> service.saveSettlementContact(id, null, body.command()));
    }

    @PutMapping("/{id}/settlement-contacts/{contactId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.SettlementContactView updateSettlementContact(
            @PathVariable UUID id, @PathVariable UUID contactId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SettlementContactRequest body) {
        return idempotency.execute(key, "customer-settlement-update:" + contactId, body.command(),
                CustomerDirectoryService.SettlementContactView.class,
                () -> service.saveSettlementContact(id, contactId, body.command()));
    }

    @PostMapping("/{id}/people")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.PersonView addPerson(@PathVariable UUID id,
                                                         @RequestHeader("Idempotency-Key") String key,
                                                         @Valid @RequestBody PersonRequest body) {
        return idempotency.execute(key, "customer-person-create:" + id, body.command(),
                CustomerDirectoryService.PersonView.class,
                () -> service.savePerson(id, null, body.command()));
    }

    @PutMapping("/{id}/people/{personId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerDirectoryService.PersonView updatePerson(@PathVariable UUID id, @PathVariable UUID personId,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody PersonRequest body) {
        return idempotency.execute(key, "customer-person-update:" + personId, body.command(),
                CustomerDirectoryService.PersonView.class,
                () -> service.savePerson(id, personId, body.command()));
    }

    @PostMapping("/{id}/accounts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public CustomerDirectoryService.AccountView linkAccount(@PathVariable UUID id,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody AccountLinkRequest body) {
        return idempotency.execute(key, "customer-account-link:" + id + ":" + body.userId(), body,
                CustomerDirectoryService.AccountView.class,
                () -> service.linkAccount(id, body.userId(), body.relationship()));
    }

    @DeleteMapping("/{id}/accounts/{userId}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkAccount(@PathVariable UUID id, @PathVariable UUID userId) {
        service.unlinkAccount(id, userId);
    }
}
