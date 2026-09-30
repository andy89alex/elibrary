package com.elibrary.lending.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.domain.Loan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire shape of a loan. {@code status} and {@code overdue} are computed here from the
 * aggregate and the clock — neither is stored, so neither can disagree with the data.
 *
 * <p>Temporal fields are pinned to ISO-8601 strings via {@code @JsonFormat}. Response field
 * names and shapes are a documented API contract, so they must not depend on whatever global
 * {@code WRITE_DATES_AS_TIMESTAMPS} setting a given {@code ObjectMapper} happens to carry.
 */
record LoanResponse(
        String id,
        BookRef book,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant borrowedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dueOn,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant returnedAt,
        String status,
        boolean overdue) {

    /**
     * Enough of the book to render a list without a second request. Title and author are
     * null when the catalogue no longer holds the item: a loan is historical record and must
     * still render.
     */
    record BookRef(String id, String title, String author, ContentKind kind) {

        static BookRef of(Loan loan, BookSummary summary) {
            return summary == null
                    ? new BookRef(loan.bookId().toString(), null, null, null)
                    : new BookRef(summary.id().toString(), summary.title(), summary.author(), summary.kind());
        }
    }

    static LoanResponse of(Loan loan, BookSummary summary, Clock clock) {
        return new LoanResponse(
                loan.id().toString(),
                BookRef.of(loan, summary),
                loan.borrowedAt(),
                loan.dueOn(),
                loan.returnedAt(),
                loan.isActive() ? "ACTIVE" : "RETURNED",
                loan.isOverdue(clock));
    }
}
