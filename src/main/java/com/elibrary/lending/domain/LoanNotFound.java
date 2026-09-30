package com.elibrary.lending.domain;

import com.elibrary.shared.error.NotFoundException;

/**
 * Also thrown when a loan exists but belongs to another member: answering 404 rather than
 * 403 avoids leaking the existence of other members' loans.
 */
public class LoanNotFound extends NotFoundException {

    public LoanNotFound(LoanId id) {
        super("LOAN_NOT_FOUND", "No loan with id " + id + " exists for this member.");
    }
}
