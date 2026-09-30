package com.elibrary.lending.application;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewLoansTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private ViewLoans viewLoans;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        viewLoans = new ViewLoans(loans);
    }

    @Test
    void activeFilterExcludesReturnedLoansAndOtherMembers() {
        Loan aliceActive = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        Loan aliceReturned = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK).returnNow(CLOCK);
        Loan bobsActive = Loan.open(BOB, BookId.newId(), POLICY, CLOCK);
        loans.seed(aliceActive, aliceReturned, bobsActive);

        PageResult<Loan> result = viewLoans.handle(ALICE, LoanStatusFilter.ACTIVE, 0, 20);

        assertThat(result.items()).containsExactly(aliceActive);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void allFilterReturnsBothStatesForTheCallerOnly() {
        Loan active = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        Loan returned = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK).returnNow(CLOCK);
        loans.seed(active, returned, Loan.open(BOB, BookId.newId(), POLICY, CLOCK));

        assertThat(viewLoans.handle(ALICE, LoanStatusFilter.ALL, 0, 20).items())
                .containsExactlyInAnyOrder(active, returned);
    }

    @Test
    void byIdReturnsTheCallersLoan() {
        Loan loan = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        loans.seed(loan);

        assertThat(viewLoans.byId(ALICE, loan.id())).isEqualTo(loan);
    }

    @Test
    void byIdHidesAnotherMembersLoanBehindNotFound() {
        Loan bobs = Loan.open(BOB, BookId.newId(), POLICY, CLOCK);
        loans.seed(bobs);

        assertThatThrownBy(() -> viewLoans.byId(ALICE, bobs.id())).isInstanceOf(LoanNotFound.class);
    }

    @Test
    void byIdReportsAnUnknownLoanAsNotFound() {
        assertThatThrownBy(() -> viewLoans.byId(ALICE, LoanId.newId())).isInstanceOf(LoanNotFound.class);
    }
}
