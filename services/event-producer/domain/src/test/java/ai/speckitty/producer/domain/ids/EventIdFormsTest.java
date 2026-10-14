package ai.speckitty.producer.domain.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventIdFormsTest {

    @Test
    void aUlidIsUpperCased() {
        assertThat(EventIdForms.normalizeForOrder("01j00000000000000000000abc")).contains("01J00000000000000000000ABC");
        assertThat(EventIdForms.normalizeForOrder("01J00000000000000000000ABC")).contains("01J00000000000000000000ABC");
    }

    @Test
    void aHyphenatedUuidIsLowerCased() {
        assertThat(EventIdForms.normalizeForOrder("0190A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B"))
                .contains("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    }

    @Test
    void aBareHexUuidIsHyphenatedAndLowerCased() {
        assertThat(EventIdForms.normalizeForOrder("0190A1B2C3D47E5F8A9B0C1D2E3F4A5B"))
                .contains("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    }

    @Test
    void everythingElseIsNotNormalizable() {
        for (String text : new String[] {
            null, "", "nope", "01J00000000000000000000ABI", "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5", "0190a1b2c3d47e5f8a9b0c1d2e3f4a5",
            "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5g", "0190a1b2c3d47e5f8a9b0c1d2e3f4a5g", "01J00000000000000000000ABC\n",
            "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b\n"
        }) {
            assertThat(EventIdForms.normalizeForOrder(text)).as("%s", text).isEqualTo(Optional.empty());
        }
    }

    @Test
    void aMisplacedHyphenIsNotAUuid() {
        assertThat(EventIdForms.normalizeForOrder("0190a1b2c-3d4-7e5f-8a9b-0c1d2e3f4a5b")).isEmpty();
        assertThat(EventIdForms.normalizeForOrder("0190a1b2-c3d4-7e5f-8a9b0-c1d2e3f4a5b")).isEmpty();
    }
}
