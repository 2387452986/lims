package com.lanchuan.lims.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemHandlerTest {
    @Test
    void malformedJsonUsesProblemDetails400InsteadOfInternalError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/equipment");
        when(request.getAttribute(RequestIdFilter.ATTRIBUTE)).thenReturn("request-400");

        var response = new ProblemHandler().unreadable(
                new HttpMessageNotReadableException("missing primitive field", mock(HttpInputMessage.class)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "MALFORMED_REQUEST")
                .containsEntry("requestId", "request-400");
    }

    @Test
    void missingResourceUsesProblemDetails404InsteadOfInternalError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/missing-resource");
        when(request.getAttribute(RequestIdFilter.ATTRIBUTE)).thenReturn("request-404");

        var response = new ProblemHandler().resourceNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/missing-resource", "classpath:/static/"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "RESOURCE_NOT_FOUND")
                .containsEntry("requestId", "request-404");
    }
}
