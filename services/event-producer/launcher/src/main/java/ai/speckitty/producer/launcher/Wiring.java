package ai.speckitty.producer.launcher;

import ai.speckitty.producer.adapter.clock.FileClockStore;
import ai.speckitty.producer.adapter.identity.SystemNodeIdentity;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.adapter.schema.ContractException;
import ai.speckitty.producer.adapter.schema.ProducerContract;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.FailClosedValidator;
import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.application.produce.TypeCatalog;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The composition root: it builds {@link Produce} from the real file-system adapters.
 *
 * <p>{@link #production()} binds the schema adapter over the registry the build embedded from the released spec-kitty-events
 * artifact. The fail-closed fallback, never an accept-all, is chosen only when no registry is embedded or the embedded one cannot be
 * used: a binary that validates nothing must not look green. The fallback has its own entry point, {@link #failClosedFallback()},
 * so tests reach it without going through {@code production()}.
 */
public final class Wiring {

    private Wiring() {}

    /** The use case of the shipped binary: the released schema adapter, or the fallback when none is embedded. */
    public static Produce production() {
        return from(ProducerContract::embedded);
    }

    /** The fail-closed fallback: no registry and a validator that refuses every row, naming {@code validator-unavailable}. */
    public static Produce failClosedFallback() {
        return assemble(new FailClosedValidator(), TypeCatalog.UNAVAILABLE);
    }

    /** The use case over this contract binding: the fail-closed fallback when none is embedded or it cannot be used. For tests. */
    public static Produce from(Supplier<Optional<ProducerContract.Binding>> contract) {
        try {
            return contract.get().map(binding -> assemble(binding.validator(), binding.catalog())).orElseGet(Wiring::failClosedFallback);
        } catch (ContractException e) {
            return failClosedFallback();
        }
    }

    /** A use case over the real adapters with this validator and registry. For tests: the shipped binary never calls it. */
    public static Produce withValidator(RowSchemaValidator validator, TypeCatalog catalog) {
        return assemble(validator, catalog);
    }

    private static Produce assemble(RowSchemaValidator validator, TypeCatalog catalog) {
        return new Produce(
                new GitRepoLocator(),
                new SystemNodeIdentity(),
                new FileClockStore(new FilePublishLog(), FaultInjector.NONE),
                validator,
                catalog,
                new FileStartContext(),
                Json::render);
    }
}
