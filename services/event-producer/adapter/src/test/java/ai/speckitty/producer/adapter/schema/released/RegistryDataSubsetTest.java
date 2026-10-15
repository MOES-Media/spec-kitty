package ai.speckitty.producer.adapter.schema.released;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.schema.ContractRegistry;
import ai.speckitty.producer.adapter.schema.RegistryValidator;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.RegisteredType;
import ai.speckitty.producer.application.produce.Shape;
import ai.speckitty.producer.application.produce.TypeCatalog;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * AC-029 (FR-005): nothing but the row travels in {@code data}. Over every type of data-model section 3, driven by the registry, the
 * {@code data} key set is a subset of the persisted row's keys (for a lifecycle row, its {@code payload}) plus the one lifted-member
 * rule: {@code actorbinding}, only for a structured actor. A {@code meta.json} or environment value injected into {@code data} fails.
 */
class RegistryDataSubsetTest {

    /** The members {@code data} holds beyond the persisted row's own: only a structured actor's binding. */
    static Set<String> excess(Map<String, Object> row, RegisteredType type, Map<String, Object> data) {
        Map<?, ?> persisted = type.shape() == Shape.PAYLOAD_ENVELOPE ? (Map<?, ?>) row.get(type.payloadMember()) : row;
        Set<String> allowed = new LinkedHashSet<>();
        persisted.keySet().forEach(k -> allowed.add((String) k));
        if (type.actorRule() != null && row.get(type.actorRule().member()) instanceof Map<?, ?>) {
            allowed.add(type.actorRule().bindingMember());
        }
        Set<String> excess = new TreeSet<>(data.keySet());
        excess.removeAll(allowed);
        return excess;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> document) {
        return (Map<String, Object>) document.get("data");
    }

    private static RegisteredType typeOf(ContractRegistry registry, String type) {
        return registry.types().stream().filter(t -> t.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void forEveryTypeOfSectionThreeTheDataKeysAreASubsetOfThePersistedRowPlusTheActorBinding() {
        ContractRegistry registry = Released.registry();
        Set<String> resolved = new LinkedHashSet<>();
        int rows = 0;
        for (Released.Fixture fixture : Released.fixtures("valid")) {
            ProduceHarness harness = new ProduceHarness(registry.catalog(), new RegistryValidator(registry), ProduceHarness.metaOf(fixture.input()));
            ProduceReply reply = harness.produce(fixture.legacyRow());
            assertThat(reply.outcome()).as(fixture.name()).isEqualTo(Outcome.PRODUCED);
            RegisteredType type = typeOf(registry, fixture.type());
            assertThat(registry.catalog().find(fixture.legacyRow())).as(fixture.name()).isInstanceOfSatisfying(TypeCatalog.Found.class, f -> assertThat(f.type()).isEqualTo(type));

            assertThat(excess(fixture.legacyRow(), type, dataOf(harness.documents.get(0)))).as(fixture.name()).isEmpty();
            resolved.add(fixture.type());
            rows++;
        }
        assertThat(resolved).as("floor: every data-model section 3 type resolved").containsExactlyInAnyOrderElementsOf(Released.SECTION_3_TYPES);
        assertThat(rows).isGreaterThanOrEqualTo(31);
    }

    @Test
    void actorbindingIsPresentOnlyForAStructuredActor() {
        ContractRegistry registry = Released.registry();
        int structured = 0;
        int string = 0;
        for (Released.Fixture fixture : Released.fixtures("valid")) {
            RegisteredType type = typeOf(registry, fixture.type());
            if (type.actorRule() == null) {
                continue;
            }
            ProduceHarness harness = new ProduceHarness(registry.catalog(), new RegistryValidator(registry), ProduceHarness.metaOf(fixture.input()));
            harness.produce(fixture.legacyRow());
            Map<String, Object> data = dataOf(harness.documents.get(0));
            Object actor = fixture.legacyRow().get(type.actorRule().member());
            if (actor instanceof Map<?, ?>) {
                assertThat(data).as(fixture.name()).containsEntry(type.actorRule().bindingMember(), actor);
                structured++;
            } else {
                assertThat(data).as(fixture.name()).doesNotContainKey(type.actorRule().bindingMember());
                string++;
            }
        }
        assertThat(structured).as("floor: structured actors seen").isGreaterThanOrEqualTo(3);
        assertThat(string).as("floor: string actors seen").isGreaterThanOrEqualTo(2);
    }

    /** A copy of a row: a payload envelope's payload is the very map {@code data} is built on, so an injection must not reach the original. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyOf(Map<String, Object> row) {
        return ((ParseResult.Accepted<Map<String, Object>>) Json.parseObject(Json.render(row))).value();
    }

    /** A validator that accepts anything and injects a value that does not come from the row: what a leaking writer would produce. */
    private static RowSchemaValidator injecting(String member, Object value) {
        return document -> {
            dataOf(document).put(member, value);
            return Optional.empty();
        };
    }

    @Test
    void anInjectedMetaJsonOrEnvironmentValueIsCaught() {
        ContractRegistry registry = Released.registry();
        for (Map.Entry<String, Object> injected : List.<Map.Entry<String, Object>>of(
                Map.entry("meta_json_mission_slug", "041-reducer-unification"), Map.entry("environment_home", "/somewhere"))) {
            for (Released.Fixture fixture : Released.fixtures("valid")) {
                ProduceHarness harness = new ProduceHarness(registry.catalog(), injecting(injected.getKey(), injected.getValue()), ProduceHarness.metaOf(fixture.input()));
                harness.produce(copyOf(fixture.legacyRow()));
                RegisteredType type = typeOf(registry, fixture.type());

                assertThat(excess(fixture.legacyRow(), type, dataOf(harness.documents.get(0)))).as(fixture.name()).containsExactly(injected.getKey());
                Map<String, Object> written = new LinkedHashMap<>(harness.documents.get(0));
                assertThat(new RegistryValidator(registry).violation(written)).as("the closed data schema refuses it too: " + fixture.name()).isPresent();
            }
        }
    }
}
