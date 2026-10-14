package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.envelope.TypeEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ListedCatalogTest {

    private static final ListedCatalog CATALOG = MirroredRegistry.catalog();

    /** A minimal row of this type: its discriminator or its constants, and nothing else. */
    static Map<String, Object> sample(RegisteredType type) {
        Map<String, Object> row = obj();
        if (type.shape() == Shape.PAYLOAD_ENVELOPE) {
            row.put(type.discriminatorMember(), type.discriminatorValue());
        } else {
            row.putAll(type.constants());
        }
        return row;
    }

    private static RegisteredType found(Map<String, Object> row) {
        TypeCatalog.Lookup lookup = CATALOG.find(row);
        assertThat(lookup).isInstanceOf(TypeCatalog.Found.class);
        return ((TypeCatalog.Found) lookup).type();
    }

    @Test
    void theMirrorHoldsEveryTypeOfTheRegistryAndTheLoopBelowIteratesAllOfThem() {
        assertThat(MirroredRegistry.TYPE_COUNT).isEqualTo(27);
        assertThat(MirroredRegistry.types()).hasSize(MirroredRegistry.TYPE_COUNT);
        assertThat(CATALOG.size()).isEqualTo(MirroredRegistry.TYPE_COUNT);
        Set<String> seen = new HashSet<>();
        for (RegisteredType type : MirroredRegistry.types()) {
            assertThat(found(sample(type))).isSameAs(type);
            seen.add(type.type());
        }
        assertThat(seen).hasSize(MirroredRegistry.TYPE_COUNT);
    }

    @Test
    void theTypeStringsAreTheRegistrysOwnAndNotRebuiltFromThePrefix() {
        RegisteredType lane = MirroredRegistry.byType("ai.spec-kitty.lane.transitioned.v1");
        assertThat(lane.dataschema()).isEqualTo("https://schemas.example.invalid/spec-kitty/lane.transitioned.v1");
        assertThat(lane.schemaVersion()).isEqualTo("4.0.0");
        // The same entry under a changed prefix and base keeps working: the strings are data, not derived.
        RegisteredType moved = new RegisteredType("org.example.lane.transitioned.v9", "https://example.invalid/other/lane", "9.9.9", lane.entry(),
                lane.shape(), null, null, null, lane.constants(), lane.actorRule());
        assertThat(moved.type()).isEqualTo("org.example.lane.transitioned.v9");
        assertThat(moved.dataschema()).isEqualTo("https://example.invalid/other/lane");
        assertThat(moved.schemaVersion()).isEqualTo("9.9.9");
    }

    @Test
    void aLaneRowAndAnAnnotationRowAreToldApartByTheirConstants() {
        assertThat(found(obj("event_id", "E", "wp_id", "WP01", "from_lane", "planned")).type()).isEqualTo("ai.spec-kitty.lane.transitioned.v1");
        assertThat(found(obj("event_id", "E", "wp_id", "WP01", "kind", "annotation")).type()).isEqualTo("ai.spec-kitty.wp.innerstate.changed.v1");
    }

    @Test
    void aRetrospectiveRowIsToldByItsTypeMember() {
        assertThat(found(obj("type", "RetrospectiveCaptured", "event_id", "E")).type()).isEqualTo("ai.spec-kitty.retrospective.captured.v1");
        assertThat(found(obj("type", "RetrospectiveSkipped")).type()).isEqualTo("ai.spec-kitty.retrospective.skipped.v1");
        assertThat(found(obj("type", "RetrospectiveCaptureFailed")).type()).isEqualTo("ai.spec-kitty.retrospective.capturefailed.v1");
    }

    @Test
    void aPayloadRowIsToldByItsDiscriminatorValue() {
        assertThat(found(obj("event_type", "WPCreated", "payload", obj())).type()).isEqualTo("ai.spec-kitty.wp.created.v1");
        assertThat(found(obj("event_type", "DecisionPointResolved")).type()).isEqualTo("ai.spec-kitty.decision.resolved.v1");
        assertThat(found(obj("event_type", "MissionRunStarted")).type()).isEqualTo("ai.spec-kitty.run.started.v1");
    }

    @Test
    void aRowOfNoRegisteredTypeIsUnknownAndNeverTakenForTheDefaultLaneType() {
        List<Map<String, Object>> strangers = List.of(
                obj("event_type", "NoSuchEvent"),
                obj("event_type", ""),
                obj("event_type", null),
                obj("event_type", 7),
                obj("type", "RetrospectiveInvented", "event_id", "E"),
                obj("kind", "somethingelse"),
                obj("kind", null));
        // A row that carries an unknown discriminator, or a constant member with a foreign value, is never the default type.
        for (Map<String, Object> row : strangers) {
            assertThat(CATALOG.find(row)).as(row.toString()).isInstanceOf(TypeCatalog.Unknown.class);
        }
        assertThat(found(obj("event_type", "WPCreated", "kind", "annotation")).type())
                .as("a payload envelope is told by its discriminator alone").isEqualTo("ai.spec-kitty.wp.created.v1");
    }

    @Test
    void aFlatRowWithAPayloadDiscriminatorMemberIsNeverFlat() {
        // event_type present with a value no payload type has: unknown, not the lane type.
        assertThat(CATALOG.find(obj("event_type", "x", "wp_id", "WP01", "from_lane", "planned"))).isInstanceOf(TypeCatalog.Unknown.class);
    }

    @Test
    void theUnknownDetailIsOneLineAndHoldsNoRowValue() {
        TypeCatalog.Unknown unknown = (TypeCatalog.Unknown) CATALOG.find(obj("event_type", "SECRET-VALUE"));
        assertThat(unknown.detail()).doesNotContain("SECRET-VALUE").doesNotContain("\n");
    }

    @Test
    void anEmptyCatalogKnowsNothingAndTheUnavailableOneSaysSo() {
        assertThat(new ListedCatalog(List.of()).find(obj())).isInstanceOf(TypeCatalog.Unknown.class);
        assertThat(TypeCatalog.UNAVAILABLE.find(obj("event_type", "WPCreated"))).isSameAs(TypeCatalog.Unavailable.INSTANCE);
    }

    @Test
    void twoTypesThatCannotBeToldApartAreRefusedWhenTheCatalogIsBuilt() {
        RegisteredType lane = MirroredRegistry.byType("ai.spec-kitty.lane.transitioned.v1");
        RegisteredType twin = new RegisteredType("ai.spec-kitty.lane.twin.v1", lane.dataschema(), lane.schemaVersion(), lane.entry(), lane.shape(),
                null, null, null, lane.constants(), lane.actorRule());
        assertThatThrownBy(() -> new ListedCatalog(List.of(lane, twin))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("told apart");
        assertThatThrownBy(() -> new ListedCatalog(List.of(lane, lane))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        RegisteredType created = MirroredRegistry.byType("ai.spec-kitty.wp.created.v1");
        RegisteredType createdTwin = new RegisteredType("ai.spec-kitty.wp.twin.v1", created.dataschema(), created.schemaVersion(), created.entry(),
                created.shape(), created.discriminatorMember(), created.discriminatorValue(), created.payloadMember(), Map.of(), null);
        assertThatThrownBy(() -> new ListedCatalog(List.of(created, createdTwin))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyPayloadTypeHasADistinctDiscriminatorValueAndEveryFlatTypeADistinctConstantSet() {
        List<String> keys = new ArrayList<>();
        for (RegisteredType type : MirroredRegistry.types()) {
            keys.add(type.shape() == Shape.PAYLOAD_ENVELOPE ? type.discriminatorValue() : type.constants().toString());
        }
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void theRegisteredTypeRefusesAnInconsistentShape() {
        TypeEntry entry = MirroredRegistry.byType("ai.spec-kitty.wp.created.v1").entry();
        assertThatThrownBy(() -> new RegisteredType("t", "d", "v", entry, Shape.PAYLOAD_ENVELOPE, null, null, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisteredType("t", "d", "v", entry, Shape.FLAT, "event_type", null, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisteredType("t", "d", "v", entry, Shape.PAYLOAD_ENVELOPE, "event_type", "X", "payload", Map.of("k", "v"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
