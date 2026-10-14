package ai.speckitty.producer.domain.order;

import java.time.Instant;
import java.util.Objects;

/**
 * The sort key {@code (h, c, t, i, s)} of a row: clockless first, the clock, the exact instant, the normalised id, the source.
 * Strings compare by code point.
 */
public record OrderKey(int h, int c, Instant t, String i, String s) implements Comparable<OrderKey> {

    public OrderKey {
        Objects.requireNonNull(t, "t");
        Objects.requireNonNull(i, "i");
        Objects.requireNonNull(s, "s");
    }

    @Override
    public int compareTo(OrderKey other) {
        throw new UnsupportedOperationException("red stub");
    }
}
