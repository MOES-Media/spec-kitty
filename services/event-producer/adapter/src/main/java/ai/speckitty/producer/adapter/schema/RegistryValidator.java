package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.RegisteredType;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Validates a built CloudEvents document against the composed row schema of its own registered type (the whole line: envelope
 * attributes, pinned {@code type} and {@code dataschema}, the scope, {@code subject} and {@code missionid} rules, and a closed {@code
 * data}). Java holds no per-type code: the schema is picked by the document's {@code type} from the registry.
 *
 * <p>A violation is one line of {@code <JSON pointer> <keyword>} entries, sorted, bounded, and free of the row's values: where the row
 * is wrong, never what it holds. A document of a type the registry does not hold is a violation, never valid.
 */
public final class RegistryValidator implements RowSchemaValidator {

    private static final int MAX_LISTED = 5;
    private static final int MAX_LENGTH = 400;
    private static final String SEPARATOR = "; ";

    private final Map<String, Schema> schemas = new LinkedHashMap<>();

    public RegistryValidator(ContractRegistry registry) {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build();
        SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
        for (RegisteredType type : registry.types()) {
            try {
                Schema schema = schemaRegistry.getSchema(registry.rowSchema(type.type()), InputFormat.JSON);
                schema.initializeValidators();
                schemas.put(type.type(), schema);
            } catch (RuntimeException e) {
                throw new ContractException("the row schema of " + type.type() + " cannot be compiled", e);
            }
        }
        if (schemas.isEmpty()) {
            throw new ContractException("no row schema: a validator built from nothing would accept everything");
        }
    }

    /** The number of row schemas this validator holds: never zero for a validator that decides anything. */
    public int schemaCount() {
        return schemas.size();
    }

    @Override
    public Optional<String> violation(Map<String, Object> document) {
        Schema schema = document.get("type") instanceof String type ? schemas.get(type) : null;
        if (schema == null) {
            return Optional.of("/type no-schema");
        }
        List<Error> errors = schema.validate(Json.render(document), InputFormat.JSON);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        TreeSet<String> lines = new TreeSet<>();
        for (Error error : errors) {
            String pointer = error.getInstanceLocation().toString();
            lines.add((pointer.isEmpty() ? "/" : pointer) + " " + error.getKeyword());
        }
        StringBuilder text = new StringBuilder();
        int listed = 0;
        for (String line : lines) {
            if (listed == MAX_LISTED || text.length() + line.length() > MAX_LENGTH) {
                break;
            }
            text.append(listed++ == 0 ? "" : SEPARATOR).append(line);
        }
        return Optional.of(text.toString());
    }
}
