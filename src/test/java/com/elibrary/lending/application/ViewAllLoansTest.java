package com.elibrary.lending.application;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ViewAllLoansTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.newId();
    private static final BookId REFACTORING = BookId.newId();
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    /** Borrowed 2026-09-30, so due 2026-10-14. */
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;

    private ViewAllLoans viewAllLoans(String today) {
        return new ViewAllLoans(loans, Clock.fixed(Instant.parse(today + "T10:00:00Z"), ZoneOffset.UTC));
    }

    private static LoanSearchCriteria criteria(MemberId member, BookId book,
                                               LoanStatusFilter status, boolean overdueOnly) {
        return LoanSearchCriteria.of(member, book, status, overdueOnly, 0, 20);
    }

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
    }

    @Test
    void returnsEveryMembersLoansNotJustOnePersons() {
        loans.seed(Loan.open(ALICE, DDD, POLICY, BORROWED), Loan.open(BOB, DDD, POLICY, BORROWED));

        PageResult<Loan> result = viewAllLoans("2026-10-01")
                .handle(criteria(null, null, LoanStatusFilter.ALL, false));

        assertThat(result.items()).extracting(Loan::memberId).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void passesTheFiltersThroughToTheRepository() {
        loans.seed(Loan.open(ALICE, DDD, POLICY, BORROWED), Loan.open(BOB, REFACTORING, POLICY, BORROWED));

        PageResult<Loan> result = viewAllLoans("2026-10-01")
                .handle(criteria(BOB, REFACTORING, LoanStatusFilter.ACTIVE, false));

        assertThat(result.items()).extracting(Loan::memberId).containsExactly(BOB);
    }

    @Test
    void overdueIsEvaluatedAgainstTheInjectedClockNotTheWallClock() {
        loans.seed(Loan.open(ALICE, DDD, POLICY, BORROWED));

        assertThat(viewAllLoans("2026-10-13").handle(criteria(null, null, LoanStatusFilter.ALL, true)).items())
                .as("one day before the 2026-10-14 due date")
                .isEmpty();
        assertThat(viewAllLoans("2026-10-15").handle(criteria(null, null, LoanStatusFilter.ALL, true)).items())
                .as("one day after the due date")
                .hasSize(1);
    }

    @Test
    void theDueDateItselfIsNotYetOverdue() {
        loans.seed(Loan.open(ALICE, DDD, POLICY, BORROWED));

        assertThat(viewAllLoans("2026-10-14").handle(criteria(null, null, LoanStatusFilter.ALL, true)).items())
                .isEmpty();
    }

    @Test
    void anEmptyLedgerIsAnEmptyPageNotAnError() {
        PageResult<Loan> result = viewAllLoans("2026-10-01")
                .handle(criteria(null, null, LoanStatusFilter.ALL, false));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }
}
