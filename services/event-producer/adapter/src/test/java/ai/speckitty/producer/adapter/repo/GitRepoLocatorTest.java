package ai.speckitty.producer.adapter.repo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoLocatorTest {

    private static final String MISSION = "01JAAAA0000000000000000ABC";

    @TempDir
    Path base;

    private final GitRepoLocator locator = new GitRepoLocator(name -> null);

    private RepoLayout located(Path start) {
        ParseResult<RepoLayout> result = locator.locate(start);
        assertThat(result).as(start.toString()).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<RepoLayout>) result).value();
    }

    private static Path publishLogPath(RepoLayout layout) {
        return layout.canonicalRoot().resolve(new PublishLog.Target(PublishLog.Target.Kind.MISSION, MISSION).relativePath().value());
    }

    @Test
    void aPrimaryCheckoutAndItsSubdirectoriesGiveOneLayout() throws Exception {
        Path root = GitFixtures.primary(base, "primary");
        Path mission = Files.createDirectories(root.resolve("kitty-specs/some-mission"));
        RepoLayout expected = new RepoLayout(root, root.resolve(".git"));
        assertThat(located(root)).isEqualTo(expected);
        assertThat(located(mission)).isEqualTo(expected);
        assertThat(located(mission.resolve("not/yet/created"))).isEqualTo(expected);
        Files.writeString(mission.resolve("meta.json"), "{}");
        assertThat(located(mission.resolve("meta.json"))).isEqualTo(expected);
    }

    @Test
    void primaryLinkedAndCoordinationWorktreesAndSubdirectoriesAllGiveTheSamePublishLog() throws Exception {
        Path root = GitFixtures.primary(base, "primary");
        Path lane = GitFixtures.linkedWorktree(root, base.resolve("elsewhere/lane-a"), "lane-a");
        Path coordination = GitFixtures.linkedWorktree(root, root.resolve(".worktrees/coordination"), "coordination");
        Map<String, Path> starts = new LinkedHashMap<>();
        starts.put("primary", root);
        starts.put("primary subdirectory", Files.createDirectories(root.resolve("kitty-specs/m")));
        starts.put("linked worktree", lane);
        starts.put("linked worktree subdirectory", Files.createDirectories(lane.resolve("kitty-specs/m/deeper")));
        starts.put("coordination worktree", coordination);
        starts.put("coordination subdirectory", Files.createDirectories(coordination.resolve("kitty-specs/m")));
        assertThat(starts).hasSize(6);
        Path expectedLog = root.resolve(".kittify/events/publish/missions/" + MISSION + ".jsonl");
        for (Map.Entry<String, Path> start : starts.entrySet()) {
            RepoLayout layout = located(start.getValue());
            assertThat(publishLogPath(layout)).as(start.getKey()).isEqualTo(expectedLog);
            assertThat(layout.gitCommonDir()).as(start.getKey()).isEqualTo(root.resolve(".git"));
        }
    }

    @Test
    void handBuiltWorktreesGiveTheSameAnswerAsRealGit() throws Exception {
        Path root = GitFixtures.handBuiltPrimary(base, "primary");
        Path lane = GitFixtures.handBuiltWorktree(root, base.resolve("lane"), "lane");
        Path inside = GitFixtures.handBuiltWorktree(root, root.resolve(".worktrees/inside"), "inside");
        RepoLayout expected = new RepoLayout(root, root.resolve(".git"));
        for (Path start : List.of(root, lane, inside, Files.createDirectories(lane.resolve("a/b")))) {
            assertThat(located(start)).isEqualTo(expected);
        }
    }

    @Test
    void theCommonDirIsReadFromCommondirNotAssumed() throws Exception {
        Path root = GitFixtures.handBuiltPrimary(base, "primary");
        Path lane = GitFixtures.handBuiltWorktree(root, base.resolve("lane"), "lane");
        Path elsewhere = Files.createDirectories(base.resolve("common-elsewhere"));
        Files.writeString(root.resolve(".git/worktrees/lane/commondir"), elsewhere.toRealPath() + "\n");
        assertThat(located(lane).gitCommonDir()).isEqualTo(elsewhere.toRealPath());
        assertThat(located(lane).canonicalRoot()).isEqualTo(root);
    }

    @Test
    void aRelativeGitdirPointerIsResolvedAgainstTheFileThatHoldsIt() throws Exception {
        Path root = GitFixtures.handBuiltPrimary(base, "primary");
        Path lane = GitFixtures.handBuiltWorktree(root, root.resolve(".worktrees/lane"), "lane");
        Files.writeString(lane.resolve(".git"), "gitdir: ../../.git/worktrees/lane\n");
        assertThat(located(lane)).isEqualTo(new RepoLayout(root, root.resolve(".git")));
    }

    @Test
    void noRepositoryAboveTheStartPathIsRootUnresolvable() throws Exception {
        Path loose = Files.createDirectories(base.resolve("loose/deeper"));
        String ceiling = base.toRealPath().toString();
        GitRepoLocator isolated = new GitRepoLocator(name -> "GIT_CEILING_DIRECTORIES".equals(name) ? ceiling : null);
        ParseResult<RepoLayout> result = isolated.locate(loose);
        assertThat(result).isInstanceOf(ParseResult.Refused.class);
        ParseResult.Refused<RepoLayout> refused = (ParseResult.Refused<RepoLayout>) result;
        assertThat(refused.reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(refused.detail()).isEqualTo(RepoLocator.ROOT_UNRESOLVABLE).isEqualTo("root-unresolvable");
        // control: the same walk finds a repository once there is one
        Files.createDirectories(base.resolve("loose/.git"));
        assertThat(isolated.locate(loose)).isInstanceOf(ParseResult.Accepted.class);
    }

    @Test
    void aCeilingDirectoryIsStillEligibleButNothingAboveItIs() throws Exception {
        Path outer = GitFixtures.handBuiltPrimary(base, "outer");
        Path inner = Files.createDirectories(outer.resolve("inner/work"));
        GitRepoLocator ceilinged = new GitRepoLocator(
                name -> "GIT_CEILING_DIRECTORIES".equals(name) ? outer.resolve("inner").toString() : null);
        assertThat(ceilinged.locate(inner)).isInstanceOf(ParseResult.Refused.class);
        Files.createDirectories(outer.resolve("inner/.git"));
        assertThat(((ParseResult.Accepted<RepoLayout>) ceilinged.locate(inner)).value().canonicalRoot()).isEqualTo(outer.resolve("inner"));
    }

    @Test
    void aSubmodulePointerWithoutTheKittifyMarkerLetsTheWalkContinueToTheEnclosingRepository() throws Exception {
        Path outer = GitFixtures.handBuiltPrimary(base, "outer");
        Path sub = Files.createDirectories(outer.resolve("vendor/sub"));
        Files.createDirectories(outer.resolve(".git/modules/sub"));
        Files.writeString(sub.resolve(".git"), "gitdir: ../../.git/modules/sub\n");
        assertThat(located(sub)).isEqualTo(new RepoLayout(outer, outer.resolve(".git")));
    }

    @Test
    void aSubmodulePointerWithTheKittifyMarkerIsItsOwnRoot() throws Exception {
        Path outer = GitFixtures.handBuiltPrimary(base, "outer");
        Path sub = Files.createDirectories(outer.resolve("vendor/sub"));
        Files.createDirectories(outer.resolve(".git/modules/sub"));
        Files.writeString(sub.resolve(".git"), "gitdir: ../../.git/modules/sub\n");
        Files.createDirectories(sub.resolve(".kittify"));
        assertThat(located(sub)).isEqualTo(new RepoLayout(sub.toRealPath(), outer.resolve(".git/modules/sub").toRealPath()));
    }

    @Test
    void aMalformedGitFileIsNeverTrustedAndTheWalkContinues() throws Exception {
        Path outer = GitFixtures.handBuiltPrimary(base, "outer");
        Path odd = Files.createDirectories(outer.resolve("odd"));
        Files.writeString(odd.resolve(".git"), "this is not a pointer\n");
        assertThat(located(odd)).isEqualTo(new RepoLayout(outer, outer.resolve(".git")));
        Files.writeString(odd.resolve(".git"), "gitdir:\n");
        assertThat(located(odd)).isEqualTo(new RepoLayout(outer, outer.resolve(".git")));
    }

    @Test
    void aMalformedGitFileInAKittifyProjectWithNoUsableCommonDirIsRefusedNotGuessed() throws Exception {
        Path odd = Files.createDirectories(base.resolve("odd"));
        Files.writeString(odd.resolve(".git"), "this is not a pointer\n");
        Files.createDirectories(odd.resolve(".kittify"));
        String ceiling = base.toRealPath().toString();
        GitRepoLocator isolated = new GitRepoLocator(name -> "GIT_CEILING_DIRECTORIES".equals(name) ? ceiling : null);
        assertThat(isolated.locate(odd)).isInstanceOf(ParseResult.Refused.class);
    }
}
