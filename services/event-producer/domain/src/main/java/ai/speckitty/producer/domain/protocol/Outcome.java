package ai.speckitty.producer.domain.protocol;

import java.util.Optional;

/** What a {@code produce} did. */
public enum Outcome {
    PRODUCED("produced"),
    SKIPPED_DUPLICATE("skipped_duplicate"),
    REFUSED("refused");

    private final String wire;

    Outcome(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<Outcome> fromWire(String wire) {
        for (Outcome outcome : values()) {
            if (outcome.wire.equals(wire)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }
}
