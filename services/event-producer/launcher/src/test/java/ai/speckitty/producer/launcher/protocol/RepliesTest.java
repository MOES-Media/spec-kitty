package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.protocol.Notice;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.launcher.Replies;
import org.junit.jupiter.api.Test;

class RepliesTest {

    private static final String ID = "01J0000000000000000000F001";
    private static final String TYPE = "ai.spec-kitty.lane.transitioned.v1";

    private static String wire(ProduceReply reply) {
        return Json.render(Replies.produce(reply));
    }

    @Test
    void aProducedReplyCarriesTheVersionTheOperationTheOutcomeTheIdAndTheType() {
        assertThat(wire(new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, null, null)))
                .isEqualTo("{\"event_id\":\"" + ID + "\",\"op\":\"produce\",\"outcome\":\"produced\",\"type\":\"" + TYPE + "\",\"v\":1}");
    }

    @Test
    void aSkippedDuplicateIsNamedAsSuch() {
        assertThat(wire(new ProduceReply(1, Outcome.SKIPPED_DUPLICATE, ID, TYPE, null, null, null))).contains("\"outcome\":\"skipped_duplicate\"");
    }

    @Test
    void aRefusalCarriesItsReasonItsClassAndItsDetail() {
        assertThat(wire(new ProduceReply(1, Outcome.REFUSED, ID, null, RefusalReason.ID_NOT_ULID, null, "event id is a UUID")))
                .isEqualTo("{\"detail\":\"event id is a UUID\",\"event_id\":\"" + ID
                        + "\",\"op\":\"produce\",\"outcome\":\"refused\",\"reason\":\"id-not-ulid\",\"reason_class\":\"rejected\",\"v\":1}");
    }

    @Test
    void theClassOfARefusalIsItsFaultClass() {
        assertThat(wire(new ProduceReply(1, Outcome.REFUSED, null, null, RefusalReason.CLOCK_OVERFLOW, null, null)))
                .contains("\"reason\":\"clock-overflow\"").contains("\"reason_class\":\"clock-overflow\"");
        assertThat(wire(new ProduceReply(1, Outcome.REFUSED, null, null, RefusalReason.BUSY, null, null)))
                .contains("\"reason\":\"busy\"").contains("\"reason_class\":\"busy\"");
        assertThat(wire(new ProduceReply(1, Outcome.REFUSED, null, null, RefusalReason.VALIDATOR_UNAVAILABLE, null, null)))
                .contains("\"reason\":\"validator-unavailable\"").contains("\"reason_class\":\"rejected\"");
        for (RefusalReason reason : RefusalReason.values()) {
            String text = wire(new ProduceReply(1, Outcome.REFUSED, null, null, reason, null, null));
            assertThat(text).contains("\"reason\":\"" + reason.wire() + "\"").contains("\"reason_class\":\"" + reason.faultClass().wire() + "\"");
        }
    }

    @Test
    void anAbsentIdAndAnAbsentDetailAreOmittedNeverNull() {
        String text = wire(new ProduceReply(1, Outcome.REFUSED, null, null, RefusalReason.ROOT_UNRESOLVABLE, null, null));
        assertThat(text).isEqualTo("{\"op\":\"produce\",\"outcome\":\"refused\",\"reason\":\"root-unresolvable\",\"reason_class\":\"rejected\",\"v\":1}");
        assertThat(text).doesNotContain("null");
    }

    @Test
    void aTornLineNoticeIsAnObjectWithItsKindItsRelativePathAndItsLine() {
        Notice notice = new Notice(new RelativePath(".kittify/events/publish/missions/" + ID + ".jsonl"), 42);
        assertThat(wire(new ProduceReply(1, Outcome.PRODUCED, ID, TYPE, null, notice, null)))
                .contains("\"notice\":{\"kind\":\"torn-line\",\"line\":42,\"path\":\".kittify/events/publish/missions/" + ID + ".jsonl\"}");
    }

    @Test
    void anUnsupportedOperationIsARefusedReplyWithNoOperationEcho() {
        assertThat(Json.render(Replies.unsupported())).isEqualTo("{\"outcome\":\"refused\",\"reason\":\"unsupported-operation\",\"v\":1}");
    }
}
