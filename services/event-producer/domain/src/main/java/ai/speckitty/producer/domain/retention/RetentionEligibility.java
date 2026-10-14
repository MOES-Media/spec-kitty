package ai.speckitty.producer.domain.retention;

import java.time.Duration;

/** Eligibility for deletion over supplied facts only (FR-029). A pure function. */
public final class RetentionEligibility {

    private RetentionEligibility() {}

    /**
     * Eligible only when outside both limits (not among the newest {@code keepRuns} AND strictly older than {@code maxAgeDays}),
     * the Mission is not open, no lock is live or unreadable, and no step is unfinished. Otherwise kept, with the first reason of
     * facts-unavailable, mission-open, locked, lock-unreadable, unfinished-step, within-keep-runs, within-max-age.
     */
    public static Decision decide(RetentionFacts facts, RetentionPolicy policy) {
        if (facts.mission() == MissionState.UNAVAILABLE) {
            return new Decision.Kept(KeepReason.FACTS_UNAVAILABLE);
        }
        if (facts.mission() == MissionState.OPEN) {
            return new Decision.Kept(KeepReason.MISSION_OPEN);
        }
        if (facts.lock() == LockState.LIVE) {
            return new Decision.Kept(KeepReason.LOCKED);
        }
        if (facts.lock() == LockState.UNREADABLE) {
            return new Decision.Kept(KeepReason.LOCK_UNREADABLE);
        }
        if (facts.unfinishedStep()) {
            return new Decision.Kept(KeepReason.UNFINISHED_STEP);
        }
        if (facts.rank() <= policy.keepRuns()) {
            return new Decision.Kept(KeepReason.WITHIN_KEEP_RUNS);
        }
        if (facts.age().compareTo(Duration.ofDays(policy.maxAgeDays())) <= 0) {
            return new Decision.Kept(KeepReason.WITHIN_MAX_AGE);
        }
        return new Decision.Eligible();
    }
}
