package com.lanchuan.lims.audit;

import com.lanchuan.lims.common.CursorPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) { this.service = service; }

    @GetMapping("/facets")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public AuditQueryService.Facets facets() { return service.facets(); }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public CursorPage<AuditQueryService.Event> list(
            @RequestParam(defaultValue = "") String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "") String operationData,
            @RequestParam(defaultValue = "") String business,
            @RequestParam(defaultValue = "") String operationType,
            @RequestParam(defaultValue = "") String resourceType,
            @RequestParam(defaultValue = "") String actorName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        String effectiveAccount = account.isBlank() ? actorName : account;
        return service.list(new AuditQueryService.Filters(effectiveAccount, from, to, operationData, business,
                operationType, resourceType, cursor, limit));
    }
}
