package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/standards")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class StandardController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final StandardService service;
    private final StandardWorkbookService workbooks;
    private final IdempotencyService idempotency;

    public StandardController(StandardService service, StandardWorkbookService workbooks,
                              IdempotencyService idempotency) {
        this.service = service;
        this.workbooks = workbooks;
        this.idempotency = idempotency;
    }

    public record WriteRequest(@NotBlank @Size(max = 240) String code,
                               @NotBlank @Size(max = 500) String name,
                               @NotBlank @Size(max = 24) String ownershipType,
                               @NotBlank @Size(max = 24) String basisType,
                               @NotBlank @Size(max = 24) String standardType,
                               @Size(max = 240) String issuingInstitution,
                               @Size(max = 1000) String citedBasis,
                               @NotNull LocalDate effectiveDate,
                               LocalDate repealDate,
                               @NotBlank @Size(max = 24) String standardStatus,
                               UUID documentFileId,
                               @Size(max = 5000) List<@NotNull UUID> parameterIds,
                               @PositiveOrZero long expectedVersion) {
        StandardService.Write command() {
            return new StandardService.Write(code, name, ownershipType, basisType, standardType,
                    issuingInstitution, citedBasis, effectiveDate, repealDate, standardStatus,
                    documentFileId, parameterIds == null ? List.of() : parameterIds, expectedVersion);
        }
    }

    @GetMapping
    public CursorPage<StandardService.Summary> list(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") @Size(max = 24) String basisType,
            @RequestParam(defaultValue = "") @Size(max = 24) String standardType,
            @RequestParam(defaultValue = "") @Size(max = 24) String standardStatus,
            @RequestParam(defaultValue = "") @Size(max = 24) String state,
            @RequestParam(required = false) LocalDate effectiveFrom,
            @RequestParam(required = false) LocalDate effectiveTo,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(query, basisType, standardType, standardStatus, state,
                effectiveFrom, effectiveTo, cursor, limit);
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return xlsx(workbooks.template(), "标准库-受控导入模板-V1.xlsx");
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "") @Size(max = 120) String query,
            @RequestParam(defaultValue = "") @Size(max = 24) String basisType,
            @RequestParam(defaultValue = "") @Size(max = 24) String standardType,
            @RequestParam(defaultValue = "") @Size(max = 24) String standardStatus,
            @RequestParam(defaultValue = "") @Size(max = 24) String state,
            @RequestParam(required = false) LocalDate effectiveFrom,
            @RequestParam(required = false) LocalDate effectiveTo) {
        StandardWorkbookService.ExportFile file = workbooks.export(query, basisType, standardType, standardStatus,
                state, effectiveFrom, effectiveTo);
        return xlsx(file.content(), file.filename());
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public StandardWorkbookService.ImportResult importWorkbook(@RequestHeader("Idempotency-Key") String key,
                                                                @RequestParam MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        StandardWorkbookService.ImportFingerprint fingerprint = workbooks.fingerprint(content, filename);
        return idempotency.execute(key, "detection-standard-import", fingerprint,
                StandardWorkbookService.ImportResult.class, () -> workbooks.importWorkbook(content, filename));
    }

    @GetMapping("/{id}")
    public StandardService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/{id}/revisions")
    public List<StandardService.View> revisions(@PathVariable UUID id) { return service.revisions(id); }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public StandardService.View create(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody WriteRequest body) {
        StandardService.Write command = body.command();
        return idempotency.execute(key, "detection-standard-create", command, StandardService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public StandardService.View update(@PathVariable UUID id, @Valid @RequestBody WriteRequest body) {
        return service.update(id, body.command());
    }

    @PostMapping("/{id}/revisions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public StandardService.View revise(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "detection-standard-revise:" + id, id,
                StandardService.View.class, () -> service.revise(id));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "detection-standard-transition:" + id, command,
                TransitionResult.class, () -> service.transition(id, command));
    }

    private ResponseEntity<byte[]> xlsx(byte[] content, String filename) {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }
}
