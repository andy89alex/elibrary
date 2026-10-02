package com.elibrary.lending.internal;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanSearchCriteria;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaLoanRepository.class)
class JpaLoanRepositoryTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final BookId EFFECTIVE_JAVA = BookId.of("11111111-1111-1111-1111-111111111107");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    private static Clock at(String isoDate) {
        return Clock.fixed(Instant.parse(isoDate + "T10:00:00Z"), ZoneOffset.UTC);
    }

    @Autowired
    private LoanRepository loans;

    @Test
    void savesAndReloadsEveryField() {
        Loan saved = loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));

        assertThat(loans.findById(saved.id())).get().satisfies(reloaded -> {
            assertThat(reloaded.id()).isEqualTo(saved.id());
            assertThat(reloaded.memberId()).isEqualTo(ALICE);
            assertThat(reloaded.bookId()).isEqualTo(DDD);
            assertThat(reloaded.borrowedAt()).isEqualTo(Instant.parse("2026-09-30T10:00:00Z"));
            assertThat(reloaded.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(reloaded.returnedAt()).isNull();
            assertThat(reloaded.isActive()).isTrue();
        });
    }

    @Test
    void savingAReturnedLoanUpdatesTheExistingRowRatherThanInsertingAnother() {
        Loan open = loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));

        loans.save(open.returnNow(at("2026-10-02")));

        assertThat(loans.findById(open.id())).get().satisfies(reloaded -> {
            assertThat(reloaded.isActive()).isFalse();
            assertThat(reloaded.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        });
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void findByIdIsEmptyForAnUnknownLoan() {
        assertThat(loans.findById(LoanId.newId())).isEmpty();
    }

    @Test
    void activeForReturnsOnlyTheMembersOpenLoans() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));
        Loan toReturn = loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-30")));
        loans.save(toReturn.returnNow(at("2026-10-01")));
        loans.save(Loan.open(BOB, EFFECTIVE_JAVA, POLICY, at("2026-09-30")));

        assertThat(loans.activeFor(ALICE).count()).isEqualTo(1);
        assertThat(loans.activeFor(ALICE).all()).allMatch(loan -> loan.isFor(DDD));
        assertThat(loans.activeFor(BOB).count()).isEqualTo(1);
    }

    @Test
    void activeForAMemberWithNoLoansIsAnEmptyPosition() {
        assertThat(loans.activeFor(ALICE).count()).isZero();
        assertThat(loans.activeFor(ALICE).memberId()).isEqualTo(ALICE);
    }

    @Test
    void findForHonoursTheStatusFilter() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));
        Loan returned = loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-28")));
        loans.save(returned.returnNow(at("2026-09-29")));

        assertThat(loans.findFor(ALICE, LoanStatusFilter.ACTIVE, 0, 20).totalElements()).isEqualTo(1);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.RETURNED, 0, 20).totalElements()).isEqualTo(1);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).totalElements()).isEqualTo(2);

        assertThat(loans.findFor(ALICE, LoanStatusFilter.ACTIVE, 0, 20).items())
                .extracting(Loan::bookId)
                .as("ACTIVE must select the open loan, not the returned one")
                .containsExactly(DDD);

        assertThat(loans.findFor(ALICE, LoanStatusFilter.RETURNED, 0, 20).items())
                .extracting(Loan::bookId)
                .as("RETURNED must select the closed loan, not the open one")
                .containsExactly(REFACTORING);
    }

    @Test
    void findForNeverLeaksAnotherMembersLoans() {
        loans.save(Loan.open(BOB, DDD, POLICY, at("2026-09-30")));

        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).items()).isEmpty();
    }

    @Test
    void findForReturnsMostRecentlyBorrowedFirstAndPagesCorrectly() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-28")));
        loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-29")));
        loans.save(Loan.open(ALICE, EFFECTIVE_JAVA, POLICY, at("2026-09-30")));

        PageResult<Loan> firstPage = loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 2);

        assertThat(firstPage.items()).extracting(Loan::bookId).containsExactly(EFFECTIVE_JAVA, REFACTORING);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 1, 2).items())
                .extracting(Loan::bookId).containsExactly(DDD);
    }

    // --- librarian search ---------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    /** alice: DDD active+overdue, Refactoring returned. bob: DDD active, not yet due. */
    private void seedLedger() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-10-01")));          // due 2026-10-15
        loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-10-02")).returnNow(at("2026-10-03")));
        loans.save(Loan.open(BOB, DDD, POLICY, at("2026-10-18")));            // due 2026-11-01
    }

    private PageResult<Loan> search(MemberId member, BookId book, LoanStatusFilter status, boolean overdueOnly) {
        return loans.search(LoanSearchCriteria.of(member, book, status, overdueOnly, 0, 50), TODAY);
    }

    @Test
    void searchWithNoFiltersReturnsEveryMembersLoans() {
        seedLedger();

        assertThat(search(null, null, LoanStatusFilter.ALL, false).items())
                .extracting(Loan::memberId)
                .containsExactlyInAnyOrder(ALICE, ALICE, BOB);
    }

    @Test
    void searchNarrowsByMember() {
        seedLedger();

        assertThat(search(ALICE, null, LoanStatusFilter.ALL, false).items())
                .extracting(Loan::memberId).containsOnly(ALICE)
                .hasSize(2);
    }

    @Test
    void searchNarrowsByBookAcrossMembers() {
        seedLedger();

        assertThat(search(null, DDD, LoanStatusFilter.ALL, false).items())
                .extracting(Loan::memberId)
                .containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void searchNarrowsByStatus() {
        seedLedger();

        assertThat(search(null, null, LoanStatusFilter.ACTIVE, false).items())
                .allMatch(Loan::isActive)
                .hasSize(2);
        assertThat(search(null, null, LoanStatusFilter.RETURNED, false).items())
                .extracting(Loan::bookId).containsExactly(REFACTORING);
    }

    @Test
    void searchNarrowsToOverdueLoansOnly() {
        seedLedger();

        assertThat(search(null, null, LoanStatusFilter.ALL, true).items())
                .as("alice's DDD loan was due 2026-10-15; bob's is due 2026-11-01")
                .extracting(Loan::memberId).containsExactly(ALICE);
    }

    @Test
    void overdueNeverIncludesAReturnedLoanEvenPastItsDueDate() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-10-01")).returnNow(at("2026-10-02")));

        assertThat(search(null, null, LoanStatusFilter.ALL, true).items()).isEmpty();
    }

    @Test
    void searchCombinesFiltersWithAnd() {
        seedLedger();

        assertThat(search(BOB, DDD, LoanStatusFilter.ACTIVE, true).items())
                .as("bob holds DDD and it is active, but it is not overdue")
                .isEmpty();
        assertThat(search(ALICE, DDD, LoanStatusFilter.ACTIVE, true).items())
                .extracting(Loan::memberId).containsExactly(ALICE);
    }

    @Test
    void searchPagesNewestFirst() {
        seedLedger();

        PageResult<Loan> firstPage = loans.search(
                LoanSearchCriteria.of(null, null, LoanStatusFilter.ALL, false, 0, 2), TODAY);

        assertThat(firstPage.items()).extracting(Loan::memberId).containsExactly(BOB, ALICE);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
    }
}
