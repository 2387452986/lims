package com.lanchuan.lims.common;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long currentVersion;
    private final List<String> blockers;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, List.of(), Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Long currentVersion,
                        List<String> blockers, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
        this.blockers = List.copyOf(blockers);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message, long currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, code, message, currentVersion, List.of(), Map.of());
    }

    public static ApiException blocked(String code, String message, long currentVersion, List<String> blockers) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, currentVersion, blockers, Map.of());
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Long currentVersion() { return currentVersion; }
    public List<String> blockers() { return blockers; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
