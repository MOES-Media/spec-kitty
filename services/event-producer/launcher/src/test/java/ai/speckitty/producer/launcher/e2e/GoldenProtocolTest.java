package ai.speckitty.producer.launcher.e2e;

import static ai.speckitty.producer.launcher.e2e.Requests.EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The golden protocol fixtures: request, reply and exit code of the real launcher (JVM build), committed under
 * {@code src/test/resources/protocol/golden/}. The Python client's stub binaries and classification tests replay these files, so the
 * client cannot pass against a fiction. This test regenerates them from a run and fails on any drift; set
 * {@code PRODUCER_REGENERATE=1} to rewrite them. When {@code PRODUCER_NATIVE_BINARY} names the native binary, the cases whose outcome
 * does not depend on a validator are replayed against it too.
 */
class GoldenProtocolTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/protocol/golden");
    private static final String RESOURCE_DIR = "/protocol/golden/";

    /** One recorded case. */
    record Case(String name, String mode, byte[] request, boolean validatorFree) {}

    static List<Case> cases() {
        Map<String, Object> noTime = Requests.laneRow(EVENT);
        noTime.remove("at");
        return List.of(
                new Case("produce-valid", "accept", Requests.produce(Requests.laneRow(EVENT), ".", 20000), false),
                new Case("produce-refused-no-time", "accept", Requests.produce(noTime, ".", 20000), false),
                new Case("malformed", "accept", "this is not json\n".getBytes(StandardCharsets.UTF_8), true),
                new Case("version-2", "accept", "{\"deadline_ms\":1000,\"op\":\"produce\",\"v\":2}".getBytes(StandardCharsets.UTF_8), true),
                new Case("deadline", "sleep", Requests.produce(Requests.laneRow(EVENT), ".", 400), false));
    }

    /** What a run recorded. */
    record Recording(byte[] request, byte[] reply, int exit) {}

    static List<String> drift(Recording expected, Recording actual) {
        List<String> problems = new ArrayList<>();
        if (!java.util.Arrays.equals(expected.request(), actual.request())) {
            problems.add("request");
        }
        if (!java.util.Arrays.equals(expected.reply(), actual.reply())) {
            problems.add("reply");
        }
        if (expected.exit() != actual.exit()) {
            problems.add("exit");
        }
        return problems;
    }

    private static Path repo(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    private static Recording record(Case c, Path root) throws Exception {
        Child.Run run = Child.run(Child.jvm(TestMain.class.getName(), c.mode()), root, c.request());
        return new Recording(c.request(), run.stdout(), run.exit());
    }

    private static Recording committed(String name) throws IOException {
        byte[] request = resource(name + ".request");
        byte[] reply = resource(name + ".reply");
        String exit = new String(resource(name + ".exit"), StandardCharsets.UTF_8).strip();
        return new Recording(request, reply, Integer.parseInt(exit));
    }

    private static byte[] resource(String file) throws IOException {
        try (InputStream in = GoldenProtocolTest.class.getResourceAsStream(RESOURCE_DIR + file)) {
            assertThat(in).as("the committed fixture " + file).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void everyCaseMatchesItsCommittedFixtureByteForByte(@TempDir Path dir) throws Exception {
        boolean regenerate = "1".equals(System.getenv("PRODUCER_REGENERATE"));
        for (Case c : cases()) {
            Recording actual = record(c, repo(Files.createDirectories(dir.resolve(c.name()))));
            if (regenerate) {
                Files.createDirectories(GOLDEN_DIR);
                Files.write(GOLDEN_DIR.resolve(c.name() + ".request"), actual.request());
                Files.write(GOLDEN_DIR.resolve(c.name() + ".reply"), actual.reply());
                Files.writeString(GOLDEN_DIR.resolve(c.name() + ".exit"), actual.exit() + "\n");
                continue;
            }
            assertThat(drift(committed(c.name()), actual)).as(c.name()).isEmpty();
        }
    }

    @Test
    void theCommittedFixturesAreExactlyTheCasesNoStaleFileAndNoMissingOne() throws IOException {
        TreeSet<String> expected = new TreeSet<>();
        for (Case c : cases()) {
            expected.add(c.name() + ".request");
            expected.add(c.name() + ".reply");
            expected.add(c.name() + ".exit");
        }
        assertThat(expected).hasSize(15);
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> list = Files.list(GOLDEN_DIR)) {
            list.forEach(p -> found.add(p.getFileName().toString()));
        }
        assertThat(found).isEqualTo(expected);
    }

    @Test
    void theFixturesPinTheFieldNamesAndTheExitCodesTheClientDependsOn() throws IOException {
        assertThat(committed("produce-valid").exit()).isZero();
        assertThat(new String(committed("produce-valid").reply(), StandardCharsets.UTF_8))
                .isEqualTo("{\"event_id\":\"" + EVENT + "\",\"op\":\"produce\",\"outcome\":\"produced\",\"type\":\"" + E2eTypes.LANE_TYPE + "\",\"v\":1}\n");
        assertThat(committed("produce-refused-no-time").exit()).isZero();
        assertThat(new String(committed("produce-refused-no-time").reply(), StandardCharsets.UTF_8))
                .contains("\"outcome\":\"refused\"").contains("\"reason\":\"time-unparseable\"").contains("\"reason_class\":\"rejected\"");
        assertThat(committed("malformed").exit()).isEqualTo(64);
        assertThat(committed("malformed").reply()).isEmpty();
        assertThat(committed("version-2").exit()).isEqualTo(64);
        assertThat(committed("version-2").reply()).isEmpty();
        assertThat(committed("deadline").exit()).isEqualTo(75);
        assertThat(committed("deadline").reply()).isEmpty();
    }

    @Test
    void theFixturesHoldNoAbsolutePathOrUserName() throws IOException {
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        try (Stream<Path> list = Files.list(GOLDEN_DIR)) {
            for (Path file : list.toList()) {
                String text = Files.readString(file);
                assertThat(text).as(file.toString()).doesNotContain(home).doesNotContain(String.join("/", "", "home", "")).doesNotContain(String.join("/", "", "Users", ""));
                if (user != null && user.length() >= 3) {
                    assertThat(text).as(file.toString()).doesNotContain(user);
                }
            }
        }
    }

    @Test
    void theDriftCheckSeesAChangedRequestReplyOrExitCode() {
        Recording base = new Recording("r".getBytes(StandardCharsets.UTF_8), "p".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(drift(base, base)).isEmpty();
        assertThat(drift(base, new Recording("x".getBytes(StandardCharsets.UTF_8), base.reply(), 0))).containsExactly("request");
        assertThat(drift(base, new Recording(base.request(), "q".getBytes(StandardCharsets.UTF_8), 0))).containsExactly("reply");
        assertThat(drift(base, new Recording(base.request(), base.reply(), 64))).containsExactly("exit");
        assertThat(drift(base, new Recording(base.request(), new byte[0], 75))).containsExactly("reply", "exit");
    }

    @Test
    void aValidatorFreeCaseRunsAgainstTheNativeBinaryWhenOneIsNamed(@TempDir Path dir) throws Exception {
        String binary = System.getenv("PRODUCER_NATIVE_BINARY");
        if (binary == null || binary.isBlank()) {
            return;
        }
        for (Case c : cases()) {
            if (!c.validatorFree()) {
                continue;
            }
            Child.Run run = Child.run(List.of(binary), repo(Files.createDirectories(dir.resolve(c.name()))), c.request());
            assertThat(drift(committed(c.name()), new Recording(c.request(), run.stdout(), run.exit()))).as("native " + c.name()).isEmpty();
        }
    }
}
