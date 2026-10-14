package ai.speckitty.producer.launcher.retain;

import static ai.speckitty.producer.launcher.retain.Repos.MISSION;
import static ai.speckitty.producer.launcher.retain.Repos.OLD;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.clock.FileClockStore;
import ai.speckitty.producer.adapter.clock.SystemClock;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.ledger.FileLedgerFacts;
import ai.speckitty.producer.adapter.ledger.localtier.ClockLockGate;
import ai.speckitty.producer.adapter.ledger.localtier.FileLocalTier;
import ai.speckitty.producer.adapter.ledger.localtier.GitIndexTrackedFiles;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.PublishLog.Target;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LocalFile;
import ai.speckitty.producer.application.retain.LocalTier;
import ai.speckitty.producer.application.retain.Retain;
import ai.speckitty.producer.application.retain.RetainResult;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.protocol.RetainReply;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A delete racing a stamp is safe (R-23, AC-013): {@code retain} takes the clock lock that {@code produce} holds from its duplicate
 * check to its durable append, so no row that was reported produced is appended into a file that is then unlinked.
 */
class RetainRaceTest {

    private static final String LOG = ".kittify/events/publish/missions/" + MISSION + ".jsonl";
    private static final String NODE = "abcdef012345";
    private static final String SOURCE = "/spec-kitty/missions/" + MISSION;
    private static final RelativePath LOG_PATH = new RelativePath(LOG);

    /** A local tier that stops inside the delete, after the lock is taken and the facts were checked, until it is released. */
    private static final class PausingTier implements LocalTier {
        private final FileLocalTier real = new FileLocalTier(new SystemClock());
        final CountDownLatch insideDelete = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public List<Entry> list(RepoLayout layout) {
            return real.list(layout);
        }

        @Override
        public Optional<LocalFile> inspect(RepoLayout layout, RelativePath path) {
            return real.inspect(layout, path);
        }

        @Override
        public boolean delete(RepoLayout layout, RelativePath path) {
            insideDelete.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return real.delete(layout, path);
        }
    }

    private static Path populate(Path dir) throws IOException {
        Path root = Repos.repo(dir);
        Repos.mission(root, "demo-01J00000", MISSION, false);
        Repos.write(root, LOG, Repos.publishRow("01J0000000000000000000E001", OLD));
        Repos.write(root, ".kittify/events/publish/project-" + Repos.PROJECT + ".jsonl",
                Repos.publishRow("01J0000000000000000000E003", Instant.now().toString()));
        return root;
    }

    private static RepoLayout layout(Path root) {
        return new RepoLayout(root, root.resolve(".git"));
    }

    private static ClockStore.Request stamp(RepoLayout layout, String id, String time) {
        return new ClockStore.Request(layout, NODE, new Target(Target.Kind.MISSION, MISSION), SOURCE, id, 20000,
                clock -> "{\"id\":\"" + id + "\",\"source\":\"" + SOURCE + "\",\"time\":\"" + time + "\",\"lamportclock\":" + clock.value()
                        + ",\"nodeid\":\"" + NODE + "\"}");
    }

    private static Retain retain(LocalTier tier) {
        return new Retain(new GitRepoLocator(), new SystemClock(), new FileLedgerFacts(), tier, new GitIndexTrackedFiles(), new ClockLockGate());
    }

    private static RetainRequest apply(Path root) {
        Map<String, Object> policy = new java.util.LinkedHashMap<>();
        policy.put("keep_runs", new JsonNumber("1"));
        policy.put("max_age_days", new JsonNumber("1"));
        return new RetainRequest(Protocol.VERSION, root.toString(), false, policy, 20000);
    }

    private static List<String> idsIn(Path file) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (Json.parseObject(line) instanceof ParseResult.Accepted<Map<String, Object>> parsed) {
                ids.add((String) parsed.value().get("id"));
            }
        }
        return ids;
    }

    @Test
    void aStampThatArrivesWhileRetainIsDeletingWaitsForTheDeleteAndTheRowSurvives(@TempDir Path dir) throws Exception {
        Path root = populate(dir);
        RepoLayout layout = layout(root);
        PausingTier tier = new PausingTier();
        FileClockStore store = new FileClockStore(new FilePublishLog(), FaultInjector.NONE);

        CompletableFuture<RetainResult> deleting = CompletableFuture.supplyAsync(() -> retain(tier).retain(apply(root)));
        assertThat(tier.insideDelete.await(30, TimeUnit.SECONDS)).as("retain reached its delete").isTrue();

        String id = "01J0000000000000000000E777";
        CompletableFuture<ParseResult<ClockStore.Stamp>> stamping =
                CompletableFuture.supplyAsync(() -> store.stampAndAppend(stamp(layout, id, Instant.now().toString())));
        Thread.sleep(500);
        assertThat(stamping).as("the stamp waits for the clock lock that retain holds").isNotDone();
        assertThat(Files.exists(root.resolve(LOG))).as("the file is still there while retain holds the lock").isTrue();

        tier.release.countDown();
        RetainReply reply = ((RetainResult.Replied) deleting.get(30, TimeUnit.SECONDS)).reply();
        assertThat(reply.deleted()).containsExactly(LOG_PATH);
        ParseResult<ClockStore.Stamp> stamped = stamping.get(30, TimeUnit.SECONDS);
        assertThat(stamped).isInstanceOf(ParseResult.Accepted.class);
        assertThat(((ParseResult.Accepted<ClockStore.Stamp>) stamped).value().duplicate()).isFalse();

        assertThat(root.resolve(LOG)).as("the row reported produced is in the file that exists").exists();
        assertThat(idsIn(root.resolve(LOG))).containsExactly(id);
    }

    @Test
    void aRowAppendedBetweenTheListingAndTheLockKeepsTheFileAndTheRow(@TempDir Path dir) throws Exception {
        Path root = populate(dir);
        RepoLayout layout = layout(root);
        FileClockStore store = new FileClockStore(new FilePublishLog(), FaultInjector.NONE);
        String id = "01J0000000000000000000E778";
        // the append lands after retain listed the file and before it takes the lock
        ClockLockGate real = new ClockLockGate();
        Retain retain = new Retain(new GitRepoLocator(), new SystemClock(), new FileLedgerFacts(), new FileLocalTier(new SystemClock()),
                new GitIndexTrackedFiles(), new ai.speckitty.producer.application.retain.ClockGate() {
                    @Override
                    public <T> Optional<T> underClockLock(RepoLayout l, long wait, java.util.function.Supplier<T> action) {
                        assertThat(store.stampAndAppend(stamp(layout, id, Instant.now().toString()))).isInstanceOf(ParseResult.Accepted.class);
                        return real.underClockLock(l, wait, action);
                    }
                });
        RetainReply reply = ((RetainResult.Replied) retain.retain(apply(root))).reply();
        assertThat(reply.deleted()).isEmpty();
        assertThat(reply.kept()).anyMatch(item -> item.path().equals(LOG_PATH) && item.reason().equals("within-max-age"));
        assertThat(idsIn(root.resolve(LOG))).containsExactly("01J0000000000000000000E001", id);
    }

    @Test
    void twoProcessesRacingProduceAgainstRetainApplyLoseNoReportedRow(@TempDir Path dir) throws Exception {
        Path root = populate(dir);
        Path go = dir.resolve("go");
        int rows = 60;
        int sweeps = 60;
        List<String> producer = ChildJvm.command(RaceChild.class.getName(), "produce", root.toString(), Integer.toString(rows), go.toString());
        List<String> retainer = ChildJvm.command(RaceChild.class.getName(), "retain", root.toString(), Integer.toString(sweeps), go.toString());
        Process a = ChildJvm.start(producer, root);
        Process b = ChildJvm.start(retainer, root);
        CompletableFuture<String> producerOut = ChildJvm.collect(a);
        CompletableFuture<String> retainerOut = ChildJvm.collect(b);
        Thread.sleep(1500);
        Files.writeString(go, "go");
        assertThat(a.waitFor(120, TimeUnit.SECONDS)).as("producer finished").isTrue();
        assertThat(b.waitFor(120, TimeUnit.SECONDS)).as("retainer finished").isTrue();
        String produced = producerOut.get(10, TimeUnit.SECONDS);
        String retained = retainerOut.get(10, TimeUnit.SECONDS);
        assertThat(a.exitValue()).as(produced).isZero();
        assertThat(b.exitValue()).as(retained).isZero();

        long reported = produced.lines().filter(l -> l.endsWith(" produced")).count();
        assertThat(reported).as("every row was reported produced, none refused as busy: " + produced).isEqualTo(rows);
        long deletions = retained.lines().filter(l -> l.contains(LOG)).count();
        assertThat(deletions).as("the race had real contention: retain deleted the file at least once").isGreaterThanOrEqualTo(1);

        // A delete removes everything appended before it. The survivors are therefore always a contiguous run of the produced
        // sequence ending at the last row; a hole would mean a row was appended into a file that was then unlinked.
        Path log = root.resolve(LOG);
        if (Files.exists(log)) {
            TreeSet<Integer> numbers = new TreeSet<>();
            for (String id : idsIn(log)) {
                if (id.startsWith("01J00000000000000000R")) {
                    numbers.add(Integer.parseInt(id.substring(id.length() - 5)));
                }
            }
            if (!numbers.isEmpty()) {
                assertThat(numbers.last()).as("the last reported row is present").isEqualTo(rows);
                assertThat(numbers.last() - numbers.first() + 1).as("no hole among " + numbers).isEqualTo(numbers.size());
            }
        }
    }
}
