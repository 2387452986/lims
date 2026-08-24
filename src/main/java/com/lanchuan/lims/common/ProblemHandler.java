package com.lanchuan.lims.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception, HttpServletRequest request) {
        ProblemDetail problem = base(exception.status(), exception.code(), exception.getMessage(), request);
        if (exception.currentVersion() != null) problem.setProperty("currentVersion", exception.currentVersion());
        if (!exception.blockers().isEmpty()) problem.setProperty("blockers", exception.blockers());
        if (!exception.fieldErrors().isEmpty()) problem.setProperty("fieldErrors", exception.fieldErrors());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError field : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(field.getField(), field.getDefaultMessage());
        }
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求字段校验失败", request);
        problem.setProperty("fieldErrors", fields);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException exception, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "CONSTRAINT_VIOLATION", exception.getMessage(), request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception,
                                             HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "请求内容缺失、类型不正确或无法解析", request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Data integrity violation path={} requestId={} causeType={}", request.getRequestURI(),
                request.getAttribute(RequestIdFilter.ATTRIBUTE),
                exception.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail problem = base(HttpStatus.CONFLICT, "DATA_CONFLICT", "数据与现有记录冲突", request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号无权执行此操作", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> resourceNotFound(NoResourceFoundException exception,
                                                   HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "请求的资源不存在", request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure path={} requestId={}", request.getRequestURI(),
                request.getAttribute(RequestIdFilter.ATTRIBUTE), exception);
        ProblemDetail problem = base(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器处理请求时发生错误", request);
        return ResponseEntity.internalServerError().body(problem);
    }

    private ProblemDetail base(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("urn:lims:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }
}
