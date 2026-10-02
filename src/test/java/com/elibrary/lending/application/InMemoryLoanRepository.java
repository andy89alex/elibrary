package com.elibrary.lending.application;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fake, not a mock. Repository mocks assert interaction order and couple tests to
 * implementation detail; this asserts behaviour and survives refactoring. Mockito is
 * still the right tool where the interaction itself is the subject.
 */
class InMemoryLoanRepository implements LoanRepository {

    private final Map<LoanId, Loan> store = new LinkedHashMap<>();

    @Override
    public ActiveLoans activeFor(MemberId memberId) {
        return new ActiveLoans(memberId, store.values().stream()
                .filter(loan -> loan.belongsTo(memberId))
                .filter(Loan::isActive)
                .toList());
    }

    @Override
    public Optional<Loan> findById(LoanId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Loan save(Loan loan) {
        store.put(loan.id(), loan);
        return loan;
    }

    @Override
    public PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size) {
        List<Loan> matching = store.values().stream()
                .filter(loan -> loan.belongsTo(memberId))
                .filter(loan -> switch (filter) {
                    case ACTIVE -> loan.isActive();
                    case RETURNED -> !loan.isActive();
                    case ALL -> true;
                })
                .sorted(Comparator.comparing(Loan::borrowedAt).reversed())
                .toList();

        List<Loan> window = matching.stream().skip((long) page * size).limit(size).toList();
        return PageResult.of(window, page, size, matching.size());
    }

    @Override
    public PageResult<Loan> search(LoanSearchCriteria criteria, LocalDate today) {
        List<Loan> matching = store.values().stream()
                .filter(loan -> criteria.member().map(loan::belongsTo).orElse(true))
                .filter(loan -> criteria.book().map(loan::isFor).orElse(true))
                .filter(loan -> switch (criteria.status()) {
                    case ACTIVE -> loan.isActive();
                    case RETURNED -> !loan.isActive();
                    case ALL -> true;
                })
                .filter(loan -> !criteria.overdueOnly() || (loan.isActive() && loan.dueOn().isBefore(today)))
                .sorted(Comparator.comparing(Loan::borrowedAt).reversed())
                .toList();

        List<Loan> window = matching.stream()
                .skip((long) criteria.page() * criteria.size())
                .limit(criteria.size())
                .toList();
        return PageResult.of(window, criteria.page(), criteria.size(), matching.size());
    }

    void seed(Loan... loans) {
        for (Loan loan : loans) {
            store.put(loan.id(), loan);
        }
    }
}
