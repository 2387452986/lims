package com.lanchuan.lims.test;

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
@RequestMapping("/api/tests/ledger")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class TestLedgerController {
    private final TestLedgerService service;

    public TestLedgerController(TestLedgerService service) { this.service = service; }

    @GetMapping
    public CursorPage<TestLedgerService.Row> list(
            @RequestParam(defaultValue = "TEST_LEDGER") String view,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(new TestLedgerService.Filters(view, q, state, result, from, to, cursor, limit));
    }
}
