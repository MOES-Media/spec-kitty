package ai.speckitty.producer.application.produce;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A catalog over a list of registered types, classifying a row by data alone. A payload-envelope row is of the type whose
 * discriminator value it carries. A flat row has no discriminator member of any payload type, and is of the flat type whose
 * constants it equals; a flat type is also refused when the row carries a constant member of another flat type that it does not
 * itself constrain, so a row that merely resembles the default type is not taken for it.
 */
public final class ListedCatalog implements TypeCatalog {

    private final List<RegisteredType> types;
    private final Set<String> discriminatorMembers = new HashSet<>();
    private final Set<String> constantMembers = new TreeSet<>();

    public ListedCatalog(List<RegisteredType> types) {
        this.types = List.copyOf(types);
        Set<String> seen = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (RegisteredType type : this.types) {
            if (!seen.add(type.type())) {
                throw new IllegalArgumentException("duplicate type " + type.type());
            }
            String key = type.shape() == Shape.PAYLOAD_ENVELOPE
                    ? "payload|" + type.discriminatorMember() + "|" + type.discriminatorValue()
                    : "flat|" + new TreeSet<>(type.constants().entrySet().stream().map(Object::toString).toList());
            if (!keys.add(key)) {
                throw new IllegalArgumentException("two types cannot be told apart: " + type.type());
            }
            if (type.shape() == Shape.PAYLOAD_ENVELOPE) {
                discriminatorMembers.add(type.discriminatorMember());
            } else {
                constantMembers.addAll(type.constants().keySet());
            }
        }
    }

    /** The number of registered types. */
    public int size() {
        return types.size();
    }

    @Override
    public Lookup find(Map<String, Object> row) {
        List<RegisteredType> matches = new ArrayList<>();
        for (RegisteredType type : types) {
            if (matches(type, row)) {
                matches.add(type);
            }
        }
        if (matches.isEmpty()) {
            return new Unknown("no registered type matches the row");
        }
        if (matches.size() > 1) {
            return new Unknown("more than one registered type matches the row");
        }
        return new Found(matches.get(0));
    }

    private boolean matches(RegisteredType type, Map<String, Object> row) {
        if (type.shape() == Shape.PAYLOAD_ENVELOPE) {
            return type.discriminatorValue().equals(row.get(type.discriminatorMember()));
        }
        for (String member : discriminatorMembers) {
            if (row.containsKey(member)) {
                return false;
            }
        }
        for (String member : constantMembers) {
            String expected = type.constants().get(member);
            if (expected == null ? row.containsKey(member) : !expected.equals(row.get(member))) {
                return false;
            }
        }
        return true;
    }
}
