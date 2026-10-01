package com.elibrary.shared;

import java.util.List;
import java.util.function.Function;

/**
 * Our pagination envelope. Spring's {@code Page<T>} is never serialised: its JSON shape
 * is not stable across Spring versions, so exposing it would bind our public contract to
 * the framework. Offset paging is used; cursor paging would be the right call at scale.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
