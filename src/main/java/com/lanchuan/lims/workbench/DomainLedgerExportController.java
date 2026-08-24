package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.report.ReportQueueService;
import com.lanchuan.lims.sample.SampleLedgerService;
import com.lanchuan.lims.task.TaskQueueService;
import com.lanchuan.lims.test.TestLedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@PreAuthorize("hasAuthority('EXPORT_DATA')")
public class DomainLedgerExportController {
    private final DomainLedgerExportService service;
    private final IdempotencyService idempotency;

    public DomainLedgerExportController(DomainLedgerExportService service, IdempotencyService idempotency) {
        this.service = service; this.idempotency = idempotency;
    }

    public record SampleExport(@Size(max=40) String view, @Size(max=100) String q, @Size(max=40) String state,
                               LocalDate from, LocalDate to) {}
    public record TaskExport(@Size(max=40) String view, @Size(max=100) String q, @Size(max=40) String state,
                             LocalDate dueFrom, LocalDate dueTo) {}
    public record TestExport(@Size(max=40) String view, @Size(max=100) String q, @Size(max=40) String state,
                             @Size(max=40) String result, LocalDate from, LocalDate to) {}
    public record ReportExport(@Size(max=40) String view, @Size(max=100) String q, @Size(max=200) String states,
                               LocalDate from, LocalDate to) {}

    @PostMapping("/api/samples/exports")
    public DomainLedgerExportService.JobQueued sample(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody SampleExport body) {
        SampleLedgerService.Filters command = new SampleLedgerService.Filters(body.view(), body.q(), body.state(),
                body.from(), body.to(), "", 1);
        return idempotency.execute(key, "sample-ledger-export", command, DomainLedgerExportService.JobQueued.class,
                () -> service.enqueueSample(command, key));
    }

    @PostMapping("/api/tasks/exports")
    public DomainLedgerExportService.JobQueued task(@RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody TaskExport body) {
        TaskQueueService.Filters command = new TaskQueueService.Filters(body.view(), body.q(), body.state(),
                body.dueFrom(), body.dueTo(), "", 1);
        return idempotency.execute(key, "task-queue-export", command, DomainLedgerExportService.JobQueued.class,
                () -> service.enqueueTask(command, key));
    }

    @PostMapping("/api/tests/exports")
    public DomainLedgerExportService.JobQueued test(@RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody TestExport body) {
        TestLedgerService.Filters command = new TestLedgerService.Filters(body.view(), body.q(), body.state(),
                body.result(), body.from(), body.to(), "", 1);
        return idempotency.execute(key, "test-ledger-export", command, DomainLedgerExportService.JobQueued.class,
                () -> service.enqueueTest(command, key));
    }

    @PostMapping("/api/reports/exports")
    public DomainLedgerExportService.JobQueued report(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody ReportExport body) {
        ReportQueueService.Filters command = new ReportQueueService.Filters(body.view(), body.q(), body.states(),
                body.from(), body.to(), "", 1);
        return idempotency.execute(key, "report-queue-export", command, DomainLedgerExportService.JobQueued.class,
                () -> service.enqueueReport(command, key));
    }
}
