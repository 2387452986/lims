package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

@Service
public class FormulaEvaluator {
    private static final String SAFE_EXPRESSION = "[A-Za-z0-9_+\\-*/%()., <>=!&|?:]+";
    private static final ExpressionConfiguration CONFIGURATION = ExpressionConfiguration.defaultConfiguration()
            .toBuilder()
            .mathContext(MathContext.DECIMAL128)
            .arraysAllowed(false)
            .structuresAllowed(false)
            .binaryAllowed(false)
            .implicitMultiplicationAllowed(false)
            .stripTrailingZeros(true)
            .build();

    public BigDecimal evaluate(String formula, Map<String, BigDecimal> variables) {
        validateSafety(formula);
        try {
            Expression expression = new Expression(formula, CONFIGURATION);
            for (Map.Entry<String, BigDecimal> variable : variables.entrySet()) {
                if (!variable.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "FORMULA_VARIABLE_INVALID", "公式变量名无效");
                }
                expression.with(variable.getKey(), variable.getValue());
            }
            EvaluationValue result = expression.evaluate();
            return result.getNumberValue();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FORMULA_EVALUATION_FAILED", "公式计算失败");
        }
    }

    public void validateSafety(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FORMULA_EMPTY", "公式不能为空");
        }
        if (formula.length() > 1000 || !formula.matches(SAFE_EXPRESSION)
                || formula.contains(";") || formula.contains("[") || formula.contains("{")
                || formula.matches(".*[A-Za-z_][A-Za-z0-9_]*\\s*\\..*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FORMULA_UNSAFE", "公式包含不允许的字符或结构");
        }
    }
}
