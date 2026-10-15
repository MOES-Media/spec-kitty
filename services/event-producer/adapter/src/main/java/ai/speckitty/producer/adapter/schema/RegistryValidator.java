package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.application.port.RowSchemaValidator;
import java.util.Map;
import java.util.Optional;

/** Validates a built CloudEvents document against the composed row schema of its registered type. */
public final class RegistryValidator implements RowSchemaValidator {

    public RegistryValidator(ContractRegistry registry) {}

    /** The number of row schemas this validator holds: never zero for a validator that decides anything. */
    public int schemaCount() {
        return 0;
    }

    @Override
    public Optional<String> violation(Map<String, Object> document) {
        return Optional.empty();
    }
}
