package com.elibrary.lending.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface LoanJpaRepository extends JpaRepository<LoanEntity, UUID> {

    List<LoanEntity> findByMemberIdAndReturnedAtIsNull(String memberId);

    Page<LoanEntity> findByMemberIdAndReturnedAtIsNull(String memberId, Pageable pageable);

    Page<LoanEntity> findByMemberIdAndReturnedAtIsNotNull(String memberId, Pageable pageable);

    Page<LoanEntity> findByMemberId(String memberId, Pageable pageable);

    /**
     * The librarian ledger. Each filter neutralises itself when unset — a null id or the
     * {@code ALL} status leaves its clause true — so callers may supply any subset, and
     * supplying several narrows rather than widens.
     *
     * <p>Overdue compares {@code dueOn} against a date the caller passes in, because
     * overdue is derived rather than stored: there is no column to filter on, and reading
     * the clock here would put a time source in the persistence layer.
     */
    @Query("""
            select l from LoanEntity l
            where (:memberId is null or l.memberId = :memberId)
              and (:bookId is null or l.bookId = :bookId)
              and (:status = 'ALL'
                   or (:status = 'ACTIVE' and l.returnedAt is null)
                   or (:status = 'RETURNED' and l.returnedAt is not null))
              and (:overdueOnly = false or (l.returnedAt is null and l.dueOn < :today))
            """)
    Page<LoanEntity> search(@Param("memberId") String memberId,
                            @Param("bookId") UUID bookId,
                            @Param("status") String status,
                            @Param("overdueOnly") boolean overdueOnly,
                            @Param("today") LocalDate today,
                            Pageable pageable);
}
