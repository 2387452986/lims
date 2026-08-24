package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaEvaluatorTest {
    private final FormulaEvaluator evaluator = new FormulaEvaluator();

    @Test
    void calculatesWithDecimal128WithoutBinaryFloatingPoint() {
        BigDecimal result = evaluator.evaluate("load / area", Map.of(
                "load", new BigDecimal("812.4"), "area", new BigDecimal("22500")));
        assertThat(result.toPlainString()).isEqualTo("0.03610666666666666666666666666666667");
    }

    @Test
    void rejectsScriptAndStructureSyntax() {
        assertThatThrownBy(() -> evaluator.evaluate("java.lang.Runtime()", Map.of()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("FORMULA_UNSAFE");
        assertThatThrownBy(() -> evaluator.evaluate("a[0]", Map.of("a", BigDecimal.ONE)))
                .isInstanceOf(ApiException.class);
    }
}
