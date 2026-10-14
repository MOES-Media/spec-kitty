package ai.speckitty.producer.domain.retention;

import java.time.Duration;
import java.util.Objects;

/**
 * The facts eligibility is decided over. All are supplied; nothing here reads a clock or a file.
 *
 * @param rank 1 for the newest run of its kind, 2 for the next, and so on
 * @param age how old the file is (newest row time, else modification time), measured by the caller
 * @param mission what the committed ledgers say about its Mission
 * @param lock what its lock sidecar says
 * @param unfinishedStep a run journal without its completion row
 */
public record RetentionFacts(int rank, Duration age, MissionState mission, LockState lock, boolean unfinishedStep) {

    public RetentionFacts {
        if (rank < 1) {
            throw new IllegalArgumentException("a rank starts at 1");
        }
        Objects.requireNonNull(age, "age");
        Objects.requireNonNull(mission, "mission");
        Objects.requireNonNull(lock, "lock");
    }
}
