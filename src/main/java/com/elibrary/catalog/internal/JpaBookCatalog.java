package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSummary;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class JpaBookCatalog implements BookCatalog {

    private final BookJpaRepository books;

    JpaBookCatalog(BookJpaRepository books) {
        this.books = books;
    }

    @Override
    public PageResult<BookSummary> search(BookSearchCriteria criteria) {
        Sort sort = Sort.by(
                criteria.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                criteria.sortField().property());

        Page<BookRecord> page = books.search(
                like(criteria.q()),
                like(criteria.author()),
                criteria.availableOnly(),
                PageRequest.of(criteria.page(), criteria.size(), sort));

        return PageResult.of(
                page.getContent().stream().map(BookRecord::toSummary).toList(),
                criteria.page(),
                criteria.size(),
                page.getTotalElements());
    }

    @Override
    public Optional<BookDetail> findById(BookId id) {
        return books.findById(id.value()).map(BookRecord::toDetail);
    }

    @Override
    public List<BookSummary> summariesFor(Collection<BookId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> raw = ids.stream().map(BookId::value).distinct().toList();
        return books.findByIdIn(raw).stream().map(BookRecord::toSummary).toList();
    }

    private static String like(String term) {
        return term == null ? null : "%" + term.toLowerCase(Locale.ROOT) + "%";
    }
}
