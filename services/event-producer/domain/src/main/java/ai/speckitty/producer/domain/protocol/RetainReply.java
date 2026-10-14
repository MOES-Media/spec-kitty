package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.retention.PolicyError;
import java.util.List;

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
        throw new UnsupportedOperationException("red stub");
    }
}
