package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.retention.PolicyError;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reply to a {@code retain}.
 *
 * @param version the protocol version, 1
 * @param dryRun whether nothing was deleted by request
 * @param eligible files eligible (dry run) or eligible and attempted
 * @param kept files kept, each with its reason
 * @param deleted files deleted: only eligible ones, and none on a dry run
 * @param policyError the named error for an invalid setting; with it, the three lists are empty
 */
public record RetainReply(
        int version,
        boolean dryRun,
        List<RetainItem> eligible,
        List<RetainItem> kept,
        List<RelativePath> deleted,
        PolicyError policyError) {

    public RetainReply {
        if (version != Protocol.VERSION) {
            throw new IllegalArgumentException("unsupported protocol version");
        }
        eligible = List.copyOf(eligible);
        kept = List.copyOf(kept);
        deleted = List.copyOf(deleted);
        if (policyError != null && !(eligible.isEmpty() && kept.isEmpty() && deleted.isEmpty())) {
            throw new IllegalArgumentException("a policy error lists nothing and deletes nothing");
        }
        if (dryRun && !deleted.isEmpty()) {
            throw new IllegalArgumentException("a dry run deletes nothing");
        }
        Set<RelativePath> eligiblePaths = new HashSet<>();
        for (RetainItem item : eligible) {
            eligiblePaths.add(item.path());
        }
        for (RetainItem item : kept) {
            if (eligiblePaths.contains(item.path())) {
                throw new IllegalArgumentException("a file is not both eligible and kept");
            }
        }
        if (!eligiblePaths.containsAll(deleted)) {
            throw new IllegalArgumentException("only eligible files are deleted");
        }
    }
}
