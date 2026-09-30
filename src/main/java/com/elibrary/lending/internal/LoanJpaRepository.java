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
