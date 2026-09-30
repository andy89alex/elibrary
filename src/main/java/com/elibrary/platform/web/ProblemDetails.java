package com.elibrary.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;

/** Builds the one response shape every error in this API uses. */
public final class ProblemDetails {

    private static final String TYPE_BASE = "https://elibrary.example/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(titleFrom(code));
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    /** {@code LOAN_LIMIT_REACHED} becomes {@code "Loan limit reached"}. */
    public static String titleFrom(String code) {
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
