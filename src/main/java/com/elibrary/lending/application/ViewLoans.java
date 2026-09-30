package com.elibrary.lending.application;

import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ViewLoans {

    private final LoanRepository loans;

    public ViewLoans(LoanRepository loans) {
        this.loans = loans;
    }

    @Transactional(readOnly = true)
    public PageResult<Loan> handle(MemberId memberId, LoanStatusFilter filter, int page, int size) {
        return loans.findFor(memberId, filter, page, size);
    }

    @Transactional(readOnly = true)
    public Loan byId(MemberId memberId, LoanId loanId) {
        return loans.findById(loanId)
                .filter(loan -> loan.belongsTo(memberId))
                .orElseThrow(() -> new LoanNotFound(loanId));
    }
}
