package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.ParseResult;

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
        throw new UnsupportedOperationException("red stub");
    }

    /**
     * Resolves the source: a non-empty ULID {@code missionId} of the row wins, else the one of {@code meta.json}; a value that is
     * not a ULID (a slug) is never used. With neither, the project source applies and nothing is refused.
     */
    public static ParseResult<Resolved> resolve(Object rowMissionId, Object metaMissionId, String projectUuid) {
        throw new UnsupportedOperationException("red stub");
    }
}
