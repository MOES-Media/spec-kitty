package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.adapter.repo.GitFixtures;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Fixtures of the local tier: a hand-built repository and files at the paths retention knows. */
final class LocalTiers {

    static final String MISSION = "01J0000000000000000000MSN1";
    static final String OTHER_MISSION = "01J0000000000000000000MSN2";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private LocalTiers() {}

    static RepoLayout repo(Path parent) throws IOException {
        Path root = GitFixtures.handBuiltPrimary(parent, "repo");
        return new RepoLayout(root, root.resolve(".git"));
    }

    static RelativePath missionLog() {
        return new RelativePath(".kittify/events/publish/missions/" + MISSION + ".jsonl");
    }

    static RelativePath missionLog(String id) {
        return new RelativePath(".kittify/events/publish/missions/" + id + ".jsonl");
    }

    static RelativePath projectLog() {
        return new RelativePath(".kittify/events/publish/project-" + PROJECT + ".jsonl");
    }

    static RelativePath faultDay(String day) {
        return new RelativePath(".kittify/events/faults/" + day + ".jsonl");
    }

    static RelativePath journal(String runId) {
        return new RelativePath(".kittify/runtime/runs/" + runId + "/run.events.jsonl");
    }

    static Path write(RepoLayout layout, RelativePath path, String text) throws IOException {
        Path file = layout.canonicalRoot().resolve(path.value());
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static Path write(RepoLayout layout, String path, String text) throws IOException {
        return write(layout, new RelativePath(path), text);
    }

    static String publishRow(String time) {
        return "{\"specversion\":\"1.0\",\"id\":\"e\",\"source\":\"s\",\"time\":\"" + time + "\"}\n";
    }

    static String journalRow(String type, String timestamp) {
        return "{\"event_type\":\"" + type + "\",\"payload\":{},\"timestamp\":\"" + timestamp + "\"}\n";
    }
}
