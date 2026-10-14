package ai.speckitty.producer.domain.envelope;

/** The CloudEvents attribute-name rule used here: {@code ^[a-z0-9]{1,20}$} (FR-010). */
public final class AttributeName {

    public static final int MAX_LENGTH = 20;

    private AttributeName() {}

    public static boolean isValid(String name) {
        throw new UnsupportedOperationException("red stub");
    }
}
