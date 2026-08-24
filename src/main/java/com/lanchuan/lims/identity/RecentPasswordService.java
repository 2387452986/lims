package com.lanchuan.lims.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@Service
public class RecentPasswordService {
    private static final String ATTRIBUTE = "lims.password-verified-at";
    private final HttpServletRequest request;
    private final Duration window;
    private final Clock clock = Clock.systemUTC();

    public RecentPasswordService(HttpServletRequest request,
                                 @Value("${lims.security.recent-password-window:5m}") Duration window) {
        this.request = request;
        this.window = window;
    }

    public void markVerified() {
        request.getSession(true).setAttribute(ATTRIBUTE, clock.millis());
    }

    public boolean isVerified() {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(ATTRIBUTE);
        return value instanceof Long verifiedAt && clock.millis() - verifiedAt >= 0
                && clock.millis() - verifiedAt <= window.toMillis();
    }
}
