package ai.speckitty.producer.domain.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventIdTest {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String ULID = "01J00000000000000000000ABC";

    private static String accepted(String text) {
        ParseResult<EventId> result = EventId.parseUlid(text);
        assertThat(result).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<EventId>) result).value().value();
    }

    private static ParseResult.Refused<EventId> refused(String text) {
        ParseResult<EventId> result = EventId.parseUlid(text);
        assertThat(result).as("parse of %s", text).isInstanceOf(ParseResult.Refused.class);
        return (ParseResult.Refused<EventId>) result;
    }

    @Test
    void anUpperCaseUlidIsAcceptedUnchanged() {
        assertThat(accepted(ULID)).isEqualTo(ULID);
    }

    @Test
    void aLowerOrMixedCaseUlidIsWrittenUpperCase() {
        assertThat(accepted(ULID.toLowerCase())).isEqualTo(ULID);
        assertThat(accepted("01j00000000000000000000aBc")).isEqualTo(ULID);
    }

    @Test
    void everyCrockfordCharacterIsAcceptedInEveryPosition() {
        // 26 characters taken from a rotating window of the 32-character alphabet: each character appears.
        for (int shift = 0; shift < ALPHABET.length(); shift++) {
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 26; i++) {
                id.append(ALPHABET.charAt((shift + i) % ALPHABET.length()));
            }
            assertThat(accepted(id.toString())).isEqualTo(id.toString());
        }
    }

    @Test
    void theExcludedLettersAreRefusedAtEveryPosition() {
        for (char bad : new char[] {'I', 'L', 'O', 'U', 'i', 'l', 'o', 'u'}) {
            for (int position : new int[] {0, 13, 25}) {
                StringBuilder id = new StringBuilder(ULID);
                id.setCharAt(position, bad);
                assertThat(refused(id.toString()).reason()).isEqualTo(FaultReason.REJECTED);
            }
        }
    }

    @Test
    void theLengthIsExactlyTwentySix() {
        assertThat(refused(ULID.substring(1)).reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(refused(ULID + "A").reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(refused("").reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(refused(null).reason()).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void whitespaceAndLookalikesAreRefused() {
        assertThat(refused(ULID.substring(1) + "\n").reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(refused(" " + ULID.substring(1)).reason()).isEqualTo(FaultReason.REJECTED);
        // a full-width digit zero is not an ASCII digit
        assertThat(refused(new String(Character.toChars(0xFF10)) + ULID.substring(1)).reason())
                .isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void aCharacterThatUpperCasesToAnAlphabetLetterIsStillRefused() {
        // U+017F (long s) upper-cases to S, which is in the alphabet; the id must be ASCII.
        String longS = new String(Character.toChars(0x17F));
        assertThat(refused(longS + ULID.substring(1)).reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(EventIdForms.normalizeForOrder(longS + ULID.substring(1))).isEmpty();
    }

    @Test
    void everyUuidFormIsRefusedAsRejectedAndSaysSo() {
        List<String> uuids = List.of(
                "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
                "0190A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B",
                "0190a1b2c3d47e5f8a9b0c1d2e3f4a5b",
                "0190A1B2C3D47E5F8A9B0C1D2E3F4A5B");
        for (String uuid : uuids) {
            ParseResult.Refused<EventId> refusal = refused(uuid);
            assertThat(refusal.reason()).isEqualTo(FaultReason.REJECTED);
            assertThat(refusal.detail()).contains("UUID");
        }
    }

    @Test
    void aRefusalDetailNeverEchoesTheRejectedText() {
        assertThat(refused("not an id with secret text").detail()).doesNotContain("secret");
    }

    @Test
    void parsingTheWrittenFormGivesTheSameId() {
        String once = accepted("01j00000000000000000000abc");
        assertThat(accepted(once)).isEqualTo(once);
        assertThat(new EventId(once)).isEqualTo(new EventId(ULID));
    }

    @Test
    void theRecordItselfRefusesWhatTheParserRefuses() {
        assertThatThrownBy(() -> new EventId("01j00000000000000000000abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EventId(ULID).value()).isEqualTo(ULID);
    }
}
