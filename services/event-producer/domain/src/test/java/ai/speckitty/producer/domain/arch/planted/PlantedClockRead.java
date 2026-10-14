package ai.speckitty.producer.domain.arch.planted;

import java.time.Instant;

/** Planted offender: reads the wall clock. */
public final class PlantedClockRead {

    private PlantedClockRead() {}

    public static Instant stamp() {
        return Instant.now();
    }
}
