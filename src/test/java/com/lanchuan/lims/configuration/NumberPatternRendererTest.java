package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumberPatternRendererTest {
    private final NumberPatternRenderer renderer = new NumberPatternRenderer();

    @Test
    void rendersReferenceYearDateSymbolAndSequenceTokens() {
        LocalDate date = LocalDate.of(2026, 8, 19);
        assertThat(renderer.render("QJCS-{symbol}-ymd-0001", "SL", date, 27))
                .isEqualTo("QJCS-SL-260819-0027");
        assertThat(renderer.render("WNBA-BG-{symbol}-y-0001", "A1", date, 1))
                .isEqualTo("WNBA-BG-A1-26-0001");
        assertThat(renderer.render("BG-{symbol}-Ymd-00001", "XM", date, 42))
                .isEqualTo("BG-XM-20260819-00042");
    }

    @Test
    void rejectsUnknownTokensAndSequenceOverflow() {
        LocalDate date = LocalDate.of(2026, 8, 19);
        assertThatThrownBy(() -> renderer.render("BG-{script}-y-0001", "XM", date, 1))
                .isInstanceOf(ApiException.class).hasMessageContaining("占位符");
        assertThatThrownBy(() -> renderer.render("BG-y-01", "XM", date, 100))
                .isInstanceOf(ApiException.class).hasMessageContaining("位数");
    }

    @Test
    void rendersControlledSequenceBeforeNumericSuffixWithoutGuessingTheWrongDigits() {
        assertThat(renderer.render("PX-{date:Y}-{sequence:4}-R1", "", LocalDate.of(2026, 8, 21), 25))
                .isEqualTo("PX-2026-0025-R1");
        assertThatThrownBy(() -> renderer.render("PX-{sequence:2}-{sequence:3}", "", LocalDate.now(), 1))
                .isInstanceOf(ApiException.class).hasMessageContaining("只能包含一个");
    }

    @Test
    void keepsDateLikeLettersInsideControlledRuleLiterals() {
        LocalDate date = LocalDate.of(2026, 8, 21);
        assertThat(renderer.render("MY-{date:Ymd}-{sequence:3}-day", "", date, 8))
                .isEqualTo("MY-20260821-008-day");
        assertThat(renderer.render("MY-{sequence:3}-day", "", date, 8))
                .isEqualTo("MY-008-day");
        assertThat(renderer.render("BG-{symbol}-Ymd-0001", "MY", date, 8))
                .isEqualTo("BG-MY-20260821-0008");
        assertThatThrownBy(() -> renderer.render("{date:Y}-{date:ym}-{sequence:2}", "", date, 1))
                .isInstanceOf(ApiException.class).hasMessageContaining("只能包含一个日期");
    }
}
