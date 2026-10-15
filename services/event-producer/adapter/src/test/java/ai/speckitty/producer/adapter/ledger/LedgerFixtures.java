package ai.speckitty.producer.adapter.ledger;

import ai.speckitty.producer.adapter.repo.GitFixtures;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Committed-ledger fixtures: Mission directories with a {@code meta.json} and a {@code status.events.jsonl}. */
final class LedgerFixtures {

    private LedgerFixtures() {}

    static RepoLayout repo(Path parent) throws IOException {
        Path root = GitFixtures.handBuiltPrimary(parent, "repo");
        return new RepoLayout(root, root.resolve(".git"));
    }

    /** A Mission directory under {@code kitty-specs}; returns the ledger path (written unless {@code ledger} is null). */
    static Path mission(RepoLayout layout, String dirName, String missionId, String ledger) throws IOException {
        Path directory = Files.createDirectories(layout.canonicalRoot().resolve("kitty-specs").resolve(dirName));
        Files.writeString(directory.resolve("meta.json"), "{\"mission_id\": \"" + missionId + "\", \"slug\": \"" + dirName + "\"}\n");
        Path file = directory.resolve("status.events.jsonl");
        if (ledger != null) {
            Files.writeString(file, ledger);
        }
        return file;
    }

    /** The copy in the coordination worktree {@code .worktrees/<dir>-coord}. */
    static Path coordinationCopy(RepoLayout layout, String dirName, String ledger) throws IOException {
        return copyIn(layout, ".worktrees/" + dirName + "-coord", dirName, ledger);
    }

    /** The copy in a lane worktree, which is not a coordination worktree. */
    static Path laneWorktreeCopy(RepoLayout layout, String dirName, String ledger) throws IOException {
        return copyIn(layout, ".worktrees/" + dirName + "-lane-a", dirName, ledger);
    }

    private static Path copyIn(RepoLayout layout, String worktree, String dirName, String ledger) throws IOException {
        Path directory = Files.createDirectories(layout.canonicalRoot().resolve(worktree).resolve("kitty-specs").resolve(dirName));
        Path file = directory.resolve("status.events.jsonl");
        Files.writeString(file, ledger);
        return file;
    }
}
