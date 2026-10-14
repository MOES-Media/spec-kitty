package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Rfc3339Test {

    private static Instant parsed(String text) {
        return Rfc3339.parse(text).orElseThrow(() -> new AssertionError("expected to parse: " + text));
    }

    private static void refused(Object value) {
        assertThat(Rfc3339.parse(value)).as("%s", value).isEmpty();
    }

    @Test
    void anOffsetIsAppliedExactly() {
        assertThat(parsed("2026-10-13T12:00:00+02:00")).isEqualTo(parsed("2026-10-13T10:00:00Z"));
        assertThat(parsed("2026-10-13T10:00:00-05:30")).isEqualTo(parsed("2026-10-13T15:30:00Z"));
        assertThat(parsed("2026-10-13T10:00:00+00:00")).isEqualTo(parsed("2026-10-13T10:00:00Z"));
        assertThat(parsed("2026-10-13T10:00:00-00:00")).isEqualTo(parsed("2026-10-13T10:00:00Z"));
    }

    @Test
    void theOffsetBoundsAreAcceptedAndOneBeyondIsRefused() {
        assertThat(parsed("2026-10-13T10:00:00+23:59")).isEqualTo(parsed("2026-10-12T10:01:00Z"));
        assertThat(parsed("2026-10-13T10:00:00-23:59")).isEqualTo(parsed("2026-10-14T09:59:00Z"));
        refused("2026-10-13T10:00:00+24:00");
        refused("2026-10-13T10:00:00+00:60");
        refused("2026-10-13T10:00:00-24:00");
    }

    @Test
    void fractionsKeepEveryNanosecondUpToNineDigits() {
        assertThat(parsed("2026-10-13T10:00:00.5Z")).isEqualTo(Instant.parse("2026-10-13T10:00:00.500000000Z"));
        assertThat(parsed("2026-10-13T10:00:00.000000001Z")).isEqualTo(Instant.parse("2026-10-13T10:00:00.000000001Z"));
        assertThat(parsed("2026-10-13T10:00:00.123456789Z").getNano()).isEqualTo(123_456_789);
        assertThat(parsed("2026-10-13T10:00:00.000000001Z")).isAfter(parsed("2026-10-13T10:00:00Z"));
        refused("2026-10-13T10:00:00.0000000001Z");
        refused("2026-10-13T10:00:00.Z");
    }

    @Test
    void aLeapSecondIsTheFollowingSecond() {
        assertThat(parsed("2026-06-30T23:59:60Z")).isEqualTo(parsed("2026-07-01T00:00:00Z"));
        assertThat(parsed("2026-06-30T23:59:60.5Z")).isEqualTo(parsed("2026-07-01T00:00:00.5Z"));
        assertThat(parsed("2026-06-30T23:59:60+02:00")).isEqualTo(parsed("2026-06-30T22:00:00Z"));
        refused("2026-06-30T23:59:61Z");
    }

    @Test
    void lowerCaseSeparatorAndZuluAreAccepted() {
        assertThat(parsed("2026-07-01t00:00:00z")).isEqualTo(parsed("2026-07-01T00:00:00Z"));
    }

    @Test
    void theCalendarIsChecked() {
        assertThat(parsed("2028-02-29T00:00:00Z")).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        refused("2026-02-29T00:00:00Z");
        refused("2026-13-01T00:00:00Z");
        refused("2026-00-01T00:00:00Z");
        refused("2026-01-00T00:00:00Z");
        refused("2026-04-31T00:00:00Z");
        refused("2026-01-32T00:00:00Z");
    }

    @Test
    void theClockFieldsAreChecked() {
        assertThat(parsed("2026-07-01T23:59:59Z")).isEqualTo(Instant.parse("2026-07-01T23:59:59Z"));
        refused("2026-07-01T24:00:00Z");
        refused("2026-07-01T00:60:00Z");
    }

    @Test
    void theYearRangeIsOneToNineNineNineNine() {
        assertThat(parsed("0001-01-01T00:00:00Z")).isEqualTo(Instant.parse("0001-01-01T00:00:00Z"));
        assertThat(parsed("9999-12-31T23:59:59Z")).isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
        assertThat(parsed("9999-12-31T23:59:60Z")).isEqualTo(Instant.parse("+10000-01-01T00:00:00Z"));
        refused("0000-01-01T00:00:00Z");
    }

    @Test
    void everyOtherShapeIsRefused() {
        for (String text : List.of(
                "", "yesterday", "2026-07-01", "2026-07-01T00:00:00", "2026-07-01 00:00:00Z", "20260701T000000Z",
                "2026-07-01T00:00:00Z\n", " 2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z ", "2026-07-01T00:00Z",
                "2026-07-01T00:00:00+0200", "2026-07-01T00:00:00+02", "2026-7-1T00:00:00Z", "2026-07-01T0:00:00Z",
                "2026-07-01T00:00:00ZZ", "+2026-07-01T00:00:00Z", "2026-07-01T00:00:00,5Z", "2026-07-01T00:00:00.5")) {
            refused(text);
        }
    }

    @Test
    void nonAsciiDigitsAreRefused() {
        String fullWidthTwo = new String(Character.toChars(0xFF12));
        refused("202" + fullWidthTwo + "-07-01T00:00:00Z");
        refused("2026-07-01T00:00:00+0" + fullWidthTwo + ":00");
    }

    @Test
    void whatIsNotAStringIsRefused() {
        refused(null);
        refused(5);
        refused(true);
        refused(List.of("2026-07-01T00:00:00Z"));
        refused(Map.of());
    }
}
