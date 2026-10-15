package ai.speckitty.producer.launcher.binding;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Throw-away repositories and the released fixtures, for the binding tests. */
final class Repos {

    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";

    private Repos() {}

    /** A repository with a git marker and a project uuid, and a Mission directory under it that holds a {@code meta.json} (or none). */
    static Path repo(Path parent, Map<String, Object> meta) throws IOException {
        Path root = Files.createDirectories(parent.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".kittify"));
        Files.writeString(root.resolve(".kittify/config.yaml"), "project:\n  uuid: " + PROJECT + "\n  slug: demo\n");
        Path mission = Files.createDirectories(root.resolve("kitty-specs/m1"));
        if (!meta.isEmpty()) {
            Files.writeString(mission.resolve("meta.json"), Json.render(meta));
        }
        return root;
    }

    /** The {@code meta.json} a Mission directory holds for a released CloudEvents line: its Mission id and slug. */
    static Map<String, Object> metaOf(Map<String, Object> cloudEvent) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (cloudEvent.get("missionid") != null) {
            meta.put("mission_id", cloudEvent.get("missionid"));
        }
        if (cloudEvent.get("missionslug") != null) {
            meta.put("mission_slug", cloudEvent.get("missionslug"));
        }
        return meta;
    }

    /** The one publish-log file of the repository. */
    static Path publishLog(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root.resolve(".kittify/events/publish"))) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".jsonl")).toList();
            assertThat(files).hasSize(1);
            return files.get(0);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        ParseResult<Map<String, Object>> parsed = Json.parseObject(text);
        assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
    }

    /** One released fixture: its name, its CloudEvents line and its legacy row. */
    record Fixture(String name, Map<String, Object> input, Map<String, Object> legacyRow) {}

    /** The valid fixtures of the extracted release. */
    static List<Fixture> validFixtures() {
        String property = System.getProperty("events.contract.dir");
        assertThat(property).as("-Devents.contract.dir, set by gradle/events-fetch.gradle.kts").isNotBlank();
        Path folder = Path.of(property).resolve("fixtures/rows/valid");
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().map(p -> {
                try {
                    Map<String, Object> json = parse(Files.readString(p, StandardCharsets.UTF_8));
                    @SuppressWarnings("unchecked")
                    Fixture fixture = new Fixture(p.getFileName().toString(), (Map<String, Object>) json.get("input"), (Map<String, Object>) json.get("legacy_row"));
                    return fixture;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
