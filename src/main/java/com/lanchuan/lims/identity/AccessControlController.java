package com.lanchuan.lims.identity;

import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/access-control")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class AccessControlController {
    private final AccessControlService accessControl;
    private final AccountAdministrationService accounts;
    private final IdempotencyService idempotency;

    public AccessControlController(AccessControlService accessControl, AccountAdministrationService accounts,
                                   IdempotencyService idempotency) {
        this.accessControl = accessControl;
        this.accounts = accounts;
        this.idempotency = idempotency;
    }

    public record RoleAssignment(@NotEmpty List<UUID> roles,
                                 @NotNull @PositiveOrZero Long expectedVersion) {}
    public record SessionRevocation(@NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping("/catalog")
    public AccessControlService.Catalog catalog() { return accessControl.catalog(); }

    @GetMapping("/roles")
    public List<AccessControlService.RoleView> roles() { return accessControl.roles(); }

    @GetMapping("/roles/{id}")
    public AccessControlService.RoleView role(@PathVariable UUID id) { return accessControl.require(id); }

    @GetMapping("/roles/{id}/history")
    public List<AccessControlService.RoleRevisionView> history(@PathVariable UUID id) {
        return accessControl.history(id);
    }

    @PostMapping("/roles")
    public AccessControlService.RoleView create(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody AccessControlService.CreateRole body) {
        return idempotency.execute(key, "access-role-create", body,
                AccessControlService.RoleView.class, () -> accessControl.create(body));
    }

    @PutMapping("/roles/{id}")
    public AccessControlService.RoleView update(@PathVariable UUID id,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody AccessControlService.UpdateRole body) {
        return idempotency.execute(key, "access-role-update:" + id, body,
                AccessControlService.RoleView.class, () -> accessControl.update(id, body));
    }

    @PostMapping("/roles/{id}/transitions")
    public TransitionResult transition(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransitionCommand body) {
        return idempotency.execute(key, "access-role-transition:" + id, body,
                TransitionResult.class, () -> accessControl.transition(id, body));
    }

    @GetMapping("/users")
    public List<AccessControlService.UserView> users() { return accessControl.users(); }

    @PutMapping("/users/{id}/roles")
    public AccessControlService.UserView userRoles(@PathVariable UUID id,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody RoleAssignment body) {
        return idempotency.execute(key, "access-user-role-assignment:" + id, body,
                AccessControlService.UserView.class, () -> {
                    accounts.updateRoles(id, body.roles(), body.expectedVersion());
                    return user(id);
                });
    }

    @PutMapping("/users/{id}/sessions/revoke")
    public AccessControlService.UserView revokeSessions(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody SessionRevocation body) {
        return idempotency.execute(key, "access-user-session-revoke:" + id, body,
                AccessControlService.UserView.class, () -> {
                    accounts.revokeSessions(id, body.expectedVersion());
                    return user(id);
                });
    }

    private AccessControlService.UserView user(UUID id) {
        return accessControl.users().stream().filter(user -> user.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "账号不存在"));
    }
}
