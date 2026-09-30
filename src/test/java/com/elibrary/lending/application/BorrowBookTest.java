package com.elibrary.lending.application;

import com.elibrary.lending.domain.AlreadyBorrowed;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanLimitReached;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.error.BookNotFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BorrowBookTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private InMemoryBookInventory inventory;
    private BorrowBook borrowBook;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        inventory = new InMemoryBookInventory();
        borrowBook = new BorrowBook(loans, inventory, new LendingPolicy(2, 14), CLOCK);
    }

    @Test
    void borrowingOpensALoanAndTakesOneCopy() {
        inventory.stock(DDD, 3);

        Loan loan = borrowBook.handle(ALICE, DDD);

        assertThat(loan.isActive()).isTrue();
        assertThat(loan.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(2);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ACTIVE, 0, 10).items()).containsExactly(loan);
    }

    @Test
    void propagatesUnavailabilityFromTheCatalogue() {
        inventory.stock(DDD, 0);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(BookUnavailable.class);
    }

    @Test
    void propagatesAnUnknownBook() {
        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(BookNotFound.class);
    }

    @Test
    void doesNotTakeACopyWhenTheMemberSideRulesRejectTheBorrow() {
        inventory.stock(DDD, 3);
        borrowBook.handle(ALICE, DDD);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(AlreadyBorrowed.class);
        assertThat(inventory.availableCopiesOf(DDD))
                .as("member-side checks run before the copy is taken")
                .isEqualTo(2);
    }

    @Test
    void rejectsBorrowingBeyondTheConfiguredLimit() {
        inventory.stock(DDD, 3);
        inventory.stock(REFACTORING, 3);
        BookId third = BookId.of("11111111-1111-1111-1111-111111111107");
        inventory.stock(third, 3);

        borrowBook.handle(ALICE, DDD);
        borrowBook.handle(ALICE, REFACTORING);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, third)).isInstanceOf(LoanLimitReached.class);
        assertThat(inventory.availableCopiesOf(third)).isEqualTo(3);
    }

    @Test
    void aReturnedLoanDoesNotCountTowardsTheLimit() {
        inventory.stock(DDD, 3);
        Loan first = borrowBook.handle(ALICE, DDD);
        loans.save(first.returnNow(CLOCK));

        assertThat(borrowBook.handle(ALICE, DDD)).isNotNull();
    }
}
