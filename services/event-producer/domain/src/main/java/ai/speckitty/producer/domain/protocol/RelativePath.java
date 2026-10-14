package ai.speckitty.producer.domain.protocol;

/** A repo-relative path with {@code /} separators: never absolute, never leaving the repository (NFR-006). */
public record RelativePath(String value) {

    public RelativePath {
        if (!isValid(value)) {
            throw new IllegalArgumentException("not a repo-relative path");
        }
    }

    /** True when {@code text} is acceptable as a {@link RelativePath}. */
    public static boolean isValid(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) == '/' || text.charAt(text.length() - 1) == '/') {
            return false;
        }
        if (text.length() >= 2 && text.charAt(1) == ':' && Character.isLetter(text.charAt(0))) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c < ' ') {
                return false;
            }
        }
        if (text.charAt(0) == '~' && text.length() > 1 && text.charAt(1) == '/') {
            return false;
        }
        for (String segment : text.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains(":")) {
                return false;
            }
        }
        return true;
    }
}
