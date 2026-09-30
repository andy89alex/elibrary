package com.elibrary.shared.error;

/**
 * Base for every business failure. Carries a stable, machine-readable code.
 * Clients switch on {@link #code()}, not on the HTTP status: status codes have too low a
 * cardinality to serve as an error contract, whereas codes can grow without a breaking change.
 * Deliberately knows nothing about HTTP — mapping happens only in {@code platform.web}.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
