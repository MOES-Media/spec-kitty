package ai.speckitty.producer.adapter.schema.released;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.schema.ContractRegistry;
import ai.speckitty.producer.adapter.schema.ContractSource;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The released spec-kitty-events producer contract, extracted by the build into the directory the test task points at. */
final class Released {

    /** The 27 types of data-model section 3, as the registry names them. The loops below must iterate every one. */
    static final List<String> SECTION_3_TYPES = List.of(
            "ai.spec-kitty.lane.transitioned.v1",
            "ai.spec-kitty.wp.innerstate.changed.v1",
            "ai.spec-kitty.project.initialized.v1",
            "ai.spec-kitty.mission.created.v1",
            "ai.spec-kitty.mission.specify.started.v1",
            "ai.spec-kitty.mission.plan.started.v1",
            "ai.spec-kitty.mission.tasks.started.v1",
            "ai.spec-kitty.mission.specify.completed.v1",
            "ai.spec-kitty.mission.plan.completed.v1",
            "ai.spec-kitty.mission.tasks.completed.v1",
            "ai.spec-kitty.wp.created.v1",
            "ai.spec-kitty.wp.reviewer.selfapproved.v1",
            "ai.spec-kitty.mission.reopened.v1",
            "ai.spec-kitty.mission.followup.recorded.v1",
            "ai.spec-kitty.decision.opened.v1",
            "ai.spec-kitty.decision.resolved.v1",
            "ai.spec-kitty.run.started.v1",
            "ai.spec-kitty.run.step.issued.v1",
            "ai.spec-kitty.run.step.autocompleted.v1",
            "ai.spec-kitty.run.decision.requested.v1",
            "ai.spec-kitty.run.decision.answered.v1",
            "ai.spec-kitty.run.completed.v1",
            "ai.spec-kitty.run.significance.evaluated.v1",
            "ai.spec-kitty.run.decision.denied.v1",
            "ai.spec-kitty.retrospective.captured.v1",
            "ai.spec-kitty.retrospective.capturefailed.v1",
            "ai.spec-kitty.retrospective.skipped.v1");

    private Released() {}

    /** The extracted contract directory; absent property or an empty directory fails the test rather than skipping it. */
    static Path dir() {
        String property = System.getProperty("events.contract.dir");
        assertThat(property).as("-Devents.contract.dir, set by gradle/events-fetch.gradle.kts").isNotBlank();
        Path dir = Path.of(property);
        assertThat(dir.resolve("registry.json")).as("the fetched registry").isRegularFile();
        return dir;
    }

    static ContractSource source() {
        return ContractSource.directory(dir());
    }

    static ContractRegistry registry() {
        return ContractRegistry.load(source());
    }

    /** One fixture file: its path and parsed content. */
    record Fixture(Path path, Map<String, Object> content) {
        String name() {
            return path.getFileName().toString();
        }

        String type() {
            return (String) content.get("type");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input() {
            return (Map<String, Object>) content.get("input");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> legacyRow() {
            return (Map<String, Object>) content.get("legacy_row");
        }
    }

    /** The fixtures of {@code rows/<kind>/}, in name order. */
    static List<Fixture> fixtures(String kind) {
        Path folder = dir().resolve("fixtures/rows").resolve(kind);
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().map(p -> new Fixture(p, json(p))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(Path file) {
        try {
            ParseResult<Map<String, Object>> parsed = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(parsed).as(file.getFileName().toString()).isInstanceOf(ParseResult.Accepted.class);
            return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
