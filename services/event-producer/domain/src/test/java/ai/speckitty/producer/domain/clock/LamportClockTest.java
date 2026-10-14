package ai.speckitty.producer.domain.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LamportClockTest {

    private static final long MAX = 2147483647L;

    private static int accepted(ParseResult<LamportClock> result) {
        assertThat(result).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<LamportClock>) result).value().value();
    }

    private static FaultReason refused(ParseResult<LamportClock> result) {
        assertThat(result).isInstanceOf(ParseResult.Refused.class);
        return ((ParseResult.Refused<LamportClock>) result).reason();
    }

    @Test
    void theRangeIsZeroToTwoToThe31MinusOne() {
        assertThat(LamportClock.MAX).isEqualTo((int) MAX);
        assertThat(accepted(LamportClock.of(0))).isEqualTo(0);
        assertThat(accepted(LamportClock.of(MAX))).isEqualTo((int) MAX);
        assertThat(refused(LamportClock.of(MAX + 1))).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(refused(LamportClock.of(Long.MAX_VALUE))).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(refused(LamportClock.of(-1))).isEqualTo(FaultReason.REJECTED);
        assertThat(refused(LamportClock.of(Long.MIN_VALUE))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void theSuccessorAddsOne() {
        assertThat(accepted(new LamportClock(0).successor())).isEqualTo(1);
        assertThat(accepted(new LamportClock((int) MAX - 1).successor())).isEqualTo((int) MAX);
    }

    @Test
    void theSuccessorOfTheMaximumIsAnOverflowNotAWrap() {
        ParseResult<LamportClock> result = new LamportClock(LamportClock.MAX).successor();
        assertThat(refused(result)).isEqualTo(FaultReason.CLOCK_OVERFLOW);
    }

    @Test
    void theRecordRefusesANegativeValue() {
        assertThatThrownBy(() -> new LamportClock(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new LamportClock(0).value()).isEqualTo(0);
    }

    @Test
    void jsonIntegersInRangeAreAccepted() {
        assertThat(accepted(LamportClock.fromJson(new JsonNumber("0")))).isEqualTo(0);
        assertThat(accepted(LamportClock.fromJson(new JsonNumber("-0")))).isEqualTo(0);
        assertThat(accepted(LamportClock.fromJson(new JsonNumber("5")))).isEqualTo(5);
        assertThat(accepted(LamportClock.fromJson(new JsonNumber("2147483647")))).isEqualTo((int) MAX);
    }

    @Test
    void jsonIntegersOutOfRangeAreRefusedWithTheirOwnClass() {
        assertThat(refused(LamportClock.fromJson(new JsonNumber("2147483648")))).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(refused(LamportClock.fromJson(new JsonNumber("99999999999999999999999999")))).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(refused(LamportClock.fromJson(new JsonNumber("-1")))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void everyNonIntegerJsonFormIsRefused() {
        List<Object> forms = List.of(
                new JsonNumber("5.0"), new JsonNumber("5e0"), new JsonNumber("1.5"), "5", "", true, false,
                List.of(5), Map.of("v", 5), Integer.valueOf(5), Long.valueOf(5), Double.valueOf(5));
        for (Object form : forms) {
            assertThat(refused(LamportClock.fromJson(form))).as("%s", form).isEqualTo(FaultReason.REJECTED);
        }
        assertThat(refused(LamportClock.fromJson(null))).isEqualTo(FaultReason.REJECTED);
    }
}
