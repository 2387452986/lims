package com.lanchuan.lims.report;

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
@RequestMapping("/api/reports/queue")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class ReportQueueController {
    private final ReportQueueService service;

    public ReportQueueController(ReportQueueService service) { this.service = service; }

    @GetMapping
    public CursorPage<ReportQueueService.Row> list(
            @RequestParam(defaultValue = "REPORT_SEARCH") String view,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String states,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(new ReportQueueService.Filters(view, q, states, from, to, cursor, limit));
    }
}
