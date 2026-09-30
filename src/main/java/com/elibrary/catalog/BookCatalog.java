package com.elibrary.catalog;

import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The catalogue's entire public surface. Everything else in the module is package-private. */
public interface BookCatalog {

    PageResult<BookSummary> search(BookSearchCriteria criteria);

    Optional<BookDetail> findById(BookId id);

    /**
     * Batch lookup so callers enriching a list of loans issue one query rather than one
     * per row. Ids with no matching book are simply absent from the result.
     */
    List<BookSummary> summariesFor(Collection<BookId> ids);
}
