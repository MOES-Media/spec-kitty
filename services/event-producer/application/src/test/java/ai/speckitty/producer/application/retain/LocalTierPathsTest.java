package ai.speckitty.producer.application.retain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.protocol.RelativePath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The closed list of local-tier shapes: tables pinned both ways, so no committed ledger can ever be a candidate. */
class LocalTierPathsTest {

    static final String MISSION = "01J0000000000000000000MSN1";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";

    private static Optional<LocalTierPaths.Shape> shape(String path) {
        return LocalTierPaths.classify(new RelativePath(path));
    }

    @Test
    void everyLocalTierShapeIsRecognisedWithItsKindAndId() {
        assertThat(shape(".kittify/events/publish/missions/" + MISSION + ".jsonl"))
                .contains(new LocalTierPaths.Shape(LocalKind.PUBLISH_MISSION, MISSION));
        assertThat(shape(".kittify/events/publish/project-" + PROJECT + ".jsonl"))
                .contains(new LocalTierPaths.Shape(LocalKind.PUBLISH_PROJECT, null));
        assertThat(shape(".kittify/events/faults/2026-10-14.jsonl")).contains(new LocalTierPaths.Shape(LocalKind.FAULT_DAY, null));
        assertThat(shape(".kittify/runtime/runs/run-01.A_b/run.events.jsonl"))
                .contains(new LocalTierPaths.Shape(LocalKind.RUN_JOURNAL, "run-01.A_b"));
    }

    @Test
    void everyOtherPathIsNotACandidate() {
        List<String> notCandidates = List.of(
                // committed ledgers and anything else under kitty-specs
                "kitty-specs/m-01J00000/status.events.jsonl",
                "kitty-specs/m-01J00000/meta.json",
                "kitty-specs/status.events.jsonl",
                ".worktrees/m-coord/kitty-specs/m/status.events.jsonl",
                // the clock state and lock are never candidates
                ".git/spec-kitty/event-producer/clock.json",
                ".git/spec-kitty/event-producer/clock.lock",
                ".kittify/events/publish/clock.json",
                // near misses of each shape
                ".kittify/events/publish/missions/" + MISSION.toLowerCase() + ".jsonl",
                ".kittify/events/publish/missions/" + MISSION + ".json",
                ".kittify/events/publish/missions/" + MISSION + ".jsonl.bak",
                ".kittify/events/publish/missions/sub/" + MISSION + ".jsonl",
                ".kittify/events/publish/missions-extra/" + MISSION + ".jsonl",
                ".kittify/events/publish/missions/NOT-A-ULID.jsonl",
                ".kittify/events/publish/project-NOT-A-UUID.jsonl",
                ".kittify/events/publish/project-" + PROJECT.toUpperCase() + ".jsonl",
                ".kittify/events/publish/" + MISSION + ".jsonl",
                ".kittify/events/faults/2026-13-45.jsonl",
                ".kittify/events/faults/2026-02-30.jsonl",
                ".kittify/events/faults/2026-2-3.jsonl",
                ".kittify/events/faults/2026-10-14.json",
                ".kittify/events/faults/sub/2026-10-14.jsonl",
                ".kittify/events/faults/2026-10-14.jsonl.tmp",
                ".kittify/runtime/runs/r1/state.json",
                ".kittify/runtime/runs/r1/state.json.lock",
                ".kittify/runtime/runs/r1/other/run.events.jsonl",
                ".kittify/runtime/runs/run.events.jsonl",
                ".kittify/runtime/runs/.hidden/run.events.jsonl",
                ".kittify/runtime/runs/r1/run.events.jsonl.bak",
                ".kittify/config.yaml",
                "README.md");
        for (String path : notCandidates) {
            assertThat(shape(path)).as(path).isEmpty();
        }
    }

    @Test
    void theTablesAreNotVacuous() {
        assertThat(shape(".kittify/events/publish/missions/" + MISSION + ".jsonl")).isPresent();
        assertThat(shape("kitty-specs/m/status.events.jsonl")).isEmpty();
    }

    @Test
    void aFaultDayNameMustBeARealUtcDate() {
        assertThat(LocalTierPaths.faultDay("2026-10-14.jsonl")).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(LocalTierPaths.faultDay("2024-02-29.jsonl")).isEqualTo(LocalDate.of(2024, 2, 29));
        for (String bad : List.of("2026-02-29.jsonl", "2026-10-14", "x.jsonl", "2026-10-14 .jsonl", ".jsonl", "+2026-10-14.jsonl")) {
            assertThat(LocalTierPaths.faultDay(bad)).as(bad).isNull();
        }
    }

    @Test
    void theRunLockSidecarIsNamedByTheRunNotByAnyCandidate() {
        assertThat(LocalTierPaths.runLockOf("r1").value()).isEqualTo(".kittify/runtime/runs/r1/state.json.lock");
        assertThat(Map.of("k", LocalTierPaths.runLockOf("r1"))).hasSize(1);
        assertThat(shape(LocalTierPaths.runLockOf("r1").value())).isEmpty();
    }
}
