package ai.speckitty.producer.domain.protocol;

/** The checks both requests share. */
final class RequestChecks {

    private RequestChecks() {}

    static void check(int version, String startPath, int deadlineMs) {
        if (version != Protocol.VERSION) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        if (startPath == null || startPath.isEmpty()) {
            throw new IllegalArgumentException("start path is required");
        }
        if (deadlineMs < 1) {
            throw new IllegalArgumentException("deadline is at least one millisecond");
        }
    }
}
