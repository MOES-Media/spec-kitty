package ai.speckitty.producer.adapter.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.NodeIdentity;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Node id parity with {@code identity/project.py::generate_node_id()} (FR-011, AC-039). The golden file is the output of the real
 * Python function with its two inputs patched, synthetic names only.
 */
class NodeIdentityTest {

    private static final int CASE_FLOOR = 3;

    record Golden(String hostname, String username, String nodeid, String via) {}

    private static List<Golden> golden() throws IOException {
        byte[] bytes;
        try (var in = NodeIdentityTest.class.getResourceAsStream("/identity/nodeid-golden.json")) {
            bytes = in.readAllBytes();
        }
        // the file is a JSON array of objects: wrap it so the object reader can take it
        ParseResult<Map<String, Object>> parsed = Json.parseObject("{\"rows\":" + new String(bytes, StandardCharsets.UTF_8) + "}");
        List<Golden> rows = new ArrayList<>();
        for (Object row : (List<?>) ((ParseResult.Accepted<Map<String, Object>>) parsed).value().get("rows")) {
            Map<?, ?> m = (Map<?, ?>) row;
            rows.add(new Golden((String) m.get("hostname"), (String) m.get("username"), (String) m.get("nodeid"), (String) m.get("via")));
        }
        return rows;
    }

    private static boolean matchesEveryGoldenRow(BiFunction<String, String, String> derive) throws IOException {
        for (Golden g : golden()) {
            if (!g.nodeid().equals(derive.apply(g.hostname(), g.username()))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256Hex(String text, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theDerivationEqualsThePythonGoldenValues() throws IOException {
        List<Golden> rows = golden();
        assertThat(rows).as("golden cases").hasSizeGreaterThanOrEqualTo(CASE_FLOOR);
        for (Golden g : rows) {
            assertThat(NodeIds.of(g.hostname(), g.username())).as(g.hostname() + ":" + g.username()).isEqualTo(g.nodeid());
            assertThat(g.nodeid()).matches("[0-9a-f]{12}");
        }
        assertThat(matchesEveryGoldenRow(NodeIds::of)).isTrue();
    }

    @Test
    void aChangedHashInputIsCaughtByTheGolden() throws IOException {
        assertThat(matchesEveryGoldenRow(NodeIds::of)).as("control").isTrue();
        assertThat(matchesEveryGoldenRow((host, user) -> NodeIds.of(user, host))).as("host and user swapped").isFalse();
        assertThat(matchesEveryGoldenRow((host, user) -> sha256Hex(host + user, 12))).as("no separator").isFalse();
        assertThat(matchesEveryGoldenRow((host, user) -> sha256Hex(host + ":" + user, 11))).as("truncated to 11").isFalse();
        assertThat(matchesEveryGoldenRow((host, user) -> sha256Hex(host + ":" + user, 13))).as("truncated to 13").isFalse();
        assertThat(matchesEveryGoldenRow((host, user) -> sha256Hex(host + ":" + user + "\n", 12))).as("trailing newline").isFalse();
        assertThat(matchesEveryGoldenRow((host, user) -> sha256Hex(host.toUpperCase() + ":" + user, 12))).as("case folded").isFalse();
    }

    @Test
    void theUsernameChainIsLognameUserLnameUsernameThenTheJavaProperty() {
        Map<String, String> env = new java.util.LinkedHashMap<>(Map.of("LOGNAME", "n1", "USER", "n2", "LNAME", "n3", "USERNAME", "n4"));
        Map<String, String> props = Map.of("user.name", "n5");
        List<String> order = new ArrayList<>();
        for (String name : List.of("LOGNAME", "USER", "LNAME", "USERNAME")) {
            order.add(UserNames.resolve(env::get, props::get));
            env.remove(name);
        }
        order.add(UserNames.resolve(env::get, props::get));
        assertThat(order).containsExactly("n1", "n2", "n3", "n4", "n5");
    }

    @Test
    void anEmptyVariableIsSkippedAsPythonsGetpassSkipsIt() {
        Map<String, String> env = Map.of("LOGNAME", "", "USER", "real");
        assertThat(UserNames.resolve(env::get, name -> "fallback")).isEqualTo("real");
    }

    @Test
    void noUsernameAnywhereIsAnErrorNotAGuess() {
        assertThatThrownBy(() -> UserNames.resolve(name -> null, name -> null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UserNames.resolve(name -> "", name -> "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theIdentityDerivesTheGoldenValuesFromItsInjectedHostAndUser() throws IOException {
        for (Golden g : golden()) {
            Map<String, String> env = "env".equals(g.via()) ? Map.of("USER", g.username()) : Map.of();
            Map<String, String> props = Map.of("user.name", g.username());
            NodeIdentity identity = new SystemNodeIdentity(() -> g.hostname(), () -> UserNames.resolve(env::get, props::get));
            assertThat(identity.nodeId()).as(g.via()).isEqualTo(g.nodeid());
        }
        assertThat(golden().stream().map(Golden::via)).contains("env", "user.name");
    }

    @Test
    void theHostnameDowncallReturnsAHostname() {
        String host = HostName.get();
        assertThat(host).isNotBlank().doesNotContain("\0", "\n");
        assertThat(host.length()).isLessThan(256);
    }

    @Test
    void theHostnameDowncallAgreesWithTheKernelWhereOneIsReadable() throws IOException {
        Path proc = Path.of("/proc/sys/kernel/hostname");
        assumeTrue(Files.isReadable(proc), "no /proc/sys/kernel/hostname on this OS; the live comparison below covers it");
        assertThat(HostName.get()).isEqualTo(Files.readString(proc).strip());
    }

    /** The live comparison: the Python value for this machine and user, exported by each leg of the gradle-test matrix. */
    @Test
    void theLiveNodeIdEqualsWhatPythonComputesForThisMachine() throws IOException {
        String expected = System.getenv("PRODUCER_LIVE_PY_NODE_ID");
        boolean required = "1".equals(System.getenv("REQUIRE_LIVE_NODE_ID"));
        if (expected == null || expected.isBlank()) {
            assertThat(required).as("PRODUCER_LIVE_PY_NODE_ID must be set when REQUIRE_LIVE_NODE_ID=1").isFalse();
            assumeTrue(false, "PRODUCER_LIVE_PY_NODE_ID is not set; only the gradle-test legs export it");
        }
        assertThat(new SystemNodeIdentity().nodeId()).isEqualTo(expected.strip());
        int cases = golden().size() + 1;
        assertThat(cases).as("cases: the live one plus the golden strings").isGreaterThanOrEqualTo(CASE_FLOOR + 1);
    }
}
