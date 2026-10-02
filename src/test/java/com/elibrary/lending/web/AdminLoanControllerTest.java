package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.application.ViewAllLoans;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.platform.web.ApiExceptionHandler;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminLoanControllerTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    /** Borrowed 2026-09-30 → due 2026-10-14; "now" is 2026-10-20, so it is overdue. */
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC);

    private ViewAllLoans viewAllLoans;
    private BookCatalog catalog;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        viewAllLoans = mock(ViewAllLoans.class);
        catalog = mock(BookCatalog.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminLoanController(viewAllLoans, catalog, NOW))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static PageResult<Loan> page(Loan... loans) {
        return PageResult.of(List.of(loans), 0, 20, loans.length);
    }

    private void catalogueKnows(BookId id, String title, String author) {
        when(catalog.summariesFor(any()))
                .thenReturn(List.of(new BookSummary(id, title, author, ContentKind.BOOK, 4, 3)));
    }

    @Test
    void exposesWhoHoldsEachLoan() throws Exception {
        catalogueKnows(DDD, "Domain-Driven Design", "Eric Evans");
        when(viewAllLoans.handle(any())).thenReturn(page(Loan.open(ALICE, DDD, POLICY, BORROWED)));

        mockMvc.perform(get("/api/v1/admin/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].memberId").value("alice"))
                .andExpect(jsonPath("$.items[0].book.title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].dueOn").value("2026-10-14"))
                .andExpect(jsonPath("$.items[0].overdue").value(true));
    }

    @Test
    void translatesEveryQueryParameterIntoTheCriteria() throws Exception {
        catalogueKnows(DDD, "Domain-Driven Design", "Eric Evans");
        when(viewAllLoans.handle(any())).thenReturn(page());

        mockMvc.perform(get("/api/v1/admin/loans")
                        .param("memberId", "bob")
                        .param("bookId", DDD.toString())
                        .param("status", "returned")
                        .param("overdue", "true")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<LoanSearchCriteria> captor = ArgumentCaptor.forClass(LoanSearchCriteria.class);
        verify(viewAllLoans).handle(captor.capture());
        LoanSearchCriteria criteria = captor.getValue();

        assertThat(criteria.memberId()).isEqualTo(BOB);
        assertThat(criteria.bookId()).isEqualTo(DDD);
        assertThat(criteria.status()).isEqualTo(LoanStatusFilter.RETURNED);
        assertThat(criteria.overdueOnly()).isTrue();
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.size()).isEqualTo(5);
    }

    @Test
    void defaultsToEveryActiveLoanWhenNoFilterIsGiven() throws Exception {
        when(viewAllLoans.handle(any())).thenReturn(page());

        mockMvc.perform(get("/api/v1/admin/loans")).andExpect(status().isOk());

        ArgumentCaptor<LoanSearchCriteria> captor = ArgumentCaptor.forClass(LoanSearchCriteria.class);
        verify(viewAllLoans).handle(captor.capture());

        assertThat(captor.getValue().memberId()).isNull();
        assertThat(captor.getValue().bookId()).isNull();
        assertThat(captor.getValue().status()).isEqualTo(LoanStatusFilter.ACTIVE);
        assertThat(captor.getValue().overdueOnly()).isFalse();
    }

    @Test
    void enrichesAWholePageWithASingleCatalogueLookup() throws Exception {
        catalogueKnows(DDD, "Domain-Driven Design", "Eric Evans");
        when(viewAllLoans.handle(any())).thenReturn(page(
                Loan.open(ALICE, DDD, POLICY, BORROWED),
                Loan.open(BOB, DDD, POLICY, BORROWED)));

        mockMvc.perform(get("/api/v1/admin/loans")).andExpect(status().isOk());

        verify(catalog, times(1)).summariesFor(any());
    }

    @Test
    void rejectsAnUnknownStatusWithFourHundred() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loans").param("status", "lost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAMalformedBookIdWithFourHundred() throws Exception {
        mockMvc.perform(get("/api/v1/admin/loans").param("bookId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void stillRendersALoanWhoseBookHasLeftTheCatalogue() throws Exception {
        when(catalog.summariesFor(any())).thenReturn(List.of());
        when(viewAllLoans.handle(any())).thenReturn(page(Loan.open(ALICE, DDD, POLICY, BORROWED)));

        mockMvc.perform(get("/api/v1/admin/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].memberId").value("alice"))
                .andExpect(jsonPath("$.items[0].book.title").doesNotExist());
    }
}
