package com.elibrary.lending.domain;

import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;

import java.time.LocalDate;
import java.util.Optional;

/** Port owned by the domain; implemented by lending.internal over JPA. */
public interface LoanRepository {

    ActiveLoans activeFor(MemberId memberId);

    Optional<Loan> findById(LoanId id);

    Loan save(Loan loan);

    PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size);

    /**
     * Every member's loans, narrowed by {@code criteria}. Backs the librarian view.
     *
     * <p>{@code today} is passed in rather than read from a clock here: overdue is derived,
     * not stored, so the comparison needs a date, and taking it as an argument keeps the
     * port free of time sources and lets tests pin it.
     */
    PageResult<Loan> search(LoanSearchCriteria criteria, LocalDate today);
}
