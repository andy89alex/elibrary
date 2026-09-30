package com.elibrary.lending.domain;

import java.util.Objects;
import java.util.UUID;

public record LoanId(UUID value) {

    public LoanId {
        Objects.requireNonNull(value, "loan id must not be null");
    }

    public static LoanId of(String raw) {
        Objects.requireNonNull(raw, "loan id must not be null");
        try {
            return new LoanId(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed loan id: " + raw, e);
        }
    }

    public static LoanId newId() {
        return new LoanId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
