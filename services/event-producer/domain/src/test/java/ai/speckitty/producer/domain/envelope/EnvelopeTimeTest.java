package ai.speckitty.producer.domain.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.Results;
import org.junit.jupiter.api.Test;

class EnvelopeTimeTest {

    @Test
    void theRowsOwnTimestampTextIsKeptVerbatim() {
        for (String text : new String[] {
            "2026-10-13T10:00:00Z", "2026-10-13T10:00:00.123456+00:00", "2026-10-13T12:00:00+02:00", "2026-10-13T10:00:00.000000001Z"
        }) {
            assertThat(Results.accepted(EnvelopeTime.fromRow(text))).isEqualTo(text);
        }
    }

    @Test
    void anUnparseableOrAbsentTimestampIsRejectedAndNeverReplaced() {
        for (Object value : new Object[] {null, "", "yesterday", "2026-10-13", "2026-10-13 10:00:00Z", 5, true}) {
            assertThat(Results.refusedReason(EnvelopeTime.fromRow(value))).as("%s", value).isEqualTo(FaultReason.REJECTED);
        }
    }
}
