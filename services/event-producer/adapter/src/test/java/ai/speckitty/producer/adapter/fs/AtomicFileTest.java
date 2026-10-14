package ai.speckitty.producer.adapter.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileTest {

    @TempDir
    Path dir;

    @Test
    void writesTheExactBytesAndCreatesMissingParents() throws IOException {
        Path target = dir.resolve("a/b/state.json");
        byte[] bytes = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        AtomicFile.write(target, bytes);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
    }

    @Test
    void replacesAnExistingFileWholesale() throws IOException {
        Path target = dir.resolve("state.json");
        AtomicFile.write(target, "first and longer".getBytes(StandardCharsets.UTF_8));
        AtomicFile.write(target, "second".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(target)).isEqualTo("second");
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        AtomicFile.write(dir.resolve("state.json"), new byte[] {1});
        AtomicFile.write(dir.resolve("state.json"), new byte[] {2});
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("state.json");
        }
    }

    @Test
    void aFailedMoveLeavesNeitherTheTargetChangedNorATemporaryFile() throws IOException {
        Path target = dir.resolve("state.json");
        Files.createDirectories(target.resolve("occupied"));
        assertThatThrownBy(() -> AtomicFile.write(target, new byte[] {1})).isInstanceOf(IOException.class);
        assertThat(Files.isDirectory(target)).isTrue();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("state.json");
        }
    }

    @Test
    void forcesTheFileToDiskBeforeTheMove() throws IOException {
        long before = AtomicFile.forcedCount();
        AtomicFile.write(dir.resolve("state.json"), new byte[] {1});
        assertThat(AtomicFile.forcedCount()).isGreaterThan(before);
    }
}
