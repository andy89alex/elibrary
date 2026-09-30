package com.elibrary.lending.application;

import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.shared.MemberId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class ReturnBook {

    private final LoanRepository loans;
    private final BookInventory inventory;
    private final Clock clock;

    public ReturnBook(LoanRepository loans, BookInventory inventory, Clock clock) {
        this.loans = loans;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional
    public Loan handle(MemberId memberId, LoanId loanId) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFound(loanId));
        if (!loan.belongsTo(memberId)) {
            throw new LoanNotFound(loanId);
        }
        Loan closed = loans.save(loan.returnNow(clock));
        inventory.restore(closed.bookId());
        return closed;
    }
}
