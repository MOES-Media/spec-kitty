package ai.speckitty.producer.adapter.clock;

import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.NODE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.SOURCE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.file;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.mission;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.fs.ChildJvm;
import ai.speckitty.producer.adapter.fs.ExclusiveLock;
import ai.speckitty.producer.adapter.fs.LockChild;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.adapter.repo.GitFixtures;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.FaultInjector.KillPoint;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** AC-010 with real processes: a primary checkout and two worktrees, concurrent stamps and kills. */
class ClockProcessTest {

    private static final int PROCESSES_PER_CHECKOUT = 3;
    private static final int STAMPS_PER_PROCESS = 15;

    @TempDir
    Path base;

    private static RepoLayout locate(Path start) {
        return ((ParseResult.Accepted<RepoLayout>) new GitRepoLocator().locate(start)).value();
    }

    private static List<Integer> clocksInFileOrder(Path logFile) throws IOException {
        List<Integer> clocks = new ArrayList<>();
        for (String text : Files.readString(logFile, StandardCharsets.UTF_8).split("\n")) {
            Map<String, Object> row = ((ParseResult.Accepted<Map<String, Object>>) Json.parseObject(text)).value();
            clocks.add(Integer.parseInt(((JsonNumber) row.get("lamportclock")).literal()));
        }
        return clocks;
    }

    @Test
    void concurrentProcessesInThreeCheckoutsNeverRepeatAClockAndTheLogIsStrictlyIncreasing() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path laneA = GitFixtures.linkedWorktree(primary, base.resolve("lane-a"), "lane-a");
        Path laneB = GitFixtures.linkedWorktree(primary, primary.resolve(".worktrees/lane-b"), "lane-b");
        List<Path> checkouts = List.of(primary, laneA, laneB);
        List<Process> children = new ArrayList<>();
        int number = 0;
        for (Path checkout : checkouts) {
            for (int p = 0; p < PROCESSES_PER_CHECKOUT; p++) {
                String tag = "P" + (number++);
                children.add(ChildJvm.start(ClockChild.class, base.resolve(tag + ".log"),
                        "stamp", checkout.toString(), Integer.toString(STAMPS_PER_PROCESS), tag));
            }
        }
        assertThat(ChildJvm.awaitAll(children, 180)).containsOnly(0);

        int total = checkouts.size() * PROCESSES_PER_CHECKOUT * STAMPS_PER_PROCESS;
        RepoLayout layout = locate(laneA);
        List<Integer> clocks = clocksInFileOrder(file(layout, mission()));
        assertThat(clocks).hasSize(total);
        assertThat(new HashSet<>(clocks)).as("no repeated (nodeid, lamportclock)").hasSize(total);
        for (int i = 1; i < clocks.size(); i++) {
            assertThat(clocks.get(i)).as("line " + (i + 1)).isGreaterThan(clocks.get(i - 1));
        }
        assertThat(clocks.get(0)).isEqualTo(1);
        assertThat(clocks.get(clocks.size() - 1)).as("no gap without a kill").isEqualTo(total);
        assertThat(Files.readString(ClockPaths.stateFile(layout))).endsWith("\"clock\":" + total + "}");
        assertThat(Files.exists(primary.resolve(".git/spec-kitty/event-producer/clock.json"))).isTrue();
        assertThat(Files.exists(laneA.resolve("spec-kitty"))).isFalse();
        assertThat(Files.exists(laneB.resolve("spec-kitty"))).isFalse();
        Set<String> ids = new HashSet<>(Files.readAllLines(file(layout, mission())));
        assertThat(ids).as("every line distinct").hasSize(total);
    }

    @ParameterizedTest
    @EnumSource(KillPoint.class)
    void aProcessKilledAtAnyPointNeverLetsTheNextStampReuseAClock(KillPoint point) throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path lane = GitFixtures.linkedWorktree(primary, base.resolve("lane"), "lane");
        Process child = ChildJvm.start(ClockChild.class, base.resolve("child.log"), "kill", lane.toString(), point.name(), "2", "K");
        assertThat(ChildJvm.awaitAll(List.of(child), 120)).containsExactly(137);

        RepoLayout layout = locate(primary);
        int highestInLog = new FilePublishLog().highestClock(layout).orElse(0);
        assertThat(highestInLog).isEqualTo(point == KillPoint.AFTER_APPEND ? 3 : 2);
        FileClockStore store = new FileClockStore(new FilePublishLog(), FaultInjector.NONE);
        ParseResult<ClockStore.Stamp> next = store.stampAndAppend(new ClockStore.Request(
                layout, NODE, mission(), SOURCE, "after", 5_000, clock -> ClockChild.line("after", clock.value())));
        int stamped = ((ParseResult.Accepted<ClockStore.Stamp>) next).value().clock().orElseThrow().value();
        assertThat(stamped).isGreaterThan(highestInLog);
        assertThat(stamped).isEqualTo(point == KillPoint.BEFORE_STATE_WRITE ? 3 : 4);
        List<Integer> clocks = clocksInFileOrder(file(layout, mission()));
        assertThat(new HashSet<>(clocks)).hasSize(clocks.size());
    }

    @Test
    void aLiveHolderInAnotherProcessMakesTheStampBusyWithinTheBoundAndNothingIsWritten() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        RepoLayout layout = locate(primary);
        Path ready = base.resolve("ready");
        Process holder = ChildJvm.start(LockChild.class, base.resolve("holder.log"),
                "hold", ClockPaths.lockFile(layout).toString(), ready.toString(), "5000");
        try {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!Files.exists(ready)) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(20);
            }
            long start = System.nanoTime();
            ParseResult<ClockStore.Stamp> result = new FileClockStore(new FilePublishLog(), FaultInjector.NONE).stampAndAppend(
                    new ClockStore.Request(layout, NODE, mission(), SOURCE, "busy", 250, clock -> ClockChild.line("busy", clock.value())));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(((ParseResult.Refused<ClockStore.Stamp>) result).reason()).isEqualTo(FaultReason.BUSY);
            assertThat(elapsedMs).isBetween(200L, 3_000L);
            assertThat(Files.exists(ClockPaths.stateFile(layout))).isFalse();
            assertThat(Files.exists(file(layout, mission()))).isFalse();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
        try (ExclusiveLock free = ExclusiveLock.acquire(ClockPaths.lockFile(layout), 5_000).orElse(null)) {
            assertThat(free).as("the dead holder's lock is released").isNotNull();
        }
    }
}
