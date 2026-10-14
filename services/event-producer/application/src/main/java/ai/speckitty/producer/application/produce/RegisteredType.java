package ai.speckitty.producer.application.produce;

import ai.speckitty.producer.domain.envelope.TypeEntry;
import java.util.Map;
import java.util.Objects;

/**
 * One event type as the registry states it. {@code type}, {@code dataschema} and {@code schemaVersion} are the registry's own
 * strings, carried as they are and never rebuilt from the prefix, so a registry change needs no code change.
 *
 * @param type the full CloudEvents {@code type}
 * @param dataschema the {@code dataschema} URI of the type's {@code data} schema
 * @param schemaVersion the {@code schemaversion} extension value
 * @param entry how a row's members are lifted to attributes and dropped (scope, subject rule, lifted and dropped members)
 * @param shape how the persisted row holds the event
 * @param discriminatorMember for a payload envelope, the member whose value names the kind; null for a flat row
 * @param discriminatorValue for a payload envelope, the value of that member for this type; null for a flat row
 * @param payloadMember for a payload envelope, the member that is the data; null for a flat row
 * @param constants for a flat row, members that must equal these values (and are dropped, the type carries them)
 * @param actorRule how a structured actor projects, or null when the type lifts no actor
 * @param dataAliases data members whose legacy string value is written under its canonical value (member to legacy to canonical),
 *     for example a lane alias; registry data, so the code holds no vocabulary
 */
public record RegisteredType(
        String type,
        String dataschema,
        String schemaVersion,
        TypeEntry entry,
        Shape shape,
        String discriminatorMember,
        String discriminatorValue,
        String payloadMember,
        Map<String, String> constants,
        ActorRule actorRule,
        Map<String, Map<String, String>> dataAliases) {

    /** A type with no value aliases. */
    public RegisteredType(
            String type,
            String dataschema,
            String schemaVersion,
            TypeEntry entry,
            Shape shape,
            String discriminatorMember,
            String discriminatorValue,
            String payloadMember,
            Map<String, String> constants,
            ActorRule actorRule) {
        this(type, dataschema, schemaVersion, entry, shape, discriminatorMember, discriminatorValue, payloadMember, constants, actorRule, Map.of());
    }

    public RegisteredType {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dataschema, "dataschema");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(shape, "shape");
        constants = Map.copyOf(constants);
        Map<String, Map<String, String>> aliases = new java.util.LinkedHashMap<>();
        dataAliases.forEach((member, values) -> aliases.put(member, Map.copyOf(values)));
        dataAliases = Map.copyOf(aliases);
        boolean payload = shape == Shape.PAYLOAD_ENVELOPE;
        if (payload && (discriminatorMember == null || discriminatorValue == null || payloadMember == null)) {
            throw new IllegalArgumentException("a payload envelope names its discriminator and its payload member");
        }
        if (!payload && (discriminatorMember != null || discriminatorValue != null || payloadMember != null)) {
            throw new IllegalArgumentException("a flat row has no discriminator and no payload member");
        }
        if (payload && !constants.isEmpty()) {
            throw new IllegalArgumentException("a payload envelope is told apart by its discriminator, not by constants");
        }
    }
}
