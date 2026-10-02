package com.elibrary.integration;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.application.ReturnBook;
import com.elibrary.lending.application.ViewLoans;
import com.elibrary.lending.domain.AlreadyBorrowed;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One member double-submitting the same borrow, which is invariant 3 under contention.
 *
 * <p>Distinct from {@link LastCopyConcurrencyIT}: that one contends several members on the
 * last copy and is protected by the write lock {@code checkout} takes on the book row. This
 * one cannot be. {@code BorrowBook.handle} reads the member's position with no lock, so two
 * threads both see "not yet borrowed", both pass the domain check, and both reach the
 * insert. Nothing in the aggregate can close that window — the guard has to be in the
 * database.
 *
 * <p>The unique constraint on {@code (member_id, active_book_id)} is that guard.
 * {@code active_book_id} carries the book while the loan is open and NULL once it is
 * returned; because NULLs never collide, the rule binds active loans only and a member may
 * borrow the same book again after returning it. That is the behaviour of a PostgreSQL
 * partial unique index, expressed with no database-specific feature.
 *
 * <p>The copy count is asserted too: the losing transaction rolls back, and its rollback has
 * to take the copy it checked out with it.
 */
@SpringBootTest
class DoubleSubmitConcurrencyIT {

    // The datasource is a named in-memory H2 shared by every integration test in the JVM,
    // so loans outlive the class that made them. Both pairs below are touched by no other
    // test; the preconditions assert that rather than trusting it.
    /** Java Concurrency in Practice — 3 copies, so availability cannot be what refuses anyone. */
    private static final BookId CONTENDED = BookId.of("11111111-1111-1111-1111-111111111108");
    /** Refactoring — 5 copies. */
    private static final BookId REBORROWED = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final MemberId ALICE = new MemberId("alice");
    private static final int CONTENDERS = 2;

    @Autowired
    private BorrowBook borrowBook;

    @Autowired
    private ViewLoans viewLoans;

    @Autowired
    private ReturnBook returnBook;

    @Autowired
    private BookCatalog catalog;

    @Test
    void twoSimultaneousBorrowsOfTheSameBookLeaveTheMemberWithExactlyOneLoan() throws Exception {
        int copiesBefore = catalog.findById(CONTENDED).orElseThrow().availableCopies();
        assertThat(copiesBefore)
                .as("precondition: plenty of copies, so invariant 1 cannot be the one refusing")
                .isGreaterThan(CONTENDERS);
        assertThat(viewLoans.handle(ALICE, LoanStatusFilter.ACTIVE, 0, 50).items())
                .as("precondition: alice must not already hold this book — another test in the "
                        + "shared database has taken it, so pick a pair nothing else uses")
                .noneMatch(loan -> loan.isFor(CONTENDED));

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < CONTENDERS; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                try {
                    borrowBook.handle(ALICE, CONTENDED);
                    succeeded.incrementAndGet();
                } catch (AlreadyBorrowed expected) {
                    // the honest refusal, however it was detected
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                }
                return null;
            }));
        }

        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (Future<?> future : futures) {
            future.get();
        }

        assertThat(unexpected)
                .as("the loser must be refused as a business conflict, not an infrastructure error")
                .isEmpty();
        assertThat(succeeded).hasValue(1);

        List<Loan> held = viewLoans.handle(ALICE, LoanStatusFilter.ACTIVE, 0, 50).items().stream()
                .filter(loan -> loan.isFor(CONTENDED))
                .toList();
        assertThat(held)
                .as("a member may hold a given book exactly once")
                .hasSize(1);

        assertThat(catalog.findById(CONTENDED).orElseThrow().availableCopies())
                .as("the losing transaction must return the copy it checked out")
                .isEqualTo(copiesBefore - 1);
    }

    @Test
    void theSameBookMayBeBorrowedAgainAfterItIsReturned() {
        MemberId carol = new MemberId("carol");
        assertThat(viewLoans.handle(carol, LoanStatusFilter.ALL, 0, 50).items())
                .as("precondition: this pair is untouched by other tests")
                .noneMatch(loan -> loan.isFor(REBORROWED));

        Loan first = borrowBook.handle(carol, REBORROWED);
        returnBook.handle(carol, first.id());

        Loan second = borrowBook.handle(carol, REBORROWED);

        assertThat(second.id())
                .as("returning nulls active_book_id, so the constraint no longer sees the old row")
                .isNotEqualTo(first.id());
        assertThat(viewLoans.handle(carol, LoanStatusFilter.ALL, 0, 50).items())
                .filteredOn(loan -> loan.isFor(REBORROWED))
                .hasSize(2);
    }
}
