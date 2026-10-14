package ai.speckitty.producer.domain.protocol;

/** A repo-relative path with {@code /} separators: never absolute, never leaving the repository (NFR-006). */
public record RelativePath(String value) {

    public RelativePath {
        throw new UnsupportedOperationException("red stub");
    }

    /** True when {@code text} is acceptable as a {@link RelativePath}. */
    public static boolean isValid(String text) {
        throw new UnsupportedOperationException("red stub");
    }
}
