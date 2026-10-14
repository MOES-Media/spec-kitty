package ai.speckitty.producer.domain.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttributeNameTest {

    @Test
    void lowerCaseLettersAndDigitsAreValid() {
        for (String name : new String[] {"id", "specversion", "lamportclock", "datacontenttype", "a", "0", "a1b2", "9"}) {
            assertThat(AttributeName.isValid(name)).as(name).isTrue();
        }
    }

    @Test
    void theLengthLimitIsTwentyOnTheLimitAcceptedAndOneOverRefused() {
        assertThat(AttributeName.MAX_LENGTH).isEqualTo(20);
        assertThat(AttributeName.isValid("a".repeat(20))).isTrue();
        assertThat(AttributeName.isValid("a".repeat(21))).isFalse();
        assertThat(AttributeName.isValid("")).isFalse();
    }

    @Test
    void everythingElseIsRefused() {
        for (String name : new String[] {
            "Id", "mission_id", "mission-id", "missionId", "mission.id", " id", "id ", "id\n", "i d", "é", "ｉｄ", "_", "-", null
        }) {
            assertThat(AttributeName.isValid(name)).as("%s", name).isFalse();
        }
    }

    @Test
    void theNamesOfTheEnvelopeTableAllPass() {
        for (String name : new String[] {
            "specversion", "id", "source", "type", "subject", "time", "datacontenttype", "dataschema", "schemaversion", "missionid",
            "missionslug", "actor", "executionmode", "lamportclock", "nodeid"
        }) {
            assertThat(AttributeName.isValid(name)).as(name).isTrue();
        }
    }
}
