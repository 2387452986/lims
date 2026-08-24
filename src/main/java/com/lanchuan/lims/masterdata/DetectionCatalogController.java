package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class DetectionCatalogController {
    private final DetectionCatalogService service;
    private final IdempotencyService idempotency;

    public DetectionCatalogController(DetectionCatalogService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record NodeUpdateRequest(@NotBlank @Size(max = 240) String name, @Size(max = 80) String symbol,
                                    @Min(0) int sortOrder, @PositiveOrZero long expectedVersion) {}
    public record NodeCreateRequest(UUID parentId, @NotBlank String type,
                                    @NotBlank @Size(max = 240) String name, @Size(max = 80) String symbol,
                                    @Min(0) int sortOrder) {}
    public record NodeMoveRequest(@NotNull UUID parentId, @Min(0) int sortOrder,
                                  @PositiveOrZero long expectedVersion) {}
    public record RevisionUpdateRequest(@Size(max = 1000) String conclusion,
                                        @Size(max = 2000) String defaultNote,
                                        @PositiveOrZero long expectedVersion) {}
    public record ParameterRequest(@NotBlank @Size(max = 80) String code,
                                   @NotBlank @Size(max = 200) String name,
                                   @NotBlank String valueType,
                                   @NotNull Map<String, Object> definition,
                                   @Size(max = 1000) String formula,
                                   @Size(max = 40) String unit,
                                   @Min(0) int sortOrder,
                                   @Size(max = 1000) String conclusion,
                                   @Size(max = 2000) String defaultNote,
                                   @Size(max = 80) String pricingMethod,
                                   String price,
                                   @Size(max = 240) String sampleName,
                                   @PositiveOrZero Integer promisedDays,
                                   @Size(max = 1000) String sampleDescription,
                                   String sampleQuantity,
                                   @Size(max = 500) String postTestTreatment,
                                   @PositiveOrZero Integer retentionDays,
                                   String retentionQuantity,
                                   @Size(max = 120) String retentionNature,
                                   boolean defaultCommissioned,
                                   boolean required,
                                   boolean enabled,
                                   @Size(max = 2000) String remark,
                                   @NotNull List<UUID> standardIds,
                                   @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.ParameterMutation toCommand() {
            return new DetectionCatalogService.ParameterMutation(code, name, valueType, definition, formula, unit,
                    sortOrder, conclusion, defaultNote, pricingMethod, price, sampleName, promisedDays,
                    sampleDescription, sampleQuantity, postTestTreatment, retentionDays, retentionQuantity,
                    retentionNature, defaultCommissioned, required, enabled, remark, standardIds, expectedVersion);
        }
    }
    public record BatchTargetRequest(@NotNull UUID id, @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.BatchTarget toCommand() {
            return new DetectionCatalogService.BatchTarget(id, expectedVersion);
        }
    }
    public record BatchParameterRequest(@NotEmpty List<@Valid BatchTargetRequest> targets,
                                        Boolean defaultCommissioned, Boolean required, Boolean enabled,
                                        @Size(max = 80) String pricingMethod, String price) {
        DetectionCatalogService.BatchParameterMutation toCommand() {
            return new DetectionCatalogService.BatchParameterMutation(
                    targets.stream().map(BatchTargetRequest::toCommand).toList(), defaultCommissioned,
                    required, enabled, pricingMethod, price);
        }
    }
    public record PricingRequest(@NotNull UUID id, @NotBlank @Size(max = 80) String pricingMethod,
                                 String price, @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.PricingMutation toCommand() {
            return new DetectionCatalogService.PricingMutation(id, pricingMethod, price, expectedVersion);
        }
    }
    public record PricingQuoteRequest(@NotNull UUID parameterId, @NotBlank String quantity) {
        DetectionCatalogService.PricingQuoteItem toCommand() {
            return new DetectionCatalogService.PricingQuoteItem(parameterId, quantity);
        }
    }
    public record GroupRequest(@NotBlank @Size(max = 80) String code,
                               @NotBlank @Size(max = 240) String name,
                               @Min(0) int sortOrder, @NotBlank String status,
                               @NotEmpty List<UUID> parameterIds,
                               @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.GroupMutation toCommand() {
            return new DetectionCatalogService.GroupMutation(code, name, sortOrder, status, parameterIds, expectedVersion);
        }
    }
    public record MarkerRequest(@NotBlank @Size(max = 80) String code,
                                @NotBlank @Size(max = 240) String name,
                                @NotNull Map<String, Object> content, @NotBlank String status,
                                @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.MarkerMutation toCommand() {
            return new DetectionCatalogService.MarkerMutation(code, name, content, status, expectedVersion);
        }
    }
    public record SampleFieldRequest(@NotBlank @Size(max = 80) String code,
                                     @NotBlank @Size(max = 240) String label,
                                     @NotBlank String fieldType, boolean required,
                                     @Size(max = 40) String unit,
                                     @Size(max = 240) String placeholder,
                                     @NotNull @Size(max = 200) List<@Size(max = 240) String> options,
                                     @NotBlank String coreBinding,
                                     @Min(0) int sortOrder,
                                     @NotNull List<UUID> standardIds,
                                     @PositiveOrZero long expectedVersion) {
        DetectionCatalogService.SampleFieldMutation toCommand() {
            return new DetectionCatalogService.SampleFieldMutation(code, label, fieldType, required, unit,
                    placeholder, options, coreBinding, sortOrder, standardIds, expectedVersion);
        }
    }
    public record ConfigurationLinkRequest(@NotNull UUID configurationId, @NotBlank String role,
                                           @PositiveOrZero long expectedRevisionVersion) {
        DetectionCatalogService.ConfigurationLink toCommand() {
            return new DetectionCatalogService.ConfigurationLink(configurationId, role, expectedRevisionVersion);
        }
    }

    @GetMapping("/api/detection-catalog/tree")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<DetectionCatalogService.CatalogNode> tree(
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "false") boolean includeHistory,
            @RequestParam(required = false) UUID parentId) {
        return service.tree(query, includeInactive, includeHistory, parentId);
    }

    @PostMapping("/api/detection-catalog/nodes")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.CatalogNode createNode(@RequestHeader("Idempotency-Key") String key,
                                                          @Valid @RequestBody NodeCreateRequest body) {
        DetectionCatalogService.NodeCreate command = new DetectionCatalogService.NodeCreate(
                body.parentId(), body.type(), body.name(), body.symbol(), body.sortOrder());
        return idempotency.execute(key, "detection-catalog-node-create", command,
                DetectionCatalogService.CatalogNode.class, () -> service.createNode(command));
    }

    @PutMapping("/api/detection-catalog/nodes/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.CatalogNode updateNode(@PathVariable UUID id,
                                                          @Valid @RequestBody NodeUpdateRequest body) {
        return service.updateNode(id, new DetectionCatalogService.NodeUpdate(
                body.name(), body.symbol(), body.sortOrder(), body.expectedVersion()));
    }

    @PostMapping("/api/detection-catalog/nodes/{id}/move")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.CatalogNode moveNode(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody NodeMoveRequest body) {
        DetectionCatalogService.NodeMove command = new DetectionCatalogService.NodeMove(
                body.parentId(), body.sortOrder(), body.expectedVersion());
        return idempotency.execute(key, "detection-catalog-move:" + id, command,
                DetectionCatalogService.CatalogNode.class, () -> service.moveNode(id, command));
    }

    @PostMapping("/api/detection-catalog/nodes/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionNode(@PathVariable UUID id,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "detection-catalog-transition:" + id, command,
                TransitionResult.class, () -> service.transitionNode(id, command));
    }

    @GetMapping("/api/detection-project-revisions/{id}")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public DetectionCatalogService.ProjectRevisionView revision(@PathVariable UUID id) {
        return service.requireRevision(id);
    }

    @PutMapping("/api/detection-project-revisions/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView updateRevision(@PathVariable UUID id,
                                                                      @Valid @RequestBody RevisionUpdateRequest body) {
        return service.updateRevision(id, new DetectionCatalogService.RevisionUpdate(
                body.conclusion(), body.defaultNote(), body.expectedVersion()));
    }

    @PostMapping("/api/detection-project-revisions/{id}/revisions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView revise(@PathVariable UUID id,
                                                               @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute(key, "detection-project-revision:" + id, Map.of("id", id),
                DetectionCatalogService.ProjectRevisionView.class, () -> service.revise(id));
    }

    @PostMapping("/api/detection-project-revisions/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionRevision(@PathVariable UUID id,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "detection-project-transition:" + id, command,
                TransitionResult.class, () -> service.transitionRevision(id, command));
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/parameters")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ParameterView createParameter(@PathVariable UUID revisionId,
                                                                 @RequestHeader("Idempotency-Key") String key,
                                                                 @Valid @RequestBody ParameterRequest body) {
        DetectionCatalogService.ParameterMutation command = body.toCommand();
        return idempotency.execute(key, "detection-parameter-create:" + revisionId, command,
                DetectionCatalogService.ParameterView.class, () -> service.createParameter(revisionId, command));
    }

    @PutMapping("/api/detection-project-revisions/{revisionId}/parameters/{parameterId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ParameterView updateParameter(@PathVariable UUID revisionId,
                                                                 @PathVariable UUID parameterId,
                                                                 @Valid @RequestBody ParameterRequest body) {
        return service.updateParameter(revisionId, parameterId, body.toCommand());
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/parameters/batch")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public List<DetectionCatalogService.ParameterView> batchParameters(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BatchParameterRequest body) {
        DetectionCatalogService.BatchParameterMutation command = body.toCommand();
        return idempotency.execute(key, "detection-parameter-batch:" + revisionId, command,
                new tools.jackson.core.type.TypeReference<List<DetectionCatalogService.ParameterView>>() {},
                () -> service.batchUpdateParameters(revisionId, command));
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/parameter-prices")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public List<DetectionCatalogService.ParameterView> updatePrices(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody @NotEmpty List<@Valid PricingRequest> body) {
        List<DetectionCatalogService.PricingMutation> command = body.stream().map(PricingRequest::toCommand).toList();
        return idempotency.execute(key, "detection-parameter-pricing:" + revisionId, command,
                new tools.jackson.core.type.TypeReference<List<DetectionCatalogService.ParameterView>>() {},
                () -> service.updatePrices(revisionId, command));
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/parameter-prices/quote")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public DetectionCatalogService.PricingQuote quotePrices(
            @PathVariable UUID revisionId, @RequestBody @NotEmpty List<@Valid PricingQuoteRequest> body) {
        return service.quote(revisionId, body.stream().map(PricingQuoteRequest::toCommand).toList());
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/groups")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView createGroup(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody GroupRequest body) {
        DetectionCatalogService.GroupMutation command = body.toCommand();
        return idempotency.execute(key, "detection-parameter-group-create:" + revisionId, command,
                DetectionCatalogService.ProjectRevisionView.class, () -> service.createGroup(revisionId, command));
    }

    @PutMapping("/api/detection-project-revisions/{revisionId}/groups/{groupId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView updateGroup(
            @PathVariable UUID revisionId, @PathVariable UUID groupId,
            @Valid @RequestBody GroupRequest body) {
        return service.updateGroup(revisionId, groupId, body.toCommand());
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/markers")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView createMarker(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MarkerRequest body) {
        DetectionCatalogService.MarkerMutation command = body.toCommand();
        return idempotency.execute(key, "detection-sample-marker-create:" + revisionId, command,
                DetectionCatalogService.ProjectRevisionView.class, () -> service.createMarker(revisionId, command));
    }

    @PutMapping("/api/detection-project-revisions/{revisionId}/markers/{markerId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView updateMarker(
            @PathVariable UUID revisionId, @PathVariable UUID markerId,
            @Valid @RequestBody MarkerRequest body) {
        return service.updateMarker(revisionId, markerId, body.toCommand());
    }

    @PostMapping("/api/detection-project-revisions/{revisionId}/sample-fields")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView createSampleField(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SampleFieldRequest body) {
        DetectionCatalogService.SampleFieldMutation command = body.toCommand();
        return idempotency.execute(key, "detection-sample-field-create:" + revisionId, command,
                DetectionCatalogService.ProjectRevisionView.class,
                () -> service.createSampleField(revisionId, command));
    }

    @PutMapping("/api/detection-project-revisions/{revisionId}/sample-fields/{fieldId}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView updateSampleField(
            @PathVariable UUID revisionId, @PathVariable UUID fieldId,
            @Valid @RequestBody SampleFieldRequest body) {
        return service.updateSampleField(revisionId, fieldId, body.toCommand());
    }

    @PutMapping("/api/detection-project-revisions/{revisionId}/configuration-link")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public DetectionCatalogService.ProjectRevisionView linkConfiguration(
            @PathVariable UUID revisionId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ConfigurationLinkRequest body) {
        DetectionCatalogService.ConfigurationLink command = body.toCommand();
        return idempotency.execute(key, "detection-configuration-link:" + revisionId + ":" + body.role(), command,
                DetectionCatalogService.ProjectRevisionView.class, () -> service.linkConfiguration(revisionId, command));
    }

    @GetMapping("/api/detection-standards")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public List<DetectionCatalogService.StandardView> standards(
            @RequestParam(defaultValue = "") @Size(max = 100) String query) {
        return service.standards(query);
    }
}
