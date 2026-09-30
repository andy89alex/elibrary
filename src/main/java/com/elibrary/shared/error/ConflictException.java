package com.elibrary.shared.error;

/** The request is well-formed but the current state forbids it. Maps to 409. */
public abstract class ConflictException extends DomainException {

    protected ConflictException(String code, String message) {
        super(code, message);
    }
}
