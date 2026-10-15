package ai.speckitty.producer.launcher.binding;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.launcher.Main;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real entry class in a child JVM with the shared smoke request: it is what each native-smoke leg pipes into the native binary,
 * so it must take the write path (lock, clock store, publish log) and validate against the released schema, not stop at a refusal.
 */
class ProductionEntryTest {

    private static byte[] smokeRequest(Path scratch) throws IOException {
        try (InputStream in = ProductionEntryTest.class.getResourceAsStream("/protocol/smoke-request.json")) {
            assertThat(in).as("protocol/smoke-request.json").isNotNull();
            Map<String, Object> request = Repos.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            request.put("start_path", scratch.toString());
            return Json.render(request).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void theSmokeRequestTakesTheWritePathInTheRealEntryAndTheLineIsValid(@TempDir Path dir) throws Exception {
        Path scratch = Files.createDirectories(dir.resolve("scratch")).toRealPath();
        Files.createDirectories(scratch.resolve(".git"));
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName())).directory(scratch.toFile());
        Process process = builder.start();
        process.getOutputStream().write(smokeRequest(scratch));
        process.getOutputStream().close();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(new String(stderr, StandardCharsets.UTF_8)).isZero();
        String reply = new String(stdout, StandardCharsets.UTF_8);
        assertThat(reply.lines().count()).isEqualTo(1);
        assertThat(Repos.parse(reply.strip())).containsEntry("outcome", "produced").containsEntry("type", "ai.spec-kitty.lane.transitioned.v1");
        Map<String, Object> written = Repos.parse(Files.readString(Repos.publishLog(scratch)).strip());
        assertThat(written).containsEntry("specversion", "1.0").containsEntry("dataschema", "https://schemas.example.invalid/spec-kitty/lane.transitioned.v1");
    }
}
