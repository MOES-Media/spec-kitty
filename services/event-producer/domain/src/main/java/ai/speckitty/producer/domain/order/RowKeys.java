package ai.speckitty.producer.domain.order;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

/** Reads the key members of a row. The checks run in a fixed order (object, id, clock, time, source). */
public final class RowKeys {

    private RowKeys() {}

    /** The key of {@code row}; throws {@link UnorderableRow} when it cannot be keyed. */
    public static OrderKey of(Object row) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The normalised id; empty or absent is {@code id_missing}, anything that does not normalise {@code id_not_normalizable}. */
    public static String id(Map<?, ?> row) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The clock, or empty for a row with no {@code lamportclock} member; a present invalid value throws. */
    public static OptionalInt clock(Map<?, ?> row) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The exact instant of {@code time}; anything else throws {@code time_unparseable}. */
    public static Instant time(Map<?, ?> row) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The source; an absent or {@code null} member is the empty string, a non-string throws {@code source_invalid}. */
    public static String source(Map<?, ?> row) {
        throw new UnsupportedOperationException("red stub");
    }
}
