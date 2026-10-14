package ai.speckitty.producer.domain.order;

/**
 * The row value model and the byte serialisation that breaks ties between duplicates. A parsed JSON value is {@code null}, a
 * {@link Boolean}, a {@link String}, a {@link ai.speckitty.producer.domain.value.JsonNumber}, a {@link java.util.List} or a
 * {@link java.util.Map} with {@link String} keys; anything else is outside the domain.
 */
public final class RowJson {

    private RowJson() {}

    /** Members sorted by code point, separators {@code ,} and {@code :}, every character outside space..tilde escaped. */
    public static String serialize(Object value) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The text Python's {@code repr(float)} gives for the number written as {@code literal}. */
    static String pythonFloat(String literal) {
        throw new UnsupportedOperationException("red stub");
    }
}
