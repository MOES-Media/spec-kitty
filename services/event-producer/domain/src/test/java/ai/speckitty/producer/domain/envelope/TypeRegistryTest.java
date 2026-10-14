package ai.speckitty.producer.domain.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeRegistryTest {

    private static TypeEntry entry(String name, int version) {
        return new TypeEntry(name, version, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.NONE, List.of(), List.of(), Map.of());
    }

    @Test
    void anEntryIsFoundByItsFullTypeName() {
        TypeRegistry registry = TypeRegistry.of(List.of(entry("a.b", 1), entry("a.b", 2), entry("c", 1)));
        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.lookup("ai.spec-kitty.a.b.v2")).contains(entry("a.b", 2));
        assertThat(registry.lookup("ai.spec-kitty.a.b.v1")).contains(entry("a.b", 1));
    }

    @Test
    void anUnknownTypeIsNotFound() {
        TypeRegistry registry = TypeRegistry.of(List.of(entry("a.b", 1)));
        for (String type : new String[] {"ai.spec-kitty.a.b.v3", "ai.spec-kitty.a.v1", "a.b", "", null, "ai.spec-kitty.A.B.v1"}) {
            assertThat(registry.lookup(type)).as("%s", type).isEmpty();
        }
    }

    @Test
    void aDuplicateTypeIsRefused() {
        assertThatThrownBy(() -> TypeRegistry.of(List.of(entry("a", 1), entry("a", 1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyRegistryIsAllowedAndFindsNothing() {
        TypeRegistry registry = TypeRegistry.of(List.of());
        assertThat(registry.size()).isZero();
        assertThat(registry.lookup("ai.spec-kitty.a.v1")).isEmpty();
    }
}
