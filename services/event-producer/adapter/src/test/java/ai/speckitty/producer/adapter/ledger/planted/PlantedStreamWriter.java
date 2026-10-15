package ai.speckitty.producer.adapter.ledger.planted;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** A planted control: it opens an output stream. It is never loaded. */
public final class PlantedStreamWriter {
    public OutputStream open(Path file) throws IOException {
        return new FileOutputStream(file.toFile(), true);
    }
}
