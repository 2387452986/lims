package com.lanchuan.lims.test;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/data-change-requests")
public class DataChangeRequestController {
    private final DataChangeRequestService service;
    private final IdempotencyService idempotency;

    public DataChangeRequestController(DataChangeRequestService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotNull UUID testRecordId, @NotBlank @Size(max = 80) String reasonCode,
                                @NotBlank @Size(max = 1000) String reason, UUID equipmentId,
                                @NotEmpty Map<String, Object> values) {}
    public record DecisionRequest(@NotBlank String action, @NotNull @PositiveOrZero Long expectedVersion,
                                  @Size(max = 1000) String comment) {}
    public record ApplyRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public CursorPage<DataChangeRequestService.View> list(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(cursor, limit);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public DataChangeRequestService.View create(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CreateRequest body) {
        DataChangeRequestService.Create command = new DataChangeRequestService.Create(body.testRecordId(),
                body.reasonCode(), body.reason(), body.equipmentId(), body.values());
        return idempotency.execute(key, "data-change-create", command, DataChangeRequestService.View.class,
                () -> service.create(command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('DATA_CHANGE_APPROVE')")
    public TransitionResult decide(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody DecisionRequest body) {
        DataChangeRequestService.Decision command = new DataChangeRequestService.Decision(
                body.action(), body.expectedVersion(), body.comment());
        return idempotency.execute(key, "data-change-transition:" + id, command, TransitionResult.class,
                () -> service.decide(id, command));
    }

    @PostMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TestRecordService.View apply(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody ApplyRequest body) {
        return idempotency.execute(key, "data-change-apply:" + id, body, TestRecordService.View.class,
                () -> service.apply(id, body.expectedVersion()));
    }
}
