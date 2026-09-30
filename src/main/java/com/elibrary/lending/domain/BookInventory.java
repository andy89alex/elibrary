package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.error.BookNotFound;

/**
 * Port owned by the domain, implemented by the catalogue.
 *
 * <p>The catalogue is the single authority on copy availability, so lending never computes
 * it. Availability is {@code totalCopies − activeLoans}, a figure sourced from two modules;
 * making the catalogue own the running total keeps the {@code available=true} browse filter
 * a single-table query and keeps paging correct.
 *
 * <p>Both operations participate in the caller's transaction. In a single-database modular
 * monolith a local transaction is the correct, simple answer; this interface is exactly the
 * seam where a saga with a compensating action would go if the modules became services.
 */
public interface BookInventory {

    /** Takes one copy. Throws {@link BookNotFound} or {@link BookUnavailable}. */
    void checkout(BookId bookId);

    /** Gives one copy back. Throws {@link BookNotFound}. */
    void restore(BookId bookId);
}
