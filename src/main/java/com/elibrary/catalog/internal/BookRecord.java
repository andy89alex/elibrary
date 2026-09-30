package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Persistence shape of a catalogue item, and the authority on copy availability.
 *
 * <p>The copy guards live on the record rather than in a service because availability is
 * the one piece of state the catalogue genuinely owns. The database enforces the same rule
 * via {@code chk_books_available_copies}, so even a write that bypasses this class cannot
 * drive availability out of range.
 */
@Entity
@Table(name = "books")
class BookRecord {

    @Id
    private UUID id;

    private String title;

    private String author;

    private String isbn;

    @Enumerated(EnumType.STRING)
    private ContentKind kind;

    private String publisher;

    @Column(name = "publication_year")
    private Integer publicationYear;

    private String volume;

    private String issue;

    @Column(name = "total_copies")
    private int totalCopies;

    @Column(name = "available_copies")
    private int availableCopies;

    @Version
    private long version;

    protected BookRecord() {
        // required by JPA
    }

    void checkoutCopy() {
        if (availableCopies <= 0) {
            throw new BookUnavailable(new BookId(id));
        }
        availableCopies--;
    }

    void restoreCopy() {
        if (availableCopies >= totalCopies) {
            throw new IllegalStateException(
                    "Cannot restore a copy of book " + id + ": all " + totalCopies + " copies are already present.");
        }
        availableCopies++;
    }

    BookSummary toSummary() {
        return new BookSummary(new BookId(id), title, author, kind, totalCopies, availableCopies);
    }

    BookDetail toDetail() {
        return new BookDetail(new BookId(id), title, author, Isbn.of(isbn), kind,
                publisher, publicationYear, volume, issue, totalCopies, availableCopies);
    }

    int availableCopies() {
        return availableCopies;
    }

    int totalCopies() {
        return totalCopies;
    }
}
