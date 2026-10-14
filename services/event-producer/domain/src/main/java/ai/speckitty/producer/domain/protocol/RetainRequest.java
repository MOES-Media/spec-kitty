package ai.speckitty.producer.domain.protocol;

import java.util.Map;

/**
 * A {@code retain} request. It never carries facts: the producer derives them.
 *
 * @param version the protocol version, 1
 * @param startPath the call site's path
 * @param dryRun true unless the client sent the JSON value {@code false}
 * @param policy the raw {@code ledger.retention} values, keyed {@code keep_runs} and {@code max_age_days}
 * @param deadlineMs the client's effective timeout in whole milliseconds, at least 1
 */
public record RetainRequest(int version, String startPath, boolean dryRun, Map<String, Object> policy, int deadlineMs)
        implements Request {

    public RetainRequest {
        throw new UnsupportedOperationException("red stub");
    }

    /** {@code dry_run} is true unless the client sent exactly {@code false}; absent, true and anything else mean true. */
    public static boolean dryRunFrom(Object value) {
        throw new UnsupportedOperationException("red stub");
    }
}
