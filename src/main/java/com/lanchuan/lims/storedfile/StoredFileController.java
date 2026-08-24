package com.lanchuan.lims.storedfile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class StoredFileController {
    private final StoredFileService service;

    public StoredFileController(StoredFileService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public StoredFileService.View upload(@RequestParam MultipartFile file,
                                         @RequestParam @NotBlank @Size(max = 40) String purpose) {
        return service.store(file, purpose);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('FILE_DOWNLOAD')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @RequestHeader(value = "X-Download-Token", required = false)
                                             String downloadToken) {
        StoredFileService.Download download = service.download(id, downloadToken);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.metadata().mediaType()))
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.metadata().originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }
}
