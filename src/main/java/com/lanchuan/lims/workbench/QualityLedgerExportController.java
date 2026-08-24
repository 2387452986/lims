package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quality-ledger-exports")
public class QualityLedgerExportController {
    private final QualityLedgerExportService service;
    private final IdempotencyService idempotency;

    public QualityLedgerExportController(QualityLedgerExportService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public QualityLedgerExportService.JobQueued export(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody Request body) {
        QualityLedgerExportService.ExportRequest command = body.toCommand();
        return idempotency.execute(key, "quality-ledger-export", command,
                QualityLedgerExportService.JobQueued.class, () -> service.enqueue(command, key));
    }

    public record Request(@NotBlank @Size(max = 32) String ledgerType,
                          java.time.LocalDate from, java.time.LocalDate to,
                          @Size(max = 120) String region, @Size(max = 120) String detectionProject,
                          @Size(max = 120) String commissionNumber, @Size(max = 120) String sampleNumber,
                          @Size(max = 120) String reportNumber, @Size(max = 120) String projectNumber,
                          @Size(max = 120) String projectName, @Size(max = 120) String customerName,
                          @Size(max = 40) String conclusion, @Size(max = 40) String taskState,
                          @Size(max = 120) String curingMethod, @Size(max = 120) String strengthGrade,
                          @Size(max = 40) String progress, @Size(max = 120) String taskNumber,
                          @Size(max = 120) String testerName) {
        QualityLedgerExportService.ExportRequest toCommand() {
            return new QualityLedgerExportService.ExportRequest(ledgerType, from, to, region, detectionProject,
                    commissionNumber, sampleNumber, reportNumber, projectNumber, projectName, customerName,
                    conclusion, taskState, curingMethod, strengthGrade, progress, taskNumber, testerName);
        }
    }
}
