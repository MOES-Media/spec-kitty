package ai.speckitty.producer.application.produce;

/** How a persisted row holds its event: all members in one object, or an envelope around a {@code payload} member. */
public enum Shape {
    /** One object: the data members, the lifted members and the constants. */
    FLAT,
    /** An envelope of its own members plus a payload member that is the data; a discriminator member names the kind. */
    PAYLOAD_ENVELOPE
}
