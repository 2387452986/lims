package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NumberPatternRenderer {
    private static final String SYMBOL_SENTINEL = "\u0001";
    private static final Pattern CONTROLLED_DATE = Pattern.compile("\\{date:(Y|y|Ym|ym|Ymd|ymd)}");
    private static final Pattern CONTROLLED_SEQUENCE = Pattern.compile("\\{sequence:(\\d{1,2})}");
    private static final Pattern LAST_SEQUENCE = Pattern.compile("(\\d+)(?!.*\\d)");
    private static final Pattern SAFE_LITERAL = Pattern.compile("[A-Za-z0-9\\p{IsHan}\\-_/ .()\\uff08\\uff09]*");

    public String render(String pattern, String symbol, LocalDate date, long sequence) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 240 || date == null || sequence < 1) {
            throw invalid("NUMBER_PATTERN_INVALID", "编号模式、日期或流水值无效");
        }
        String safeSymbol = symbol == null ? "" : symbol.trim();
        if (safeSymbol.length() > 80 || safeSymbol.contains("{") || safeSymbol.contains("}")) {
            throw invalid("NUMBER_SYMBOL_INVALID", "项目符号无效");
        }
        Matcher controlledSequence = CONTROLLED_SEQUENCE.matcher(pattern);
        boolean hasControlledSequence = controlledSequence.find();
        if (hasControlledSequence && controlledSequence.find()) {
            throw invalid("NUMBER_PATTERN_SEQUENCE_AMBIGUOUS", "编号模式只能包含一个流水占位");
        }
        controlledSequence.reset();
        Matcher controlledDate = CONTROLLED_DATE.matcher(pattern);
        boolean hasControlledDate = controlledDate.find();
        if (hasControlledDate && controlledDate.find()) {
            throw invalid("NUMBER_PATTERN_DATE_AMBIGUOUS", "编号模式只能包含一个日期占位");
        }
        controlledDate.reset();
        String withoutSymbol = pattern.replace("{symbol}", "");
        if (hasControlledSequence) withoutSymbol = CONTROLLED_SEQUENCE.matcher(withoutSymbol).replaceFirst("");
        if (hasControlledDate) withoutSymbol = CONTROLLED_DATE.matcher(withoutSymbol).replaceFirst("");
        String literal = hasControlledSequence || hasControlledDate ? withoutSymbol
                : withoutSymbol.replace("ymd", "").replace("Ymd", "")
                        .replace("y", "").replace("Y", "").replace("m", "").replace("d", "");
        if (withoutSymbol.contains("{") || withoutSymbol.contains("}") || !SAFE_LITERAL.matcher(literal).matches()) {
            throw invalid("NUMBER_PATTERN_TOKEN_INVALID", "编号模式包含未支持的字符或占位符");
        }
        Matcher sequenceToken = hasControlledSequence ? CONTROLLED_SEQUENCE.matcher(pattern) : LAST_SEQUENCE.matcher(pattern);
        if (!sequenceToken.find()) throw invalid("NUMBER_PATTERN_SEQUENCE_REQUIRED", "编号模式必须包含流水占位");
        int width = hasControlledSequence ? Integer.parseInt(sequenceToken.group(1)) : sequenceToken.group(1).length();
        if (width < 1 || width > 12) throw invalid("NUMBER_PATTERN_SEQUENCE_WIDTH_INVALID", "流水占位宽度必须在 1 到 12 之间");
        String sequenceText = Long.toString(sequence);
        if (sequenceText.length() > width) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMBER_SEQUENCE_EXHAUSTED", "流水号已超过编号模式可容纳位数");
        }
        String value = pattern.substring(0, sequenceToken.start()) + ("%0" + width + "d").formatted(sequence)
                + pattern.substring(sequenceToken.end());
        value = value.replace("{symbol}", SYMBOL_SENTINEL);
        if (hasControlledDate) {
            Matcher dateToken = CONTROLLED_DATE.matcher(value);
            if (!dateToken.find()) throw invalid("NUMBER_PATTERN_DATE_INVALID", "日期占位无效");
            value = dateToken.replaceFirst(Matcher.quoteReplacement(formatDate(dateToken.group(1), date)));
        } else if (!hasControlledSequence) {
            value = value.replace("Ymd", date.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                    .replace("ymd", date.format(DateTimeFormatter.ofPattern("yyMMdd")))
                    .replace("Y", date.format(DateTimeFormatter.ofPattern("yyyy")))
                    .replace("y", date.format(DateTimeFormatter.ofPattern("yy")))
                    .replace("m", date.format(DateTimeFormatter.ofPattern("MM")))
                    .replace("d", date.format(DateTimeFormatter.ofPattern("dd")));
        }
        value = value.replace(SYMBOL_SENTINEL, safeSymbol);
        if (value.length() > 240) throw invalid("NUMBER_RESULT_TOO_LONG", "生成编号超过240字符");
        return value;
    }

    private String formatDate(String token, LocalDate date) {
        return switch (token) {
            case "Y" -> date.format(DateTimeFormatter.ofPattern("yyyy"));
            case "y" -> date.format(DateTimeFormatter.ofPattern("yy"));
            case "Ym" -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "ym" -> date.format(DateTimeFormatter.ofPattern("yyMM"));
            case "Ymd" -> date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case "ymd" -> date.format(DateTimeFormatter.ofPattern("yyMMdd"));
            default -> throw invalid("NUMBER_PATTERN_DATE_INVALID", "日期占位无效");
        };
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
