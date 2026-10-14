package ai.speckitty.producer.application.port;

import ai.speckitty.producer.domain.fault.ParseResult;
import java.nio.file.Path;
import java.util.Objects;

/** Finds the canonical repository root and the git common directory from a start path, without spawning git (FR-004, FR-012). */
public interface RepoLocator {

    /** The detail of the refusal when no repository lies above the start path. */
    String ROOT_UNRESOLVABLE = "root-unresolvable";

    /**
     * The layout above {@code start}; refused as {@code rejected} with detail {@link #ROOT_UNRESOLVABLE} when no repository lies
     * above it. A primary checkout, a linked worktree, a coordination worktree and a Mission subdirectory of any of them give
     * the same layout.
     */
    ParseResult<RepoLayout> locate(Path start);

    /**
     * Where the repository keeps its files.
     *
     * @param canonicalRoot the working tree of the primary checkout (the parity of {@code resolve_canonical_root})
     * @param gitCommonDir the git common directory shared by every worktree of the repository
     */
    record RepoLayout(Path canonicalRoot, Path gitCommonDir) {
        public RepoLayout {
            Objects.requireNonNull(canonicalRoot, "canonicalRoot");
            Objects.requireNonNull(gitCommonDir, "gitCommonDir");
        }
    }
}
