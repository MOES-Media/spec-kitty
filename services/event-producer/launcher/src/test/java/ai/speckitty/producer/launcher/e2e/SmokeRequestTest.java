package ai.speckitty.producer.launcher.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.Launcher;
import ai.speckitty.producer.launcher.ProduceHandler;
import ai.speckitty.producer.launcher.Wiring;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code smoke-request.json} is what the shared smoke action pipes into the native binary on each leg. It must be a well-formed
 * {@code produce} request, and the fail-closed fallback must refuse it. These tests reach the fallback through {@link
 * Wiring#failClosedFallback()}, so replacing the file with a valid-row request later (and binding the schema adapter to {@code
 * production()}) turns neither red.
 */
class SmokeRequestTest {

    private static byte[] smoke() throws IOException {
        try (InputStream in = SmokeRequestTest.class.getResourceAsStream("/protocol/smoke-request.json")) {
            assertThat(in).as("protocol/smoke-request.json on the test class path").isNotNull();
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsed() throws IOException {
        ParseResult<Map<String, Object>> parsed = Json.parseObject(smoke());
        assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
    }

    @Test
    void theSmokeRequestIsOneWellFormedProduceRequestRunInTheScratchRepositoryItself() throws IOException {
        Map<String, Object> request = parsed();
        assertThat(request).containsEntry("op", "produce").containsEntry("start_path", ".").containsKeys("v", "deadline_ms", "slot", "row");
        assertThat(smoke()).as("one object, no trailing second request").isNotEmpty();
        assertThat(new String(smoke(), StandardCharsets.UTF_8)).doesNotContain("/home/").doesNotContain("/Users/");
    }

    @Test
    void theFailClosedFallbackRepliesRejectedToItWithExitZeroAndWritesNothing(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("scratch")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        Map<String, Object> request = parsed();
        request.put("start_path", root.toString());
        byte[] bytes = Json.render(request).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Dispatcher dispatcher = Dispatcher.of(new ProduceHandler(Wiring.failClosedFallback()::produce));
        int exit = new Launcher(() -> dispatcher).run(new ByteArrayInputStream(bytes), out);

        assertThat(exit).isZero();
        String reply = out.toString(StandardCharsets.UTF_8);
        assertThat(reply).endsWith("\n").contains("\"outcome\":\"refused\"").contains("\"reason\":\"validator-unavailable\"")
                .contains("\"reason_class\":\"rejected\"");
        assertThat(reply.lines().count()).isEqualTo(1);
        assertThat(Files.exists(root.resolve(".kittify"))).isFalse();
    }
}
