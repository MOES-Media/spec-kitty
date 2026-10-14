package ai.speckitty.producer.adapter.publishlog;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rows and layouts shared by the publish-log and clock tests. */
public final class PublishLogFixtures {

    public static final String MISSION = "01JAAAA0000000000000000ABD";
    public static final String SOURCE = "/spec-kitty/missions/" + MISSION;
    public static final String PROJECT = "0190a1b2-0000-4000-8000-000000000001";
    public static final String NODE = "abcdef012345";

    private PublishLogFixtures() {}

    /** A layout whose common dir is a plain directory under {@code root}; enough for the file-level classes. */
    public static RepoLayout layout(Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        return new RepoLayout(root, root.resolve(".git"));
    }

    public static PublishLog.Target mission() {
        return new PublishLog.Target(PublishLog.Target.Kind.MISSION, MISSION);
    }

    public static PublishLog.Target project() {
        return new PublishLog.Target(PublishLog.Target.Kind.PROJECT, PROJECT);
    }

    public static Path file(RepoLayout layout, PublishLog.Target target) {
        return layout.canonicalRoot().resolve(target.relativePath().value());
    }

    /** A complete envelope-shaped row as the line text. */
    public static String line(String id, int clock) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("specversion", "1.0");
        row.put("id", id);
        row.put("source", SOURCE);
        row.put("type", "ai.spec-kitty.lane.transitioned.v1");
        row.put("lamportclock", new JsonNumber(Integer.toString(clock)));
        row.put("nodeid", NODE);
        return Json.render(row);
    }

    /** The id {@code 01JAAAA0000000000000000A..} with the last two characters from {@code n} (0..99), a valid ULID. */
    public static String ulid(int n) {
        return String.format("01JAAAA00000000000000000%02d", n);
    }
}
