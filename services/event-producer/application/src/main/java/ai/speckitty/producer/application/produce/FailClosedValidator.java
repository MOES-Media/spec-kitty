package ai.speckitty.producer.application.produce;

import ai.speckitty.producer.application.port.RowSchemaValidator;
import java.util.Map;
import java.util.Optional;

/**
 * The validator bound until the released schema adapter is: it refuses every document, naming {@link
 * Produce#VALIDATOR_UNAVAILABLE_VIOLATION}. A producer that cannot validate must not look green by validating nothing.
 */
public final class FailClosedValidator implements RowSchemaValidator {

    @Override
    public Optional<String> violation(Map<String, Object> document) {
        return Optional.of(Produce.VALIDATOR_UNAVAILABLE_VIOLATION);
    }
}
