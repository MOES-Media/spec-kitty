package ai.speckitty.producer.domain.order;

import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventIdForms;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

/** Reads the key members of a row. The checks run in a fixed order (object, id, clock, time, source). */
public final class RowKeys {

    private RowKeys() {}

    /** The key of {@code row}; throws {@link UnorderableRow} when it cannot be keyed. */
    public static OrderKey of(Object row) {
        if (!(row instanceof Map<?, ?> map)) {
            throw new UnorderableRow(OrderErrorReason.ROW_NOT_OBJECT, "row is not a JSON object");
        }
        String id = id(map);
        OptionalInt clock = clock(map);
        Instant time = time(map);
        String source = source(map);
        return new OrderKey(clock.isPresent() ? 1 : 0, clock.orElse(0), time, id, source);
    }

    /** The normalised id; empty or absent is {@code id_missing}, anything that does not normalise {@code id_not_normalizable}. */
    public static String id(Map<?, ?> row) {
        Object value = row.get("id");
        if (value == null || "".equals(value)) {
            throw new UnorderableRow(OrderErrorReason.ID_MISSING, "id is absent or empty");
        }
        if (value instanceof String text) {
            return EventIdForms.normalizeForOrder(text)
                    .orElseThrow(() -> new UnorderableRow(OrderErrorReason.ID_NOT_NORMALIZABLE, "id has no accepted form"));
        }
        throw new UnorderableRow(OrderErrorReason.ID_NOT_NORMALIZABLE, "id is not a string");
    }

    /** The clock, or empty for a row with no {@code lamportclock} member; a present invalid value throws. */
    public static OptionalInt clock(Map<?, ?> row) {
        if (!row.containsKey("lamportclock")) {
            return OptionalInt.empty();
        }
        ParseResult<LamportClock> parsed = LamportClock.fromJson(row.get("lamportclock"));
        if (parsed instanceof ParseResult.Accepted<LamportClock> accepted) {
            return OptionalInt.of(accepted.value().value());
        }
        throw new UnorderableRow(OrderErrorReason.CLOCK_OUT_OF_RANGE, "lamportclock is not an integer in 0..2^31-1");
    }

    /** The exact instant of {@code time}; anything else throws {@code time_unparseable}. */
    public static Instant time(Map<?, ?> row) {
        return Rfc3339.parse(row.get("time"))
                .orElseThrow(() -> new UnorderableRow(OrderErrorReason.TIME_UNPARSEABLE, "time is not an RFC 3339 timestamp"));
    }

    /** The source; an absent or {@code null} member is the empty string, a non-string throws {@code source_invalid}. */
    public static String source(Map<?, ?> row) {
        Object value = row.get("source");
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        throw new UnorderableRow(OrderErrorReason.SOURCE_INVALID, "source is not a string");
    }
}
