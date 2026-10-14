package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.fault.FaultReason;

/**
 * The reply to a {@code produce}: exactly one per request.
 *
 * @param version the protocol version, 1
 * @param outcome produced, skipped as a duplicate, or refused
 * @param eventId the normalised id, present when the row carried a normalisable one
 * @param type the event type, present when produced or skipped
 * @param reason the closed refusal reason, present exactly when refused
 * @param notice an optional torn-line notice, never with a refusal
 * @param detail one line, repo-relative paths only, or null
 */
public record ProduceReply(
        int version, Outcome outcome, String eventId, String type, RefusalReason reason, Notice notice, String detail) {

    public static final int MAX_DETAIL_LENGTH = 500;

    public ProduceReply {
        throw new UnsupportedOperationException("red stub");
    }

    /** The fault class a refusal maps to; null unless refused. */
    public FaultReason reasonClass() {
        throw new UnsupportedOperationException("red stub");
    }
}
