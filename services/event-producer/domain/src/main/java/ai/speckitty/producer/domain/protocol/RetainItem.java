package ai.speckitty.producer.domain.protocol;

/**
 * One local-tier file in a {@code retain} reply, with the reason it is eligible or kept.
 *
 * @param path the repo-relative path
 * @param reason {@code closed-mission-outside-limits} or a keep reason of the retention package
 */
public record RetainItem(RelativePath path, String reason) {

    public RetainItem {
        throw new UnsupportedOperationException("red stub");
    }
}
