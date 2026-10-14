package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import java.util.Optional;
import java.util.function.Supplier;

/** The clock lock that {@code produce} holds from its duplicate check to its durable append (OD-CLOCK). */
public interface ClockGate {

    /**
     * Runs {@code action} while holding the clock lock, waiting at most {@code waitMillis} for it. Empty when the lock was not had
     * in time or could not be opened; the action then did not run.
     */
    <T> Optional<T> underClockLock(RepoLayout layout, long waitMillis, Supplier<T> action);
}
