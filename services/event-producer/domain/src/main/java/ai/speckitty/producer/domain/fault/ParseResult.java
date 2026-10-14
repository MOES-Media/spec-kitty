package ai.speckitty.producer.domain.fault;

import java.util.Objects;

/** The outcome of a domain parse: a value, or a refusal carrying a fault class and a one-line detail. */
public sealed interface ParseResult<T> {

    /** The parse succeeded. */
    record Accepted<T>(T value) implements ParseResult<T> {
        public Accepted {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The parse refused its input; {@code reason} is the fault class a caller reports. */
    record Refused<T>(FaultReason reason, String detail) implements ParseResult<T> {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            if (detail.indexOf('\n') >= 0 || detail.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("a refusal detail is one line");
            }
        }
    }

    default boolean isAccepted() {
        return this instanceof Accepted<T>;
    }
}
