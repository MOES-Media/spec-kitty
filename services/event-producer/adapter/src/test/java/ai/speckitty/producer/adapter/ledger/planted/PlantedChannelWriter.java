package ai.speckitty.producer.adapter.ledger.planted;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A planted control: it opens a channel for write. It is never loaded. */
public final class PlantedChannelWriter {
    public FileChannel open(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.WRITE);
    }
}
