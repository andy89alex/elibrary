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
