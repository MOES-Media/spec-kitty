package ai.speckitty.producer.application.port;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventIdForms;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The gitignored local publish log (OD-SINK): one UTF-8 JSON object per line, LF only, no BOM, the producer its single writer.
 * The methods of this port are not themselves exclusive; {@link ClockStore#stampAndAppend} holds the lock around them.
 */
public interface PublishLog {

    /** The state of one file before an append: whether the event is already there, and whether the file ends in a torn line. */
    record Probe(boolean duplicate, TornLine torn) {}

    /**
     * A final line without a trailing LF, left in place (FR-021).
     *
     * @param line the 1-based position of the torn line
     */
    record TornLine(int line) {
        public TornLine {
            if (line < 1) {
                throw new IllegalArgumentException("a line position starts at 1");
            }
        }
    }

    /** Which file receives a row: one per Mission, or the project file. */
    record Target(Kind kind, String id) {

        /** The kind of file. */
        public enum Kind {
            MISSION,
            PROJECT
        }

        public Target {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            boolean valid = kind == Kind.MISSION
                    ? EventIdForms.isUlid(id) && id.equals(id.toUpperCase(Locale.ROOT))
                    : EventIdForms.isHyphenatedUuid(id) && id.equals(id.toLowerCase(Locale.ROOT));
            if (!valid) {
                throw new IllegalArgumentException("a target id is an upper-case ULID or a lower-case hyphenated UUID");
            }
        }

        /** The file under the canonical root, relative, with {@code /} separators. */
        public RelativePath relativePath() {
            return new RelativePath(kind == Kind.MISSION
                    ? ".kittify/events/publish/missions/" + id + ".jsonl"
                    : ".kittify/events/publish/project-" + id + ".jsonl");
        }
    }

    /** Reads {@code target} for a row with this {@code (source, id)}: a duplicate is found, a torn final line is reported. */
    Probe probe(RepoLayout layout, Target target, String source, String id);

    /**
     * Appends {@code line} (no LF in it) and a LF, starting on a new line when the file ends in a torn one, and syncs the file.
     * Refused as {@code rejected} when the line holds a raw CR or LF, a BOM or no JSON object.
     */
    ParseResult<Boolean> append(RepoLayout layout, Target target, String line);

    /**
     * The highest {@code lamportclock} readable across every publish-log file of the repository, skipping torn fragments and lines
     * that are not a JSON object with an integer clock; empty when there is none.
     */
    OptionalInt highestClock(RepoLayout layout);
}
