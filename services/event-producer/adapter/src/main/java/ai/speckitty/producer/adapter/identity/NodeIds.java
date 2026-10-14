package ai.speckitty.producer.adapter.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The node id hash of {@code identity/project.py::generate_node_id()}: SHA-256 of {@code hostname:username}, first 12 hex. */
final class NodeIds {

    private static final int LENGTH = 12;

    private NodeIds() {}

    static String of(String hostname, String username) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((hostname + ":" + username).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
