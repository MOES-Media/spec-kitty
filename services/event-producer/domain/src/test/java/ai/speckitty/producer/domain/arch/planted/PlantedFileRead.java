package ai.speckitty.producer.domain.arch.planted;

import java.nio.file.Files;
import java.nio.file.Path;

/** Planted offender: touches the file system. */
public final class PlantedFileRead {

    private PlantedFileRead() {}

    public static boolean exists() {
        return Files.exists(Path.of("x"));
    }
}
