package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    private static Clock at(String isoDate) {
        return Clock.fixed(Instant.parse(isoDate + "T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void opensActiveWithADueDateDrivenByThePolicy() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        assertThat(loan.isActive()).isTrue();
        assertThat(loan.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(loan.returnedAt()).isNull();
        assertThat(loan.memberId()).isEqualTo(ALICE);
        assertThat(loan.bookId()).isEqualTo(DDD);
    }

    @Test
    void returningProducesANewClosedLoanAndLeavesTheOriginalUntouched() {
        Loan open = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        Loan closed = open.returnNow(at("2026-10-02"));

        assertThat(closed.isActive()).isFalse();
        assertThat(closed.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(open.isActive()).as("the aggregate is immutable").isTrue();
    }

    @Test
    void returningTwiceIsRejected() {
        Loan closed = Loan.open(ALICE, DDD, POLICY, at("2026-09-30")).returnNow(at("2026-10-02"));

        assertThatThrownBy(() -> closed.returnNow(at("2026-10-03")))
                .isInstanceOf(LoanAlreadyReturned.class)
                .satisfies(e -> assertThat(((LoanAlreadyReturned) e).code()).isEqualTo("LOAN_ALREADY_RETURNED"));
    }

    @Test
    void isNotOverdueOnTheDueDateItself() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(loan.isOverdue(at("2026-10-14"))).isFalse();
    }

    @Test
    void becomesOverdueTheDayAfterTheDueDate() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(loan.isOverdue(at("2026-10-15"))).isTrue();
    }

    @Test
    void aReturnedLoanIsNeverOverdueHoweverLateItWas() {
        Loan closed = Loan.open(ALICE, DDD, POLICY, at("2026-09-30")).returnNow(at("2026-12-01"));
        assertThat(closed.isOverdue(at("2027-01-01"))).isFalse();
    }

    @Test
    void knowsWhoItBelongsToAndWhatItIsFor() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        assertThat(loan.belongsTo(ALICE)).isTrue();
        assertThat(loan.belongsTo(new MemberId("bob"))).isFalse();
        assertThat(loan.isFor(DDD)).isTrue();
        assertThat(loan.isFor(BookId.of("11111111-1111-1111-1111-111111111102"))).isFalse();
    }

    @Test
    void reconstitutionFromStorageRoundTripsEveryField() {
        LoanId id = LoanId.newId();
        Instant borrowedAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant returnedAt = Instant.parse("2026-10-02T10:00:00Z");

        Loan loan = Loan.reconstitute(id, ALICE, DDD, borrowedAt, LocalDate.of(2026, 10, 14), returnedAt);

        assertThat(loan.id()).isEqualTo(id);
        assertThat(loan.borrowedAt()).isEqualTo(borrowedAt);
        assertThat(loan.returnedAt()).isEqualTo(returnedAt);
        assertThat(loan.isActive()).isFalse();
    }

    @Test
    void identityIsTheLoanIdAloneSoStateChangesDoNotBreakEquality() {
        Loan open = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(open.returnNow(at("2026-10-02"))).isEqualTo(open);
    }
}
