package ai.speckitty.producer.launcher;

import ai.speckitty.producer.adapter.clock.FileClockStore;
import ai.speckitty.producer.adapter.identity.SystemNodeIdentity;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.FailClosedValidator;
import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.application.produce.TypeCatalog;

/**
 * The composition root: it builds {@link Produce} from the real file-system adapters.
 *
 * <p>Until the released schema adapter is bound, {@link #production()} is the fail-closed fallback, never an accept-all: a binary
 * that validates nothing must not look green. The fallback has its own entry point, {@link #failClosedFallback()}, so tests reach
 * it without going through {@code production()}.
 */
public final class Wiring {

    private Wiring() {}

    /** The use case of the shipped binary. */
    public static Produce production() {
        return failClosedFallback();
    }

    /** The fail-closed fallback: no registry and a validator that refuses every row, naming {@code validator-unavailable}. */
    public static Produce failClosedFallback() {
        return assemble(new FailClosedValidator(), TypeCatalog.UNAVAILABLE);
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
