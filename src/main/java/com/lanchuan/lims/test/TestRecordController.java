package com.lanchuan.lims.test;

import com.lanchuan.lims.common.IdempotencyService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tests")
public class TestRecordController {
    private final TestRecordService service;
    private final IdempotencyService idempotency;

    public TestRecordController(TestRecordService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotNull UUID taskId, UUID equipmentId, @NotEmpty Map<String, Object> values) {}
    public record UpdateRequest(UUID equipmentId, @NotEmpty Map<String, Object> values,
                                @NotNull @PositiveOrZero Long expectedVersion) {}
    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @NotNull @PositiveOrZero Long expectedVersion,
                                    @Size(max = 80) String reasonCode, @Size(max = 1000) String comment,
                                    String result) {}

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public TestRecordService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public java.util.List<TestRecordService.View> list(@RequestParam UUID commissionId) { return service.list(commissionId); }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TestRecordService.View create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateRequest body) {
        TestRecordService.Create command = new TestRecordService.Create(body.taskId(), body.equipmentId(), body.values());
        return idempotency.execute(key, "test-record-create", command, TestRecordService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TestRecordService.View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return service.update(id, new TestRecordService.Update(body.equipmentId(), body.values(), body.expectedVersion()));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION') or "
            + "(hasAuthority('TEST_CONFIRM') and (#body.action == 'CONFIRM' or #body.action == 'RETURN'))")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        TestRecordService.Transition command = new TestRecordService.Transition(body.action(), body.expectedVersion(),
                body.reasonCode(), body.comment(), body.result());
        return idempotency.execute(key, "test-record-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }
}
