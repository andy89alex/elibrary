package com.elibrary.lending.web;

import com.elibrary.catalog.BookSummary;
import com.elibrary.lending.domain.Loan;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire shape of a loan for the librarian view. Identical to {@link LoanResponse} but for
 * the leading {@code memberId}: knowing who holds the item is the reason this endpoint
 * exists, whereas on the member-facing endpoints the borrower is always the caller and
 * echoing it back would be noise.
 *
 * <p>Kept as its own record rather than adding a nullable field to {@code LoanResponse}.
 * A field that is populated on one endpoint and null on another is a contract clients have
 * to read prose to understand; two records each say exactly what they carry.
 */
record AdminLoanResponse(
        String id,
        String memberId,
        LoanResponse.BookRef book,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant borrowedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dueOn,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant returnedAt,
        String status,
        boolean overdue) {

    static AdminLoanResponse of(Loan loan, BookSummary summary, Clock clock) {
        return new AdminLoanResponse(
                loan.id().toString(),
                loan.memberId().value(),
                LoanResponse.BookRef.of(loan, summary),
                loan.borrowedAt(),
                loan.dueOn(),
                loan.returnedAt(),
                loan.isActive() ? "ACTIVE" : "RETURNED",
                loan.isOverdue(clock));
    }
}
