package ai.speckitty.producer.domain.ids;

import ai.speckitty.producer.domain.fault.ParseResult;

/** An event id the producer accepts: a 26-character ULID, written upper-case (FR-007). */
public record EventId(String value) {

    public EventId {
        throw new UnsupportedOperationException("red stub");
    }

    /** Parses a ledger {@code event_id}. Every UUID form and everything that is not a ULID is refused as {@code rejected}. */
    public static ParseResult<EventId> parseUlid(String text) {
        throw new UnsupportedOperationException("red stub");
    }
}
