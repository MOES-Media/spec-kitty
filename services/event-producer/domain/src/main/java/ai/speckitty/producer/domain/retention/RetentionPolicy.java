package ai.speckitty.producer.domain.retention;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.regex.Pattern;

/** The retention limits: keep at least the newest {@code keepRuns} runs and anything younger than {@code maxAgeDays} (FR-028). */
public record RetentionPolicy(int keepRuns, int maxAgeDays) {

    private static final Pattern CANONICAL_POSITIVE = Pattern.compile("[1-9][0-9]*");
    private static final int MAX_DIGITS = 10;

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
        if (keepRuns < 1 || maxAgeDays < 1) {
            throw new IllegalArgumentException("retention limits are positive");
        }
    }

    /**
     * Reads the raw values exactly as the client forwarded them. Absent or {@code null} is the default (20 and 30). Anything but a
     * positive integer up to 2^31-1, written as an integer or as a string of digits, is a {@link PolicyError}.
     */
    public static Parsed parse(Object keepRuns, Object maxAgeDays) {
        int keep = positiveInt(keepRuns, DEFAULT_KEEP_RUNS);
        if (keep < 1) {
            return new Parsed.Invalid(new PolicyError(KEEP_RUNS_KEY, PolicyError.INVALID_VALUE));
        }
        int age = positiveInt(maxAgeDays, DEFAULT_MAX_AGE_DAYS);
        if (age < 1) {
            return new Parsed.Invalid(new PolicyError(MAX_AGE_DAYS_KEY, PolicyError.INVALID_VALUE));
        }
        return new Parsed.Valid(new RetentionPolicy(keep, age));
    }

    /** The value as a positive int, the default when absent, or -1 when it is not acceptable. */
    private static int positiveInt(Object raw, int absent) {
        if (raw == null) {
            return absent;
        }
        String digits;
        if (raw instanceof JsonNumber number) {
            if (!number.isInteger()) {
                return -1;
            }
            digits = number.literal();
        } else if (raw instanceof String text) {
            digits = text;
        } else {
            return -1;
        }
        if (!CANONICAL_POSITIVE.matcher(digits).matches() || digits.length() > MAX_DIGITS) {
            return -1;
        }
        long value = Long.parseLong(digits);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }
}
