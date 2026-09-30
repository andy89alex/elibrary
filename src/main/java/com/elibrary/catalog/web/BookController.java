package com.elibrary.catalog.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.shared.error.BookNotFound;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalogue", description = "Browse books and journals")
@RestController
@RequestMapping(path = "/api/v1/books", produces = MediaType.APPLICATION_JSON_VALUE)
class BookController {

    private final BookCatalog catalog;

    BookController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    /** Response shape for list views. Value objects are flattened to strings for the wire. */
    record BookSummaryResponse(
            String id, String title, String author, ContentKind kind,
            int totalCopies, int availableCopies, boolean available) {

        static BookSummaryResponse from(BookSummary summary) {
            return new BookSummaryResponse(
                    summary.id().toString(), summary.title(), summary.author(), summary.kind(),
                    summary.totalCopies(), summary.availableCopies(), summary.available());
        }
    }

    record BookDetailResponse(
            String id, String title, String author, String isbn, ContentKind kind,
            String publisher, Integer publicationYear, String volume, String issue,
            int totalCopies, int availableCopies, boolean available) {

        static BookDetailResponse from(BookDetail detail) {
            return new BookDetailResponse(
                    detail.id().toString(), detail.title(), detail.author(),
                    detail.isbn() == null ? null : detail.isbn().value(), detail.kind(),
                    detail.publisher(), detail.publicationYear(), detail.volume(), detail.issue(),
                    detail.totalCopies(), detail.availableCopies(), detail.available());
        }
    }

    @Operation(summary = "Browse the catalogue",
            description = "Free-text search over title and author, optional author filter, "
                    + "optional availability filter, offset paging. Sort accepts "
                    + "title|author|publicationYear followed by asc|desc.")
    @GetMapping
    PageResult<BookSummaryResponse> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(name = "available", required = false, defaultValue = "false") boolean availableOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        BookSearchCriteria criteria = BookSearchCriteria.of(
                q, author, availableOnly, sortField(sort), ascending(sort), page, size);

        return catalog.search(criteria).map(BookSummaryResponse::from);
    }

    @Operation(summary = "Retrieve one catalogue item")
    @GetMapping("/{bookId}")
    BookDetailResponse detail(@PathVariable String bookId) {
        BookId id = BookId.of(bookId);
        return catalog.findById(id).map(BookDetailResponse::from).orElseThrow(() -> new BookNotFound(id));
    }

    /**
     * Parses {@code field,direction}. An unrecognised field is a 400 rather than a silent
     * fallback: quietly ignoring it would make a client believe its ordering was applied.
     */
    private static BookSortField sortField(String sort) {
        if (sort == null || sort.isBlank()) {
            return BookSortField.TITLE;
        }
        String field = sort.split(",")[0].trim();
        return BookSortField.parse(field).orElseThrow(() -> new IllegalArgumentException(
                "Cannot sort by '" + field + "'. Sortable fields: title, author, publicationYear."));
    }

    private static boolean ascending(String sort) {
        if (sort == null || sort.isBlank()) {
            return true;
        }
        String[] parts = sort.split(",", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return true;
        }
        return !"desc".equalsIgnoreCase(parts[1].trim());
    }
}
