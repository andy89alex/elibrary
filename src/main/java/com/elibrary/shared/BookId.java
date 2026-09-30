package com.elibrary.shared;

import java.util.Objects;
import java.util.UUID;

/** Identity of a catalogue item. Parsing lives here so no caller hand-rolls UUID handling. */
public record BookId(UUID value) {

    public BookId {
        Objects.requireNonNull(value, "book id must not be null");
    }

    public static BookId of(String raw) {
        Objects.requireNonNull(raw, "book id must not be null");
        try {
            return new BookId(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed book id: " + raw, e);
        }
    }

    public static BookId newId() {
        return new BookId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
