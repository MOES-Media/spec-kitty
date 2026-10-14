package ai.speckitty.producer.domain.order;

import java.util.Objects;

/** The identity of an event: its source (empty when the row has none) and its normalised id. */
public record SourceId(String source, String id) {

    public SourceId {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
    }
}
