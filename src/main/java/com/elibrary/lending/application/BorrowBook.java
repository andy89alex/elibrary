package com.elibrary.lending.application;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Orchestration only: every business rule lives in the domain or in the catalogue. */
@Service
public class BorrowBook {

    private final LoanRepository loans;
    private final BookInventory inventory;
    private final LendingPolicy policy;
    private final Clock clock;

    public BorrowBook(LoanRepository loans, BookInventory inventory, LendingPolicy policy, Clock clock) {
        this.loans = loans;
        this.inventory = inventory;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Ordering is deliberate. The member-side checks are cheap and take no lock, so they run
     * first; {@code checkout} acquires a pessimistic write lock on the book row and is last,
     * which keeps the lock held for the shortest possible window. Both steps share one local
     * transaction, so a failure after checkout rolls the copy back.
     */
    @Transactional
    public Loan handle(MemberId memberId, BookId bookId) {
        ActiveLoans position = loans.activeFor(memberId);
        Loan loan = position.borrow(bookId, policy, clock);
        inventory.checkout(bookId);
        return loans.save(loan);
    }
}
