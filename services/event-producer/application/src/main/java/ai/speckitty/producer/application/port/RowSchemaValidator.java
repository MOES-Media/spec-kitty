package ai.speckitty.producer.application.port;

import java.util.Map;
import java.util.Optional;

/** Checks a built envelope document against the schema of its type. */
public interface RowSchemaValidator {

    /**
     * Validates the document (context attributes and {@code data}) against the schema its {@code type} and {@code dataschema}
     * name. Empty when the document is valid, else one line naming the violation. An implementation that cannot decide fails
     * closed: it returns a violation, never "valid".
     */
    Optional<String> violation(Map<String, Object> document);
}
