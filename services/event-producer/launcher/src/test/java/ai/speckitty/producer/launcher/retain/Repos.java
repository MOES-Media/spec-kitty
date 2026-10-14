package ai.speckitty.producer.launcher.retain;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Temporary repositories for the retain tests, and the processes that run in them. */
final class Repos {

    static final String MISSION = "01J0000000000000000000MSN1";
    static final String OPEN_MISSION = "01J0000000000000000000MSN2";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";
    static final String OLD = "2026-01-01T00:00:00+00:00";

    private Repos() {}

    /** A primary checkout built by hand: a {@code .git} directory, a config and an empty {@code .kittify}. */
    static Path repo(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        write(root, ".kittify/config.yaml", "project:\n  uuid: " + PROJECT + "\n  slug: demo\n");
        return root;
    }

    static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static String publishRow(String id, String time) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"source\":\"/spec-kitty/missions/" + MISSION + "\",\"time\":\"" + time + "\"}\n";
    }

    /** A Mission directory whose work packages are all done, or one with a work package still in progress. */
    static void mission(Path root, String dirName, String missionId, boolean open) throws IOException {
        write(root, "kitty-specs/" + dirName + "/meta.json", "{\"mission_id\": \"" + missionId + "\"}\n");
        String done = "{\"at\": \"2026-01-02T00:00:00+00:00\", \"to_lane\": \"done\", \"wp_id\": \"WP01\"}\n";
        String inProgress = "{\"at\": \"2026-01-03T00:00:00+00:00\", \"to_lane\": \"in_progress\", \"wp_id\": \"WP01\"}\n";
        write(root, "kitty-specs/" + dirName + "/status.events.jsonl", open ? done + inProgress : done);
    }

    static RuntimeException unchecked(Exception e) {
        return new IllegalStateException(e);
    }

    /** Every regular file under {@code root} (not under .git) with its bytes and time, to prove a tree was not touched. */
    static List<String> fingerprint(Path root, String under) throws IOException {
        Path start = root.resolve(under);
        if (!Files.exists(start)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(start)) {
            List<String> prints = new ArrayList<>();
            for (Path file : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                prints.add(root.relativize(file) + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
            return prints;
        }
    }

    static void age(Path file, String instant) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(instant)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        ParseResult<Map<String, Object>> parsed = Json.parseObject(text.strip());
        if (!(parsed instanceof ParseResult.Accepted)) {
            throw new AssertionError("not a JSON object: " + text);
        }
        return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
    }

    static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        String nothing = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null";
        builder.environment().put("GIT_CONFIG_GLOBAL", nothing);
        builder.environment().put("GIT_CONFIG_SYSTEM", nothing);
        builder.environment().put("GIT_CEILING_DIRECTORIES", cwd.getParent().toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " failed: " + output);
        }
    }

    static boolean gitAvailable(Path dir) {
        try {
            Process process = new ProcessBuilder("git", "--version").directory(dir.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
