package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.ids.EventIdForms;
import java.util.Objects;

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
        if (version != Protocol.VERSION) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        Objects.requireNonNull(outcome, "outcome");
        boolean refused = outcome == Outcome.REFUSED;
        if (refused != (reason != null)) {
            throw new IllegalArgumentException("a reason belongs to a refusal and a refusal needs one");
        }
        if (!refused && type == null) {
            throw new IllegalArgumentException("a produced or skipped reply names its type");
        }
        if (refused && notice != null) {
            throw new IllegalArgumentException("a refusal carries no notice");
        }
        if (eventId != null && !EventIdForms.normalizeForOrder(eventId).filter(eventId::equals).isPresent()) {
            throw new IllegalArgumentException("event id must be normalised");
        }
        if (detail != null && (detail.length() > MAX_DETAIL_LENGTH || detail.indexOf('\n') >= 0 || detail.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("detail is one line of bounded length");
        }
    }

    /** The fault class a refusal maps to; null unless refused. */
    public FaultReason reasonClass() {
        return reason == null ? null : reason.faultClass();
    }
}
