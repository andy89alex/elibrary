# E-Library Service — Design Spec

**Date:** 2026-09-30
**Context:** Backend take-home exercise, senior level. Budget 4–6 hours.
**Deliverables:** source code + README.

---

## 1. Goal and evaluation frame

Build a backend service for an e-library where members browse a catalogue, borrow
items, return them, and see what they currently hold.

The brief states the reviewers care more about thought process and trade-offs than
feature completeness. The spec therefore optimises for:

- clear module boundaries that are enforced, not merely described
- a domain model that owns real invariants
- a consistent, machine-readable error contract
- a README that argues for each decision

Scope creep is the main failure mode. Every omission below is deliberate and
documented.

## 2. Stack

| Concern | Choice |
|---|---|
| Language | Java 21 (`maven.compiler.release=21`) |
| Framework | Spring Boot 3.x |
| Build | Maven (single module) |
| Persistence | H2 in-memory + Spring Data JPA |
| Schema | Flyway, `ddl-auto: validate` |
| Auth | Spring Security HTTP Basic, in-memory users |
| Docs | springdoc-openapi (`/swagger-ui.html`) |
| Test | JUnit 5, AssertJ, MockMvc, ArchUnit |

Local toolchain is JDK 25; the build targets release 21 so a reviewer with JDK 21
can build. Docker is unavailable locally, which rules out Postgres + Testcontainers:
shipping tests the author has never run is not acceptable. H2 is chosen for reviewer
reproducibility, and Flyway keeps the schema explicit and portable to Postgres.

## 3. Architecture: deliberate asymmetry

Two modules carrying different architectural weight, on purpose.

- **`lending`** — genuine hexagonal. All business complexity lives here, so isolation
  pays for itself. Pure-Java domain, ports defined by the consumer, JPA adapter
  outside.
- **`catalog`** — read-mostly, so no layered aggregate or mapper stack. Spring Data
  projections feed response DTOs directly (CQRS-lite).
- **`shared`** — cross-module value objects and the pagination envelope.
- **`platform`** — security, error mapping, clock configuration.

Complexity is allocated in proportion to business risk. Applying full hexagonal
symmetry to a read-mostly catalogue would spend roughly 40% of the budget on
plumbing that protects invariants which do not exist.

### 3.1 Package layout

```
com.elibrary
├── ElibraryApplication.java
│
├── shared/
│   ├── BookId, MemberId, Isbn          # records, self-validating
│   └── PageResult<T>                   # our pagination envelope, not Spring's Page
│
├── catalog/
│   ├── web/      BookController, BookResponse, BookDetailResponse, BookSearchRequest
│   ├── query/    BookQueryService, BookSummaryProjection
│   ├── store/    BookRecord (@Entity, @Version), BookJpaRepository
│   └── spi/      CatalogBookInventory       # implements lending's port
│
├── lending/
│   ├── domain/       Loan, LoanId, ActiveLoans, LendingPolicy,
│   │                 LoanRepository (port), BookInventory (port),
│   │                 BookNotFound, BookUnavailable, LoanLimitReached,
│   │                 AlreadyBorrowed, LoanNotFound, LoanAlreadyReturned
│   ├── application/  BorrowBook, ReturnBook, ViewLoans
│   ├── web/          LoanController, BorrowRequest, LoanResponse
│   └── store/        LoanEntity, LoanJpaRepository, JpaLoanRepository, LoanMapper
│
└── platform/
    ├── security/ SecurityConfig, CurrentMember
    ├── web/      ApiExceptionHandler
    └── time/     ClockConfig
```

Boundaries are compiler-enforced: classes in `catalog.store`, `catalog.query`, and
`lending.store` are package-private. `catalog.spi` holds the only public class the
catalogue exposes to other modules. `lending` physically cannot reach
`BookJpaRepository`.

### 3.2 Copy availability ownership

Availability is `totalCopies − activeLoans`, a number sourced from two modules. Left
unresolved, the `?available=true` filter forces a cross-module join and the boundary
becomes fiction.

**Decision: `catalog` is the availability authority.** `BookRecord` stores
`totalCopies` and `availableCopies`. `lending` never computes availability; it calls
a port it defines itself:

```java
// lending/domain — port owned by the consumer
public interface BookInventory {
    void checkout(BookId id);   // throws BookNotFound, BookUnavailable
    void restore(BookId id);
}
```

`catalog.spi.CatalogBookInventory` implements it.

Consequences:

- `available=true` becomes `WHERE available_copies > 0` — single table, correct paging.
- `BorrowBook` is one local transaction: `checkout()` then `save(loan)`. In a
  single-database modular monolith a local transaction is the correct, simple answer.
  The coupling is explicit through a port rather than implicit through a neighbour's
  repository.
- The README states the limit: once `catalog` and `lending` become separate services
  this needs a saga with a compensating action, and `BookInventory` is exactly the seam.

### 3.3 Concurrency on the last copy

`checkout()` takes a **pessimistic write lock** on the book row
(`@Lock(LockModeType.PESSIMISTIC_WRITE)`). The transaction is short and contention is
on a single row — precisely where pessimistic locking beats optimistic. Users do not
receive spurious conflicts on retryable work.

`@Version` remains on `BookRecord` as a safety net against writes that bypass the
locked read path.

The trade-off (pessimistic vs. optimistic-plus-retry) is documented in the README.

## 4. Domain model

### 4.1 `Loan`

```java
public final class Loan {
    private final LoanId id;
    private final MemberId memberId;
    private final BookId bookId;
    private final Instant borrowedAt;
    private final LocalDate dueOn;
    private final Instant returnedAt;   // null ⇒ active

    static Loan open(MemberId m, BookId b, LendingPolicy p, Clock c);
    Loan returnNow(Clock c);            // throws LoanAlreadyReturned
    boolean isOverdue(Clock c);         // returnedAt == null && dueOn < today(c)
    boolean belongsTo(MemberId m);
    boolean isFor(BookId b);
}
```

Two modelling decisions:

1. **Immutable.** `returnNow()` returns a new `Loan`. No partially-mutated state; the
   aggregate is trivial to test.
2. **`OVERDUE` is a derived predicate, never stored state.** Overdue is a function of
   `dueOn` and the clock. No scheduled job is needed to flip statuses, no column can
   go stale, and the classic "the cron died overnight" bug cannot occur.

`Clock` is injected everywhere (bean `Clock.systemUTC()`, replaced by `Clock.fixed()`
in tests). No stray `LocalDate.now()` calls exist in the codebase.

### 4.2 Invariants and where each is enforced

| # | Invariant | Enforced in |
|---|---|---|
| 1 | Borrow only when a copy is available | `catalog` via `BookInventory.checkout` (under write lock) |
| 2 | Member may not exceed `maxConcurrentLoans` | `ActiveLoans.borrow` |
| 3 | Member may not hold two active loans of the same book | `ActiveLoans.borrow` |
| 4 | A loan has a due date; return closes it and restores the copy | `Loan.open` / `Loan.returnNow` + `BookInventory.restore` |

Invariants 2 and 3 live in a domain concept representing the member's lending
position, not in the application service:

```java
public final class ActiveLoans {
    private final MemberId memberId;
    private final List<Loan> loans;

    public Loan borrow(BookId bookId, LendingPolicy policy, Clock clock) {
        if (loans.size() >= policy.maxConcurrentLoans())   throw new LoanLimitReached(...);
        if (loans.stream().anyMatch(l -> l.isFor(bookId))) throw new AlreadyBorrowed(...);
        return Loan.open(memberId, bookId, policy, clock);
    }
}
```

### 4.3 `LendingPolicy`

```java
@ConfigurationProperties("elibrary.lending")
public record LendingPolicy(int maxConcurrentLoans, int loanPeriodDays) {
    public LocalDate dueDateFrom(LocalDate start) { return start.plusDays(loanPeriodDays); }
}
```

Defaults: `maxConcurrentLoans=5`, `loanPeriodDays=14`. Policy is separated from
mechanism and configurable without code change.

### 4.4 Application services

One class per use case, `@Transactional`, pure orchestration:

```java
// BorrowBook
ActiveLoans active = loans.activeFor(member);
Loan loan = active.borrow(book, policy, clock);   // invariants 2 & 3
inventory.checkout(book);                          // invariant 1, takes the lock
return loans.save(loan).id();
```

Ordering is deliberate: cheap checks first, lock-taking call last, minimising lock
hold time.

```java
// ReturnBook
Loan loan = loans.findById(loanId).orElseThrow(() -> new LoanNotFound(loanId));
if (!loan.belongsTo(member)) throw new LoanNotFound(loanId);   // 404, not 403
loans.save(loan.returnNow(clock));                              // invariant 4
inventory.restore(loan.bookId());
```

## 5. API

Base path `/api/v1`. All endpoints require HTTP Basic authentication.

| Method | Path | Result |
|---|---|---|
| GET | `/books?q=&author=&available=&page=&size=&sort=` | 200 `PageResult<BookSummary>` |
| GET | `/books/{bookId}` | 200 `BookDetail` |
| POST | `/loans` body `{"bookId":"…"}` | 201 + `Location: /api/v1/loans/{id}` |
| POST | `/loans/{loanId}/return` | 200 `LoanResponse` |
| GET | `/loans?status=active` | 200 `PageResult<LoanResponse>` |
| GET | `/loans/{loanId}` | 200 `LoanResponse` |

`GET /loans/{loanId}` exists so the `Location` header returned by `POST /loans` points
at a retrievable resource, as a 201 requires. It is scoped to the caller on the same
404-not-403 rule as return.

Browsing details:

- `q` matches title or author, case-insensitive `LIKE`. The README notes that
  production requires a full-text index.
- `available=true` filters at query level, never in memory after fetch, so paging
  stays correct.
- Pagination uses our own `PageResult` envelope (`items`, `page`, `size`,
  `totalElements`, `totalPages`). Spring's `Page<T>` is never serialised: its JSON
  shape is not stable across Spring versions and would bind the public contract to the
  framework.
- Offset pagination is used; cursor pagination is noted as the better choice for large
  datasets.
- `GET /loans` defaults to `status=active`, satisfying "viewing currently borrowed
  books". `status=all` and `status=returned` are accepted.
- Entities are never serialised to HTTP; response DTOs are mapped explicitly.

Design arguments recorded in the README:

- **`POST /loans` rather than `POST /books/{id}/borrow`.** The thing created is a
  `Loan`; borrowing *is* loan creation. This also gives the loan the identity needed
  to return it.
- **`/loans` carries no `{memberId}`.** Scope is always the caller, resolved from the
  principal. No other member's identifier appears in a URL.
- **`POST /loans/{id}/return` is a conscious compromise.** Strict REST would be
  `PATCH /loans/{id} {"status":"RETURNED"}`, which hands the state transition to the
  client and lets illegal transitions be expressed. A named action is more honest about
  the domain.
- **A loan belonging to another member returns 404, not 403**, so existence is not
  leaked.

## 6. Error handling

Domain exceptions know nothing about HTTP. A single `@RestControllerAdvice` in
`platform/web` maps them to RFC 9457 `ProblemDetail`.

| Exception | Status | `code` |
|---|---|---|
| `BookNotFound` | 404 | `BOOK_NOT_FOUND` |
| `LoanNotFound` | 404 | `LOAN_NOT_FOUND` |
| `BookUnavailable` | 409 | `NO_COPIES_AVAILABLE` |
| `LoanLimitReached` | 409 | `LOAN_LIMIT_REACHED` |
| `AlreadyBorrowed` | 409 | `ALREADY_BORROWED` |
| `LoanAlreadyReturned` | 409 | `LOAN_ALREADY_RETURNED` |
| bean validation failure | 400 | `VALIDATION_FAILED` (+ field errors) |
| unauthenticated | 401 | `UNAUTHENTICATED` |
| unhandled | 500 | `INTERNAL_ERROR` (details logged, not returned) |

All business refusals are 409. Clients must distinguish cases via the stable
machine-readable `code`, not the status code: status codes have too low a cardinality
to serve as an error contract, whereas `code` can grow without a breaking change.

```json
{ "type": "https://elibrary.example/problems/loan-limit-reached",
  "title": "Loan limit reached",
  "status": 409,
  "detail": "Member already has 5 active loans (limit 5).",
  "code": "LOAN_LIMIT_REACHED",
  "instance": "/api/v1/loans" }
```

## 7. Persistence and data

Flyway migrations:

- `V1__create_schema.sql` — tables `books`, `loans`; indexes on
  `loans(member_id, returned_at)` and `books(title)`.
- `V2__seed_catalog.sql` — roughly 15 books and journals, deliberately mixing
  available and fully-borrowed items so the `available` filter is demonstrable
  immediately.

`ddl-auto: validate`. The schema is a reviewed artefact, not a Hibernate side effect.

**No `members` table.** `MemberId` arrives from the principal and none of the four
invariants require member data. Member management is stated as a separate domain. This
is a scope decision recorded as an explicit assumption, and loans therefore carry no
foreign key to a member row.

Members are seeded in `SecurityConfig` via an in-memory `UserDetailsService`: three
members plus one librarian. Credentials are listed in the README. A `LIBRARIAN` role
exists but gates no endpoint in this scope.

## 8. Testing strategy

| Layer | Tooling | Subject |
|---|---|---|
| Domain unit | JUnit 5 + AssertJ, no Spring | `Loan`, `ActiveLoans`, `LendingPolicy`; `Clock.fixed()` makes due dates and overdue deterministic |
| Application | JUnit + hand-written fakes | orchestration of `BorrowBook` / `ReturnBook`, error propagation |
| Web | `@WebMvcTest` | request binding, response shape, exception-to-`ProblemDetail` mapping |
| Integration | `@SpringBootTest` + H2 + `MockMvc` | end-to-end happy path over HTTP plus principal error paths |
| Concurrency | `@SpringBootTest` + `ExecutorService` | N threads contend for the last copy: exactly one 201, the rest 409 |
| Architecture | ArchUnit | `lending.domain` must not import Spring or `jakarta.persistence`; `lending` must not import `catalog.store` |

Two deliberate choices:

- **Fakes, not Mockito, for repository ports.** `InMemoryLoanRepository` is about 25
  lines and yields tests that assert behaviour rather than interaction order. Mocked
  repositories couple tests to implementation detail and impede refactoring. Mockito is
  still used where the interaction itself is the subject.
- **ArchUnit makes the architectural claim verifiable.** Without it, "my domain is
  pure" is a README assertion that rots by the twentieth commit. This is the cheapest
  item in the project with the highest signal.

Domain and application layers are built test-first.

## 9. Out of scope, with reasons

| Omitted | Reason |
|---|---|
| Consuming content (reading, streaming, DRM) | Requires content storage, licensing, and signed delivery — far beyond the budget and orthogonal to the lending domain |
| Reservation / hold queue | Realistically 1.5–2 hours of state machine and expiry logic. README names the extension point: `LendingPolicy` plus a `BookReturned` domain event |
| JWT / OIDC issuance | Not requested; HTTP Basic proves the boundary design at a fraction of the cost |
| Member management | Separate domain; no invariant here needs it |
| Full-text search | `LIKE` suffices at this scale; production needs a proper index |
| Cursor pagination | Offset is correct for a seeded catalogue; the trade-off is documented |
| Idempotency keys | Invariant 3 already makes repeat borrows of the same book safe |
| Fines and overdue penalties | Policy-heavy, adds no architectural signal |

## 10. README outline

1. Quick start — two commands, JDK 21 the only prerequisite
2. Endpoint table with copy-pasteable `curl` examples
3. Architecture — ASCII diagram and *why the asymmetry* (the most important section)
4. Domain model and the four invariants, with enforcement points
5. Decisions and trade-offs — catalogue as availability authority · pessimistic vs
   optimistic locking · overdue as a derived value · 409 plus `code` · H2 and Flyway as
   the path to Postgres · no members table · `POST /loans` versus a borrow action
6. Out of scope and why (section 9)
7. Testing strategy and how to run it
8. If this went to production — Postgres with Testcontainers, an outbox for events,
   observability, rate limiting, what breaks when the modules split into services
9. Credentials for the seeded users

springdoc-openapi serves `/swagger-ui.html` so a reviewer can explore before reading
code.

Git history is incremental and meaningful (`feat(lending): enforce concurrent loan
limit`), never a single large initial commit; senior reviewers read history as evidence
of working method.

## 11. Time budget (~5 hours)

| Phase | Estimate |
|---|---|
| Scaffold, Flyway, security, error kernel | 45m |
| `lending` domain plus unit tests (TDD) | 75m |
| Application services, ports, JPA adapter | 60m |
| Catalogue query and web layer | 60m |
| Integration, concurrency, and ArchUnit tests | 45m |
| README, OpenAPI, history tidy-up | 45m |

## 12. Definition of done

- All five required capabilities reachable over HTTP and covered by integration tests
- Four invariants enforced in the domain and covered by unit tests
- Concurrency test proves exactly one winner for the last copy
- ArchUnit tests pass, proving domain purity and module isolation
- `mvn clean verify` green on a clean checkout with JDK 21
- README complete per section 10, every trade-off argued
- Git history incremental and readable
