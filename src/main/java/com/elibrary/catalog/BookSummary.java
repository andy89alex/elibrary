package com.elibrary.catalog;

import com.elibrary.shared.BookId;

/** Read model for list views. This is also the JSON shape: the catalogue is CQRS-lite. */
public record BookSummary(
        BookId id,
        String title,
        String author,
        ContentKind kind,
        int totalCopies,
        int availableCopies) {

    public boolean available() {
        return availableCopies > 0;
    }
}
