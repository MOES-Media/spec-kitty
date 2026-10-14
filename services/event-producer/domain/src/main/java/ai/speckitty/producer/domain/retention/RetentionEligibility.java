package ai.speckitty.producer.domain.retention;

/** Eligibility for deletion over supplied facts only (FR-029). A pure function. */
public final class RetentionEligibility {

    private RetentionEligibility() {}

    /**
     * Eligible only when outside both limits (not among the newest {@code keepRuns} AND strictly older than {@code maxAgeDays}),
     * the Mission is not open, no lock is live or unreadable, and no step is unfinished. Otherwise kept, with the first reason of
     * facts-unavailable, mission-open, locked, lock-unreadable, unfinished-step, within-keep-runs, within-max-age.
     */
    public static Decision decide(RetentionFacts facts, RetentionPolicy policy) {
        throw new UnsupportedOperationException("red stub");
    }
}
