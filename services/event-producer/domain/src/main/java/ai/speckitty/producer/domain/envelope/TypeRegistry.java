package ai.speckitty.producer.domain.envelope;

import java.util.List;
import java.util.Optional;

/** The set of known event types, looked up by full type string. */
public final class TypeRegistry {

    private TypeRegistry() {}

    public static TypeRegistry of(List<TypeEntry> entries) {
        throw new UnsupportedOperationException("red stub");
    }

    public Optional<TypeEntry> lookup(String typeName) {
        throw new UnsupportedOperationException("red stub");
    }

    public int size() {
        throw new UnsupportedOperationException("red stub");
    }
}
