package com.elibrary.catalog.internal;

import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.error.BookNotFound;
import com.elibrary.shared.BookId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's side of the availability contract.
 *
 * <p>Both operations require an existing transaction, which is always the caller's use-case
 * transaction. That is what makes the two-step borrow (take a copy, record the loan) atomic
 * in a single-database monolith: a failure after checkout rolls the copy back with no
 * compensating action. {@code MANDATORY} rather than {@code REQUIRED} makes that requirement
 * loud — calling this outside a transaction fails immediately instead of silently committing
 * half the work.
 */
@Service
class CatalogBookInventory implements BookInventory {

    private final BookJpaRepository books;

    CatalogBookInventory(BookJpaRepository books) {
        this.books = books;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkout(BookId bookId) {
        if (!lock(bookId).checkoutCopy()) {
            throw new BookUnavailable(bookId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(BookId bookId) {
        lock(bookId).restoreCopy();
    }

    private BookRecord lock(BookId bookId) {
        return books.findByIdForUpdate(bookId.value()).orElseThrow(() -> new BookNotFound(bookId));
    }
}
