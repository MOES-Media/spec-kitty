package ai.speckitty.producer.application.retain.planted;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A planted control: a use case that touches the file system itself. It is never loaded. */
public final class PlantedFileReader {
    public String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
