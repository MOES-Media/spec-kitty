package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Fixture.EVENT;
import static ai.speckitty.producer.application.produce.Fixture.MISSION;
import static ai.speckitty.producer.application.produce.Fixture.OTHER_MISSION;
import static ai.speckitty.producer.application.produce.Fixture.PROJECT;
import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** OD-MISSIONID (b): the Mission id comes from the row, then meta.json; otherwise the project source, no id, no fault. */
class MissionIdentityTest {

    private static final String PROJECT_SOURCE = "/spec-kitty/projects/" + PROJECT;
    private static final String OTHER_PROJECT = "7d1e1a52-0d8b-4a63-b2b4-1f0c5d2f3a90";

    /** A journal row whose payload leaves mission_id empty, as the engine persists it. */
    private static Map<String, Object> journalRow(Object payloadMissionId) {
        return obj("event_id", EVENT, "row_version", Rows.num("1"), "event_type", "MissionRunStarted", "timestamp", Fixture.AT,
                "payload", obj("run_id", "r1", "mission_id", payloadMissionId));
    }

    private static Map<String, Object> laneWithout(String... members) {
        Map<String, Object> row = Fixture.laneRow();
        for (String member : members) {
            row.remove(member);
        }
        return row;
    }

    private static void assertProjectScoped(Fixture f, ProduceReply reply) {
        assertThat(reply.outcome()).as("an unresolved Mission is no refusal and no fault").isEqualTo(Outcome.PRODUCED);
        assertThat(reply.reason()).isNull();
        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("source", PROJECT_SOURCE).doesNotContainKey("missionid");
        assertThat(f.store.requests.get(0).target().kind()).isEqualTo(PublishLog.Target.Kind.PROJECT);
        assertThat(f.store.requests.get(0).target().id()).isEqualTo(PROJECT);
        assertThat(f.store.requests.get(0).source()).isEqualTo(PROJECT_SOURCE);
    }

    @Test
    void aNonEmptyUlidMissionIdOfTheRowWinsOverMetaJson() {
        Fixture f = new Fixture();
        f.meta = Optional.of(obj("mission_id", OTHER_MISSION));
        f.run(Fixture.laneRow());
        assertThat(f.onlyDocument()).containsEntry("missionid", MISSION).containsEntry("source", "/spec-kitty/missions/" + MISSION);
    }

    @Test
    void anEmptyPayloadMissionIdWithMetaJsonGivesThatMissionId() {
        Fixture f = new Fixture();
        f.meta = Optional.of(obj("mission_id", OTHER_MISSION, "mission_slug", "from-meta"));
        ProduceReply reply = f.run(journalRow(""));
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(f.onlyDocument()).containsEntry("missionid", OTHER_MISSION).containsEntry("source", "/spec-kitty/missions/" + OTHER_MISSION)
                .containsEntry("missionslug", "from-meta");
        assertThat(f.store.requests.get(0).target().id()).isEqualTo(OTHER_MISSION);
        assertThat(f.metaReads).containsExactly(Path.of(Fixture.START));
    }

    @Test
    void anAbsentOrNullPayloadMissionIdFallsThroughToMetaJsonToo() {
        for (Object missing : new Object[] {null}) {
            Fixture f = new Fixture();
            f.meta = Optional.of(obj("mission_id", OTHER_MISSION));
            f.run(journalRow(missing));
            assertThat(f.onlyDocument()).containsEntry("missionid", OTHER_MISSION);
        }
        Fixture g = new Fixture();
        g.meta = Optional.of(obj("mission_id", OTHER_MISSION));
        g.run(obj("event_id", EVENT, "event_type", "MissionRunStarted", "timestamp", Fixture.AT, "payload", obj("run_id", "r1")));
        assertThat(g.onlyDocument()).containsEntry("missionid", OTHER_MISSION);
    }

    @Test
    void noMetaJsonGivesTheProjectScopedSourceNoMissionIdAndNoFault() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        ProduceReply reply = f.run(journalRow(""));
        assertProjectScoped(f, reply);
        assertThat(f.onlyDocument()).doesNotContainKey("subject");
    }

    @Test
    void aSlugOnlyPayloadStillGetsTheProjectScopedSourceAndIsNeverGivenTheSlugAsMissionId() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        ProduceReply reply = f.run(journalRow("041-reducer-unification"));
        assertProjectScoped(f, reply);
        assertThat(f.onlyDocument().toString()).as("the slug is not presented as a mission id").doesNotContain("missionid=");
    }

    @Test
    void aSlugOnlyMetaJsonIsNeverUsedAsMissionId() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        f.meta = Optional.of(obj("mission_id", "041-reducer-unification", "mission_slug", "041-reducer-unification"));
        assertProjectScoped(f, f.run(journalRow("")));
    }

    @Test
    void anEmptyStringMissionIdIsNeverEmittedAnywhere() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        f.meta = Optional.of(obj("mission_id", ""));
        Map<String, Object> row = Fixture.laneRow();
        row.put("mission_id", "");
        ProduceReply reply = f.run(row);
        assertProjectScoped(f, reply);
        assertThat(f.rendered.get(0).values()).doesNotContain("");
        assertThat(f.validated.get(0)).doesNotContainKey("missionid");
    }

    @Test
    void aLowerCaseRowMissionIdIsWrittenUpperCaseAndAMalformedOneIsIgnored() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("mission_id", MISSION.toLowerCase());
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("missionid", MISSION);

        Fixture g = new Fixture();
        g.configProjectUuid = Optional.of(PROJECT);
        Map<String, Object> bad = Fixture.laneRow();
        bad.put("mission_id", 12345);
        assertProjectScoped(g, g.run(bad));
    }

    @Test
    void theLaneRowWithNoMissionIdKeepsItsWorkPackageAsSubject() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        ProduceReply reply = f.run(laneWithout("mission_id"));
        assertProjectScoped(f, reply);
        assertThat(f.onlyDocument()).containsEntry("subject", "WP01").containsEntry("missionslug", "041-reducer-unification");
    }

    @Test
    void aMissionLevelRowWithNoMissionIdTakesTheAggregateAsSubject() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.missionCreatedRow();
        row.put("payload", obj("mission_slug", "041-reducer-unification", "mission_id", ""));
        ProduceReply reply = f.run(row);
        assertProjectScoped(f, reply);
        assertThat(f.onlyDocument()).containsEntry("subject", "041-reducer-unification");
    }

    @Test
    void theAggregateFallsBackToTheMissionSlugWhenTheRowHasNoAggregateId() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        Map<String, Object> row = obj("event_id", EVENT, "at", Fixture.AT, "event_type", "DecisionPointOpened",
                "payload", obj("decision_id", "d1", "mission_slug", "the-slug"));
        assertProjectScoped(f, f.run(row));
        assertThat(f.onlyDocument()).containsEntry("subject", "the-slug").containsEntry("missionslug", "the-slug");
    }

    @Test
    void withNoAggregateAtAllThereIsNoSubjectAndNothingIsMadeUp() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        assertProjectScoped(f, f.run(journalRow("")));
        assertThat(f.onlyDocument()).doesNotContainKey("subject");
    }

    @Test
    void theProjectUuidComesFromTheRowThenThePayloadThenTheProjectConfig() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(OTHER_PROJECT);
        Map<String, Object> row = Fixture.missionCreatedRow();
        row.put("payload", obj("mission_id", ""));
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("source", PROJECT_SOURCE);

        Fixture g = new Fixture();
        g.configProjectUuid = Optional.of(OTHER_PROJECT);
        Map<String, Object> noTop = Fixture.missionCreatedRow();
        noTop.remove("project_uuid");
        noTop.put("payload", obj("mission_id", "", "project_uuid", PROJECT));
        g.run(noTop);
        assertThat(g.onlyDocument()).containsEntry("source", PROJECT_SOURCE);

        Fixture h = new Fixture();
        h.configProjectUuid = Optional.of(OTHER_PROJECT);
        Map<String, Object> none = Fixture.missionCreatedRow();
        none.remove("project_uuid");
        none.put("payload", obj("mission_id", ""));
        h.run(none);
        assertThat(h.onlyDocument()).containsEntry("source", "/spec-kitty/projects/" + OTHER_PROJECT);
        assertThat(h.store.requests.get(0).target().id()).isEqualTo(OTHER_PROJECT);
    }

    @Test
    void aNullProjectUuidInTheRowFallsThroughRatherThanBeingUsed() {
        Fixture f = new Fixture();
        f.configProjectUuid = Optional.of(PROJECT);
        Map<String, Object> row = Fixture.missionCreatedRow();
        row.put("project_uuid", null);
        row.put("payload", obj("mission_id", ""));
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("source", PROJECT_SOURCE);
    }

    @Test
    void noMissionIdAndNoProjectUuidAnywhereIsAnAtomicRefusalWithItsOwnReason() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.missionCreatedRow();
        row.remove("project_uuid");
        row.put("payload", obj("mission_id", ""));
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.PROJECT_UNRESOLVED);
        assertThat(f.store.requests).isEmpty();
    }

    @Test
    void aMalformedProjectUuidIsNotUsed() {
        for (Object bad : List.of("not-a-uuid", "5C0F6F0E3B1A4D8E9A550D7E3F2B9C11", 7)) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.missionCreatedRow();
            row.put("project_uuid", bad);
            row.put("payload", obj("mission_id", ""));
            ProduceReply reply = f.run(row);
            assertThat(reply.reason()).as(String.valueOf(bad)).isEqualTo(RefusalReason.PROJECT_UNRESOLVED);
        }
    }

    @Test
    void aProjectScopedTypeKeepsTheProjectSourceEvenWhenAMissionIdIsPresent() {
        Fixture f = new Fixture();
        Map<String, Object> row = obj("event_id", EVENT, "event_type", "ProjectInitialized", "aggregate_id", PROJECT, "aggregate_type", "Project",
                "schema_version", "1.0.0", "timestamp", Fixture.AT, "project_uuid", PROJECT,
                "payload", obj("project_uuid", PROJECT, "mission_id", MISSION));
        f.meta = Optional.of(obj("mission_id", OTHER_MISSION));
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("source", PROJECT_SOURCE).doesNotContainKeys("missionid", "subject");
        assertThat(f.store.requests.get(0).target().kind()).isEqualTo(PublishLog.Target.Kind.PROJECT);
    }

    @Test
    void metaJsonIsReadFromTheStartPathOnlyAndOnlyWhenTheRowHasNoMissionId() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());
        assertThat(f.metaReads).isEmpty();
        Fixture g = new Fixture();
        g.run(journalRow(""));
        assertThat(g.metaReads).containsExactly(Path.of(Fixture.START));
    }

    @Test
    void theMissionSlugComesFromTheRowThenTheMissionPayloadThenMetaJson() {
        Fixture f = new Fixture();
        f.meta = Optional.of(obj("mission_id", MISSION, "mission_slug", "from-meta"));
        Map<String, Object> row = obj("event_id", EVENT, "event_type", "MissionRunStarted", "timestamp", Fixture.AT, "payload", obj("mission_slug", "from-payload"));
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("missionslug", "from-payload");

        Fixture g = new Fixture();
        g.meta = Optional.of(obj("mission_id", MISSION, "mission_slug", "from-meta"));
        g.run(journalRow(""));
        assertThat(g.onlyDocument()).containsEntry("missionslug", "from-meta");

        Fixture h = new Fixture();
        h.meta = Optional.of(obj("mission_id", MISSION, "mission_slug", ""));
        h.run(journalRow(""));
        assertThat(h.onlyDocument()).doesNotContainKey("missionslug");
    }

    @Test
    void theMissionFileIsKeyedByTheMissionIdNotTheSlug() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());
        assertThat(f.store.requests.get(0).target().relativePath().value()).isEqualTo(".kittify/events/publish/missions/" + MISSION + ".jsonl");
    }
}
