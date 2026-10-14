package ai.speckitty.producer.domain.protocol;

/** A request from the client. */
public sealed interface Request permits ProduceRequest, RetainRequest {

    int version();

    String startPath();

    int deadlineMs();
}
