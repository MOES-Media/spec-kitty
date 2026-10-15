package ai.speckitty.producer.adapter.schema.released;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.NodeIdentity;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.application.produce.StartContext;
import ai.speckitty.producer.application.produce.TypeCatalog;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The producer use case over the released registry and validator with in-memory ports: the row goes through the real mapping and the
 * real schema, and what would have been appended is recorded. Nothing touches a file system.
 */
final class ProduceHarness {

    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";
    static final String NODE = "0123456789ab";

    private final TypeCatalog catalog;
    private final RowSchemaValidator validator;
    private final Optional<Map<String, Object>> meta;

    /** Every document the renderer was given, as the use case built it (clock stamped), in order. */
    final List<Map<String, Object>> documents = new ArrayList<>();

    /** Every line the fake store appended. */
    final List<String> lines = new ArrayList<>();

    ProduceHarness(TypeCatalog catalog, RowSchemaValidator validator) {
        this(catalog, validator, Optional.empty());
    }

    /** @param meta the {@code meta.json} of the start directory: where a row with no Mission id of its own (a journal row) finds it */
    ProduceHarness(TypeCatalog catalog, RowSchemaValidator validator, Optional<Map<String, Object>> meta) {
        this.catalog = catalog;
        this.validator = validator;
        this.meta = meta;
    }

    /** The {@code meta.json} a Mission directory holds for this document: its Mission id and slug. */
    static Optional<Map<String, Object>> metaOf(Map<String, Object> cloudEvent) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (cloudEvent.get("missionid") != null) {
            meta.put("mission_id", cloudEvent.get("missionid"));
        }
        if (cloudEvent.get("missionslug") != null) {
            meta.put("mission_slug", cloudEvent.get("missionslug"));
        }
        return meta.isEmpty() ? Optional.empty() : Optional.of(meta);
    }

    ProduceReply produce(Map<String, Object> row) {
        RepoLocator locator = start -> new ParseResult.Accepted<>(new RepoLayout(Path.of("/repo"), Path.of("/repo/.git")));
        NodeIdentity identity = () -> NODE;
        StartContext context = new StartContext() {
            @Override
            public Optional<Map<String, Object>> metaJson(Path startPath) {
                return meta;
            }

            @Override
            public Optional<String> projectUuid(RepoLayout layout) {
                return Optional.of(PROJECT);
            }
        };
        int[] counter = {0};
        ClockStore store = request -> {
            counter[0]++;
            lines.add(request.renderer().render(new LamportClock(counter[0])));
            return new ParseResult.Accepted<>(new ClockStore.Stamp(false, Optional.of(new LamportClock(counter[0])), Optional.empty()));
        };
        Produce produce = new Produce(locator, identity, store, validator, catalog, context, document -> {
            documents.add(new LinkedHashMap<>(document));
            return Json.render(document);
        });
        return produce.produce(new ProduceRequest(1, row, Slot.STATUS, new LinkedHashMap<>(), "work/missions/m1", 1500));
    }
}
