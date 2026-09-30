package com.elibrary.lending.web;

import jakarta.validation.constraints.NotBlank;

/** No member id: the borrower is always the authenticated caller. */
record BorrowRequest(@NotBlank(message = "bookId is required") String bookId) {
}
