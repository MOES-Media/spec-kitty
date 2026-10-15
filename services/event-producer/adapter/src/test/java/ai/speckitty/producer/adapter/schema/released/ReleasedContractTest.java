package ai.speckitty.producer.adapter.schema.released;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.schema.ContractException;
import ai.speckitty.producer.adapter.schema.ContractRegistry;
import ai.speckitty.producer.adapter.schema.ContractSource;
import ai.speckitty.producer.adapter.schema.ProducerContract;
import ai.speckitty.producer.adapter.schema.RegistryValidator;
import ai.speckitty.producer.application.produce.RegisteredType;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The schema adapter against the pinned, released spec-kitty-events wheel (AC-002, AC-020 producer half, FR-006, FR-015, FR-017):
 * per in-scope type a valid and an invalid line, the legacy row form through the real mapping, and the refusals that must never
 * write. The wheel is extracted by {@code fetchEventsContract}; nothing of it is committed.
 */
class ReleasedContractTest {

    private static final RegistryValidator VALIDATOR = new RegistryValidator(Released.registry());

    /** Persisted rows the producer accepts although the legacy-form schema refuses them: each is a constraint on the persisted file. */
    private static final Map<String, String> LEGACY_ONLY = Map.of(
            "journal_missing_row_version.json",
            "row_version is the write-time stamp of the persisted run journal (FR-035); it is dropped, never published, so the CloudEvents line has nothing to check");

    // ----- the registry ---------------------------------------------------------------------------------------------------

    @Test
    void theReleasedRegistryHoldsExactlyTheTypesOfDataModelSectionThree() {
        List<String> types = Released.registry().types().stream().map(RegisteredType::type).toList();

        assertThat(types).containsExactlyInAnyOrderElementsOf(Released.SECTION_3_TYPES);
        assertThat(Released.SECTION_3_TYPES).hasSize(27).doesNotHaveDuplicates();
    }

    @Test
    void theContractTheBinaryEmbedsIsTheContractThatWasFetched() throws IOException {
        Optional<ProducerContract.Binding> embedded = ProducerContract.embedded();

        assertThat(embedded).as("a contract embedded in the jar: the build wires the fetch").isPresent();
        assertThat(embedded.orElseThrow().typeCount()).isEqualTo(27);
        String fetched = Files.readString(Released.dir().resolve("registry.json"));
        assertThat(ContractSource.classpath(ReleasedContractTest.class.getClassLoader()).read("registry.json")).contains(fetched);
        for (RegisteredType type : Released.registry().types()) {
            String name = type.type().substring("ai.spec-kitty.".length());
            assertThat(ContractSource.classpath(ReleasedContractTest.class.getClassLoader()).read("composed/rows/" + name + ".row.schema.json"))
                    .as("embedded row schema of " + name)
                    .contains(Files.readString(Released.dir().resolve("composed/rows/" + name + ".row.schema.json")));
        }
    }

    // ----- a valid and an invalid line per type ------------------------------------------------------------------------------

    @Test
    void everyTypeHasAValidLineTheValidatorAcceptsAndAnInvalidLineItRejects() {
        Set<String> iterated = new LinkedHashSet<>();
        for (RegisteredType type : Released.registry().types()) {
            List<Released.Fixture> valid = Released.fixtures("valid").stream().filter(f -> type.type().equals(f.type())).toList();
            List<Released.Fixture> invalid = Released.fixtures("invalid").stream().filter(f -> type.type().equals(f.type()) && f.name().startsWith(type.type().substring(14))).toList();
            assertThat(valid).as("valid fixtures of " + type.type()).isNotEmpty();
            assertThat(invalid).as("invalid fixtures of " + type.type()).isNotEmpty();
            valid.forEach(f -> assertThat(VALIDATOR.violation(f.input())).as(f.name()).isEmpty());
            invalid.forEach(f -> assertThat(VALIDATOR.violation(f.input())).as(f.name()).isPresent());
            iterated.add(type.type());
        }
        assertThat(iterated).as("the per-type loop iterated every data-model section 3 type").containsExactlyInAnyOrderElementsOf(Released.SECTION_3_TYPES);
    }

    @Test
    void everyInvalidFixtureIsRejectedAtTheLocationAndByTheKeywordItNames() {
        List<Released.Fixture> invalid = Released.fixtures("invalid");
        assertThat(invalid.size()).as("floor: the released invalid fixtures").isGreaterThanOrEqualTo(79);
        for (Released.Fixture fixture : invalid) {
            Optional<String> violation = VALIDATOR.violation(fixture.input());
            assertThat(violation).as(fixture.name() + " is rejected").isPresent();
            String path = (String) fixture.content().get("expected_error_path");
            String keyword = (String) fixture.content().get("expected_keyword");
            assertThat(explains(violation.orElseThrow(), path, keyword)).as(fixture.name() + ": " + violation.get() + " names " + path + " " + keyword).isTrue();
        }
    }

    /** The violation names the fixture's location and keyword; an {@code anyOf} is reported by its branches, an unknown type by no schema. */
    private static boolean explains(String violation, String path, String keyword) {
        String pointer = path.isEmpty() ? "/" : path;
        List<String> lines = List.of(violation.split("; "));
        if (lines.contains(pointer + " " + keyword)) {
            return true;
        }
        if (("anyOf".equals(keyword) || "oneOf".equals(keyword)) && lines.stream().anyMatch(l -> l.startsWith(pointer + " "))) {
            return true;
        }
        return "/type".equals(pointer) && "const".equals(keyword) && violation.equals("/type no-schema");
    }

    // ----- the legacy row form, through the real mapping -------------------------------------------------------------------

    @Test
    void everyValidLegacyRowIsMappedToExactlyTheReleasedReferenceLine() {
        List<Released.Fixture> valid = Released.fixtures("valid");
        int checked = 0;
        Set<String> types = new LinkedHashSet<>();
        for (Released.Fixture fixture : valid) {
            ContractRegistry registry = Released.registry();
            ProduceHarness harness = new ProduceHarness(registry.catalog(), new RegistryValidator(registry), ProduceHarness.metaOf(fixture.input()));
            ProduceReply reply = harness.produce(fixture.legacyRow());

            assertThat(reply.outcome()).as(fixture.name() + " " + reply.detail()).isEqualTo(Outcome.PRODUCED);
            assertThat(harness.lines).as(fixture.name()).hasSize(1);
            Map<String, Object> expected = new LinkedHashMap<>(fixture.input());
            Map<String, Object> actual = new LinkedHashMap<>(harness.documents.get(0));
            for (Map<String, Object> side : List.of(expected, actual)) {
                side.remove("lamportclock");
                side.remove("nodeid");
            }
            assertThat(Json.render(actual)).as(fixture.name() + " equals the reference line").isEqualTo(Json.render(expected));
            types.add(fixture.type());
            checked++;
        }
        assertThat(checked).as("floor: the released valid fixtures").isGreaterThanOrEqualTo(31);
        assertThat(types).as("every section 3 type has a legacy-form variant").containsExactlyInAnyOrderElementsOf(Released.SECTION_3_TYPES);
    }

    @Test
    void everyInvalidLegacyRowIsRefusedWithANamedReasonAndNeverWritten() {
        List<Released.Fixture> legacyInvalid = Released.fixtures("legacy_invalid");
        assertThat(legacyInvalid.size()).as("floor: the released invalid legacy fixtures").isGreaterThanOrEqualTo(34);
        Set<String> accepted = new LinkedHashSet<>();
        for (Released.Fixture fixture : legacyInvalid) {
            ContractRegistry registry = Released.registry();
            ProduceHarness harness = new ProduceHarness(registry.catalog(), new RegistryValidator(registry));
            ProduceReply reply = harness.produce(fixture.input());
            if (reply.outcome() == Outcome.PRODUCED) {
                accepted.add(fixture.name());
                continue;
            }
            assertThat(reply.outcome()).as(fixture.name()).isEqualTo(Outcome.REFUSED);
            assertThat(reply.reason()).as(fixture.name()).isIn(RefusalReason.SCHEMA_INVALID, RefusalReason.TYPE_UNKNOWN);
            assertThat(reply.detail()).as(fixture.name()).isNotBlank();
            assertThat(harness.lines).as(fixture.name() + " is never written").isEmpty();
        }
        assertThat(accepted).as("the only legacy-invalid rows the producer accepts, each with its reason").containsExactlyInAnyOrderElementsOf(LEGACY_ONLY.keySet());
    }

    @Test
    void aRowOfATypeTheRegistryDoesNotHoldIsRefusedByNameAndNeverWritten() {
        Released.Fixture created = Released.fixtures("valid").stream().filter(f -> f.name().equals("mission.created.v1.json")).findFirst().orElseThrow();
        Map<String, Object> row = new LinkedHashMap<>(created.legacyRow());
        row.put("event_type", "SomethingTheRegistryNeverHeard");
        ContractRegistry registry = Released.registry();
        ProduceHarness harness = new ProduceHarness(registry.catalog(), new RegistryValidator(registry));

        ProduceReply reply = harness.produce(row);

        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.TYPE_UNKNOWN);
        assertThat(harness.lines).isEmpty();
    }

    @Test
    void anUnknownScopeInTheRegistryIsRefusedAndNoValidatorIsBuilt() throws IOException {
        String registry = Files.readString(Released.dir().resolve("registry.json"));
        assertThat(registry).contains("\"scope\": \"mission\"");
        ContractSource galaxy = path -> path.equals("registry.json")
                ? Optional.of(registry.replaceFirst("\"scope\": \"mission\"", "\"scope\": \"galaxy\""))
                : Released.source().read(path);

        assertThatThrownBy(() -> ContractRegistry.load(galaxy)).isInstanceOf(ContractException.class).hasMessageContaining("galaxy");
    }

    @Test
    void aWheelWhoseRegistryIsEmptyNeverYieldsAValidatorThatAcceptsEverything() {
        ContractSource empty = path -> path.equals("registry.json")
                ? Optional.of("{\"schemaversion\":\"4.0.0\",\"legacy_shapes\":{},\"types\":[]}")
                : Released.source().read(path);

        assertThatThrownBy(() -> ProducerContract.from(empty)).isInstanceOf(ContractException.class).hasMessageContaining("no types");
    }

    // ----- AC-020, the producer half -------------------------------------------------------------------------------------------

    private static Map<String, Object> laneRow(String from, String to) {
        Released.Fixture lane = Released.fixtures("valid").stream().filter(f -> f.name().equals("lane.transitioned.v1.json")).findFirst().orElseThrow();
        Map<String, Object> row = new LinkedHashMap<>(lane.legacyRow());
        row.put("from_lane", from);
        row.put("to_lane", to);
        return row;
    }

    @Test
    void doingIsPublishedAsInProgressAndUninitializedIsRefused() {
        ContractRegistry registry = Released.registry();
        ProduceHarness doing = new ProduceHarness(registry.catalog(), new RegistryValidator(registry), Optional.empty());
        ProduceReply published = doing.produce(laneRow("planned", "doing"));

        assertThat(published.outcome()).isEqualTo(Outcome.PRODUCED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) doing.documents.get(0).get("data");
        assertThat(data).containsEntry("from_lane", "planned").containsEntry("to_lane", "in_progress");

        for (Map<String, Object> row : List.of(laneRow("uninitialized", "claimed"), laneRow("planned", "uninitialized"))) {
            ProduceHarness refused = new ProduceHarness(registry.catalog(), new RegistryValidator(registry), Optional.empty());
            ProduceReply reply = refused.produce(row);
            assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
            assertThat(reply.reason()).isEqualTo(RefusalReason.SCHEMA_INVALID);
            assertThat(refused.lines).isEmpty();
        }
    }
}
