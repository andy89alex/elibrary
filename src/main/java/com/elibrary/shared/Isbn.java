package com.elibrary.shared;

import java.util.regex.Pattern;

/**
 * Loose identifier for a catalogue item: ISBN-10, ISBN-13, or ISSN for journals.
 * Checksum validation is deliberately out of scope — the catalogue is a read model
 * populated from a trusted feed, and rejecting real-world identifiers on a checksum
 * technicality would be worse than accepting them.
 */
public record Isbn(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[0-9Xx][0-9Xx-]{7,18}$");

    public Isbn {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed isbn/issn: " + value);
        }
    }

    public static Isbn of(String raw) {
        return raw == null ? null : new Isbn(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
