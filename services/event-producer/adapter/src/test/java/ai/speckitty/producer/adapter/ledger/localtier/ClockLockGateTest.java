package ai.speckitty.producer.adapter.ledger.localtier;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.clock.ClockPaths;
import ai.speckitty.producer.adapter.fs.ExclusiveLock;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Retention takes the same lock {@code produce} holds around its append. */
class ClockLockGateTest {

    private final ClockLockGate gate = new ClockLockGate();

    @Test
    void theActionRunsWhileTheClockLockIsHeldAndTheLockIsFreeAfterwards(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Path lockFile = ClockPaths.lockFile(layout);
        Optional<String> result = gate.underClockLock(layout, 1000, () -> {
            try {
                assertThat(ExclusiveLock.acquire(lockFile, 30)).as("held by the gate: a contender times out").isEmpty();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return "ran";
        });
        assertThat(result).contains("ran");
        Optional<ExclusiveLock> after = ExclusiveLock.acquire(lockFile, 1000);
        assertThat(after).as("released").isPresent();
        after.get().close();
    }

    @Test
    void aLockHeldElsewhereMeansTheActionDoesNotRun(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        AtomicInteger ran = new AtomicInteger();
        try (ExclusiveLock held = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 1000).orElseThrow()) {
            assertThat(held).isNotNull();
            long started = System.nanoTime();
            Optional<String> result = gate.underClockLock(layout, 60, () -> {
                ran.incrementAndGet();
                return "ran";
            });
            assertThat(result).isEmpty();
            assertThat((System.nanoTime() - started) / 1_000_000).as("bounded by the wait").isLessThan(5_000);
        }
        assertThat(ran).hasValue(0);
    }

    @Test
    void theLockIsReleasedWhenTheActionThrows(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        try {
            gate.underClockLock(layout, 1000, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        Optional<ExclusiveLock> after = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 1000);
        assertThat(after).isPresent();
        after.get().close();
    }

    @Test
    void takingTheLockWritesNothingToIt(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        gate.underClockLock(layout, 1000, () -> "x");
        assertThat(Files.size(ClockPaths.lockFile(layout))).as("clock.lock stays zero bytes").isZero();
    }

    @Test
    void aLockFileThatCannotBeOpenedIsABusyNotACrash(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Files.createDirectories(ClockPaths.lockFile(layout));
        AtomicInteger ran = new AtomicInteger();
        assertThat(gate.underClockLock(layout, 50, () -> ran.incrementAndGet())).isEmpty();
        assertThat(ran).hasValue(0);
    }
}
