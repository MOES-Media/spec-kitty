package ai.speckitty.producer.launcher.retain;

import static ai.speckitty.producer.launcher.retain.Repos.MISSION;
import static ai.speckitty.producer.launcher.retain.Repos.OLD;
import static ai.speckitty.producer.launcher.retain.Repos.OPEN_MISSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.RequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The retain handler over the real adapters on a temporary repository: facts derived from files, nothing taken from the request. */
class RetainEndToEndTest {

    private static final String MISSION_LOG = ".kittify/events/publish/missions/" + MISSION + ".jsonl";
    private static final String OPEN_LOG = ".kittify/events/publish/missions/" + OPEN_MISSION + ".jsonl";
    private static final String PROJECT_LOG = ".kittify/events/publish/project-" + Repos.PROJECT + ".jsonl";
    private static final String OLD_FAULT = ".kittify/events/faults/2024-01-01.jsonl";
    private static final String NEW_FAULT = ".kittify/events/faults/2026-10-14.jsonl";
    private static final String LEDGER = "kitty-specs/demo-01J00000/status.events.jsonl";

    private final OperationHandler handler = new RetainHandler();

    private static String request(Path root, boolean dryRun, String policy) {
        return "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":20000,\"start_path\":\"" + root.toString().replace("\\", "/") + "\",\"dry_run\":" + dryRun
                + ",\"policy\":" + policy + "}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(Path root, boolean dryRun, String policy) throws RequestException {
        return handler.handle(Repos.parse(request(root, dryRun, policy)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(Object items) {
        List<String> paths = new ArrayList<>();
        for (Object item : (List<Object>) items) {
            paths.add(item instanceof Map<?, ?> map ? (String) map.get("path") : (String) item);
        }
        return paths;
    }

    /** A repository with a closed Mission and an open one, a young project log and two fault days. */
    private static Path populated(Path dir) throws IOException {
        Path root = Repos.repo(dir);
        Repos.mission(root, "demo-01J00000", MISSION, false);
        Repos.mission(root, "busy-01J00000-two", OPEN_MISSION, true);
        Repos.write(root, MISSION_LOG, Repos.publishRow("01J0000000000000000000E001", OLD));
        Repos.write(root, OPEN_LOG, Repos.publishRow("01J0000000000000000000E002", OLD));
        Repos.write(root, PROJECT_LOG, Repos.publishRow("01J0000000000000000000E003", Instant.now().toString()));
        Repos.write(root, OLD_FAULT, "{}\n");
        Repos.write(root, NEW_FAULT, "{}\n");
        return root;
    }

    @Test
    void aDryRunListsExactlyTheEligibleFilesAndChangesNothing(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        List<String> before = Repos.fingerprint(root, ".");
        Map<String, Object> reply = handle(root, true, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(reply).containsEntry("op", "retain").containsEntry("dry_run", true);
        assertThat(paths(reply.get("eligible"))).containsExactlyInAnyOrder(MISSION_LOG, OLD_FAULT);
        assertThat(paths(reply.get("kept"))).containsExactlyInAnyOrder(OPEN_LOG, PROJECT_LOG, NEW_FAULT);
        assertThat(paths(reply.get("deleted"))).isEmpty();
        assertThat(Repos.fingerprint(root, ".")).as("every byte and time of every file").isEqualTo(before);
    }

    @Test
    void theKeptFilesCarryTheReasonTheProducerDerived(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        Map<String, Object> reply = handle(root, true, "{\"keep_runs\":1,\"max_age_days\":30}");
        Map<String, String> reasons = new java.util.HashMap<>();
        for (Object item : (List<?>) reply.get("kept")) {
            Map<?, ?> map = (Map<?, ?>) item;
            reasons.put((String) map.get("path"), (String) map.get("reason"));
        }
        assertThat(reasons).containsEntry(OPEN_LOG, "mission-open").containsEntry(PROJECT_LOG, "within-keep-runs").containsEntry(NEW_FAULT, "within-keep-runs");
    }

    @Test
    void aRealRunDeletesOnlyThoseFilesAndNoCommittedLedgerIsTouched(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        List<String> ledgersBefore = Repos.fingerprint(root, "kitty-specs");
        assertThat(ledgersBefore).as("floor: ledgers exist").hasSize(4);
        Map<String, Object> reply = handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(paths(reply.get("deleted"))).containsExactlyInAnyOrder(MISSION_LOG, OLD_FAULT);
        assertThat(root.resolve(MISSION_LOG)).doesNotExist();
        assertThat(root.resolve(OLD_FAULT)).doesNotExist();
        assertThat(root.resolve(OPEN_LOG)).exists();
        assertThat(root.resolve(PROJECT_LOG)).exists();
        assertThat(root.resolve(NEW_FAULT)).exists();
        assertThat(Repos.fingerprint(root, "kitty-specs")).as("committed ledgers: every byte and time").isEqualTo(ledgersBefore);
        assertThat(root.resolve(LEDGER)).exists();
    }

    @Test
    void anInvalidPolicyDeletesNothingEvenOnARealRun(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        List<String> before = Repos.fingerprint(root, ".");
        Map<String, Object> reply = handle(root, false, "{\"max_age_days\":\"thirty\"}");
        assertThat(((Map<?, ?>) reply.get("policy_error")).get("key")).isEqualTo("ledger.retention.max_age_days");
        assertThat(Repos.fingerprint(root, ".")).isEqualTo(before);
    }

    @Test
    void aMissionWhoseLedgerIsGoneOrBrokenKeepsItsFiles(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        Files.delete(root.resolve(LEDGER));
        Map<String, Object> reply = handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(paths(reply.get("deleted"))).doesNotContain(MISSION_LOG);
        assertThat(root.resolve(MISSION_LOG)).exists();

        Repos.write(root, LEDGER, "{\"truncated");
        Map<String, Object> second = handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(paths(second.get("deleted"))).doesNotContain(MISSION_LOG);
        assertThat(root.resolve(MISSION_LOG)).exists();
    }

    @Test
    void aCoordinationCopyThatSaysOpenKeepsTheFiles(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        Repos.write(root, ".worktrees/demo-01J00000-coord/kitty-specs/demo-01J00000/status.events.jsonl",
                "{\"at\": \"2026-02-01T00:00:00+00:00\", \"to_lane\": \"in_progress\", \"wp_id\": \"WP01\"}\n");
        handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(root.resolve(MISSION_LOG)).exists();
    }

    @Test
    void aRunJournalIsKeptWhileItsLockRecordIsLiveAndUnfinishedAndDeletedOnceReleasedAndComplete(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        String done = "{\"event_type\":\"MissionRunCompleted\",\"timestamp\":\"" + OLD + "\"}\n";
        String journal = ".kittify/runtime/runs/%s/run.events.jsonl";
        Repos.write(root, journal.formatted("live"), done);
        Repos.write(root, ".kittify/runtime/runs/live/state.json.lock",
                "{\"host\": \"fixture-host\", \"pid\": 1, \"schema_version\": 1, \"started_at\": \"" + Instant.now() + "\", \"version\": \"x\"}");
        Repos.write(root, journal.formatted("released"), done);
        Repos.write(root, ".kittify/runtime/runs/released/state.json.lock", "");
        Repos.write(root, journal.formatted("unfinished"), "{\"event_type\":\"MissionRunStarted\",\"timestamp\":\"" + OLD + "\"}\n");
        Repos.write(root, journal.formatted("fresh"), done.replace(OLD, Instant.now().toString()));
        handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");
        assertThat(root.resolve(journal.formatted("live"))).exists();
        assertThat(root.resolve(journal.formatted("unfinished"))).exists();
        assertThat(root.resolve(journal.formatted("fresh"))).exists();
        assertThat(root.resolve(journal.formatted("released"))).doesNotExist();
    }

    @Test
    void aTrackedCommittedLedgerIsNeverWrittenOrDeletedAndAnUntrackedTwinIs(@TempDir Path dir) throws Exception {
        assumeTrue(Repos.gitAvailable(dir), "git is installed");
        Path root = Files.createDirectories(dir.resolve("real")).toRealPath();
        Repos.git(root, "init", "-q", "-b", "main");
        Repos.mission(root, "demo-01J00000", MISSION, false);
        Repos.write(root, ".kittify/config.yaml", "project:\n  uuid: " + Repos.PROJECT + "\n");
        // the committed ledger, and a publish log that someone force-added: both tracked
        Repos.write(root, MISSION_LOG, Repos.publishRow("01J0000000000000000000E001", OLD));
        Repos.git(root, "add", "kitty-specs", ".kittify/config.yaml");
        Repos.git(root, "add", "-f", MISSION_LOG);
        // an untracked twin of the same kind
        Repos.mission(root, "other-01J00000", "01J0000000000000000000MSN3", false);
        String twin = ".kittify/events/publish/missions/01J0000000000000000000MSN3.jsonl";
        Repos.write(root, twin, Repos.publishRow("01J0000000000000000000E009", OLD));
        // a project log young enough to be the newest run
        Repos.write(root, PROJECT_LOG, Repos.publishRow("01J0000000000000000000E003", Instant.now().toString()));

        List<String> ledgers = Repos.fingerprint(root, "kitty-specs");
        byte[] trackedLog = Files.readAllBytes(root.resolve(MISSION_LOG));
        Map<String, Object> reply = handle(root, false, "{\"keep_runs\":1,\"max_age_days\":30}");

        assertThat(Repos.fingerprint(root, "kitty-specs")).as("zero writes, zero deletes under kitty-specs").isEqualTo(ledgers);
        assertThat(Files.readAllBytes(root.resolve(MISSION_LOG))).as("the force-added log is untouched").isEqualTo(trackedLog);
        assertThat(paths(reply.get("deleted"))).containsExactly(twin);
        assertThat(root.resolve(twin)).doesNotExist();
        assertThat(paths(reply.get("kept"))).doesNotContain(MISSION_LOG);
        assertThat(paths(reply.get("eligible"))).doesNotContain(MISSION_LOG);
    }

    @Test
    void theReplyCarriesNoAbsolutePath(@TempDir Path dir) throws Exception {
        Path root = populated(dir);
        String text = ai.speckitty.producer.adapter.json.Json.render(handle(root, true, "{\"keep_runs\":1}"));
        assertThat(text).doesNotContain(root.toString()).doesNotContain(root.getParent().toString());
    }
}
