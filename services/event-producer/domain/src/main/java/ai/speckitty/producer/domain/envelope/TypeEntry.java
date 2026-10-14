package ai.speckitty.producer.domain.envelope;

import java.util.List;
import java.util.Map;

/**
 * A registry entry: how one event type maps a persisted row to an envelope. Pure data; no behaviour depends on the type name.
 *
 * @param name the dotted type name without prefix and version, for example {@code a.b.c}
 * @param version the schema generation, at least 1
 * @param scope whether the row belongs to a Mission or to the project
 * @param subject how the {@code subject} attribute is derived
 * @param lifts row members moved to context attributes
 * @param dropped row members that are neither lifted nor kept (the type or the envelope carries them)
 * @param dataRenames row members kept in {@code data} under another name
 */
public record TypeEntry(
        String name,
        int version,
        Scope scope,
        SubjectRule subject,
        List<Lift> lifts,
        List<String> dropped,
        Map<String, String> dataRenames) {

    public static final String PREFIX = "ai.spec-kitty.";

    /** Where an event is scoped. */
    public enum Scope {
        MISSION,
        PROJECT
    }

    /** How the {@code subject} attribute is derived. */
    public enum SubjectRule {
        WORK_PACKAGE,
        NONE
    }

    /** A row member lifted to a context attribute. */
    public record Lift(String rowMember, String attribute) {}

    public TypeEntry {
        throw new UnsupportedOperationException("red stub");
    }

    /** The full type string, {@code ai.spec-kitty.<name>.v<version>}. */
    public String typeName() {
        throw new UnsupportedOperationException("red stub");
    }
}
