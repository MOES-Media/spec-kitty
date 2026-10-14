package ai.speckitty.producer.domain.envelope;

/** The CloudEvents attribute-name rule used here: {@code ^[a-z0-9]{1,20}$} (FR-010). */
public final class AttributeName {

    public static final int MAX_LENGTH = 20;

    private AttributeName() {}

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }
}
