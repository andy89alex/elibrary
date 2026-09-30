package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.lending.domain.BookInventory;
import com.elibrary.shared.error.BookNotFound;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({JpaBookCatalog.class, CatalogBookInventory.class})
class CatalogBookInventoryTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId TIDY_FIRST = BookId.of("11111111-1111-1111-1111-111111111111");
    private static final BookId ACCELERATE = BookId.of("11111111-1111-1111-1111-111111111110");

    @Autowired
    private BookInventory inventory;

    @Autowired
    private BookCatalog catalog;

    private int availableCopiesOf(BookId id) {
        return catalog.findById(id).orElseThrow().availableCopies();
    }

    @Test
    void checkoutTakesExactlyOneCopy() {
        int before = availableCopiesOf(DDD);

        inventory.checkout(DDD);

        assertThat(availableCopiesOf(DDD)).isEqualTo(before - 1);
    }

    @Test
    void checkoutOfTheLastCopyLeavesTheBookUnavailable() {
        inventory.checkout(TIDY_FIRST);

        assertThat(availableCopiesOf(TIDY_FIRST)).isZero();
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().available()).isFalse();
    }

    @Test
    void checkoutOfAFullyBorrowedBookIsRejected() {
        assertThatThrownBy(() -> inventory.checkout(ACCELERATE))
                .isInstanceOf(BookUnavailable.class)
                .satisfies(e -> assertThat(((BookUnavailable) e).code()).isEqualTo("NO_COPIES_AVAILABLE"));
    }

    @Test
    void checkoutOfAnUnknownBookIsNotFound() {
        assertThatThrownBy(() -> inventory.checkout(BookId.newId()))
                .isInstanceOf(BookNotFound.class)
                .satisfies(e -> assertThat(((BookNotFound) e).code()).isEqualTo("BOOK_NOT_FOUND"));
    }

    @Test
    void restoreGivesTheCopyBack() {
        inventory.checkout(DDD);
        int afterCheckout = availableCopiesOf(DDD);

        inventory.restore(DDD);

        assertThat(availableCopiesOf(DDD)).isEqualTo(afterCheckout + 1);
    }

    @Test
    void restoreBeyondTheTotalNumberOfCopiesIsRejected() {
        assertThatThrownBy(() -> inventory.restore(DDD))
                .as("no copy is out, so there is nothing to give back")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoreOfAnUnknownBookIsNotFound() {
        assertThatThrownBy(() -> inventory.restore(BookId.newId())).isInstanceOf(BookNotFound.class);
    }
}
