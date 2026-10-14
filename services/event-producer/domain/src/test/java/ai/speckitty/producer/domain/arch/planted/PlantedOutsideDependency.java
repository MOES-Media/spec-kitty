package ai.speckitty.producer.domain.arch.planted;

import org.junit.jupiter.api.Assertions;

/** Planted offender: depends on a class outside java.base. */
public final class PlantedOutsideDependency {

    private PlantedOutsideDependency() {}

    public static void use() {
        Assertions.assertTrue(true);
    }
}
