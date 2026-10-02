package com.elibrary.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic over a fixed set of members.
 *
 * <p>Authentication is deliberately the cheapest thing that is still real. Issuing JWTs was
 * not asked for and would consume budget that the domain needs; what matters architecturally
 * is that identity is resolved at the boundary and travels inward as a {@code MemberId},
 * which an OIDC-backed setup would do in exactly the same place.
 *
 * <p>CSRF is disabled because the API is stateless and token-free: there is no cookie for a
 * third-party site to ride on. Sessions are disabled for the same reason.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ProblemDetailEntryPoint entryPoint,
                                    ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // One prefix carries every librarian-only resource, so authorisation
                        // stays a single rule here rather than an annotation per handler.
                        // LibrarianLedgerIT asserts the rule from outside: a path added under
                        // /admin without this guard would not quietly ship unprotected.
                        .requestMatchers("/api/v1/admin/**").hasRole("LIBRARIAN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        String password = encoder.encode("password");
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password(password).roles("MEMBER").build(),
                User.withUsername("bob").password(password).roles("MEMBER").build(),
                User.withUsername("carol").password(password).roles("MEMBER").build(),
                User.withUsername("librarian").password(password).roles("LIBRARIAN").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
