package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveLoansTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final LendingPolicy POLICY = new LendingPolicy(2, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void borrowingFromAnEmptyPositionOpensALoanForTheMember() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of());

        Loan loan = position.borrow(DDD, POLICY, CLOCK);

        assertThat(loan.belongsTo(ALICE)).isTrue();
        assertThat(loan.isFor(DDD)).isTrue();
        assertThat(loan.isActive()).isTrue();
    }

    @Test
    void rejectsBorrowingWhenTheConcurrentLimitIsAlreadyReached() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(
                Loan.open(ALICE, DDD, POLICY, CLOCK),
                Loan.open(ALICE, REFACTORING, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(
                BookId.of("11111111-1111-1111-1111-111111111107"), POLICY, CLOCK))
                .isInstanceOf(LoanLimitReached.class)
                .hasMessageContaining("2")
                .satisfies(e -> assertThat(((LoanLimitReached) e).code()).isEqualTo("LOAN_LIMIT_REACHED"));
    }

    @Test
    void rejectsBorrowingABookTheMemberAlreadyHolds() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(Loan.open(ALICE, DDD, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(DDD, POLICY, CLOCK))
                .isInstanceOf(AlreadyBorrowed.class)
                .satisfies(e -> assertThat(((AlreadyBorrowed) e).code()).isEqualTo("ALREADY_BORROWED"));
    }

    @Test
    void theDuplicateCheckRunsBeforeTheLimitCheckIsExhaustedSoTheMessageIsTheUsefulOne() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(
                Loan.open(ALICE, DDD, POLICY, CLOCK),
                Loan.open(ALICE, REFACTORING, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(DDD, POLICY, CLOCK))
                .as("holding the limit AND already owning it should report the specific cause")
                .isInstanceOf(AlreadyBorrowed.class);
    }

    @Test
    void borrowingOneUnderTheLimitIsAllowed() {
        LendingPolicy five = new LendingPolicy(5, 14);
        List<Loan> four = IntStream.range(1, 5)
                .mapToObj(i -> Loan.open(ALICE, BookId.newId(), five, CLOCK))
                .toList();

        ActiveLoans position = new ActiveLoans(ALICE, four);

        assertThat(position.count()).isEqualTo(4);
        assertThat(position.borrow(DDD, five, CLOCK)).isNotNull();
    }

    @Test
    void rejectsConstructionFromAReturnedLoanBecauseThePositionMustHoldOnlyActiveOnes() {
        Loan returned = Loan.open(ALICE, DDD, POLICY, CLOCK).returnNow(CLOCK);

        assertThatThrownBy(() -> new ActiveLoans(ALICE, List.of(returned)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    @Test
    void rejectsConstructionFromAnotherMembersLoan() {
        Loan bobs = Loan.open(new MemberId("bob"), DDD, POLICY, CLOCK);

        assertThatThrownBy(() -> new ActiveLoans(ALICE, List.of(bobs)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");
    }
}
