package ai.speckitty.producer.domain.arch.planted;

/** Planted offender: reads the system timer. */
public final class PlantedSystemTime {

    private PlantedSystemTime() {}

    public static long ticks() {
        return System.nanoTime();
    }
}
