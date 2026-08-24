package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/warnings")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class WarningRecordController {
    private final WarningRecordService service;
    private final IdempotencyService idempotency;

    public WarningRecordController(WarningRecordService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record ResolveRequest(@NotBlank @Size(max = 40) String action,
                                 @NotNull @PositiveOrZero Long expectedVersion,
                                 @NotBlank @Size(max = 2000) String description,
                                 @Size(max = 20) List<UUID> attachmentFileIds) {}

    @GetMapping
    public CursorPage<WarningRecordService.View> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "") String warningType,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") @Size(max = 120) String q,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(new WarningRecordService.Filters(from, to, warningType, state, q, cursor, limit));
    }

    @GetMapping("/{id}")
    public WarningRecordService.View detail(@PathVariable UUID id) { return service.require(id); }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('WARNING_RESOLVE')")
    public WarningRecordService.View transition(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody ResolveRequest body) {
        if (!body.action().trim().equalsIgnoreCase("RESOLVE")) {
            throw new com.lanchuan.lims.common.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "WARNING_ACTION_INVALID", "预警仅支持处置动作 RESOLVE");
        }
        WarningRecordService.Resolve command = new WarningRecordService.Resolve(
                body.expectedVersion(), body.description(), body.attachmentFileIds());
        return idempotency.execute(key, "warning-transition:" + id, body,
                WarningRecordService.View.class, () -> service.resolve(id, command));
    }
}
