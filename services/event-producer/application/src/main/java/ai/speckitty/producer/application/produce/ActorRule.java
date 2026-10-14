package ai.speckitty.producer.application.produce;

import java.util.Objects;

/**
 * The actor rule of one type (data-model section 3): which row member holds the actor, how it projects to the attribute, and the
 * {@code data} member that keeps a structured actor unchanged.
 *
 * @param member the row member, normally {@code actor}
 * @param kind the projection
 * @param bindingMember the {@code data} member that holds a structured actor, normally {@code actorbinding}
 */
public record ActorRule(String member, ActorKind kind, String bindingMember) {

    public ActorRule {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bindingMember, "bindingMember");
    }
}
