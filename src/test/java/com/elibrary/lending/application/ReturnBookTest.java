package com.elibrary.lending.application;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanAlreadyReturned;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnBookTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock RETURNED = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private InMemoryBookInventory inventory;
    private ReturnBook returnBook;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        inventory = new InMemoryBookInventory();
        inventory.stock(DDD, 2);
        returnBook = new ReturnBook(loans, inventory, RETURNED);
    }

    @Test
    void returningClosesTheLoanAndGivesTheCopyBack() {
        Loan open = Loan.open(ALICE, DDD, POLICY, BORROWED);
        loans.seed(open);

        Loan closed = returnBook.handle(ALICE, open.id());

        assertThat(closed.isActive()).isFalse();
        assertThat(closed.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(3);
    }

    @Test
    void returningAnUnknownLoanIsNotFound() {
        assertThatThrownBy(() -> returnBook.handle(ALICE, LoanId.newId()))
                .isInstanceOf(LoanNotFound.class);
    }

    @Test
    void anotherMembersLoanIsReportedAsNotFoundRatherThanForbidden() {
        Loan bobs = Loan.open(BOB, DDD, POLICY, BORROWED);
        loans.seed(bobs);

        assertThatThrownBy(() -> returnBook.handle(ALICE, bobs.id()))
                .as("404 rather than 403 so existence is not leaked")
                .isInstanceOf(LoanNotFound.class);
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(2);
    }

    @Test
    void returningTwiceIsRejectedAndDoesNotInflateTheCopyCount() {
        Loan open = Loan.open(ALICE, DDD, POLICY, BORROWED);
        loans.seed(open);
        returnBook.handle(ALICE, open.id());

        assertThatThrownBy(() -> returnBook.handle(ALICE, open.id()))
                .isInstanceOf(LoanAlreadyReturned.class);
        assertThat(inventory.availableCopiesOf(DDD))
                .as("the copy must not be restored twice")
                .isEqualTo(3);
    }
}
