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
