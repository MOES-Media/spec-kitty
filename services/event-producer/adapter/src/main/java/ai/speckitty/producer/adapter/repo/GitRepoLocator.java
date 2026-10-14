package ai.speckitty.producer.adapter.repo;

import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds the canonical repository root and the git common directory by reading {@code .git} and {@code commondir}; git is never
 * spawned. The walk is that of {@code core.paths.resolve_canonical_root}: a {@code .git} directory is a root; a {@code .git} file
 * pointing at {@code <main>/.git/worktrees/<name>} leads back to the primary checkout; any other pointer (submodule, separate git
 * dir, malformed) is a root only when it carries a {@code .kittify} directory, else the walk goes on upward. A listed
 * {@code GIT_CEILING_DIRECTORIES} entry is still examined but never walked past.
 */
public final class GitRepoLocator implements RepoLocator {

    private static final String GITDIR_PREFIX = "gitdir:";

    private final Function<String, String> environment;

    /** A locator reading the process environment. */
    public GitRepoLocator() {
        this(System::getenv);
    }

    /** A locator reading {@code environment} (a variable name to its value, or null). */
    public GitRepoLocator(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public ParseResult<RepoLayout> locate(Path start) {
        Set<Path> ceilings = ceilings();
        for (Path candidate = existingAncestor(start); candidate != null; candidate = candidate.getParent()) {
            Path marker = candidate.resolve(".git");
            if (Files.isDirectory(marker)) {
                return found(candidate, commonDirOf(marker, marker));
            }
            if (Files.isRegularFile(marker)) {
                Optional<Path> pointer = readPointer(marker);
                if (pointer.isPresent() && isWorktreeGitdir(pointer.get())) {
                    Path mainGitDir = pointer.get().getParent().getParent();
                    return found(mainGitDir.getParent(), commonDirOf(pointer.get(), mainGitDir));
                }
                if (Files.isDirectory(candidate.resolve(".kittify"))) {
                    return pointer.isPresent() ? found(candidate, commonDirOf(pointer.get(), pointer.get())) : unresolvable();
                }
            }
            if (ceilings.contains(candidate)) {
                break;
            }
        }
        return unresolvable();
    }

    private static ParseResult<RepoLayout> unresolvable() {
        return new ParseResult.Refused<>(FaultReason.REJECTED, ROOT_UNRESOLVABLE);
    }

    private static ParseResult<RepoLayout> found(Path root, Path commonDir) {
        return new ParseResult.Accepted<>(new RepoLayout(real(root), real(commonDir)));
    }

    /** The common directory a git directory names in its {@code commondir} file, else {@code fallback}. */
    private static Path commonDirOf(Path gitDir, Path fallback) {
        Path file = gitDir.resolve("commondir");
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!text.isEmpty()) {
                    return gitDir.resolve(text).normalize();
                }
            } catch (IOException | InvalidPathException ignored) {
                // an unreadable commondir falls back to the git directory itself
            }
        }
        return fallback;
    }

    private static Optional<Path> readPointer(Path gitFile) {
        try {
            String text = Files.readString(gitFile, StandardCharsets.UTF_8).strip();
            if (!text.startsWith(GITDIR_PREFIX)) {
                return Optional.empty();
            }
            String target = text.substring(GITDIR_PREFIX.length()).strip();
            if (target.isEmpty() || target.indexOf('\n') >= 0) {
                return Optional.empty();
            }
            return Optional.of(gitFile.getParent().resolve(target).normalize());
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** {@code <main>/.git/worktrees/<name>}, or the bare-repository form {@code <repo>.git/worktrees/<name>}. */
    private static boolean isWorktreeGitdir(Path gitdir) {
        Path worktrees = gitdir.getParent();
        Path repository = worktrees == null ? null : worktrees.getParent();
        return worktrees != null
                && repository != null
                && repository.getParent() != null
                && "worktrees".equals(worktrees.getFileName().toString())
                && repository.getFileName().toString().endsWith(".git");
    }

    private Set<Path> ceilings() {
        Set<Path> ceilings = new HashSet<>();
        String raw = environment.apply("GIT_CEILING_DIRECTORIES");
        if (raw != null) {
            for (String part : List.of(raw.split(File.pathSeparator))) {
                if (!part.isBlank()) {
                    try {
                        ceilings.add(real(Path.of(part)));
                    } catch (InvalidPathException ignored) {
                        // a malformed entry ceils nothing
                    }
                }
            }
        }
        return ceilings;
    }

    /** The real path of the nearest existing ancestor of {@code start} (a file is replaced by its directory). */
    private static Path existingAncestor(Path start) {
        Path candidate = start.toAbsolutePath().normalize();
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        if (candidate != null && !Files.isDirectory(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate == null ? null : real(candidate);
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
