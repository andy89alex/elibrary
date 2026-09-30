package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.error.ConflictException;

public class AlreadyBorrowed extends ConflictException {

    public AlreadyBorrowed(MemberId memberId, BookId bookId) {
        super("ALREADY_BORROWED",
                "Member " + memberId + " already holds an active loan for book " + bookId + ".");
    }
}
