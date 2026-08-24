package com.lanchuan.lims.report;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/signature-schemes")
@PreAuthorize("hasAuthority('SIGNING_ASSET_MANAGE')")
public class SignatureSchemeController {
    private final SignatureSchemeService service;
    private final IdempotencyService idempotency;

    public SignatureSchemeController(SignatureSchemeService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record PositionRequest(@NotBlank @Size(max = 20) String positionType,
                                  @NotNull UUID signingAssetId,
                                  @NotBlank @Size(max = 120) String label,
                                  @NotBlank @Size(max = 20) String pageRule,
                                  @NotNull BigDecimal xMm, @NotNull BigDecimal yMm,
                                  @NotNull BigDecimal widthMm, @NotNull BigDecimal heightMm,
                                  int sortOrder) {}
    public record CreateRequest(@NotBlank @Size(max = 160) String name,
                                @Size(max = 1000) String remark,
                                @Valid List<PositionRequest> positions) {}
    public record UpdateRequest(@NotBlank @Size(max = 160) String name,
                                @Size(max = 1000) String remark,
                                @Valid List<PositionRequest> positions,
                                @PositiveOrZero long expectedVersion) {}
    public record TransitionRequest(@NotBlank @Size(max = 80) String action,
                                    @PositiveOrZero long expectedVersion,
                                    @Size(max = 80) String reasonCode,
                                    @Size(max = 1000) String comment) {}

    @GetMapping
    public List<SignatureSchemeService.View> list() { return service.list(); }

    @GetMapping("/{id}")
    public SignatureSchemeService.View get(@PathVariable UUID id) { return service.require(id); }

    @PostMapping
    public SignatureSchemeService.View create(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody CreateRequest body) {
        SignatureSchemeService.Create command = new SignatureSchemeService.Create(body.name(), body.remark(),
                positions(body.positions()));
        return idempotency.execute(key, "signature-scheme-create", command, SignatureSchemeService.View.class,
                () -> service.create(command));
    }

    @PutMapping("/{id}")
    public SignatureSchemeService.View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return service.update(id, new SignatureSchemeService.Update(body.name(), body.remark(),
                positions(body.positions()), body.expectedVersion()));
    }

    @PostMapping("/{id}/revisions")
    public SignatureSchemeService.View revise(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "signature-scheme-revise:" + id, id,
                SignatureSchemeService.View.class, () -> service.revise(id));
    }

    @PostMapping("/{id}/transitions")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionRequest body) {
        SignatureSchemeService.Transition command = new SignatureSchemeService.Transition(body.action(),
                body.expectedVersion(), body.reasonCode(), body.comment());
        return idempotency.execute(key, "signature-scheme-transition:" + id, command, TransitionResult.class,
                () -> service.transition(id, command));
    }

    private List<SignatureSchemeService.PositionWrite> positions(List<PositionRequest> values) {
        if (values == null) return List.of();
        return values.stream().map(item -> new SignatureSchemeService.PositionWrite(item.positionType(),
                item.signingAssetId(), item.label(), item.pageRule(), item.xMm(), item.yMm(), item.widthMm(),
                item.heightMm(), item.sortOrder())).toList();
    }
}
