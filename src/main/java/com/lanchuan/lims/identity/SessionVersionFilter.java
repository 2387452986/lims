package com.lanchuan.lims.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SessionVersionFilter extends OncePerRequestFilter {
    private final AccountRepository accounts;

    public SessionVersionFilter(AccountRepository accounts) { this.accounts = accounts; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedUser user
                && accounts.activeSessionVersion(user.id()).filter(version -> version == user.sessionVersion()).isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json;charset=UTF-8");
            response.getWriter().write("{\"type\":\"urn:lims:problem:session-revoked\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"会话已被撤销，请重新登录\",\"code\":\"SESSION_REVOKED\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
