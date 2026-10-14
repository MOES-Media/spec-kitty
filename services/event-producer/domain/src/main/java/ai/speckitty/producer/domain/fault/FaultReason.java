package ai.speckitty.producer.domain.fault;

import java.util.Optional;

/**
 * The closed vocabulary of producer fault classes (data-model section 6). There is no {@code disabled} class: the producer
 * cannot be switched off (OD-OPTOUT).
 */
public enum FaultReason {
    BINARY_MISSING("binary-missing"),
    CRASH("crash"),
    TIMEOUT("timeout"),
    MALFORMED_REPLY("malformed-reply"),
    CLOCK_OVERFLOW("clock-overflow"),
    REJECTED("rejected"),
    BUSY("busy");

    private final String wire;

    FaultReason(String wire) {
        this.wire = wire;
    }

    /** The name written on the wire and in the fault log. */
    public String wire() {
        return wire;
    }

    /** The class named by {@code wire}, empty for anything outside the vocabulary. */
    public static Optional<FaultReason> fromWire(String wire) {
        for (FaultReason reason : values()) {
            if (reason.wire.equals(wire)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
