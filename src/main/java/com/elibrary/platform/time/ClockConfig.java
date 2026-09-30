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
