package com.elibrary.catalog.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.platform.web.ApiExceptionHandler;
import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookControllerTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");

    private BookCatalog catalog;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalog = mock(BookCatalog.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BookController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static BookSummary summary() {
        return new BookSummary(DDD, "Domain-Driven Design", "Eric Evans", ContentKind.BOOK, 4, 3);
    }

    @Test
    void returnsOurPaginationEnvelopeRatherThanSpringsPageShape() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(summary()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(DDD.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.items[0].availableCopies").value(3))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void passesQueryParametersThroughAsCriteria() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 1, 5, 0));

        mockMvc.perform(get("/api/v1/books")
                        .param("q", "fowler")
                        .param("author", "Martin")
                        .param("available", "true")
                        .param("sort", "publicationYear,desc")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        BookSearchCriteria criteria = captor.getValue();

        assertThat(criteria.q()).isEqualTo("fowler");
        assertThat(criteria.author()).isEqualTo("Martin");
        assertThat(criteria.availableOnly()).isTrue();
        assertThat(criteria.sortField()).isEqualTo(BookSortField.PUBLICATION_YEAR);
        assertThat(criteria.ascending()).isFalse();
        assertThat(criteria.page()).isEqualTo(1);
        assertThat(criteria.size()).isEqualTo(5);
    }

    @Test
    void appliesDefaultsWhenNoParametersAreGiven() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/books")).andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());

        assertThat(captor.getValue().sortField()).isEqualTo(BookSortField.TITLE);
        assertThat(captor.getValue().ascending()).isTrue();
        assertThat(captor.getValue().size()).isEqualTo(20);
        assertThat(captor.getValue().availableOnly()).isFalse();
    }

    @Test
    void clampsAnOversizedPageRequest() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 100, 0));

        mockMvc.perform(get("/api/v1/books").param("size", "100000")).andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(BookSearchCriteria.MAX_SIZE);
    }

    @Test
    void rejectsASortFieldThatIsNotWhitelisted() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("sort", "availableCopies,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("availableCopies")));
    }

    @Test
    void returnsDetailForAKnownBook() throws Exception {
        when(catalog.findById(DDD)).thenReturn(Optional.of(new BookDetail(
                DDD, "Domain-Driven Design", "Eric Evans", Isbn.of("9780321125217"),
                ContentKind.BOOK, "Addison-Wesley", 2003, null, null, 4, 3)));

        mockMvc.perform(get("/api/v1/books/{id}", DDD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value("9780321125217"))
                .andExpect(jsonPath("$.publisher").value("Addison-Wesley"))
                .andExpect(jsonPath("$.publicationYear").value(2003));
    }

    @Test
    void aTrailingCommaInSortIsTreatedAsNoDirectionRatherThanAServerError() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/books").param("sort", "title,"))
                .andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        assertThat(captor.getValue().sortField()).isEqualTo(BookSortField.TITLE);
        assertThat(captor.getValue().ascending()).isTrue();
    }

    @Test
    void aBareSortFieldWithNoDirectionIsAscending() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/books").param("sort", "title"))
                .andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        assertThat(captor.getValue().ascending()).isTrue();
    }

    @Test
    void anUnknownBookIs404WithTheBookNotFoundCode() throws Exception {
        when(catalog.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/books/{id}", BookId.newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    void aMalformedBookIdIs400NotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
