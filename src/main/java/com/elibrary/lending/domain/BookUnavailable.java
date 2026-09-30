package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.error.ConflictException;

public class BookUnavailable extends ConflictException {

    public BookUnavailable(BookId bookId) {
        super("NO_COPIES_AVAILABLE", "Book " + bookId + " has no copies available.");
    }
}
