package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.application.ReturnBook;
import com.elibrary.lending.application.ViewLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Loan endpoints.
 *
 * <p>No path carries a member id: scope is always the authenticated caller, so one member's
 * loans cannot be requested by guessing a URL.
 *
 * <p>{@code POST /loans/{id}/return} is a conscious departure from strict REST. Modelling it
 * as {@code PATCH /loans/{id}} with a status field would hand the state transition to the
 * client and let illegal transitions be expressed in a request body; a named action is more
 * honest about the domain.
 */
@Tag(name = "Lending", description = "Borrow, return, and view your loans")
@RestController
@RequestMapping(path = "/api/v1/loans", produces = MediaType.APPLICATION_JSON_VALUE)
class LoanController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final BorrowBook borrowBook;
    private final ReturnBook returnBook;
    private final ViewLoans viewLoans;
    private final BookCatalog catalog;
    private final Clock clock;

    LoanController(BorrowBook borrowBook, ReturnBook returnBook, ViewLoans viewLoans,
                   BookCatalog catalog, Clock clock) {
        this.borrowBook = borrowBook;
        this.returnBook = returnBook;
        this.viewLoans = viewLoans;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Operation(summary = "Borrow a book",
            description = "Creates a loan for the authenticated member. 409 when no copy is "
                    + "available, the member is at the loan limit, or the member already holds this book.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoanResponse> borrow(MemberId member, @Valid @RequestBody BorrowRequest request) {
        Loan loan = borrowBook.handle(member, BookId.of(request.bookId()));
        return ResponseEntity
                .created(URI.create("/api/v1/loans/" + loan.id()))
                .body(enrich(loan));
    }

    @Operation(summary = "Return a borrowed book")
    @PostMapping("/{loanId}/return")
    LoanResponse returnLoan(MemberId member, @PathVariable String loanId) {
        return enrich(returnBook.handle(member, LoanId.of(loanId)));
    }

    @Operation(summary = "View your loans",
            description = "Defaults to currently borrowed items. status accepts active|returned|all.")
    @GetMapping
    PageResult<LoanResponse> list(
            MemberId member,
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = Math.min(size == null || size < 1 ? DEFAULT_SIZE : size, MAX_SIZE);

        PageResult<Loan> loans = viewLoans.handle(member, parseStatus(status), safePage, safeSize);
        return enrich(loans);
    }

    @Operation(summary = "Retrieve one of your loans")
    @GetMapping("/{loanId}")
    LoanResponse detail(MemberId member, @PathVariable String loanId) {
        return enrich(viewLoans.byId(member, LoanId.of(loanId)));
    }

    /**
     * Enriches a whole page with catalogue data in a single lookup. Doing this per row would
     * be an N+1 against the catalogue; composing the two modules' data here, at the boundary,
     * also keeps the lending domain unaware that a catalogue exists.
     */
    private PageResult<LoanResponse> enrich(PageResult<Loan> loans) {
        Map<BookId, BookSummary> summaries = catalog
                .summariesFor(loans.items().stream().map(Loan::bookId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(BookSummary::id, Function.identity()));

        return loans.map(loan -> LoanResponse.of(loan, summaries.get(loan.bookId()), clock));
    }

    private LoanResponse enrich(Loan loan) {
        List<BookSummary> found = catalog.summariesFor(List.of(loan.bookId()));
        return LoanResponse.of(loan, found.isEmpty() ? null : found.getFirst(), clock);
    }

    private static LoanStatusFilter parseStatus(String raw) {
        try {
            return LoanStatusFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown status '" + raw + "'. Valid values: active, returned, all.");
        }
    }
}
