package ai.speckitty.producer.application.port;

/** The node id of this machine and user (FR-011). */
public interface NodeIdentity {

    /** The first 12 hex characters of the SHA-256 of {@code hostname:username}, equal to the Python {@code generate_node_id()}. */
    String nodeId();
}
