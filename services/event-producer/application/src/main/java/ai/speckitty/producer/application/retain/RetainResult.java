package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.domain.protocol.RetainReply;

/** What {@link Retain} answers. */
public sealed interface RetainResult {

    /** A reply was formed (a policy error is a reply). */
    record Replied(RetainReply reply) implements RetainResult {}

    /** No repository lies above the start path: nothing was listed. */
    record RootUnresolvable() implements RetainResult {}
}
