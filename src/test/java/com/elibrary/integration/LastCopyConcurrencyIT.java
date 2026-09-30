package com.elibrary.integration;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.domain.BookUnavailable;
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
 * The last copy of a book, contended by many members at once.
 *
 * <p>This is the test that gives the locking decision teeth. Without the pessimistic write
 * lock in CatalogBookInventory, several transactions read {@code available_copies = 1},
 * all decrement, and the library lends a book it does not have. The database check
 * constraint would catch some of that, but the point is that the application must not
 * depend on the constraint to stay correct.
 */
@SpringBootTest
class LastCopyConcurrencyIT {

    /** Seeded with exactly one available copy. */
    private static final BookId TIDY_FIRST = BookId.of("11111111-1111-1111-1111-111111111111");
    private static final int CONTENDERS = 8;

    @Autowired
    private BorrowBook borrowBook;

    @Autowired
    private BookCatalog catalog;

    @Test
    void exactlyOneMemberGetsTheLastCopy() throws Exception {
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().availableCopies())
                .as("precondition: exactly one copy is available")
                .isEqualTo(1);

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                MemberId member = new MemberId("contender-" + i);
                attempts.add(pool.submit(() -> {
                    try {
                        startTogether.await();
                        borrowBook.handle(member, TIDY_FIRST);
                        succeeded.incrementAndGet();
                    } catch (BookUnavailable expected) {
                        refused.incrementAndGet();
                    } catch (Throwable other) {
                        unexpected.add(other);
                    }
                    return null;
                }));
            }

            startTogether.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get())
                .as("the library must not lend a copy it does not have")
                .isEqualTo(1);
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().availableCopies())
                .as("availability must land on exactly zero, never negative")
                .isZero();
        assertThat(succeeded.get() + refused.get() + unexpected.size()).isEqualTo(CONTENDERS);
        assertThat(unexpected)
                .as("losers should be refused cleanly, not fail with lock or constraint errors")
                .isEmpty();
    }
}
