package com.elibrary.lending.application;

import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.shared.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The librarian's read over every member's loans.
 *
 * <p>Separate from {@link ViewLoans} rather than a flag on it: that service takes a
 * {@code MemberId} it cannot run without, and that signature is what makes "a member sees
 * only their own loans" impossible to get wrong. A boolean parameter would have moved the
 * guarantee from the type system into whichever caller remembered to pass false.
 *
 * <p>Resolving today's date is this service's job, not the repository's: the clock is a
 * dependency of the application layer, and keeping it here lets the port stay a pure
 * query.
 */
@Service
public class ViewAllLoans {

    private final LoanRepository loans;
    private final Clock clock;

    public ViewAllLoans(LoanRepository loans, Clock clock) {
        this.loans = loans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<Loan> handle(LoanSearchCriteria criteria) {
        return loans.search(criteria, LocalDate.ofInstant(clock.instant(), clock.getZone()));
    }
}
