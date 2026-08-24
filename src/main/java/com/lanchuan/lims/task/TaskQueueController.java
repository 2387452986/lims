package com.lanchuan.lims.task;

import com.lanchuan.lims.common.CursorPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/tasks/queue")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class TaskQueueController {
    private final TaskQueueService service;

    public TaskQueueController(TaskQueueService service) { this.service = service; }

    @GetMapping
    public CursorPage<TaskQueueService.Row> list(
            @RequestParam(defaultValue = "TASKS") String view,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(new TaskQueueService.Filters(view, q, state, dueFrom, dueTo, cursor, limit));
    }
}
