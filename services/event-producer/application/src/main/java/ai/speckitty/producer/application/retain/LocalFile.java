package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.retention.LockState;
import java.time.Instant;
import java.util.Objects;

/**
 * What the local tier says about one candidate file at one moment.
 *
 * @param path the repo-relative path
 * @param length the size in bytes when it was read
 * @param newest the newest row time in the file, else its modification time (the fault log: the end of the UTC day in its name)
 * @param lock what the run's Python-owned lock sidecar says; {@code NONE} for a file with no sidecar
 * @param unfinishedStep a run journal without its completion row
 */
public record LocalFile(RelativePath path, long length, Instant newest, LockState lock, boolean unfinishedStep) {

    public LocalFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(newest, "newest");
        Objects.requireNonNull(lock, "lock");
        if (length < 0) {
            throw new IllegalArgumentException("a length is not negative");
        }
    }
}
