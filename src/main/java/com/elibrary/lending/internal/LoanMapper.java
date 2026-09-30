package com.elibrary.lending.internal;

import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

final class LoanMapper {

    private LoanMapper() {
    }

    static LoanEntity toEntity(Loan loan) {
        return new LoanEntity(
                loan.id().value(),
                loan.memberId().value(),
                loan.bookId().value(),
                loan.borrowedAt(),
                loan.dueOn(),
                loan.returnedAt());
    }

    static Loan toDomain(LoanEntity entity) {
        return Loan.reconstitute(
                new LoanId(entity.id()),
                new MemberId(entity.memberId()),
                new BookId(entity.bookId()),
                entity.borrowedAt(),
                entity.dueOn(),
                entity.returnedAt());
    }
}
