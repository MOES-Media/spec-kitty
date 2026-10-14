package ai.speckitty.producer.launcher;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.math.BigInteger;
import java.util.Map;

/** Typed reads of parsed request members. */
final class Fields {

    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);

    private Fields() {}

    /** True for the JSON integer literal {@code 1} exactly: not {@code 1.0}, not {@code 1e0}, not a string. */
    static boolean isVersionOne(Object value) {
        return value instanceof JsonNumber number && "1".equals(number.literal());
    }

    /** The deadline in whole milliseconds: an integer literal in 1..2^31-1; else -1. */
    static int deadlineMs(Object value) {
        if (value instanceof JsonNumber number && number.isInteger()) {
            BigInteger exact = number.integerValue().orElseThrow();
            if (exact.signum() > 0 && exact.compareTo(MAX_INT) <= 0) {
                return exact.intValueExact();
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectOrNull(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
