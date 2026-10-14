package ai.speckitty.producer.launcher;

/** A request that cannot be read for its operation: exit 64, and no reply is written. The message is for tests, never printed. */
public final class RequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public RequestException(String message) {
        super(message, null, false, false);
    }
}
