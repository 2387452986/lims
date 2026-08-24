package com.lanchuan.lims.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/document-template-controls")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class DocumentTemplateControlController {
    private final DocumentTemplateControlService service;

    public DocumentTemplateControlController(DocumentTemplateControlService service) {
        this.service = service;
    }

    public record UpdateRequest(@Size(max = 160) String controlledNumber,
                                @Size(max = 120) String templateVersionNumber,
                                @NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping
    public DocumentTemplateControlService.PageView list(
            @RequestParam String documentType,
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(required = false) UUID catalogNodeId,
            @RequestParam(defaultValue = "ALL") String linkStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(documentType, q, catalogNodeId, linkStatus, page, limit);
    }

    @PutMapping("/{id}")
    public DocumentTemplateControlService.ItemView update(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody UpdateRequest body) {
        return service.update(id, key, new DocumentTemplateControlService.UpdateCommand(
                body.controlledNumber(), body.templateVersionNumber(), body.expectedVersion()));
    }
}
