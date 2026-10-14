package ai.speckitty.producer.domain.value;

import java.math.BigInteger;
import java.util.Optional;

/**
 * A JSON number kept as its literal text, so a number written with a decimal point or an exponent stays distinguishable from an
 * integer however its value reads ({@code 5.0} is not the integer {@code 5}).
 */
public record JsonNumber(String literal) {

    public JsonNumber {
        throw new UnsupportedOperationException("red stub");
    }

    /** True when the literal is written as an integer: no fraction, no exponent. */
    public boolean isInteger() {
        throw new UnsupportedOperationException("red stub");
    }

    /** The exact value of an integer literal, empty for any other literal. */
    public Optional<BigInteger> integerValue() {
        throw new UnsupportedOperationException("red stub");
    }
}
