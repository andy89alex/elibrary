package com.elibrary.lending.domain;

import com.elibrary.shared.MemberId;
import com.elibrary.shared.error.ConflictException;

public class LoanLimitReached extends ConflictException {

    public LoanLimitReached(MemberId memberId, int held, int limit) {
        super("LOAN_LIMIT_REACHED",
                "Member " + memberId + " already has " + held + " active loans (limit " + limit + ").");
    }
}
