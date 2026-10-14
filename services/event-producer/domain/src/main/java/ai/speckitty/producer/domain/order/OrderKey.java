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
        int byH = Integer.compare(h, other.h);
        if (byH != 0) {
            return byH;
        }
        int byC = Integer.compare(c, other.c);
        if (byC != 0) {
            return byC;
        }
        int byT = t.compareTo(other.t);
        if (byT != 0) {
            return byT;
        }
        int byI = CodePoints.compare(i, other.i);
        return byI != 0 ? byI : CodePoints.compare(s, other.s);
    }
}
