package ai.speckitty.producer.launcher.e2e;

import static ai.speckitty.producer.launcher.e2e.Requests.AT;
import static ai.speckitty.producer.launcher.e2e.Requests.EVENT;
import static ai.speckitty.producer.launcher.e2e.Requests.MISSION;
import static ai.speckitty.producer.launcher.e2e.Requests.PROJECT;
import static ai.speckitty.producer.launcher.e2e.Requests.num;
import static ai.speckitty.producer.launcher.e2e.Requests.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launcher over real file-system adapters on a temporary repository, in child JVMs, as the client runs it. */
class ProducerEndToEndTest {

    private static final String LANE_FILE = ".kittify/events/publish/missions/" + MISSION + ".jsonl";

    private static Path repo(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".kittify"));
        Files.writeString(root.resolve(".kittify/config.yaml"), "project:\n  uuid: " + PROJECT + "\n  slug: demo\n");
        return root;
    }

    private static Child.Run accept(Path root, byte[] request) throws Exception {
        return Child.run(Child.jvm(TestMain.class.getName(), "accept"), root, request);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        ParseResult<Map<String, Object>> parsed = Json.parseObject(text.strip());
        assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void oneValidRowIsAppendedAsOneCloudEventsLine(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Child.Run run = accept(root, Requests.produce(Requests.laneRow(EVENT), ".", 20000));

        assertThat(run.exit()).isZero();
        assertThat(run.stderr()).isEmpty();
        Map<String, Object> reply = parse(run.out());
        assertThat(reply).containsEntry("outcome", "produced").containsEntry("event_id", EVENT)
                .containsEntry("type", E2eTypes.LANE_TYPE).containsEntry("op", "produce");
        assertThat(run.out()).endsWith("}\n");

        List<String> written = lines(root.resolve(LANE_FILE));
        assertThat(written).hasSize(1);
        Map<String, Object> event = parse(written.get(0));
        assertThat(event).containsEntry("specversion", "1.0").containsEntry("id", EVENT).containsEntry("type", E2eTypes.LANE_TYPE)
                .containsEntry("source", "/spec-kitty/missions/" + MISSION).containsEntry("subject", "WP01").containsEntry("time", AT)
                .containsEntry("missionid", MISSION).containsEntry("lamportclock", num("1")).containsEntry("actor", "claude");
        assertThat((String) event.get("nodeid")).matches("[0-9a-f]{12}");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        assertThat(data).containsEntry("from_lane", "planned").containsEntry("to_lane", "claimed");
        assertThat(Files.readString(root.resolve(LANE_FILE))).contains("\"weight\":5.0").as("number text survives the whole path").doesNotContain("\"weight\":5,");
        assertThat(Files.exists(root.resolve(".git/spec-kitty/event-producer/clock.json"))).isTrue();
    }

    @Test
    void firingTheSameRowAgainLeavesOneLine(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        byte[] request = Requests.produce(Requests.laneRow(EVENT), ".", 20000);
        Child.Run first = accept(root, request);
        byte[] afterFirst = Files.readAllBytes(root.resolve(LANE_FILE));
        Child.Run second = accept(root, request);

        assertThat(parse(first.out())).containsEntry("outcome", "produced");
        assertThat(parse(second.out())).containsEntry("outcome", "skipped_duplicate").containsEntry("event_id", EVENT);
        assertThat(Files.readAllBytes(root.resolve(LANE_FILE))).isEqualTo(afterFirst);
        assertThat(lines(root.resolve(LANE_FILE))).hasSize(1);
    }

    @Test
    void manyProcessesFiringTheSameRowAtOnceLeaveExactlyOneLine(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        byte[] request = Requests.produce(Requests.laneRow(EVENT), ".", 60000);
        List<Child.Run> runs = concurrently(5, i -> accept(root, request));

        List<String> outcomes = new ArrayList<>();
        for (Child.Run run : runs) {
            assertThat(run.exit()).isZero();
            outcomes.add((String) parse(run.out()).get("outcome"));
        }
        assertThat(outcomes.stream().filter("produced"::equals)).hasSize(1);
        assertThat(outcomes.stream().filter("skipped_duplicate"::equals)).hasSize(4);
        assertThat(lines(root.resolve(LANE_FILE))).hasSize(1);
    }

    @Test
    void manyProcessesFiringDifferentRowsAtOnceStampDistinctIncreasingClocks(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        List<Child.Run> runs = concurrently(6, i -> accept(root, Requests.produce(Requests.laneRow("01J0000000000000000000F00" + (i + 1)), ".", 60000)));
        for (Child.Run run : runs) {
            assertThat(run.exit()).isZero();
            assertThat(parse(run.out())).containsEntry("outcome", "produced");
        }
        List<String> written = lines(root.resolve(LANE_FILE));
        assertThat(written).hasSize(6);
        Set<Integer> clocks = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (String line : written) {
            Map<String, Object> event = parse(line);
            clocks.add(((JsonNumber) event.get("lamportclock")).integerValue().orElseThrow().intValueExact());
            keys.add(event.get("source") + "|" + event.get("id"));
        }
        assertThat(clocks).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(keys).as("at most one row per (source, id)").hasSize(6);
    }

    private interface Job {
        Child.Run run(int index) throws Exception;
    }

    private static List<Child.Run> concurrently(int count, Job job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Child.Run>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> job.run(index)));
            }
            List<Child.Run> runs = new ArrayList<>();
            for (Future<Child.Run> future : futures) {
                runs.add(future.get());
            }
            return runs;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nothingIsWrittenUnderKittySpecsAndAJournalRowTakesItsMissionFromThereOnly(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Path mission = Files.createDirectories(root.resolve("kitty-specs/041-reducer-unification"));
        Files.writeString(mission.resolve("meta.json"), "{\"mission_id\":\"" + MISSION + "\",\"mission_slug\":\"041-reducer-unification\"}\n");
        Files.writeString(mission.resolve("status.events.jsonl"), "{\"event_id\":\"x\"}\n");
        Map<String, byte[]> before = snapshot(root.resolve("kitty-specs"));

        Map<String, Object> journal = obj("event_id", EVENT, "row_version", num("1"), "event_type", "MissionRunStarted", "timestamp", AT,
                "payload", obj("run_id", "r1", "mission_id", ""));
        Child.Run run = accept(root, Requests.produce(journal, "kitty-specs/041-reducer-unification", 20000));

        assertThat(run.exit()).isZero();
        assertThat(parse(run.out())).containsEntry("outcome", "produced").containsEntry("type", E2eTypes.RUN_STARTED_TYPE);
        Map<String, Object> event = parse(lines(root.resolve(LANE_FILE)).get(0));
        assertThat(event).containsEntry("missionid", MISSION).containsEntry("source", "/spec-kitty/missions/" + MISSION)
                .containsEntry("missionslug", "041-reducer-unification");
        Map<String, byte[]> after = snapshot(root.resolve("kitty-specs"));
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((path, bytes) -> assertThat(after.get(path)).as(path).isEqualTo(bytes));
    }

    @Test
    void aMissionWithNoIdAnywherePublishesUnderTheProjectSourceWithNoFault(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Map<String, Object> journal = obj("event_id", EVENT, "row_version", num("1"), "event_type", "MissionRunStarted", "timestamp", AT,
                "payload", obj("run_id", "r1", "mission_id", "041-reducer-unification"));
        Child.Run run = accept(root, Requests.produce(journal, ".", 20000));

        assertThat(run.exit()).isZero();
        assertThat(run.stderr()).isEmpty();
        assertThat(parse(run.out())).containsEntry("outcome", "produced");
        Path file = root.resolve(".kittify/events/publish/project-" + PROJECT + ".jsonl");
        Map<String, Object> event = parse(lines(file).get(0));
        assertThat(event).containsEntry("source", "/spec-kitty/projects/" + PROJECT).doesNotContainKey("missionid");
        assertThat(Files.readString(file)).doesNotContain("missionid");
    }

    @Test
    void anUnresolvableRootIsARefusedReplyAndNothingIsCreated(@TempDir Path dir) throws Exception {
        Path bare = Files.createDirectories(dir.resolve("not-a-repo")).toRealPath();
        // The machine's temporary directory may itself hold a stray .git; the ceiling stops the walk at the start directory.
        Child.Run run = Child.run(Child.jvm(TestMain.class.getName(), "accept"), bare, Requests.produce(Requests.laneRow(EVENT), ".", 20000),
                Map.of("GIT_CEILING_DIRECTORIES", bare.toString()));
        assertThat(run.exit()).isZero();
        Map<String, Object> reply = parse(run.out());
        assertThat(reply).containsEntry("outcome", "refused").containsEntry("reason", "root-unresolvable").containsEntry("reason_class", "rejected");
        try (Stream<Path> walk = Files.walk(bare)) {
            assertThat(walk.filter(p -> !p.equals(bare))).isEmpty();
        }
        assertThat(run.out()).doesNotContain(bare.toString());
    }

    @Test
    void aRefusedRowWritesNothingAndRepliesWithAReasonNotAnException(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Map<String, Object> noTime = Requests.laneRow(EVENT);
        noTime.remove("at");
        Child.Run run = accept(root, Requests.produce(noTime, ".", 20000));
        assertThat(run.exit()).isZero();
        assertThat(parse(run.out())).containsEntry("outcome", "refused").containsEntry("reason", "time-unparseable");
        assertThat(Files.exists(root.resolve(".kittify/events"))).isFalse();
    }

    @Test
    void theLineAndTheReplyHoldNoHomePathOrUserName(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Child.Run run = accept(root, Requests.produce(Requests.laneRow(EVENT), ".", 20000));
        String everything = run.out() + run.stderr() + Files.readString(root.resolve(LANE_FILE))
                + Files.readString(root.resolve(".git/spec-kitty/event-producer/clock.json"));
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        assertThat(everything).doesNotContain(root.toString()).doesNotContain(home);
        if (user != null && user.length() >= 3) {
            assertThat(everything).doesNotContain(user);
        }
        assertThat(everything).doesNotContain("/home/").doesNotContain("/Users/").doesNotContain("C:\\\\Users");
    }

    @Test
    void aProcessThatOutlivesItsDeadlineExitsSeventyFiveWithNoReplyAndWritesNothing(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Child.Run run = Child.run(Child.jvm(TestMain.class.getName(), "sleep"), root, Requests.produce(Requests.laneRow(EVENT), ".", 400));
        assertThat(run.exit()).isEqualTo(75);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.millis()).as("it did not wait for the 120 s sleep").isLessThan(60_000);
        assertThat(Files.exists(root.resolve(LANE_FILE))).isFalse();
    }

    @Test
    void anUnreadableRequestIsExitSixtyFourFromARealProcess(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Child.Run garbage = accept(root, "this is not json".getBytes(StandardCharsets.UTF_8));
        assertThat(garbage.exit()).isEqualTo(64);
        assertThat(garbage.stdout()).isEmpty();
        Child.Run v2 = accept(root, "{\"v\":2,\"op\":\"produce\",\"deadline_ms\":1000}".getBytes(StandardCharsets.UTF_8));
        assertThat(v2.exit()).isEqualTo(64);
        assertThat(v2.stdout()).isEmpty();
    }

    @Test
    void theJvmEntryReportsNoStackTraceOrPathOnItsStandardError(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Child.Run run = accept(root, Requests.produce(Requests.laneRow(EVENT.toLowerCase()), ".", 20000));
        assertThat(run.stderr()).doesNotContain("Exception").doesNotContain("\tat ");
    }

    private static Map<String, byte[]> snapshot(Path directory) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return files;
    }
}
