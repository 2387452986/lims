package com.lanchuan.lims.sample;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
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

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/samples")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class SampleController {
    private final SampleService service;
    private final IdempotencyService idempotency;

    public SampleController(SampleService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record CreateRequest(@NotNull UUID commissionId, @NotNull UUID sampleGroupId,
                                @NotBlank @Size(max = 200) String name,
                                @Size(max = 200) String specification, @NotBlank String quantity,
                                @NotBlank @Size(max = 24) String unit,
                                LocalDate samplingDate, LocalDate moldingDate, LocalDate plannedTestDate,
                                @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(3650) Integer ageDays,
                                @Size(max = 80) String curingMethod, @Size(max = 80) String strengthGrade,
                                @Size(max = 240) String structurePart, @Size(max = 160) String chipCode,
                                String representedQuantity, @Size(max = 24) String representedUnit) {}
    public record SplitRequest(@NotBlank @Size(max = 200) String name, @NotBlank String quantity,
                               @NotNull @PositiveOrZero Long expectedVersion, @Size(max = 1000) String comment) {}
    public record ConsumeRequest(@NotBlank String quantity, @NotNull @PositiveOrZero Long expectedVersion,
                                 @Size(max = 1000) String comment) {}

    @GetMapping
    public List<SampleService.View> list(@RequestParam UUID commissionId) { return service.list(commissionId); }

    @GetMapping("/{id}")
    public SampleService.View get(@PathVariable UUID id) { return service.require(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public SampleService.View create(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CreateRequest body) {
        SampleService.Create command = new SampleService.Create(body.commissionId(), body.sampleGroupId(), body.name(),
                body.specification(), body.quantity(), body.unit(), body.samplingDate(), body.moldingDate(),
                body.plannedTestDate(), body.ageDays(), body.curingMethod(), body.strengthGrade(),
                body.structurePart(), body.chipCode(), body.representedQuantity(), body.representedUnit());
        return idempotency.execute(key, "sample-create", command, SampleService.View.class, () -> service.create(command));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "sample-transition:" + id, body, TransitionResult.class,
                () -> service.transition(id, body));
    }

    @PostMapping("/{id}/split")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public SampleService.View split(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody SplitRequest body) {
        SampleService.Split command = new SampleService.Split(body.name(), body.quantity(), body.expectedVersion(), body.comment());
        return idempotency.execute(key, "sample-split:" + id, command, SampleService.View.class, () -> service.split(id, command));
    }

    @PostMapping("/{id}/consume")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public SampleService.View consume(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody ConsumeRequest body) {
        SampleService.Consume command = new SampleService.Consume(body.quantity(), body.expectedVersion(), body.comment());
        return idempotency.execute(key, "sample-consume:" + id, command, SampleService.View.class, () -> service.consume(id, command));
    }
}
