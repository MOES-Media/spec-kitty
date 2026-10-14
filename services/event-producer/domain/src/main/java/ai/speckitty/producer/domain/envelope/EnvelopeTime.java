package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.ParseResult;

/** The {@code time} attribute: the row's own timestamp text, never synthesised (FR-008). */
public final class EnvelopeTime {

    private EnvelopeTime() {}

    /** The timestamp text when {@code rowTimestamp} is an RFC 3339 date-time string, else {@code rejected}. */
    public static ParseResult<String> fromRow(Object rowTimestamp) {
        throw new UnsupportedOperationException("red stub");
    }
}
