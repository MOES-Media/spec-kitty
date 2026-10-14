package ai.speckitty.producer.domain.value;

import java.math.BigInteger;
import java.util.Optional;

/**
 * A JSON number kept as its literal text, so a number written with a decimal point or an exponent stays distinguishable from an
 * integer however its value reads ({@code 5.0} is not the integer {@code 5}).
 */
public record JsonNumber(String literal) {

    public JsonNumber {
        if (literal == null || !isJsonNumber(literal)) {
            throw new IllegalArgumentException("not a JSON number literal");
        }
    }

    /** True when the literal is written as an integer: no fraction, no exponent. */
    public boolean isInteger() {
        return literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
    }

    /** The exact value of an integer literal, empty for any other literal. */
    public Optional<BigInteger> integerValue() {
        return isInteger() ? Optional.of(new BigInteger(literal)) : Optional.empty();
    }

    /** The JSON number grammar: {@code -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?}, ASCII digits only. */
    private static boolean isJsonNumber(String text) {
        int i = 0;
        int n = text.length();
        if (i < n && text.charAt(i) == '-') {
            i++;
        }
        if (i >= n || !isDigit(text.charAt(i))) {
            return false;
        }
        if (text.charAt(i) == '0') {
            i++;
        } else {
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
        }
        if (i < n && text.charAt(i) == '.') {
            i = skipDigits(text, i + 1);
            if (i < 0) {
                return false;
            }
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            i = skipDigits(text, i);
            if (i < 0) {
                return false;
            }
        }
        return i == n;
    }

    /** The index after one or more digits starting at {@code from}, or -1 when there is no digit there. */
    private static int skipDigits(String text, int from) {
        int i = from;
        while (i < text.length() && isDigit(text.charAt(i))) {
            i++;
        }
        return i == from ? -1 : i;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
