package ai.speckitty.producer.adapter.clock;

import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.NODE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.SOURCE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.file;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.layout;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.line;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.mission;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.project;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.fs.ExclusiveLock;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.ClockStore.Stamp;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.FaultInjector.KillPoint;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileClockStoreTest {

    /** The kill, in-process: thrown by the injector at the chosen point. */
    static final class Killed extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @TempDir
    Path root;

    private RepoLayout layout;
    private final PublishLog log = new FilePublishLog();

    @BeforeEach
    void setUp() throws IOException {
        layout = layout(root);
    }

    private FileClockStore store(FaultInjector injector) {
        return new FileClockStore(log, injector);
    }

    private ClockStore.Request request(String id, int waitMillis) {
        return request(mission(), SOURCE, id, waitMillis);
    }

    private ClockStore.Request request(PublishLog.Target target, String source, String id, int waitMillis) {
        return new ClockStore.Request(layout, NODE, target, source, id, waitMillis, clock -> line(id, clock.value()));
    }

    private Stamp stamp(FileClockStore store, String id) {
        ParseResult<Stamp> result = store.stampAndAppend(request(id, 5_000));
        assertThat(result).as(id).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Stamp>) result).value();
    }

    private int stampedClock(FileClockStore store, String id) {
        return stamp(store, id).clock().orElseThrow().value();
    }

    private Path state() {
        return ClockPaths.stateFile(layout);
    }

    private String stateText() throws IOException {
        return Files.readString(state());
    }

    @Test
    void theFirstStampIsOneAndTheStateLivesUnderTheCommonDir() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        Stamp first = stamp(store, ulid(1));
        assertThat(first.duplicate()).isFalse();
        assertThat(first.clock()).contains(new LamportClock(1));
        assertThat(state()).isEqualTo(root.resolve(".git/spec-kitty/event-producer/clock.json"));
        assertThat(ClockPaths.lockFile(layout)).isEqualTo(root.resolve(".git/spec-kitty/event-producer/clock.lock"));
        assertThat(stateText()).isEqualTo("{\"v\":1,\"nodeid\":\"" + NODE + "\",\"clock\":1}");
        assertThat(Files.size(ClockPaths.lockFile(layout))).isZero();
        assertThat(Files.readString(file(layout, mission()))).isEqualTo(line(ulid(1), 1) + "\n");
    }

    @Test
    void stampsIncreaseByOneAndTheStateFollows() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        for (int expected = 1; expected <= 5; expected++) {
            assertThat(stampedClock(store, ulid(expected))).isEqualTo(expected);
            assertThat(stateText()).endsWith("\"clock\":" + expected + "}");
        }
    }

    @Test
    void aDuplicateIsANoOpThatConsumesNoClockAndWritesNothing() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        stamp(store, ulid(1));
        String stateBefore = stateText();
        byte[] logBefore = Files.readAllBytes(file(layout, mission()));
        Stamp again = stamp(store, ulid(1));
        assertThat(again.duplicate()).isTrue();
        assertThat(again.clock()).isEmpty();
        assertThat(stateText()).isEqualTo(stateBefore);
        assertThat(Files.readAllBytes(file(layout, mission()))).isEqualTo(logBefore);
        assertThat(stampedClock(store, ulid(2))).isEqualTo(2);
    }

    @Test
    void deletingTheLogButKeepingTheStateRaisesTheNextStamp() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        for (int i = 1; i <= 4; i++) {
            stamp(store, ulid(i));
        }
        Files.delete(file(layout, mission()));
        assertThat(stampedClock(store, ulid(9))).isEqualTo(5);
    }

    @Test
    void deletingTheStateRecoversAboveTheHighestClockInAnyPublishLogFile() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        for (int i = 1; i <= 3; i++) {
            stamp(store, ulid(i));
        }
        ParseResult<Stamp> inProject = store.stampAndAppend(
                new ClockStore.Request(layout, NODE, project(), SOURCE, ulid(50), 5_000, clock -> line(ulid(50), clock.value())));
        assertThat(inProject).isInstanceOf(ParseResult.Accepted.class);
        assertThat(log.highestClock(layout)).isEqualTo(OptionalInt.of(4));
        Files.delete(state());
        assertThat(stampedClock(store, ulid(9))).isEqualTo(5);
    }

    @Test
    void theStateIsWrittenThroughTheAtomicFileWriterAndForced() throws IOException {
        long before = ai.speckitty.producer.adapter.fs.AtomicFile.forcedCount();
        FileClockStore store = store(p -> {
            if (p == KillPoint.AFTER_STATE_WRITE) {
                throw new Killed();
            }
        });
        try {
            stamp(store, ulid(1));
        } catch (Killed expected) {
            // the kill lands after the state write and before any row is appended, so only the state write can have forced
        }
        assertThat(ai.speckitty.producer.adapter.fs.AtomicFile.forcedCount()).isGreaterThan(before);
        try (var siblings = Files.list(state().getParent())) {
            assertThat(siblings.map(p -> p.getFileName().toString()).filter(n -> n.contains("tmp")).toList()).isEmpty();
        }
    }

    @Test
    void stateLostWithNoLogStartsAtOne() throws IOException {
        assertThat(stampedClock(store(FaultInjector.NONE), ulid(1))).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "garbage",
        "[1]",
        "{\"v\":2,\"nodeid\":\"abcdef012345\",\"clock\":5}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\",\"clock\":5.0}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\",\"clock\":\"5\"}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\",\"clock\":-1}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\",\"clock\":2147483648}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\"}",
        "{\"v\":1,\"clock\":5}",
        "{\"v\":1.0,\"nodeid\":\"abcdef012345\",\"clock\":5}",
        "{\"v\":1,\"nodeid\":\"someone-else\",\"clock\":500}",
        "{\"v\":1,\"nodeid\":\"abcdef012345\",\"clock\":5} trailing"
    })
    void anUnreadableOrForeignStateIsTreatedAsLostAndRecoveredFromTheLog(String badState) throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        Path logFile = file(layout, mission());
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, line(ulid(1), 7) + "\n");
        Files.createDirectories(state().getParent());
        Files.writeString(state(), badState);
        assertThat(stampedClock(store, ulid(2))).isEqualTo(8);
    }

    @Test
    void aReadableStateIsTrustedEvenWhenAboveTheLog() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        Path logFile = file(layout, mission());
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, line(ulid(1), 7) + "\n");
        Files.createDirectories(state().getParent());
        Files.writeString(state(), "{\"v\":1,\"nodeid\":\"" + NODE + "\",\"clock\":50}");
        assertThat(stampedClock(store, ulid(2))).isEqualTo(51);
    }

    @Test
    void theClockNeverExceedsTwoToTheThirtyFirstMinusOneAndNeverWraps() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        Files.createDirectories(state().getParent());
        Files.writeString(state(), "{\"v\":1,\"nodeid\":\"" + NODE + "\",\"clock\":" + (Integer.MAX_VALUE - 1) + "}");
        assertThat(stampedClock(store, ulid(1))).isEqualTo(Integer.MAX_VALUE);
        String stateAtMax = stateText();
        byte[] logAtMax = Files.readAllBytes(file(layout, mission()));
        ParseResult<Stamp> over = store.stampAndAppend(request(ulid(2), 5_000));
        assertThat(over).isInstanceOf(ParseResult.Refused.class);
        assertThat(((ParseResult.Refused<Stamp>) over).reason()).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(stateText()).isEqualTo(stateAtMax);
        assertThat(Files.readAllBytes(file(layout, mission()))).isEqualTo(logAtMax);
    }

    @Test
    void overflowAlsoAppliesWhenRecoveringFromALogAtTheBound() throws IOException {
        Path logFile = file(layout, mission());
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, line(ulid(1), Integer.MAX_VALUE) + "\n");
        ParseResult<Stamp> over = store(FaultInjector.NONE).stampAndAppend(request(ulid(2), 5_000));
        assertThat(((ParseResult.Refused<Stamp>) over).reason()).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(Files.exists(state())).isFalse();
    }

    @Test
    void aTornFragmentIsReportedLeftInPlaceAndTheNewRowStandsOnItsOwnLine() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        stamp(store, ulid(1));
        Path logFile = file(layout, mission());
        Files.write(logFile, "{\"id\":\"01JAAAA".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        Stamp second = stamp(store, ulid(2));
        assertThat(second.torn()).contains(new PublishLog.TornLine(2));
        assertThat(Files.readString(logFile).split("\n", -1))
                .containsExactly(line(ulid(1), 1), "{\"id\":\"01JAAAA", line(ulid(2), 2), "");
    }

    @Test
    void aRenderedLineThatDoesNotCarryTheStampIsRefusedBeforeAnythingIsWritten() throws IOException {
        FileClockStore store = store(FaultInjector.NONE);
        List<ClockStore.LineRenderer> bad = List.of(
                clock -> line(ulid(1), clock.value() + 1),
                clock -> line(ulid(2), clock.value()),
                clock -> line(ulid(1), clock.value()) + "\n",
                clock -> "not json",
                clock -> line(ulid(1), clock.value()).replace(NODE, "ffffffffffff"),
                clock -> line(ulid(1), clock.value()).replace(SOURCE, "/spec-kitty/missions/01JAAAA0000000000000000ABE"));
        for (ClockStore.LineRenderer renderer : bad) {
            ParseResult<Stamp> result = store.stampAndAppend(
                    new ClockStore.Request(layout, NODE, mission(), SOURCE, ulid(1), 5_000, renderer));
            assertThat(result).isInstanceOf(ParseResult.Refused.class);
            assertThat(((ParseResult.Refused<Stamp>) result).reason()).isEqualTo(FaultReason.REJECTED);
        }
        assertThat(Files.exists(state())).isFalse();
        assertThat(Files.exists(file(layout, mission()))).isFalse();
        assertThat(bad).hasSize(6);
    }

    @Test
    void theWaitIsBoundedAndABusyLockIsRefusedWithNothingWritten() throws Exception {
        FileClockStore store = store(FaultInjector.NONE);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ExclusiveLock held = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 1_000).orElseThrow()) {
            assertThat(held).isNotNull();
            long start = System.nanoTime();
            ParseResult<Stamp> result = pool.submit(() -> store.stampAndAppend(request(ulid(1), 200))).get();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(((ParseResult.Refused<Stamp>) result).reason()).isEqualTo(FaultReason.BUSY);
            assertThat(elapsedMs).isBetween(150L, 3_000L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(Files.exists(state())).isFalse();
        assertThat(Files.exists(file(layout, mission()))).isFalse();
        assertThat(stampedClock(store, ulid(1))).isEqualTo(1);
    }

    @Test
    void stateIsWrittenBeforeTheLineAndTheKillPointsSeeEachStep() throws IOException {
        List<String> seen = new ArrayList<>();
        FaultInjector observer = point -> {
            boolean stateHasOne = Files.exists(state()) && stateTextOrEmpty().endsWith("\"clock\":1}");
            boolean lineThere = Files.exists(file(layout, mission()));
            seen.add(point + " state=" + stateHasOne + " line=" + lineThere);
        };
        stamp(store(observer), ulid(1));
        assertThat(seen).containsExactly(
                "BEFORE_STATE_WRITE state=false line=false",
                "AFTER_STATE_WRITE state=true line=false",
                "AFTER_APPEND state=true line=true");
    }

    private String stateTextOrEmpty() {
        try {
            return Files.readString(state());
        } catch (IOException e) {
            return "";
        }
    }

    @Test
    void theLockIsHeldAtEveryKillPointFromTheStateReadToTheLineBeingDurable() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<String> verdicts = new ArrayList<>();
        FaultInjector probe = point -> {
            try {
                Optional<ExclusiveLock> other = pool.submit(() -> ExclusiveLock.acquire(ClockPaths.lockFile(layout), 60)).get();
                verdicts.add(point + " free=" + other.isPresent());
                other.ifPresent(ExclusiveLock::close);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        try {
            stamp(store(probe), ulid(1));
        } finally {
            pool.shutdownNow();
        }
        assertThat(verdicts).containsExactly(
                "BEFORE_STATE_WRITE free=false", "AFTER_STATE_WRITE free=false", "AFTER_APPEND free=false");
        try (ExclusiveLock free = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 100).orElse(null)) {
            assertThat(free).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_STATE_WRITE", "AFTER_STATE_WRITE", "AFTER_APPEND"})
    void aKillAtAnyPointNeverLetsTheNextStampReuseAClockValue(String pointName) throws IOException {
        KillPoint killAt = KillPoint.valueOf(pointName);
        FileClockStore healthy = store(FaultInjector.NONE);
        stamp(healthy, ulid(1));
        stamp(healthy, ulid(2));
        FileClockStore doomed = store(point -> {
            if (point == killAt) {
                throw new Killed();
            }
        });
        try {
            doomed.stampAndAppend(request(ulid(3), 5_000));
        } catch (Killed expected) {
            // the producer died here
        }
        int highestInLog = log.highestClock(layout).orElse(0);
        int next = stampedClock(healthy, ulid(4));
        assertThat(next).isGreaterThan(highestInLog);
        switch (killAt) {
            case BEFORE_STATE_WRITE -> assertThat(next).as("no advance").isEqualTo(3);
            case AFTER_STATE_WRITE -> assertThat(next).as("a gap: 3 was consumed").isEqualTo(4);
            case AFTER_APPEND -> assertThat(next).as("row 3 is present").isEqualTo(4);
        }
        assertThat(Files.readString(file(layout, mission())).contains(ulid(3))).isEqualTo(killAt == KillPoint.AFTER_APPEND);
    }

    @Test
    void aKillAtAnyPointReleasesTheLock() throws IOException {
        for (KillPoint point : KillPoint.values()) {
            FileClockStore doomed = store(p -> {
                if (p == point) {
                    throw new Killed();
                }
            });
            try {
                doomed.stampAndAppend(request(ulid(10 + point.ordinal()), 5_000));
            } catch (Killed expected) {
                // the producer died here
            }
            try (ExclusiveLock free = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 200).orElse(null)) {
                assertThat(free).as(point.name()).isNotNull();
            }
        }
    }
}
