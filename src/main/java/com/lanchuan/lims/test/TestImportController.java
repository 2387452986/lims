package com.lanchuan.lims.test;

import com.lanchuan.lims.common.IdempotencyService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/test-imports")
public class TestImportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final TestImportService service;
    private final IdempotencyService idempotency;

    public TestImportController(TestImportService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("受控试验数据导入模板-V1.xlsx", StandardCharsets.UTF_8).build().toString())
                .body(service.template());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_TRANSITION')")
    public TestImportService.ImportResult upload(@RequestHeader("Idempotency-Key") String key,
                                                  @RequestParam MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        TestImportService.ImportFingerprint fingerprint = service.fingerprint(content, filename);
        return idempotency.execute(key, "test-record-import", fingerprint, TestImportService.ImportResult.class,
                () -> service.importWorkbook(content, filename));
    }
}
