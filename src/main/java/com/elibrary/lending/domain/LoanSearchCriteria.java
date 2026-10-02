package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

import java.util.Objects;
import java.util.Optional;

/**
 * A librarian's view over every member's loans. Each filter is optional and narrows the
 * result; omitting all of them returns the whole ledger.
 *
 * <p>Deliberately separate from the member-facing read path: {@link LoanRepository#findFor}
 * takes a {@link MemberId} it cannot be called without, which is what guarantees a member
 * can only ever see their own loans. Widening that method with an optional member would
 * have turned a compiler guarantee into a runtime one.
 */
public record LoanSearchCriteria(
        MemberId memberId,
        BookId bookId,
        LoanStatusFilter status,
        boolean overdueOnly,
        int page,
        int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public LoanSearchCriteria {
        Objects.requireNonNull(status, "status");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, was " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE + ", was " + size);
        }
    }

    /** Applies the paging defaults, so callers may pass {@code null} for "unspecified". */
    public static LoanSearchCriteria of(MemberId memberId, BookId bookId, LoanStatusFilter status,
                                        boolean overdueOnly, Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = Math.min(size == null || size < 1 ? DEFAULT_SIZE : size, MAX_SIZE);
        return new LoanSearchCriteria(
                memberId, bookId, status == null ? LoanStatusFilter.ACTIVE : status,
                overdueOnly, safePage, safeSize);
    }

    public Optional<MemberId> member() {
        return Optional.ofNullable(memberId);
    }

    public Optional<BookId> book() {
        return Optional.ofNullable(bookId);
    }
}
