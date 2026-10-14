package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventId;
import ai.speckitty.producer.domain.ids.EventIdForms;
import java.util.Locale;
import java.util.Optional;

/** The {@code source} rule (FR-009, OD-MISSIONID): a Mission source, else the project fallback. */
public final class EnvelopeSource {

    public static final String MISSION_PREFIX = "/spec-kitty/missions/";
    public static final String PROJECT_PREFIX = "/spec-kitty/projects/";

    private EnvelopeSource() {}

    /**
     * @param source the source URI-reference
     * @param missionId the resolved Mission id, upper-case, or null when the project fallback applies
     * @param subjectIsAggregate true when the project fallback applies: the subject is then the aggregate
     */
    public record Resolved(String source, String missionId, boolean subjectIsAggregate) {}

    /** True for {@code /spec-kitty/missions/<ULID>} and {@code /spec-kitty/projects/<hyphenated UUID>}. */
    public static boolean isValid(String source) {
        if (source == null) {
            return false;
        }
        if (source.startsWith(MISSION_PREFIX)) {
            String id = source.substring(MISSION_PREFIX.length());
            return EventIdForms.isUlid(id) && id.equals(id.toUpperCase(Locale.ROOT));
        }
        if (source.startsWith(PROJECT_PREFIX)) {
            String uuid = source.substring(PROJECT_PREFIX.length());
            return EventIdForms.isHyphenatedUuid(uuid) && uuid.equals(uuid.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    /**
     * Resolves the source: a non-empty ULID {@code missionId} of the row wins, else the one of {@code meta.json}; a value that is
     * not a ULID (a slug) is never used. With neither, the project source applies and nothing is refused.
     */
    public static ParseResult<Resolved> resolve(Object rowMissionId, Object metaMissionId, String projectUuid) {
        Optional<String> missionId = missionId(rowMissionId).or(() -> missionId(metaMissionId));
        if (missionId.isPresent()) {
            return new ParseResult.Accepted<>(new Resolved(MISSION_PREFIX + missionId.get(), missionId.get(), false));
        }
        if (projectUuid == null || !EventIdForms.isHyphenatedUuid(projectUuid)) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, "project uuid is absent or not a hyphenated UUID");
        }
        return new ParseResult.Accepted<>(new Resolved(PROJECT_PREFIX + projectUuid.toLowerCase(Locale.ROOT), null, true));
    }

    private static Optional<String> missionId(Object candidate) {
        if (candidate instanceof String text && !text.isEmpty() && EventId.parseUlid(text) instanceof ParseResult.Accepted<EventId> ok) {
            return Optional.of(ok.value().value());
        }
        return Optional.empty();
    }
}
