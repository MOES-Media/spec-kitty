package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FailClosedValidatorTest {

    @Test
    void itRefusesEveryDocumentWithTheNamedViolation() {
        FailClosedValidator validator = new FailClosedValidator();
        for (Map<String, Object> document : new Map[] {obj(), obj("specversion", "1.0", "data", obj()), obj("anything", "at all")}) {
            assertThat(validator.violation(document)).contains(Produce.VALIDATOR_UNAVAILABLE_VIOLATION);
        }
    }

    @Test
    void theSentinelIsTheWireReasonNameSoItCannotDriftFromTheVocabulary() {
        assertThat(Produce.VALIDATOR_UNAVAILABLE_VIOLATION)
                .isEqualTo(ai.speckitty.producer.domain.protocol.RefusalReason.VALIDATOR_UNAVAILABLE.wire());
    }
}
