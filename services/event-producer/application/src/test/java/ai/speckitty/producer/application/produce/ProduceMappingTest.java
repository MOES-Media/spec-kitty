package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Fixture.AT;
import static ai.speckitty.producer.application.produce.Fixture.EVENT;
import static ai.speckitty.producer.application.produce.Fixture.MISSION;
import static ai.speckitty.producer.application.produce.Fixture.NODE;
import static ai.speckitty.producer.application.produce.Fixture.PROJECT;
import static ai.speckitty.producer.application.produce.Rows.num;
import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProduceMappingTest {

    private static final String LANE_TYPE = "ai.spec-kitty.lane.transitioned.v1";
    private static final String LANE_SCHEMA = "https://schemas.example.invalid/spec-kitty/lane.transitioned.v1";

    @Test
    void aLaneRowBecomesExactlyThisCloudEvent() {
        Fixture f = new Fixture();
        ProduceReply reply = f.run(Fixture.laneRow());

        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(reply.eventId()).isEqualTo(EVENT);
        assertThat(reply.type()).isEqualTo(LANE_TYPE);
        assertThat(reply.reason()).isNull();
        assertThat(f.onlyDocument()).isEqualTo(obj(
                "specversion", "1.0",
                "id", EVENT,
                "source", "/spec-kitty/missions/" + MISSION,
                "type", LANE_TYPE,
                "subject", "WP01",
                "time", AT,
                "datacontenttype", "application/json",
                "dataschema", LANE_SCHEMA,
                "schemaversion", "4.0.0",
                "missionid", MISSION,
                "missionslug", "041-reducer-unification",
                "actor", "claude",
                "executionmode", "worktree",
                "lamportclock", num("1"),
                "nodeid", NODE,
                "data", obj(
                        "from_lane", "planned",
                        "to_lane", "claimed",
                        "force", Boolean.FALSE,
                        "reason", null,
                        "review_ref", null,
                        "evidence", null,
                        "policy_metadata", obj("agent", "claude", "shell_pid", num("4242")))));
    }

    @Test
    void theClockStoreIsAskedForTheRightFileAndTheNormalisedIdentity() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());

        assertThat(f.store.requests).hasSize(1);
        var request = f.store.requests.get(0);
        assertThat(request.layout()).isEqualTo(Fixture.LAYOUT);
        assertThat(request.nodeId()).isEqualTo(NODE);
        assertThat(request.source()).isEqualTo("/spec-kitty/missions/" + MISSION);
        assertThat(request.id()).isEqualTo(EVENT);
        assertThat(request.target().kind()).isEqualTo(PublishLog.Target.Kind.MISSION);
        assertThat(request.target().id()).isEqualTo(MISSION);
    }

    @Test
    void theRootIsFoundFromTheStartPathAndNeverFromTheProcess() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());
        assertThat(f.locatedFrom).containsExactly(java.nio.file.Path.of(Fixture.START));
    }

    @Test
    void theValidatorSeesTheWholeDocumentBeforeAnythingIsWritten() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());

        assertThat(f.validated).hasSize(1);
        Map<String, Object> seen = f.validated.get(0);
        assertThat(seen).containsEntry("nodeid", NODE).containsEntry("lamportclock", num("0")).containsKey("data");
        Map<String, Object> written = f.onlyDocument();
        Map<String, Object> withoutClock = new LinkedHashMap<>(written);
        withoutClock.put("lamportclock", num("0"));
        assertThat(seen).isEqualTo(withoutClock);
    }

    @Test
    void nothingBeyondTheRowAndTheEnvelopeAttributesReachesData() {
        for (Map<String, Object> row : List.of(Fixture.laneRow(), Fixture.wpCreatedRow(), Fixture.missionCreatedRow(), annotationRow(), retrospectiveRow())) {
            Fixture f = new Fixture();
            f.meta = java.util.Optional.of(obj("mission_id", MISSION));
            f.run(row);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
            Set<String> allowed = new TreeSet<>();
            if (row.get("payload") instanceof Map<?, ?> payload) {
                for (Object key : payload.keySet()) {
                    allowed.add((String) key);
                }
            } else {
                allowed.addAll(row.keySet());
            }
            allowed.add("actorbinding");
            assertThat(data.keySet()).as(row.toString()).isSubsetOf(allowed);
        }
    }

    @Test
    void aMemberTheRowLacksIsNeverInventedIntoDataEvenWhenMetaJsonHasIt() {
        Fixture f = new Fixture();
        f.meta = java.util.Optional.of(obj("mission_id", OTHER(), "mission_slug", "from-meta", "extra_member", "x"));
        Map<String, Object> row = Fixture.laneRow();
        row.remove("mission_id");
        f.run(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data).doesNotContainKeys("extra_member", "mission_id", "mission_slug");
    }

    private static String OTHER() {
        return Fixture.OTHER_MISSION;
    }

    // ----- F2: the structured actor projection ----------------------------------------------------------------------------

    @Test
    void aStructuredActorProjectsToItsToolAndKeepsItsObjectInActorbindingUnchanged() {
        Fixture f = new Fixture();
        Map<String, Object> actor = obj("role", "implementer", "profile", "java-jenny", "tool", "claude", "model", "m-1");
        Map<String, Object> row = Fixture.laneRow();
        row.put("actor", actor);
        f.run(row);

        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("actor", "claude");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) document.get("data");
        assertThat(data).doesNotContainKey("actor");
        assertThat(data.get("actorbinding")).isEqualTo(actor);
        assertThat(data.get("actorbinding")).as("the object is not copied or reshaped").isSameAs(actor);
    }

    @Test
    void aStructuredActorWithoutAToolProjectsToItsRole() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("actor", obj("role", "reviewer", "profile", null, "tool", null, "model", null));
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("actor", "reviewer");
    }

    @Test
    void aStructuredActorWithNoIdentityOmitsTheAttributeButStillKeepsTheBinding() {
        Fixture f = new Fixture();
        Map<String, Object> actor = obj("role", null, "profile", "p", "tool", null, "model", null);
        Map<String, Object> row = Fixture.laneRow();
        row.put("actor", actor);
        f.run(row);
        assertThat(f.onlyDocument()).doesNotContainKey("actor");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data.get("actorbinding")).isEqualTo(actor);
    }

    @Test
    void aStringActorIsTheAttributeAndHasNoBinding() {
        Fixture f = new Fixture();
        f.run(Fixture.laneRow());
        assertThat(f.onlyDocument()).containsEntry("actor", "claude");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data).doesNotContainKeys("actor", "actorbinding");
    }

    @Test
    void anAbsentActorLeavesNeitherAnAttributeNorABinding() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.remove("actor");
        f.run(row);
        assertThat(f.onlyDocument()).doesNotContainKey("actor");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data).doesNotContainKey("actorbinding");
    }

    @Test
    void aRowThatAlreadyHoldsAnActorbindingMemberIsRefusedRatherThanOverwritten() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("actor", obj("role", "r", "tool", "t"));
        row.put("actorbinding", "x");
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(f.rendered).isEmpty();
        assertThat(f.store.requests).isEmpty();
    }

    @Test
    void aRetrospectiveActorProjectsToItsIdAndKeepsItsObject() {
        Fixture f = new Fixture();
        Map<String, Object> actor = obj("kind", "agent", "id", "agent-7", "display", "Seven");
        Map<String, Object> row = retrospectiveRow();
        row.put("actor", actor);
        f.run(row);
        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("actor", "agent-7").containsEntry("type", "ai.spec-kitty.retrospective.captured.v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) document.get("data");
        assertThat(data.get("actorbinding")).isEqualTo(actor);
        assertThat(data).containsKeys("lamport", "execution_mode").doesNotContainKeys("type", "actor", "event_id", "at");
    }

    // ----- other shapes ---------------------------------------------------------------------------------------------------

    @Test
    void aLifecycleEnvelopeBecomesItsPayloadVerbatimWithTheEnvelopeMembersLifted() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.wpCreatedRow();
        ProduceReply reply = f.run(row);

        assertThat(reply.type()).isEqualTo("ai.spec-kitty.wp.created.v1");
        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("id", EVENT).containsEntry("time", AT).containsEntry("subject", "WP02")
                .containsEntry("source", "/spec-kitty/missions/" + MISSION).containsEntry("missionid", MISSION)
                .containsEntry("missionslug", "041-reducer-unification")
                .doesNotContainKeys("actor", "executionmode", "event_type", "aggregate_id", "payload");
        assertThat(document.get("data")).isEqualTo(row.get("payload"));
        assertThat(document.get("data")).as("the payload is the data, not a copy of the envelope").isSameAs(row.get("payload"));
    }

    @Test
    void aMissionLevelLifecycleRowOnAMissionSourceHasNoSubject() {
        Fixture f = new Fixture();
        f.run(Fixture.missionCreatedRow());
        assertThat(f.onlyDocument()).doesNotContainKey("subject").containsEntry("type", "ai.spec-kitty.mission.created.v1");
    }

    @Test
    void aDecisionRowTakesItsIdAndTimeFromEventIdAndAt() {
        Fixture f = new Fixture();
        Map<String, Object> payload = obj("decision_id", "d1", "mission_id", MISSION, "actor_id", "me", "actor_type", "human");
        Map<String, Object> row = obj("event_id", EVENT, "at", AT, "event_type", "DecisionPointOpened", "payload", payload);
        f.run(row);
        assertThat(f.onlyDocument()).containsEntry("id", EVENT).containsEntry("time", AT).doesNotContainKey("actor");
        assertThat(f.onlyDocument().get("data")).isEqualTo(payload);
        assertThat(f.onlyDocument()).containsEntry("type", "ai.spec-kitty.decision.opened.v1");
    }

    @Test
    void aRunJournalRowTakesItsIdFromEventIdNotFromTheEnvelopeAndDropsRowVersion() {
        Fixture f = new Fixture();
        Map<String, Object> payload = obj("run_id", "r1", "mission_id", "");
        Map<String, Object> row = obj("event_id", EVENT, "row_version", num("1"), "event_type", "MissionRunStarted", "timestamp", AT, "payload", payload);
        f.meta = java.util.Optional.of(obj("mission_id", MISSION));
        ProduceReply reply = f.run(row);
        assertThat(reply.type()).isEqualTo("ai.spec-kitty.run.started.v1");
        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("id", EVENT).containsEntry("time", AT).containsEntry("missionid", MISSION);
        assertThat(document.get("data")).isEqualTo(payload);
        assertThat(document.toString()).doesNotContain("row_version");
    }

    @Test
    void anAnnotationRowDropsItsKindConstantAndLiftsItsActor() {
        Fixture f = new Fixture();
        f.meta = java.util.Optional.of(obj("mission_id", MISSION));
        f.run(annotationRow());
        Map<String, Object> document = f.onlyDocument();
        assertThat(document).containsEntry("type", "ai.spec-kitty.wp.innerstate.changed.v1").containsEntry("subject", "WP01")
                .containsEntry("actor", "claude");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) document.get("data");
        assertThat(data).containsOnlyKeys("delta");
    }

    @Test
    void aRowWhoseNumbersCarryADecimalPointKeepsTheirTextEndToEnd() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.wpCreatedRow();
        row.put("payload", obj("wp_id", "WP02", "mission_id", MISSION, "weight", num("5.0"), "ratio", num("1e3"), "count", num("5"), "neg", num("-0.50")));
        f.run(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data.get("weight")).isEqualTo(new JsonNumber("5.0")).isNotEqualTo(new JsonNumber("5"));
        assertThat(data.get("ratio")).isEqualTo(new JsonNumber("1e3"));
        assertThat(data.get("count")).isEqualTo(new JsonNumber("5"));
        assertThat(data.get("neg")).isEqualTo(new JsonNumber("-0.50"));
    }

    @Test
    void anIntegerOutsideThe32BitRangeInDataIsKeptNotRefusedBecauseOnlyAttributesAreBounded() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.wpCreatedRow();
        row.put("payload", obj("wp_id", "WP02", "mission_id", MISSION, "big", num("12345678901234567890")));
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data.get("big")).isEqualTo(num("12345678901234567890"));
    }

    @Test
    void theRowIsNeverChanged() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("actor", obj("role", "r", "tool", "t", "profile", null, "model", null));
        Map<String, Object> before = deepCopy(row);
        f.run(row);
        assertThat(row).isEqualTo(before);
        assertThat(new ArrayList<>(row.keySet())).isEqualTo(new ArrayList<>(before.keySet()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, v instanceof Map<?, ?> m ? deepCopy((Map<String, Object>) m) : v));
        return copy;
    }

    // ----- lane vocabulary (AC-020, producer half) and reason_source (AC-021) ---------------------------------------------

    private static RegisteredType laneWithDoingAlias() {
        RegisteredType lane = MirroredRegistry.byType(LANE_TYPE);
        return new RegisteredType(lane.type(), lane.dataschema(), lane.schemaVersion(), lane.entry(), lane.shape(), null, null, null,
                lane.constants(), lane.actorRule(),
                Map.of("from_lane", Map.of("doing", "in_progress"), "to_lane", Map.of("doing", "in_progress")));
    }

    @Test
    void aLegacyDoingLaneAppearsAsInProgressWhenTheRegistryEntryCarriesTheAlias() {
        Fixture f = new Fixture();
        f.catalog = new ListedCatalog(List.of(laneWithDoingAlias()));
        f.testLocalLaneSchema = true;
        Map<String, Object> row = Fixture.laneRow();
        row.put("from_lane", "doing");
        row.put("to_lane", "doing");
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data).containsEntry("from_lane", "in_progress").containsEntry("to_lane", "in_progress");
        assertThat(row).as("the row itself is not rewritten").containsEntry("to_lane", "doing");
    }

    @Test
    void aValueWithNoAliasIsLeftAloneAndAStringOnlyAliasNeverTouchesOtherTypes() {
        Fixture f = new Fixture();
        f.catalog = new ListedCatalog(List.of(laneWithDoingAlias()));
        Map<String, Object> row = Fixture.laneRow();
        row.put("to_lane", "approved");
        row.put("from_lane", null);
        f.run(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data).containsEntry("to_lane", "approved").containsEntry("from_lane", null);
    }

    @Test
    void anAliasMemberWhoseValueIsNotAStringIsLeftForTheSchemaToRefuse() {
        Fixture f = new Fixture();
        f.catalog = new ListedCatalog(List.of(laneWithDoingAlias()));
        Map<String, Object> row = Fixture.laneRow();
        row.put("to_lane", List.of("doing"));
        f.run(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
        assertThat(data.get("to_lane")).isEqualTo(List.of("doing"));
    }

    @Test
    void anUninitializedLaneIsRefusedAndNothingIsWritten() {
        Fixture f = new Fixture();
        f.catalog = new ListedCatalog(List.of(laneWithDoingAlias()));
        f.testLocalLaneSchema = true;
        Map<String, Object> row = Fixture.laneRow();
        row.put("to_lane", "uninitialized");
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(ai.speckitty.producer.domain.protocol.RefusalReason.SCHEMA_INVALID);
        assertThat(f.store.requests).isEmpty();
        assertThat(f.rendered).isEmpty();
    }

    @Test
    void reasonSourceRoundTripsAndStaysAbsentWhenTheRowHasNone() {
        for (String reasonSource : List.of("operator", "synthetic")) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.laneRow();
            row.put("to_lane", "canceled");
            row.put("reason_source", reasonSource);
            f.run(row);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) f.onlyDocument().get("data");
            assertThat(data).containsEntry("reason_source", reasonSource);
        }
        Fixture absent = new Fixture();
        absent.run(Fixture.laneRow());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) absent.onlyDocument().get("data");
        assertThat(data).doesNotContainKey("reason_source");
    }

    // ----- dedupe (FR-016, AC-011) and the torn-line notice ---------------------------------------------------------------

    @Test
    void firingTheSameRowTwiceLeavesOneLine() {
        Fixture f = new Fixture();
        Produce produce = f.produce();
        ProduceReply first = produce.produce(Fixture.request(Fixture.laneRow()));
        ProduceReply second = produce.produce(Fixture.request(Fixture.laneRow()));
        assertThat(first.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_DUPLICATE);
        assertThat(second.type()).isEqualTo(LANE_TYPE);
        assertThat(second.eventId()).isEqualTo(EVENT);
        assertThat(f.store.lines).hasSize(1);
    }

    @Test
    void aTornFinalLineRidesOnTheReplyAsANoticeForAProducedAndForASkippedRow() {
        Fixture f = new Fixture();
        f.store.torn = new PublishLog.TornLine(42);
        Produce produce = f.produce();
        ProduceReply first = produce.produce(Fixture.request(Fixture.laneRow()));
        ProduceReply second = produce.produce(Fixture.request(Fixture.laneRow()));
        RelativePath expected = new RelativePath(".kittify/events/publish/missions/" + MISSION + ".jsonl");
        assertThat(first.notice().path()).isEqualTo(expected);
        assertThat(first.notice().line()).isEqualTo(42);
        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_DUPLICATE);
        assertThat(second.notice()).isNotNull();
    }

    @Test
    void noTornLineMeansNoNotice() {
        assertThat(new Fixture().run(Fixture.laneRow()).notice()).isNull();
    }

    @Test
    void aProjectScopedRowIsAppendedToTheProjectFile() {
        Fixture f = new Fixture();
        Map<String, Object> row = obj("event_id", EVENT, "event_type", "ProjectInitialized", "aggregate_id", PROJECT, "aggregate_type", "Project",
                "schema_version", "1.0.0", "timestamp", AT, "payload", obj("project_uuid", PROJECT, "project_slug", "demo"),
                "project_uuid", PROJECT, "project_slug", "demo");
        ProduceReply reply = f.run(row);
        assertThat(reply.type()).isEqualTo("ai.spec-kitty.project.initialized.v1");
        assertThat(f.store.requests.get(0).target().kind()).isEqualTo(PublishLog.Target.Kind.PROJECT);
        assertThat(f.store.requests.get(0).target().id()).isEqualTo(PROJECT);
        assertThat(f.onlyDocument()).containsEntry("source", "/spec-kitty/projects/" + PROJECT).doesNotContainKeys("missionid", "subject");
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------------

    static Map<String, Object> annotationRow() {
        return obj("event_id", EVENT, "wp_id", "WP01", "kind", "annotation", "at", AT, "actor", "claude",
                "delta", obj("note", "halfway"));
    }

    static Map<String, Object> retrospectiveRow() {
        return obj("type", "RetrospectiveCaptured", "event_id", EVENT, "at", AT, "actor", obj("kind", "agent", "id", "a1", "display", null),
                "mission_id", MISSION, "mission_slug", "041-reducer-unification", "lamport", num("3"), "execution_mode", "worktree",
                "summary", "done");
    }
}
