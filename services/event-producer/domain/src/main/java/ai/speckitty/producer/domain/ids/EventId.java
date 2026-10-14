package ai.speckitty.producer.domain.ids;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.Locale;

/** An event id the producer accepts: a 26-character ULID, written upper-case (FR-007). */
public record EventId(String value) {

    public EventId {
        if (value == null || !EventIdForms.isUlid(value) || !value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("an event id is an upper-case 26-character ULID");
        }
    }

    /** Parses a ledger {@code event_id}. Every UUID form and everything that is not a ULID is refused as {@code rejected}. */
    public static ParseResult<EventId> parseUlid(String text) {
        if (text == null) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "event id is absent");
        }
        if (EventIdForms.isHyphenatedUuid(text) || EventIdForms.isBareUuid(text)) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "event id is a UUID; a 26-character ULID is required");
        }
        if (!EventIdForms.isUlid(text)) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "event id is not a 26-character ULID");
        }
        return new ParseResult.Accepted<>(new EventId(text.toUpperCase(Locale.ROOT)));
    }
}
