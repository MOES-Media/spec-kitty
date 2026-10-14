package ai.speckitty.producer.domain.retention;

/** Why a local-tier file is kept. */
public enum KeepReason {
    FACTS_UNAVAILABLE("facts-unavailable"),
    MISSION_OPEN("mission-open"),
    LOCKED("locked"),
    LOCK_UNREADABLE("lock-unreadable"),
    UNFINISHED_STEP("unfinished-step"),
    WITHIN_KEEP_RUNS("within-keep-runs"),
    WITHIN_MAX_AGE("within-max-age"),
    /** The delete could not take the clock lock in time (decided by the adapter, not by eligibility). */
    BUSY("busy"),
    /** The delete failed (decided by the adapter, not by eligibility). */
    DELETE_FAILED("delete-failed");

    /** The reason an eligible file carries. */
    public static final String ELIGIBLE = "closed-mission-outside-limits";

    private final String wire;

    KeepReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
