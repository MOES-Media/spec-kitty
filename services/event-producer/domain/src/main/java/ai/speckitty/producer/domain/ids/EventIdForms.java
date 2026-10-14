package ai.speckitty.producer.domain.ids;

import java.util.Optional;

/** The reader-side id normalisation of the total order: a ULID upper-cased, a UUID lower-cased and hyphenated. */
public final class EventIdForms {

    static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int ULID_LENGTH = 26;
    private static final int UUID_HYPHENATED_LENGTH = 36;
    private static final int UUID_BARE_LENGTH = 32;
    private static final int[] HYPHENS = {8, 13, 18, 23};

    private EventIdForms() {}

    /** The normal form of {@code id}, empty when it is none of the three accepted forms. */
    public static Optional<String> normalizeForOrder(String id) {
        if (id == null) {
            return Optional.empty();
        }
        if (isUlid(id)) {
            return Optional.of(id.toUpperCase(java.util.Locale.ROOT));
        }
        if (isHyphenatedUuid(id)) {
            return Optional.of(id.toLowerCase(java.util.Locale.ROOT));
        }
        if (isBareUuid(id)) {
            String hex = id.toLowerCase(java.util.Locale.ROOT);
            return Optional.of(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20));
        }
        return Optional.empty();
    }

    /** True for 26 Crockford base32 characters of either case. */
    static boolean isUlid(String text) {
        if (text.length() != ULID_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 'z' || CROCKFORD.indexOf(Character.toUpperCase(c)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** True for the 36-character hyphenated UUID form, either case. */
    static boolean isHyphenatedUuid(String text) {
        if (text.length() != UUID_HYPHENATED_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            boolean hyphenPosition = i == HYPHENS[0] || i == HYPHENS[1] || i == HYPHENS[2] || i == HYPHENS[3];
            if (hyphenPosition ? text.charAt(i) != '-' : !isHex(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** True for the 32-character hex UUID form, either case. */
    static boolean isBareUuid(String text) {
        if (text.length() != UUID_BARE_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isHex(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
