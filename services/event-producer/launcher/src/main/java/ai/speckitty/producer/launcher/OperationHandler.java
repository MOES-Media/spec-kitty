package ai.speckitty.producer.launcher;

import java.util.Map;

/**
 * One operation of the stdin/stdout protocol. {@code produce} is registered directly; any further operation is discovered with
 * {@link java.util.ServiceLoader} from {@code META-INF/services/ai.speckitty.producer.launcher.OperationHandler}, so adding an
 * operation edits no file of this one.
 */
public interface OperationHandler {

    /** The {@code op} value this handler answers, for example {@code produce}. */
    String operation();

    /**
     * Answers a request whose version, operation and deadline are already checked. The reply is the JSON object to write: it holds
     * the {@code v} and {@code op} members, never the request's row, never an absolute path, never a stack trace.
     *
     * @throws RequestException when the request is unreadable for this operation (exit 64, no reply)
     */
    Map<String, Object> handle(Map<String, Object> request) throws RequestException;
}
