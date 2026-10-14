package ai.speckitty.producer.application.retain.planted;

import java.io.FileInputStream;
import java.io.IOException;

/** A planted control: java.io in the use case. It is never loaded. */
public final class PlantedStreamUser {
    public int first(String name) throws IOException {
        try (FileInputStream in = new FileInputStream(name)) {
            return in.read();
        }
    }
}
