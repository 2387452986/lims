package com.lanchuan.lims.identity;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final CurrentActor actor;
    private final AuditService audit;
    private final RecentPasswordService recentPassword;

    public AuthController(AccountRepository accounts, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                          CurrentActor actor, AuditService audit, RecentPasswordService recentPassword) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.actor = actor;
        this.audit = audit;
        this.recentPassword = recentPassword;
    }

    public record LoginRequest(@NotBlank @Size(max = 80) String username,
                               @NotBlank @Size(max = 128) String password) {}
    public record PasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                  @NotBlank @Size(max = 128) String newPassword) {}
    public record VerifyPasswordRequest(@NotBlank @Size(max = 128) String password) {}
    public record CurrentUserResponse(String id, String username, String displayName, String departmentId,
                                      String departmentName,
                                      List<String> roles, List<String> permissions, boolean forcePasswordChange) {}

    @PostMapping("/login")
    @Operation(summary = "账号密码登录", security = {})
    @Transactional
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String username = body.username().trim();
        String remote = request.getRemoteAddr();
        if (accounts.rateLimited(username, remote)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "登录尝试过于频繁，请15分钟后重试");
        }
        AccountRepository.Account account = accounts.find(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误"));
        if (!account.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "账号已临时锁定，请稍后重试");
        }
        if (!encoder.matches(body.password(), account.passwordHash())) {
            accounts.failed(account, remote);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        accounts.succeeded(account, remote);
        AuthenticatedUser user = principal(account);
        List<SimpleGrantedAuthority> authorities = user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
        SecurityContextHolder.setContext(context);
        HttpSession existing = request.getSession(false);
        if (existing != null) request.changeSessionId();
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        audit.record("LOGIN_SUCCESS", "USER", user.id(), null, Map.of("username", user.username()), null, null);
        return response(user);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(CsrfToken csrfToken) {
        csrfToken.getToken();
        return response(actor.require());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        AuthenticatedUser user = actor.require();
        audit.record("LOGOUT", "USER", user.id(), null, null, null, null);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        AuthenticatedUser user = actor.require();
        AccountRepository.Account account = accounts.find(user.username()).orElseThrow();
        if (!encoder.matches(body.currentPassword(), account.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CURRENT_PASSWORD_INVALID", "当前密码错误");
        }
        passwordPolicy.validate(user.username(), body.newPassword());
        accounts.changePassword(user.id(), encoder.encode(body.newPassword()));
        audit.record("PASSWORD_CHANGED", "USER", user.id(), null, Map.of("sessionRevoked", true), null, null);
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    @PostMapping("/verify-password")
    @Transactional
    public Map<String, Object> verifyPassword(@Valid @RequestBody VerifyPasswordRequest body) {
        AuthenticatedUser user = actor.require();
        AccountRepository.Account account = accounts.find(user.username()).orElseThrow();
        if (!encoder.matches(body.password(), account.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSWORD_REVERIFICATION_FAILED", "密码复核失败");
        }
        recentPassword.markVerified();
        audit.record("PASSWORD_REVERIFIED", "USER", user.id(), null, null, null, null);
        return Map.of("verified", true, "validForSeconds", 300);
    }

    private AuthenticatedUser principal(AccountRepository.Account account) {
        return new AuthenticatedUser(account.id(), account.personId(), account.organizationId(), account.departmentId(),
                account.username(), account.displayName(), account.mustChangePassword(), account.sessionVersion(),
                accounts.dataScope(account.id()), accounts.roles(account.id()), accounts.permissions(account.id()));
    }

    private CurrentUserResponse response(AuthenticatedUser user) {
        return new CurrentUserResponse(user.id().toString(), user.username(), user.displayName(),
                user.departmentId() == null ? null : user.departmentId().toString(),
                accounts.departmentName(user.departmentId()),
                user.roles(), user.permissions(), user.mustChangePassword());
    }
}
