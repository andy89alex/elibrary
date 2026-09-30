package com.elibrary.shared.error;

import com.elibrary.shared.BookId;

/**
 * No catalogue item exists for the given id. Lives in the shared kernel rather than in
 * either module because both need it: the catalogue answers 404 for an unknown book on
 * browse, and lending rejects a borrow against one.
 */
public class BookNotFound extends NotFoundException {

    public BookNotFound(BookId bookId) {
        super("BOOK_NOT_FOUND", "No book with id " + bookId + " exists.");
    }
}
