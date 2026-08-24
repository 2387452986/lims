package com.lanchuan.lims.common;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCursorTest {
    @Test
    void cursorRoundTripsWithoutExposingMutableQueryState() {
        PageCursor cursor = new PageCursor(OffsetDateTime.parse("2026-08-19T10:15:30Z"), UUID.randomUUID());
        assertThat(PageCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void malformedCursorUsesStableProblemCode() {
        assertThatThrownBy(() -> PageCursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CURSOR_INVALID"));
    }
}
