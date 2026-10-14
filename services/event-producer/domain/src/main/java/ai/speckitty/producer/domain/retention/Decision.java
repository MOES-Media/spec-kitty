package ai.speckitty.producer.domain.retention;

/** The eligibility decision for one file. */
public sealed interface Decision {

    /** Outside both limits, closed, unlocked, nothing unfinished. */
    record Eligible() implements Decision {}

    /** Kept, with the first reason that applies. */
    record Kept(KeepReason reason) implements Decision {}
}
