package com.elibrary.catalog;

import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;

/** Read model for the detail view. Journal-only fields are null for books. */
public record BookDetail(
        BookId id,
        String title,
        String author,
        Isbn isbn,
        ContentKind kind,
        String publisher,
        Integer publicationYear,
        String volume,
        String issue,
        int totalCopies,
        int availableCopies) {

    public boolean available() {
        return availableCopies > 0;
    }
}
