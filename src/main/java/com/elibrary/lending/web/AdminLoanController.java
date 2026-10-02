package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.lending.application.ViewAllLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The librarian's view of the whole ledger: who holds what, since when, due when, and
 * whether it is late.
 *
 * <p>A separate resource from {@code /api/v1/loans} rather than a flag on it. That endpoint
 * means "my loans" and derives its scope from the principal, which is the property that
 * stops one member reading another's data; overloading it so the same URL returns different
 * rows depending on the caller's role would make that contract conditional and far harder to
 * document or test. A distinct path also gives authorisation a single prefix to guard —
 * {@code /api/v1/admin/**} is restricted to {@code LIBRARIAN} in the filter chain.
 *
 * <p>Unlike every other loan endpoint, the response carries {@code memberId}: identifying
 * the borrower is the entire point here, whereas elsewhere it is always the caller.
 */
@Tag(name = "Administration", description = "Librarian view over every member's loans")
@RestController
@RequestMapping(path = "/api/v1/admin/loans", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminLoanController {

    private final ViewAllLoans viewAllLoans;
    private final BookCatalog catalog;
    private final Clock clock;

    AdminLoanController(ViewAllLoans viewAllLoans, BookCatalog catalog, Clock clock) {
        this.viewAllLoans = viewAllLoans;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Operation(summary = "Browse every member's loans",
            description = "Filters are optional and combine: supplying several narrows the result. "
                    + "status accepts active|returned|all and defaults to active. "
                    + "overdue=true keeps only active loans past their due date.")
    @GetMapping
    PageResult<AdminLoanResponse> browse(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(name = "overdue", required = false, defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        LoanSearchCriteria criteria = LoanSearchCriteria.of(
                memberId == null || memberId.isBlank() ? null : new MemberId(memberId),
                bookId == null || bookId.isBlank() ? null : BookId.of(bookId),
                parseStatus(status),
                overdueOnly,
                page,
                size);

        return enrich(viewAllLoans.handle(criteria));
    }

    /**
     * One catalogue lookup for the whole page. Resolving per row would be an N+1 against the
     * catalogue; composing the two modules here, at the boundary, also keeps the lending
     * domain unaware that a catalogue exists.
     */
    private PageResult<AdminLoanResponse> enrich(PageResult<Loan> loans) {
        Map<BookId, BookSummary> summaries = catalog
                .summariesFor(loans.items().stream().map(Loan::bookId).toList())
                .stream()
                .collect(Collectors.toMap(BookSummary::id, Function.identity()));

        return loans.map(loan -> AdminLoanResponse.of(loan, summaries.get(loan.bookId()), clock));
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
