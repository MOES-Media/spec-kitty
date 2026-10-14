package ai.speckitty.producer.domain.retention;

/** The retention limits: keep at least the newest {@code keepRuns} runs and anything younger than {@code maxAgeDays} (FR-028). */
public record RetentionPolicy(int keepRuns, int maxAgeDays) {

    public static final int DEFAULT_KEEP_RUNS = 20;
    public static final int DEFAULT_MAX_AGE_DAYS = 30;
    public static final String KEEP_RUNS_KEY = "ledger.retention.keep_runs";
    public static final String MAX_AGE_DAYS_KEY = "ledger.retention.max_age_days";

    /** The outcome of reading the two raw settings. */
    public sealed interface Parsed {
        /** Both settings are valid or absent. */
        record Valid(RetentionPolicy policy) implements Parsed {}

        /** A setting is invalid; nothing may be deleted. */
        record Invalid(PolicyError error) implements Parsed {}
    }

    public RetentionPolicy {
        throw new UnsupportedOperationException("red stub");
    }

    /**
     * Reads the raw values exactly as the client forwarded them. Absent or {@code null} is the default (20 and 30). Anything but a
     * positive integer up to 2^31-1, written as an integer or as a string of digits, is a {@link PolicyError}.
     */
    public static Parsed parse(Object keepRuns, Object maxAgeDays) {
        throw new UnsupportedOperationException("red stub");
    }
}
