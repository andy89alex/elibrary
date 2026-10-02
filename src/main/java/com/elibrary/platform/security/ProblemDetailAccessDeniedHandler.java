package com.elibrary.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * The 403 counterpart to {@link ProblemDetailEntryPoint}. Authorisation failures are
 * rejected inside the filter chain, before any {@code @RestControllerAdvice} runs, so
 * without this the API would answer 403 in Spring's default shape while every other error
 * is an RFC 9457 {@code ProblemDetail} carrying a {@code code}.
 *
 * <p>The detail is deliberately generic. Naming the required role would tell an
 * unprivileged caller exactly what to go after, and the client cannot act on it either way.
 */
@Component
class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://elibrary.example/problems/forbidden"));
        problem.setTitle("Forbidden");
        problem.setDetail("Your account is not permitted to access this resource.");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "FORBIDDEN");

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
