package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/system-experience")
public class SystemExperienceController {
    private final SystemExperienceService service;
    private final IdempotencyService idempotency;

    public SystemExperienceController(SystemExperienceService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record VersionRequest(@NotNull Long expectedVersion) {}

    @GetMapping("/desktop-themes/active")
    @PreAuthorize("hasAuthority('MODULE_READ')")
    public SystemExperienceService.ThemeView activeTheme() { return service.activeTheme(); }

    @GetMapping("/desktop-themes")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public List<SystemExperienceService.ThemeView> themes() { return service.themes(); }

    @PostMapping("/desktop-themes")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.ThemeView createTheme(@RequestHeader("Idempotency-Key") String key,
                                                          @Valid @RequestBody SystemExperienceService.ThemeWrite body) {
        return idempotency.execute(key, "desktop-theme-create", body, SystemExperienceService.ThemeView.class,
                () -> service.createTheme(body));
    }

    @PutMapping("/desktop-themes/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.ThemeView updateTheme(@PathVariable UUID id,
                                                          @Valid @RequestBody SystemExperienceService.ThemeWrite body) {
        return service.updateTheme(id, body);
    }

    @PostMapping("/desktop-themes/{id}/revisions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.ThemeView reviseTheme(@PathVariable UUID id,
                                                          @RequestHeader("Idempotency-Key") String key,
                                                          @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "desktop-theme-revise:" + id, body,
                SystemExperienceService.ThemeView.class, () -> service.reviseTheme(id, body.expectedVersion()));
    }

    @PostMapping("/desktop-themes/{id}/transitions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public TransitionResult transitionTheme(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "desktop-theme-transition:" + id, body, TransitionResult.class,
                () -> service.transitionTheme(id, body.action(), body.expectedVersion(),
                        body.reasonCode(), body.comment()));
    }

    @GetMapping("/detection-projects")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public List<SystemExperienceService.DetectionProjectOption> detectionProjects(
            @RequestParam(defaultValue = "") String q) {
        return service.detectionProjects(q);
    }

    @GetMapping("/safety-briefings")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public CursorPage<SystemExperienceService.BriefingView> briefings(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.briefings(q, state, cursor, limit);
    }

    @PostMapping("/safety-briefings")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.BriefingView createBriefing(@RequestHeader("Idempotency-Key") String key,
                                                                @Valid @RequestBody SystemExperienceService.BriefingWrite body) {
        return idempotency.execute(key, "safety-briefing-create", body,
                SystemExperienceService.BriefingView.class, () -> service.createBriefing(body));
    }

    @PutMapping("/safety-briefings/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.BriefingView updateBriefing(@PathVariable UUID id,
                                                                @Valid @RequestBody SystemExperienceService.BriefingWrite body) {
        return service.updateBriefing(id, body);
    }

    @PostMapping("/safety-briefings/{id}/revisions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.BriefingView reviseBriefing(@PathVariable UUID id,
                                                                @RequestHeader("Idempotency-Key") String key,
                                                                @Valid @RequestBody VersionRequest body) {
        return idempotency.execute(key, "safety-briefing-revise:" + id, body,
                SystemExperienceService.BriefingView.class,
                () -> service.reviseBriefing(id, body.expectedVersion()));
    }

    @PostMapping("/safety-briefings/{id}/transitions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public TransitionResult transitionBriefing(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "safety-briefing-transition:" + id, body, TransitionResult.class,
                () -> service.transitionBriefing(id, body.action(), body.expectedVersion(),
                        body.reasonCode(), body.comment()));
    }

    @GetMapping("/transition-time-settings")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public List<SystemExperienceService.TransitionTimeView> transitionTimes() { return service.transitionTimes(); }

    @PutMapping("/transition-time-settings/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public SystemExperienceService.TransitionTimeView updateTransitionTime(
            @PathVariable UUID id, @Valid @RequestBody SystemExperienceService.TransitionTimeWrite body) {
        return service.updateTransitionTime(id, body);
    }

    @GetMapping("/transition-warnings")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public List<SystemExperienceService.TransitionWarning> transitionWarnings() {
        return service.transitionWarnings(null);
    }
}
