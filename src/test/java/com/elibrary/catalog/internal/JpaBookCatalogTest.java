package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaBookCatalog.class)
class JpaBookCatalogTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId LEGACY_CODE = BookId.of("11111111-1111-1111-1111-111111111109");

    @Autowired
    private BookCatalog catalog;

    private static BookSearchCriteria criteria(String q, String author, boolean availableOnly) {
        return BookSearchCriteria.of(q, author, availableOnly, BookSortField.TITLE, true, 0, 50);
    }

    @Test
    void returnsTheWholeSeededCatalogueByDefault() {
        PageResult<BookSummary> result = catalog.search(criteria(null, null, false));
        assertThat(result.totalElements()).isEqualTo(15);
    }

    @Test
    void matchesTheFreeTextTermAgainstTitleAndAuthorCaseInsensitively() {
        assertThat(catalog.search(criteria("DOMAIN-DRIVEN", null, false)).items())
                .extracting(BookSummary::title)
                .contains("Domain-Driven Design", "Implementing Domain-Driven Design");

        assertThat(catalog.search(criteria("fowler", null, false)).items())
                .extracting(BookSummary::author)
                .containsOnly("Martin Fowler");
    }

    @Test
    void filtersByAuthorSeparatelyFromTheFreeTextTerm() {
        assertThat(catalog.search(criteria(null, "goetz", false)).items())
                .extracting(BookSummary::title)
                .containsExactly("Java Concurrency in Practice");
    }

    @Test
    void availableOnlyFiltersInTheQuerySoPagingStaysCorrect() {
        PageResult<BookSummary> result = catalog.search(criteria(null, null, true));

        assertThat(result.totalElements())
                .as("two seeded items have zero available copies")
                .isEqualTo(13);
        assertThat(result.items()).allMatch(BookSummary::available);
        assertThat(result.items()).extracting(BookSummary::id).doesNotContain(LEGACY_CODE);
    }

    @Test
    void sortsByTheWhitelistedFieldInBothDirections() {
        List<String> ascending = catalog.search(
                        BookSearchCriteria.of(null, null, false, BookSortField.TITLE, true, 0, 50))
                .items().stream().map(BookSummary::title).toList();
        List<String> descending = catalog.search(
                        BookSearchCriteria.of(null, null, false, BookSortField.TITLE, false, 0, 50))
                .items().stream().map(BookSummary::title).toList();

        assertThat(ascending).isSorted();
        assertThat(descending).isEqualTo(ascending.reversed());
    }

    @Test
    void pagesWithACorrectTotalAndPageCount() {
        PageResult<BookSummary> firstPage = catalog.search(
                BookSearchCriteria.of(null, null, false, BookSortField.TITLE, true, 0, 6));

        assertThat(firstPage.items()).hasSize(6);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(6);
        assertThat(firstPage.totalElements()).isEqualTo(15);
        assertThat(firstPage.totalPages()).isEqualTo(3);
    }

    @Test
    void findsDetailIncludingJournalOnlyFields() {
        assertThat(catalog.findById(BookId.of("11111111-1111-1111-1111-111111111112")))
                .get()
                .satisfies(detail -> {
                    assertThat(detail.kind()).isEqualTo(ContentKind.JOURNAL);
                    assertThat(detail.volume()).isEqualTo("68");
                    assertThat(detail.issue()).isEqualTo("9");
                    assertThat(detail.publisher()).isEqualTo("ACM");
                });
    }

    @Test
    void treatsAPercentInTheSearchTermAsALiteralCharacter() {
        assertThat(catalog.search(criteria("%", null, false)).totalElements())
                .as("an unescaped %% would match every row")
                .isZero();
    }

    @Test
    void treatsAnUnderscoreInTheSearchTermAsALiteralCharacter() {
        assertThat(catalog.search(criteria("_ava", null, false)).totalElements())
                .as("an unescaped _ is a single-character wildcard and would match 'Java'")
                .isZero();
    }

    @Test
    void treatsABackslashInTheSearchTermAsALiteralCharacter() {
        assertThat(catalog.search(criteria("\\", null, false)).totalElements())
                .as("the escape character itself must not corrupt the pattern")
                .isZero();
    }

    @Test
    void escapingAppliesToTheAuthorFilterToo() {
        assertThat(catalog.search(criteria(null, "%", false)).totalElements()).isZero();
    }

    @Test
    void stillMatchesOrdinaryTermsAfterEscaping() {
        assertThat(catalog.search(criteria("java", null, false)).items())
                .extracting(BookSummary::title)
                .containsExactlyInAnyOrder("Effective Java", "Java Concurrency in Practice");
    }

    @Test
    void detailIsEmptyForAnUnknownId() {
        assertThat(catalog.findById(BookId.newId())).isEmpty();
    }

    @Test
    void batchSummariesReturnOneRowPerKnownIdAndSkipUnknownOnes() {
        List<BookSummary> summaries = catalog.summariesFor(List.of(DDD, LEGACY_CODE, BookId.newId()));

        assertThat(summaries).extracting(BookSummary::id).containsExactlyInAnyOrder(DDD, LEGACY_CODE);
    }

    @Test
    void batchSummariesForAnEmptyRequestDoNotHitTheDatabase() {
        assertThat(catalog.summariesFor(List.of())).isEmpty();
    }
}
