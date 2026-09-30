package com.elibrary.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * Sortable fields, whitelisted. Passing a client string straight into Spring's
 * {@code Sort} lets a caller order by any persistent property, which leaks the
 * persistence model and can be used to probe it. An enum makes the sortable surface
 * an explicit API decision.
 */
public enum BookSortField {

    TITLE("title"),
    AUTHOR("author"),
    PUBLICATION_YEAR("publicationYear");

    private final String property;

    BookSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static Optional<BookSortField> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (BookSortField field : values()) {
            if (field.name().equals(normalised) || field.property.equalsIgnoreCase(raw.trim())) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }
}
