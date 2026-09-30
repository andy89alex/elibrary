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
