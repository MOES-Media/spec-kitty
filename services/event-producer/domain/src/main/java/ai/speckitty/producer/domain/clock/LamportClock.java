package ai.speckitty.producer.domain.clock;

import ai.speckitty.producer.domain.fault.ParseResult;

/** A Lamport clock value: an integer in 0..2^31-1, the CloudEvents Integer range (FR-012). */
public record LamportClock(int value) {

    public static final int MAX = Integer.MAX_VALUE;

    public LamportClock {
        throw new UnsupportedOperationException("red stub");
    }

    /** A clock from an integer; above 2^31-1 is {@code clock-overflow}, below zero {@code rejected}. */
    public static ParseResult<LamportClock> of(long value) {
        throw new UnsupportedOperationException("red stub");
    }

    /** A clock from a parsed JSON value; every form but an integer literal in range is refused. */
    public static ParseResult<LamportClock> fromJson(Object value) {
        throw new UnsupportedOperationException("red stub");
    }

    /** The next value; at 2^31-1 it is {@code clock-overflow}, never a wrap. */
    public ParseResult<LamportClock> successor() {
        throw new UnsupportedOperationException("red stub");
    }
}
