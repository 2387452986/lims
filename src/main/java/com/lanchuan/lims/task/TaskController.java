package com.lanchuan.lims.task;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;
    private final IdempotencyService idempotency;

    public TaskController(TaskService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotNull UUID commissionId, @NotNull UUID sampleId,
                                @NotNull UUID parameterId, OffsetDateTime dueAt) {}
    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @NotNull @PositiveOrZero Long expectedVersion,
                                    @Size(max = 80) String reasonCode, @Size(max = 1000) String comment,
                                    UUID assignedTo) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<TaskService.View> list(@RequestParam UUID commissionId) { return service.list(commissionId); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public TaskService.View get(@PathVariable UUID id) { return service.require(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TaskService.View create(@RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody CreateRequest body) {
        TaskService.Create command = new TaskService.Create(body.commissionId(), body.sampleId(), body.parameterId(), body.dueAt());
        return idempotency.execute(key, "task-create", command, TaskService.View.class, () -> service.create(command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        TaskService.Transition command = new TaskService.Transition(body.action(), body.expectedVersion(),
                body.reasonCode(), body.comment(), body.assignedTo());
        return idempotency.execute(key, "task-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }
}
