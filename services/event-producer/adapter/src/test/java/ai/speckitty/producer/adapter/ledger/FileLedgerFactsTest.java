package ai.speckitty.producer.adapter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.retention.MissionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Mission open" (FR-029, R-6): a work package is open when its latest row, by {@code at}, is in a lane other than done or
 * canceled. Both the canonical copy and the coordination-worktree copy are read; an unreadable, unparseable or absent ledger
 * keeps the Mission's files. Every table is pinned both ways.
 */
class FileLedgerFactsTest {

    static final String MISSION = "01J0000000000000000000MSN1";
    static final String DIR = "demo-mission-01J00000";

    private final FileLedgerFacts facts = new FileLedgerFacts();

    /** One transition row in the shape the Python writer produces (sorted keys). */
    static String lane(String wp, String toLane, String at) {
        return "{\"actor\": \"fixture\", \"at\": \"" + at + "\", \"event_id\": \"01J0000000000000000000E" + Math.abs((wp + toLane + at).hashCode() % 1000)
                + "\", \"force\": false, \"from_lane\": \"planned\", \"mission_id\": \"" + MISSION + "\", \"to_lane\": \"" + toLane + "\", \"wp_id\": \"" + wp + "\"}\n";
    }

    static String missionRow() {
        return "{\"aggregate_id\": \"" + MISSION + "\", \"aggregate_type\": \"Mission\", \"event_id\": \"01J0000000000000000000M001\", "
                + "\"event_type\": \"MissionCreated\", \"payload\": {\"wp_id\": \"WP99\", \"to_lane\": \"x\"}, \"timestamp\": \"2026-01-01T00:00:00+00:00\"}\n";
    }

    private static RepoLayout repo(Path dir) throws IOException {
        return LedgerFixtures.repo(dir);
    }

    private static Path mission(RepoLayout layout, String dirName, String missionId, String ledger) throws IOException {
        return LedgerFixtures.mission(layout, dirName, missionId, ledger);
    }

    private MissionState state(RepoLayout layout) {
        return facts.missionState(layout, MISSION);
    }

    @Test
    void everyWorkPackageDoneOrCanceledMeansTheMissionIsClosed(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, missionRow() + lane("WP01", "done", "2026-01-02T00:00:00+00:00") + lane("WP02", "canceled", "2026-01-02T00:00:00+00:00"));
        assertThat(state(layout)).isEqualTo(MissionState.CLOSED);
    }

    @Test
    void anyLaneButDoneAndCanceledIsOpen(@TempDir Path dir) throws IOException {
        for (String openLane : List.of("planned", "claimed", "in_progress", "for_review", "in_review", "approved", "blocked", "doing", "weird-lane", "Done", "")) {
            Path sub = Files.createTempDirectory(dir, "open");
            RepoLayout layout = repo(sub);
            mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00") + lane("WP02", openLane, "2026-01-03T00:00:00+00:00"));
            assertThat(state(layout)).as("lane '" + openLane + "'").isEqualTo(MissionState.OPEN);
        }
        for (String closedLane : List.of("done", "canceled")) {
            Path sub = Files.createTempDirectory(dir, "closed");
            RepoLayout layout = repo(sub);
            mission(layout, DIR, MISSION, lane("WP01", closedLane, "2026-01-02T00:00:00+00:00"));
            assertThat(state(layout)).as(closedLane).isEqualTo(MissionState.CLOSED);
        }
    }

    @Test
    void theLatestRowByAtDecidesNotTheLastLineOfTheFile(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-05T00:00:00+00:00") + lane("WP01", "in_progress", "2026-01-04T00:00:00+00:00"));
        assertThat(state(layout)).as("done is later although listed first").isEqualTo(MissionState.CLOSED);
        Path other = Files.createTempDirectory(dir, "o");
        RepoLayout second = repo(other);
        mission(second, DIR, MISSION, lane("WP01", "in_progress", "2026-01-05T00:00:00+00:00") + lane("WP01", "done", "2026-01-04T00:00:00+00:00"));
        assertThat(state(second)).as("in_progress is later although listed first").isEqualTo(MissionState.OPEN);
    }

    @Test
    void timesAreComparedAsInstantsNotAsText(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        // 10:00+02:00 is 08:00Z, earlier than 09:00Z, although "10:00" sorts after "09:00" as text
        mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-05T09:00:00+00:00") + lane("WP01", "in_progress", "2026-01-05T10:00:00+02:00"));
        assertThat(state(layout)).isEqualTo(MissionState.CLOSED);
    }

    @Test
    void whenTwoRowsShareTheirTimeTheLaterLineWins(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, lane("WP01", "in_progress", "2026-01-05T00:00:00+00:00") + lane("WP01", "done", "2026-01-05T00:00:00+00:00"));
        assertThat(state(layout)).isEqualTo(MissionState.CLOSED);
        Path other = Files.createTempDirectory(dir, "o");
        RepoLayout second = repo(other);
        mission(second, DIR, MISSION, lane("WP01", "done", "2026-01-05T00:00:00+00:00") + lane("WP01", "in_progress", "2026-01-05T00:00:00+00:00"));
        assertThat(state(second)).isEqualTo(MissionState.OPEN);
    }

    @Test
    void rowsThatAreNotTransitionsAreNotWorkPackageLanes(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        // the Mission row carries a nested wp_id and to_lane of its own; it must not count
        mission(layout, DIR, MISSION, missionRow() + lane("WP01", "done", "2026-01-02T00:00:00+00:00") + "\n   \n");
        assertThat(state(layout)).isEqualTo(MissionState.CLOSED);
    }

    @Test
    void theCoordinationCopyIsReadToo_OpenIfEitherSaysSo(@TempDir Path dir) throws IOException {
        RepoLayout canonicalClosedCoordOpen = repo(Files.createTempDirectory(dir, "a"));
        mission(canonicalClosedCoordOpen, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.coordinationCopy(canonicalClosedCoordOpen, DIR, lane("WP01", "in_progress", "2026-01-03T00:00:00+00:00"));
        assertThat(state(canonicalClosedCoordOpen)).isEqualTo(MissionState.OPEN);

        RepoLayout canonicalOpenCoordClosed = repo(Files.createTempDirectory(dir, "b"));
        mission(canonicalOpenCoordClosed, DIR, MISSION, lane("WP01", "in_progress", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.coordinationCopy(canonicalOpenCoordClosed, DIR, lane("WP01", "done", "2026-01-03T00:00:00+00:00"));
        assertThat(state(canonicalOpenCoordClosed)).isEqualTo(MissionState.OPEN);

        RepoLayout bothClosed = repo(Files.createTempDirectory(dir, "c"));
        mission(bothClosed, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.coordinationCopy(bothClosed, DIR, lane("WP01", "done", "2026-01-03T00:00:00+00:00"));
        assertThat(state(bothClosed)).isEqualTo(MissionState.CLOSED);
    }

    @Test
    void aCoordinationCopyThatCannotBeReadKeepsTheMissionEvenWhenTheCanonicalOneIsClosed(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.coordinationCopy(layout, DIR, lane("WP01", "done", "2026-01-03T00:00:00+00:00") + "{\"truncated");
        assertThat(state(layout)).isEqualTo(MissionState.UNAVAILABLE);
    }

    @Test
    void anOpenCopyOutranksAnUnavailableOne(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, lane("WP01", "in_progress", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.coordinationCopy(layout, DIR, "{\"truncated");
        assertThat(state(layout)).isEqualTo(MissionState.OPEN);
    }

    @Test
    void aLaneWorktreeCopyIsNotACoordinationCopy(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        LedgerFixtures.laneWorktreeCopy(layout, DIR, lane("WP01", "in_progress", "2026-01-03T00:00:00+00:00"));
        assertThat(state(layout)).as("a lane worktree holds a stale planning copy").isEqualTo(MissionState.CLOSED);
    }

    @Test
    void aMissionWithAnythingUnreadableOrAbsentIsUnavailable(@TempDir Path dir) throws IOException {
        List<RepoLayout> cases = new ArrayList<>();
        // no kitty-specs at all
        cases.add(repo(Files.createTempDirectory(dir, "nospecs")));
        // a Mission directory for another Mission only
        RepoLayout otherMission = repo(Files.createTempDirectory(dir, "other"));
        mission(otherMission, "elsewhere-01J99999", "01J9999999999999999999ABCD", lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        cases.add(otherMission);
        // the directory exists, the ledger does not
        RepoLayout noLedger = repo(Files.createTempDirectory(dir, "noledger"));
        mission(noLedger, DIR, MISSION, null);
        cases.add(noLedger);
        // a ledger with no work package at all
        RepoLayout noWorkPackages = repo(Files.createTempDirectory(dir, "nowp"));
        mission(noWorkPackages, DIR, MISSION, missionRow());
        cases.add(noWorkPackages);
        RepoLayout emptyLedger = repo(Files.createTempDirectory(dir, "empty"));
        mission(emptyLedger, DIR, MISSION, "");
        cases.add(emptyLedger);
        // a truncated last line
        RepoLayout truncated = repo(Files.createTempDirectory(dir, "trunc"));
        mission(truncated, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00") + "{\"wp_id\": \"WP02\", \"to_la");
        cases.add(truncated);
        // a middle line that is not JSON
        RepoLayout garbled = repo(Files.createTempDirectory(dir, "garbled"));
        mission(garbled, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00") + "not json\n" + lane("WP02", "done", "2026-01-02T00:00:00+00:00"));
        cases.add(garbled);
        // a transition row that cannot be placed in time, or has no lane, or no work package
        for (String bad : List.of(
                "{\"at\": \"not a time\", \"to_lane\": \"done\", \"wp_id\": \"WP01\"}\n",
                "{\"to_lane\": \"done\", \"wp_id\": \"WP01\"}\n",
                "{\"at\": 5, \"to_lane\": \"done\", \"wp_id\": \"WP01\"}\n",
                "{\"at\": \"2026-01-02T00:00:00+00:00\", \"to_lane\": 7, \"wp_id\": \"WP01\"}\n",
                "{\"at\": \"2026-01-02T00:00:00+00:00\", \"to_lane\": \"done\"}\n",
                "{\"at\": \"2026-01-02T00:00:00+00:00\", \"to_lane\": \"done\", \"wp_id\": \"\"}\n")) {
            RepoLayout layout = repo(Files.createTempDirectory(dir, "bad"));
            mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-01T00:00:00+00:00") + bad);
            cases.add(layout);
        }
        // invalid UTF-8
        RepoLayout notUtf8 = repo(Files.createTempDirectory(dir, "utf8"));
        Path ledger = mission(notUtf8, DIR, MISSION, "");
        Files.write(ledger, new byte[] {'{', '"', (byte) 0xff, (byte) 0xfe, '"', '}', '\n'});
        cases.add(notUtf8);
        // the ledger is a directory
        RepoLayout asDirectory = repo(Files.createTempDirectory(dir, "asdir"));
        Path asDirectoryLedger = mission(asDirectory, DIR, MISSION, null);
        Files.createDirectories(asDirectoryLedger);
        cases.add(asDirectory);

        List<String> states = new ArrayList<>();
        for (RepoLayout layout : cases) {
            states.add(state(layout).name());
        }
        assertThat(states).hasSize(cases.size()).containsOnly("UNAVAILABLE");
    }

    @Test
    void aClosedMissionBesideItIsNotConfusedWithTheOneAsked(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        mission(layout, "closed-01J00000", MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        mission(layout, "busy-01J88888", "01J8888888888888888888BUSY", lane("WP01", "in_progress", "2026-01-02T00:00:00+00:00"));
        assertThat(state(layout)).isEqualTo(MissionState.CLOSED);
        assertThat(facts.missionState(layout, "01J8888888888888888888BUSY")).isEqualTo(MissionState.OPEN);
        assertThat(facts.missionState(layout, "01J0000000000000000000MSN2")).as("no such Mission").isEqualTo(MissionState.UNAVAILABLE);
        assertThat(facts.missionState(layout, MISSION.toLowerCase())).as("ids are compared exactly").isEqualTo(MissionState.UNAVAILABLE);
    }

    @Test
    void anUnreadableMetaJsonKeepsTheMissionOnlyWhenItsDirectoryCouldBeTheMissions(@TempDir Path dir) throws IOException {
        RepoLayout unrelated = repo(Files.createTempDirectory(dir, "u"));
        mission(unrelated, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        Path broken = Files.createDirectories(unrelated.canonicalRoot().resolve("kitty-specs/broken-01JZZZZZ"));
        Files.writeString(broken.resolve("meta.json"), "{ not json");
        assertThat(state(unrelated)).as("an unrelated broken meta.json does not block").isEqualTo(MissionState.CLOSED);

        RepoLayout related = repo(Files.createTempDirectory(dir, "r"));
        Path own = Files.createDirectories(related.canonicalRoot().resolve("kitty-specs/mine-01J00000"));
        Files.writeString(own.resolve("meta.json"), "{ not json");
        Files.writeString(own.resolve("status.events.jsonl"), lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        assertThat(state(related)).as("a broken meta.json of a directory named for the Mission").isEqualTo(MissionState.UNAVAILABLE);
    }

    @Test
    void readingChangesNoByteAndNoTimeOfAnyLedger(@TempDir Path dir) throws IOException {
        RepoLayout layout = repo(dir);
        Path ledger = mission(layout, DIR, MISSION, lane("WP01", "done", "2026-01-02T00:00:00+00:00"));
        Path coord = LedgerFixtures.coordinationCopy(layout, DIR, lane("WP01", "done", "2026-01-03T00:00:00+00:00"));
        Path meta = ledger.resolveSibling("meta.json");
        List<Path> files = List.of(ledger, coord, meta);
        for (Path file : files) {
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        }
        List<String> before = files.stream().map(FileLedgerFactsTest::fingerprint).collect(Collectors.toList());
        state(layout);
        assertThat(files.stream().map(FileLedgerFactsTest::fingerprint).collect(Collectors.toList())).isEqualTo(before);
    }

    private static String fingerprint(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant() + "|" + new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
