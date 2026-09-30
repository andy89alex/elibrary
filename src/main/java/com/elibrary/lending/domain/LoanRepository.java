package com.elibrary.lending.domain;

import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;

import java.util.Optional;

/** Port owned by the domain; implemented by lending.internal over JPA. */
public interface LoanRepository {

    ActiveLoans activeFor(MemberId memberId);

    Optional<Loan> findById(LoanId id);

    Loan save(Loan loan);

    PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size);
}
