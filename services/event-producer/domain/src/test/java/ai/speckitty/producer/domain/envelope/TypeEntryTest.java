package ai.speckitty.producer.domain.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeEntryTest {

    private static TypeEntry entry(String name, int version, List<TypeEntry.Lift> lifts, List<String> dropped, Map<String, String> renames) {
        return new TypeEntry(name, version, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.NONE, lifts, dropped, renames);
    }

    @Test
    void theTypeNameIsPrefixNameVersion() {
        assertThat(entry("lane.transitioned", 1, List.of(), List.of(), Map.of()).typeName()).isEqualTo("ai.spec-kitty.lane.transitioned.v1");
        assertThat(entry("a", 12, List.of(), List.of(), Map.of()).typeName()).isEqualTo("ai.spec-kitty.a.v12");
        assertThat(TypeEntry.PREFIX).isEqualTo("ai.spec-kitty.");
    }

    @Test
    void aNameIsDottedLowerCaseSegments() {
        for (String bad : new String[] {"", "Lane", "lane..x", ".lane", "lane.", "la ne", "lane_x", "lane-x", null}) {
            assertThatThrownBy(() -> entry(bad, 1, List.of(), List.of(), Map.of())).as("%s", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theVersionIsAtLeastOne() {
        assertThat(entry("a", 1, List.of(), List.of(), Map.of()).version()).isEqualTo(1);
        assertThatThrownBy(() -> entry("a", 0, List.of(), List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("a", -1, List.of(), List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLiftNeedsAValidAttributeNameAndMayNotRepeat() {
        assertThatThrownBy(() -> entry("a", 1, List.of(new TypeEntry.Lift("mission_id", "mission_id")), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("a", 1, List.of(new TypeEntry.Lift("x", "y"), new TypeEntry.Lift("z", "y")), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("a", 1, List.of(new TypeEntry.Lift("x", "y"), new TypeEntry.Lift("x", "z")), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMemberIsLiftedOrDroppedOrRenamedNotTwoOfThose() {
        assertThatThrownBy(() -> entry("a", 1, List.of(new TypeEntry.Lift("x", "y")), List.of("x"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("a", 1, List.of(new TypeEntry.Lift("x", "y")), List.of(), Map.of("x", "w")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("a", 1, List.of(), List.of("x"), Map.of("x", "w"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(entry("a", 1, List.of(new TypeEntry.Lift("x", "y")), List.of("d"), Map.of("r", "w")).lifts()).hasSize(1);
    }

    @Test
    void theListsAreCopiedSoTheEntryIsImmutable() {
        java.util.ArrayList<String> dropped = new java.util.ArrayList<>(List.of("d"));
        TypeEntry made = entry("a", 1, List.of(), dropped, Map.of());
        dropped.add("e");
        assertThat(made.dropped()).containsExactly("d");
        assertThatThrownBy(() -> made.dropped().add("z")).isInstanceOf(UnsupportedOperationException.class);
    }
}
