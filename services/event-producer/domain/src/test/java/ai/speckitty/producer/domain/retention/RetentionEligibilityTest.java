package ai.speckitty.producer.domain.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetentionEligibilityTest {

    private static final RetentionPolicy POLICY = new RetentionPolicy(20, 30);
    private static final Duration OLD = Duration.ofDays(31);

    private static RetentionFacts facts(int rank, Duration age, MissionState mission, LockState lock, boolean unfinished) {
        return new RetentionFacts(rank, age, mission, lock, unfinished);
    }

    private static RetentionFacts closedOutside() {
        return facts(21, OLD, MissionState.CLOSED, LockState.NONE, false);
    }

    private static Decision.Kept kept(RetentionFacts facts) {
        Decision decision = RetentionEligibility.decide(facts, POLICY);
        assertThat(decision).isInstanceOf(Decision.Kept.class);
        return (Decision.Kept) decision;
    }

    @Test
    void outsideBothLimitsClosedUnlockedAndFinishedIsEligible() {
        assertThat(RetentionEligibility.decide(closedOutside(), POLICY)).isEqualTo(new Decision.Eligible());
        assertThat(KeepReason.ELIGIBLE).isEqualTo("closed-mission-outside-limits");
    }

    @Test
    void aFileWithoutAMissionIsEligibleOutsideTheLimits() {
        RetentionFacts facts = facts(21, OLD, MissionState.NOT_APPLICABLE, LockState.NONE, false);
        assertThat(RetentionEligibility.decide(facts, POLICY)).isEqualTo(new Decision.Eligible());
    }

    @Test
    void theNewestKeepRunsAreKeptOnTheLimitAndTheNextIsEligible() {
        assertThat(kept(facts(20, OLD, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_KEEP_RUNS);
        assertThat(kept(facts(1, OLD, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_KEEP_RUNS);
        assertThat(RetentionEligibility.decide(facts(21, OLD, MissionState.CLOSED, LockState.NONE, false), POLICY)).isInstanceOf(Decision.Eligible.class);
    }

    @Test
    void aFileOlderThanMaxAgeDaysIsEligibleOnlyStrictlyOlder() {
        Duration limit = Duration.ofDays(30);
        assertThat(kept(facts(21, limit, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_MAX_AGE);
        assertThat(kept(facts(21, limit.minusNanos(1), MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_MAX_AGE);
        assertThat(RetentionEligibility.decide(facts(21, limit.plusNanos(1), MissionState.CLOSED, LockState.NONE, false), POLICY))
                .isInstanceOf(Decision.Eligible.class);
    }

    @Test
    void aFileFromTheFutureOrOfZeroAgeIsKept() {
        assertThat(kept(facts(21, Duration.ofDays(-1), MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_MAX_AGE);
        assertThat(kept(facts(21, Duration.ZERO, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_MAX_AGE);
    }

    @Test
    void bothLimitsMustBeExceededNotJustOne() {
        // old but among the newest: kept; young but outside the count: kept.
        assertThat(kept(facts(5, OLD, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_KEEP_RUNS);
        assertThat(kept(facts(500, Duration.ofDays(1), MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_MAX_AGE);
    }

    @Test
    void theLimitsFollowThePolicy() {
        RetentionPolicy tight = new RetentionPolicy(1, 1);
        RetentionFacts second = facts(2, Duration.ofDays(1).plusNanos(1), MissionState.CLOSED, LockState.NONE, false);
        assertThat(RetentionEligibility.decide(second, tight)).isInstanceOf(Decision.Eligible.class);
        assertThat(RetentionEligibility.decide(second, POLICY)).isInstanceOf(Decision.Kept.class);
    }

    @Test
    void anOpenMissionIsKeptWhateverTheLimits() {
        assertThat(kept(facts(99, OLD, MissionState.OPEN, LockState.NONE, false)).reason()).isEqualTo(KeepReason.MISSION_OPEN);
    }

    @Test
    void unavailableFactsKeepTheFile() {
        assertThat(kept(facts(99, OLD, MissionState.UNAVAILABLE, LockState.NONE, false)).reason()).isEqualTo(KeepReason.FACTS_UNAVAILABLE);
    }

    @Test
    void aLiveLockKeepsTheFileAndAnUnreadableOneToo() {
        assertThat(kept(facts(99, OLD, MissionState.CLOSED, LockState.LIVE, false)).reason()).isEqualTo(KeepReason.LOCKED);
        assertThat(kept(facts(99, OLD, MissionState.CLOSED, LockState.UNREADABLE, false)).reason()).isEqualTo(KeepReason.LOCK_UNREADABLE);
    }

    @Test
    void anUnfinishedStepKeepsTheFile() {
        assertThat(kept(facts(99, OLD, MissionState.CLOSED, LockState.NONE, true)).reason()).isEqualTo(KeepReason.UNFINISHED_STEP);
    }

    @Test
    void theReasonIsTheFirstThatAppliesInTheDocumentedOrder() {
        RetentionFacts everything = facts(1, Duration.ZERO, MissionState.UNAVAILABLE, LockState.LIVE, true);
        assertThat(kept(everything).reason()).isEqualTo(KeepReason.FACTS_UNAVAILABLE);
        assertThat(kept(facts(1, Duration.ZERO, MissionState.OPEN, LockState.LIVE, true)).reason()).isEqualTo(KeepReason.MISSION_OPEN);
        assertThat(kept(facts(1, Duration.ZERO, MissionState.CLOSED, LockState.LIVE, true)).reason()).isEqualTo(KeepReason.LOCKED);
        assertThat(kept(facts(1, Duration.ZERO, MissionState.CLOSED, LockState.UNREADABLE, true)).reason()).isEqualTo(KeepReason.LOCK_UNREADABLE);
        assertThat(kept(facts(1, Duration.ZERO, MissionState.CLOSED, LockState.NONE, true)).reason()).isEqualTo(KeepReason.UNFINISHED_STEP);
        assertThat(kept(facts(1, Duration.ZERO, MissionState.CLOSED, LockState.NONE, false)).reason()).isEqualTo(KeepReason.WITHIN_KEEP_RUNS);
    }

    @Test
    void factsRefuseARankBelowOneAndNulls() {
        assertThatThrownBy(() -> facts(0, OLD, MissionState.CLOSED, LockState.NONE, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts(1, null, MissionState.CLOSED, LockState.NONE, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> facts(1, OLD, null, LockState.NONE, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> facts(1, OLD, MissionState.CLOSED, null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theKeepReasonVocabularyIsPinned() {
        assertThat(java.util.Arrays.stream(KeepReason.values()).map(KeepReason::wire).toList()).containsExactly(
                "facts-unavailable", "mission-open", "locked", "lock-unreadable", "unfinished-step", "within-keep-runs", "within-max-age",
                "busy", "delete-failed");
    }
}
