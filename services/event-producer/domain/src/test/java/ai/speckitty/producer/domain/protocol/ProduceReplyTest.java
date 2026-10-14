package ai.speckitty.producer.domain.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.fault.FaultReason;
import org.junit.jupiter.api.Test;

class ProduceReplyTest {

    private static final String ID = "01J00000000000000000000ABA";
    private static final String TYPE = "ai.spec-kitty.lane.transitioned.v1";

    @Test
    void aProducedReplyNamesTheTypeAndNoReason() {
        ProduceReply reply = new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, null);
        assertThat(reply.reasonClass()).isNull();
        assertThat(reply.eventId()).isEqualTo(ID);
    }

    @Test
    void aSkippedDuplicateNamesTheTypeToo() {
        ProduceReply reply = new ProduceReply(1, Outcome.SKIPPED_DUPLICATE, ID, TYPE, null, null, "already written");
        assertThat(reply.detail()).isEqualTo("already written");
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.SKIPPED_DUPLICATE, ID, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, ID, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefusalNeedsAReasonAndMapsToItsFaultClass() {
        ProduceReply overflow = new ProduceReply(1, Outcome.REFUSED, ID, null, RefusalReason.CLOCK_OVERFLOW, null, null);
        assertThat(overflow.reasonClass()).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        for (RefusalReason reason : RefusalReason.values()) {
            ProduceReply refused = new ProduceReply(1, Outcome.REFUSED, null, null, reason, null, "d");
            FaultReason expected = switch (reason) {
                case CLOCK_OVERFLOW -> FaultReason.CLOCK_OVERFLOW;
                case BUSY -> FaultReason.BUSY;
                default -> FaultReason.REJECTED;
            };
            assertThat(refused.reasonClass()).isEqualTo(expected);
            assertThat(reason.faultClass()).isEqualTo(refused.reasonClass());
        }
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.REFUSED, ID, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReasonBelongsToARefusalOnly() {
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, RefusalReason.TYPE_UNKNOWN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTornLineNoticeRidesOnAProducedReplyButNeverOnARefusal() {
        Notice notice = new Notice(new RelativePath(".kittify/events/publish/missions/m.jsonl"), 42);
        ProduceReply reply = new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, notice, null);
        assertThat(reply.notice().line()).isEqualTo(42);
        assertThat(Notice.KIND).isEqualTo("torn-line");
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.REFUSED, ID, null, RefusalReason.SCHEMA_INVALID, notice, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNoticeNeedsAPositiveLine() {
        RelativePath path = new RelativePath("a");
        assertThat(new Notice(path, 1).line()).isEqualTo(1);
        assertThatThrownBy(() -> new Notice(path, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Notice(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theEventIdMustBeNormalisedWhenPresent() {
        assertThat(new ProduceReply(1, Outcome.PRODUCED, "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", TYPE, null, null, null).eventId()).isNotNull();
        assertThat(new ProduceReply(1, Outcome.PRODUCED, null, TYPE, null, null, null).eventId()).isNull();
        for (String bad : new String[] {"01j00000000000000000000aba", "nope", "", "0190A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B", "0190a1b2c3d47e5f8a9b0c1d2e3f4a5b"}) {
            assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, bad, TYPE, null, null, null)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theDetailIsOneLineOfBoundedLength() {
        String onLimit = "x".repeat(ProduceReply.MAX_DETAIL_LENGTH);
        assertThat(new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, onLimit).detail()).hasSize(500);
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, onLimit + "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, "a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, "a\rb")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVersionIsOneAndTheOutcomeIsRequired() {
        assertThatThrownBy(() -> new ProduceReply(2, Outcome.PRODUCED, ID, TYPE, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceReply(1, null, ID, TYPE, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theWireVocabulariesArePinned() {
        assertThat(java.util.Arrays.stream(Outcome.values()).map(Outcome::wire).toList()).containsExactly("produced", "skipped_duplicate", "refused");
        assertThat(java.util.Arrays.stream(RefusalReason.values()).map(RefusalReason::wire).toList()).containsExactly(
                "root-unresolvable", "id-not-ulid", "time-unparseable", "schema-invalid", "type-unknown", "clock-overflow", "nodeid-unavailable",
                "validator-unavailable", "busy", "project-unresolved");
        assertThat(Outcome.fromWire("refused")).contains(Outcome.REFUSED);
        assertThat(Outcome.fromWire("disabled")).isEmpty();
        assertThat(RefusalReason.fromWire("type-unknown")).contains(RefusalReason.TYPE_UNKNOWN);
        assertThat(RefusalReason.fromWire("anything")).isEmpty();
    }
}
