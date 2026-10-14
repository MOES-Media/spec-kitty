package ai.speckitty.producer.launcher.retain;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code smoke-request-retain.json} is what the shared smoke action pipes into the native binary on each leg. It is a dry run, so
 * the leg proves the provider file and the retain classes survive the native build without deleting anything.
 */
class RetainSmokeRequestTest {

    private static byte[] smoke() throws IOException {
        try (InputStream in = RetainSmokeRequestTest.class.getResourceAsStream("/retain/smoke-request-retain.json")) {
            assertThat(in).as("retain/smoke-request-retain.json on the test class path").isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void theSmokeRequestIsAWellFormedDryRunRetainWithAnEmptyPolicy() throws IOException {
        Map<String, Object> request = Repos.parse(new String(smoke(), StandardCharsets.UTF_8));
        assertThat(request).containsEntry("op", "retain").containsEntry("dry_run", true).containsEntry("start_path", ".")
                .containsKeys("v", "deadline_ms", "policy");
        assertThat((Map<?, ?>) request.get("policy")).isEmpty();
        assertThat(new String(smoke(), StandardCharsets.UTF_8)).doesNotContain(String.join("/", "", "home", "")).doesNotContain(String.join("/", "", "Users", ""));
    }

    @Test
    void theEntryClassAnswersItInAScratchRepositoryWithAnEmptyDryRunReplyAndExitZero(@TempDir Path dir) throws Exception {
        Path scratch = Repos.repo(dir);
        List<String> command = ChildJvm.command(Main.class.getName());
        Process process = ChildJvm.start(command, scratch);
        try (var stdin = process.getOutputStream()) {
            stdin.write(smoke());
        }
        String out = ChildJvm.collect(process).get(60, TimeUnit.SECONDS);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out.lines().count()).isEqualTo(1);
        assertThat(Repos.parse(out)).containsEntry("op", "retain").containsEntry("dry_run", true)
                .containsEntry("eligible", List.of()).containsEntry("kept", List.of()).containsEntry("deleted", List.of());
        try (var walk = Files.walk(scratch.resolve(".kittify"))) {
            assertThat(walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList()).containsExactly("config.yaml");
        }
    }
}
