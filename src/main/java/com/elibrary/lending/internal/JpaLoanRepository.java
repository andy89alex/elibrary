package com.elibrary.lending.internal;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.AlreadyBorrowed;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

@Repository
class JpaLoanRepository implements LoanRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "borrowedAt");

    /** Defined in V3__active_loan_uniqueness.sql. Compared case-insensitively. */
    private static final String ACTIVE_LOAN_CONSTRAINT = "UQ_LOANS_MEMBER_ACTIVE_BOOK";

    private final LoanJpaRepository loans;

    JpaLoanRepository(LoanJpaRepository loans) {
        this.loans = loans;
    }

    @Override
    public ActiveLoans activeFor(MemberId memberId) {
        return new ActiveLoans(memberId, loans.findByMemberIdAndReturnedAtIsNull(memberId.value())
                .stream()
                .map(LoanMapper::toDomain)
                .toList());
    }

    @Override
    public Optional<Loan> findById(LoanId id) {
        return loans.findById(id.value()).map(LoanMapper::toDomain);
    }

    /**
     * Loan ids are assigned in the domain, so Spring Data treats every save as a merge:
     * an existing row is updated rather than duplicated. That is exactly the behaviour
     * {@code returnNow} needs, at the cost of a select before the update.
     */
    @Override
    public Loan save(Loan loan) {
        try {
            return LoanMapper.toDomain(loans.saveAndFlush(LoanMapper.toEntity(loan)));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, ACTIVE_LOAN_CONSTRAINT)) {
                throw new AlreadyBorrowed(loan.memberId(), loan.bookId());
            }
            throw e;
        }
    }

    /**
     * Translates one specific constraint violation into the domain refusal it represents.
     *
     * <p>The guard belongs here rather than in the web layer: this adapter is the only class
     * that knows the schema, and callers above it already handle {@code AlreadyBorrowed}. The
     * check is on the constraint *name*, not on the exception type, because the same
     * {@code DataIntegrityViolationException} also carries foreign-key and check-constraint
     * failures — mapping all of them to "already borrowed" would turn an unrelated bug into a
     * plausible-looking 409. Anything else is rethrown and becomes a 500, which is correct:
     * it is a defect, not a business refusal.
     *
     * <p>{@code saveAndFlush} rather than {@code save} so the insert reaches the database
     * inside this method. A deferred flush would raise the violation at commit, outside any
     * catch block here, and the caller would see a raw infrastructure error instead.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        String member = memberId.value();

        Page<LoanEntity> found = switch (filter) {
            case ACTIVE -> loans.findByMemberIdAndReturnedAtIsNull(member, request);
            case RETURNED -> loans.findByMemberIdAndReturnedAtIsNotNull(member, request);
            case ALL -> loans.findByMemberId(member, request);
        };

        return PageResult.of(
                found.getContent().stream().map(LoanMapper::toDomain).toList(),
                page,
                size,
                found.getTotalElements());
    }

    @Override
    public PageResult<Loan> search(LoanSearchCriteria criteria, LocalDate today) {
        PageRequest request = PageRequest.of(criteria.page(), criteria.size(), NEWEST_FIRST);

        Page<LoanEntity> found = loans.search(
                criteria.member().map(MemberId::value).orElse(null),
                criteria.book().map(BookId::value).orElse(null),
                criteria.status().name(),
                criteria.overdueOnly(),
                today,
                request);

        return PageResult.of(
                found.getContent().stream().map(LoanMapper::toDomain).toList(),
                criteria.page(),
                criteria.size(),
                found.getTotalElements());
    }
}
