package ai.speckitty.producer.application.produce;

import java.util.Map;
import java.util.Objects;

/** Finds the registered type of a persisted row. The registry is data; no code looks at a type name. */
public interface TypeCatalog {

    /** The answer of a lookup. */
    sealed interface Lookup permits Found, Unknown, Unavailable {}

    /** The row is of this registered type. */
    record Found(RegisteredType type) implements Lookup {
        public Found {
            Objects.requireNonNull(type, "type");
        }
    }

    /** The registry is there and holds no type for this row; {@code detail} is one line, without row values. */
    record Unknown(String detail) implements Lookup {
        public Unknown {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** There is no registry at all (none embedded): nothing can be validated, so nothing is written. */
    enum Unavailable implements Lookup {
        INSTANCE
    }

    /** A catalog with no registry behind it: every lookup is {@link Unavailable}. */
    TypeCatalog UNAVAILABLE = row -> Unavailable.INSTANCE;

    Lookup find(Map<String, Object> row);
}
