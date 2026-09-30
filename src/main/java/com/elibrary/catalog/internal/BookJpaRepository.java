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
