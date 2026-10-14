package ai.speckitty.producer.adapter.clock;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** The state file: exactly {@code {"v":1,"nodeid":"<12 hex>","clock":<int>}}. Anything else is unreadable, which is state loss. */
final class ClockState {

    private static final JsonNumber VERSION = new JsonNumber("1");
    private static final int MEMBERS = 3;

    private ClockState() {}

    static byte[] render(String nodeId, LamportClock clock) {
        return ("{\"v\":1,\"nodeid\":\"" + nodeId + "\",\"clock\":" + clock.value() + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** The stored clock of {@code nodeId}, empty when the file is missing, unreadable, malformed or another node's. */
    static Optional<LamportClock> read(Path file, String nodeId) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!(Json.parseObject(bytes) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
            return Optional.empty();
        }
        Map<String, Object> state = parsed.value();
        if (state.size() != MEMBERS || !VERSION.equals(state.get("v")) || !nodeId.equals(state.get("nodeid"))) {
            return Optional.empty();
        }
        if (LamportClock.fromJson(state.get("clock")) instanceof ParseResult.Accepted<LamportClock> clock) {
            return Optional.of(clock.value());
        }
        return Optional.empty();
    }
}
