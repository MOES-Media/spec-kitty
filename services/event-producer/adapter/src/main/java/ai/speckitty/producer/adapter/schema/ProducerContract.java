package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.TypeCatalog;
import java.util.Optional;

/** The schema adapter as the composition root binds it: a catalog and a validator over one registry. */
public final class ProducerContract {

    /** @param typeCount the number of registered types behind both */
    public record Binding(TypeCatalog catalog, RowSchemaValidator validator, int typeCount) {}

    private ProducerContract() {}

    /** The binding over the contract embedded in this build; empty when none is embedded. */
    public static Optional<Binding> embedded() {
        return Optional.empty();
    }

    /** The binding over this source. */
    public static Binding from(ContractSource source) {
        return new Binding(TypeCatalog.UNAVAILABLE, document -> Optional.empty(), 0);
    }
}
