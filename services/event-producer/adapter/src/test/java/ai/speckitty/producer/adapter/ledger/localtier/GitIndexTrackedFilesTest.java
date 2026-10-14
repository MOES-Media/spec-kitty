package ai.speckitty.producer.adapter.ledger.localtier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.speckitty.producer.adapter.repo.GitFixtures;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.TrackedFiles.Tracking;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-031: a tracked ledger is recognised from the git index, without starting git; anything not understood keeps the file. */
class GitIndexTrackedFilesTest {

    private static final RelativePath LEDGER = new RelativePath("kitty-specs/m-01J00000/status.events.jsonl");
    private static final RelativePath LOCAL = new RelativePath(".kittify/events/publish/missions/" + LocalTiers.MISSION + ".jsonl");

    private final GitIndexTrackedFiles tracked = new GitIndexTrackedFiles();

    /** A version 2 or 3 index with these entries, as git lays it out (entries padded to 8 bytes, 20 trailing checksum bytes). */
    static byte[] index(int version, List<String> paths, byte[]... extensions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("DIRC".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(version).array());
        out.writeBytes(ByteBuffer.allocate(4).putInt(paths.size()).array());
        for (String path : paths) {
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            ByteBuffer entry = ByteBuffer.allocate(62 + 2 + name.length + 8);
            entry.position(60);
            entry.putShort((short) (Math.min(name.length, 0x0FFF) | (version == 3 ? 0x4000 : 0)));
            if (version == 3) {
                entry.putShort((short) 0);
            }
            entry.put(name);
            int length = ((version == 3 ? 64 : 62) + name.length + 8) & ~7;
            out.write(entry.array(), 0, length);
        }
        for (byte[] extension : extensions) {
            out.writeBytes(extension);
        }
        out.writeBytes(new byte[20]);
        return out.toByteArray();
    }

    static byte[] extension(String signature, int size) {
        return ByteBuffer.allocate(8 + size).put(signature.getBytes(StandardCharsets.US_ASCII)).putInt(size).array();
    }

    private Map<RelativePath, Tracking> ask(Path dir, byte[] index, RelativePath... paths) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        if (index != null) {
            Files.write(layout.gitCommonDir().resolve("index"), index);
        }
        return tracked.of(layout, List.of(paths));
    }

    @Test
    void aPathInTheIndexIsTrackedAndAnotherIsNot(@TempDir Path dir) throws IOException {
        byte[] index = index(2, List.of("README.md", "kitty-specs/m-01J00000/status.events.jsonl", "src/a.py"));
        assertThat(ask(dir, index, LEDGER, LOCAL)).containsEntry(LEDGER, Tracking.TRACKED).containsEntry(LOCAL, Tracking.UNTRACKED);
    }

    @Test
    void aVersionThreeIndexWithExtendedFlagsIsFollowedToo(@TempDir Path dir) throws IOException {
        byte[] index = index(3, List.of("a.txt", LOCAL.value(), "z.txt"));
        assertThat(ask(dir, index, LEDGER, LOCAL)).containsEntry(LEDGER, Tracking.UNTRACKED).containsEntry(LOCAL, Tracking.TRACKED);
    }

    @Test
    void aForceAddedLocalTierFileIsTracked(@TempDir Path dir) throws IOException {
        assertThat(ask(dir, index(2, List.of(LOCAL.value())), LOCAL)).containsEntry(LOCAL, Tracking.TRACKED);
    }

    @Test
    void aPathThatOnlyOccursInsideALongerTrackedPathIsKeptAsTracked(@TempDir Path dir) throws IOException {
        byte[] index = index(2, List.of("vendor/" + LOCAL.value()));
        assertThat(ask(dir, index, LOCAL)).as("a misreading may only keep more").containsEntry(LOCAL, Tracking.TRACKED);
    }

    @Test
    void anIndexAbsentMeansNothingWasEverStaged(@TempDir Path dir) throws IOException {
        assertThat(ask(dir, null, LEDGER, LOCAL)).containsEntry(LEDGER, Tracking.UNTRACKED).containsEntry(LOCAL, Tracking.UNTRACKED);
    }

    @Test
    void anIndexInAFormThisReaderDoesNotFollowAnswersUnknownForEveryPath(@TempDir Path dir) throws IOException {
        List<byte[]> unfollowed = new ArrayList<>();
        unfollowed.add(index(4, List.of("a")));
        unfollowed.add(index(2, List.of("a"), extension("link", 20)));
        unfollowed.add(index(2, List.of("a"), extension("sdir", 0)));
        unfollowed.add("not an index at all, but long enough to be read as one by length".getBytes(StandardCharsets.US_ASCII));
        unfollowed.add(new byte[0]);
        byte[] truncated = index(2, List.of("a.txt", "b.txt"));
        unfollowed.add(java.util.Arrays.copyOf(truncated, truncated.length - 40));
        byte[] liar = index(2, List.of("a.txt"));
        liar[11] = 5; // five entries are claimed, one is there
        unfollowed.add(liar);
        for (byte[] index : unfollowed) {
            Path sub = Files.createTempDirectory(dir, "case");
            assertThat(ask(sub, index, LEDGER, LOCAL)).containsEntry(LEDGER, Tracking.UNKNOWN).containsEntry(LOCAL, Tracking.UNKNOWN);
        }
    }

    @Test
    void anUnreadableIndexAnswersUnknown(@TempDir Path dir) throws IOException {
        RepoLayout layout = LocalTiers.repo(dir);
        Files.createDirectories(layout.gitCommonDir().resolve("index"));
        assertThat(tracked.of(layout, List.of(LEDGER))).containsEntry(LEDGER, Tracking.UNKNOWN);
    }

    @Test
    void aKnownExtensionAfterTheEntriesIsStepped(@TempDir Path dir) throws IOException {
        byte[] index = index(2, List.of("README.md"), extension("TREE", 12), extension("REUC", 0));
        assertThat(ask(dir, index, LEDGER)).containsEntry(LEDGER, Tracking.UNTRACKED);
    }

    @Test
    void everyAskedPathGetsAnAnswer(@TempDir Path dir) throws IOException {
        assertThat(ask(dir, index(2, List.of("a")), LEDGER, LOCAL)).hasSize(2);
        assertThat(tracked.of(LocalTiers.repo(Files.createTempDirectory(dir, "empty")), List.of())).isEmpty();
    }

    @Test
    void theIndexThatGitItselfWritesIsUnderstood(@TempDir Path dir) throws IOException, InterruptedException {
        assumeTrue(gitAvailable(dir), "git is installed");
        Path root = GitFixtures.primary(dir, "real");
        Path ledger = root.resolve(LEDGER.value());
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "{}\n");
        Path local = root.resolve(LOCAL.value());
        Files.createDirectories(local.getParent());
        Files.writeString(local, "{}\n");
        Path notAdded = root.resolve("kitty-specs/m-01J00000/meta.json");
        Files.writeString(notAdded, "{}\n");
        git(root, "add", LEDGER.value());
        git(root, "add", "-f", LOCAL.value());
        RepoLayout layout = new RepoLayout(root, root.resolve(".git"));
        RelativePath meta = new RelativePath("kitty-specs/m-01J00000/meta.json");
        Map<RelativePath, Tracking> answers = tracked.of(layout, List.of(LEDGER, LOCAL, meta));
        assertThat(answers).containsEntry(LEDGER, Tracking.TRACKED).containsEntry(LOCAL, Tracking.TRACKED).containsEntry(meta, Tracking.UNTRACKED);
    }

    private static boolean gitAvailable(Path dir) {
        try {
            Process process = new ProcessBuilder("git", "--version").directory(dir.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        String nothing = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null";
        builder.environment().put("GIT_CONFIG_GLOBAL", nothing);
        builder.environment().put("GIT_CONFIG_SYSTEM", nothing);
        builder.environment().put("GIT_CEILING_DIRECTORIES", cwd.getParent().toString());
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(String.join(" ", command)).isZero();
    }
}
