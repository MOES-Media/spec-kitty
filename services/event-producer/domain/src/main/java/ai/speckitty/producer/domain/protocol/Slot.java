package ai.speckitty.producer.domain.protocol;

import java.util.Optional;

/** The seam slot a row arrives through; the resolved-binding slot is not registered (FR-003). */
public enum Slot {
    STATUS("status"),
    LIFECYCLE("lifecycle");

    private final String wire;

    Slot(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<Slot> fromWire(String wire) {
        for (Slot slot : values()) {
            if (slot.wire.equals(wire)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }
}
