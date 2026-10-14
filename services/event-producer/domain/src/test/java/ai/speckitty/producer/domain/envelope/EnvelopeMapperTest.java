package ai.speckitty.producer.domain.envelope;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.Results;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvelopeMapperTest {

    private static final TypeEntry ZETA = new TypeEntry(
            "zeta.event", 3, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.WORK_PACKAGE,
            List.of(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("at", "time"), new TypeEntry.Lift("wp", "subject")),
            List.of("kind"), Map.of("actor", "actorbinding"));

    private static final TypeEntry PLAIN = new TypeEntry(
            "plain", 1, TypeEntry.Scope.PROJECT, TypeEntry.SubjectRule.NONE, List.of(), List.of(), Map.of());

    @Test
    void liftedMembersMoveToAttributesUnderTheirAttributeNames() {
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("event_id", "E1", "at", "T1", "wp", "WP01", "other", "o")));
        assertThat(mapped.attributes()).containsOnly(Map.entry("id", "E1"), Map.entry("time", "T1"), Map.entry("subject", "WP01"));
        assertThat(mapped.data()).containsOnly(Map.entry("other", "o"));
    }

    @Test
    void everyOtherMemberStaysInDataVerbatimAndStructuredValuesAreNotTouched() {
        Map<String, Object> nested = obj("k", list(num("1"), null));
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("event_id", "E1", "payload", nested, "n", num("2.50"), "flag", false)));
        assertThat(mapped.data()).containsEntry("payload", nested).containsEntry("n", num("2.50")).containsEntry("flag", false);
        assertThat(mapped.data().get("payload")).isSameAs(nested);
    }

    @Test
    void aDroppedMemberIsInNeitherPlace() {
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("kind", "annotation", "x", "y")));
        assertThat(mapped.attributes()).isEmpty();
        assertThat(mapped.data()).containsOnlyKeys("x");
    }

    @Test
    void theOneRenameIsAppliedToTheDataMember() {
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("actor", obj("role", "r"), "x", "y")));
        assertThat(mapped.data()).containsOnlyKeys("actorbinding", "x");
        assertThat(mapped.data().get("actorbinding")).isEqualTo(obj("role", "r"));
    }

    @Test
    void aLiftedMemberThatIsNullOrAbsentIsAbsent() {
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("event_id", "E1", "at", null)));
        assertThat(mapped.attributes()).containsOnlyKeys("id");
        assertThat(mapped.data()).isEmpty();
    }

    @Test
    void aRenameThatCollidesWithAnExistingMemberIsRefused() {
        assertThat(Results.refusedReason(EnvelopeMapper.map(ZETA, obj("actor", "a", "actorbinding", "b")))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void anEntryWithNoRulesMovesEverythingToData() {
        Map<String, Object> row = obj("a", "1", "b", "2");
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(PLAIN, row));
        assertThat(mapped.attributes()).isEmpty();
        assertThat(mapped.data()).isEqualTo(row);
    }

    @Test
    void theMappingIsDrivenByTheEntryNotByTheTypeName() {
        // The same lift list under two different names behaves the same: no code looks at the name.
        TypeEntry renamed = new TypeEntry("omega.other", 9, ZETA.scope(), ZETA.subject(), ZETA.lifts(), ZETA.dropped(), ZETA.dataRenames());
        Map<String, Object> row = obj("event_id", "E1", "at", "T1", "kind", "k", "actor", "a", "z", "z");
        assertThat(Results.accepted(EnvelopeMapper.map(renamed, row))).isEqualTo(Results.accepted(EnvelopeMapper.map(ZETA, row)));
    }

    @Test
    void theMappedMapsCannotBeChangedByTheCaller() {
        EnvelopeMapper.Mapped mapped = Results.accepted(EnvelopeMapper.map(ZETA, obj("event_id", "E1", "x", "y")));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> mapped.attributes().put("a", "b"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> mapped.data().put("a", "b"));
    }
}
