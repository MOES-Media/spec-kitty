package ai.speckitty.producer.domain.retention;

import java.util.Objects;

/**
 * The named error for an invalid retention setting (AC-031). Nothing is deleted when it is returned, also on a dry run.
 *
 * @param key the configuration key, for example {@code ledger.retention.keep_runs}
 * @param code a closed code; {@code invalid-value} is the only one today
 */
public record PolicyError(String key, String code) {

    public static final String NAME = "policy_error";
    public static final String INVALID_VALUE = "invalid-value";

    public PolicyError {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(code, "code");
    }

    /** The one-line text {@code doctor} renders; it never echoes the rejected value. */
    public String message() {
        return NAME + ": " + key + " must be a positive integer";
    }
}
