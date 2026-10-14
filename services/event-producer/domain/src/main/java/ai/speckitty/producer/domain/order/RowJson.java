package ai.speckitty.producer.domain.order;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The row value model and the byte serialisation that breaks ties between duplicates. A parsed JSON value is {@code null}, a
 * {@link Boolean}, a {@link String}, a {@link JsonNumber}, a {@link List} or a {@link Map} with {@link String} keys; anything
 * else is outside the domain and refused.
 *
 * <p>The output is that of Python's {@code json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)}, which is
 * what the merged contract specifies, so every implementation picks the same survivor.
 */
public final class RowJson {

    private static final int FIRST_PRINTABLE = 0x20;
    private static final int LAST_PRINTABLE = 0x7e;
    private static final int PLAIN_FORM_MAX_POINT = 16;
    private static final int PLAIN_FORM_MIN_POINT = -3;

    private RowJson() {}

    /** Members sorted by code point, separators {@code ,} and {@code :}, every character outside space..tilde escaped. */
    public static String serialize(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof JsonNumber number) {
            out.append(number.isInteger() ? number.integerValue().orElseThrow().toString() : pythonFloat(number.literal()));
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else {
            throw new IllegalArgumentException("not a parsed JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("a JSON object has String keys");
            }
            keys.add(name);
        }
        keys.sort(CodePoints::compare);
        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            quote(out, keys.get(i));
            out.append(':');
            write(out, map.get(keys.get(i)));
        }
        out.append('}');
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c >= FIRST_PRINTABLE && c <= LAST_PRINTABLE) {
                        out.append(c);
                    } else {
                        out.append("\\u").append(String.format("%04x", (int) c));
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * {@link Double#toString} never gives fewer than two digits, so for the smallest subnormals it picks a two-digit form (4.9E-324)
     * where one digit round-trips (5e-324). When that happens, the one-digit form is the shortest and is used.
     */
    private static BigDecimal oneDigitIfExact(double value, BigDecimal shortest) {
        if (shortest.unscaledValue().toString().length() != 2) {
            return shortest;
        }
        BigDecimal one = new BigDecimal(Math.abs(value)).round(new java.math.MathContext(1, java.math.RoundingMode.HALF_EVEN));
        return Double.parseDouble(one.toString()) == Math.abs(value) ? one.stripTrailingZeros() : shortest;
    }

    /** The text Python's {@code repr(float)} gives for the number written as {@code literal}. */
    static String pythonFloat(String literal) {
        double value = Double.parseDouble(literal);
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        String sign = negative ? "-" : "";
        if (value == 0) {
            return sign + "0.0";
        }
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        shortest = oneDigitIfExact(value, shortest);
        String digits = shortest.unscaledValue().toString();
        int point = digits.length() - shortest.scale();
        if (point > PLAIN_FORM_MAX_POINT || point < PLAIN_FORM_MIN_POINT) {
            int exponent = point - 1;
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        if (point <= 0) {
            return sign + "0." + "0".repeat(-point) + digits;
        }
        if (point >= digits.length()) {
            return sign + digits + "0".repeat(point - digits.length()) + ".0";
        }
        return sign + digits.substring(0, point) + "." + digits.substring(point);
    }
}
