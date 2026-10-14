package ai.speckitty.producer.application.produce;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.envelope.TypeEntry;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The registry's {@code type}, {@code dataschema} and {@code schemaversion} are written as the registry states them. The entries
 * here are test-local and deliberately diverge from anything that could be derived from a prefix, a name and a version, so a
 * producer that rebuilt or hard-coded any of the three would fail.
 */
class ProduceRegistryPassThroughTest {

    private static final String ODD_TYPE = "ai.spec-kitty.oddkind.thing.v7";
    private static final String ODD_SCHEMA = "https://registry.example.invalid/odd/path/lane-2/schema.json";
    private static final String ODD_VERSION = "1.3.0";

    private static RegisteredType oddLane(String type, String dataschema, String schemaVersion) {
        return new RegisteredType(
                type,
                dataschema,
                schemaVersion,
                new TypeEntry("lane.transitioned", 1, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.WORK_PACKAGE,
                        List.of(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("at", "time"), new TypeEntry.Lift("mission_slug", "missionslug"), new TypeEntry.Lift("mission_id", "missionid"), new TypeEntry.Lift("wp_id", "subject"), new TypeEntry.Lift("actor", "actor"), new TypeEntry.Lift("execution_mode", "executionmode")),
                        List.of(),
                        Map.of()),
                Shape.FLAT, null, null, null,
                Map.of(),
                new ActorRule("actor", ActorKind.ACTOR_IDENTITY, "actorbinding"));
    }

    private static Fixture fixtureWith(RegisteredType type) {
        Fixture f = new Fixture();
        f.catalog = row -> new TypeCatalog.Found(type);
        return f;
    }

    @Test
    void theWrittenEnvelopeCarriesTheRegistrysDivergentTypeSchemaAndVersion() {
        Fixture f = fixtureWith(oddLane(ODD_TYPE, ODD_SCHEMA, ODD_VERSION));
        ProduceReply reply = f.run(Fixture.laneRow());

        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(reply.type()).isEqualTo(ODD_TYPE);
        Map<String, Object> written = f.onlyDocument();
        assertThat(written).containsEntry("type", ODD_TYPE);
        assertThat(written).containsEntry("dataschema", ODD_SCHEMA);
        assertThat(written).containsEntry("schemaversion", ODD_VERSION);
    }

    @Test
    void theValidatorSeesTheSameRegistryValuesThatAreWritten() {
        Fixture f = fixtureWith(oddLane(ODD_TYPE, ODD_SCHEMA, ODD_VERSION));
        f.run(Fixture.laneRow());

        assertThat(f.validated).hasSize(1);
        assertThat(f.validated.get(0))
                .containsEntry("type", ODD_TYPE)
                .containsEntry("dataschema", ODD_SCHEMA)
                .containsEntry("schemaversion", ODD_VERSION);
    }

    @Test
    void aSecondRegistryValueSetIsCarriedTooSoNoSingleValueIsBakedIn() {
        Fixture f = fixtureWith(oddLane("ai.spec-kitty.other.v2", "https://other.example.invalid/s/9", "0.9.12"));
        f.run(Fixture.laneRow());

        assertThat(f.onlyDocument())
                .containsEntry("type", "ai.spec-kitty.other.v2")
                .containsEntry("dataschema", "https://other.example.invalid/s/9")
                .containsEntry("schemaversion", "0.9.12");
    }
}
