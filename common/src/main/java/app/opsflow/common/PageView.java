package app.opsflow.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** One page of a list, in the same shape from every service. */
public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static <E, T> PageView<T> of(Page<E> p, Function<E, T> map) {
        return new PageView<>(p.getContent().stream().map(map).toList(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    /** Clamps client paging input to sane values. */
    public static int size(int requested) {
        return Math.max(1, Math.min(requested, 100));
    }

    public static int page(int requested) {
        return Math.max(0, Math.min(requested, 10_000));
    }
}
