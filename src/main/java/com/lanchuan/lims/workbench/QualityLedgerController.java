package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.CursorPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/quality-ledgers")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class QualityLedgerController {
    private final QualityLedgerService service;

    public QualityLedgerController(QualityLedgerService service) { this.service = service; }

    @GetMapping("/specimens")
    public CursorPage<QualityLedgerService.SpecimenRow> specimens(
            @RequestParam String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate commissionedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate commissionedTo,
            @RequestParam(defaultValue = "") @Size(max = 120) String region,
            @RequestParam(defaultValue = "") @Size(max = 120) String detectionProject,
            @RequestParam(defaultValue = "") @Size(max = 120) String commissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String sampleNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String reportNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectName,
            @RequestParam(defaultValue = "") @Size(max = 120) String customerName,
            @RequestParam(defaultValue = "") String conclusion,
            @RequestParam(defaultValue = "") String taskState,
            @RequestParam(defaultValue = "") @Size(max = 120) String curingMethod,
            @RequestParam(defaultValue = "") @Size(max = 120) String strengthGrade,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.specimens(new QualityLedgerService.SpecimenFilters(view, commissionedFrom, commissionedTo,
                region, detectionProject, commissionNumber, sampleNumber, reportNumber, projectNumber,
                projectName, customerName, conclusion, taskState, curingMethod, strengthGrade, cursor, limit));
    }

    @GetMapping("/rebound-assessments")
    public CursorPage<QualityLedgerService.ReboundRow> rebounds(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate commissionedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate commissionedTo,
            @RequestParam(defaultValue = "") @Size(max = 120) String commissionNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String sampleNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectName,
            @RequestParam(defaultValue = "") @Size(max = 120) String customerName,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.rebounds(new QualityLedgerService.ReboundFilters(commissionedFrom, commissionedTo,
                commissionNumber, sampleNumber, projectNumber, projectName, customerName, cursor, limit));
    }

    @GetMapping("/field-tests")
    public CursorPage<QualityLedgerService.FieldTestRow> fieldTests(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String progress,
            @RequestParam(defaultValue = "") @Size(max = 120) String detectionProject,
            @RequestParam(defaultValue = "") @Size(max = 120) String taskNumber,
            @RequestParam(defaultValue = "") @Size(max = 120) String projectName,
            @RequestParam(defaultValue = "") @Size(max = 120) String testerName,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.fieldTests(new QualityLedgerService.FieldTestFilters(from, to, progress,
                detectionProject, taskNumber, projectName, testerName, cursor, limit));
    }
}
