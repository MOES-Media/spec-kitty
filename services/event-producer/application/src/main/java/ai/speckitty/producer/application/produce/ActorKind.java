package ai.speckitty.producer.application.produce;

/** The projection of a structured row {@code actor} to the {@code actor} context attribute. */
public enum ActorKind {
    /** Lane and annotation rows: a string is copied; a structured actor becomes its {@code tool}, else its {@code role}. */
    ACTOR_IDENTITY,
    /** Retrospective rows: the structured actor becomes its {@code id}. */
    RETROSPECTIVE_ACTOR_ID
}
