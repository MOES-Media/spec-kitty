package ai.speckitty.producer.adapter.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the producer contract of the released spec-kitty-events artifact is read from: {@code registry.json} and {@code
 * composed/rows/<name>.row.schema.json}, by path relative to the contract root. The shipped binary reads the classpath copy the
 * build embedded; tests read the extracted wheel or a small in-memory one.
 */
public interface ContractSource {

    /** The text of the file at this contract-relative path, or empty when there is none. */
    Optional<String> read(String relativePath);

    /** The contract embedded in the jar (and, in the native image, in its resources). */
    static ContractSource classpath(ClassLoader loader) {
        return relativePath -> {
            try (InputStream in = loader.getResourceAsStream(ContractPaths.EMBEDDED_ROOT + relativePath)) {
                return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** The contract extracted into a directory. */
    static ContractSource directory(Path root) {
        return relativePath -> {
            Path file = root.resolve(relativePath);
            try {
                return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
