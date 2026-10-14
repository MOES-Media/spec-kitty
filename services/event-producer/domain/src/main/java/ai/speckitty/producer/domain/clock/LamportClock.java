package ai.speckitty.producer.domain.clock;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.math.BigInteger;

/** A Lamport clock value: an integer in 0..2^31-1, the CloudEvents Integer range (FR-012). */
public record LamportClock(int value) {

    public static final int MAX = Integer.MAX_VALUE;

    public LamportClock {
        if (value < 0) {
            throw new IllegalArgumentException("a clock value is not negative");
        }
    }

    /** A clock from an integer; above 2^31-1 is {@code clock-overflow}, below zero {@code rejected}. */
    public static ParseResult<LamportClock> of(long value) {
        if (value > MAX) {
            return new ParseResult.Refused<>(FaultReason.CLOCK_OVERFLOW, "clock above 2^31-1");
        }
        if (value < 0) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "clock below zero");
        }
        return new ParseResult.Accepted<>(new LamportClock((int) value));
    }

    /** A clock from a parsed JSON value; every form but an integer literal in range is refused. */
    public static ParseResult<LamportClock> fromJson(Object value) {
        if (!(value instanceof JsonNumber number) || !number.isInteger()) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "clock is not an integer");
        }
        BigInteger exact = number.integerValue().orElseThrow();
        if (exact.signum() < 0) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "clock below zero");
        }
        if (exact.compareTo(BigInteger.valueOf(MAX)) > 0) {
            return new ParseResult.Refused<>(FaultReason.CLOCK_OVERFLOW, "clock above 2^31-1");
        }
        return new ParseResult.Accepted<>(new LamportClock(exact.intValueExact()));
    }

    /** The next value; at 2^31-1 it is {@code clock-overflow}, never a wrap. */
    public ParseResult<LamportClock> successor() {
        return of((long) value + 1L);
    }
}
