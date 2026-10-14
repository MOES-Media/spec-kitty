package ai.speckitty.producer.application.produce;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small builders for parsed-row test data: members in order, numbers as {@link JsonNumber}. */
final class Rows {

    private Rows() {}

    static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static JsonNumber num(String literal) {
        return new JsonNumber(literal);
    }
}
