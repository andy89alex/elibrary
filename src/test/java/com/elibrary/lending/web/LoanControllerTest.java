package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.application.ReturnBook;
import com.elibrary.lending.application.ViewLoans;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanLimitReached;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.platform.web.ApiExceptionHandler;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoanControllerTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private BorrowBook borrowBook;
    private ReturnBook returnBook;
    private ViewLoans viewLoans;
    private BookCatalog catalog;
    private MockMvc mockMvc;

    /** Stands in for MemberIdArgumentResolver so the controller can be tested standalone. */
    private static HandlerMethodArgumentResolver fixedMember(MemberId member) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return MemberId.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                                          org.springframework.web.method.support.ModelAndViewContainer mav,
                                          org.springframework.web.context.request.NativeWebRequest request,
                                          org.springframework.web.bind.support.WebDataBinderFactory factory) {
                return member;
            }
        };
    }

    @BeforeEach
    void setUp() {
        borrowBook = mock(BorrowBook.class);
        returnBook = mock(ReturnBook.class);
        viewLoans = mock(ViewLoans.class);
        catalog = mock(BookCatalog.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new LoanController(borrowBook, returnBook, viewLoans, catalog, BORROWED))
                .setCustomArgumentResolvers(fixedMember(ALICE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        when(catalog.summariesFor(any())).thenReturn(List.of(
                new BookSummary(DDD, "Domain-Driven Design", "Eric Evans", ContentKind.BOOK, 4, 3)));
    }

    @Test
    void borrowingReturns201WithALocationPointingAtTheNewLoan() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        when(borrowBook.handle(eq(ALICE), eq(DDD))).thenReturn(loan);

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/loans/" + loan.id()))
                .andExpect(jsonPath("$.id").value(loan.id().toString()))
                .andExpect(jsonPath("$.book.id").value(DDD.toString()))
                .andExpect(jsonPath("$.book.title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.dueOn").value("2026-10-14"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.returnedAt").doesNotExist());
    }

    @Test
    void borrowingWithAMissingBookIdIs400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("bookId"));

        verify(borrowBook, never()).handle(any(), any());
    }

    @Test
    void borrowingWithAMalformedBookIdIs400() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBusinessRefusalSurfacesAs409WithItsCode() throws Exception {
        when(borrowBook.handle(any(), any())).thenThrow(new LoanLimitReached(ALICE, 5, 5));

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_REACHED"));
    }

    @Test
    void returningRespondsWithTheClosedLoan() throws Exception {
        Loan closed = Loan.open(ALICE, DDD, POLICY, BORROWED)
                .returnNow(Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC));
        when(returnBook.handle(eq(ALICE), eq(closed.id()))).thenReturn(closed);

        mockMvc.perform(post("/api/v1/loans/{id}/return", closed.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnedAt").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void listingDefaultsToActiveLoansOfTheCaller() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        when(viewLoans.handle(eq(ALICE), eq(LoanStatusFilter.ACTIVE), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        mockMvc.perform(get("/api/v1/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(loan.id().toString()))
                .andExpect(jsonPath("$.items[0].book.title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(viewLoans).handle(ALICE, LoanStatusFilter.ACTIVE, 0, 20);
    }

    @Test
    void listingAcceptsAnExplicitStatusFilter() throws Exception {
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/loans").param("status", "returned"))
                .andExpect(status().isOk());

        verify(viewLoans).handle(ALICE, LoanStatusFilter.RETURNED, 0, 20);
    }

    @Test
    void anUnknownStatusFilterIs400() throws Exception {
        mockMvc.perform(get("/api/v1/loans").param("status", "pending"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anOverdueActiveLoanIsFlaggedOverdue() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        Clock afterDueDate = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        MockMvc withLateClock = MockMvcBuilders
                .standaloneSetup(new LoanController(borrowBook, returnBook, viewLoans, catalog, afterDueDate))
                .setCustomArgumentResolvers(fixedMember(ALICE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        withLateClock.perform(get("/api/v1/loans"))
                .andExpect(jsonPath("$.items[0].overdue").value(true));
    }

    @Test
    void enrichmentIssuesOneCatalogueLookupForTheWholePageNotOnePerLoan() throws Exception {
        List<Loan> threeLoans = List.of(
                Loan.open(ALICE, DDD, POLICY, BORROWED),
                Loan.open(ALICE, BookId.of("11111111-1111-1111-1111-111111111104"), POLICY, BORROWED),
                Loan.open(ALICE, BookId.of("11111111-1111-1111-1111-111111111107"), POLICY, BORROWED));
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(threeLoans, 0, 20, 3));

        mockMvc.perform(get("/api/v1/loans")).andExpect(status().isOk());

        verify(catalog, org.mockito.Mockito.times(1)).summariesFor(any());
    }

    @Test
    void aLoanWhoseBookVanishedFromTheCatalogueStillRenders() throws Exception {
        Loan loan = Loan.open(ALICE, BookId.newId(), POLICY, BORROWED);
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        mockMvc.perform(get("/api/v1/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].book.id").value(loan.bookId().toString()))
                .andExpect(jsonPath("$.items[0].book.title").doesNotExist());
    }
}
