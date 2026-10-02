-- Invariant 3 ("a member may not hold two active loans of the same book") as a database
-- constraint, not only a domain check.
--
-- BorrowBook reads the member's position without a lock, so two concurrent requests can
-- both pass ActiveLoans.borrow and both reach the insert. No change to the aggregate can
-- close that window; only the database can.
--
-- PostgreSQL would express this as a partial unique index:
--     create unique index ... on loans (member_id, book_id) where returned_at is null
-- H2 does not support those. active_book_id gets the same effect with no database-specific
-- feature: it carries book_id while the loan is open and NULL once it is returned, and
-- because SQL never treats one NULL as equal to another, returned rows drop out of the
-- constraint entirely. A member may therefore borrow the same book again after returning
-- it, while holding it twice at once is impossible.
--
-- The column duplicates what book_id and returned_at already say. LoanMapper is the only
-- writer, and DoubleSubmitConcurrencyIT covers both halves of the lifecycle.

alter table loans add column active_book_id uuid;

update loans set active_book_id = book_id where returned_at is null;

alter table loans
    add constraint uq_loans_member_active_book unique (member_id, active_book_id);
