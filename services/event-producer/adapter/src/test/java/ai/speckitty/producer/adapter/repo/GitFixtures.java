package ai.speckitty.producer.adapter.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Repository fixtures for the tests. {@code git} is spawned here and only here: the producer itself reads {@code .git} and
 * {@code commondir} and never starts a process. The git environment is isolated from the machine's own configuration.
 */
public final class GitFixtures {

    private GitFixtures() {}

    /** A real primary checkout with one commit; the returned path is the real path of the working tree. */
    public static Path primary(Path parent, String name) throws IOException, InterruptedException {
        Path root = Files.createDirectories(parent.resolve(name)).toRealPath();
        git(root, "init", "-q", "-b", "main");
        git(root, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "initial");
        return root;
    }

    /** A real linked worktree of {@code primary} at {@code where}. */
    public static Path linkedWorktree(Path primary, Path where, String branch) throws IOException, InterruptedException {
        Files.createDirectories(where.getParent());
        git(primary, "worktree", "add", "-q", "-b", branch, where.toString());
        return where.toRealPath();
    }

    /** A primary checkout built by hand: a {@code .git} directory only. */
    public static Path handBuiltPrimary(Path parent, String name) throws IOException {
        Path root = Files.createDirectories(parent.resolve(name)).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    /** A linked worktree built by hand, in git's own layout: {@code <primary>/.git/worktrees/<name>} and a {@code .git} file. */
    public static Path handBuiltWorktree(Path primary, Path where, String name) throws IOException {
        Path gitdir = Files.createDirectories(primary.resolve(".git/worktrees").resolve(name));
        Files.writeString(gitdir.resolve("commondir"), "../..\n");
        Files.writeString(gitdir.resolve("gitdir"), where.resolve(".git") + "\n");
        Files.createDirectories(where);
        Files.writeString(where.resolve(".git"), "gitdir: " + gitdir.toString().replace('\\', '/') + "\n");
        return where.toRealPath();
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        String nothing = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null";
        env.put("GIT_CONFIG_GLOBAL", nothing);
        env.put("GIT_CONFIG_SYSTEM", nothing);
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.remove("GIT_DIR");
        env.remove("GIT_WORK_TREE");
        env.remove("GIT_COMMON_DIR");
        env.remove("GIT_INDEX_FILE");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
