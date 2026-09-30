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
