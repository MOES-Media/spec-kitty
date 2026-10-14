package ai.speckitty.producer.application.port;

/**
 * A test-only seam: production code binds {@link #NONE}. A test binds an injector that stops the process at a kill point, to prove
 * that a kill never reuses a clock value (AC-010).
 */
public interface FaultInjector {

    /** An injector that does nothing. */
    FaultInjector NONE = point -> {};

    /** The places a producer may be killed between the lock being taken and released. */
    enum KillPoint {
        /** Everything read and decided, nothing written: a kill leaves no advance. */
        BEFORE_STATE_WRITE,
        /** The new state is durable, the line is not appended: a kill leaves a gap in the clock. */
        AFTER_STATE_WRITE,
        /** The line is durable: a kill leaves the row present. */
        AFTER_APPEND
    }

    /** Called when the producer reaches {@code point}. */
    void at(KillPoint point);
}
