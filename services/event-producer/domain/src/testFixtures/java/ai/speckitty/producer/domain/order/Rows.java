package ai.speckitty.producer.domain.order;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builders for parsed-JSON test rows. */
public final class Rows {

    private Rows() {}

    /** An object from alternating keys and values. */
    public static Map<String, Object> obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values alternate");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    public static List<Object> list(Object... values) {
        return new ArrayList<>(java.util.Arrays.asList(values));
    }

    /** A number kept as its literal text. */
    public static JsonNumber num(String literal) {
        return new JsonNumber(literal);
    }

    /** A copy of {@code row} without {@code member}. */
    public static Map<String, Object> without(Map<String, Object> row, String member) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove(member);
        return copy;
    }

    /** A copy of {@code row} with {@code member} set. */
    public static Map<String, Object> with(Map<String, Object> row, String member, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(member, value);
        return copy;
    }
}
