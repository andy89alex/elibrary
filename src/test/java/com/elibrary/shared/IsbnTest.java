package com.elibrary.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsbnTest {

    @ParameterizedTest
    @ValueSource(strings = {"9780321125217", "978-0-321-12521-7", "0321125217", "0001-0782", "1049-331X"})
    void acceptsIsbn10Isbn13AndIssnFormats(String raw) {
        assertThat(new Isbn(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12", "97803211252170000000000"})
    void rejectsValuesThatCannotBeAnIdentifier(String raw) {
        assertThatThrownBy(() -> new Isbn(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofPassesNullThroughBecauseTheColumnIsNullable() {
        assertThat(Isbn.of(null)).isNull();
    }
}
