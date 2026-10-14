package ai.speckitty.producer.domain.envelope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The set of known event types, looked up by full type string. */
public final class TypeRegistry {

    private final Map<String, TypeEntry> byType;

    private TypeRegistry(Map<String, TypeEntry> byType) {
        this.byType = byType;
    }

    public static TypeRegistry of(List<TypeEntry> entries) {
        Map<String, TypeEntry> byType = new HashMap<>();
        for (TypeEntry entry : entries) {
            if (byType.put(entry.typeName(), entry) != null) {
                throw new IllegalArgumentException("duplicate type " + entry.typeName());
            }
        }
        return new TypeRegistry(byType);
    }

    public Optional<TypeEntry> lookup(String typeName) {
        return Optional.ofNullable(typeName == null ? null : byType.get(typeName));
    }

    public int size() {
        return byType.size();
    }
}
