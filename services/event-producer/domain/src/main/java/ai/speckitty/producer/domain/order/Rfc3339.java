package ai.speckitty.producer.domain.order;

import java.time.Instant;
import java.util.Optional;

/** The RFC 3339 {@code date-time} reader of the total order: exact instants, nine fractional digits at most. */
public final class Rfc3339 {

    private Rfc3339() {}

    /** The instant {@code text} denotes, empty when it is not a string or not an accepted RFC 3339 timestamp. */
    public static Optional<Instant> parse(Object text) {
        throw new UnsupportedOperationException("red stub");
    }
}
