package com.elibrary.lending.domain;

import com.elibrary.shared.error.ConflictException;

public class LoanAlreadyReturned extends ConflictException {

    public LoanAlreadyReturned(LoanId id) {
        super("LOAN_ALREADY_RETURNED", "Loan " + id + " has already been returned.");
    }
}
