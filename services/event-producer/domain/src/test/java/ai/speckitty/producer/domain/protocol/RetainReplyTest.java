package ai.speckitty.producer.domain.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.retention.KeepReason;
import ai.speckitty.producer.domain.retention.PolicyError;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetainReplyTest {

    private static RelativePath path(String name) {
        return new RelativePath(".kittify/events/publish/missions/" + name + ".jsonl");
    }

    private static RetainItem eligible(String name) {
        return new RetainItem(path(name), KeepReason.ELIGIBLE);
    }

    private static RetainItem kept(String name, KeepReason reason) {
        return new RetainItem(path(name), reason.wire());
    }

    @Test
    void aDryRunListsEligibleAndKeptAndDeletesNothing() {
        RetainReply reply = new RetainReply(1, true, List.of(eligible("a")), List.of(kept("b", KeepReason.MISSION_OPEN)), List.of(), null);
        assertThat(reply.eligible()).hasSize(1);
        assertThat(reply.deleted()).isEmpty();
    }

    @Test
    void aDryRunThatDeletedSomethingIsRefused() {
        assertThatThrownBy(() -> new RetainReply(1, true, List.of(eligible("a")), List.of(), List.of(path("a")), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRealRunMayDeleteOnlyWhatWasEligible() {
        RetainReply reply = new RetainReply(1, false, List.of(eligible("a"), eligible("c")), List.of(), List.of(path("a")), null);
        assertThat(reply.deleted()).containsExactly(path("a"));
        assertThatThrownBy(() -> new RetainReply(1, false, List.of(eligible("a")), List.of(kept("b", KeepReason.LOCKED)), List.of(path("b")), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainReply(1, false, List.of(), List.of(), List.of(path("z")), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileIsNeverBothEligibleAndKept() {
        assertThatThrownBy(() -> new RetainReply(1, true, List.of(eligible("a")), List.of(kept("a", KeepReason.BUSY)), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPolicyErrorMeansNothingIsListedOrDeletedOnADryRunAndOnARealRun() {
        PolicyError error = new PolicyError(PolicyErrorKeys.KEEP, PolicyError.INVALID_VALUE);
        for (boolean dryRun : new boolean[] {true, false}) {
            RetainReply reply = new RetainReply(1, dryRun, List.of(), List.of(), List.of(), error);
            assertThat(reply.policyError()).isEqualTo(error);
            assertThatThrownBy(() -> new RetainReply(1, dryRun, List.of(eligible("a")), List.of(), List.of(), error)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RetainReply(1, dryRun, List.of(), List.of(kept("a", KeepReason.MISSION_OPEN)), List.of(), error))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RetainReply(1, dryRun, List.of(), List.of(), List.of(path("a")), error)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anItemReasonMustBeAKnownReason() {
        assertThat(new RetainItem(path("a"), KeepReason.ELIGIBLE).reason()).isEqualTo("closed-mission-outside-limits");
        for (KeepReason reason : KeepReason.values()) {
            assertThat(new RetainItem(path("a"), reason.wire()).reason()).isEqualTo(reason.wire());
        }
        assertThatThrownBy(() -> new RetainItem(path("a"), "because")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainItem(path("a"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainItem(null, KeepReason.ELIGIBLE)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theVersionIsOneAndTheListsAreCopied() {
        assertThatThrownBy(() -> new RetainReply(2, true, List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        java.util.ArrayList<RetainItem> items = new java.util.ArrayList<>(List.of(eligible("a")));
        RetainReply reply = new RetainReply(1, true, items, List.of(), List.of(), null);
        items.clear();
        assertThat(reply.eligible()).hasSize(1);
        assertThatThrownBy(() -> reply.eligible().add(eligible("b"))).isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class PolicyErrorKeys {
        static final String KEEP = "ledger.retention.keep_runs";
    }
}
