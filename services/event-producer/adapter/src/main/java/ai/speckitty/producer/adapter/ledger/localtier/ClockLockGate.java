package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.adapter.clock.ClockPaths;
import ai.speckitty.producer.adapter.fs.ExclusiveLock;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.ClockGate;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The clock lock of {@code produce}, taken for a delete. {@code produce} holds it from its duplicate check to its durable append,
 * so a delete under it cannot unlink a file between a producer's open and its append. Taking it opens {@code clock.lock}, which
 * is no candidate file and is never written.
 */
public final class ClockLockGate implements ClockGate {

    @Override
    public <T> Optional<T> underClockLock(RepoLayout layout, long waitMillis, Supplier<T> action) {
        Optional<ExclusiveLock> acquired;
        try {
            acquired = ExclusiveLock.acquire(ClockPaths.lockFile(layout), waitMillis);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        try (ExclusiveLock ignored = acquired.get()) {
            return Optional.ofNullable(action.get());
        }
    }
}
