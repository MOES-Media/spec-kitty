package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.retention.KeepReason;
import java.util.Objects;

/**
 * One local-tier file in a {@code retain} reply, with the reason it is eligible or kept.
 *
 * @param path the repo-relative path
 * @param reason {@code closed-mission-outside-limits} or a keep reason of the retention package
 */
public record RetainItem(RelativePath path, String reason) {

    public RetainItem {
        Objects.requireNonNull(path, "path");
        boolean known = false;
        for (KeepReason candidate : KeepReason.values()) {
            known |= candidate.wire().equals(reason);
        }
        if (!known && !KeepReason.ELIGIBLE.equals(reason)) {
            throw new IllegalArgumentException("unknown retention reason");
        }
    }
}
