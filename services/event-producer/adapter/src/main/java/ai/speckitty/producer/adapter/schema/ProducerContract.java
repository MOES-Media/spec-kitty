package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.TypeCatalog;
import java.util.Optional;

/**
 * The schema adapter as the composition root binds it: a catalog and a validator over one registry. The shipped binary reads the
 * contract the build embedded from the released spec-kitty-events artifact; a build that embedded none has no binding, and the
 * launcher then falls back to its fail-closed validator.
 */
public final class ProducerContract {

    /** @param typeCount the number of registered types behind both */
    public record Binding(TypeCatalog catalog, RowSchemaValidator validator, int typeCount) {}

    private ProducerContract() {}

    /** The binding over the contract embedded in this build; empty when none is embedded, a {@link ContractException} when it is unusable. */
    public static Optional<Binding> embedded() {
        ContractSource source = ContractSource.classpath(ProducerContract.class.getClassLoader());
        if (source.read(ContractPaths.REGISTRY).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(from(source));
    }

    /** The binding over this source. */
    public static Binding from(ContractSource source) {
        ContractRegistry registry = ContractRegistry.load(source);
        RegistryValidator validator = new RegistryValidator(registry);
        return new Binding(registry.catalog(), validator, registry.types().size());
    }
}
