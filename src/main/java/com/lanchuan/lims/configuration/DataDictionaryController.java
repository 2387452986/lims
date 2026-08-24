package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.TransitionCommand;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class DataDictionaryController {
    private final DataDictionaryService service;

    public DataDictionaryController(DataDictionaryService service) {
        this.service = service;
    }

    public record CreateRequest(UUID parentId, @NotBlank @Size(max = 240) String name,
                                boolean allowsChildren) {}
    public record ValueRequest(@PositiveOrZero Integer sortOrder, @NotBlank @Size(max = 1000) String value,
                               @Size(max = 2000) String remark) {}
    public record UpdateRequest(@NotBlank @Size(max = 240) String name, boolean allowsChildren,
                                @NotNull @Size(max = 1000) List<@Valid ValueRequest> values,
                                @NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping("/data-dictionaries/tree")
    public DataDictionaryService.TreeResponse tree(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.tree(q, includeRetired);
    }

    @GetMapping("/data-dictionary-revisions/{revisionId}")
    public DataDictionaryService.DetailView detail(@PathVariable UUID revisionId) {
        return service.detail(revisionId);
    }

    @PostMapping("/data-dictionaries")
    public DataDictionaryService.DetailView create(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody CreateRequest body) {
        return service.create(key, new DataDictionaryService.CreateCommand(
                body.parentId(), body.name(), body.allowsChildren()));
    }

    @PostMapping("/data-dictionaries/{seriesId}/revisions")
    public DataDictionaryService.DetailView revise(@PathVariable UUID seriesId,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return service.revise(seriesId, key);
    }

    @PutMapping("/data-dictionary-revisions/{revisionId}")
    public DataDictionaryService.DetailView update(@PathVariable UUID revisionId,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody UpdateRequest body) {
        return service.update(revisionId, key, new DataDictionaryService.UpdateCommand(
                body.name(), body.allowsChildren(), body.values().stream()
                .map(value -> new DataDictionaryService.ValueCommand(
                        value.sortOrder(), value.value(), value.remark())).toList(), body.expectedVersion()));
    }

    @PostMapping("/data-dictionary-revisions/{revisionId}/transitions")
    public TransitionResult transition(@PathVariable UUID revisionId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return service.transition(revisionId, key, body);
    }
}
