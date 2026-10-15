package ai.speckitty.producer.adapter.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.application.produce.ActorKind;
import ai.speckitty.producer.application.produce.RegisteredType;
import ai.speckitty.producer.application.produce.Shape;
import ai.speckitty.producer.application.produce.TypeCatalog;
import ai.speckitty.producer.domain.envelope.TypeEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The registry as data: every field of a registered type comes from the registry file, and a contract that cannot be used is refused. */
class ContractRegistryTest {

    private static Map<String, Object> laneRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", "01J0000000000000000000F001");
        row.put("wp_id", "WP01");
        row.put("from_lane", "planned");
        row.put("to_lane", "claimed");
        row.put("at", "2026-01-01T00:00:00+00:00");
        row.put("actor", "claude");
        row.put("execution_mode", "worktree");
        return row;
    }

    private static RegisteredType type(ContractRegistry registry, String name) {
        return registry.types().stream().filter(t -> t.type().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyTypeCarriesTheRegistrysOwnStrings() {
        ContractRegistry registry = ContractRegistry.load(new MiniContract());

        assertThat(registry.types()).extracting(RegisteredType::type).containsExactly(MiniContract.LANE, MiniContract.CREATED);
        RegisteredType lane = type(registry, MiniContract.LANE);
        assertThat(lane.dataschema()).isEqualTo(MiniContract.BASE + "lane.transitioned.v1");
        assertThat(lane.schemaVersion()).isEqualTo("4.0.0");
        assertThat(registry.schemaVersion()).isEqualTo("4.0.0");
    }

    @Test
    void aFlatTypeIsMappedFromItsRegistryEntry() {
        RegisteredType lane = type(ContractRegistry.load(new MiniContract()), MiniContract.LANE);

        assertThat(lane.shape()).isEqualTo(Shape.FLAT);
        assertThat(lane.entry().scope()).isEqualTo(TypeEntry.Scope.MISSION);
        assertThat(lane.entry().subject()).isEqualTo(TypeEntry.SubjectRule.WORK_PACKAGE);
        assertThat(lane.entry().name()).isEqualTo("lane.transitioned");
        assertThat(lane.entry().version()).isEqualTo(1);
        assertThat(lane.entry().lifts()).contains(
                new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("at", "time"), new TypeEntry.Lift("wp_id", "subject"),
                new TypeEntry.Lift("execution_mode", "executionmode"));
        assertThat(lane.constants()).isEmpty();
        assertThat(lane.actorRule().member()).isEqualTo("actor");
        assertThat(lane.actorRule().kind()).isEqualTo(ActorKind.ACTOR_IDENTITY);
        assertThat(lane.actorRule().bindingMember()).isEqualTo("actorbinding");
    }

    @Test
    void aPayloadEnvelopeTypeKeepsItsDiscriminatorAndDropsTheEnvelopeMembersTheTypeCarries() {
        RegisteredType created = type(ContractRegistry.load(new MiniContract()), MiniContract.CREATED);

        assertThat(created.shape()).isEqualTo(Shape.PAYLOAD_ENVELOPE);
        assertThat(created.discriminatorMember()).isEqualTo("event_type");
        assertThat(created.discriminatorValue()).isEqualTo("MissionCreated");
        assertThat(created.payloadMember()).isEqualTo("payload");
        assertThat(created.entry().scope()).isEqualTo(TypeEntry.Scope.MISSION);
        assertThat(created.entry().subject()).isEqualTo(TypeEntry.SubjectRule.NONE);
        assertThat(created.entry().lifts()).containsExactly(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("timestamp", "time"));
        assertThat(created.entry().dropped()).containsExactlyInAnyOrder("event_type", "aggregate_id", "project_uuid");
        assertThat(created.actorRule()).isNull();
    }

    @Test
    void theLaneAliasIsSuppliedByTheAdapterBecauseTheReleasedRegistryHasNone() {
        RegisteredType lane = type(ContractRegistry.load(new MiniContract()), MiniContract.LANE);

        assertThat(lane.dataAliases()).containsEntry("from_lane", Map.of("doing", "in_progress")).containsEntry("to_lane", Map.of("doing", "in_progress"));
        assertThat(type(ContractRegistry.load(new MiniContract()), MiniContract.CREATED).dataAliases()).isEmpty();
    }

    @Test
    void theCatalogFindsTheTypeOfARowByItsData() {
        ContractRegistry registry = ContractRegistry.load(new MiniContract());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("event_id", "01J0000000000000000000F007");
        created.put("event_type", "MissionCreated");
        created.put("payload", Map.of());
        Map<String, Object> other = new LinkedHashMap<>(created);
        other.put("event_type", "SomethingNew");

        assertThat(registry.catalog().find(laneRow())).isInstanceOfSatisfying(TypeCatalog.Found.class, f -> assertThat(f.type().type()).isEqualTo(MiniContract.LANE));
        assertThat(registry.catalog().find(created)).isInstanceOfSatisfying(TypeCatalog.Found.class, f -> assertThat(f.type().type()).isEqualTo(MiniContract.CREATED));
        assertThat(registry.catalog().find(other)).isInstanceOf(TypeCatalog.Unknown.class);
    }

    @Test
    void theRowSchemaOfATypeIsTheTextOfItsFile() {
        MiniContract contract = new MiniContract();
        ContractRegistry registry = ContractRegistry.load(contract);

        assertThat(registry.rowSchema(MiniContract.LANE)).isEqualTo(contract.files.get("composed/rows/lane.transitioned.v1.row.schema.json"));
        assertThatThrownBy(() -> registry.rowSchema("ai.spec-kitty.nope.v1")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void refuses(Consumer<MiniContract> damage, String mentions) {
        MiniContract contract = new MiniContract();
        damage.accept(contract);
        assertThatThrownBy(() -> ContractRegistry.load(contract)).isInstanceOf(ContractException.class).hasMessageContaining(mentions);
    }

    @Test
    void aContractThatCannotBeUsedIsRefusedAndNeverBecomesAnEmptyRegistry() {
        refuses(c -> c.files.remove("registry.json"), "registry.json");
        refuses(c -> c.files.put("registry.json", "not json"), "registry.json");
        refuses(c -> c.files.put("registry.json", MiniContract.registry()), "no types");
        refuses(c -> c.files.put("registry.json", "{\"schemaversion\":\"4.0.0\",\"legacy_shapes\":{},\"types\":{}}"), "types");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType().replace("\"schema\":", "\"schemaX\":"), MiniContract.createdType())), "schema");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType(), MiniContract.laneType())), "duplicate");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType().replace("actor_identity", "mystery"), MiniContract.createdType())), "mystery");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType().replace("\"shape\":\"flat\"", "\"shape\":\"nowhere\""), MiniContract.createdType())), "nowhere");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType(), MiniContract.createdType().replace(",\"discriminator_value\":\"MissionCreated\"", ""))), "discriminator_value");
        refuses(c -> c.files.put("registry.json", MiniContract.registry(MiniContract.laneType().replace("\"scope\":\"mission\"", "\"scope\":\"galaxy\""), MiniContract.createdType())), "galaxy");
    }

    @Test
    void aTypeWithoutItsRowSchemaIsRefusedByName() {
        refuses(c -> c.files.remove("composed/rows/mission.created.v1.row.schema.json"), "mission.created.v1");
        refuses(c -> c.files.put("composed/rows/mission.created.v1.row.schema.json", "  "), "mission.created.v1");
        refuses(c -> c.files.put("composed/rows/mission.created.v1.row.schema.json", "not json"), "mission.created.v1");
    }

    @Test
    void anAliasTableNamingATypeTheRegistryNoLongerHoldsIsRefused() {
        MiniContract contract = new MiniContract();
        contract.files.put("registry.json", MiniContract.registry(MiniContract.createdType()));

        assertThatThrownBy(() -> ContractRegistry.load(contract)).isInstanceOf(ContractException.class).hasMessageContaining("lane.transitioned.v1");
        assertThat(ContractRegistry.load(contract, Map.of()).types()).hasSize(1);
    }

    @Test
    void anAliasTableCannotNameAMemberOfNoValue() {
        MiniContract contract = new MiniContract();
        Map<String, Map<String, Map<String, String>>> aliases = Map.of("lane.transitioned.v1", Map.of("to_lane", Map.of()));

        assertThatThrownBy(() -> ContractRegistry.load(contract, aliases)).isInstanceOf(ContractException.class).hasMessageContaining("to_lane");
    }
}
