package com.elibrary.lending.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence shape of a loan, kept separate from the {@code Loan} aggregate so the
 * aggregate can stay immutable plain Java. No column stores "overdue" or a status: both
 * are derived from {@code dueOn} and {@code returnedAt}, so there is nothing to keep in sync.
 */
@Entity
@Table(name = "loans")
class LoanEntity {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "book_id", nullable = false)
    private UUID bookId;

    @Column(name = "borrowed_at", nullable = false)
    private Instant borrowedAt;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "returned_at")
    private Instant returnedAt;

    /**
     * Mirrors {@code bookId} while the loan is open and is NULL once it is returned, so the
     * unique constraint on {@code (member_id, active_book_id)} binds active loans only.
     * Derived state, never read by the domain — {@link LoanMapper} is its only writer.
     */
    @Column(name = "active_book_id")
    private UUID activeBookId;

    protected LoanEntity() {
        // required by JPA
    }

    LoanEntity(UUID id, String memberId, UUID bookId, Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        this.id = id;
        this.memberId = memberId;
        this.bookId = bookId;
        this.borrowedAt = borrowedAt;
        this.dueOn = dueOn;
        this.returnedAt = returnedAt;
        this.activeBookId = returnedAt == null ? bookId : null;
    }

    UUID id() {
        return id;
    }

    String memberId() {
        return memberId;
    }

    UUID bookId() {
        return bookId;
    }

    Instant borrowedAt() {
        return borrowedAt;
    }

    LocalDate dueOn() {
        return dueOn;
    }

    Instant returnedAt() {
        return returnedAt;
    }

    UUID activeBookId() {
        return activeBookId;
    }
}
