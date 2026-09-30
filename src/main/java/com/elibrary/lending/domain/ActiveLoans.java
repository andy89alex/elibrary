package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * A member's current lending position, and the only route to opening a loan.
 *
 * <p>Invariants 2 (concurrent limit) and 3 (no duplicate active loan for the same book)
 * live here rather than in the application service. Keeping them in the domain means an
 * anaemic-service refactor cannot quietly drop them, and they are unit-testable without
 * Spring or a database.
 */
public final class ActiveLoans {

    private final MemberId memberId;
    private final List<Loan> loans;

    public ActiveLoans(MemberId memberId, List<Loan> loans) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.loans = List.copyOf(Objects.requireNonNull(loans, "loans"));
        for (Loan loan : this.loans) {
            if (!loan.isActive()) {
                throw new IllegalArgumentException("ActiveLoans may only hold active loans, got " + loan);
            }
            if (!loan.belongsTo(memberId)) {
                throw new IllegalArgumentException(
                        "ActiveLoans for " + memberId + " may not hold a loan of " + loan.memberId());
            }
        }
    }

    /**
     * Opens a loan for {@code bookId}, enforcing the member-side rules.
     * Copy availability (invariant 1) is not checked here: the catalogue owns it and
     * enforces it under a write lock. See {@link BookInventory}.
     */
    public Loan borrow(BookId bookId, LendingPolicy policy, Clock clock) {
        if (loans.stream().anyMatch(loan -> loan.isFor(bookId))) {
            throw new AlreadyBorrowed(memberId, bookId);
        }
        if (loans.size() >= policy.maxConcurrentLoans()) {
            throw new LoanLimitReached(memberId, loans.size(), policy.maxConcurrentLoans());
        }
        return Loan.open(memberId, bookId, policy, clock);
    }

    public int count() {
        return loans.size();
    }

    public List<Loan> all() {
        return loans;
    }

    public MemberId memberId() {
        return memberId;
    }
}
