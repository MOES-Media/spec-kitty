package ai.speckitty.producer.launcher.spike;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WP01 feasibility spike. One JSON object in on stdin, one JSON object out on stdout.
 *
 * <p>It exercises exactly what the native image must carry: Jackson, one JSON Schema validation, an exclusive {@link FileChannel}
 * lock and a {@code gethostname} call through the Foreign Function and Memory API. WP04 replaces it with the real entry and
 * deletes this package.
 */
public final class SpikeMain {
    private static final String REQUEST_SCHEMA = """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "required": ["v", "op"],
          "properties": {
            "v": {"const": 1},
            "op": {"type": "string", "minLength": 1},
            "start_path": {"type": "string"}
          }
        }
        """;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private SpikeMain() {}

    public static void main(String[] args) throws IOException {
        String request = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        int exit = 0;
        String reply;
        try {
            reply = handle(request);
        } catch (RuntimeException | IOException e) {
            reply = "{\"v\":1,\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
            exit = 2;
        }
        out.println(reply);
        out.flush();
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /** Validates {@code requestJson}, takes and releases an exclusive file lock, resolves the host name, and builds the reply. */
    public static String handle(String requestJson) throws IOException {
        JsonNode request = MAPPER.readTree(requestJson);
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(REQUEST_SCHEMA);
        List<Error> errors = schema.validate(request);
        ObjectNode reply = MAPPER.createObjectNode();
        reply.put("v", 1);
        if (!errors.isEmpty()) {
            reply.put("ok", false);
            reply.put("error", "schema");
            reply.put("errors", errors.size());
            return MAPPER.writeValueAsString(reply);
        }
        reply.put("ok", true);
        reply.put("op", request.get("op").asText());
        reply.put("locked", lockOnce());
        String host = HostName.get();
        reply.put("hostname_ok", !host.isEmpty());
        return MAPPER.writeValueAsString(reply);
    }

    private static boolean lockOnce() throws IOException {
        Path file = Files.createTempFile("producer-spike", ".lock");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
            FileLock lock = channel.lock()) {
            return lock.isValid() && !lock.isShared();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
