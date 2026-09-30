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
