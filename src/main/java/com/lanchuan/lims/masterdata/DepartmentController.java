package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/master-data/departments")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class DepartmentController {
    private final DepartmentService service;
    private final IdempotencyService idempotency;

    public DepartmentController(DepartmentService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@Size(max = 40) String code,
                                @NotBlank @Size(max = 160) String name,
                                UUID parentId,
                                @Min(0) @Max(999999) int sortOrder,
                                UUID responsiblePersonId,
                                UUID supervisingPersonId,
                                @NotNull @Size(max = 2000) List<UUID> detectionProjectNodeIds) {
        DepartmentService.Create toCommand() {
            return new DepartmentService.Create(code, name, parentId, sortOrder, responsiblePersonId,
                    supervisingPersonId, detectionProjectNodeIds);
        }
    }

    public record UpdateRequest(@NotBlank @Size(max = 160) String name,
                                UUID parentId,
                                @Min(0) @Max(999999) int sortOrder,
                                UUID responsiblePersonId,
                                UUID supervisingPersonId,
                                @NotNull @Size(max = 2000) List<UUID> detectionProjectNodeIds,
                                @PositiveOrZero long expectedVersion) {
        DepartmentService.Update toCommand() {
            return new DepartmentService.Update(name, parentId, sortOrder, responsiblePersonId,
                    supervisingPersonId, detectionProjectNodeIds, expectedVersion);
        }
    }

    @GetMapping
    public List<DepartmentService.View> list() {
        return service.list();
    }

    @GetMapping("/options")
    public DepartmentService.Options options() {
        return service.options();
    }

    @GetMapping("/{id}")
    public DepartmentService.View get(@PathVariable UUID id) {
        return service.require(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DepartmentService.View create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateRequest body) {
        DepartmentService.Create command = body.toCommand();
        return idempotency.execute(key, "department-create", command, DepartmentService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DepartmentService.View update(@PathVariable UUID id,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody UpdateRequest body) {
        DepartmentService.Update command = body.toCommand();
        return idempotency.execute(key, "department-update:" + id, command, DepartmentService.View.class,
                () -> service.update(id, command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transition(@PathVariable UUID id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "department-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }
}
