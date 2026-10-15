package ai.speckitty.producer.launcher.binding;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.schema.ContractException;
import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import ai.speckitty.producer.domain.protocol.Slot;
import ai.speckitty.producer.launcher.Wiring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The composition root with the released schema adapter bound (FR-006, FR-015, AC-001, AC-002): {@code production()} validates with
 * the registry embedded from the released spec-kitty-events artifact, over the real file-system adapters. The fail-closed validator
 * stays as the explicit fallback, selected only when no usable registry is embedded.
 */
class SchemaBindingTest {

    private static ProduceReply run(Produce produce, Path root, Map<String, Object> row) {
        return produce.produce(new ProduceRequest(1, row, Slot.STATUS, new LinkedHashMap<>(), root.resolve("kitty-specs/m1").toString(), 20000));
    }

    private static Map<String, Object> laneRow() {
        return Repos.validFixtures().stream().filter(f -> f.name().equals("lane.transitioned.v1.json")).findFirst().orElseThrow().legacyRow();
    }

    @Test
    void anInvalidRowRepliesRejectedWithTheReleasedSchemasLocationAndWritesNothing(@TempDir Path dir) throws IOException {
        Path root = Repos.repo(dir, Map.of("mission_id", "01J0000000000000000000MSN1", "mission_slug", "041-reducer-unification"));
        Map<String, Object> row = new LinkedHashMap<>(laneRow());
        row.put("to_lane", "bogus");

        ProduceReply reply = run(Wiring.production(), root, row);

        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.SCHEMA_INVALID);
        assertThat(reply.reasonClass().wire()).isEqualTo("rejected");
        assertThat(reply.detail()).startsWith("/data/to_lane enum");
        assertThat(Files.exists(root.resolve(".kittify/events"))).isFalse();
    }

    @Test
    void aRowOfATypeTheReleasedRegistryDoesNotHoldIsRejectedByName(@TempDir Path dir) throws IOException {
        Path root = Repos.repo(dir, Map.of());
        Map<String, Object> row = new LinkedHashMap<>(Repos.validFixtures().stream().filter(f -> f.name().equals("mission.created.v1.json")).findFirst().orElseThrow().legacyRow());
        row.put("event_type", "SomethingNew");

        ProduceReply reply = run(Wiring.production(), root, row);

        assertThat(reply.reason()).isEqualTo(RefusalReason.TYPE_UNKNOWN);
        assertThat(Files.exists(root.resolve(".kittify/events"))).isFalse();
    }

    @Test
    void aValidRowOfEveryInScopeTypeIsAcceptedAndWrittenAsTheReleasedReferenceLine(@TempDir Path dir) throws IOException {
        Set<String> types = new LinkedHashSet<>();
        int index = 0;
        for (Repos.Fixture fixture : Repos.validFixtures()) {
            Path root = Repos.repo(Files.createDirectories(dir.resolve("r" + index++)), Repos.metaOf(fixture.input()));

            ProduceReply reply = run(Wiring.production(), root, fixture.legacyRow());

            assertThat(reply.outcome()).as(fixture.name() + " " + reply.detail()).isEqualTo(Outcome.PRODUCED);
            assertThat(reply.type()).isEqualTo(fixture.input().get("type"));
            Map<String, Object> written = Repos.parse(Files.readString(Repos.publishLog(root)).strip());
            assertThat(written.get("nodeid")).as(fixture.name()).asString().matches("[0-9a-f]{12}");
            assertThat(Json.render(written.get("lamportclock"))).isEqualTo("1");
            Map<String, Object> expected = new LinkedHashMap<>(fixture.input());
            for (Map<String, Object> side : java.util.List.of(expected, written)) {
                side.remove("lamportclock");
                side.remove("nodeid");
            }
            assertThat(Json.render(written)).as(fixture.name()).isEqualTo(Json.render(expected));
            types.add((String) fixture.input().get("type"));
        }
        assertThat(types).as("floor: every in-scope type of the registry").hasSize(27);
    }

    @Test
    void theFallbackIsSelectedOnlyWhenNoRegistryIsEmbeddedOrItCannotBeUsed(@TempDir Path dir) throws IOException {
        Path root = Repos.repo(dir, Map.of("mission_id", "01J0000000000000000000MSN1", "mission_slug", "041-reducer-unification"));

        ProduceReply none = run(Wiring.from(Optional::empty), root, laneRow());
        ProduceReply broken = run(Wiring.from(() -> {
            throw new ContractException("unusable");
        }), root, laneRow());

        for (ProduceReply reply : new ProduceReply[] {none, broken}) {
            assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
            assertThat(reply.reason()).isEqualTo(RefusalReason.VALIDATOR_UNAVAILABLE);
        }
        assertThat(Files.exists(root.resolve(".kittify/events"))).isFalse();
        assertThat(run(Wiring.production(), root, laneRow()).outcome()).as("production() is not the fallback").isEqualTo(Outcome.PRODUCED);
    }
}
