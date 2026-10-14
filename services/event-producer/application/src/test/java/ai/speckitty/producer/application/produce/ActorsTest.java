package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActorsTest {

    private static final ActorKind IDENTITY = ActorKind.ACTOR_IDENTITY;
    private static final ActorKind RETRO = ActorKind.RETROSPECTIVE_ACTOR_ID;

    @Test
    void aStringActorIsCopiedVerbatim() {
        assertThat(Actors.attribute(IDENTITY, "claude")).contains("claude");
        assertThat(Actors.attribute(IDENTITY, " spaced name ")).contains(" spaced name ");
    }

    @Test
    void aStructuredActorProjectsToItsToolElseItsRole() {
        assertThat(Actors.attribute(IDENTITY, obj("role", "implementer", "profile", "p", "tool", "claude", "model", "m"))).contains("claude");
        assertThat(Actors.attribute(IDENTITY, obj("role", "implementer", "profile", null, "tool", null, "model", null))).contains("implementer");
        assertThat(Actors.attribute(IDENTITY, obj("role", "implementer", "tool", ""))).as("an empty tool is no tool").contains("implementer");
    }

    @Test
    void anEmptyIdentityOmitsTheAttribute() {
        assertThat(Actors.attribute(IDENTITY, obj("role", null, "profile", null, "tool", null, "model", null))).isEmpty();
        assertThat(Actors.attribute(IDENTITY, obj("role", "", "tool", ""))).isEmpty();
        assertThat(Actors.attribute(IDENTITY, obj())).isEmpty();
        assertThat(Actors.attribute(IDENTITY, "")).isEmpty();
        assertThat(Actors.attribute(IDENTITY, null)).isEmpty();
    }

    @Test
    void aNonStringToolOrRoleIsNeverStringified() {
        assertThat(Actors.attribute(IDENTITY, obj("tool", 5, "role", "r"))).as("a number is not a tool").contains("r");
        assertThat(Actors.attribute(IDENTITY, obj("tool", List.of("x"), "role", Boolean.TRUE))).isEmpty();
    }

    @Test
    void aRetrospectiveActorProjectsToItsId() {
        assertThat(Actors.attribute(RETRO, obj("kind", "agent", "id", "agent-7", "display", "Seven"))).contains("agent-7");
        assertThat(Actors.attribute(RETRO, obj("kind", "human", "id", "", "display", null))).isEmpty();
        assertThat(Actors.attribute(RETRO, obj("kind", "human", "display", "x"))).isEmpty();
    }

    @Test
    void theRetrospectiveProjectionDoesNotFallBackToToolOrRole() {
        assertThat(Actors.attribute(RETRO, obj("tool", "claude", "role", "r"))).isEmpty();
        assertThat(Actors.attribute(IDENTITY, obj("id", "agent-7"))).as("the identity projection does not read id").isEmpty();
    }

    @Test
    void aValueOfAnotherKindIsPassedThroughSoTheChecksRefuseItInsteadOfItBeingDroppedSilently() {
        for (ActorKind kind : ActorKind.values()) {
            assertThat(Actors.attribute(kind, Boolean.TRUE)).as(kind.name()).isEqualTo(Optional.of(Boolean.TRUE));
            assertThat(Actors.attribute(kind, List.of("a"))).as(kind.name()).isEqualTo(Optional.of(List.of("a")));
        }
        assertThat(Actors.attribute(RETRO, "plain")).as("a string retrospective actor is not an object").contains("plain");
    }

    @Test
    void onlyAStructuredActorIsBound() {
        assertThat(Actors.isBound(obj("role", "r"))).isTrue();
        assertThat(Actors.isBound(obj())).as("an empty object is still an object").isTrue();
        assertThat(Actors.isBound("claude")).isFalse();
        assertThat(Actors.isBound(null)).isFalse();
        assertThat(Actors.isBound(List.of())).isFalse();
    }
}
