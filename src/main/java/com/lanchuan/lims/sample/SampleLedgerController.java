package com.lanchuan.lims.sample;

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
@RequestMapping("/api/samples/ledger")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class SampleLedgerController {
    private final SampleLedgerService service;

    public SampleLedgerController(SampleLedgerService service) { this.service = service; }

    @GetMapping
    public CursorPage<SampleLedgerService.Row> list(
            @RequestParam(defaultValue = "SAMPLE_LEDGER") String view,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(new SampleLedgerService.Filters(view, q, state, from, to, cursor, limit));
    }
}
