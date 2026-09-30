package com.elibrary.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void computesTotalPagesByRoundingUp() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 0, 2, 5);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void reportsZeroPagesForAnEmptyResult() {
        PageResult<String> result = PageResult.of(List.of(), 0, 20, 0);
        assertThat(result.totalPages()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void itemsAreDefensivelyCopiedSoCallersCannotMutateTheEnvelope() {
        List<String> source = new java.util.ArrayList<>(List.of("a"));
        PageResult<String> result = PageResult.of(source, 0, 10, 1);
        source.add("b");
        assertThat(result.items()).containsExactly("a");
    }
}
