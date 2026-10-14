package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.order.Rfc3339;

/** The {@code time} attribute: the row's own timestamp text, never synthesised (FR-008). */
public final class EnvelopeTime {

    private EnvelopeTime() {}

    /** The timestamp text when {@code rowTimestamp} is an RFC 3339 date-time string, else {@code rejected}. */
    public static ParseResult<String> fromRow(Object rowTimestamp) {
        if (Rfc3339.parse(rowTimestamp).isPresent()) {
            return new ParseResult.Accepted<>((String) rowTimestamp);
        }
        return new ParseResult.Refused<>(FaultReason.REJECTED, "time is absent or not an RFC 3339 timestamp");
    }
}
