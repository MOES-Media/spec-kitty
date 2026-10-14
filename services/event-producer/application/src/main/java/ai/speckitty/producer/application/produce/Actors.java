package ai.speckitty.producer.application.produce;

import java.util.Map;
import java.util.Optional;

/**
 * The structured actor projection (ADR 0001, data-model section 3). The attribute is the identity of the actor; the structured
 * object itself is kept, unchanged, in {@code data.actorbinding} by {@link Produce}. A value that is neither a string nor an object
 * is passed through untouched, so the envelope and schema checks refuse it instead of it being dropped silently.
 */
public final class Actors {

    private Actors() {}

    /** The {@code actor} attribute value for {@code actor}; empty when the attribute is omitted (absent, or an empty identity). */
    public static Optional<Object> attribute(ActorKind kind, Object actor) {
        if (actor == null) {
            return Optional.empty();
        }
        if (actor instanceof Map<?, ?> object) {
            return switch (kind) {
                case ACTOR_IDENTITY -> firstNonEmpty(object.get("tool"), object.get("role"));
                case RETROSPECTIVE_ACTOR_ID -> firstNonEmpty(object.get("id"));
            };
        }
        if (actor instanceof String text) {
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
        return Optional.of(actor);
    }

    /** True when the row's actor is kept in the binding member: only a structured (object) actor is. */
    public static boolean isBound(Object actor) {
        return actor instanceof Map<?, ?>;
    }

    private static Optional<Object> firstNonEmpty(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof String text && !text.isEmpty()) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }
}
