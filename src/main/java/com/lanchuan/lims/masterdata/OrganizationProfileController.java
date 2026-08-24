package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/organization-profile")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class OrganizationProfileController {
    private final OrganizationProfileService service;
    private final IdempotencyService idempotency;

    public OrganizationProfileController(OrganizationProfileService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    public OrganizationProfileService.Profile profile() {
        return service.profile();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public OrganizationProfileService.Profile update(
            @Valid @RequestBody OrganizationProfileService.ProfileWrite body) {
        return service.update(body);
    }

    @PostMapping("/business-license")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public OrganizationProfileService.Profile bindBusinessLicense(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationProfileService.BusinessLicenseWrite body) {
        return idempotency.execute(key, "organization-business-license", body,
                OrganizationProfileService.Profile.class, () -> service.bindBusinessLicense(body));
    }

    @PostMapping("/electronic-signature-enrollment")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public void electronicSignatureEnrollment(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationProfileService.ElectronicSignatureEnrollment body) {
        idempotency.execute(key, "organization-esign-enrollment", body, Void.class, () -> {
            service.requestElectronicSignatureEnrollment(body);
            return null;
        });
    }

    @GetMapping("/office-addresses")
    public List<OrganizationProfileService.OfficeAddressView> officeAddresses(
            @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.officeAddresses(includeRetired);
    }

    @PostMapping("/office-addresses")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public OrganizationProfileService.OfficeAddressView createOfficeAddress(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrganizationProfileService.OfficeAddressWrite body) {
        return idempotency.execute(key, "organization-office-address-create", body,
                OrganizationProfileService.OfficeAddressView.class, () -> service.createOfficeAddress(body));
    }

    @PutMapping("/office-addresses/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public OrganizationProfileService.OfficeAddressView updateOfficeAddress(
            @PathVariable UUID id,
            @Valid @RequestBody OrganizationProfileService.OfficeAddressWrite body) {
        return service.updateOfficeAddress(id, body);
    }

    @PostMapping("/office-addresses/{id}/transitions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public TransitionResult transitionOfficeAddress(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "organization-office-address-transition:" + id, body,
                TransitionResult.class, () -> service.transitionOfficeAddress(id, body));
    }

    @GetMapping("/office-addresses/{id}/history")
    public List<OrganizationProfileService.OfficeAddressRevision> officeAddressHistory(@PathVariable UUID id) {
        return service.officeAddressHistory(id);
    }
}
