package com.lanchuan.lims.report;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/signing-assets")
@PreAuthorize("hasAuthority('SIGNING_ASSET_MANAGE')")
public class SigningAssetController {
    private final SigningAssetService service;
    private final IdempotencyService idempotency;

    public SigningAssetController(SigningAssetService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record UpdateRequest(@NotBlank @Size(max = 160) String name, UUID ownerPersonId,
                                @Size(max = 80) String engineeringType, @Size(max = 40) String sealType,
                                @NotNull LocalDate validFrom, @NotNull LocalDate validUntil,
                                @PositiveOrZero long expectedVersion) {}
    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @PositiveOrZero long expectedVersion,
                                    @Size(max = 80) String reasonCode,
                                    @Size(max = 1000) String comment) {}
    private record UploadFingerprint(SigningAssetService.Create metadata, String mediaType,
                                     String sha256, int width, int height) {}

    @GetMapping
    public List<SigningAssetService.View> list(@RequestParam(required = false) String assetType,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String verificationState,
                                               @RequestParam(required = false) Boolean active,
                                               @RequestParam(required = false) LocalDate createdFrom,
                                               @RequestParam(required = false) LocalDate createdTo) {
        return service.list(assetType, q, verificationState, active, createdFrom, createdTo);
    }

    @GetMapping("/{id}")
    public SigningAssetService.View get(@PathVariable UUID id) { return service.require(id); }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        SigningAssetService.AssetContent content = service.content(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, content.mediaType())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(content.content());
    }

    @PostMapping
    public SigningAssetService.View store(@RequestHeader("Idempotency-Key") String key,
                                          @RequestParam @NotBlank String assetType,
                                          @RequestParam @NotBlank @Size(max = 160) String name,
                                          @RequestParam(required = false) UUID ownerPersonId,
                                          @RequestParam(required = false) @Size(max = 80) String engineeringType,
                                          @RequestParam(required = false) @Size(max = 40) String sealType,
                                          @RequestParam LocalDate validFrom,
                                          @RequestParam LocalDate validUntil,
                                          @RequestParam MultipartFile file) {
        SigningAssetService.Create command = new SigningAssetService.Create(assetType, name, ownerPersonId,
                engineeringType, sealType, validFrom, validUntil);
        SigningAssetService.Upload upload = service.readUpload(file);
        UploadFingerprint fingerprint = new UploadFingerprint(command, upload.mediaType(), upload.sha256(),
                upload.width(), upload.height());
        return idempotency.execute(key, "signing-asset-create", fingerprint, SigningAssetService.View.class,
                () -> service.store(command, upload));
    }

    @PutMapping("/{id}")
    public SigningAssetService.View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return service.update(id, new SigningAssetService.Update(body.name(), body.ownerPersonId(),
                body.engineeringType(), body.sealType(), body.validFrom(), body.validUntil(), body.expectedVersion()));
    }

    @PostMapping("/{id}/transitions")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        SigningAssetService.Transition command = new SigningAssetService.Transition(body.action(),
                body.expectedVersion(), body.reasonCode(), body.comment());
        return idempotency.execute(key, "signing-asset-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }
}
