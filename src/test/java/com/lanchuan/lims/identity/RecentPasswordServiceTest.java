package com.lanchuan.lims.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RecentPasswordServiceTest {
    @Test
    void verificationIsBoundToOneServerSession() {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        RecentPasswordService first = new RecentPasswordService(firstRequest, Duration.ofMinutes(5));
        first.markVerified();
        assertThat(first.isVerified()).isTrue();

        RecentPasswordService second = new RecentPasswordService(new MockHttpServletRequest(), Duration.ofMinutes(5));
        assertThat(second.isVerified()).isFalse();

        firstRequest.getSession().invalidate();
        assertThat(first.isVerified()).isFalse();
    }
}
