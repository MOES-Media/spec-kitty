package ai.speckitty.producer.domain.ids;

import java.util.Optional;

/** The reader-side id normalisation of the total order: a ULID upper-cased, a UUID lower-cased and hyphenated. */
public final class EventIdForms {

    private EventIdForms() {}

    /** The normal form of {@code id}, empty when it is none of the three accepted forms. */
    public static Optional<String> normalizeForOrder(String id) {
        throw new UnsupportedOperationException("red stub");
    }
}
