package ai.speckitty.producer.domain.envelope;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(\\.[a-z0-9]+)*");

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
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a type name is dotted lower-case segments");
        }
        if (version < 1) {
            throw new IllegalArgumentException("a type version is at least 1");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(subject, "subject");
        lifts = List.copyOf(lifts);
        dropped = List.copyOf(dropped);
        dataRenames = Map.copyOf(dataRenames);
        Set<String> members = new HashSet<>();
        Set<String> attributes = new HashSet<>();
        for (Lift lift : lifts) {
            if (!AttributeName.isValid(lift.attribute())) {
                throw new IllegalArgumentException("a lifted attribute name must be a valid attribute name");
            }
            if (!attributes.add(lift.attribute()) || !members.add(lift.rowMember())) {
                throw new IllegalArgumentException("a lift may not repeat a member or an attribute");
            }
        }
        for (String member : dropped) {
            if (!members.add(member)) {
                throw new IllegalArgumentException("a member is lifted or dropped, not both");
            }
        }
        for (String member : dataRenames.keySet()) {
            if (!members.add(member)) {
                throw new IllegalArgumentException("a renamed member is not also lifted or dropped");
            }
        }
    }

    /** The full type string, {@code ai.spec-kitty.<name>.v<version>}. */
    public String typeName() {
        return PREFIX + name + ".v" + version;
    }
}
