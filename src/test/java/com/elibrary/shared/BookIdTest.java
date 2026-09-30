package com.elibrary.shared;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookIdTest {

    @Test
    void parsesAWellFormedUuid() {
        BookId id = BookId.of("11111111-1111-1111-1111-111111111101");
        assertThat(id.value()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111101"));
    }

    @Test
    void rendersAsTheBareUuidSoItIsSafeToPutInJson() {
        BookId id = BookId.of("11111111-1111-1111-1111-111111111101");
        assertThat(id).hasToString("11111111-1111-1111-1111-111111111101");
    }

    @Test
    void rejectsAMalformedIdWithAMessageNamingTheInput() {
        assertThatThrownBy(() -> BookId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-uuid");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new BookId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void generatedIdsAreDistinct() {
        assertThat(BookId.newId()).isNotEqualTo(BookId.newId());
    }
}
