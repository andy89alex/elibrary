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
