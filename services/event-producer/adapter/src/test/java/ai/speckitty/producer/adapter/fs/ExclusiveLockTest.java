package ai.speckitty.producer.adapter.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExclusiveLockTest {

    @TempDir
    Path dir;

    @Test
    void createsAZeroByteLockFileAndNeverReplacesIt() throws Exception {
        Path lock = dir.resolve("x/clock.lock");
        try (ExclusiveLock held = ExclusiveLock.acquire(lock, 1_000).orElseThrow()) {
            assertThat(held).isNotNull();
        }
        Object firstKey = Files.readAttributes(lock, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        try (ExclusiveLock held = ExclusiveLock.acquire(lock, 1_000).orElseThrow()) {
            assertThat(held).isNotNull();
        }
        assertThat(Files.size(lock)).isZero();
        assertThat(Files.readAttributes(lock, java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(firstKey);
    }

    @Test
    void aSecondAcquireWaitsTheBoundThenGivesUpWithoutHanging() throws Exception {
        Path lock = dir.resolve("clock.lock");
        try (ExclusiveLock first = ExclusiveLock.acquire(lock, 1_000).orElseThrow()) {
            assertThat(first).isNotNull();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                long start = System.nanoTime();
                Optional<ExclusiveLock> second = pool.submit(() -> ExclusiveLock.acquire(lock, 150)).get();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(second).isEmpty();
                assertThat(elapsedMs).isBetween(100L, 3_000L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void releaseLetsTheNextAcquireThrough() throws Exception {
        Path lock = dir.resolve("clock.lock");
        ExclusiveLock first = ExclusiveLock.acquire(lock, 1_000).orElseThrow();
        first.close();
        first.close();
        try (ExclusiveLock second = ExclusiveLock.acquire(lock, 100).orElseThrow()) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void threadsNeverOverlapInsideTheCriticalSection() throws Exception {
        Path lock = dir.resolve("clock.lock");
        int threads = 8;
        int rounds = 40;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger worst = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        try (ExclusiveLock ignored = ExclusiveLock.acquire(lock, 60_000).orElseThrow()) {
                            worst.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Thread.sleep(1);
                            inside.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(worst.get()).isEqualTo(1);
    }

    @Test
    void separateProcessesNeverLoseAnUpdateUnderContention() throws Exception {
        Path lock = dir.resolve("clock.lock");
        Path counter = dir.resolve("counter");
        Files.writeString(counter, "0");
        int processes = 6;
        int times = 25;
        List<Process> children = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            children.add(ChildJvm.start(LockChild.class, dir.resolve("child" + i + ".log"),
                    "count", lock.toString(), counter.toString(), Integer.toString(times)));
        }
        int[] codes = ChildJvm.awaitAll(children, 120);
        assertThat(codes).containsOnly(0);
        assertThat(Integer.parseInt(Files.readString(counter).trim())).isEqualTo(processes * times);
    }

    @Test
    void aWaiterInAnotherProcessTimesOutWhileTheHolderLives() throws Exception {
        Path lock = dir.resolve("clock.lock");
        Path ready = dir.resolve("ready");
        Process holder = ChildJvm.start(LockChild.class, dir.resolve("holder.log"), "hold", lock.toString(), ready.toString(), "4000");
        try {
            awaitFile(ready);
            long start = System.nanoTime();
            Optional<ExclusiveLock> mine = ExclusiveLock.acquire(lock, 250);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(mine).isEmpty();
            assertThat(elapsedMs).isBetween(200L, 3_000L);
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
        try (ExclusiveLock afterDeath = ExclusiveLock.acquire(lock, 5_000).orElseThrow()) {
            assertThat(afterDeath).isNotNull();
        }
    }

    private static void awaitFile(Path file) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!Files.exists(file)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the child never signalled");
            }
            Thread.sleep(20);
        }
    }
}
