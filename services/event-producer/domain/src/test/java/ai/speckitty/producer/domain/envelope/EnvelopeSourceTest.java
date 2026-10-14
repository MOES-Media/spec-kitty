package ai.speckitty.producer.domain.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.Results;
import org.junit.jupiter.api.Test;

class EnvelopeSourceTest {

    private static final String MISSION = "01J0000000000000000000MSN1";
    private static final String OTHER = "01J0000000000000000000MSN2";
    private static final String PROJECT = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";

    @Test
    void theRowsMissionIdBuildsTheMissionSource() {
        EnvelopeSource.Resolved resolved = Results.accepted(EnvelopeSource.resolve(MISSION, OTHER, PROJECT));
        assertThat(resolved.source()).isEqualTo("/spec-kitty/missions/" + MISSION);
        assertThat(resolved.missionId()).isEqualTo(MISSION);
        assertThat(resolved.subjectIsAggregate()).isFalse();
    }

    @Test
    void metaJsonSuppliesTheMissionIdWhenTheRowHasNone() {
        for (Object row : new Object[] {null, ""}) {
            EnvelopeSource.Resolved resolved = Results.accepted(EnvelopeSource.resolve(row, OTHER, PROJECT));
            assertThat(resolved.source()).isEqualTo("/spec-kitty/missions/" + OTHER);
            assertThat(resolved.missionId()).isEqualTo(OTHER);
            assertThat(resolved.subjectIsAggregate()).isFalse();
        }
    }

    @Test
    void aLowerCaseMissionIdIsWrittenUpperCase() {
        assertThat(Results.accepted(EnvelopeSource.resolve(MISSION.toLowerCase(), null, PROJECT)).missionId()).isEqualTo(MISSION);
    }

    @Test
    void withNeitherTheProjectSourceAppliesAndNothingIsRefused() {
        for (Object row : new Object[] {null, ""}) {
            for (Object meta : new Object[] {null, ""}) {
                EnvelopeSource.Resolved resolved = Results.accepted(EnvelopeSource.resolve(row, meta, PROJECT));
                assertThat(resolved.source()).isEqualTo("/spec-kitty/projects/" + PROJECT);
                assertThat(resolved.missionId()).isNull();
                assertThat(resolved.subjectIsAggregate()).isTrue();
            }
        }
    }

    @Test
    void aSlugIsNeverPresentedAsAMissionId() {
        EnvelopeSource.Resolved viaMeta = Results.accepted(EnvelopeSource.resolve("my-feature-01M4VDY9", OTHER, PROJECT));
        assertThat(viaMeta.missionId()).isEqualTo(OTHER);
        EnvelopeSource.Resolved fallback = Results.accepted(EnvelopeSource.resolve("my-feature-01M4VDY9", "another-slug", PROJECT));
        assertThat(fallback.missionId()).isNull();
        assertThat(fallback.source()).startsWith("/spec-kitty/projects/");
        assertThat(Results.accepted(EnvelopeSource.resolve(42, true, PROJECT)).missionId()).isNull();
    }

    @Test
    void theProjectUuidMustBeAHyphenatedUuidAndIsWrittenLowerCase() {
        assertThat(Results.accepted(EnvelopeSource.resolve(null, null, PROJECT.toUpperCase())).source()).isEqualTo("/spec-kitty/projects/" + PROJECT);
        for (String bad : new String[] {null, "", "nope", PROJECT.replace("-", ""), PROJECT + "0", "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5g"}) {
            assertThat(Results.refusedReason(EnvelopeSource.resolve(null, null, bad))).as("%s", bad).isEqualTo(FaultReason.REJECTED);
        }
    }

    @Test
    void theProjectUuidIsNotNeededWhenAMissionIdIsKnown() {
        assertThat(Results.accepted(EnvelopeSource.resolve(MISSION, null, null)).source()).isEqualTo("/spec-kitty/missions/" + MISSION);
    }

    @Test
    void sourcesAreValidatedByShape() {
        assertThat(EnvelopeSource.isValid("/spec-kitty/missions/" + MISSION)).isTrue();
        assertThat(EnvelopeSource.isValid("/spec-kitty/projects/" + PROJECT)).isTrue();
        for (String bad : new String[] {
            null, "", "/spec-kitty/missions/", "/spec-kitty/missions/slug", "/spec-kitty/missions/" + MISSION + "/x", "/spec-kitty/projects/x",
            "spec-kitty/missions/" + MISSION, "/spec-kitty/other/" + MISSION, "/spec-kitty/missions/" + MISSION.toLowerCase()
        }) {
            assertThat(EnvelopeSource.isValid(bad)).as("%s", bad).isFalse();
        }
    }
}
