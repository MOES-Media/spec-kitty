package ai.speckitty.producer.domain.arch.planted;

/** Planted offender: reads an environment variable. */
public final class PlantedEnvironmentRead {

    private PlantedEnvironmentRead() {}

    public static String home() {
        return System.getenv("HOME");
    }
}
