package ai.speckitty.producer.adapter.ledger.planted;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A planted control: a delete outside the one class that may delete. It is never loaded. */
public final class PlantedDeleter {
    public void remove(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
