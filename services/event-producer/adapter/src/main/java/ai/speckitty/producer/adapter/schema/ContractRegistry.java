package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.produce.ActorKind;
import ai.speckitty.producer.application.produce.ActorRule;
import ai.speckitty.producer.application.produce.ListedCatalog;
import ai.speckitty.producer.application.produce.RegisteredType;
import ai.speckitty.producer.application.produce.Shape;
import ai.speckitty.producer.domain.envelope.TypeEntry;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The registry of the released contract as the producer's data: the registered types, their composed row schemas and a catalog over
 * them. Java holds no per-type code: every field below is read from {@code registry.json}, and the registry's own {@code type},
 * {@code dataschema} and {@code schemaversion} strings are carried as they are. A contract that cannot be used (no registry, no types,
 * a type without its row schema) is a {@link ContractException}; it never becomes an empty registry, because a validator built from
 * nothing would accept everything.
 */
public final class ContractRegistry {

    private static final Pattern NAME = Pattern.compile("(.+)\\.v([0-9]+)");

    private final String schemaVersion;
    private final List<RegisteredType> types;
    private final Map<String, String> rowSchemas;
    private final ListedCatalog catalog;

    private ContractRegistry(String schemaVersion, List<RegisteredType> types, Map<String, String> rowSchemas) {
        this.schemaVersion = schemaVersion;
        this.types = List.copyOf(types);
        this.rowSchemas = Map.copyOf(rowSchemas);
        try {
            this.catalog = new ListedCatalog(this.types);
        } catch (IllegalArgumentException e) {
            throw new ContractException("the registry cannot be told apart: " + e.getMessage(), e);
        }
    }

    /** Reads the registry and every row schema it names; a contract that cannot be used is a {@link ContractException}. */
    public static ContractRegistry load(ContractSource source) {
        return load(source, DataAliases.RELEASED);
    }

    /** As {@link #load(ContractSource)}, with this table of data-value aliases (type name, member, legacy value to canonical value). */
    public static ContractRegistry load(ContractSource source, Map<String, Map<String, Map<String, String>>> aliases) {
        String text = source.read(ContractPaths.REGISTRY).filter(t -> !t.isBlank()).orElseThrow(() -> new ContractException("registry.json is missing or empty"));
        if (!(Json.parseObject(text) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
            throw new ContractException("registry.json is not a JSON object");
        }
        Map<String, Object> root = parsed.value();
        String schemaVersion = string(root, "schemaversion", "registry.json");
        Map<String, Object> shapes = object(root, "legacy_shapes", "registry.json");
        if (!(root.get("types") instanceof List<?> entries)) {
            throw new ContractException("registry.json has no types list");
        }
        if (entries.isEmpty()) {
            throw new ContractException("registry.json holds no types; a validator built from it would accept everything");
        }
        List<RegisteredType> types = new ArrayList<>();
        Map<String, String> rowSchemas = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new ContractException("registry.json holds a type entry that is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            String name = string(map, "name", "a registry type");
            if (!names.add(name)) {
                throw new ContractException("duplicate type " + name);
            }
            RegisteredType type = typeOf(map, name, schemaVersion, shapes, aliases.getOrDefault(name, Map.of()));
            types.add(type);
            rowSchemas.put(type.type(), rowSchemaOf(source, name));
        }
        for (Map.Entry<String, Map<String, Map<String, String>>> alias : aliases.entrySet()) {
            if (!names.contains(alias.getKey())) {
                throw new ContractException("the alias table names " + alias.getKey() + ", which the registry does not hold");
            }
            for (Map.Entry<String, Map<String, String>> member : alias.getValue().entrySet()) {
                if (member.getValue().isEmpty()) {
                    throw new ContractException("the alias table gives " + alias.getKey() + " member " + member.getKey() + " no value");
                }
            }
        }
        return new ContractRegistry(schemaVersion, types, rowSchemas);
    }

    private static String rowSchemaOf(ContractSource source, String name) {
        String text = source.read(ContractPaths.rowSchema(name)).orElse("");
        if (text.isBlank() || !text.stripLeading().startsWith("{")) {
            throw new ContractException("the contract holds no usable row schema for the registered type " + name);
        }
        if (!(Json.parseObject(text) instanceof ParseResult.Accepted<Map<String, Object>>)) {
            throw new ContractException("the row schema of the registered type " + name + " is not JSON");
        }
        return text;
    }

    private static RegisteredType typeOf(
            Map<String, Object> map,
            String name,
            String schemaVersion,
            Map<String, Object> shapes,
            Map<String, Map<String, String>> aliases) {
        String where = "type " + name;
        String type = string(map, "type", where);
        String dataschema = string(map, "dataschema", where);
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) {
            throw new ContractException(where + " has no version suffix");
        }
        TypeEntry.Scope scope = switch (string(map, "scope", where)) {
            case "mission" -> TypeEntry.Scope.MISSION;
            case "project" -> TypeEntry.Scope.PROJECT;
            case String other -> throw new ContractException(where + " has the unknown scope " + other);
        };
        TypeEntry.SubjectRule subject = switch (string(map, "subject", where)) {
            case "wp_id" -> TypeEntry.SubjectRule.WORK_PACKAGE;
            case "none" -> TypeEntry.SubjectRule.NONE;
            case String other -> throw new ContractException(where + " has the unknown subject rule " + other);
        };
        string(map, "schema", where);
        Map<String, Object> legacy = object(map, "legacy", where);
        String shapeName = string(legacy, "shape", where);
        if (!(shapes.get(shapeName) instanceof Map<?, ?>)) {
            throw new ContractException(where + " names the unknown legacy shape " + shapeName);
        }
        Map<String, Object> shape = object(shapes, shapeName, "legacy_shapes");

        List<TypeEntry.Lift> lifts = new ArrayList<>();
        Set<String> liftedMembers = new LinkedHashSet<>();
        ActorRule actorRule = null;
        if (!(map.get("lifted") instanceof List<?> lifted)) {
            throw new ContractException(where + " has no lifted list");
        }
        for (Object item : lifted) {
            if (!(item instanceof Map<?, ?> rawLift)) {
                throw new ContractException(where + " has a lifted member that is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> lift = (Map<String, Object>) rawLift;
            String member = string(lift, "member", where);
            String attribute = string(lift, "attribute", where);
            if (lift.get("projection") != null) {
                if (actorRule != null) {
                    throw new ContractException(where + " projects more than one actor");
                }
                actorRule = new ActorRule(member, kindOf(string(lift, "projection", where), where), string(lift, "binding_member", where));
            }
            if (!"type".equals(attribute)) {
                lifts.add(new TypeEntry.Lift(member, attribute));
            }
            liftedMembers.add(member);
        }

        Shape shapeKind;
        String discriminatorMember = null;
        String discriminatorValue = null;
        String payloadMember = null;
        Map<String, String> constants = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        switch (string(shape, "form", "legacy shape " + shapeName)) {
            case "flat" -> {
                shapeKind = Shape.FLAT;
                if (legacy.get("constants") instanceof Map<?, ?> given) {
                    for (Map.Entry<?, ?> constant : given.entrySet()) {
                        if (!(constant.getKey() instanceof String key) || !(constant.getValue() instanceof String value)) {
                            throw new ContractException(where + " has a constant that is not a string");
                        }
                        constants.put(key, value);
                        dropped.add(key);
                    }
                }
            }
            case "payload_envelope" -> {
                shapeKind = Shape.PAYLOAD_ENVELOPE;
                discriminatorMember = string(shape, "discriminator_member", "legacy shape " + shapeName);
                payloadMember = string(shape, "payload_member", "legacy shape " + shapeName);
                discriminatorValue = string(legacy, "discriminator_value", where + " (discriminator_value)");
                Set<String> envelopeMembers = new LinkedHashSet<>(object(shape, "members", "legacy shape " + shapeName).keySet());
                envelopeMembers.add(discriminatorMember);
                for (String member : envelopeMembers) {
                    if (!liftedMembers.contains(member) || isTypeLift(lifted, member)) {
                        dropped.add(member);
                    }
                }
            }
            case String other -> throw new ContractException("legacy shape " + shapeName + " has the unknown form " + other);
        }
        TypeEntry entry = new TypeEntry(matcher.group(1), Integer.parseInt(matcher.group(2)), scope, subject, lifts, dropped, Map.of());
        return new RegisteredType(
                type, dataschema, schemaVersion, entry, shapeKind, discriminatorMember, discriminatorValue, payloadMember, constants, actorRule, aliases);
    }

    private static boolean isTypeLift(List<?> lifted, String member) {
        for (Object item : lifted) {
            if (item instanceof Map<?, ?> lift && member.equals(lift.get("member")) && "type".equals(lift.get("attribute"))) {
                return true;
            }
        }
        return false;
    }

    private static ActorKind kindOf(String projection, String where) {
        return switch (projection) {
            case "actor_identity" -> ActorKind.ACTOR_IDENTITY;
            case "retrospective_actor_id" -> ActorKind.RETROSPECTIVE_ACTOR_ID;
            default -> throw new ContractException(where + " has the unknown actor projection " + projection);
        };
    }

    private static String string(Map<String, Object> map, String key, String where) {
        if (map.get(key) instanceof String value && !value.isEmpty()) {
            return value;
        }
        throw new ContractException(where + " has no " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key, String where) {
        if (map.get(key) instanceof Map<?, ?> value) {
            return (Map<String, Object>) value;
        }
        throw new ContractException(where + " has no " + key + " object");
    }

    public List<RegisteredType> types() {
        return types;
    }

    public ListedCatalog catalog() {
        return catalog;
    }

    /** The composed row schema (the whole CloudEvents line) of the registered type with this {@code type} string. */
    public String rowSchema(String type) {
        String schema = rowSchemas.get(type);
        if (schema == null) {
            throw new IllegalArgumentException("no registered type " + type);
        }
        return schema;
    }

    /** The registry's own {@code schemaversion}. */
    public String schemaVersion() {
        return schemaVersion;
    }
}
