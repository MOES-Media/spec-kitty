package ai.speckitty.producer.domain.fault;

/** Unwrapping helpers for tests: a wrong outcome is an {@link AssertionError} naming what was found. */
public final class Results {

    private Results() {}

    public static <T> T accepted(ParseResult<T> result) {
        if (result instanceof ParseResult.Accepted<T> accepted) {
            return accepted.value();
        }
        ParseResult.Refused<T> refused = (ParseResult.Refused<T>) result;
        throw new AssertionError("expected an accepted result but was refused as " + refused.reason().wire() + ": " + refused.detail());
    }

    public static <T> ParseResult.Refused<T> refused(ParseResult<T> result) {
        if (result instanceof ParseResult.Refused<T> refused) {
            return refused;
        }
        throw new AssertionError("expected a refusal but was accepted: " + ((ParseResult.Accepted<T>) result).value());
    }

    public static <T> FaultReason refusedReason(ParseResult<T> result) {
        return refused(result).reason();
    }
}
