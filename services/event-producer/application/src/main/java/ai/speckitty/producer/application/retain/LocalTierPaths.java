package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.domain.ids.EventIdForms;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed list of paths retention may ever touch (FR-028, FR-031). A path that matches none of the shapes is not a candidate:
 * nothing under {@code kitty-specs/}, no committed ledger, no clock state and no other file of the repository can be listed,
 * inspected or deleted, whoever offers it.
 */
public final class LocalTierPaths {

    public static final String MISSIONS_DIR = ".kittify/events/publish/missions";
    public static final String PUBLISH_DIR = ".kittify/events/publish";
    public static final String FAULTS_DIR = ".kittify/events/faults";
    public static final String RUNS_DIR = ".kittify/runtime/runs";
    public static final String JOURNAL_FILE = "run.events.jsonl";
    public static final String RUN_LOCK_FILE = "state.json.lock";

    private static final String EXTENSION = ".jsonl";
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private LocalTierPaths() {}

    /**
     * A recognised local-tier path.
     *
     * @param kind which kind of file it is
     * @param id the Mission id of a Mission publish log, the run id of a journal, else null
     */
    public record Shape(LocalKind kind, String id) {}

    /** The shape of {@code path}, or empty when it is not a local-tier candidate. */
    public static Optional<Shape> classify(RelativePath path) {
        String value = path.value();
        String name = value.substring(value.lastIndexOf('/') + 1);
        if (value.equals(MISSIONS_DIR + "/" + name)) {
            String id = stem(name);
            return id != null && EventIdForms.isUlid(id) && id.equals(id.toUpperCase(Locale.ROOT))
                    ? Optional.of(new Shape(LocalKind.PUBLISH_MISSION, id))
                    : Optional.empty();
        }
        if (value.equals(PUBLISH_DIR + "/" + name) && name.startsWith("project-")) {
            String id = stem(name.substring("project-".length()));
            return id != null && EventIdForms.isHyphenatedUuid(id) && id.equals(id.toLowerCase(Locale.ROOT))
                    ? Optional.of(new Shape(LocalKind.PUBLISH_PROJECT, null))
                    : Optional.empty();
        }
        if (value.equals(FAULTS_DIR + "/" + name)) {
            return faultDay(name) != null ? Optional.of(new Shape(LocalKind.FAULT_DAY, null)) : Optional.empty();
        }
        String prefix = RUNS_DIR + "/";
        if (value.startsWith(prefix) && value.endsWith("/" + JOURNAL_FILE)) {
            String runId = value.substring(prefix.length(), value.length() - JOURNAL_FILE.length() - 1);
            return RUN_ID.matcher(runId).matches() ? Optional.of(new Shape(LocalKind.RUN_JOURNAL, runId)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The UTC date a fault-log file name carries, else null. */
    public static LocalDate faultDay(String fileName) {
        String stem = stem(fileName);
        if (stem == null || stem.length() != 10) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(stem);
            return day.toString().equals(stem) ? day : null;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String stem(String fileName) {
        return fileName.endsWith(EXTENSION) ? fileName.substring(0, fileName.length() - EXTENSION.length()) : null;
    }

    /** The lock sidecar of a run journal, relative to the repository. */
    public static RelativePath runLockOf(String runId) {
        return new RelativePath(RUNS_DIR + "/" + runId + "/" + RUN_LOCK_FILE);
    }
}
