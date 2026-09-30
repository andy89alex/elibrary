# E-Library Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot service where authenticated members browse a book/journal catalogue, borrow items, return them, and view what they currently hold.

**Architecture:** Two modules carrying deliberately different architectural weight. `lending` is genuine hexagonal — a pure-Java immutable `Loan` aggregate, ports defined by the consumer, JPA adapter outside. `catalog` is read-mostly, so it is CQRS-lite: no aggregate or mapper stack, just a public read API over JPA. `catalog` is the sole authority on copy availability and exposes it to `lending` through a port `lending` owns. Module internals are package-private so the boundary is enforced by the compiler and verified by ArchUnit.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Maven, Spring Data JPA, H2 (in-memory, PostgreSQL compatibility mode), Flyway, Spring Security (HTTP Basic), springdoc-openapi, JUnit 5, AssertJ, MockMvc, ArchUnit 1.3.0.

**Spec:** `docs/superpowers/specs/2026-09-30-elibrary-service-design.md`

## Global Constraints

- Java language level is **21**. Set `<maven.compiler.release>21</maven.compiler.release>`. The local JDK is 25; the build must still target 21 so a reviewer on JDK 21 can build.
- Base package is `com.elibrary`.
- API base path is `/api/v1`. All endpoints require HTTP Basic auth except `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/actuator/health`.
- **No class in `com.elibrary.lending.domain` may import `org.springframework..`, `jakarta.persistence..`, or `com.elibrary.catalog..`.** This is enforced by ArchUnit in Task 15. Domain code is plain Java.
- **Never call `LocalDate.now()`, `Instant.now()`, or `LocalDateTime.now()`.** Time comes only from an injected `java.time.Clock`.
- **Never serialise a JPA entity to HTTP.** Response DTOs are separate records, mapped explicitly.
- **Never serialise Spring's `Page<T>`.** Use `com.elibrary.shared.PageResult<T>`.
- Lending policy defaults: `maxConcurrentLoans=5`, `loanPeriodDays=14`, bound from `elibrary.lending.*`.
- All business refusals return HTTP **409** with a stable machine-readable `code` field in an RFC 9457 `ProblemDetail`. Problem `type` URIs are `https://elibrary.example/problems/<kebab-case-code>`.
- A loan that does not belong to the caller returns **404**, never 403.
- Classes inside `com.elibrary.catalog.internal` and `com.elibrary.lending.internal` are **package-private**. Spring instantiates package-private beans fine.
- `spring.jpa.hibernate.ddl-auto=validate`. Schema changes go in Flyway migrations only.
- `spring.jpa.open-in-view=false`.
- Commit after every task using the message given in that task's final step.

---

## File Structure

### Production

| File | Responsibility |
|---|---|
| `pom.xml` | Maven build, Java 21 target, dependencies |
| `src/main/resources/application.yml` | Datasource, JPA, Flyway, lending policy, springdoc config |
| `src/main/resources/db/migration/V1__create_schema.sql` | `books` and `loans` tables, indexes, check constraints |
| `src/main/resources/db/migration/V2__seed_catalog.sql` | 15 seeded books/journals with fixed UUIDs |
| `com/elibrary/ElibraryApplication.java` | Spring Boot entry point |
| **shared** | |
| `shared/BookId.java` | Value object wrapping `UUID` |
| `shared/MemberId.java` | Value object wrapping the principal name |
| `shared/Isbn.java` | Value object with format validation |
| `shared/PageResult.java` | Our pagination envelope, framework-free |
| `shared/error/DomainException.java` | Abstract base carrying a stable `code()` |
| `shared/error/NotFoundException.java` | Abstract base → HTTP 404 |
| `shared/error/ConflictException.java` | Abstract base → HTTP 409 |
| **catalog** (public surface) | |
| `catalog/BookCatalog.java` | Public read API: search, find by id, batch summaries |
| `catalog/BookSummary.java` | Read model for list views |
| `catalog/BookDetail.java` | Read model for the detail view |
| `catalog/ContentKind.java` | `BOOK` / `JOURNAL` |
| `catalog/BookSearchCriteria.java` | Search inputs including whitelisted sort field |
| `catalog/BookSortField.java` | `TITLE` / `AUTHOR` / `PUBLICATION_YEAR` |
| **catalog** (internal, package-private) | |
| `catalog/internal/BookRecord.java` | `@Entity`, owns `totalCopies`/`availableCopies` and their guards |
| `catalog/internal/BookJpaRepository.java` | Search query plus pessimistic-lock read |
| `catalog/internal/JpaBookCatalog.java` | Implements `BookCatalog`, maps entity → read model |
| `catalog/internal/CatalogBookInventory.java` | Implements lending's `BookInventory` port under a write lock |
| `catalog/web/BookController.java` | `GET /api/v1/books`, `GET /api/v1/books/{id}` |
| **lending** (domain, pure Java) | |
| `lending/domain/Loan.java` | Immutable aggregate; `open`, `returnNow`, `isOverdue` |
| `lending/domain/LoanId.java` | Value object wrapping `UUID` |
| `lending/domain/ActiveLoans.java` | Member's lending position; enforces limit and duplicate rules |
| `lending/domain/LendingPolicy.java` | Pure record: `maxConcurrentLoans`, `loanPeriodDays`, `dueDateFrom` |
| `lending/domain/LoanStatusFilter.java` | `ACTIVE` / `RETURNED` / `ALL` |
| `lending/domain/LoanRepository.java` | Port owned by the domain |
| `lending/domain/BookInventory.java` | Port owned by the domain, implemented by `catalog` |
| `lending/domain/BookNotFound.java` | 404 |
| `lending/domain/LoanNotFound.java` | 404 |
| `lending/domain/BookUnavailable.java` | 409 `NO_COPIES_AVAILABLE` |
| `lending/domain/LoanLimitReached.java` | 409 `LOAN_LIMIT_REACHED` |
| `lending/domain/AlreadyBorrowed.java` | 409 `ALREADY_BORROWED` |
| `lending/domain/LoanAlreadyReturned.java` | 409 `LOAN_ALREADY_RETURNED` |
| `lending/application/BorrowBook.java` | Use case: orchestration only |
| `lending/application/ReturnBook.java` | Use case: orchestration only |
| `lending/application/ViewLoans.java` | Use case: paged read of the caller's loans |
| `lending/internal/LoanEntity.java` | `@Entity` persistence shape |
| `lending/internal/LoanJpaRepository.java` | Spring Data repository |
| `lending/internal/LoanMapper.java` | `Loan` ↔ `LoanEntity` |
| `lending/internal/JpaLoanRepository.java` | Implements `LoanRepository` |
| `lending/web/LoanController.java` | Loan endpoints; enriches with catalogue titles |
| `lending/web/BorrowRequest.java` | Request body for `POST /loans` |
| `lending/web/LoanResponse.java` | Response DTO including `BookRef` |
| **platform** | |
| `platform/time/ClockConfig.java` | `Clock` bean |
| `platform/config/LendingProperties.java` | `@ConfigurationProperties("elibrary.lending")` |
| `platform/config/LendingConfiguration.java` | Produces the pure `LendingPolicy` bean |
| `platform/security/SecurityConfig.java` | Filter chain, seeded users, password encoder |
| `platform/security/MemberIdArgumentResolver.java` | Principal → `MemberId` at the boundary |
| `platform/security/WebMvcConfig.java` | Registers the resolver |
| `platform/security/ProblemDetailEntryPoint.java` | 401 as `ProblemDetail` |
| `platform/web/ApiExceptionHandler.java` | Domain exception → `ProblemDetail` |
| `platform/web/ProblemDetails.java` | Builds `type` URI and `code` property |
| `platform/openapi/OpenApiConfig.java` | API metadata and Basic auth scheme |

### Tests

| File | Responsibility |
|---|---|
| `ElibraryApplicationTests.java` | Context loads, Flyway migrates |
| `shared/BookIdTest.java`, `MemberIdTest.java`, `IsbnTest.java`, `PageResultTest.java` | Value object invariants |
| `lending/domain/LendingPolicyTest.java` | Validation and `dueDateFrom` |
| `lending/domain/LoanTest.java` | Lifecycle, overdue predicate, ownership |
| `lending/domain/ActiveLoansTest.java` | Limit and duplicate invariants |
| `lending/application/BorrowBookTest.java`, `ReturnBookTest.java`, `ViewLoansTest.java` | Orchestration against fakes |
| `lending/application/InMemoryLoanRepository.java` | Hand-written fake |
| `lending/application/InMemoryBookInventory.java` | Hand-written fake |
| `catalog/internal/JpaBookCatalogTest.java` | `@DataJpaTest`: search, filter, sort, paging |
| `catalog/internal/CatalogBookInventoryTest.java` | `@DataJpaTest`: checkout/restore guards |
| `lending/internal/JpaLoanRepositoryTest.java` | `@DataJpaTest`: round-trip, active query, paging |
| `platform/web/ApiExceptionHandlerTest.java` | `@WebMvcTest`: status and `code` mapping |
| `catalog/web/BookControllerTest.java` | `@WebMvcTest`: binding, response shape, sort whitelist |
| `lending/web/LoanControllerTest.java` | `@WebMvcTest`: binding, 201 + `Location`, enrichment |
| `integration/LendingFlowIT.java` | End-to-end borrow → view → return over HTTP |
| `integration/LastCopyConcurrencyIT.java` | N threads, exactly one winner |
| `architecture/ArchitectureTest.java` | Domain purity and module isolation |

---

### Task 1: Project scaffold, configuration, and database schema

Replaces the IntelliJ scaffold with a Maven layout, boots Spring Boot against H2, and migrates a Flyway-managed schema with seed data.

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/elibrary/ElibraryApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__create_schema.sql`
- Create: `src/main/resources/db/migration/V2__seed_catalog.sql`
- Delete: `src/Main.java`, `Elibrary.iml`
- Test: `src/test/java/com/elibrary/ElibraryApplicationTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a bootable app; the `books` and `loans` tables with the exact column names later tasks map to; seeded book UUIDs `11111111-1111-1111-1111-111111111101` … `…115` used by integration tests.

- [ ] **Step 1: Remove the IntelliJ scaffold and create the Maven source layout**

```bash
git rm -q src/Main.java Elibrary.iml
mkdir -p src/main/java/com/elibrary src/main/resources/db/migration src/test/java/com/elibrary
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.1</version>
    <relativePath/>
  </parent>

  <groupId>com.elibrary</groupId>
  <artifactId>elibrary</artifactId>
  <version>0.1.0</version>
  <name>elibrary</name>
  <description>E-Library lending service</description>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <springdoc.version>2.8.0</springdoc.version>
    <archunit.version>1.3.0</archunit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>${springdoc.version}</version>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>${archunit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

The failsafe plugin is wired so `*IT` classes run under `mvn verify` rather than `mvn test`, keeping the fast unit suite fast.

- [ ] **Step 3: Write the application entry point**

`src/main/java/com/elibrary/ElibraryApplication.java`

```java
package com.elibrary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ElibraryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElibraryApplication.class, args);
    }
}
```

- [ ] **Step 4: Write `application.yml`**

`src/main/resources/application.yml`

```yaml
spring:
  application:
    name: elibrary
  datasource:
    url: jdbc:h2:mem:elibrary;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  h2:
    console:
      enabled: true
      path: /h2-console

management:
  endpoints:
    web:
      exposure:
        include: health

springdoc:
  swagger-ui:
    path: /swagger-ui.html

elibrary:
  lending:
    max-concurrent-loans: 5
    loan-period-days: 14

logging:
  level:
    org.springframework.security: INFO
```

`MODE=PostgreSQL` keeps SQL dialect drift small so the Flyway migrations stay portable. `open-in-view: false` prevents lazy loading from leaking into the web layer.

- [ ] **Step 5: Write the schema migration**

`src/main/resources/db/migration/V1__create_schema.sql`

```sql
create table books (
    id               uuid         primary key,
    title            varchar(300) not null,
    author           varchar(200) not null,
    isbn             varchar(20),
    kind             varchar(20)  not null,
    publisher        varchar(200),
    publication_year int,
    volume           varchar(20),
    issue            varchar(20),
    total_copies     int          not null,
    available_copies int          not null,
    version          bigint       not null default 0,
    constraint chk_books_total_copies     check (total_copies >= 0),
    constraint chk_books_available_copies check (available_copies >= 0 and available_copies <= total_copies),
    constraint chk_books_kind             check (kind in ('BOOK', 'JOURNAL'))
);

create index idx_books_title  on books (title);
create index idx_books_author on books (author);

create table loans (
    id          uuid         primary key,
    member_id   varchar(100) not null,
    book_id     uuid         not null,
    borrowed_at timestamp(6) not null,
    due_on      date         not null,
    returned_at timestamp(6),
    constraint fk_loans_book foreign key (book_id) references books (id)
);

create index idx_loans_member_returned on loans (member_id, returned_at);
create index idx_loans_book            on loans (book_id);
```

Two notes to carry into the README. The `chk_books_available_copies` constraint makes the copy invariant a database-level guarantee, not only an application one — even a buggy write cannot drive availability negative. Invariant 3 (no two active loans of the same book by one member) is *not* expressible as a constraint here because H2 does not support partial unique indexes; on PostgreSQL it would be `create unique index … on loans (member_id, book_id) where returned_at is null`, and that belongs in the README as a known gap between environments.

- [ ] **Step 6: Write the seed migration**

`src/main/resources/db/migration/V2__seed_catalog.sql`

Fixed UUIDs so `curl` examples and integration tests can reference stable ids. Note the deliberate mix: `…109` and `…110` have zero available copies so the `available=true` filter is demonstrable immediately, and `…111` has exactly one copy left for the concurrency test.

```sql
insert into books (id, title, author, isbn, kind, publisher, publication_year, volume, issue, total_copies, available_copies) values
('11111111-1111-1111-1111-111111111101', 'Domain-Driven Design', 'Eric Evans', '9780321125217', 'BOOK', 'Addison-Wesley', 2003, null, null, 4, 4),
('11111111-1111-1111-1111-111111111102', 'Implementing Domain-Driven Design', 'Vaughn Vernon', '9780321834577', 'BOOK', 'Addison-Wesley', 2013, null, null, 3, 3),
('11111111-1111-1111-1111-111111111103', 'Patterns of Enterprise Application Architecture', 'Martin Fowler', '9780321127426', 'BOOK', 'Addison-Wesley', 2002, null, null, 2, 2),
('11111111-1111-1111-1111-111111111104', 'Refactoring', 'Martin Fowler', '9780134757599', 'BOOK', 'Addison-Wesley', 2018, null, null, 5, 5),
('11111111-1111-1111-1111-111111111105', 'Release It!', 'Michael Nygard', '9781680502398', 'BOOK', 'Pragmatic Bookshelf', 2018, null, null, 2, 2),
('11111111-1111-1111-1111-111111111106', 'Designing Data-Intensive Applications', 'Martin Kleppmann', '9781449373320', 'BOOK', 'O''Reilly', 2017, null, null, 6, 6),
('11111111-1111-1111-1111-111111111107', 'Effective Java', 'Joshua Bloch', '9780134685991', 'BOOK', 'Addison-Wesley', 2018, null, null, 4, 4),
('11111111-1111-1111-1111-111111111108', 'Java Concurrency in Practice', 'Brian Goetz', '9780321349606', 'BOOK', 'Addison-Wesley', 2006, null, null, 3, 3),
('11111111-1111-1111-1111-111111111109', 'Working Effectively with Legacy Code', 'Michael Feathers', '9780131177055', 'BOOK', 'Prentice Hall', 2004, null, null, 2, 0),
('11111111-1111-1111-1111-111111111110', 'Accelerate', 'Nicole Forsgren', '9781942788331', 'BOOK', 'IT Revolution', 2018, null, null, 1, 0),
('11111111-1111-1111-1111-111111111111', 'Tidy First?', 'Kent Beck', '9781098151249', 'BOOK', 'O''Reilly', 2023, null, null, 1, 1),
('11111111-1111-1111-1111-111111111112', 'Communications of the ACM', 'Various', '0001-0782', 'JOURNAL', 'ACM', 2025, '68', '9', 8, 8),
('11111111-1111-1111-1111-111111111113', 'IEEE Software', 'Various', '0740-7459', 'JOURNAL', 'IEEE', 2025, '42', '4', 5, 5),
('11111111-1111-1111-1111-111111111114', 'ACM Transactions on Software Engineering', 'Various', '1049-331X', 'JOURNAL', 'ACM', 2024, '33', '2', 3, 3),
('11111111-1111-1111-1111-111111111115', 'Journal of Systems and Software', 'Various', '0164-1212', 'JOURNAL', 'Elsevier', 2024, '210', '1', 2, 2);
```

- [ ] **Step 7: Write the failing smoke test**

`src/test/java/com/elibrary/ElibraryApplicationTests.java`

```java
package com.elibrary;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ElibraryApplicationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsAndFlywayMigratesTheSeededCatalogue() {
        Integer books = jdbc.queryForObject("select count(*) from books", Integer.class);
        assertThat(books).isEqualTo(15);
    }

    @Test
    void seedContainsBothAvailableAndFullyBorrowedItems() {
        Integer unavailable = jdbc.queryForObject(
                "select count(*) from books where available_copies = 0", Integer.class);
        assertThat(unavailable).isEqualTo(2);
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `mvn -q test -Dtest=ElibraryApplicationTests`
Expected: FAIL. Before the files above exist the build cannot resolve `ElibraryApplication`; once they do, this step is the confirmation that migrations actually ran. If it fails with `Table "BOOKS" not found`, Flyway did not pick up `classpath:db/migration` — check the file names match `V1__`/`V2__` exactly (two underscores).

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn -q test -Dtest=ElibraryApplicationTests`
Expected: PASS, 2 tests.

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main src/test .gitignore
git add -u
git commit -m "chore: scaffold Spring Boot app with Flyway-managed H2 schema

Replaces the IntelliJ scaffold with a Maven layout targeting Java 21.
Schema is Flyway-managed with ddl-auto=validate so it is a reviewed
artefact rather than a Hibernate side effect. Seed data deliberately
mixes available and fully-borrowed items."
```

---

### Task 2: Shared kernel — value objects, pagination envelope, error bases

Framework-free types both modules depend on. These carry their own validation so no caller has to remember to check.

**Files:**
- Create: `src/main/java/com/elibrary/shared/BookId.java`
- Create: `src/main/java/com/elibrary/shared/MemberId.java`
- Create: `src/main/java/com/elibrary/shared/Isbn.java`
- Create: `src/main/java/com/elibrary/shared/PageResult.java`
- Create: `src/main/java/com/elibrary/shared/error/DomainException.java`
- Create: `src/main/java/com/elibrary/shared/error/NotFoundException.java`
- Create: `src/main/java/com/elibrary/shared/error/ConflictException.java`
- Test: `src/test/java/com/elibrary/shared/BookIdTest.java`
- Test: `src/test/java/com/elibrary/shared/MemberIdTest.java`
- Test: `src/test/java/com/elibrary/shared/IsbnTest.java`
- Test: `src/test/java/com/elibrary/shared/PageResultTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces, relied on by every later task:
  - `BookId` — `record BookId(UUID value)`; `BookId.of(String)`, `BookId.newId()`, `toString()` returns the bare UUID.
  - `MemberId` — `record MemberId(String value)`.
  - `Isbn` — `record Isbn(String value)`; `Isbn.of(String)` returns `null` for `null` input.
  - `PageResult<T>` — `record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages)`; factory `PageResult.of(List<T> items, int page, int size, long totalElements)`.
  - `DomainException` — `abstract class DomainException extends RuntimeException` with `String code()`.
  - `NotFoundException`, `ConflictException` — `abstract` subclasses; the only two shapes `ApiExceptionHandler` maps.

- [ ] **Step 1: Write the failing value object tests**

`src/test/java/com/elibrary/shared/BookIdTest.java`

```java
package com.elibrary.shared;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookIdTest {

    @Test
    void parsesAWellFormedUuid() {
        BookId id = BookId.of("11111111-1111-1111-1111-111111111101");
        assertThat(id.value()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111101"));
    }

    @Test
    void rendersAsTheBareUuidSoItIsSafeToPutInJson() {
        BookId id = BookId.of("11111111-1111-1111-1111-111111111101");
        assertThat(id).hasToString("11111111-1111-1111-1111-111111111101");
    }

    @Test
    void rejectsAMalformedIdWithAMessageNamingTheInput() {
        assertThatThrownBy(() -> BookId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-uuid");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new BookId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void generatedIdsAreDistinct() {
        assertThat(BookId.newId()).isNotEqualTo(BookId.newId());
    }
}
```

`src/test/java/com/elibrary/shared/MemberIdTest.java`

```java
package com.elibrary.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberIdTest {

    @Test
    void holdsThePrincipalName() {
        assertThat(new MemberId("alice").value()).isEqualTo("alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankValues(String blank) {
        assertThatThrownBy(() -> new MemberId(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new MemberId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIsByValueSoItWorksAsAMapKey() {
        assertThat(new MemberId("alice")).isEqualTo(new MemberId("alice"));
    }
}
```

`src/test/java/com/elibrary/shared/IsbnTest.java`

```java
package com.elibrary.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsbnTest {

    @ParameterizedTest
    @ValueSource(strings = {"9780321125217", "978-0-321-12521-7", "0321125217", "0001-0782", "1049-331X"})
    void acceptsIsbn10Isbn13AndIssnFormats(String raw) {
        assertThat(new Isbn(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12", "97803211252170000000000"})
    void rejectsValuesThatCannotBeAnIdentifier(String raw) {
        assertThatThrownBy(() -> new Isbn(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofPassesNullThroughBecauseTheColumnIsNullable() {
        assertThat(Isbn.of(null)).isNull();
    }
}
```

`src/test/java/com/elibrary/shared/PageResultTest.java`

```java
package com.elibrary.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void computesTotalPagesByRoundingUp() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 0, 2, 5);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void reportsZeroPagesForAnEmptyResult() {
        PageResult<String> result = PageResult.of(List.of(), 0, 20, 0);
        assertThat(result.totalPages()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void itemsAreDefensivelyCopiedSoCallersCannotMutateTheEnvelope() {
        List<String> source = new java.util.ArrayList<>(List.of("a"));
        PageResult<String> result = PageResult.of(source, 0, 10, 1);
        source.add("b");
        assertThat(result.items()).containsExactly("a");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='BookIdTest,MemberIdTest,IsbnTest,PageResultTest'`
Expected: FAIL — compilation error, `cannot find symbol: class BookId`.

- [ ] **Step 3: Write the value objects**

`src/main/java/com/elibrary/shared/BookId.java`

```java
package com.elibrary.shared;

import java.util.Objects;
import java.util.UUID;

/** Identity of a catalogue item. Parsing lives here so no caller hand-rolls UUID handling. */
public record BookId(UUID value) {

    public BookId {
        Objects.requireNonNull(value, "book id must not be null");
    }

    public static BookId of(String raw) {
        Objects.requireNonNull(raw, "book id must not be null");
        try {
            return new BookId(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed book id: " + raw, e);
        }
    }

    public static BookId newId() {
        return new BookId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
```

`src/main/java/com/elibrary/shared/MemberId.java`

```java
package com.elibrary.shared;

/**
 * Identity of a library member, taken from the authenticated principal.
 * There is no members table: member management is a separate domain, and none of
 * the lending invariants need member data beyond this identifier.
 */
public record MemberId(String value) {

    public MemberId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("member id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
```

`src/main/java/com/elibrary/shared/Isbn.java`

```java
package com.elibrary.shared;

import java.util.regex.Pattern;

/**
 * Loose identifier for a catalogue item: ISBN-10, ISBN-13, or ISSN for journals.
 * Checksum validation is deliberately out of scope — the catalogue is a read model
 * populated from a trusted feed, and rejecting real-world identifiers on a checksum
 * technicality would be worse than accepting them.
 */
public record Isbn(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[0-9Xx][0-9Xx-]{7,18}$");

    public Isbn {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed isbn/issn: " + value);
        }
    }

    public static Isbn of(String raw) {
        return raw == null ? null : new Isbn(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

`src/main/java/com/elibrary/shared/PageResult.java`

```java
package com.elibrary.shared;

import java.util.List;

/**
 * Our pagination envelope. Spring's {@code Page<T>} is never serialised: its JSON shape
 * is not stable across Spring versions, so exposing it would bind our public contract to
 * the framework. Offset paging is used; cursor paging would be the right call at scale.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }

    public <R> PageResult<R> map(java.util.function.Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
```

- [ ] **Step 4: Write the error bases**

`src/main/java/com/elibrary/shared/error/DomainException.java`

```java
package com.elibrary.shared.error;

/**
 * Base for every business failure. Carries a stable, machine-readable code.
 * Clients switch on {@link #code()}, not on the HTTP status: status codes have too low a
 * cardinality to serve as an error contract, whereas codes can grow without a breaking change.
 * Deliberately knows nothing about HTTP — mapping happens only in {@code platform.web}.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

`src/main/java/com/elibrary/shared/error/NotFoundException.java`

```java
package com.elibrary.shared.error;

/** A referenced thing does not exist, or the caller may not know that it does. Maps to 404. */
public abstract class NotFoundException extends DomainException {

    protected NotFoundException(String code, String message) {
        super(code, message);
    }
}
```

`src/main/java/com/elibrary/shared/error/ConflictException.java`

```java
package com.elibrary.shared.error;

/** The request is well-formed but the current state forbids it. Maps to 409. */
public abstract class ConflictException extends DomainException {

    protected ConflictException(String code, String message) {
        super(code, message);
    }
}
```

Two abstract bases rather than one is what keeps `ApiExceptionHandler` down to two handler methods in Task 11. A new domain error picks a base and needs no change to the web layer.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='BookIdTest,MemberIdTest,IsbnTest,PageResultTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/elibrary/shared src/test/java/com/elibrary/shared
git commit -m "feat(shared): add value objects, pagination envelope, and error kernel

Value objects validate on construction so callers cannot forget. PageResult
keeps Spring's Page off the public contract. Two abstract exception bases
(NotFound/Conflict) keep the HTTP mapping to two handler methods and let new
domain errors ship without touching the web layer."
```

---

### Task 3: Lending policy and clock

Policy is separated from mechanism: the numbers live in configuration, not in code.

**Spec correction:** spec §4.3 puts `@ConfigurationProperties` on `LendingPolicy`, which contradicts §8's rule that `lending.domain` imports no Spring. `LendingPolicy` stays a pure record; binding moves to `platform/config`.

**Files:**
- Create: `src/main/java/com/elibrary/lending/domain/LendingPolicy.java`
- Create: `src/main/java/com/elibrary/platform/config/LendingProperties.java`
- Create: `src/main/java/com/elibrary/platform/config/LendingConfiguration.java`
- Create: `src/main/java/com/elibrary/platform/time/ClockConfig.java`
- Test: `src/test/java/com/elibrary/lending/domain/LendingPolicyTest.java`
- Test: `src/test/java/com/elibrary/platform/config/LendingConfigurationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `LendingPolicy` — `record LendingPolicy(int maxConcurrentLoans, int loanPeriodDays)` with `LocalDate dueDateFrom(LocalDate start)`. Available as a Spring bean.
  - A `Clock` bean (`Clock.systemUTC()`), injected wherever time is read.

- [ ] **Step 1: Write the failing policy test**

`src/test/java/com/elibrary/lending/domain/LendingPolicyTest.java`

```java
package com.elibrary.lending.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LendingPolicyTest {

    @Test
    void dueDateIsTheLoanPeriodAfterTheStartDate() {
        LendingPolicy policy = new LendingPolicy(5, 14);
        assertThat(policy.dueDateFrom(LocalDate.of(2026, 9, 30)))
                .isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void rejectsANonPositiveLoanLimitBecauseItWouldBlockAllBorrowing() {
        assertThatThrownBy(() -> new LendingPolicy(0, 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrentLoans");
    }

    @Test
    void rejectsANonPositiveLoanPeriodBecauseTheLoanWouldBeBornOverdue() {
        assertThatThrownBy(() -> new LendingPolicy(5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loanPeriodDays");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=LendingPolicyTest`
Expected: FAIL — `cannot find symbol: class LendingPolicy`.

- [ ] **Step 3: Write `LendingPolicy`**

`src/main/java/com/elibrary/lending/domain/LendingPolicy.java`

```java
package com.elibrary.lending.domain;

import java.time.LocalDate;

/**
 * The library's lending rules, as data. Kept free of Spring annotations so the domain
 * stays plain Java (enforced by ArchUnit); binding lives in platform.config.
 */
public record LendingPolicy(int maxConcurrentLoans, int loanPeriodDays) {

    public LendingPolicy {
        if (maxConcurrentLoans < 1) {
            throw new IllegalArgumentException("maxConcurrentLoans must be at least 1, was " + maxConcurrentLoans);
        }
        if (loanPeriodDays < 1) {
            throw new IllegalArgumentException("loanPeriodDays must be at least 1, was " + loanPeriodDays);
        }
    }

    public LocalDate dueDateFrom(LocalDate start) {
        return start.plusDays(loanPeriodDays);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LendingPolicyTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing binding test**

`src/test/java/com/elibrary/platform/config/LendingConfigurationTest.java`

```java
package com.elibrary.platform.config;

import com.elibrary.lending.domain.LendingPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "elibrary.lending.max-concurrent-loans=3",
        "elibrary.lending.loan-period-days=7"
})
class LendingConfigurationTest {

    @Autowired
    private LendingPolicy policy;

    @Autowired
    private Clock clock;

    @Test
    void bindsThePolicyFromConfiguration() {
        assertThat(policy.maxConcurrentLoans()).isEqualTo(3);
        assertThat(policy.loanPeriodDays()).isEqualTo(7);
    }

    @Test
    void exposesAClockBeanSoNoCodeNeedsToCallNowDirectly() {
        assertThat(clock).isNotNull();
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `mvn -q test -Dtest=LendingConfigurationTest`
Expected: FAIL — `NoSuchBeanDefinitionException` for `LendingPolicy`.

- [ ] **Step 7: Write the binding and clock configuration**

`src/main/java/com/elibrary/platform/config/LendingProperties.java`

```java
package com.elibrary.platform.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** External shape of the lending policy. Fails fast at startup on a bad value. */
@Validated
@ConfigurationProperties(prefix = "elibrary.lending")
public record LendingProperties(
        @Min(1) int maxConcurrentLoans,
        @Min(1) int loanPeriodDays) {
}
```

`src/main/java/com/elibrary/platform/config/LendingConfiguration.java`

```java
package com.elibrary.platform.config;

import com.elibrary.lending.domain.LendingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Translates external configuration into the domain's own policy type. */
@Configuration
class LendingConfiguration {

    @Bean
    LendingPolicy lendingPolicy(LendingProperties properties) {
        return new LendingPolicy(properties.maxConcurrentLoans(), properties.loanPeriodDays());
    }
}
```

`src/main/java/com/elibrary/platform/time/ClockConfig.java`

```java
package com.elibrary.platform.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The single source of time. Every class that needs "now" injects this, so tests can
 * substitute {@code Clock.fixed(...)} and assert exact due dates and overdue behaviour.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LendingConfigurationTest`
Expected: PASS, 2 tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/elibrary/lending/domain/LendingPolicy.java \
        src/main/java/com/elibrary/platform \
        src/test/java/com/elibrary/lending/domain/LendingPolicyTest.java \
        src/test/java/com/elibrary/platform
git commit -m "feat(lending): add configurable lending policy and a single clock source

LendingPolicy stays a pure domain record; @ConfigurationProperties binding lives
in platform.config so the domain imports no Spring. A Clock bean replaces every
direct now() call, making due dates and overdue deterministic under test."
```

---

### Task 4: The `Loan` aggregate

Immutable, with `OVERDUE` modelled as a derived predicate rather than stored state.

**Files:**
- Create: `src/main/java/com/elibrary/lending/domain/LoanId.java`
- Create: `src/main/java/com/elibrary/lending/domain/Loan.java`
- Create: `src/main/java/com/elibrary/lending/domain/LoanAlreadyReturned.java`
- Test: `src/test/java/com/elibrary/lending/domain/LoanTest.java`

**Interfaces:**
- Consumes: `BookId`, `MemberId` (Task 2); `LendingPolicy` (Task 3); `ConflictException` (Task 2).
- Produces:
  - `LoanId` — `record LoanId(UUID value)`; `LoanId.of(String)`, `LoanId.newId()`.
  - `Loan` — static `open(MemberId, BookId, LendingPolicy, Clock)`, static `reconstitute(LoanId, MemberId, BookId, Instant borrowedAt, LocalDate dueOn, Instant returnedAt)`; instance `returnNow(Clock)`, `isActive()`, `isOverdue(Clock)`, `belongsTo(MemberId)`, `isFor(BookId)`; accessors `id()`, `memberId()`, `bookId()`, `borrowedAt()`, `dueOn()`, `returnedAt()`.
  - `LoanAlreadyReturned` — `ConflictException`, code `LOAN_ALREADY_RETURNED`.

- [ ] **Step 1: Write the failing aggregate test**

`src/test/java/com/elibrary/lending/domain/LoanTest.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    private static Clock at(String isoDate) {
        return Clock.fixed(Instant.parse(isoDate + "T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void opensActiveWithADueDateDrivenByThePolicy() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        assertThat(loan.isActive()).isTrue();
        assertThat(loan.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(loan.returnedAt()).isNull();
        assertThat(loan.memberId()).isEqualTo(ALICE);
        assertThat(loan.bookId()).isEqualTo(DDD);
    }

    @Test
    void returningProducesANewClosedLoanAndLeavesTheOriginalUntouched() {
        Loan open = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        Loan closed = open.returnNow(at("2026-10-02"));

        assertThat(closed.isActive()).isFalse();
        assertThat(closed.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(open.isActive()).as("the aggregate is immutable").isTrue();
    }

    @Test
    void returningTwiceIsRejected() {
        Loan closed = Loan.open(ALICE, DDD, POLICY, at("2026-09-30")).returnNow(at("2026-10-02"));

        assertThatThrownBy(() -> closed.returnNow(at("2026-10-03")))
                .isInstanceOf(LoanAlreadyReturned.class)
                .satisfies(e -> assertThat(((LoanAlreadyReturned) e).code()).isEqualTo("LOAN_ALREADY_RETURNED"));
    }

    @Test
    void isNotOverdueOnTheDueDateItself() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(loan.isOverdue(at("2026-10-14"))).isFalse();
    }

    @Test
    void becomesOverdueTheDayAfterTheDueDate() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(loan.isOverdue(at("2026-10-15"))).isTrue();
    }

    @Test
    void aReturnedLoanIsNeverOverdueHoweverLateItWas() {
        Loan closed = Loan.open(ALICE, DDD, POLICY, at("2026-09-30")).returnNow(at("2026-12-01"));
        assertThat(closed.isOverdue(at("2027-01-01"))).isFalse();
    }

    @Test
    void knowsWhoItBelongsToAndWhatItIsFor() {
        Loan loan = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));

        assertThat(loan.belongsTo(ALICE)).isTrue();
        assertThat(loan.belongsTo(new MemberId("bob"))).isFalse();
        assertThat(loan.isFor(DDD)).isTrue();
        assertThat(loan.isFor(BookId.of("11111111-1111-1111-1111-111111111102"))).isFalse();
    }

    @Test
    void reconstitutionFromStorageRoundTripsEveryField() {
        LoanId id = LoanId.newId();
        Instant borrowedAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant returnedAt = Instant.parse("2026-10-02T10:00:00Z");

        Loan loan = Loan.reconstitute(id, ALICE, DDD, borrowedAt, LocalDate.of(2026, 10, 14), returnedAt);

        assertThat(loan.id()).isEqualTo(id);
        assertThat(loan.borrowedAt()).isEqualTo(borrowedAt);
        assertThat(loan.returnedAt()).isEqualTo(returnedAt);
        assertThat(loan.isActive()).isFalse();
    }

    @Test
    void identityIsTheLoanIdAloneSoStateChangesDoNotBreakEquality() {
        Loan open = Loan.open(ALICE, DDD, POLICY, at("2026-09-30"));
        assertThat(open.returnNow(at("2026-10-02"))).isEqualTo(open);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=LoanTest`
Expected: FAIL — `cannot find symbol: class Loan`.

- [ ] **Step 3: Write `LoanId`**

`src/main/java/com/elibrary/lending/domain/LoanId.java`

```java
package com.elibrary.lending.domain;

import java.util.Objects;
import java.util.UUID;

public record LoanId(UUID value) {

    public LoanId {
        Objects.requireNonNull(value, "loan id must not be null");
    }

    public static LoanId of(String raw) {
        Objects.requireNonNull(raw, "loan id must not be null");
        try {
            return new LoanId(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed loan id: " + raw, e);
        }
    }

    public static LoanId newId() {
        return new LoanId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
```

- [ ] **Step 4: Write `LoanAlreadyReturned`**

`src/main/java/com/elibrary/lending/domain/LoanAlreadyReturned.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.error.ConflictException;

public class LoanAlreadyReturned extends ConflictException {

    public LoanAlreadyReturned(LoanId id) {
        super("LOAN_ALREADY_RETURNED", "Loan " + id + " has already been returned.");
    }
}
```

- [ ] **Step 5: Write the `Loan` aggregate**

`src/main/java/com/elibrary/lending/domain/Loan.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A member's borrowing of one catalogue item.
 *
 * <p>Immutable: {@link #returnNow(Clock)} yields a new instance rather than mutating,
 * so there is no partially-updated state to reason about and the type is trivial to test.
 *
 * <p>"Overdue" is a derived predicate, never stored state. It is a pure function of
 * {@code dueOn} and the clock, so no scheduled job has to flip statuses, no column can
 * go stale, and the classic "the cron died overnight" bug cannot happen.
 */
public final class Loan {

    private final LoanId id;
    private final MemberId memberId;
    private final BookId bookId;
    private final Instant borrowedAt;
    private final LocalDate dueOn;
    private final Instant returnedAt;

    private Loan(LoanId id, MemberId memberId, BookId bookId,
                 Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
        this.borrowedAt = Objects.requireNonNull(borrowedAt, "borrowedAt");
        this.dueOn = Objects.requireNonNull(dueOn, "dueOn");
        this.returnedAt = returnedAt;
    }

    public static Loan open(MemberId memberId, BookId bookId, LendingPolicy policy, Clock clock) {
        Instant now = clock.instant();
        return new Loan(LoanId.newId(), memberId, bookId, now, policy.dueDateFrom(today(clock)), null);
    }

    /** Rebuilds a loan from storage. Used only by the persistence adapter. */
    public static Loan reconstitute(LoanId id, MemberId memberId, BookId bookId,
                                    Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        return new Loan(id, memberId, bookId, borrowedAt, dueOn, returnedAt);
    }

    public Loan returnNow(Clock clock) {
        if (returnedAt != null) {
            throw new LoanAlreadyReturned(id);
        }
        return new Loan(id, memberId, bookId, borrowedAt, dueOn, clock.instant());
    }

    public boolean isActive() {
        return returnedAt == null;
    }

    public boolean isOverdue(Clock clock) {
        return isActive() && dueOn.isBefore(today(clock));
    }

    public boolean belongsTo(MemberId candidate) {
        return memberId.equals(candidate);
    }

    public boolean isFor(BookId candidate) {
        return bookId.equals(candidate);
    }

    private static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    public LoanId id() {
        return id;
    }

    public MemberId memberId() {
        return memberId;
    }

    public BookId bookId() {
        return bookId;
    }

    public Instant borrowedAt() {
        return borrowedAt;
    }

    public LocalDate dueOn() {
        return dueOn;
    }

    public Instant returnedAt() {
        return returnedAt;
    }

    /** Identity is the loan id alone: a returned loan is still the same loan. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Loan other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Loan[" + id + " member=" + memberId + " book=" + bookId
                + " dueOn=" + dueOn + (isActive() ? " active]" : " returnedAt=" + returnedAt + "]");
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LoanTest`
Expected: PASS, 9 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/elibrary/lending/domain src/test/java/com/elibrary/lending/domain/LoanTest.java
git commit -m "feat(lending): add immutable Loan aggregate with derived overdue

returnNow() returns a new instance rather than mutating. Overdue is a pure
function of dueOn and the injected clock, so no scheduled job is needed to
flip statuses and no persisted column can go stale."
```

---

### Task 5: `ActiveLoans` — where the limit and duplicate invariants live

Invariants 2 and 3 belong in the domain, not in the application service. `ActiveLoans` models the member's current lending position and is the only way to create a `Loan`, so neither rule can be bypassed.

**Files:**
- Create: `src/main/java/com/elibrary/lending/domain/ActiveLoans.java`
- Create: `src/main/java/com/elibrary/lending/domain/LoanLimitReached.java`
- Create: `src/main/java/com/elibrary/lending/domain/AlreadyBorrowed.java`
- Test: `src/test/java/com/elibrary/lending/domain/ActiveLoansTest.java`

**Interfaces:**
- Consumes: `Loan`, `LendingPolicy` (Tasks 3–4); `BookId`, `MemberId`, `ConflictException` (Task 2).
- Produces:
  - `ActiveLoans` — constructor `ActiveLoans(MemberId, List<Loan>)`; `Loan borrow(BookId, LendingPolicy, Clock)`; `int count()`; `List<Loan> all()`.
  - `LoanLimitReached` — `ConflictException`, code `LOAN_LIMIT_REACHED`.
  - `AlreadyBorrowed` — `ConflictException`, code `ALREADY_BORROWED`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/lending/domain/ActiveLoansTest.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveLoansTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final LendingPolicy POLICY = new LendingPolicy(2, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void borrowingFromAnEmptyPositionOpensALoanForTheMember() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of());

        Loan loan = position.borrow(DDD, POLICY, CLOCK);

        assertThat(loan.belongsTo(ALICE)).isTrue();
        assertThat(loan.isFor(DDD)).isTrue();
        assertThat(loan.isActive()).isTrue();
    }

    @Test
    void rejectsBorrowingWhenTheConcurrentLimitIsAlreadyReached() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(
                Loan.open(ALICE, DDD, POLICY, CLOCK),
                Loan.open(ALICE, REFACTORING, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(
                BookId.of("11111111-1111-1111-1111-111111111107"), POLICY, CLOCK))
                .isInstanceOf(LoanLimitReached.class)
                .hasMessageContaining("2")
                .satisfies(e -> assertThat(((LoanLimitReached) e).code()).isEqualTo("LOAN_LIMIT_REACHED"));
    }

    @Test
    void rejectsBorrowingABookTheMemberAlreadyHolds() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(Loan.open(ALICE, DDD, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(DDD, POLICY, CLOCK))
                .isInstanceOf(AlreadyBorrowed.class)
                .satisfies(e -> assertThat(((AlreadyBorrowed) e).code()).isEqualTo("ALREADY_BORROWED"));
    }

    @Test
    void theDuplicateCheckRunsBeforeTheLimitCheckIsExhaustedSoTheMessageIsTheUsefulOne() {
        ActiveLoans position = new ActiveLoans(ALICE, List.of(
                Loan.open(ALICE, DDD, POLICY, CLOCK),
                Loan.open(ALICE, REFACTORING, POLICY, CLOCK)));

        assertThatThrownBy(() -> position.borrow(DDD, POLICY, CLOCK))
                .as("holding the limit AND already owning it should report the specific cause")
                .isInstanceOf(AlreadyBorrowed.class);
    }

    @Test
    void borrowingOneUnderTheLimitIsAllowed() {
        LendingPolicy five = new LendingPolicy(5, 14);
        List<Loan> four = IntStream.range(1, 5)
                .mapToObj(i -> Loan.open(ALICE, BookId.newId(), five, CLOCK))
                .toList();

        ActiveLoans position = new ActiveLoans(ALICE, four);

        assertThat(position.count()).isEqualTo(4);
        assertThat(position.borrow(DDD, five, CLOCK)).isNotNull();
    }

    @Test
    void rejectsConstructionFromAReturnedLoanBecauseThePositionMustHoldOnlyActiveOnes() {
        Loan returned = Loan.open(ALICE, DDD, POLICY, CLOCK).returnNow(CLOCK);

        assertThatThrownBy(() -> new ActiveLoans(ALICE, List.of(returned)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    @Test
    void rejectsConstructionFromAnotherMembersLoan() {
        Loan bobs = Loan.open(new MemberId("bob"), DDD, POLICY, CLOCK);

        assertThatThrownBy(() -> new ActiveLoans(ALICE, List.of(bobs)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=ActiveLoansTest`
Expected: FAIL — `cannot find symbol: class ActiveLoans`.

- [ ] **Step 3: Write the two conflict exceptions**

`src/main/java/com/elibrary/lending/domain/LoanLimitReached.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.MemberId;
import com.elibrary.shared.error.ConflictException;

public class LoanLimitReached extends ConflictException {

    public LoanLimitReached(MemberId memberId, int held, int limit) {
        super("LOAN_LIMIT_REACHED",
                "Member " + memberId + " already has " + held + " active loans (limit " + limit + ").");
    }
}
```

`src/main/java/com/elibrary/lending/domain/AlreadyBorrowed.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.error.ConflictException;

public class AlreadyBorrowed extends ConflictException {

    public AlreadyBorrowed(MemberId memberId, BookId bookId) {
        super("ALREADY_BORROWED",
                "Member " + memberId + " already holds an active loan for book " + bookId + ".");
    }
}
```

- [ ] **Step 4: Write `ActiveLoans`**

`src/main/java/com/elibrary/lending/domain/ActiveLoans.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * A member's current lending position, and the only route to opening a loan.
 *
 * <p>Invariants 2 (concurrent limit) and 3 (no duplicate active loan for the same book)
 * live here rather than in the application service. Keeping them in the domain means an
 * anaemic-service refactor cannot quietly drop them, and they are unit-testable without
 * Spring or a database.
 */
public final class ActiveLoans {

    private final MemberId memberId;
    private final List<Loan> loans;

    public ActiveLoans(MemberId memberId, List<Loan> loans) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.loans = List.copyOf(Objects.requireNonNull(loans, "loans"));
        for (Loan loan : this.loans) {
            if (!loan.isActive()) {
                throw new IllegalArgumentException("ActiveLoans may only hold active loans, got " + loan);
            }
            if (!loan.belongsTo(memberId)) {
                throw new IllegalArgumentException(
                        "ActiveLoans for " + memberId + " may not hold a loan of " + loan.memberId());
            }
        }
    }

    /**
     * Opens a loan for {@code bookId}, enforcing the member-side rules.
     * Copy availability (invariant 1) is not checked here: the catalogue owns it and
     * enforces it under a write lock. See {@link BookInventory}.
     */
    public Loan borrow(BookId bookId, LendingPolicy policy, Clock clock) {
        if (loans.stream().anyMatch(loan -> loan.isFor(bookId))) {
            throw new AlreadyBorrowed(memberId, bookId);
        }
        if (loans.size() >= policy.maxConcurrentLoans()) {
            throw new LoanLimitReached(memberId, loans.size(), policy.maxConcurrentLoans());
        }
        return Loan.open(memberId, bookId, policy, clock);
    }

    public int count() {
        return loans.size();
    }

    public List<Loan> all() {
        return loans;
    }

    public MemberId memberId() {
        return memberId;
    }
}
```

Note the check order: the duplicate test runs first so a member who is both at the limit and already holding the book gets the specific, actionable error rather than a misleading "limit reached".

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=ActiveLoansTest`
Expected: PASS, 7 tests. `ActiveLoans` references `BookInventory` only in a javadoc link, so it compiles before Task 6 — if the build complains about the `{@link BookInventory}` tag, leave it; javadoc links to missing types are warnings, not errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/elibrary/lending/domain src/test/java/com/elibrary/lending/domain/ActiveLoansTest.java
git commit -m "feat(lending): enforce concurrent loan limit and duplicate rule in the domain

ActiveLoans models the member's lending position and is the only route to
opening a Loan, so invariants 2 and 3 cannot be bypassed by a service. The
duplicate check precedes the limit check so the reported cause is the useful one."
```

---

### Task 6: Ports and application services

Use cases become pure orchestration. Tested against hand-written fakes, not mocks.

**Files:**
- Create: `src/main/java/com/elibrary/lending/domain/LoanRepository.java`
- Create: `src/main/java/com/elibrary/lending/domain/BookInventory.java`
- Create: `src/main/java/com/elibrary/lending/domain/LoanStatusFilter.java`
- Create: `src/main/java/com/elibrary/lending/domain/BookNotFound.java`
- Create: `src/main/java/com/elibrary/lending/domain/BookUnavailable.java`
- Create: `src/main/java/com/elibrary/lending/domain/LoanNotFound.java`
- Create: `src/main/java/com/elibrary/lending/application/BorrowBook.java`
- Create: `src/main/java/com/elibrary/lending/application/ReturnBook.java`
- Create: `src/main/java/com/elibrary/lending/application/ViewLoans.java`
- Test: `src/test/java/com/elibrary/lending/application/InMemoryLoanRepository.java`
- Test: `src/test/java/com/elibrary/lending/application/InMemoryBookInventory.java`
- Test: `src/test/java/com/elibrary/lending/application/BorrowBookTest.java`
- Test: `src/test/java/com/elibrary/lending/application/ReturnBookTest.java`
- Test: `src/test/java/com/elibrary/lending/application/ViewLoansTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Produces:
  - `LoanRepository` — `ActiveLoans activeFor(MemberId)`, `Optional<Loan> findById(LoanId)`, `Loan save(Loan)`, `PageResult<Loan> findFor(MemberId, LoanStatusFilter, int page, int size)`.
  - `BookInventory` — `void checkout(BookId)` (throws `BookNotFound`, `BookUnavailable`), `void restore(BookId)` (throws `BookNotFound`).
  - `LoanStatusFilter` — `enum { ACTIVE, RETURNED, ALL }`.
  - `BookNotFound` / `LoanNotFound` — `NotFoundException`, codes `BOOK_NOT_FOUND` / `LOAN_NOT_FOUND`.
  - `BookUnavailable` — `ConflictException`, code `NO_COPIES_AVAILABLE`.
  - `BorrowBook` — `Loan handle(MemberId, BookId)`.
  - `ReturnBook` — `Loan handle(MemberId, LoanId)`.
  - `ViewLoans` — `PageResult<Loan> handle(MemberId, LoanStatusFilter, int page, int size)`, `Loan byId(MemberId, LoanId)`.

- [ ] **Step 1: Write the ports and remaining exceptions**

`src/main/java/com/elibrary/lending/domain/LoanStatusFilter.java`

```java
package com.elibrary.lending.domain;

public enum LoanStatusFilter {
    ACTIVE, RETURNED, ALL
}
```

`src/main/java/com/elibrary/lending/domain/LoanRepository.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;

import java.util.Optional;

/** Port owned by the domain; implemented by lending.internal over JPA. */
public interface LoanRepository {

    ActiveLoans activeFor(MemberId memberId);

    Optional<Loan> findById(LoanId id);

    Loan save(Loan loan);

    PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size);
}
```

`src/main/java/com/elibrary/lending/domain/BookInventory.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;

/**
 * Port owned by the domain, implemented by the catalogue.
 *
 * <p>The catalogue is the single authority on copy availability, so lending never computes
 * it. Availability is {@code totalCopies − activeLoans}, a figure sourced from two modules;
 * making the catalogue own the running total keeps the {@code available=true} browse filter
 * a single-table query and keeps paging correct.
 *
 * <p>Both operations participate in the caller's transaction. In a single-database modular
 * monolith a local transaction is the correct, simple answer; this interface is exactly the
 * seam where a saga with a compensating action would go if the modules became services.
 */
public interface BookInventory {

    /** Takes one copy. Throws {@link BookNotFound} or {@link BookUnavailable}. */
    void checkout(BookId bookId);

    /** Gives one copy back. Throws {@link BookNotFound}. */
    void restore(BookId bookId);
}
```

`src/main/java/com/elibrary/lending/domain/BookNotFound.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.error.NotFoundException;

public class BookNotFound extends NotFoundException {

    public BookNotFound(BookId bookId) {
        super("BOOK_NOT_FOUND", "No book with id " + bookId + " exists.");
    }
}
```

`src/main/java/com/elibrary/lending/domain/BookUnavailable.java`

```java
package com.elibrary.lending.domain;

import com.elibrary.shared.BookId;
import com.elibrary.shared.error.ConflictException;

public class BookUnavailable extends ConflictException {

    public BookUnavailable(BookId bookId) {
        super("NO_COPIES_AVAILABLE", "Book " + bookId + " has no copies available.");
    }
}
```

`src/main/java/com/elibrary/lending/domain/LoanNotFound.java`

```java
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
```

- [ ] **Step 2: Write the fakes**

`src/test/java/com/elibrary/lending/application/InMemoryLoanRepository.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fake, not a mock. Repository mocks assert interaction order and couple tests to
 * implementation detail; this asserts behaviour and survives refactoring. Mockito is
 * still the right tool where the interaction itself is the subject.
 */
class InMemoryLoanRepository implements LoanRepository {

    private final Map<LoanId, Loan> store = new LinkedHashMap<>();

    @Override
    public ActiveLoans activeFor(MemberId memberId) {
        return new ActiveLoans(memberId, store.values().stream()
                .filter(loan -> loan.belongsTo(memberId))
                .filter(Loan::isActive)
                .toList());
    }

    @Override
    public Optional<Loan> findById(LoanId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Loan save(Loan loan) {
        store.put(loan.id(), loan);
        return loan;
    }

    @Override
    public PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size) {
        List<Loan> matching = store.values().stream()
                .filter(loan -> loan.belongsTo(memberId))
                .filter(loan -> switch (filter) {
                    case ACTIVE -> loan.isActive();
                    case RETURNED -> !loan.isActive();
                    case ALL -> true;
                })
                .sorted(Comparator.comparing(Loan::borrowedAt).reversed())
                .toList();

        List<Loan> window = matching.stream().skip((long) page * size).limit(size).toList();
        return PageResult.of(window, page, size, matching.size());
    }

    void seed(Loan... loans) {
        for (Loan loan : loans) {
            store.put(loan.id(), loan);
        }
    }
}
```

`src/test/java/com/elibrary/lending/application/InMemoryBookInventory.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.BookNotFound;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;

import java.util.HashMap;
import java.util.Map;

class InMemoryBookInventory implements BookInventory {

    private final Map<BookId, Integer> availableCopies = new HashMap<>();

    @Override
    public void checkout(BookId bookId) {
        Integer available = availableCopies.get(bookId);
        if (available == null) {
            throw new BookNotFound(bookId);
        }
        if (available <= 0) {
            throw new BookUnavailable(bookId);
        }
        availableCopies.put(bookId, available - 1);
    }

    @Override
    public void restore(BookId bookId) {
        Integer available = availableCopies.get(bookId);
        if (available == null) {
            throw new BookNotFound(bookId);
        }
        availableCopies.put(bookId, available + 1);
    }

    void stock(BookId bookId, int copies) {
        availableCopies.put(bookId, copies);
    }

    int availableCopiesOf(BookId bookId) {
        return availableCopies.getOrDefault(bookId, 0);
    }
}
```

- [ ] **Step 3: Write the failing use case tests**

`src/test/java/com/elibrary/lending/application/BorrowBookTest.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.AlreadyBorrowed;
import com.elibrary.lending.domain.BookNotFound;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanLimitReached;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BorrowBookTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private InMemoryBookInventory inventory;
    private BorrowBook borrowBook;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        inventory = new InMemoryBookInventory();
        borrowBook = new BorrowBook(loans, inventory, new LendingPolicy(2, 14), CLOCK);
    }

    @Test
    void borrowingOpensALoanAndTakesOneCopy() {
        inventory.stock(DDD, 3);

        Loan loan = borrowBook.handle(ALICE, DDD);

        assertThat(loan.isActive()).isTrue();
        assertThat(loan.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(2);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ACTIVE, 0, 10).items()).containsExactly(loan);
    }

    @Test
    void propagatesUnavailabilityFromTheCatalogue() {
        inventory.stock(DDD, 0);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(BookUnavailable.class);
    }

    @Test
    void propagatesAnUnknownBook() {
        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(BookNotFound.class);
    }

    @Test
    void doesNotTakeACopyWhenTheMemberSideRulesRejectTheBorrow() {
        inventory.stock(DDD, 3);
        borrowBook.handle(ALICE, DDD);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, DDD)).isInstanceOf(AlreadyBorrowed.class);
        assertThat(inventory.availableCopiesOf(DDD))
                .as("member-side checks run before the copy is taken")
                .isEqualTo(2);
    }

    @Test
    void rejectsBorrowingBeyondTheConfiguredLimit() {
        inventory.stock(DDD, 3);
        inventory.stock(REFACTORING, 3);
        BookId third = BookId.of("11111111-1111-1111-1111-111111111107");
        inventory.stock(third, 3);

        borrowBook.handle(ALICE, DDD);
        borrowBook.handle(ALICE, REFACTORING);

        assertThatThrownBy(() -> borrowBook.handle(ALICE, third)).isInstanceOf(LoanLimitReached.class);
        assertThat(inventory.availableCopiesOf(third)).isEqualTo(3);
    }

    @Test
    void aReturnedLoanDoesNotCountTowardsTheLimit() {
        inventory.stock(DDD, 3);
        Loan first = borrowBook.handle(ALICE, DDD);
        loans.save(first.returnNow(CLOCK));

        assertThat(borrowBook.handle(ALICE, DDD)).isNotNull();
    }
}
```

`src/test/java/com/elibrary/lending/application/ReturnBookTest.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanAlreadyReturned;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnBookTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock RETURNED = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private InMemoryBookInventory inventory;
    private ReturnBook returnBook;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        inventory = new InMemoryBookInventory();
        inventory.stock(DDD, 2);
        returnBook = new ReturnBook(loans, inventory, RETURNED);
    }

    @Test
    void returningClosesTheLoanAndGivesTheCopyBack() {
        Loan open = Loan.open(ALICE, DDD, POLICY, BORROWED);
        loans.seed(open);

        Loan closed = returnBook.handle(ALICE, open.id());

        assertThat(closed.isActive()).isFalse();
        assertThat(closed.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(3);
    }

    @Test
    void returningAnUnknownLoanIsNotFound() {
        assertThatThrownBy(() -> returnBook.handle(ALICE, LoanId.newId()))
                .isInstanceOf(LoanNotFound.class);
    }

    @Test
    void anotherMembersLoanIsReportedAsNotFoundRatherThanForbidden() {
        Loan bobs = Loan.open(BOB, DDD, POLICY, BORROWED);
        loans.seed(bobs);

        assertThatThrownBy(() -> returnBook.handle(ALICE, bobs.id()))
                .as("404 rather than 403 so existence is not leaked")
                .isInstanceOf(LoanNotFound.class);
        assertThat(inventory.availableCopiesOf(DDD)).isEqualTo(2);
    }

    @Test
    void returningTwiceIsRejectedAndDoesNotInflateTheCopyCount() {
        Loan open = Loan.open(ALICE, DDD, POLICY, BORROWED);
        loans.seed(open);
        returnBook.handle(ALICE, open.id());

        assertThatThrownBy(() -> returnBook.handle(ALICE, open.id()))
                .isInstanceOf(LoanAlreadyReturned.class);
        assertThat(inventory.availableCopiesOf(DDD))
                .as("the copy must not be restored twice")
                .isEqualTo(3);
    }
}
```

`src/test/java/com/elibrary/lending/application/ViewLoansTest.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewLoansTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryLoanRepository loans;
    private ViewLoans viewLoans;

    @BeforeEach
    void setUp() {
        loans = new InMemoryLoanRepository();
        viewLoans = new ViewLoans(loans);
    }

    @Test
    void activeFilterExcludesReturnedLoansAndOtherMembers() {
        Loan aliceActive = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        Loan aliceReturned = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK).returnNow(CLOCK);
        Loan bobsActive = Loan.open(BOB, BookId.newId(), POLICY, CLOCK);
        loans.seed(aliceActive, aliceReturned, bobsActive);

        PageResult<Loan> result = viewLoans.handle(ALICE, LoanStatusFilter.ACTIVE, 0, 20);

        assertThat(result.items()).containsExactly(aliceActive);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void allFilterReturnsBothStatesForTheCallerOnly() {
        Loan active = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        Loan returned = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK).returnNow(CLOCK);
        loans.seed(active, returned, Loan.open(BOB, BookId.newId(), POLICY, CLOCK));

        assertThat(viewLoans.handle(ALICE, LoanStatusFilter.ALL, 0, 20).items())
                .containsExactlyInAnyOrder(active, returned);
    }

    @Test
    void byIdReturnsTheCallersLoan() {
        Loan loan = Loan.open(ALICE, BookId.newId(), POLICY, CLOCK);
        loans.seed(loan);

        assertThat(viewLoans.byId(ALICE, loan.id())).isEqualTo(loan);
    }

    @Test
    void byIdHidesAnotherMembersLoanBehindNotFound() {
        Loan bobs = Loan.open(BOB, BookId.newId(), POLICY, CLOCK);
        loans.seed(bobs);

        assertThatThrownBy(() -> viewLoans.byId(ALICE, bobs.id())).isInstanceOf(LoanNotFound.class);
    }

    @Test
    void byIdReportsAnUnknownLoanAsNotFound() {
        assertThatThrownBy(() -> viewLoans.byId(ALICE, LoanId.newId())).isInstanceOf(LoanNotFound.class);
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='BorrowBookTest,ReturnBookTest,ViewLoansTest'`
Expected: FAIL — `cannot find symbol: class BorrowBook`.

- [ ] **Step 5: Write the use cases**

`src/main/java/com/elibrary/lending/application/BorrowBook.java`

```java
package com.elibrary.lending.application;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Orchestration only: every business rule lives in the domain or in the catalogue. */
@Service
public class BorrowBook {

    private final LoanRepository loans;
    private final BookInventory inventory;
    private final LendingPolicy policy;
    private final Clock clock;

    public BorrowBook(LoanRepository loans, BookInventory inventory, LendingPolicy policy, Clock clock) {
        this.loans = loans;
        this.inventory = inventory;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Ordering is deliberate. The member-side checks are cheap and take no lock, so they run
     * first; {@code checkout} acquires a pessimistic write lock on the book row and is last,
     * which keeps the lock held for the shortest possible window. Both steps share one local
     * transaction, so a failure after checkout rolls the copy back.
     */
    @Transactional
    public Loan handle(MemberId memberId, BookId bookId) {
        ActiveLoans position = loans.activeFor(memberId);
        Loan loan = position.borrow(bookId, policy, clock);
        inventory.checkout(bookId);
        return loans.save(loan);
    }
}
```

`src/main/java/com/elibrary/lending/application/ReturnBook.java`

```java
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
```

`returnNow` runs before `restore`, so a double return throws before the copy count can be inflated.

`src/main/java/com/elibrary/lending/application/ViewLoans.java`

```java
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
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='BorrowBookTest,ReturnBookTest,ViewLoansTest'`
Expected: PASS, 15 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/elibrary/lending src/test/java/com/elibrary/lending/application
git commit -m "feat(lending): add ports and borrow/return/view use cases

Ports are defined by the consumer; the catalogue implements BookInventory.
Use cases hold no business rules. Borrow runs cheap member-side checks before
taking the inventory lock; return closes the loan before restoring the copy so
a double return cannot inflate availability. Tested against hand-written fakes
rather than repository mocks."
```

---

### Task 7: Catalogue — public read API and JPA implementation

**Spec correction:** spec §3.1 splits the catalogue into `query`, `store`, and `spi` packages while §3.1 also requires those classes be package-private. Java visibility is per-package, so `catalog.query` reading `catalog.store`'s repository would force both public and the boundary would exist in name only. Internals therefore collapse into a single `catalog.internal` package. The public surface is the handful of types in `com.elibrary.catalog`.

**Files:**
- Create: `src/main/java/com/elibrary/catalog/ContentKind.java`
- Create: `src/main/java/com/elibrary/catalog/BookSummary.java`
- Create: `src/main/java/com/elibrary/catalog/BookDetail.java`
- Create: `src/main/java/com/elibrary/catalog/BookSortField.java`
- Create: `src/main/java/com/elibrary/catalog/BookSearchCriteria.java`
- Create: `src/main/java/com/elibrary/catalog/BookCatalog.java`
- Create: `src/main/java/com/elibrary/catalog/internal/BookRecord.java`
- Create: `src/main/java/com/elibrary/catalog/internal/BookJpaRepository.java`
- Create: `src/main/java/com/elibrary/catalog/internal/JpaBookCatalog.java`
- Test: `src/test/java/com/elibrary/catalog/internal/JpaBookCatalogTest.java`

**Interfaces:**
- Consumes: `BookId`, `Isbn`, `PageResult` (Task 2).
- Produces:
  - `ContentKind` — `enum { BOOK, JOURNAL }`.
  - `BookSummary` — `record BookSummary(BookId id, String title, String author, ContentKind kind, int totalCopies, int availableCopies)` with `boolean available()`.
  - `BookDetail` — `record BookDetail(BookId id, String title, String author, Isbn isbn, ContentKind kind, String publisher, Integer publicationYear, String volume, String issue, int totalCopies, int availableCopies)`.
  - `BookSortField` — `enum { TITLE, AUTHOR, PUBLICATION_YEAR }` with `String property()`.
  - `BookSearchCriteria` — `record BookSearchCriteria(String q, String author, boolean availableOnly, BookSortField sortField, boolean ascending, int page, int size)`, with static `BookSearchCriteria of(...)` that applies defaults and clamps `size`.
  - `BookCatalog` — `PageResult<BookSummary> search(BookSearchCriteria)`, `Optional<BookDetail> findById(BookId)`, `List<BookSummary> summariesFor(Collection<BookId>)`.
  - Package-private `BookRecord` with `checkoutCopy()` / `restoreCopy()`, used by Task 8.

- [ ] **Step 1: Write the public read model**

`src/main/java/com/elibrary/catalog/ContentKind.java`

```java
package com.elibrary.catalog;

/**
 * Journals are modelled as a kind of catalogue item rather than a separate type.
 * A sealed Book/Journal hierarchy would add JPA inheritance mapping and a polymorphic
 * response shape without protecting any invariant that differs between the two.
 */
public enum ContentKind {
    BOOK, JOURNAL
}
```

`src/main/java/com/elibrary/catalog/BookSummary.java`

```java
package com.elibrary.catalog;

import com.elibrary.shared.BookId;

/** Read model for list views. This is also the JSON shape: the catalogue is CQRS-lite. */
public record BookSummary(
        BookId id,
        String title,
        String author,
        ContentKind kind,
        int totalCopies,
        int availableCopies) {

    public boolean available() {
        return availableCopies > 0;
    }
}
```

`src/main/java/com/elibrary/catalog/BookDetail.java`

```java
package com.elibrary.catalog;

import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;

/** Read model for the detail view. Journal-only fields are null for books. */
public record BookDetail(
        BookId id,
        String title,
        String author,
        Isbn isbn,
        ContentKind kind,
        String publisher,
        Integer publicationYear,
        String volume,
        String issue,
        int totalCopies,
        int availableCopies) {

    public boolean available() {
        return availableCopies > 0;
    }
}
```

`src/main/java/com/elibrary/catalog/BookSortField.java`

```java
package com.elibrary.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * Sortable fields, whitelisted. Passing a client string straight into Spring's
 * {@code Sort} lets a caller order by any persistent property, which leaks the
 * persistence model and can be used to probe it. An enum makes the sortable surface
 * an explicit API decision.
 */
public enum BookSortField {

    TITLE("title"),
    AUTHOR("author"),
    PUBLICATION_YEAR("publicationYear");

    private final String property;

    BookSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static Optional<BookSortField> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (BookSortField field : values()) {
            if (field.name().equals(normalised) || field.property.equalsIgnoreCase(raw.trim())) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }
}
```

`src/main/java/com/elibrary/catalog/BookSearchCriteria.java`

```java
package com.elibrary.catalog;

/** Browse inputs, already defaulted and clamped so no adapter has to repeat the rules. */
public record BookSearchCriteria(
        String q,
        String author,
        boolean availableOnly,
        BookSortField sortField,
        boolean ascending,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static BookSearchCriteria of(String q, String author, boolean availableOnly,
                                        BookSortField sortField, boolean ascending,
                                        Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int requested = size == null || size < 1 ? DEFAULT_SIZE : size;
        return new BookSearchCriteria(
                blankToNull(q),
                blankToNull(author),
                availableOnly,
                sortField == null ? BookSortField.TITLE : sortField,
                ascending,
                safePage,
                Math.min(requested, MAX_SIZE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
```

Clamping `size` at 100 stops a caller from asking for the whole table in one request.

`src/main/java/com/elibrary/catalog/BookCatalog.java`

```java
package com.elibrary.catalog;

import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The catalogue's entire public surface. Everything else in the module is package-private. */
public interface BookCatalog {

    PageResult<BookSummary> search(BookSearchCriteria criteria);

    Optional<BookDetail> findById(BookId id);

    /**
     * Batch lookup so callers enriching a list of loans issue one query rather than one
     * per row. Ids with no matching book are simply absent from the result.
     */
    List<BookSummary> summariesFor(Collection<BookId> ids);
}
```

- [ ] **Step 2: Write the failing repository test**

`src/test/java/com/elibrary/catalog/internal/JpaBookCatalogTest.java`

```java
package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaBookCatalog.class)
class JpaBookCatalogTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId LEGACY_CODE = BookId.of("11111111-1111-1111-1111-111111111109");

    @Autowired
    private BookCatalog catalog;

    private static BookSearchCriteria criteria(String q, String author, boolean availableOnly) {
        return BookSearchCriteria.of(q, author, availableOnly, BookSortField.TITLE, true, 0, 50);
    }

    @Test
    void returnsTheWholeSeededCatalogueByDefault() {
        PageResult<BookSummary> result = catalog.search(criteria(null, null, false));
        assertThat(result.totalElements()).isEqualTo(15);
    }

    @Test
    void matchesTheFreeTextTermAgainstTitleAndAuthorCaseInsensitively() {
        assertThat(catalog.search(criteria("DOMAIN-DRIVEN", null, false)).items())
                .extracting(BookSummary::title)
                .contains("Domain-Driven Design", "Implementing Domain-Driven Design");

        assertThat(catalog.search(criteria("fowler", null, false)).items())
                .extracting(BookSummary::author)
                .containsOnly("Martin Fowler");
    }

    @Test
    void filtersByAuthorSeparatelyFromTheFreeTextTerm() {
        assertThat(catalog.search(criteria(null, "goetz", false)).items())
                .extracting(BookSummary::title)
                .containsExactly("Java Concurrency in Practice");
    }

    @Test
    void availableOnlyFiltersInTheQuerySoPagingStaysCorrect() {
        PageResult<BookSummary> result = catalog.search(criteria(null, null, true));

        assertThat(result.totalElements())
                .as("two seeded items have zero available copies")
                .isEqualTo(13);
        assertThat(result.items()).allMatch(BookSummary::available);
        assertThat(result.items()).extracting(BookSummary::id).doesNotContain(LEGACY_CODE);
    }

    @Test
    void sortsByTheWhitelistedFieldInBothDirections() {
        List<String> ascending = catalog.search(
                        BookSearchCriteria.of(null, null, false, BookSortField.TITLE, true, 0, 50))
                .items().stream().map(BookSummary::title).toList();
        List<String> descending = catalog.search(
                        BookSearchCriteria.of(null, null, false, BookSortField.TITLE, false, 0, 50))
                .items().stream().map(BookSummary::title).toList();

        assertThat(ascending).isSorted();
        assertThat(descending).isEqualTo(ascending.reversed());
    }

    @Test
    void pagesWithACorrectTotalAndPageCount() {
        PageResult<BookSummary> firstPage = catalog.search(
                BookSearchCriteria.of(null, null, false, BookSortField.TITLE, true, 0, 6));

        assertThat(firstPage.items()).hasSize(6);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(6);
        assertThat(firstPage.totalElements()).isEqualTo(15);
        assertThat(firstPage.totalPages()).isEqualTo(3);
    }

    @Test
    void findsDetailIncludingJournalOnlyFields() {
        assertThat(catalog.findById(BookId.of("11111111-1111-1111-1111-111111111112")))
                .get()
                .satisfies(detail -> {
                    assertThat(detail.kind()).isEqualTo(ContentKind.JOURNAL);
                    assertThat(detail.volume()).isEqualTo("68");
                    assertThat(detail.issue()).isEqualTo("9");
                    assertThat(detail.publisher()).isEqualTo("ACM");
                });
    }

    @Test
    void detailIsEmptyForAnUnknownId() {
        assertThat(catalog.findById(BookId.newId())).isEmpty();
    }

    @Test
    void batchSummariesReturnOneRowPerKnownIdAndSkipUnknownOnes() {
        List<BookSummary> summaries = catalog.summariesFor(List.of(DDD, LEGACY_CODE, BookId.newId()));

        assertThat(summaries).extracting(BookSummary::id).containsExactlyInAnyOrder(DDD, LEGACY_CODE);
    }

    @Test
    void batchSummariesForAnEmptyRequestDoNotHitTheDatabase() {
        assertThat(catalog.summariesFor(List.of())).isEmpty();
    }
}
```

`@DataJpaTest` runs Flyway, so the seeded catalogue from Task 1 is present and the counts above are stable.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q test -Dtest=JpaBookCatalogTest`
Expected: FAIL — `cannot find symbol: class JpaBookCatalog`.

- [ ] **Step 4: Write the entity**

`src/main/java/com/elibrary/catalog/internal/BookRecord.java`

```java
package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Persistence shape of a catalogue item, and the authority on copy availability.
 *
 * <p>The copy guards live on the record rather than in a service because availability is
 * the one piece of state the catalogue genuinely owns. The database enforces the same rule
 * via {@code chk_books_available_copies}, so even a write that bypasses this class cannot
 * drive availability out of range.
 */
@Entity
@Table(name = "books")
class BookRecord {

    @Id
    private UUID id;

    private String title;

    private String author;

    private String isbn;

    @Enumerated(EnumType.STRING)
    private ContentKind kind;

    private String publisher;

    @Column(name = "publication_year")
    private Integer publicationYear;

    private String volume;

    private String issue;

    @Column(name = "total_copies")
    private int totalCopies;

    @Column(name = "available_copies")
    private int availableCopies;

    @Version
    private long version;

    protected BookRecord() {
        // required by JPA
    }

    void checkoutCopy() {
        if (availableCopies <= 0) {
            throw new BookUnavailable(new BookId(id));
        }
        availableCopies--;
    }

    void restoreCopy() {
        if (availableCopies >= totalCopies) {
            throw new IllegalStateException(
                    "Cannot restore a copy of book " + id + ": all " + totalCopies + " copies are already present.");
        }
        availableCopies++;
    }

    BookSummary toSummary() {
        return new BookSummary(new BookId(id), title, author, kind, totalCopies, availableCopies);
    }

    BookDetail toDetail() {
        return new BookDetail(new BookId(id), title, author, Isbn.of(isbn), kind,
                publisher, publicationYear, volume, issue, totalCopies, availableCopies);
    }

    int availableCopies() {
        return availableCopies;
    }

    int totalCopies() {
        return totalCopies;
    }
}
```

- [ ] **Step 5: Write the repository**

`src/main/java/com/elibrary/catalog/internal/BookJpaRepository.java`

```java
package com.elibrary.catalog.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BookJpaRepository extends JpaRepository<BookRecord, UUID> {

    /**
     * Free-text and attribute search. Every filter is applied in SQL, including
     * availability, so the paging window and totals are correct. Terms arrive
     * pre-lowercased and pre-wrapped in wildcards to keep the JPQL portable.
     */
    @Query("""
            select b from BookRecord b
            where (:q is null or lower(b.title) like :q or lower(b.author) like :q)
              and (:author is null or lower(b.author) like :author)
              and (:availableOnly = false or b.availableCopies > 0)
            """)
    Page<BookRecord> search(@Param("q") String q,
                            @Param("author") String author,
                            @Param("availableOnly") boolean availableOnly,
                            Pageable pageable);

    List<BookRecord> findByIdIn(Collection<UUID> ids);

    /**
     * Reads the row under a pessimistic write lock for the duration of the transaction.
     * Borrowing contends on a single row for a very short transaction, which is exactly
     * where pessimistic locking beats optimistic retry: the loser waits briefly instead of
     * receiving a spurious conflict on work that would have succeeded.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookRecord b where b.id = :id")
    Optional<BookRecord> findByIdForUpdate(@Param("id") UUID id);
}
```

- [ ] **Step 6: Write the read adapter**

`src/main/java/com/elibrary/catalog/internal/JpaBookCatalog.java`

```java
package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSummary;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class JpaBookCatalog implements BookCatalog {

    private final BookJpaRepository books;

    JpaBookCatalog(BookJpaRepository books) {
        this.books = books;
    }

    @Override
    public PageResult<BookSummary> search(BookSearchCriteria criteria) {
        Sort sort = Sort.by(
                criteria.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                criteria.sortField().property());

        Page<BookRecord> page = books.search(
                like(criteria.q()),
                like(criteria.author()),
                criteria.availableOnly(),
                PageRequest.of(criteria.page(), criteria.size(), sort));

        return PageResult.of(
                page.getContent().stream().map(BookRecord::toSummary).toList(),
                criteria.page(),
                criteria.size(),
                page.getTotalElements());
    }

    @Override
    public Optional<BookDetail> findById(BookId id) {
        return books.findById(id.value()).map(BookRecord::toDetail);
    }

    @Override
    public List<BookSummary> summariesFor(Collection<BookId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> raw = ids.stream().map(BookId::value).distinct().toList();
        return books.findByIdIn(raw).stream().map(BookRecord::toSummary).toList();
    }

    private static String like(String term) {
        return term == null ? null : "%" + term.toLowerCase(Locale.ROOT) + "%";
    }
}
```

Entities are loaded and mapped in Java rather than projected in JPQL. A constructor expression cannot build `BookId`/`Isbn` value objects, and at this row width the saving would be negligible; on a wide table a dedicated projection would be worth the extra type. This belongs in the README as a stated trade-off rather than an unexamined default.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -q test -Dtest=JpaBookCatalogTest`
Expected: PASS, 10 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/elibrary/catalog src/test/java/com/elibrary/catalog
git commit -m "feat(catalog): add public read API over a package-private JPA internal

Catalogue internals live in one package so package-private visibility actually
enforces the boundary. Search applies every filter in SQL, including
availability, so paging totals are correct. Sortable fields are whitelisted by
an enum rather than passing a client string into Spring's Sort."
```

---

### Task 8: Catalogue as the availability authority

Implements lending's `BookInventory` port under a pessimistic write lock.

**Files:**
- Create: `src/main/java/com/elibrary/catalog/internal/CatalogBookInventory.java`
- Test: `src/test/java/com/elibrary/catalog/internal/CatalogBookInventoryTest.java`

**Interfaces:**
- Consumes: `BookInventory`, `BookNotFound`, `BookUnavailable` (Task 6); `BookJpaRepository`, `BookRecord` (Task 7).
- Produces: a `BookInventory` bean, satisfying the dependency `BorrowBook` and `ReturnBook` declare.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/catalog/internal/CatalogBookInventoryTest.java`

```java
package com.elibrary.catalog.internal;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.BookNotFound;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({JpaBookCatalog.class, CatalogBookInventory.class})
class CatalogBookInventoryTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId TIDY_FIRST = BookId.of("11111111-1111-1111-1111-111111111111");
    private static final BookId ACCELERATE = BookId.of("11111111-1111-1111-1111-111111111110");

    @Autowired
    private BookInventory inventory;

    @Autowired
    private BookCatalog catalog;

    private int availableCopiesOf(BookId id) {
        return catalog.findById(id).orElseThrow().availableCopies();
    }

    @Test
    void checkoutTakesExactlyOneCopy() {
        int before = availableCopiesOf(DDD);

        inventory.checkout(DDD);

        assertThat(availableCopiesOf(DDD)).isEqualTo(before - 1);
    }

    @Test
    void checkoutOfTheLastCopyLeavesTheBookUnavailable() {
        inventory.checkout(TIDY_FIRST);

        assertThat(availableCopiesOf(TIDY_FIRST)).isZero();
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().available()).isFalse();
    }

    @Test
    void checkoutOfAFullyBorrowedBookIsRejected() {
        assertThatThrownBy(() -> inventory.checkout(ACCELERATE))
                .isInstanceOf(BookUnavailable.class)
                .satisfies(e -> assertThat(((BookUnavailable) e).code()).isEqualTo("NO_COPIES_AVAILABLE"));
    }

    @Test
    void checkoutOfAnUnknownBookIsNotFound() {
        assertThatThrownBy(() -> inventory.checkout(BookId.newId()))
                .isInstanceOf(BookNotFound.class)
                .satisfies(e -> assertThat(((BookNotFound) e).code()).isEqualTo("BOOK_NOT_FOUND"));
    }

    @Test
    void restoreGivesTheCopyBack() {
        inventory.checkout(DDD);
        int afterCheckout = availableCopiesOf(DDD);

        inventory.restore(DDD);

        assertThat(availableCopiesOf(DDD)).isEqualTo(afterCheckout + 1);
    }

    @Test
    void restoreBeyondTheTotalNumberOfCopiesIsRejected() {
        assertThatThrownBy(() -> inventory.restore(DDD))
                .as("no copy is out, so there is nothing to give back")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoreOfAnUnknownBookIsNotFound() {
        assertThatThrownBy(() -> inventory.restore(BookId.newId())).isInstanceOf(BookNotFound.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=CatalogBookInventoryTest`
Expected: FAIL — `cannot find symbol: class CatalogBookInventory`.

- [ ] **Step 3: Write the adapter**

`src/main/java/com/elibrary/catalog/internal/CatalogBookInventory.java`

```java
package com.elibrary.catalog.internal;

import com.elibrary.lending.domain.BookInventory;
import com.elibrary.lending.domain.BookNotFound;
import com.elibrary.shared.BookId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's side of the availability contract.
 *
 * <p>Both operations require an existing transaction, which is always the caller's use-case
 * transaction. That is what makes the two-step borrow (take a copy, record the loan) atomic
 * in a single-database monolith: a failure after checkout rolls the copy back with no
 * compensating action. {@code MANDATORY} rather than {@code REQUIRED} makes that requirement
 * loud — calling this outside a transaction fails immediately instead of silently committing
 * half the work.
 */
@Service
class CatalogBookInventory implements BookInventory {

    private final BookJpaRepository books;

    CatalogBookInventory(BookJpaRepository books) {
        this.books = books;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkout(BookId bookId) {
        lock(bookId).checkoutCopy();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(BookId bookId) {
        lock(bookId).restoreCopy();
    }

    private BookRecord lock(BookId bookId) {
        return books.findByIdForUpdate(bookId.value()).orElseThrow(() -> new BookNotFound(bookId));
    }
}
```

`Propagation.MANDATORY` means the test above must supply a transaction. `@DataJpaTest` is transactional by default, so the test methods already run inside one.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=CatalogBookInventoryTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Verify the whole unit suite still passes**

Run: `mvn -q test`
Expected: PASS. This is the first point where the lending use cases have a real `BookInventory` bean available.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/elibrary/catalog/internal/CatalogBookInventory.java \
        src/test/java/com/elibrary/catalog/internal/CatalogBookInventoryTest.java
git commit -m "feat(catalog): implement BookInventory under a pessimistic write lock

The catalogue is the single authority on copy availability. Checkout reads the
book row FOR UPDATE, which is the right trade-off for a very short transaction
contending on one row: the loser waits rather than getting a spurious conflict.
Propagation.MANDATORY makes the shared-transaction requirement explicit."
```

---

### Task 9: Lending persistence adapter

The `Loan` aggregate never touches JPA. A separate entity plus an explicit mapper is the price of that purity, and it is worth paying here because this is where the invariants live.

**Files:**
- Create: `src/main/java/com/elibrary/lending/internal/LoanEntity.java`
- Create: `src/main/java/com/elibrary/lending/internal/LoanJpaRepository.java`
- Create: `src/main/java/com/elibrary/lending/internal/LoanMapper.java`
- Create: `src/main/java/com/elibrary/lending/internal/JpaLoanRepository.java`
- Test: `src/test/java/com/elibrary/lending/internal/JpaLoanRepositoryTest.java`

**Interfaces:**
- Consumes: `LoanRepository`, `Loan`, `LoanId`, `ActiveLoans`, `LoanStatusFilter` (Tasks 4–6).
- Produces: a `LoanRepository` bean backed by the `loans` table.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/lending/internal/JpaLoanRepositoryTest.java`

```java
package com.elibrary.lending.internal;

import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaLoanRepository.class)
class JpaLoanRepositoryTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final MemberId BOB = new MemberId("bob");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final BookId REFACTORING = BookId.of("11111111-1111-1111-1111-111111111104");
    private static final BookId EFFECTIVE_JAVA = BookId.of("11111111-1111-1111-1111-111111111107");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);

    private static Clock at(String isoDate) {
        return Clock.fixed(Instant.parse(isoDate + "T10:00:00Z"), ZoneOffset.UTC);
    }

    @Autowired
    private LoanRepository loans;

    @Test
    void savesAndReloadsEveryField() {
        Loan saved = loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));

        assertThat(loans.findById(saved.id())).get().satisfies(reloaded -> {
            assertThat(reloaded.id()).isEqualTo(saved.id());
            assertThat(reloaded.memberId()).isEqualTo(ALICE);
            assertThat(reloaded.bookId()).isEqualTo(DDD);
            assertThat(reloaded.borrowedAt()).isEqualTo(Instant.parse("2026-09-30T10:00:00Z"));
            assertThat(reloaded.dueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(reloaded.returnedAt()).isNull();
            assertThat(reloaded.isActive()).isTrue();
        });
    }

    @Test
    void savingAReturnedLoanUpdatesTheExistingRowRatherThanInsertingAnother() {
        Loan open = loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));

        loans.save(open.returnNow(at("2026-10-02")));

        assertThat(loans.findById(open.id())).get().satisfies(reloaded -> {
            assertThat(reloaded.isActive()).isFalse();
            assertThat(reloaded.returnedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        });
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void findByIdIsEmptyForAnUnknownLoan() {
        assertThat(loans.findById(LoanId.newId())).isEmpty();
    }

    @Test
    void activeForReturnsOnlyTheMembersOpenLoans() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));
        Loan toReturn = loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-30")));
        loans.save(toReturn.returnNow(at("2026-10-01")));
        loans.save(Loan.open(BOB, EFFECTIVE_JAVA, POLICY, at("2026-09-30")));

        assertThat(loans.activeFor(ALICE).count()).isEqualTo(1);
        assertThat(loans.activeFor(ALICE).all()).allMatch(loan -> loan.isFor(DDD));
        assertThat(loans.activeFor(BOB).count()).isEqualTo(1);
    }

    @Test
    void activeForAMemberWithNoLoansIsAnEmptyPosition() {
        assertThat(loans.activeFor(ALICE).count()).isZero();
        assertThat(loans.activeFor(ALICE).memberId()).isEqualTo(ALICE);
    }

    @Test
    void findForHonoursTheStatusFilter() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-30")));
        Loan returned = loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-28")));
        loans.save(returned.returnNow(at("2026-09-29")));

        assertThat(loans.findFor(ALICE, LoanStatusFilter.ACTIVE, 0, 20).totalElements()).isEqualTo(1);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.RETURNED, 0, 20).totalElements()).isEqualTo(1);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void findForNeverLeaksAnotherMembersLoans() {
        loans.save(Loan.open(BOB, DDD, POLICY, at("2026-09-30")));

        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 20).items()).isEmpty();
    }

    @Test
    void findForReturnsMostRecentlyBorrowedFirstAndPagesCorrectly() {
        loans.save(Loan.open(ALICE, DDD, POLICY, at("2026-09-28")));
        loans.save(Loan.open(ALICE, REFACTORING, POLICY, at("2026-09-29")));
        loans.save(Loan.open(ALICE, EFFECTIVE_JAVA, POLICY, at("2026-09-30")));

        PageResult<Loan> firstPage = loans.findFor(ALICE, LoanStatusFilter.ALL, 0, 2);

        assertThat(firstPage.items()).extracting(Loan::bookId).containsExactly(EFFECTIVE_JAVA, REFACTORING);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(loans.findFor(ALICE, LoanStatusFilter.ALL, 1, 2).items())
                .extracting(Loan::bookId).containsExactly(DDD);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=JpaLoanRepositoryTest`
Expected: FAIL — `cannot find symbol: class JpaLoanRepository`.

- [ ] **Step 3: Write the entity**

`src/main/java/com/elibrary/lending/internal/LoanEntity.java`

```java
package com.elibrary.lending.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence shape of a loan, kept separate from the {@code Loan} aggregate so the
 * aggregate can stay immutable plain Java. No column stores "overdue" or a status: both
 * are derived from {@code dueOn} and {@code returnedAt}, so there is nothing to keep in sync.
 */
@Entity
@Table(name = "loans")
class LoanEntity {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "book_id", nullable = false)
    private UUID bookId;

    @Column(name = "borrowed_at", nullable = false)
    private Instant borrowedAt;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "returned_at")
    private Instant returnedAt;

    protected LoanEntity() {
        // required by JPA
    }

    LoanEntity(UUID id, String memberId, UUID bookId, Instant borrowedAt, LocalDate dueOn, Instant returnedAt) {
        this.id = id;
        this.memberId = memberId;
        this.bookId = bookId;
        this.borrowedAt = borrowedAt;
        this.dueOn = dueOn;
        this.returnedAt = returnedAt;
    }

    UUID id() {
        return id;
    }

    String memberId() {
        return memberId;
    }

    UUID bookId() {
        return bookId;
    }

    Instant borrowedAt() {
        return borrowedAt;
    }

    LocalDate dueOn() {
        return dueOn;
    }

    Instant returnedAt() {
        return returnedAt;
    }
}
```

- [ ] **Step 4: Write the Spring Data repository and the mapper**

`src/main/java/com/elibrary/lending/internal/LoanJpaRepository.java`

```java
package com.elibrary.lending.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface LoanJpaRepository extends JpaRepository<LoanEntity, UUID> {

    List<LoanEntity> findByMemberIdAndReturnedAtIsNull(String memberId);

    Page<LoanEntity> findByMemberIdAndReturnedAtIsNull(String memberId, Pageable pageable);

    Page<LoanEntity> findByMemberIdAndReturnedAtIsNotNull(String memberId, Pageable pageable);

    Page<LoanEntity> findByMemberId(String memberId, Pageable pageable);
}
```

The active queries are covered by `idx_loans_member_returned` from the V1 migration.

`src/main/java/com/elibrary/lending/internal/LoanMapper.java`

```java
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
```

- [ ] **Step 5: Write the port implementation**

`src/main/java/com/elibrary/lending/internal/JpaLoanRepository.java`

```java
package com.elibrary.lending.internal;

import com.elibrary.lending.domain.ActiveLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanRepository;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class JpaLoanRepository implements LoanRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "borrowedAt");

    private final LoanJpaRepository loans;

    JpaLoanRepository(LoanJpaRepository loans) {
        this.loans = loans;
    }

    @Override
    public ActiveLoans activeFor(MemberId memberId) {
        return new ActiveLoans(memberId, loans.findByMemberIdAndReturnedAtIsNull(memberId.value())
                .stream()
                .map(LoanMapper::toDomain)
                .toList());
    }

    @Override
    public Optional<Loan> findById(LoanId id) {
        return loans.findById(id.value()).map(LoanMapper::toDomain);
    }

    /**
     * Loan ids are assigned in the domain, so Spring Data treats every save as a merge:
     * an existing row is updated rather than duplicated. That is exactly the behaviour
     * {@code returnNow} needs, at the cost of a select before the update.
     */
    @Override
    public Loan save(Loan loan) {
        return LoanMapper.toDomain(loans.save(LoanMapper.toEntity(loan)));
    }

    @Override
    public PageResult<Loan> findFor(MemberId memberId, LoanStatusFilter filter, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        String member = memberId.value();

        Page<LoanEntity> found = switch (filter) {
            case ACTIVE -> loans.findByMemberIdAndReturnedAtIsNull(member, request);
            case RETURNED -> loans.findByMemberIdAndReturnedAtIsNotNull(member, request);
            case ALL -> loans.findByMemberId(member, request);
        };

        return PageResult.of(
                found.getContent().stream().map(LoanMapper::toDomain).toList(),
                page,
                size,
                found.getTotalElements());
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=JpaLoanRepositoryTest`
Expected: PASS, 8 tests. If `savesAndReloadsEveryField` fails on the `borrowedAt` comparison, check that `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` is present in `application.yml` from Task 1.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/elibrary/lending/internal src/test/java/com/elibrary/lending/internal
git commit -m "feat(lending): add JPA adapter for the LoanRepository port

A separate LoanEntity plus an explicit mapper keeps the Loan aggregate free of
JPA. No column stores status or overdue, so neither can go stale. Loan ids are
domain-assigned, which makes save() a merge and gives returnNow() an update
rather than a duplicate row."
```

---

### Task 10: Security — authentication stops at the boundary

HTTP Basic against seeded members. The principal is translated into a `MemberId` at the edge; nothing behind the controller knows Spring Security exists.

**Files:**
- Create: `src/main/java/com/elibrary/platform/security/SecurityConfig.java`
- Create: `src/main/java/com/elibrary/platform/security/MemberIdArgumentResolver.java`
- Create: `src/main/java/com/elibrary/platform/security/WebMvcConfig.java`
- Create: `src/main/java/com/elibrary/platform/security/ProblemDetailEntryPoint.java`
- Test: `src/test/java/com/elibrary/platform/security/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `MemberId` (Task 2).
- Produces:
  - Seeded users: `alice`, `bob`, `carol` with role `MEMBER`, and `librarian` with role `LIBRARIAN`. Password for all four is `password`.
  - Controllers may declare a `MemberId` parameter and receive the authenticated caller.
  - 401 responses are `application/problem+json` with `code: UNAUTHENTICATED`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/platform/security/SecurityConfigTest.java`

```java
package com.elibrary.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnUnauthenticatedRequestWithAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void rejectsBadCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/books").with(httpBasic("alice", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsASeededMember() throws Exception {
        mockMvc.perform(get("/api/v1/books").with(httpBasic("alice", "password")))
                .andExpect(status().isOk());
    }

    @Test
    void healthAndApiDocsAreOpenSoAReviewerCanExploreFirst() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
```

`acceptsASeededMember` and the api-docs assertion will not pass until Tasks 12 and 15 add the controller and springdoc config. Until then this test class is expected to fail on those two methods; the first two methods must pass at the end of this task. Re-run the full class after Task 15.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=SecurityConfigTest`
Expected: FAIL — without a filter chain the request either 200s or returns Spring Boot's default HTML 401, so the `$.code` assertion fails.

- [ ] **Step 3: Write the 401 entry point**

`src/main/java/com/elibrary/platform/security/ProblemDetailEntryPoint.java`

```java
package com.elibrary.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Authentication failures are rejected inside the filter chain, before any
 * {@code @RestControllerAdvice} runs, so the error contract has to be written here too.
 * Without this the API would answer 401 in a different shape from every other error.
 */
@Component
class ProblemDetailEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    ProblemDetailEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create("https://elibrary.example/problems/unauthenticated"));
        problem.setTitle("Unauthenticated");
        problem.setDetail("Valid credentials are required. Use HTTP Basic authentication.");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "UNAUTHENTICATED");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("WWW-Authenticate", "Basic realm=\"elibrary\"");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
```

- [ ] **Step 4: Write the filter chain and seeded users**

`src/main/java/com/elibrary/platform/security/SecurityConfig.java`

```java
package com.elibrary.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic over a fixed set of members.
 *
 * <p>Authentication is deliberately the cheapest thing that is still real. Issuing JWTs was
 * not asked for and would consume budget that the domain needs; what matters architecturally
 * is that identity is resolved at the boundary and travels inward as a {@code MemberId},
 * which an OIDC-backed setup would do in exactly the same place.
 *
 * <p>CSRF is disabled because the API is stateless and token-free: there is no cookie for a
 * third-party site to ride on. Sessions are disabled for the same reason.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ProblemDetailEntryPoint entryPoint) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/h2-console/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        String password = encoder.encode("password");
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password(password).roles("MEMBER").build(),
                User.withUsername("bob").password(password).roles("MEMBER").build(),
                User.withUsername("carol").password(password).roles("MEMBER").build(),
                User.withUsername("librarian").password(password).roles("LIBRARIAN").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

The `LIBRARIAN` role gates no endpoint in this scope. It exists because the seam for administrative endpoints (adding stock, forcing a return) is worth showing without building them.

- [ ] **Step 5: Write the argument resolver**

`src/main/java/com/elibrary/platform/security/MemberIdArgumentResolver.java`

```java
package com.elibrary.platform.security;

import com.elibrary.shared.MemberId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the authenticated principal into a {@link MemberId} so controllers can declare it
 * as a parameter. This is the single point where an HTTP/Spring Security concern becomes a
 * domain concept: nothing behind the controller sees an {@code Authentication}.
 *
 * <p>It is also why no endpoint takes a member id in its path — the caller's identity is
 * never client-supplied, so one member's data cannot be requested by guessing a URL.
 */
@Component
class MemberIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return MemberId.class.equals(parameter.getParameterType());
    }

    @Override
    public MemberId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException(
                    "No authenticated principal; every MemberId endpoint must sit behind authentication.");
        }
        return new MemberId(authentication.getName());
    }
}
```

`src/main/java/com/elibrary/platform/security/WebMvcConfig.java`

```java
package com.elibrary.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
class WebMvcConfig implements WebMvcConfigurer {

    private final MemberIdArgumentResolver memberIdArgumentResolver;

    WebMvcConfig(MemberIdArgumentResolver memberIdArgumentResolver) {
        this.memberIdArgumentResolver = memberIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(memberIdArgumentResolver);
    }
}
```

- [ ] **Step 6: Run the test and confirm the two authentication cases pass**

Run: `mvn -q test -Dtest='SecurityConfigTest#rejectsAnUnauthenticatedRequestWithAProblemDetail+rejectsBadCredentials'`
Expected: PASS, 2 tests. The other two methods in the class remain red until Tasks 12 and 15.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/elibrary/platform/security src/test/java/com/elibrary/platform/security
git commit -m "feat(platform): add HTTP Basic auth with identity resolved at the boundary

An argument resolver converts the principal into a MemberId so no code behind the
controller sees Spring Security, and no endpoint needs a member id in its path.
401s are emitted as problem+json from a custom entry point so authentication
failures match the error contract used everywhere else."
```

---

### Task 11: The error contract

One advice class maps domain failures to RFC 9457 `ProblemDetail`. Because Task 2 gave the domain exactly two abstract bases, this stays two handler methods and a new domain error ships without touching the web layer.

**Files:**
- Create: `src/main/java/com/elibrary/platform/web/ProblemDetails.java`
- Create: `src/main/java/com/elibrary/platform/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/elibrary/platform/web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `NotFoundException`, `ConflictException` (Task 2); the concrete lending exceptions (Tasks 4–6).
- Produces: `ProblemDetails.of(HttpStatus, String code, String detail, HttpServletRequest)` and `ProblemDetails.titleFrom(String code)`, used by the entry point contract and available to any future adapter.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/platform/web/ApiExceptionHandlerTest.java`

```java
package com.elibrary.platform.web;

import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanNotFound;
import com.elibrary.shared.BookId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    /** Throws on demand so the advice can be exercised without the real controllers. */
    @RestController
    static class BoomController {

        @GetMapping("/boom/not-found")
        String notFound() {
            throw new LoanNotFound(LoanId.of("22222222-2222-2222-2222-222222222201"));
        }

        @GetMapping("/boom/conflict")
        String conflict() {
            throw new BookUnavailable(BookId.of("11111111-1111-1111-1111-111111111110"));
        }

        @GetMapping("/boom/bad-id")
        String badId(@RequestParam String id) {
            return BookId.of(id).toString();
        }

        @GetMapping("/boom/unexpected")
        String unexpected() {
            throw new IllegalStateException("a database cable came loose and the password is hunter2");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void notFoundBecomes404WithAStableCodeAndATypeUri() throws Exception {
        mockMvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Loan not found"))
                .andExpect(jsonPath("$.type").value("https://elibrary.example/problems/loan-not-found"))
                .andExpect(jsonPath("$.instance").value("/boom/not-found"));
    }

    @Test
    void businessRefusalsBecome409AndAreDistinguishedByCodeNotByStatus() throws Exception {
        mockMvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_COPIES_AVAILABLE"))
                .andExpect(jsonPath("$.type").value("https://elibrary.example/problems/no-copies-available"));
    }

    @Test
    void aMalformedIdentifierBecomes400RatherThan500() throws Exception {
        mockMvc.perform(get("/boom/bad-id").param("id", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not-a-uuid")));
    }

    @Test
    void anUnexpectedFailureBecomes500AndLeaksNothingFromTheMessage() throws Exception {
        mockMvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
```

The last assertion is the one that matters: an exception message can carry connection strings or user data, so it is logged and never returned.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=ApiExceptionHandlerTest`
Expected: FAIL — `cannot find symbol: class ApiExceptionHandler`.

- [ ] **Step 3: Write the problem builder**

`src/main/java/com/elibrary/platform/web/ProblemDetails.java`

```java
package com.elibrary.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;

/** Builds the one response shape every error in this API uses. */
final class ProblemDetails {

    private static final String TYPE_BASE = "https://elibrary.example/problems/";

    private ProblemDetails() {
    }

    static ProblemDetail of(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(titleFrom(code));
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    /** {@code LOAN_LIMIT_REACHED} becomes {@code "Loan limit reached"}. */
    static String titleFrom(String code) {
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
```

- [ ] **Step 4: Write the advice**

`src/main/java/com/elibrary/platform/web/ApiExceptionHandler.java`

```java
package com.elibrary.platform.web;

import com.elibrary.shared.error.ConflictException;
import com.elibrary.shared.error.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * The only place in the codebase that knows how a business failure maps to an HTTP status.
 *
 * <p>Every business refusal is 409. Clients must branch on {@code code}, not on the status:
 * status codes have far too low a cardinality to act as an error contract, whereas codes can
 * grow without a breaking change. Because the domain exposes just two abstract bases, adding
 * a domain error needs no change here at all.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail onNotFound(NotFoundException e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail onConflict(ConflictException e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request body is invalid.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Covers malformed identifiers, which the value objects reject on construction. */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail onInvalidRequest(Exception e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }
}
```

The 500 handler logs the cause with the request line and returns a fixed message. Exception messages routinely contain connection strings, SQL, or user data, so none of it crosses the boundary.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=ApiExceptionHandlerTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/elibrary/platform/web src/test/java/com/elibrary/platform/web
git commit -m "feat(platform): map domain failures to RFC 9457 problem details

Two abstract domain bases keep this to two handler methods, so new domain errors
need no web-layer change. All business refusals are 409 and are distinguished by
a stable code rather than by status. Unexpected failures are logged and answered
with a fixed message so exception text cannot leak."
```

---

### Task 12: Browse endpoints

**Files:**
- Create: `src/main/java/com/elibrary/catalog/web/BookController.java`
- Test: `src/test/java/com/elibrary/catalog/web/BookControllerTest.java`

**Interfaces:**
- Consumes: `BookCatalog`, `BookSearchCriteria`, `BookSortField`, `BookSummary`, `BookDetail` (Task 7); `BookNotFound` (Task 6); `PageResult` (Task 2).
- Produces: `GET /api/v1/books` and `GET /api/v1/books/{bookId}`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/catalog/web/BookControllerTest.java`

```java
package com.elibrary.catalog.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.platform.web.ApiExceptionHandler;
import com.elibrary.shared.BookId;
import com.elibrary.shared.Isbn;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookControllerTest {

    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");

    private BookCatalog catalog;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalog = mock(BookCatalog.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BookController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static BookSummary summary() {
        return new BookSummary(DDD, "Domain-Driven Design", "Eric Evans", ContentKind.BOOK, 4, 3);
    }

    @Test
    void returnsOurPaginationEnvelopeRatherThanSpringsPageShape() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(summary()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(DDD.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.items[0].availableCopies").value(3))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void passesQueryParametersThroughAsCriteria() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 1, 5, 0));

        mockMvc.perform(get("/api/v1/books")
                        .param("q", "fowler")
                        .param("author", "Martin")
                        .param("available", "true")
                        .param("sort", "publicationYear,desc")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        BookSearchCriteria criteria = captor.getValue();

        assertThat(criteria.q()).isEqualTo("fowler");
        assertThat(criteria.author()).isEqualTo("Martin");
        assertThat(criteria.availableOnly()).isTrue();
        assertThat(criteria.sortField()).isEqualTo(BookSortField.PUBLICATION_YEAR);
        assertThat(criteria.ascending()).isFalse();
        assertThat(criteria.page()).isEqualTo(1);
        assertThat(criteria.size()).isEqualTo(5);
    }

    @Test
    void appliesDefaultsWhenNoParametersAreGiven() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/books")).andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());

        assertThat(captor.getValue().sortField()).isEqualTo(BookSortField.TITLE);
        assertThat(captor.getValue().ascending()).isTrue();
        assertThat(captor.getValue().size()).isEqualTo(20);
        assertThat(captor.getValue().availableOnly()).isFalse();
    }

    @Test
    void clampsAnOversizedPageRequest() throws Exception {
        when(catalog.search(any())).thenReturn(PageResult.of(List.of(), 0, 100, 0));

        mockMvc.perform(get("/api/v1/books").param("size", "100000")).andExpect(status().isOk());

        ArgumentCaptor<BookSearchCriteria> captor = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(catalog).search(captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(BookSearchCriteria.MAX_SIZE);
    }

    @Test
    void rejectsASortFieldThatIsNotWhitelisted() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("sort", "availableCopies,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("availableCopies")));
    }

    @Test
    void returnsDetailForAKnownBook() throws Exception {
        when(catalog.findById(DDD)).thenReturn(Optional.of(new BookDetail(
                DDD, "Domain-Driven Design", "Eric Evans", Isbn.of("9780321125217"),
                ContentKind.BOOK, "Addison-Wesley", 2003, null, null, 4, 3)));

        mockMvc.perform(get("/api/v1/books/{id}", DDD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value("9780321125217"))
                .andExpect(jsonPath("$.publisher").value("Addison-Wesley"))
                .andExpect(jsonPath("$.publicationYear").value(2003));
    }

    @Test
    void anUnknownBookIs404WithTheBookNotFoundCode() throws Exception {
        when(catalog.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/books/{id}", BookId.newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    void aMalformedBookIdIs400NotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
```

`Isbn` and `BookId` are serialised as bare strings, which is what the `$.isbn` and `$.items[0].id` assertions expect. Their `toString()` overrides from Task 2 are not enough on their own — the controller maps them explicitly in the next step.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=BookControllerTest`
Expected: FAIL — `cannot find symbol: class BookController`.

- [ ] **Step 3: Write the controller**

`src/main/java/com/elibrary/catalog/web/BookController.java`

```java
package com.elibrary.catalog.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookDetail;
import com.elibrary.catalog.BookSearchCriteria;
import com.elibrary.catalog.BookSortField;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.domain.BookNotFound;
import com.elibrary.shared.BookId;
import com.elibrary.shared.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalogue", description = "Browse books and journals")
@RestController
@RequestMapping(path = "/api/v1/books", produces = MediaType.APPLICATION_JSON_VALUE)
class BookController {

    private final BookCatalog catalog;

    BookController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    /** Response shape for list views. Value objects are flattened to strings for the wire. */
    record BookSummaryResponse(
            String id, String title, String author, ContentKind kind,
            int totalCopies, int availableCopies, boolean available) {

        static BookSummaryResponse from(BookSummary summary) {
            return new BookSummaryResponse(
                    summary.id().toString(), summary.title(), summary.author(), summary.kind(),
                    summary.totalCopies(), summary.availableCopies(), summary.available());
        }
    }

    record BookDetailResponse(
            String id, String title, String author, String isbn, ContentKind kind,
            String publisher, Integer publicationYear, String volume, String issue,
            int totalCopies, int availableCopies, boolean available) {

        static BookDetailResponse from(BookDetail detail) {
            return new BookDetailResponse(
                    detail.id().toString(), detail.title(), detail.author(),
                    detail.isbn() == null ? null : detail.isbn().value(), detail.kind(),
                    detail.publisher(), detail.publicationYear(), detail.volume(), detail.issue(),
                    detail.totalCopies(), detail.availableCopies(), detail.available());
        }
    }

    @Operation(summary = "Browse the catalogue",
            description = "Free-text search over title and author, optional author filter, "
                    + "optional availability filter, offset paging. Sort accepts "
                    + "title|author|publicationYear followed by asc|desc.")
    @GetMapping
    PageResult<BookSummaryResponse> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(name = "available", required = false, defaultValue = "false") boolean availableOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        BookSearchCriteria criteria = BookSearchCriteria.of(
                q, author, availableOnly, sortField(sort), ascending(sort), page, size);

        return catalog.search(criteria).map(BookSummaryResponse::from);
    }

    @Operation(summary = "Retrieve one catalogue item")
    @GetMapping("/{bookId}")
    BookDetailResponse detail(@PathVariable String bookId) {
        BookId id = BookId.of(bookId);
        return catalog.findById(id).map(BookDetailResponse::from).orElseThrow(() -> new BookNotFound(id));
    }

    /**
     * Parses {@code field,direction}. An unrecognised field is a 400 rather than a silent
     * fallback: quietly ignoring it would make a client believe its ordering was applied.
     */
    private static BookSortField sortField(String sort) {
        if (sort == null || sort.isBlank()) {
            return BookSortField.TITLE;
        }
        String field = sort.split(",")[0].trim();
        return BookSortField.parse(field).orElseThrow(() -> new IllegalArgumentException(
                "Cannot sort by '" + field + "'. Sortable fields: title, author, publicationYear."));
    }

    private static boolean ascending(String sort) {
        if (sort == null || !sort.contains(",")) {
            return true;
        }
        return !"desc".equalsIgnoreCase(sort.split(",")[1].trim());
    }
}
```

Rejecting an unknown sort field rather than falling back to the default is the deliberate choice: a client that asked for an ordering and silently did not get it will build wrong behaviour on top of the assumption.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=BookControllerTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Confirm the browse endpoint is reachable end to end**

Run: `mvn -q test -Dtest='SecurityConfigTest#acceptsASeededMember'`
Expected: PASS — the controller added here is what that method was waiting for.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/elibrary/catalog/web src/test/java/com/elibrary/catalog/web
git commit -m "feat(catalog): add browse and detail endpoints

Responses use our own pagination envelope, never Spring's Page shape, and value
objects are flattened to strings at the wire boundary. An unrecognised sort
field is a 400 rather than a silent fallback so a client cannot build on an
ordering it did not actually get."
```

---

### Task 13: Loan endpoints

**Files:**
- Create: `src/main/java/com/elibrary/lending/web/BorrowRequest.java`
- Create: `src/main/java/com/elibrary/lending/web/LoanResponse.java`
- Create: `src/main/java/com/elibrary/lending/web/LoanController.java`
- Test: `src/test/java/com/elibrary/lending/web/LoanControllerTest.java`

**Interfaces:**
- Consumes: `BorrowBook`, `ReturnBook`, `ViewLoans` (Task 6); `BookCatalog`, `BookSummary` (Task 7); `MemberId` resolution (Task 10); `Clock` (Task 3).
- Produces: `POST /api/v1/loans`, `POST /api/v1/loans/{loanId}/return`, `GET /api/v1/loans`, `GET /api/v1/loans/{loanId}`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/elibrary/lending/web/LoanControllerTest.java`

```java
package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.application.ReturnBook;
import com.elibrary.lending.application.ViewLoans;
import com.elibrary.lending.domain.LendingPolicy;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanLimitReached;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.platform.web.ApiExceptionHandler;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoanControllerTest {

    private static final MemberId ALICE = new MemberId("alice");
    private static final BookId DDD = BookId.of("11111111-1111-1111-1111-111111111101");
    private static final LendingPolicy POLICY = new LendingPolicy(5, 14);
    private static final Clock BORROWED = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private BorrowBook borrowBook;
    private ReturnBook returnBook;
    private ViewLoans viewLoans;
    private BookCatalog catalog;
    private MockMvc mockMvc;

    /** Stands in for MemberIdArgumentResolver so the controller can be tested standalone. */
    private static HandlerMethodArgumentResolver fixedMember(MemberId member) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return MemberId.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                                          org.springframework.web.context.request.NativeWebRequest request,
                                          org.springframework.web.bind.support.WebDataBinderFactory factory) {
                return member;
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                                          org.springframework.web.method.support.ModelAndViewContainer mav,
                                          org.springframework.web.context.request.NativeWebRequest request,
                                          org.springframework.web.bind.support.WebDataBinderFactory factory) {
                return member;
            }
        };
    }

    @BeforeEach
    void setUp() {
        borrowBook = mock(BorrowBook.class);
        returnBook = mock(ReturnBook.class);
        viewLoans = mock(ViewLoans.class);
        catalog = mock(BookCatalog.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new LoanController(borrowBook, returnBook, viewLoans, catalog, BORROWED))
                .setCustomArgumentResolvers(fixedMember(ALICE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        when(catalog.summariesFor(any())).thenReturn(List.of(
                new BookSummary(DDD, "Domain-Driven Design", "Eric Evans", ContentKind.BOOK, 4, 3)));
    }

    @Test
    void borrowingReturns201WithALocationPointingAtTheNewLoan() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        when(borrowBook.handle(eq(ALICE), eq(DDD))).thenReturn(loan);

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/loans/" + loan.id()))
                .andExpect(jsonPath("$.id").value(loan.id().toString()))
                .andExpect(jsonPath("$.book.id").value(DDD.toString()))
                .andExpect(jsonPath("$.book.title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.dueOn").value("2026-10-14"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.returnedAt").doesNotExist());
    }

    @Test
    void borrowingWithAMissingBookIdIs400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("bookId"));

        verify(borrowBook, never()).handle(any(), any());
    }

    @Test
    void borrowingWithAMalformedBookIdIs400() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBusinessRefusalSurfacesAs409WithItsCode() throws Exception {
        when(borrowBook.handle(any(), any())).thenThrow(new LoanLimitReached(ALICE, 5, 5));

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_LIMIT_REACHED"));
    }

    @Test
    void returningRespondsWithTheClosedLoan() throws Exception {
        Loan closed = Loan.open(ALICE, DDD, POLICY, BORROWED)
                .returnNow(Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC));
        when(returnBook.handle(eq(ALICE), eq(closed.id()))).thenReturn(closed);

        mockMvc.perform(post("/api/v1/loans/{id}/return", closed.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnedAt").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void listingDefaultsToActiveLoansOfTheCaller() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        when(viewLoans.handle(eq(ALICE), eq(LoanStatusFilter.ACTIVE), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        mockMvc.perform(get("/api/v1/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(loan.id().toString()))
                .andExpect(jsonPath("$.items[0].book.title").value("Domain-Driven Design"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(viewLoans).handle(ALICE, LoanStatusFilter.ACTIVE, 0, 20);
    }

    @Test
    void listingAcceptsAnExplicitStatusFilter() throws Exception {
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/loans").param("status", "returned"))
                .andExpect(status().isOk());

        verify(viewLoans).handle(ALICE, LoanStatusFilter.RETURNED, 0, 20);
    }

    @Test
    void anUnknownStatusFilterIs400() throws Exception {
        mockMvc.perform(get("/api/v1/loans").param("status", "pending"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anOverdueActiveLoanIsFlaggedOverdue() throws Exception {
        Loan loan = Loan.open(ALICE, DDD, POLICY, BORROWED);
        Clock afterDueDate = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        MockMvc withLateClock = MockMvcBuilders
                .standaloneSetup(new LoanController(borrowBook, returnBook, viewLoans, catalog, afterDueDate))
                .setCustomArgumentResolvers(fixedMember(ALICE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        withLateClock.perform(get("/api/v1/loans"))
                .andExpect(jsonPath("$.items[0].overdue").value(true));
    }

    @Test
    void enrichmentIssuesOneCatalogueLookupForTheWholePageNotOnePerLoan() throws Exception {
        List<Loan> threeLoans = List.of(
                Loan.open(ALICE, DDD, POLICY, BORROWED),
                Loan.open(ALICE, BookId.of("11111111-1111-1111-1111-111111111104"), POLICY, BORROWED),
                Loan.open(ALICE, BookId.of("11111111-1111-1111-1111-111111111107"), POLICY, BORROWED));
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(threeLoans, 0, 20, 3));

        mockMvc.perform(get("/api/v1/loans")).andExpect(status().isOk());

        verify(catalog, org.mockito.Mockito.times(1)).summariesFor(any());
    }

    @Test
    void aLoanWhoseBookVanishedFromTheCatalogueStillRenders() throws Exception {
        Loan loan = Loan.open(ALICE, BookId.newId(), POLICY, BORROWED);
        when(viewLoans.handle(any(), any(), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(loan), 0, 20, 1));

        mockMvc.perform(get("/api/v1/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].book.id").value(loan.bookId().toString()))
                .andExpect(jsonPath("$.items[0].book.title").doesNotExist());
    }
}
```

Note the two enrichment tests at the end: one pins the batch lookup so an N+1 regression fails the build, the other pins that a loan whose book has left the catalogue still renders. A loan is a historical record and must not become unreadable because the catalogue changed.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=LoanControllerTest`
Expected: FAIL — `cannot find symbol: class LoanController`.

- [ ] **Step 3: Write the request and response DTOs**

`src/main/java/com/elibrary/lending/web/BorrowRequest.java`

```java
package com.elibrary.lending.web;

import jakarta.validation.constraints.NotBlank;

/** No member id: the borrower is always the authenticated caller. */
record BorrowRequest(@NotBlank(message = "bookId is required") String bookId) {
}
```

`src/main/java/com/elibrary/lending/web/LoanResponse.java`

```java
package com.elibrary.lending.web;

import com.elibrary.catalog.BookSummary;
import com.elibrary.catalog.ContentKind;
import com.elibrary.lending.domain.Loan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire shape of a loan. {@code status} and {@code overdue} are computed here from the
 * aggregate and the clock — neither is stored, so neither can disagree with the data.
 */
record LoanResponse(
        String id,
        BookRef book,
        Instant borrowedAt,
        LocalDate dueOn,
        Instant returnedAt,
        String status,
        boolean overdue) {

    /**
     * Enough of the book to render a list without a second request. Title and author are
     * null when the catalogue no longer holds the item: a loan is historical record and must
     * still render.
     */
    record BookRef(String id, String title, String author, ContentKind kind) {

        static BookRef of(Loan loan, BookSummary summary) {
            return summary == null
                    ? new BookRef(loan.bookId().toString(), null, null, null)
                    : new BookRef(summary.id().toString(), summary.title(), summary.author(), summary.kind());
        }
    }

    static LoanResponse of(Loan loan, BookSummary summary, Clock clock) {
        return new LoanResponse(
                loan.id().toString(),
                BookRef.of(loan, summary),
                loan.borrowedAt(),
                loan.dueOn(),
                loan.returnedAt(),
                loan.isActive() ? "ACTIVE" : "RETURNED",
                loan.isOverdue(clock));
    }
}
```

- [ ] **Step 4: Write the controller**

`src/main/java/com/elibrary/lending/web/LoanController.java`

```java
package com.elibrary.lending.web;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.catalog.BookSummary;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.application.ReturnBook;
import com.elibrary.lending.application.ViewLoans;
import com.elibrary.lending.domain.Loan;
import com.elibrary.lending.domain.LoanId;
import com.elibrary.lending.domain.LoanStatusFilter;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import com.elibrary.shared.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Loan endpoints.
 *
 * <p>No path carries a member id: scope is always the authenticated caller, so one member's
 * loans cannot be requested by guessing a URL.
 *
 * <p>{@code POST /loans/{id}/return} is a conscious departure from strict REST. Modelling it
 * as {@code PATCH /loans/{id}} with a status field would hand the state transition to the
 * client and let illegal transitions be expressed in a request body; a named action is more
 * honest about the domain.
 */
@Tag(name = "Lending", description = "Borrow, return, and view your loans")
@RestController
@RequestMapping(path = "/api/v1/loans", produces = MediaType.APPLICATION_JSON_VALUE)
class LoanController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final BorrowBook borrowBook;
    private final ReturnBook returnBook;
    private final ViewLoans viewLoans;
    private final BookCatalog catalog;
    private final Clock clock;

    LoanController(BorrowBook borrowBook, ReturnBook returnBook, ViewLoans viewLoans,
                   BookCatalog catalog, Clock clock) {
        this.borrowBook = borrowBook;
        this.returnBook = returnBook;
        this.viewLoans = viewLoans;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Operation(summary = "Borrow a book",
            description = "Creates a loan for the authenticated member. 409 when no copy is "
                    + "available, the member is at the loan limit, or the member already holds this book.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoanResponse> borrow(MemberId member, @Valid @RequestBody BorrowRequest request) {
        Loan loan = borrowBook.handle(member, BookId.of(request.bookId()));
        return ResponseEntity
                .created(URI.create("/api/v1/loans/" + loan.id()))
                .body(enrich(loan));
    }

    @Operation(summary = "Return a borrowed book")
    @PostMapping("/{loanId}/return")
    LoanResponse returnLoan(MemberId member, @PathVariable String loanId) {
        return enrich(returnBook.handle(member, LoanId.of(loanId)));
    }

    @Operation(summary = "View your loans",
            description = "Defaults to currently borrowed items. status accepts active|returned|all.")
    @GetMapping
    PageResult<LoanResponse> list(
            MemberId member,
            @RequestParam(required = false, defaultValue = "active") String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = Math.min(size == null || size < 1 ? DEFAULT_SIZE : size, MAX_SIZE);

        PageResult<Loan> loans = viewLoans.handle(member, parseStatus(status), safePage, safeSize);
        return enrich(loans);
    }

    @Operation(summary = "Retrieve one of your loans")
    @GetMapping("/{loanId}")
    LoanResponse detail(MemberId member, @PathVariable String loanId) {
        return enrich(viewLoans.byId(member, LoanId.of(loanId)));
    }

    /**
     * Enriches a whole page with catalogue data in a single lookup. Doing this per row would
     * be an N+1 against the catalogue; composing the two modules' data here, at the boundary,
     * also keeps the lending domain unaware that a catalogue exists.
     */
    private PageResult<LoanResponse> enrich(PageResult<Loan> loans) {
        Map<BookId, BookSummary> summaries = catalog
                .summariesFor(loans.items().stream().map(Loan::bookId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(BookSummary::id, Function.identity()));

        return loans.map(loan -> LoanResponse.of(loan, summaries.get(loan.bookId()), clock));
    }

    private LoanResponse enrich(Loan loan) {
        List<BookSummary> found = catalog.summariesFor(List.of(loan.bookId()));
        return LoanResponse.of(loan, found.isEmpty() ? null : found.getFirst(), clock);
    }

    private static LoanStatusFilter parseStatus(String raw) {
        try {
            return LoanStatusFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown status '" + raw + "'. Valid values: active, returned, all.");
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LoanControllerTest`
Expected: PASS, 11 tests.

- [ ] **Step 6: Run the whole unit suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/elibrary/lending/web src/test/java/com/elibrary/lending/web
git commit -m "feat(lending): add borrow, return, and view-loans endpoints

No endpoint takes a member id: scope is always the authenticated caller.
POST /loans creates the loan resource and returns 201 with a Location header;
return is a named action rather than a client-driven status PATCH. A page of
loans is enriched with catalogue data in one batch lookup, not one per row."
```

---

### Task 14: Integration and concurrency tests

Two tests carry disproportionate weight. The flow test proves the five required capabilities work over real HTTP with real authentication; the concurrency test proves the pessimistic lock from Task 8 actually prevents overselling rather than being an annotation that merely looks right.

**Files:**
- Create: `src/test/java/com/elibrary/integration/LendingFlowIT.java`
- Create: `src/test/java/com/elibrary/integration/LastCopyConcurrencyIT.java`
- Modify: `src/main/resources/application.yml` — add `LOCK_TIMEOUT=10000` to the H2 URL

**Interfaces:**
- Consumes: the whole application.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Raise the H2 lock timeout**

Contended `FOR UPDATE` reads must wait rather than fail. H2's default lock timeout is short enough that a loser can be rejected with a lock exception instead of queuing. Edit the datasource URL in `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:elibrary;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;LOCK_TIMEOUT=10000
```

- [ ] **Step 2: Write the failing end-to-end flow test**

`src/test/java/com/elibrary/integration/LendingFlowIT.java`

```java
package com.elibrary.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LendingFlowIT {

    private static final String DDD = "11111111-1111-1111-1111-111111111101";
    private static final String ACCELERATE = "11111111-1111-1111-1111-111111111110";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int availableCopiesOf(String bookId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/books/{id}", bookId).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("availableCopies").asInt();
    }

    @Test
    void browseBorrowViewAndReturnAcrossTheWholeStack() throws Exception {
        int copiesBefore = availableCopiesOf(DDD);

        // browse, filtered and paged
        mockMvc.perform(get("/api/v1/books")
                        .param("q", "domain-driven")
                        .param("available", "true")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").exists())
                .andExpect(jsonPath("$.totalElements").exists());

        // borrow
        MvcResult borrowed = mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String loanId = json(borrowed).get("id").asText();
        String location = borrowed.getResponse().getHeader("Location");

        assertThat(location).isEqualTo("/api/v1/loans/" + loanId);
        assertThat(availableCopiesOf(DDD)).isEqualTo(copiesBefore - 1);

        // the Location header points at something retrievable
        mockMvc.perform(get(location).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(loanId));

        // currently borrowed books
        mockMvc.perform(get("/api/v1/loans").with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").exists())
                .andExpect(jsonPath("$.items[0].book.title").value("Domain-Driven Design"));

        // return
        mockMvc.perform(post("/api/v1/loans/{id}/return", loanId).with(httpBasic("alice", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnedAt").exists());

        assertThat(availableCopiesOf(DDD)).isEqualTo(copiesBefore);

        // no longer in the active list
        mockMvc.perform(get("/api/v1/loans").with(httpBasic("alice", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").doesNotExist());

        // but still visible in history
        mockMvc.perform(get("/api/v1/loans").param("status", "returned").with(httpBasic("alice", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + loanId + "')]").exists());
    }

    @Test
    void borrowingAFullyBorrowedBookIsRefusedWithoutChangingAnything() throws Exception {
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + ACCELERATE + "\"}")
                        .with(httpBasic("bob", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_COPIES_AVAILABLE"));

        assertThat(availableCopiesOf(ACCELERATE)).isZero();
    }

    @Test
    void borrowingTheSameBookTwiceIsRefusedAndTheFirstLoanSurvives() throws Exception {
        String book = "11111111-1111-1111-1111-111111111103";

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}")
                        .with(httpBasic("carol", "password")))
                .andExpect(status().isCreated());

        int afterFirst = availableCopiesOf(book);

        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + book + "\"}")
                        .with(httpBasic("carol", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BORROWED"));

        assertThat(availableCopiesOf(book))
                .as("a refused borrow must not consume a copy")
                .isEqualTo(afterFirst);
    }

    @Test
    void oneMemberCannotSeeOrReturnAnotherMembersLoan() throws Exception {
        MvcResult borrowed = mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"11111111-1111-1111-1111-111111111106\"}")
                        .with(httpBasic("alice", "password")))
                .andExpect(status().isCreated())
                .andReturn();
        String aliceLoan = json(borrowed).get("id").asText();

        mockMvc.perform(get("/api/v1/loans/{id}", aliceLoan).with(httpBasic("bob", "password")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/loans/{id}/return", aliceLoan).with(httpBasic("bob", "password")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/loans").with(httpBasic("bob", "password")))
                .andExpect(jsonPath("$.items[?(@.id == '" + aliceLoan + "')]").doesNotExist());
    }

    @Test
    void everyLoanEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/loans")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + DDD + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

These tests share one in-memory database and are not transactional, so they must not depend on each other's state. Each uses a different book and asserts relative to a value it reads first rather than to a seeded constant.

- [ ] **Step 3: Write the failing concurrency test**

`src/test/java/com/elibrary/integration/LastCopyConcurrencyIT.java`

```java
package com.elibrary.integration;

import com.elibrary.catalog.BookCatalog;
import com.elibrary.lending.application.BorrowBook;
import com.elibrary.lending.domain.BookUnavailable;
import com.elibrary.shared.BookId;
import com.elibrary.shared.MemberId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last copy of a book, contended by many members at once.
 *
 * <p>This is the test that gives the locking decision teeth. Without the pessimistic write
 * lock in CatalogBookInventory, several transactions read {@code available_copies = 1},
 * all decrement, and the library lends a book it does not have. The database check
 * constraint would catch some of that, but the point is that the application must not
 * depend on the constraint to stay correct.
 */
@SpringBootTest
class LastCopyConcurrencyIT {

    /** Seeded with exactly one available copy. */
    private static final BookId TIDY_FIRST = BookId.of("11111111-1111-1111-1111-111111111111");
    private static final int CONTENDERS = 8;

    @Autowired
    private BorrowBook borrowBook;

    @Autowired
    private BookCatalog catalog;

    @Test
    void exactlyOneMemberGetsTheLastCopy() throws Exception {
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().availableCopies())
                .as("precondition: exactly one copy is available")
                .isEqualTo(1);

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                MemberId member = new MemberId("contender-" + i);
                attempts.add(pool.submit(() -> {
                    try {
                        startTogether.await();
                        borrowBook.handle(member, TIDY_FIRST);
                        succeeded.incrementAndGet();
                    } catch (BookUnavailable expected) {
                        refused.incrementAndGet();
                    } catch (Throwable other) {
                        unexpected.add(other);
                    }
                    return null;
                }));
            }

            startTogether.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get())
                .as("the library must not lend a copy it does not have")
                .isEqualTo(1);
        assertThat(catalog.findById(TIDY_FIRST).orElseThrow().availableCopies())
                .as("availability must land on exactly zero, never negative")
                .isZero();
        assertThat(succeeded.get() + refused.get() + unexpected.size()).isEqualTo(CONTENDERS);
        assertThat(unexpected)
                .as("losers should be refused cleanly, not fail with lock or constraint errors")
                .isEmpty();
    }
}
```

Each contender is a distinct member, so the duplicate-borrow rule from Task 5 cannot be what rejects them — the only thing standing between eight threads and an oversold book is the write lock.

If `unexpected` collects `CannotAcquireLockException`, the H2 `LOCK_TIMEOUT` from Step 1 is not being applied; confirm the datasource URL. If it collects a constraint-violation exception, the lock is not being taken at all — check that `findByIdForUpdate` is the method `CatalogBookInventory` calls and that `@Lock` is present on it.

- [ ] **Step 4: Run both integration tests to verify they fail or pass as expected**

Run: `mvn -q verify -Dtest=skip -DfailIfNoTests=false -Dit.test='LendingFlowIT,LastCopyConcurrencyIT'`

If that invocation is awkward, run the whole verify phase instead:

Run: `mvn -q verify`
Expected: PASS, including both `*IT` classes. Failsafe picks up `*IT` during `verify`, so `mvn test` alone will not run them — that separation keeps the unit suite fast.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/elibrary/integration src/main/resources/application.yml
git commit -m "test: add end-to-end flow and last-copy concurrency tests

The flow test exercises all five required capabilities over real HTTP with real
authentication, including that one member cannot see or return another's loan.
The concurrency test contends eight distinct members on a single remaining copy
and asserts exactly one winner with availability landing on zero, which is what
makes the pessimistic lock a verified decision rather than a hopeful annotation."
```

---

### Task 15: Architecture tests and API documentation

The cheapest item in the project with the highest signal. Without these rules, "my domain is pure" is a README claim that rots by the twentieth commit; with them, the boundary has teeth and a violation fails the build.

**Files:**
- Create: `src/test/java/com/elibrary/architecture/ArchitectureTest.java`
- Create: `src/main/java/com/elibrary/platform/openapi/OpenApiConfig.java`

**Interfaces:**
- Consumes: the whole application.
- Produces: `/swagger-ui.html` and `/v3/api-docs`, both reachable without credentials.

- [ ] **Step 1: Write the architecture test**

This test is written to pass against the code as it already stands — it pins the decisions rather than driving new ones. Run it immediately; a failure means an earlier task drifted.

`src/test/java/com/elibrary/architecture/ArchitectureTest.java`

```java
package com.elibrary.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable architecture. Every rule here corresponds to a claim made in the README;
 * keeping them as tests is what stops those claims from quietly becoming false.
 */
@AnalyzeClasses(packages = "com.elibrary", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfSpring =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("the lending domain must stay plain Java so it is testable without a container");

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfPersistence =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
                    .because("persistence is an adapter concern; the aggregate is mapped, not annotated");

    @ArchTest
    static final ArchRule theDomainKnowsNothingOfTheCatalogue =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..catalog..")
                    .because("lending owns the BookInventory port; the catalogue implements it, not the reverse");

    @ArchTest
    static final ArchRule theDomainDoesNotReachOutwards =
            noClasses().that().resideInAPackage("..lending.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..lending.internal..", "..lending.web..")
                    .because("dependencies point inwards");

    @ArchTest
    static final ArchRule lendingCannotReachIntoTheCatalogueInternals =
            noClasses().that().resideInAPackage("..lending..")
                    .should().dependOnClassesThat().resideInAPackage("..catalog.internal..")
                    .because("the catalogue's only public surface is com.elibrary.catalog");

    @ArchTest
    static final ArchRule theCatalogueCannotReachIntoLendingBeyondItsPort =
            noClasses().that().resideInAPackage("..catalog..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..lending.internal..", "..lending.application..", "..lending.web..")
                    .because("the catalogue may implement lending's port, but must not know its use cases");

    @ArchTest
    static final ArchRule sharedDependsOnNoModule =
            noClasses().that().resideInAPackage("..shared..")
                    .should().dependOnClassesThat().resideInAnyPackage("..lending..", "..catalog..", "..platform..")
                    .because("the shared kernel is the bottom of the dependency graph");

    @ArchTest
    static final ArchRule entitiesLiveOnlyInModuleInternals =
            noClasses().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().resideOutsideOfPackages("..catalog.internal..", "..lending.internal..")
                    .because("an entity that escapes its module can be serialised or mutated from anywhere");

    @ArchTest
    static final ArchRule springsPageNeverReachesTheWire =
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.data.domain.Page")
                    .because("PageResult is the public pagination contract; Page's JSON shape is Spring's, not ours");

    @ArchTest
    static final ArchRule nobodyReadsTheWallClockDirectly =
            noClasses().should().callMethod(LocalDate.class, "now")
                    .orShould().callMethod(Instant.class, "now")
                    .orShould().callMethod(LocalDateTime.class, "now")
                    .because("time comes from the injected Clock so due dates and overdue are deterministic");
}
```

- [ ] **Step 2: Run the architecture test**

Run: `mvn -q test -Dtest=ArchitectureTest`
Expected: PASS, 10 rules.

If `theDomainKnowsNothingOfSpring` fails, something annotated a domain class — most likely `@ConfigurationProperties` drifted back onto `LendingPolicy`; the binding belongs in `platform.config` (Task 3). If `entitiesLiveOnlyInModuleInternals` fails, an entity was declared outside an `internal` package. If `nobodyReadsTheWallClockDirectly` fails, find the call and inject `Clock` instead.

- [ ] **Step 3: Write the OpenAPI configuration**

`src/main/java/com/elibrary/platform/openapi/OpenApiConfig.java`

```java
package com.elibrary.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lets a reviewer click through the API before reading any code. */
@Configuration
class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    @Bean
    OpenAPI elibraryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-Library Service")
                        .version("0.1.0")
                        .description("""
                                Browse, borrow, and return digital library content.

                                Authenticate with HTTP Basic. Seeded members: alice, bob, carol \
                                (role MEMBER) and librarian (role LIBRARIAN). The password for all \
                                four is `password`.

                                Errors follow RFC 9457. Every business refusal is 409; branch on the \
                                `code` field rather than the status, because codes can grow without \
                                breaking clients.
                                """))
                .components(new Components().addSecuritySchemes(BASIC_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
```

- [ ] **Step 4: Confirm the remaining security assertions now pass**

Run: `mvn -q test -Dtest=SecurityConfigTest`
Expected: PASS, 4 tests. The `healthAndApiDocsAreOpen` method was waiting on springdoc being configured and permitted.

- [ ] **Step 5: Run the full build**

Run: `mvn -q clean verify`
Expected: PASS, all unit and integration tests.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/elibrary/architecture src/main/java/com/elibrary/platform/openapi
git commit -m "test: enforce module boundaries and domain purity with ArchUnit

Ten rules pin the claims the README makes: no Spring or JPA in the lending
domain, no reaching across module internals, no entity outside an internal
package, no Spring Page on the wire, and no direct wall-clock reads. Adds
springdoc config so a reviewer can explore the API before reading code."
```

---

### Task 16: README

The brief names the README as a deliverable and says the reviewers care more about thought process and trade-offs than feature completeness. This task is therefore not documentation cleanup — it is where most of the argument is made.

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: the finished application.
- Produces: the second deliverable.

- [ ] **Step 1: Write `README.md`**

````markdown
# E-Library Service

A backend service for a digital library: browse a catalogue of books and journals, borrow
items, return them, and see what you currently hold.

## Quick start

Requires **JDK 21** and nothing else — no database, no Docker.

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080/swagger-ui.html>.

```bash
mvn clean verify     # full build: unit + integration tests
mvn test             # unit tests only (fast)
```

The database is in-memory H2, migrated and seeded by Flyway on startup with 15 items.

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

### Examples

```bash
# Browse, available only, sorted by year, page 1
curl -u alice:password \
  'http://localhost:8080/api/v1/books?q=domain&available=true&sort=publicationYear,desc&page=0&size=5'

# Borrow
curl -u alice:password -X POST http://localhost:8080/api/v1/loans \
  -H 'Content-Type: application/json' \
  -d '{"bookId":"11111111-1111-1111-1111-111111111101"}'

# Currently borrowed
curl -u alice:password http://localhost:8080/api/v1/loans

# Return
curl -u alice:password -X POST http://localhost:8080/api/v1/loans/<loanId>/return
```

Query parameters for `/books`: `q` (title or author), `author`, `available`,
`sort` (`title|author|publicationYear` plus `,asc|,desc`), `page`, `size` (max 100).
For `/loans`: `status` (`active|returned|all`), `page`, `size`.

## Architecture

```
                 ┌──────────────────────────────────────────┐
   HTTP  ───────▶│  catalog.web          lending.web        │
                 ├──────────────────────────────────────────┤
                 │                       lending.application│
                 │  catalog (public      ┌──────────────────┐│
                 │  read API)            │ lending.domain   ││
                 │      ▲                │  Loan            ││
                 │      │                │  ActiveLoans     ││
                 │  catalog.internal     │  LendingPolicy   ││
                 │   BookRecord          │  ports:          ││
                 │   JpaBookCatalog      │   LoanRepository ││
                 │   CatalogBook─────────┼─▶ BookInventory  ││
                 │   Inventory           └──────────────────┘│
                 │                       lending.internal    │
                 ├──────────────────────────────────────────┤
                 │  platform (security, error mapping, clock)│
                 │  shared (value objects, PageResult)       │
                 └──────────────────────────────────────────┘
```

### Why the two modules are not symmetric

This is the central decision, and it is deliberate.

**`lending` is genuine hexagonal.** All of the business complexity lives here: four
invariants, a state transition, a policy. The `Loan` aggregate is immutable plain Java with
no framework annotations; it defines its own ports (`LoanRepository`, `BookInventory`) and
the JPA adapter sits outside in `lending.internal`. Isolation costs a separate entity and an
explicit mapper, and here that cost buys something real — the rules are unit-testable in
milliseconds with no container and no database.

**`catalog` is CQRS-lite.** It is read-mostly. Giving it a matching aggregate, repository
port, and mapper stack would spend a large share of the budget on plumbing that protects
invariants which do not exist. Instead it exposes a small public read API (`BookCatalog` plus
three records) over a package-private JPA internal.

Complexity is allocated in proportion to business risk. A uniform application of either
pattern would have been the easier answer to defend in the abstract and the worse answer
here.

### Enforced, not asserted

Module internals are **package-private**. `lending` cannot import `BookJpaRepository`
because the compiler forbids it. `src/test/java/com/elibrary/architecture/ArchitectureTest.java`
adds ten ArchUnit rules that fail the build if the domain grows a Spring import, an entity
escapes an `internal` package, Spring's `Page` reaches the wire, or anyone calls
`LocalDate.now()` directly. Every architectural claim in this README has a corresponding
test, because a claim without one becomes false around the twentieth commit.

## Domain model and invariants

| # | Invariant | Enforced in |
|---|---|---|
| 1 | Borrow only when a copy is available | `catalog` — `CatalogBookInventory.checkout`, under a write lock, plus a database check constraint |
| 2 | A member may not exceed `maxConcurrentLoans` | `ActiveLoans.borrow` |
| 3 | A member may not hold two active loans of the same book | `ActiveLoans.borrow` |
| 4 | A loan has a due date; returning closes it and restores the copy | `Loan.open` / `Loan.returnNow` + `BookInventory.restore` |

Invariants 2 and 3 live in `ActiveLoans`, a domain type representing the member's lending
position, rather than in the application service. `ActiveLoans.borrow` is the only route to
constructing a `Loan`, so the rules cannot be bypassed. The application services contain no
business logic at all:

```java
ActiveLoans position = loans.activeFor(memberId);
Loan loan = position.borrow(bookId, policy, clock);   // invariants 2 and 3
inventory.checkout(bookId);                            // invariant 1, takes the lock
return loans.save(loan);
```

The policy numbers are configuration, not code:

```yaml
elibrary:
  lending:
    max-concurrent-loans: 5
    loan-period-days: 14
```

## Decisions and trade-offs

**The catalogue owns copy availability.** Availability is `totalCopies − activeLoans`, a
figure sourced from two modules. Left unresolved, the `?available=true` filter forces a
cross-module join and the boundary is fiction. So `catalog` keeps a running
`available_copies` and `lending` never computes it — it calls a port that `lending` itself
defines and `catalog` implements. The browse filter stays a single-table query, so paging
totals are correct. The honest limit: once these become separate services, the two-step
borrow needs a saga with a compensating action. `BookInventory` is exactly that seam.

**Pessimistic locking, not optimistic retry.** `checkout` reads the book row `FOR UPDATE`.
Borrowing contends on one row inside a very short transaction, which is where pessimistic
locking wins: the loser waits a few milliseconds instead of receiving a 409 on work that
would have succeeded. Optimistic locking plus retry is the better choice when contention is
rare and transactions are long — the opposite of this case. `@Version` is still present on
`BookRecord` as a safety net against writes that bypass the locked read path.
`LastCopyConcurrencyIT` contends eight members on a single remaining copy and asserts exactly
one winner, so this is a verified decision rather than a hopeful annotation.

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
{ "type": "https://elibrary.example/problems/loan-limit-reached",
  "title": "Loan limit reached",
  "status": 409,
  "detail": "Member alice already has 5 active loans (limit 5).",
  "code": "LOAN_LIMIT_REACHED",
  "instance": "/api/v1/loans" }
```

The domain exposes exactly two abstract exception bases (`NotFoundException`,
`ConflictException`), so the HTTP mapping is two handler methods and a new domain error ships
without touching the web layer. Unexpected failures are logged with the request line and
answered with a fixed message — exception text routinely carries connection strings or user
data.

**Someone else's loan returns 404, not 403.** 403 confirms the loan exists. Applied
consistently to `GET /loans/{id}` and `POST /loans/{id}/return`.

**`POST /loans`, not `POST /books/{id}/borrow`.** The thing created is a `Loan`; borrowing
*is* loan creation. That is also what gives the loan the identity needed to return it, and
the 201 carries a `Location` pointing at a genuinely retrievable resource.

**`POST /loans/{id}/return` is a conscious departure from strict REST.** The pure form,
`PATCH /loans/{id}` with `{"status":"RETURNED"}`, hands the state transition to the client
and makes illegal transitions expressible in a request body. A named action is more honest
about the domain.

**No member id in any URL.** Identity comes from the authenticated principal, translated into
a `MemberId` by an argument resolver at the boundary. Nothing behind the controller knows
Spring Security exists, and no member's data can be requested by guessing a URL.

**H2 with Flyway, not Postgres with Testcontainers.** Docker is unavailable in the
environment this was built in, and shipping tests the author has never executed would be
worse than shipping none. H2 runs in PostgreSQL compatibility mode and the schema is
Flyway-managed with `ddl-auto: validate`, so it is a reviewed artefact rather than a
Hibernate side effect and the move to Postgres is a datasource change.

**No `members` table.** `MemberId` comes from the principal, and none of the four invariants
need member data. Member management is a different domain. This is a scope decision stated as
an explicit assumption, not an oversight — and it is the decision in this list I would most
expect to be challenged.

**Invariant 3 is not a database constraint.** On PostgreSQL it would be
`create unique index … on loans (member_id, book_id) where returned_at is null`. H2 does not
support partial unique indexes, so it is enforced in the domain only. Worth knowing as a real
gap between the development and production environments.

**Sortable fields are whitelisted by an enum.** Passing a client string into Spring's `Sort`
lets a caller order by any persistent property, which leaks the persistence model. An
unrecognised field is a 400 rather than a silent fallback: a client that asked for an ordering
and did not get it will build wrong behaviour on the assumption that it did.

**Entities are mapped in Java, not projected in JPQL.** A constructor expression cannot build
`BookId`/`Isbn` value objects, and at this row width the saving is negligible. On a wide table
a dedicated projection would earn its keep.

## Testing

```bash
mvn test        # domain, application, web, persistence
mvn verify      # the above plus *IT integration and concurrency tests
```

| Layer | Tooling | Subject |
|---|---|---|
| Domain | JUnit 5 + AssertJ, no Spring | `Loan`, `ActiveLoans`, `LendingPolicy` with `Clock.fixed` |
| Application | JUnit + hand-written fakes | orchestration and error propagation |
| Web | standalone `MockMvc` | binding, response shape, error mapping |
| Persistence | `@DataJpaTest` | queries, paging, copy guards |
| End-to-end | `@SpringBootTest` + `MockMvc` | all five capabilities over HTTP with real auth |
| Concurrency | `@SpringBootTest` + `ExecutorService` | eight members, one copy, exactly one winner |
| Architecture | ArchUnit | domain purity and module isolation |

Repository ports are backed by **fakes, not mocks**. `InMemoryLoanRepository` is about
twenty-five lines and produces tests that assert behaviour rather than interaction order;
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
| Cursor pagination | Offset is correct for a seeded catalogue. Cursor paging is the right answer once the table is large and writes are frequent |
| Idempotency keys | Invariant 3 already makes a repeated borrow of the same book safe |
| Fines and overdue penalties | Policy-heavy and adds no architectural signal |

## If this went to production

- **Postgres and Testcontainers.** The schema is already Flyway-managed and dialect-neutral;
  add the partial unique index for invariant 3.
- **Domain events and an outbox.** `BookReturned` is the natural first event, and it is what a
  reservation queue would consume. A transactional outbox keeps publication atomic with the
  state change.
- **Observability.** Actuator is present but minimal. Real deployment wants structured logging
  with a correlation id, metrics on borrow/return rates and lock wait time, and tracing across
  the two modules — lock contention is the first thing that will hurt under load.
- **Rate limiting** on borrow, to make the contended path harder to abuse.
- **Splitting the modules into services** is the interesting one. The compiler-enforced
  boundary means the code would move cleanly, but the two-step borrow stops being atomic. It
  would become a reservation with a timeout and a compensating release, coordinated through the
  existing `BookInventory` seam. That is the moment the current design's central simplification
  has to be paid for, and it is deliberate that the payment is localised to one interface.
````

- [ ] **Step 2: Verify every command in the README actually works**

Run each, in order, and confirm the documented behaviour:

```bash
mvn clean verify
mvn spring-boot:run &
sleep 20
curl -s -u alice:password 'http://localhost:8080/api/v1/books?available=true&size=3' | head -c 400
curl -s -u alice:password -X POST http://localhost:8080/api/v1/loans \
  -H 'Content-Type: application/json' \
  -d '{"bookId":"11111111-1111-1111-1111-111111111101"}'
curl -s -u alice:password http://localhost:8080/api/v1/loans
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/swagger-ui.html
kill %1
```

Expected: `verify` green; the browse call returns an `items` array with an `available` field;
the borrow returns 201-shaped JSON with a `book.title`; the loans call lists it; swagger-ui
returns 200 or 302. A README that documents a command nobody ran is worse than no README.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add README covering architecture, trade-offs, and scope

Argues the central decisions rather than only describing them: why the two
modules carry different architectural weight, why the catalogue owns
availability, why pessimistic locking beats optimistic retry here, why overdue
is derived, and why every business refusal is a 409 keyed on a stable code.
States what was left out and why, including the one decision most likely to be
challenged."
```

- [ ] **Step 4: Review the git history as a reviewer would**

Run: `git log --oneline`
Expected: an incremental, readable sequence — scaffold, shared kernel, policy, aggregate,
invariants, ports and use cases, catalogue, adapters, security, errors, endpoints, tests,
README. Senior reviewers read history as evidence of working method. If any commit bundles
unrelated changes, that is worth fixing with an interactive rebase before submitting.

---

## Self-Review

Checked after writing, against the spec.

**Spec coverage.** Every spec section maps to a task: §2 stack → Task 1; §3.1 layout → Tasks
2, 7, 9; §3.2 availability ownership → Tasks 6–8; §3.3 concurrency → Tasks 8, 14; §4.1 `Loan`
→ Task 4; §4.2 invariants → Tasks 5, 8; §4.3 policy → Task 3; §4.4 services → Task 6; §5 API
→ Tasks 12, 13; §6 errors → Task 11; §7 data → Task 1; §8 testing → Tasks 14, 15; §9 out of
scope and §10 README → Task 16; §12 definition of done → Tasks 15, 16.

**Three spec corrections, each stated in the task that makes it.**

1. `@ConfigurationProperties` cannot sit on `LendingPolicy` (spec §4.3) without violating the
   ArchUnit rule in §8. Binding moved to `platform.config` (Task 3).
2. The `query`/`store`/`spi` split (§3.1) cannot be package-private, since Java visibility is
   per-package. Internals collapsed into `catalog.internal` and `lending.internal` (Task 7).
3. `GET /loans/{loanId}` was added during spec review so the `Location` header on a 201 points
   at a retrievable resource.

**Two additions the spec did not anticipate.** Loan responses need book titles, so
`lending.web` enriches against the public `BookCatalog` with a batch lookup (Task 13); and
`BookInventory` uses `Propagation.MANDATORY` so the shared-transaction requirement fails loudly
rather than silently committing half a borrow (Task 8).

**Type consistency.** `BookId`/`MemberId`/`LoanId`/`PageResult` signatures are identical
everywhere they appear. `LoanRepository`'s four methods match the fake, the JPA adapter, and
every call site. `ViewLoans` is named consistently (the spec's `ViewActiveLoans` was corrected
during spec review). `BookInventory.checkout`/`restore` match across port, fake, adapter, and
tests.

**Placeholder scan.** No `TBD`, no "add error handling", no "similar to Task N", no test
described without its code. Every code step carries the real content.

**One intentional deviation from the usual red-green rhythm.** `SecurityConfigTest` (Task 10)
is deliberately partially red until Tasks 12 and 15 add the controller and springdoc config.
Tasks 10, 12, and 15 each state exactly which methods must pass at that point. Likewise
`ArchitectureTest` (Task 15) is written to pass on arrival — it pins earlier decisions rather
than driving new code, so a failure there means a previous task drifted.
