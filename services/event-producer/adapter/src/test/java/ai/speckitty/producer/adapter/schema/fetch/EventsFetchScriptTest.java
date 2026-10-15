package ai.speckitty.producer.adapter.schema.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real {@code gradle/events-fetch.gradle.kts} in a throwaway Gradle project against a tiny wheel that sits in the task's
 * wheel cache, so no test touches the network. The negatives are the silent-success cases of the plan: a wrong checksum must fail
 * before anything is unzipped, and a wheel whose registry is empty, absent or lacks a schema must never produce an output that a
 * validator could be built from.
 */
class EventsFetchScriptTest {

    private static final String VERSION = "0.0.1";
    private static final String WHEEL = "spec_kitty_events-" + VERSION + "-py3-none-any.whl";
    private static final String ROOT = "spec_kitty_events/producer_contract/";
    private static final String ROW_SCHEMA = "{\"type\":\"object\"}";

    @TempDir
    Path project;

    // ----- fixture ------------------------------------------------------------------------------------------------------

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String registry(String... names) {
        StringBuilder types = new StringBuilder();
        for (String name : names) {
            types.append(types.isEmpty() ? "" : ",")
                    .append("{\"type\":\"ai.spec-kitty.").append(name).append("\",\"name\":\"").append(name)
                    .append("\",\"schema\":\"data/").append(name).append(".schema.json\"}");
        }
        return "{\"registry_version\":\"1.0.0\",\"types\":[" + types + "]}";
    }

    private static byte[] wheel(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> goodEntries(String... names) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "registry.json", registry(names));
        for (String name : names) {
            entries.put(ROOT + "data/" + name + ".schema.json", ROW_SCHEMA);
            entries.put(ROOT + "composed/rows/" + name + ".row.schema.json", ROW_SCHEMA);
        }
        entries.put("spec_kitty_events/other.py", "x = 1\n");
        return entries;
    }

    /** Lays out a project that applies the real script, with {@code wheel} in the cache and a pin naming {@code pinnedSha}. */
    private Path layout(byte[] wheel, String pinnedSha) throws IOException {
        Path script = Path.of("..", "gradle", "events-fetch.gradle.kts").toAbsolutePath().normalize();
        assertThat(script).as("the script under test").exists();
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }\napply(from = \"" + script.toString().replace('\\', '/') + "\")\n");
        Files.createDirectories(project.resolve("gradle"));
        Files.writeString(
                project.resolve("gradle/events-pin.properties"),
                "version=" + VERSION + "\nurl=https://files.pythonhosted.org/packages/00/00/" + "0".repeat(60) + "/" + WHEEL + "\nsha256=" + pinnedSha + "\n");
        Path cache = Files.createDirectories(project.resolve("build/events/wheel"));
        Files.write(cache.resolve(WHEEL), wheel);
        return project;
    }

    private GradleRunner runner(Map<String, String> env, String... arguments) {
        List<String> all = new java.util.ArrayList<>(List.of(arguments));
        all.add("--offline");
        all.add("-Dorg.gradle.jvmargs=-Xmx384m");
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.remove("CI");
        environment.putAll(env);
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(all).withEnvironment(environment);
    }

    private Path extracted() {
        return project.resolve("build/events/contract");
    }

    /** Gradle creates the declared output directory before the task runs, so "nothing unzipped" is: no file in it. */
    private void assertNothingExtracted() throws IOException {
        if (Files.exists(extracted())) {
            try (java.util.stream.Stream<Path> walk = Files.walk(extracted())) {
                assertThat(walk.filter(Files::isRegularFile)).as("files extracted from the wheel").isEmpty();
            }
        }
    }

    // ----- the positive control -------------------------------------------------------------------------------------------

    @Test
    void aWheelWhoseChecksumMatchesIsExtractedAndItsContractFilesLandInTheOutput() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1", "c.d.v1"));
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").build();

        assertThat(result.getOutput()).contains("fetchEventsContract");
        assertThat(extracted().resolve("registry.json")).exists();
        assertThat(extracted().resolve("composed/rows/a.b.v1.row.schema.json")).exists();
        assertThat(extracted().resolve("composed/rows/c.d.v1.row.schema.json")).exists();
        assertThat(extracted().resolve("data/a.b.v1.schema.json")).exists();
        assertThat(extracted().resolve("other.py")).as("only the producer contract is taken from the wheel").doesNotExist();
    }

    // ----- silent-success negatives ---------------------------------------------------------------------------------------

    @Test
    void aPlantedWrongChecksumFailsTheFetchBeforeAnythingIsUnzipped() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        layout(bytes, "0".repeat(64));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("sha256").contains("does not match");
        assertNothingExtracted();
    }

    @Test
    void aWheelWithAnEmptyRegistryFailsTheBuildAndLeavesNoOutputBehind() throws Exception {
        Map<String, String> entries = goodEntries();
        byte[] bytes = wheel(entries);
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("registry").contains("no types");
        assertThat(extracted().resolve("registry.json")).doesNotExist();
    }

    @Test
    void aWheelWithNoRegistryAtAllFailsTheBuild() throws Exception {
        Map<String, String> entries = goodEntries("a.b.v1");
        entries.remove(ROOT + "registry.json");
        byte[] bytes = wheel(entries);
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("registry.json").contains("missing");
    }

    @Test
    void aRegisteredTypeWhoseRowSchemaIsMissingFailsTheBuild() throws Exception {
        Map<String, String> entries = goodEntries("a.b.v1", "c.d.v1");
        entries.remove(ROOT + "composed/rows/c.d.v1.row.schema.json");
        byte[] bytes = wheel(entries);
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("c.d.v1").contains("row schema");
    }

    @Test
    void aRegisteredTypeWhoseRowSchemaIsEmptyFailsTheBuild() throws Exception {
        Map<String, String> entries = goodEntries("a.b.v1");
        entries.put(ROOT + "composed/rows/a.b.v1.row.schema.json", "");
        byte[] bytes = wheel(entries);
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("a.b.v1").contains("row schema");
    }

    @Test
    void anEntryThatEscapesTheOutputDirectoryFailsTheBuild() throws Exception {
        Map<String, String> entries = goodEntries("a.b.v1");
        entries.put(ROOT + "../../../escaped.json", "{}");
        byte[] bytes = wheel(entries);
        layout(bytes, sha256(bytes));

        BuildResult result = runner(Map.of(), "fetchEventsContract").buildAndFail();

        assertThat(result.getOutput()).contains("escapes");
        assertThat(project.resolve("escaped.json")).as("outside the output directory").doesNotExist();
    }

    // ----- the download branch --------------------------------------------------------------------------------------------

    /** Lays out a project with NO cached wheel, so the fetch must take the download branch. */
    private void layoutWithoutCache(String pinnedSha) throws IOException {
        layout(new byte[0], pinnedSha);
        Files.delete(project.resolve("build/events/wheel").resolve(WHEEL));
    }

    @Test
    void aDownloadedWheelWithTheWrongChecksumFailsTheFetchBeforeAnythingIsUnzipped() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        Path source = project.resolve("served.whl");
        Files.write(source, bytes);
        layoutWithoutCache("0".repeat(64));

        BuildResult result = runner(Map.of(), "fetchEventsContract", "-PeventsDownloadUrl=" + source.toUri()).buildAndFail();

        assertThat(result.getOutput()).contains("downloaded wheel's sha256").contains("does not match");
        assertNothingExtracted();
        assertThat(project.resolve("build/events/wheel").resolve(WHEEL)).as("a refused download is not cached").doesNotExist();
    }

    @Test
    void aDownloadedWheelWithTheRightChecksumIsCachedAndExtracted() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        Path source = project.resolve("served.whl");
        Files.write(source, bytes);
        layoutWithoutCache(sha256(bytes));

        runner(Map.of(), "fetchEventsContract", "-PeventsDownloadUrl=" + source.toUri()).build();

        assertThat(extracted().resolve("composed/rows/a.b.v1.row.schema.json")).exists();
        assertThat(project.resolve("build/events/wheel").resolve(WHEEL)).exists();
    }

    @Test
    void theDownloadOverrideIsRefusedWhenCiIsSet() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        Path source = project.resolve("served.whl");
        Files.write(source, bytes);
        layoutWithoutCache(sha256(bytes));

        BuildResult result = runner(Map.of("CI", "true"), "fetchEventsContract", "-PeventsDownloadUrl=" + source.toUri()).buildAndFail();

        assertThat(result.getOutput()).contains("eventsDownloadUrl").contains("CI");
        assertNothingExtracted();
    }

    // ----- the development wheel ------------------------------------------------------------------------------------------

    @Test
    void theDevelopmentWheelOptionIsRefusedWhenCiIsSet() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        layout(bytes, sha256(bytes));
        Path dev = project.resolve("dev.whl");
        Files.write(dev, bytes);

        BuildResult result = runner(Map.of("CI", "true"), "fetchEventsContract", "-PeventsWheel=" + dev).buildAndFail();

        assertThat(result.getOutput()).contains("eventsWheel").contains("CI");
        assertNothingExtracted();
    }

    @Test
    void theDevelopmentWheelOptionIsTakenWithoutCiAndSaysSo() throws Exception {
        byte[] pinnedBytes = wheel(goodEntries("a.b.v1"));
        layout(pinnedBytes, "0".repeat(64));
        Path dev = project.resolve("dev.whl");
        Files.write(dev, wheel(goodEntries("e.f.v1")));

        BuildResult result = runner(Map.of(), "fetchEventsContract", "-PeventsWheel=" + dev).build();

        assertThat(result.getOutput()).contains("development wheel");
        assertThat(extracted().resolve("composed/rows/e.f.v1.row.schema.json")).exists();
    }

    // ----- the wiring -----------------------------------------------------------------------------------------------------

    @Test
    void processResourcesAndTestAreWiredBehindTheFetchTask() throws Exception {
        byte[] bytes = wheel(goodEntries("a.b.v1"));
        layout(bytes, sha256(bytes));

        BuildResult resources = runner(Map.of(), "processResources", "--dry-run").build();
        BuildResult tests = runner(Map.of(), "test", "--dry-run").build();

        assertThat(resources.getOutput()).contains(":fetchEventsContract SKIPPED");
        assertThat(tests.getOutput()).contains(":fetchEventsContract SKIPPED");
    }
}
