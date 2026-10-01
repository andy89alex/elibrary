package com.elibrary.catalog;

import java.util.Objects;

/** Browse inputs, already defaulted and clamped so no adapter has to repeat the rules. */
public record BookSearchCriteria(
        String q,
        String author,
        boolean availableOnly,
        BookSortField sortField,
        boolean ascending,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public BookSearchCriteria {
        Objects.requireNonNull(sortField, "sortField");
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
        size = Math.min(size, MAX_SIZE);
    }

    public static BookSearchCriteria of(String q, String author, boolean availableOnly,
                                        BookSortField sortField, boolean ascending,
                                        Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int requested = size == null || size < 1 ? DEFAULT_SIZE : size;
        return new BookSearchCriteria(
                blankToNull(q),
                blankToNull(author),
                availableOnly,
                sortField == null ? BookSortField.TITLE : sortField,
                ascending,
                safePage,
                Math.min(requested, MAX_SIZE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
