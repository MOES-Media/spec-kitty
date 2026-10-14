package ai.speckitty.producer.adapter.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.adapter.repo.GitFixtures;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AC-023: every worktree of a repository reaches one clock state under the git common dir, outside every working tree. */
class ClockAnchorTest {

    private static final int FLOOR_CHECKOUTS = 2;

    @TempDir
    Path base;

    private static RepoLayout locate(Path start) {
        return ((ParseResult.Accepted<RepoLayout>) new GitRepoLocator().locate(start)).value();
    }

    /** The assertion under test: all anchors equal, under the common dir, outside every linked working tree. */
    static void assertAnchored(Map<String, Path> anchors, Path commonDir, List<Path> linkedWorkingTrees) {
        assertThat(anchors.size()).as("checkouts compared").isGreaterThanOrEqualTo(FLOOR_CHECKOUTS + 1);
        assertThat(anchors.values()).as("one state path").containsOnly(anchors.values().iterator().next());
        Path state = anchors.values().iterator().next();
        assertThat(state.startsWith(commonDir)).as("under the git common dir").isTrue();
        for (Path tree : linkedWorkingTrees) {
            assertThat(state.startsWith(tree)).as("outside " + tree.getFileName()).isFalse();
        }
    }

    @Test
    void primaryAndTwoLinkedWorktreesShareOneStateFileUnderTheCommonDir() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path laneA = GitFixtures.linkedWorktree(primary, base.resolve("lane-a"), "lane-a");
        Path laneB = GitFixtures.linkedWorktree(primary, primary.resolve(".worktrees/lane-b"), "lane-b");
        Map<String, Path> anchors = new LinkedHashMap<>();
        for (Path checkout : List.of(primary, laneA, laneB)) {
            anchors.put(checkout.getFileName().toString(), ClockPaths.stateFile(locate(checkout)));
        }
        assertAnchored(anchors, primary.resolve(".git"), List.of(laneA, laneB));
        assertThat(anchors.values().iterator().next()).isEqualTo(primary.resolve(".git/spec-kitty/event-producer/clock.json"));
    }

    @Test
    void anchoringTheStateAtTheWorktreeRootIsCaught() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path laneA = GitFixtures.linkedWorktree(primary, base.resolve("lane-a"), "lane-a");
        Path laneB = GitFixtures.linkedWorktree(primary, base.resolve("lane-b"), "lane-b");
        Function<Path, Path> mutant = checkout -> checkout.resolve(".kittify/clock.json");
        Map<String, Path> anchors = new LinkedHashMap<>();
        for (Path checkout : List.of(primary, laneA, laneB)) {
            anchors.put(checkout.getFileName().toString(), mutant.apply(checkout));
        }
        assertThatThrownBy(() -> assertAnchored(anchors, primary.resolve(".git"), List.of(laneA, laneB))).isInstanceOf(AssertionError.class);
    }

    @Test
    void aStateInsideALinkedWorkingTreeIsCaughtEvenWhenAllPathsAgree() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path laneA = GitFixtures.linkedWorktree(primary, base.resolve("lane-a"), "lane-a");
        Path laneB = GitFixtures.linkedWorktree(primary, base.resolve("lane-b"), "lane-b");
        Path inside = laneA.resolve("clock.json");
        Map<String, Path> anchors = Map.of("a", inside, "b", inside, "c", inside);
        assertThatThrownBy(() -> assertAnchored(anchors, primary.resolve(".git"), List.of(laneA, laneB))).isInstanceOf(AssertionError.class);
    }

    @Test
    void theClockDirectoryIsCreatedUnderTheCommonDirNotInAnyWorkingTree() throws IOException, InterruptedException {
        Path primary = GitFixtures.primary(base, "primary");
        Path lane = GitFixtures.linkedWorktree(primary, base.resolve("lane"), "lane");
        RepoLayout layout = locate(lane);
        Files.createDirectories(ClockPaths.stateFile(layout).getParent());
        assertThat(Files.isDirectory(primary.resolve(".git/spec-kitty/event-producer"))).isTrue();
        assertThat(Files.exists(lane.resolve("spec-kitty"))).isFalse();
    }
}
