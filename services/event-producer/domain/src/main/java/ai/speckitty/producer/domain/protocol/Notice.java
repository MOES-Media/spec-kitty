package ai.speckitty.producer.domain.protocol;

import java.util.Objects;

/**
 * A reply notice: a torn final line was found and left in place (FR-021). The client records it; it is not a fault.
 *
 * @param path the repo-relative publish log
 * @param line the 1-based position of the torn line
 */
public record Notice(RelativePath path, int line) {

    public static final String KIND = "torn-line";

    public Notice {
        Objects.requireNonNull(path, "path");
        if (line < 1) {
            throw new IllegalArgumentException("a line position starts at 1");
        }
    }
}
