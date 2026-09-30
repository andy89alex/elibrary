package com.elibrary.platform.web;

import com.elibrary.shared.error.ConflictException;
import com.elibrary.shared.error.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * The only place in the codebase that knows how a business failure maps to an HTTP status.
 *
 * <p>Every business refusal is 409. Clients must branch on {@code code}, not on the status:
 * status codes have far too low a cardinality to act as an error contract, whereas codes can
 * grow without a breaking change. Because the domain exposes just two abstract bases, adding
 * a domain error needs no change here at all.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail onNotFound(NotFoundException e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail onConflict(ConflictException e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request body is invalid.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Covers malformed identifiers, which the value objects reject on construction. */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail onInvalidRequest(Exception e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }
}
