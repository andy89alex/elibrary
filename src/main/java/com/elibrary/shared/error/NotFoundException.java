package com.elibrary.shared.error;

/** A referenced thing does not exist, or the caller may not know that it does. Maps to 404. */
public abstract class NotFoundException extends DomainException {

    protected NotFoundException(String code, String message) {
        super(code, message);
    }
}
