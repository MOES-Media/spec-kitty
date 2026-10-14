package ai.speckitty.producer.domain.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonNumberTest {

    @Test
    void integerLiteralsAreIntegers() {
        for (String literal : List.of("0", "5", "-1", "-0", "2147483648", "123456789012345678901234567890")) {
            JsonNumber number = new JsonNumber(literal);
            assertThat(number.isInteger()).as(literal).isTrue();
            assertThat(number.integerValue()).as(literal).contains(new BigInteger(literal));
        }
    }

    @Test
    void aNegativeZeroIsTheIntegerZero() {
        assertThat(new JsonNumber("-0").integerValue()).contains(BigInteger.ZERO);
    }

    @Test
    void aFractionOrExponentIsNotAnInteger() {
        for (String literal : List.of("5.0", "5e0", "5E0", "1e2", "0.5", "-1.5", "1E+2", "1e-2", "0.0")) {
            JsonNumber number = new JsonNumber(literal);
            assertThat(number.isInteger()).as(literal).isFalse();
            assertThat(number.integerValue()).as(literal).isEmpty();
        }
    }

    @Test
    void textThatIsNotAJsonNumberIsRefused() {
        for (String literal : List.of(
                "", "+1", "01", "1.", ".5", "1e", "0x10", "NaN", "Infinity", "-", "--1", " 1", "1 ", "1\n", "5,0", "1_000")) {
            assertThatThrownBy(() -> new JsonNumber(literal)).as(literal).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new JsonNumber(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
