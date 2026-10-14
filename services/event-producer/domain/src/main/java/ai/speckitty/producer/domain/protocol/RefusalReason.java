package ai.speckitty.producer.domain.protocol;

import ai.speckitty.producer.domain.fault.FaultReason;
import java.util.Optional;

/** The closed vocabulary of the {@code reason} a refused {@code produce} reply carries. */
public enum RefusalReason {
    ROOT_UNRESOLVABLE("root-unresolvable"),
    ID_NOT_ULID("id-not-ulid"),
    TIME_UNPARSEABLE("time-unparseable"),
    SCHEMA_INVALID("schema-invalid"),
    TYPE_UNKNOWN("type-unknown"),
    CLOCK_OVERFLOW("clock-overflow"),
    NODEID_UNAVAILABLE("nodeid-unavailable");

    private final String wire;

    RefusalReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** The fault class the client records for this refusal: {@code clock-overflow} or {@code rejected}. */
    public FaultReason faultClass() {
        return this == CLOCK_OVERFLOW ? FaultReason.CLOCK_OVERFLOW : FaultReason.REJECTED;
    }

    public static Optional<RefusalReason> fromWire(String wire) {
        for (RefusalReason reason : values()) {
            if (reason.wire.equals(wire)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
