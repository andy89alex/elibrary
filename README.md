# E-Library Service

A backend service for a digital library: browse a catalogue of books and journals, borrow
items, return them, and see what you currently hold.

The brief said it cared more about thought process and trade-offs than feature completeness,
so this README argues the decisions rather than just listing them. Where a decision has a
cost, the cost is stated.

## Quick start

Requires **JDK 21 or newer** and nothing else — no database, no Docker.

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080/swagger-ui.html> (it 302-redirects to the Swagger UI index).

```bash
mvn clean verify     # full build: 124 unit tests + 6 integration tests
mvn test             # unit tests only (~11s)
```

If port 8080 is taken:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9123
```

The database is in-memory H2, migrated and seeded by Flyway on startup with 15 items
(11 books, 4 journals). Two of them are seeded with zero available copies and one with
exactly one, so the refusal paths are reachable without setup.

### A note on the build

`maven.compiler.release` is **21**. Surefire and Failsafe both set
`net.bytebuddy.experimental=true`: Byte Buddy cannot read class files from JDKs newer than it
natively supports, so without the flag Mockito fails to mock concrete classes and the suite
breaks on JDK 25. It is Mockito's documented escape hatch and becomes a no-op once Byte Buddy
catches up. With it, the suite was verified green on Temurin 25. It should be green on 21
too — that's the compile target — but only 25 was available here, so treat 21 as expected,
not observed.

### Credentials

HTTP Basic. Password for every account is `password`.

| Username | Role |
|---|---|
| `alice`, `bob`, `carol` | `MEMBER` |
| `librarian` | `LIBRARIAN` |

## API

Base path `/api/v1`. All endpoints require authentication.

| Method | Path | Purpose |
|---|---|---|
| GET | `/books` | Browse: search, filter, sort, page |
| GET | `/books/{bookId}` | Item detail |
| POST | `/loans` | Borrow — creates a loan |
| GET | `/loans` | Your loans; defaults to currently borrowed |
| GET | `/loans/{loanId}` | One of your loans |
| POST | `/loans/{loanId}/return` | Return |

### Query parameters

For `/books`:

| Parameter | Behaviour |
|---|---|
| `q` | Case-insensitive substring match on title **or** author |
| `author` | Case-insensitive substring match on author only |
| `available` | `true` restricts to items with at least one copy free |
| `sort` | `field,direction`. `field` is whitelisted to `title\|author\|publicationYear`; anything else is a **400**. A missing or empty direction (`sort=title`, `sort=title,`) means ascending |
| `page`, `size` | Offset paging. `size` defaults to 20, clamped to 100 |

For `/loans`: `status` (`active` \| `returned` \| `all`, default `active`), `page`, `size`.

### Examples

Each of these was run against a live server while writing this file.

```bash
# Browse: search, available only, newest first
curl -u alice:password \
  'http://localhost:8080/api/v1/books?q=domain&available=true&sort=publicationYear,desc&page=0&size=5'
```

```json
{"items":[
  {"id":"11111111-1111-1111-1111-111111111102","title":"Implementing Domain-Driven Design",
   "author":"Vaughn Vernon","kind":"BOOK","totalCopies":3,"availableCopies":3,"available":true},
  {"id":"11111111-1111-1111-1111-111111111101","title":"Domain-Driven Design",
   "author":"Eric Evans","kind":"BOOK","totalCopies":4,"availableCopies":4,"available":true}],
 "page":0,"size":5,"totalElements":2,"totalPages":1}
```

```bash
# Borrow — 201 with Location: /api/v1/loans/{id}
curl -u alice:password -X POST http://localhost:8080/api/v1/loans \
  -H 'Content-Type: application/json' \
  -d '{"bookId":"11111111-1111-1111-1111-111111111101"}'
```

```json
{"id":"cad7b5a7-01fd-4e18-beaf-8887420aa9e4",
 "book":{"id":"11111111-1111-1111-1111-111111111101","title":"Domain-Driven Design",
         "author":"Eric Evans","kind":"BOOK"},
 "borrowedAt":"2026-09-30T17:39:00.743732Z","dueOn":"2026-10-14",
 "returnedAt":null,"status":"ACTIVE","overdue":false}
```

```bash
# Currently borrowed (status=all also shows returned loans)
curl -u alice:password http://localhost:8080/api/v1/loans

# Return
curl -u alice:password -X POST \
  http://localhost:8080/api/v1/loans/cad7b5a7-01fd-4e18-beaf-8887420aa9e4/return

# A refusal: book ...110 is seeded with zero available copies
curl -u alice:password -X POST http://localhost:8080/api/v1/loans \
  -H 'Content-Type: application/json' \
  -d '{"bookId":"11111111-1111-1111-1111-111111111110"}'
```

```json
{"type":"https://elibrary.example/problems/no-copies-available",
 "title":"No copies available","status":409,
 "detail":"Book 11111111-1111-1111-1111-111111111110 has no copies available.",
 "instance":"/api/v1/loans","code":"NO_COPIES_AVAILABLE"}
```

## Architecture

```
                 ┌───────────────────────────────────────────┐
   HTTP  ───────▶│  catalog.web          lending.web         │
                 ├───────────────────────────────────────────┤
                 │                       lending.application │
                 │  catalog (public      ┌──────────────────┐│
                 │  read API)            │ lending.domain   ││
                 │      ▲                │  Loan            ││
                 │      │                │  ActiveLoans     ││
                 │  catalog.internal     │  LendingPolicy   ││
                 │   BookRecord          │  ports:          ││
                 │   JpaBookCatalog      │   LoanRepository ││
                 │   BookJpaRepository   │                  ││
                 │   CatalogBook─────────┼─▶ BookInventory  ││
                 │   Inventory           └──────────────────┘│
                 │                       lending.internal    │
                 ├───────────────────────────────────────────┤
                 │  platform (security, error mapping, clock)│
                 │  shared (value objects, PageResult)       │
                 └───────────────────────────────────────────┘
```

Two modules, `catalog` and `lending`, over a `shared` kernel of value objects and a
`platform` package holding the framework wiring nobody else should see.

### Why internals collapse into one `internal` package per module

An earlier sketch split each module's internals into `query`, `store`, and `spi`. That cannot
work, because **Java's package-private visibility is per-package**: three sibling packages can
only see each other's types if those types are `public`, at which point the whole codebase can
see them too and the boundary exists only in documentation. So each module has exactly one
non-public package — `catalog.internal`, `lending.internal` — and the classes in it are
package-private. `lending` cannot import `BookJpaRepository` because the compiler refuses,
not because a convention asks it not to.

The cost is that `internal` is a grab-bag: entity, Spring Data interface, mapper, and adapter
all live together with no further sub-structure. At this size that is a fair trade; a larger
module would want to become its own Maven module (or a JPMS module) to get finer-grained
internals back without giving up enforcement.

### Why the two modules are not symmetric

This is the central decision, and it is deliberate.

**`lending` is genuine hexagonal.** All of the business complexity lives here: four
invariants, a state transition, a policy. The `Loan` aggregate is immutable plain Java with
no framework annotations; it defines its own ports (`LoanRepository`, `BookInventory`) and
the JPA adapter sits outside in `lending.internal`. Isolation costs a separate entity and an
explicit mapper, and here that cost buys something real — the rules are unit-testable in
milliseconds with no container and no database.

**`catalog` is CQRS-lite.** It is read-mostly and owns exactly one piece of mutable state
(copy counts). Giving it a matching aggregate, repository port, and mapper stack would spend
a large share of the budget on plumbing that protects invariants which do not exist. Instead
it exposes a small public read API (`BookCatalog` plus three records) over a package-private
JPA internal, and `BookSummary`/`BookDetail` double as the read models — no separate DTO
layer between the query and the wire.

Complexity is allocated in proportion to business risk. A uniform application of either
pattern would have been the easier answer to defend in the abstract and the worse answer
here. The honest risk: if the catalogue ever grows real write behaviour (acquisitions,
withdrawals, per-copy condition), it will need the structure that `lending` already has, and
retrofitting it is more work than having built it.

### Enforced, not asserted

`src/test/java/com/elibrary/architecture/ArchitectureTest.java` holds **10 ArchUnit rules**
that fail the build on drift. They enforce:

- `lending.domain` depends on no Spring, no `jakarta.persistence`, and nothing in `catalog`
- `lending.domain` does not depend on `lending.application`, `lending.internal`, or
  `lending.web` — dependencies point inwards
- nothing in `lending` reaches into `catalog.internal`
- nothing in `catalog` reaches into `lending.internal`, `.application`, or `.web` (it may
  implement the domain's port, and only that)
- `shared` depends on no module
- `@Entity` classes reside only inside `catalog.internal` / `lending.internal`
- Spring's `Page` never reaches a `..web..` package — `PageResult` is the public contract
- nobody calls `LocalDate.now()`, `Instant.now()`, or `LocalDateTime.now()` directly

That last rule has a real limit worth naming: it matches the no-arg `now()` on those three
types only. `ZonedDateTime.now()` or `System.currentTimeMillis()` would slip through. It
catches the mistake actually likely to be made in this codebase, not every possible one.

Every architectural claim above has a test behind it, because a claim without one becomes
false around the twentieth commit.

## Domain model and invariants

| # | Invariant | Enforced in |
|---|---|---|
| 1 | Borrow only when a copy is available | `catalog` — `BookRecord.checkoutCopy` under a write lock, plus the `chk_books_available_copies` database constraint |
| 2 | A member may not exceed `maxConcurrentLoans` | `ActiveLoans.borrow` |
| 3 | A member may not hold two active loans of the same book | `ActiveLoans.borrow` |
| 4 | A loan has a due date; returning closes it and restores the copy | `Loan.open` / `Loan.returnNow` + `BookInventory.restore` |

Invariants 2 and 3 live in `ActiveLoans`, a domain type representing the member's lending
position, rather than in the application service. `ActiveLoans.borrow` is the only production
caller of `Loan.open`, so an "anaemic service" refactor cannot quietly drop the rules and
they stay unit-testable without Spring or a database. To be precise about the strength of
that: `Loan.open` is `public` — tests call it directly to build fixtures — so the funnel is a
convention with a single enforcement point, not a compiler guarantee. Making it
package-private would cost the fixture ergonomics and buy little, since the only production
path already goes through `ActiveLoans`.

The application services contain no business logic at all:

```java
ActiveLoans position = loans.activeFor(memberId);
Loan loan = position.borrow(bookId, policy, clock);   // invariants 2 and 3
inventory.checkout(bookId);                           // invariant 1, takes the lock
return loans.save(loan);
```

The ordering there is deliberate: the member-side checks are cheap and take no lock, so they
run first, and the lock on the book row is held for the shortest possible window.

The policy numbers are configuration, not code:

```yaml
elibrary:
  lending:
    max-concurrent-loans: 5
    loan-period-days: 14
```

`LendingPolicy` itself is a plain record with no Spring annotations — `@ConfigurationProperties`
would have violated the domain-purity rule above, so binding lives in `platform.config` and
the policy is injected as a bean.

## Decisions and trade-offs

**The catalogue owns copy availability.** Availability is `totalCopies − activeLoans`, a
figure sourced from two modules. Left unresolved, the `?available=true` filter forces a
cross-module join and the boundary is fiction. So `catalog` keeps a running
`available_copies` column and `lending` never computes it — it calls `BookInventory`, a port
that `lending` itself defines and `catalog` implements. The browse filter stays a
single-table query, so the paging window and totals are correct. The honest limit: once these
become separate services, the two-step borrow needs a saga with a compensating action.
`BookInventory` is exactly that seam, and `CatalogBookInventory` uses
`Propagation.MANDATORY` rather than `REQUIRED` so that "this must run inside the caller's
transaction" fails loudly instead of silently committing half a borrow.

**Three layers protect the copy invariant; the pessimistic lock is not the one that makes
overselling impossible.** The layers are:

1. the `chk_books_available_copies` check constraint in the schema;
2. `@Version` optimistic locking on `BookRecord`;
3. the pessimistic write lock taken by `BookJpaRepository.findByIdForUpdate`.

Layers 1 and 2 are already sufficient to prevent a copy being lent twice. What layer 3 buys
is **clean queuing**. Without it, contenders would race, lose the version check, and surface
`ObjectOptimisticLockingFailureException` to the caller — a legitimate borrow failing on an
infrastructure conflict, which forces retry logic into the application. With it, contenders
wait a few milliseconds on a single row inside a very short transaction and then either
succeed or receive an honest `NO_COPIES_AVAILABLE` refusal. On that shape of contention —
one row, brief transaction — waiting beats failing-and-retrying. Optimistic locking plus
retry is the better choice when contention is rare and transactions are long, which is the
opposite of this case.

`LastCopyConcurrencyIT` contends eight distinct members on the single remaining copy of a
seeded book and asserts exactly one winner, zero remaining copies, and — the assertion that
actually carries the argument — an empty list of unexpected throwables. Remove the
pessimistic lock and that test still fails, but by way of
`ObjectOptimisticLockingFailureException` appearing among the unexpected throwables, not by
way of multiple successful loans. That distinction is the point: the test verifies the
queuing behaviour, and the safety net underneath it is a separate, independently sufficient
mechanism.

**Overdue is derived, never stored.** `Loan.isOverdue(clock)` is a pure function of `dueOn`
and the injected clock. There is no `status` column and no scheduled job flipping rows, so
nothing can go stale and the "the cron died overnight" class of bug cannot occur. The cost is
that overdue is not directly queryable; a reporting need would add a view or a derived
column, and that is a better problem than a synchronisation bug.

**All business refusals are 409, distinguished by `code`.** Status codes have far too low a
cardinality to act as an error contract. Clients branch on a stable machine-readable `code`
(`NO_COPIES_AVAILABLE`, `LOAN_LIMIT_REACHED`, `ALREADY_BORROWED`, `LOAN_ALREADY_RETURNED`),
which can grow without a breaking change. Responses are RFC 9457 `ProblemDetail`:

```json
{ "type": "https://elibrary.example/problems/already-borrowed",
  "title": "Already borrowed",
  "status": 409,
  "detail": "Member alice already holds an active loan for book 11111111-...-111111111101.",
  "instance": "/api/v1/loans",
  "code": "ALREADY_BORROWED" }
```

The domain exposes exactly two abstract exception bases (`NotFoundException`,
`ConflictException`), so the HTTP mapping is two handler methods and a new domain error ships
without touching the web layer. Unexpected failures are logged with the request line and
answered with a fixed message — exception text routinely carries connection strings or user
data.

**`BookNotFound` and `BookUnavailable` deliberately live in different packages.**
`BookNotFound` is in `com.elibrary.shared.error`; `BookUnavailable` is in
`com.elibrary.lending.domain`. The asymmetry is the point. "No such book" is shared
vocabulary — browsing raises it for an unknown id, and borrowing raises it too — so it
belongs in the shared kernel. "No copies available" is purely a lending concept; browsing
never raises it. The catalogue raises it only because it *implements* lending's
`BookInventory` port, and an adapter depending on its port's package — exceptions included —
is the correct direction of dependency. Put `BookUnavailable` in `shared` and the shared
kernel starts accumulating one module's vocabulary.

**Someone else's loan returns 404, not 403.** 403 confirms the loan exists. Applied
consistently to `GET /loans/{id}` and `POST /loans/{id}/return`.

**`POST /loans`, not `POST /books/{id}/borrow`.** The thing created is a `Loan`; borrowing
*is* loan creation. That is also what gives the loan the identity needed to return it, and
the 201 carries a `Location` pointing at a genuinely retrievable resource — `GET
/loans/{loanId}` exists for exactly that reason.

**`POST /loans/{id}/return` is a conscious departure from strict REST.** The pure form,
`PATCH /loans/{id}` with `{"status":"RETURNED"}`, hands the state transition to the client
and makes illegal transitions expressible in a request body. A named action is more honest
about the domain.

**No member id in any URL.** Identity comes from the authenticated principal, translated into
a `MemberId` by an argument resolver at the boundary. Nothing behind the controller knows
Spring Security exists, and no member's data can be requested by guessing a URL.

**H2 with Flyway, not Postgres with Testcontainers.** Docker was unavailable in the
environment this was built in, and shipping tests the author has never executed would be
worse than shipping none. H2 runs in PostgreSQL compatibility mode and the schema is
Flyway-managed with `ddl-auto: validate`, so it is a reviewed artefact rather than a
Hibernate side effect and the move to Postgres is a datasource change. The gap this leaves is
real and named below: one invariant that would be a database constraint on Postgres is not
one here.

**No `members` table.** `MemberId` comes from the principal, and none of the four invariants
need member data. Member management is a different domain. This is a scope decision stated as
an explicit assumption, not an oversight — and it is the decision in this list I would most
expect to be challenged.

**Sortable fields are whitelisted by an enum.** Passing a client string into Spring's `Sort`
lets a caller order by any persistent property, which leaks the persistence model and can be
used to probe it. An unrecognised field is a 400 rather than a silent fallback: a client that
asked for an ordering and quietly did not get it will build wrong behaviour on the assumption
that it did.

**Entities are mapped in Java, not projected in JPQL.** A constructor expression cannot build
`BookId`/`Isbn` value objects, and at this row width the saving is negligible. On a wide table
a dedicated projection would earn its keep.

## Testing

```bash
mvn test        # 124 unit tests: domain, application, web, persistence, architecture
mvn verify      # the above plus 6 integration tests
```

The 6 integration tests are `LendingFlowIT` (5) and `LastCopyConcurrencyIT` (1).

| Layer | Tooling | Subject |
|---|---|---|
| Domain | JUnit 5 + AssertJ, no Spring | `Loan`, `ActiveLoans`, `LendingPolicy` with `Clock.fixed` |
| Application | JUnit + hand-written fakes | orchestration and error propagation |
| Web | standalone `MockMvc` | binding, response shape, error mapping |
| Persistence | `@DataJpaTest` | queries, paging, copy guards |
| End-to-end | `@SpringBootTest` + `MockMvc` | the whole flow over HTTP with real auth |
| Concurrency | `@SpringBootTest` + `ExecutorService` | eight members, one copy, exactly one winner |
| Architecture | ArchUnit | domain purity and module isolation |

Repository ports are backed by **fakes, not mocks**. `InMemoryLoanRepository` is short enough
to read in one go and produces tests that assert behaviour rather than interaction order;
mocked repositories couple tests to implementation detail and resist refactoring. Mockito is
still used where the interaction itself is the subject, such as verifying that enriching a
page of loans issues one catalogue lookup rather than one per row.

## Out of scope, and why

| Omitted | Reason |
|---|---|
| Consuming content (reading, streaming, DRM) | Needs content storage, licensing, and signed delivery. Orthogonal to lending and far beyond the budget |
| Reservation / hold queue | Realistically 1.5–2 hours of state machine and expiry logic. The extension point is `LendingPolicy` plus a `BookReturned` domain event raised by `ReturnBook` |
| JWT / OIDC | Not requested. HTTP Basic proves the boundary design at a fraction of the cost, and an OIDC swap touches only `platform.security` |
| Member management | A separate domain; no invariant here needs it |
| Full-text search | `LIKE` is honest at this scale. Production needs a real index — the query is isolated in one repository method |
| Cursor pagination | Offset is correct for a 15-row seeded catalogue. Cursor paging is the right answer once the table is large and writes are frequent |
| Idempotency keys | Invariant 3 already makes a repeated borrow of the same book safe, so the common double-submit is handled |
| Fines and overdue penalties | Policy-heavy and adds no architectural signal |
| Librarian endpoints | The `LIBRARIAN` role is seeded and the filter chain is in place, but no endpoint uses it. Acquisitions and withdrawals are catalogue *writes*, which is the one thing the CQRS-lite catalogue is not built for — see the risk noted above |

## If this went to production

Known gaps in what is here, first — these are small, but they are real:

- **`LIKE` wildcards are not escaped.** `q` and `author` are lowercased and wrapped in `%`,
  but `%` and `_` in the user's own input are passed through. `?q=%` therefore matches every
  row (verified: it returns all 15). Harmless here, wrong at scale, and fixed by escaping the
  two metacharacters and declaring an `escape` clause.
- **`BookSearchCriteria`'s canonical constructor bypasses its own defaulting and clamping.**
  The `of(...)` factory does the work — null-blanking, page floor, `size` clamp to 100 — but
  the record's generated constructor is public and does none of it. Nothing calls it raw
  today, so the clamp holds in practice; it is just not enforced at every entry point. A
  compact constructor doing the validation, with `of(...)` reduced to defaulting, would close
  it.
- **Invariant 3 is not a database constraint.** "No two active loans of the same book per
  member" is enforced in the domain only. On PostgreSQL it would be
  `create unique index ... on loans (member_id, book_id) where returned_at is null`; H2 does
  not support partial unique indexes. It is the one invariant with only a single line of
  defence, and worth knowing as a genuine difference between this environment and production.

Then:

- **Postgres and Testcontainers.** The schema is already Flyway-managed and dialect-neutral;
  add the partial unique index above and the third gap closes with it.
- **Domain events and an outbox.** `BookReturned` is the natural first event, and it is what a
  reservation queue would consume. A transactional outbox keeps publication atomic with the
  state change.
- **Observability.** Actuator is present but exposes only `health`. Real deployment wants
  structured logging with a correlation id, metrics on borrow/return rates and lock wait time,
  and tracing across the two modules — lock contention is the first thing that will hurt under
  load, and right now nothing would tell you.
- **Rate limiting** on borrow, to make the contended path harder to abuse.
- **Turn off the H2 console.** It is enabled for convenience here and `permitAll` in the
  filter chain. That is a development affordance, not a production one.
- **Splitting the modules into services** is the interesting one. The compiler-enforced
  boundary means the code would move cleanly, but the two-step borrow stops being atomic. It
  would become a reservation with a timeout and a compensating release, coordinated through the
  existing `BookInventory` seam. That is the moment this design's central simplification has
  to be paid for, and it is deliberate that the payment is localised to one interface.
