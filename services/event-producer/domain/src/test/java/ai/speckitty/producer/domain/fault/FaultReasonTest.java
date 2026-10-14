package ai.speckitty.producer.domain.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FaultReasonTest {

    private static final List<String> VOCABULARY = List.of(
            "binary-missing", "crash", "timeout", "malformed-reply", "clock-overflow", "rejected", "busy");

    @Test
    void theVocabularyIsExactlyTheSevenClassesInOrder() {
        List<String> wire = Arrays.stream(FaultReason.values()).map(FaultReason::wire).collect(Collectors.toList());
        assertThat(wire).containsExactlyElementsOf(VOCABULARY);
    }

    @Test
    void thereIsNoDisabledClass() {
        assertThat(Arrays.stream(FaultReason.values()).map(FaultReason::wire)).doesNotContain("disabled");
        assertThat(FaultReason.fromWire("disabled")).isEmpty();
    }

    @Test
    void everyWireNameRoundTrips() {
        for (FaultReason reason : FaultReason.values()) {
            assertThat(FaultReason.fromWire(reason.wire())).contains(reason);
        }
    }

    @Test
    void lookupIsExactAndNullSafe() {
        assertThat(FaultReason.fromWire("Crash")).isEqualTo(Optional.empty());
        assertThat(FaultReason.fromWire(" crash")).isEmpty();
        assertThat(FaultReason.fromWire("")).isEmpty();
        assertThat(FaultReason.fromWire(null)).isEmpty();
    }

    @Test
    void aRefusalDetailIsOneLine() {
        assertThat(new ParseResult.Refused<String>(FaultReason.REJECTED, "one line").isAccepted()).isFalse();
        assertThat(new ParseResult.Accepted<>("v").isAccepted()).isTrue();
        assertThatThrownBy(() -> new ParseResult.Refused<String>(FaultReason.REJECTED, "two\nlines"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseResult.Refused<String>(FaultReason.REJECTED, "two\rlines"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseResult.Accepted<String>(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParseResult.Refused<String>(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParseResult.Refused<String>(FaultReason.CRASH, null))
                .isInstanceOf(NullPointerException.class);
    }
}
