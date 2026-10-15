package ai.speckitty.producer.adapter.ledger.planted;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A planted control for the no-write-open rule: it appends to a file. It is never loaded. */
public final class PlantedWriter {
    public void touch(Path file) throws IOException {
        Files.writeString(file, "x");
    }
}
