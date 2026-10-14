package ai.speckitty.producer.domain.envelope;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static ai.speckitty.producer.domain.order.Rows.with;
import static ai.speckitty.producer.domain.order.Rows.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.Results;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private static final Map<String, Object> VALID = obj(
            "specversion", "1.0",
            "id", "01J00000000000000000000ABA",
            "source", "/spec-kitty/missions/01J0000000000000000000MSN1",
            "type", "ai.spec-kitty.lane.transitioned.v1",
            "time", "2026-10-13T10:00:00Z",
            "lamportclock", num("3"),
            "nodeid", "0123456789ab");

    @Test
    void aValidEnvelopeKeepsEveryAttributeWithItsType() {
        Envelope envelope = Results.accepted(Envelope.build(VALID));
        assertThat(envelope.get("specversion")).isEqualTo(new AttributeValue.Text("1.0"));
        assertThat(envelope.get("lamportclock")).isEqualTo(new AttributeValue.Integer32(3));
        assertThat(envelope.attributes()).hasSize(7);
    }

    @Test
    void anAbsentValueIsOmittedNeverNull() {
        Envelope envelope = Results.accepted(Envelope.build(with(with(VALID, "subject", null), "missionid", null)));
        assertThat(envelope.attributes()).doesNotContainKeys("subject", "missionid");
        assertThat(envelope.attributes().values()).doesNotContainNull();
        assertThat(envelope.get("subject")).isNull();
    }

    @Test
    void everyAttributeNameIsChecked() {
        for (String name : new String[] {"mission_id", "Mission", "a".repeat(21), "", "mission-id"}) {
            assertThat(Results.refusedReason(Envelope.build(with(VALID, name, "x")))).as(name).isEqualTo(FaultReason.REJECTED);
        }
        assertThat(Results.accepted(Envelope.build(with(VALID, "a".repeat(20), "x"))).attributes()).hasSize(8);
    }

    @Test
    void aStructuredOrFractionalValueIsRefused() {
        for (Object value : new Object[] {obj("a", "b"), list("a"), num("1.5")}) {
            assertThat(Results.refusedReason(Envelope.build(with(VALID, "data", value)))).isEqualTo(FaultReason.REJECTED);
        }
    }

    @Test
    void theCoreAttributesAreRequired() {
        for (String core : new String[] {"specversion", "id", "source", "type"}) {
            assertThat(Results.refusedReason(Envelope.build(without(VALID, core)))).as(core).isEqualTo(FaultReason.REJECTED);
            assertThat(Results.refusedReason(Envelope.build(with(VALID, core, null)))).as(core + " null").isEqualTo(FaultReason.REJECTED);
        }
    }

    @Test
    void theSpecVersionIsOnePointZero() {
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "specversion", "1.1")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "specversion", num("1"))))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void theIdMustBeAUlidAndNeverAUuid() {
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "id", "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "id", "0190a1b2c3d47e5f8a9b0c1d2e3f4a5b")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "id", num("5"))))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void theSourceMustHaveTheSpecKittyShape() {
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "source", "/spec-kitty/missions/slug")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.accepted(Envelope.build(with(VALID, "source", "/spec-kitty/projects/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b")))
                .get("source")).isNotNull();
    }

    @Test
    void theTypeMustCarryThePrefixAndAVersion() {
        for (String type : new String[] {"lane.transitioned.v1", "ai.spec-kitty.lane.transitioned", "ai.spec-kitty..v1", "ai.spec-kitty.Lane.v1", "ai.spec-kitty.lane.v0", "ai.spec-kitty.lane.v", "ai.spec-kitty.x.v01"}) {
            assertThat(Results.refusedReason(Envelope.build(with(VALID, "type", type)))).as(type).isEqualTo(FaultReason.REJECTED);
        }
        assertThat(Results.accepted(Envelope.build(with(VALID, "type", "ai.spec-kitty.run.step.issued.v12")))).isNotNull();
    }

    @Test
    void timeMustBeRfc3339WhenPresentAndIsNeverSynthesisedWhenAbsent() {
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "time", "yesterday")))).isEqualTo(FaultReason.REJECTED);
        Envelope noTime = Results.accepted(Envelope.build(without(VALID, "time")));
        assertThat(noTime.get("time")).isNull();
    }

    @Test
    void theClockAttributeIsAnIntegerInTheClockRange() {
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "lamportclock", num("-1"))))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.refusedReason(Envelope.build(with(VALID, "lamportclock", "3")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.accepted(Envelope.build(with(VALID, "lamportclock", num("0")))).get("lamportclock")).isEqualTo(new AttributeValue.Integer32(0));
    }

    @Test
    void theEnvelopeCannotBeChangedThroughItsInput() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>(VALID);
        Envelope envelope = Results.accepted(Envelope.build(raw));
        raw.put("extra", "x");
        assertThat(envelope.attributes()).doesNotContainKey("extra");
        assertThatThrownBy(() -> envelope.attributes().put("x", new AttributeValue.Text("y"))).isInstanceOf(UnsupportedOperationException.class);
    }
}
