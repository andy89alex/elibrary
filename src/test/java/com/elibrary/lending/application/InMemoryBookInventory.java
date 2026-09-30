package com.elibrary.lending.application;

import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import com.elibrary.shared.error.BookNotFound;

import java.util.HashMap;
import java.util.Map;

class InMemoryBookInventory implements BookInventory {

    private final Map<BookId, Integer> availableCopies = new HashMap<>();

    @Override
    public void checkout(BookId bookId) {
        Integer available = availableCopies.get(bookId);
        if (available == null) {
            throw new BookNotFound(bookId);
        }
        if (available <= 0) {
            throw new BookUnavailable(bookId);
        }
        availableCopies.put(bookId, available - 1);
    }

    @Override
    public void restore(BookId bookId) {
        Integer available = availableCopies.get(bookId);
        if (available == null) {
            throw new BookNotFound(bookId);
        }
        availableCopies.put(bookId, available + 1);
    }

    void stock(BookId bookId, int copies) {
        availableCopies.put(bookId, copies);
    }

    int availableCopiesOf(BookId bookId) {
        return availableCopies.getOrDefault(bookId, 0);
    }
}
