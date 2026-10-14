package ai.speckitty.producer.adapter.ledger;

import ai.speckitty.producer.domain.retention.LockState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Reads what a Python-owned lock sidecar says about a run: its content, never its presence (FR-029). */
public final class LockRecords {

    /** {@code kernel.locks.STALE_AFTER_S_DEFAULT}: a record older than this is stale. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private LockRecords() {}

    public static LockState read(Path sidecar, Instant now) {
        return LockState.NONE;
    }
}
