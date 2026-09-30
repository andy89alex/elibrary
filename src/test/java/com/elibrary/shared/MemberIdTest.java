package com.elibrary.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberIdTest {

    @Test
    void holdsThePrincipalName() {
        assertThat(new MemberId("alice").value()).isEqualTo("alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankValues(String blank) {
        assertThatThrownBy(() -> new MemberId(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new MemberId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIsByValueSoItWorksAsAMapKey() {
        assertThat(new MemberId("alice")).isEqualTo(new MemberId("alice"));
    }
}
