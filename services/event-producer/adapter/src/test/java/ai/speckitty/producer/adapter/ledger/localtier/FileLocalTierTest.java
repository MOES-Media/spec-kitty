package ai.speckitty.producer.adapter.ledger.localtier;

import static ai.speckitty.producer.adapter.ledger.localtier.LocalTiers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LocalFile;
import ai.speckitty.producer.application.retain.LocalTier;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.retention.LockState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local tier on the file system: what is listed, the facts it reports, and the one thing it does to a file, a whole delete. */
class FileLocalTierTest {

    private static final String HELD = "{\"host\": \"fixture-host\", \"pid\": 1, \"schema_version\": 1, \"started_at\": \"2026-10-15T11:59:50+00:00\", \"version\": \"x\"}";

    private final FileLocalTier tier = new FileLocalTier(() -> NOW);

    private static List<String> paths(List<LocalTier.Entry> entries) {
        return entries.stream().map(e -> e.path().value()).collect(Collectors.toList());
    }

    private static LocalFile present(FileLocalTier tier, RepoLayout layout, RelativePath path) {
        Optional<LocalFile> file = tier.inspect(layout, path);
        assertThat(file).as(path.value()).isPresent();
        return file.get();
    }

    @Test
    void everyKindOfCandidateIsListedAndNothingElse(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, LocalTiers.missionLog(), LocalTiers.publishRow("2026-01-01T00:00:00Z"));
        LocalTiers.write(layout, LocalTiers.projectLog(), LocalTiers.publishRow("2026-01-02T00:00:00Z"));
        LocalTiers.write(layout, LocalTiers.faultDay("2026-01-03"), "{}\n");
        LocalTiers.write(layout, LocalTiers.journal("run-1"), LocalTiers.journalRow("MissionRunStarted", "2026-01-04T00:00:00Z"));
        // none of these is a candidate
        LocalTiers.write(layout, "kitty-specs/m-01J00000/status.events.jsonl", "{}\n");
        LocalTiers.write(layout, "kitty-specs/m-01J00000/meta.json", "{}\n");
        LocalTiers.write(layout, ".kittify/events/publish/clock.json", "{}\n");
        LocalTiers.write(layout, ".kittify/events/publish/missions/" + LocalTiers.MISSION + ".jsonl.tmp", "{}\n");
        LocalTiers.write(layout, ".kittify/events/publish/missions/not-a-ulid.jsonl", "{}\n");
        LocalTiers.write(layout, ".kittify/events/faults/not-a-date.jsonl", "{}\n");
        LocalTiers.write(layout, ".kittify/runtime/runs/run-1/state.json", "{}\n");
        LocalTiers.write(layout, ".kittify/runtime/runs/run-1/state.json.lock", "");
        LocalTiers.write(layout, ".kittify/runtime/runs/run-2/notes.txt", "x");
        LocalTiers.write(layout, ".git/spec-kitty/event-producer/clock.json", "{}\n");
        LocalTiers.write(layout, ".git/spec-kitty/event-producer/clock.lock", "");

        assertThat(paths(tier.list(layout))).containsExactly(
                ".kittify/events/faults/2026-01-03.jsonl",
                ".kittify/events/publish/missions/" + LocalTiers.MISSION + ".jsonl",
                ".kittify/events/publish/project-" + LocalTiers.PROJECT + ".jsonl",
                ".kittify/runtime/runs/run-1/run.events.jsonl");
    }

    @Test
    void anEmptyRepositoryListsNothing(@TempDir Path dir) throws IOException {
        assertThat(tier.list(LocalTiers.repo(dir))).isEmpty();
    }

    @Test
    void aPublishLogReportsItsLengthAndTheNewestRowTimeNotTheLast(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        String text = LocalTiers.publishRow("2026-03-01T00:00:00Z")
                + LocalTiers.publishRow("2026-05-01T10:00:00+02:00")
                + LocalTiers.publishRow("2026-02-01T00:00:00Z");
        LocalTiers.write(layout, LocalTiers.missionLog(), text);
        LocalFile file = present(tier, layout, LocalTiers.missionLog());
        assertThat(file.length()).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        assertThat(file.newest()).isEqualTo(Instant.parse("2026-05-01T08:00:00Z"));
        assertThat(file.lock()).isEqualTo(LockState.NONE);
        assertThat(file.unfinishedStep()).isFalse();
    }

    @Test
    void aTornFinalLineAndNonJsonLinesAreSkippedWhenReadingTheNewestRow(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, LocalTiers.missionLog(),
                LocalTiers.publishRow("2026-03-01T00:00:00Z") + "garbage\n{\"time\":\"2027-01-01T00:00:00Z\"");
        assertThat(present(tier, layout, LocalTiers.missionLog()).newest()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void aFileWithoutAnyRowTimeIsAsOldAsItsModificationTime(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Path file = LocalTiers.write(layout, LocalTiers.missionLog(), "not json\n{\"x\":1}\n");
        Instant modified = Instant.parse("2025-06-06T06:06:06Z");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        assertThat(present(tier, layout, LocalTiers.missionLog()).newest()).isEqualTo(modified);
    }

    @Test
    void aFaultDayIsAsOldAsTheEndOfItsUtcDay(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, LocalTiers.faultDay("2026-10-14"), "{}\n");
        LocalFile file = present(tier, layout, LocalTiers.faultDay("2026-10-14"));
        assertThat(file.newest()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(file.lock()).isEqualTo(LockState.NONE);
        assertThat(file.unfinishedStep()).isFalse();
    }

    @Test
    void aJournalWithoutItsCompletionRowIsAnUnfinishedStep(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, LocalTiers.journal("open"),
                LocalTiers.journalRow("MissionRunStarted", "2026-01-01T00:00:00Z") + LocalTiers.journalRow("NextStepIssued", "2026-01-02T00:00:00Z"));
        LocalTiers.write(layout, LocalTiers.journal("done"),
                LocalTiers.journalRow("MissionRunStarted", "2026-01-01T00:00:00Z") + LocalTiers.journalRow("MissionRunCompleted", "2026-01-03T00:00:00Z"));
        LocalTiers.write(layout, LocalTiers.journal("torn"),
                LocalTiers.journalRow("MissionRunStarted", "2026-01-01T00:00:00Z") + "{\"event_type\":\"MissionRunComple");
        assertThat(present(tier, layout, LocalTiers.journal("open")).unfinishedStep()).isTrue();
        assertThat(present(tier, layout, LocalTiers.journal("open")).newest()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(present(tier, layout, LocalTiers.journal("done")).unfinishedStep()).isFalse();
        assertThat(present(tier, layout, LocalTiers.journal("done")).newest()).isEqualTo(Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(present(tier, layout, LocalTiers.journal("torn")).unfinishedStep()).as("a torn completion row is not a completion").isTrue();
    }

    @Test
    void aJournalTakesItsLockFactFromTheSidecarBesideItNeverFromThePresenceOfOne(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        String done = LocalTiers.journalRow("MissionRunCompleted", "2026-01-01T00:00:00Z");
        for (String run : List.of("live", "released", "absent", "stale", "unreadable")) {
            LocalTiers.write(layout, LocalTiers.journal(run), done);
        }
        LocalTiers.write(layout, ".kittify/runtime/runs/live/state.json.lock", HELD);
        LocalTiers.write(layout, ".kittify/runtime/runs/released/state.json.lock", "");
        LocalTiers.write(layout, ".kittify/runtime/runs/stale/state.json.lock", HELD.replace("11:59:50", "10:00:00"));
        Files.createDirectories(layout.canonicalRoot().resolve(".kittify/runtime/runs/unreadable/state.json.lock"));

        assertThat(present(tier, layout, LocalTiers.journal("live")).lock()).isEqualTo(LockState.LIVE);
        assertThat(present(tier, layout, LocalTiers.journal("released")).lock()).isEqualTo(LockState.NONE);
        assertThat(present(tier, layout, LocalTiers.journal("absent")).lock()).isEqualTo(LockState.NONE);
        assertThat(present(tier, layout, LocalTiers.journal("stale")).lock()).isEqualTo(LockState.NONE);
        assertThat(present(tier, layout, LocalTiers.journal("unreadable")).lock()).isEqualTo(LockState.UNREADABLE);
    }

    @Test
    void theClockFilesAreNeverALockFactOfAnyRun(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, LocalTiers.journal("r"), LocalTiers.journalRow("MissionRunCompleted", "2026-01-01T00:00:00Z"));
        LocalTiers.write(layout, ".git/spec-kitty/event-producer/clock.lock", HELD);
        assertThat(present(tier, layout, LocalTiers.journal("r")).lock()).isEqualTo(LockState.NONE);
    }

    @Test
    void aCandidateNameThatIsNotARegularFileIsListedAsUnreadableAndNeverInspected(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Path asDirectory = layout.canonicalRoot().resolve(LocalTiers.missionLog().value());
        Files.createDirectories(asDirectory);
        Path target = LocalTiers.write(layout, "kitty-specs/m/status.events.jsonl", "{}\n");
        Path link = layout.canonicalRoot().resolve(LocalTiers.projectLog().value());
        Files.createDirectories(link.getParent());
        boolean linked = true;
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            linked = false;
        }
        List<LocalTier.Entry> entries = tier.list(layout);
        assertThat(entries).allMatch(e -> e instanceof LocalTier.Entry.Unreadable);
        assertThat(paths(entries)).contains(LocalTiers.missionLog().value());
        if (linked) {
            assertThat(paths(entries)).contains(LocalTiers.projectLog().value());
            assertThat(tier.delete(layout, LocalTiers.projectLog())).as("a link is never deleted").isFalse();
            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(Files.exists(target)).isTrue();
        }
        assertThat(tier.delete(layout, LocalTiers.missionLog())).isFalse();
        assertThat(Files.isDirectory(asDirectory)).isTrue();
    }

    @Test
    void inspectRefusesAPathThatIsNotACandidateEvenWhenItExists(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        LocalTiers.write(layout, "kitty-specs/m/status.events.jsonl", "{}\n");
        LocalTiers.write(layout, ".git/spec-kitty/event-producer/clock.json", "{}\n");
        assertThat(tier.inspect(layout, new RelativePath("kitty-specs/m/status.events.jsonl"))).isEmpty();
        assertThat(tier.inspect(layout, new RelativePath(".git/spec-kitty/event-producer/clock.json"))).isEmpty();
        assertThat(tier.inspect(layout, LocalTiers.missionLog())).as("a candidate that does not exist").isEmpty();
    }

    @Test
    void deleteRemovesTheWholeCandidateFileAndOnlyThat(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Path victim = LocalTiers.write(layout, LocalTiers.missionLog(), LocalTiers.publishRow("2026-01-01T00:00:00Z"));
        Path neighbour = LocalTiers.write(layout, LocalTiers.missionLog(LocalTiers.OTHER_MISSION), LocalTiers.publishRow("2026-01-01T00:00:00Z"));
        byte[] neighbourBytes = Files.readAllBytes(neighbour);
        assertThat(tier.delete(layout, LocalTiers.missionLog())).isTrue();
        assertThat(victim).doesNotExist();
        assertThat(Files.readAllBytes(neighbour)).isEqualTo(neighbourBytes);
        assertThat(tier.delete(layout, LocalTiers.missionLog())).as("already gone").isFalse();
    }

    @Test
    void deleteRefusesEveryPathThatIsNotACandidateAndTouchesNothing(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Map<String, byte[]> before = new HashMap<>();
        for (String path : List.of(
                "kitty-specs/m-01J00000/status.events.jsonl",
                "kitty-specs/m-01J00000/meta.json",
                ".git/spec-kitty/event-producer/clock.json",
                ".git/spec-kitty/event-producer/clock.lock",
                ".kittify/config.yaml",
                ".kittify/runtime/runs/r/state.json.lock",
                "README.md")) {
            Path file = LocalTiers.write(layout, path, "committed\n");
            before.put(path, Files.readAllBytes(file));
        }
        for (String path : before.keySet()) {
            assertThat(tier.delete(layout, new RelativePath(path))).as(path).isFalse();
            assertThat(Files.readAllBytes(layout.canonicalRoot().resolve(path))).as(path).isEqualTo(before.get(path));
        }
    }

    @Test
    void aDeleteThatTheFileSystemRefusesIsReportedAsFailedAndTheFileStays(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Path victim = LocalTiers.write(layout, LocalTiers.missionLog(), LocalTiers.publishRow("2026-01-01T00:00:00Z"));
        Path parent = victim.getParent();
        assumeTrue(parent.toFile().setWritable(false) && !Files.isWritable(parent), "the file system enforces a read-only directory");
        try {
            assertThat(tier.delete(layout, LocalTiers.missionLog())).isFalse();
            assertThat(victim).exists();
        } finally {
            parent.toFile().setWritable(true);
        }
    }

    @Test
    void listingAndInspectingChangeNoByteNoSizeAndNoTime(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Map<RelativePath, String> files = new HashMap<>();
        files.put(LocalTiers.missionLog(), LocalTiers.publishRow("2026-01-01T00:00:00Z"));
        files.put(LocalTiers.faultDay("2026-01-03"), "{}\n");
        files.put(LocalTiers.journal("r"), LocalTiers.journalRow("MissionRunStarted", "2026-01-01T00:00:00Z"));
        Map<RelativePath, FileTime> times = new HashMap<>();
        for (Map.Entry<RelativePath, String> file : files.entrySet()) {
            Path written = LocalTiers.write(layout, file.getKey(), file.getValue());
            Files.setLastModifiedTime(written, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
            times.put(file.getKey(), Files.getLastModifiedTime(written));
        }
        tier.list(layout);
        for (RelativePath path : files.keySet()) {
            tier.inspect(layout, path);
            Path file = layout.canonicalRoot().resolve(path.value());
            assertThat(Files.readString(file)).isEqualTo(files.get(path));
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(times.get(path));
        }
    }
}
