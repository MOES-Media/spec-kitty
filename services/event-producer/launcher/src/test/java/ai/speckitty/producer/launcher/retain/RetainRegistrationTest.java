package ai.speckitty.producer.launcher.retain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.Launcher;
import ai.speckitty.producer.launcher.Main;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.ProduceHandler;
import ai.speckitty.producer.launcher.Wiring;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The WP05-owned provider file registers {@code retain}: without it the operation is refused, with it it answers per the policy. */
class RetainRegistrationTest {

    private static final String SERVICES = "META-INF/services/" + OperationHandler.class.getName();

    @Test
    void theProviderFileNamesTheRetainHandlerAndNothingElse() throws IOException {
        List<URL> files = Collections.list(Main.class.getClassLoader().getResources(SERVICES));
        String all = "";
        for (URL file : files) {
            try (InputStream in = file.openStream()) {
                all += new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertThat(all.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList())
                .containsExactly(RetainHandler.class.getName());
    }

    @Test
    void theEntryClassLoaderDiscoversRetainBesideProduce() {
        Dispatcher dispatcher = Dispatcher.discover(new ProduceHandler(Wiring.failClosedFallback()::produce), Main.class.getClassLoader());
        assertThat(dispatcher.operations()).containsExactly("produce", "retain");
        assertThat(dispatcher.find("retain").orElseThrow()).isInstanceOf(RetainHandler.class);
    }

    @Test
    void aRetainRequestIsNoLongerRefusedAsUnsupportedAndAnswersPerThePolicy(@TempDir Path dir) throws IOException {
        Path root = Repos.repo(dir);
        Dispatcher dispatcher = Dispatcher.discover(new ProduceHandler(Wiring.failClosedFallback()::produce), Main.class.getClassLoader());
        String request = "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":\"" + root + "\",\"dry_run\":true,\"policy\":{\"keep_runs\":\"abc\"}}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = new Launcher(() -> dispatcher, code -> { throw new AssertionError("halted " + code); })
                .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        assertThat(exit).isZero();
        Map<String, Object> reply = Repos.parse(out.toString(StandardCharsets.UTF_8));
        assertThat(reply).containsEntry("op", "retain").doesNotContainKey("reason");
        assertThat(reply.get("policy_error")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) reply.get("policy_error")).get("key")).isEqualTo("ledger.retention.keep_runs");
    }

    @Test
    void withoutTheProviderFileTheOperationIsRefusedAsUnsupported() {
        Dispatcher withoutFile = Dispatcher.of(new ProduceHandler(Wiring.failClosedFallback()::produce));
        assertThat(withoutFile.find("retain")).isEmpty();
    }
}
