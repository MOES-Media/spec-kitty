package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.ClockGate;
import java.util.Optional;
import java.util.function.Supplier;

/** The clock lock of {@code produce}, taken for a delete (stub: the tests are written first). */
public final class ClockLockGate implements ClockGate {

    @Override
    public <T> Optional<T> underClockLock(RepoLayout layout, long waitMillis, Supplier<T> action) {
        return Optional.empty();
    }
}
