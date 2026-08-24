package com.lanchuan.lims.task;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.configuration.SystemExperienceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks/{taskId}/safety-briefings")
public class TaskSafetyBriefingController {
    private final SystemExperienceService service;
    private final IdempotencyService idempotency;

    public TaskSafetyBriefingController(SystemExperienceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record StartRequest(@NotNull @PositiveOrZero Long ruleVersion) {}
    public record AcknowledgeRequest(@NotNull @PositiveOrZero Long ruleVersion,
                                     @NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<SystemExperienceService.TaskBriefing> list(@PathVariable UUID taskId) {
        return service.taskBriefings(taskId);
    }

    @PostMapping("/{ruleId}/read-sessions")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public SystemExperienceService.TaskBriefing start(@PathVariable UUID taskId, @PathVariable UUID ruleId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody StartRequest body) {
        return idempotency.execute(key, "task-briefing-start:" + taskId + ":" + ruleId, body,
                SystemExperienceService.TaskBriefing.class,
                () -> service.startBriefingRead(taskId, ruleId, body.ruleVersion()));
    }

    @PostMapping("/{ruleId}/acknowledgements")
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public SystemExperienceService.TaskBriefing acknowledge(@PathVariable UUID taskId, @PathVariable UUID ruleId,
                                                             @RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody AcknowledgeRequest body) {
        return idempotency.execute(key, "task-briefing-ack:" + taskId + ":" + ruleId, body,
                SystemExperienceService.TaskBriefing.class,
                () -> service.acknowledgeBriefing(taskId, ruleId, body.ruleVersion(), body.expectedVersion()));
    }
}
