package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A member's borrowing of one catalogue item.
 *
 * <p>Immutable: {@link #returnNow(Clock)} yields a new instance rather than mutating,
 * so there is no partially-updated state to reason about and the type is trivial to test.
 *
 * <p>"Overdue" is a derived predicate, never stored state. It is a pure function of
 * {@code dueOn} and the clock, so no scheduled job has to flip statuses, no column can
 * go stale, and the classic "the cron died overnight" bug cannot happen.
 */
public final class Loan {

    private final LoanId id;
    private final MemberId memberId;
    private final BookId bookId;
    private final Instant borrowedAt;
    private final LocalDate dueOn;
    private final Instant returnedAt;

    private Loan(LoanId id, MemberId memberId, BookId bookId,
                 Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
        this.borrowedAt = Objects.requireNonNull(borrowedAt, "borrowedAt");
        this.dueOn = Objects.requireNonNull(dueOn, "dueOn");
        this.returnedAt = returnedAt;
    }

    public static Loan open(MemberId memberId, BookId bookId, LendingPolicy policy, Clock clock) {
        Instant now = clock.instant();
        return new Loan(LoanId.newId(), memberId, bookId, now, policy.dueDateFrom(today(clock)), null);
    }

    /** Rebuilds a loan from storage. Used only by the persistence adapter. */
    public static Loan reconstitute(LoanId id, MemberId memberId, BookId bookId,
                                    Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        return new Loan(id, memberId, bookId, borrowedAt, dueOn, returnedAt);
    }

    public Loan returnNow(Clock clock) {
        if (returnedAt != null) {
            throw new LoanAlreadyReturned(id);
        }
        return new Loan(id, memberId, bookId, borrowedAt, dueOn, clock.instant());
    }

    public boolean isActive() {
        return returnedAt == null;
    }

    public boolean isOverdue(Clock clock) {
        return isActive() && dueOn.isBefore(today(clock));
    }

    public boolean belongsTo(MemberId candidate) {
        return memberId.equals(candidate);
    }

    public boolean isFor(BookId candidate) {
        return bookId.equals(candidate);
    }

    private static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    public LoanId id() {
        return id;
    }

    public MemberId memberId() {
        return memberId;
    }

    public BookId bookId() {
        return bookId;
    }

    public Instant borrowedAt() {
        return borrowedAt;
    }

    public LocalDate dueOn() {
        return dueOn;
    }

    public Instant returnedAt() {
        return returnedAt;
    }

    /** Identity is the loan id alone: a returned loan is still the same loan. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Loan other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Loan[" + id + " member=" + memberId + " book=" + bookId
                + " dueOn=" + dueOn + (isActive() ? " active]" : " returnedAt=" + returnedAt + "]");
    }
}
