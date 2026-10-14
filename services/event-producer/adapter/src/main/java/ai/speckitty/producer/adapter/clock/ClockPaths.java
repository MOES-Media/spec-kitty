package ai.speckitty.producer.adapter.clock;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import java.nio.file.Path;

/** Where the clock lives: under the git common directory, so every worktree of one repository reaches the same files (OD-CLOCK). */
public final class ClockPaths {

    private ClockPaths() {}

    /** {@code <git-common-dir>/spec-kitty/event-producer}. */
    public static Path directory(RepoLayout layout) {
        return layout.gitCommonDir().resolve("spec-kitty").resolve("event-producer");
    }

    /** The state file, {@code clock.json}. */
    public static Path stateFile(RepoLayout layout) {
        return directory(layout).resolve("clock.json");
    }

    /** The lock file, {@code clock.lock}: zero bytes, never replaced. */
    public static Path lockFile(RepoLayout layout) {
        return directory(layout).resolve("clock.lock");
    }
}
