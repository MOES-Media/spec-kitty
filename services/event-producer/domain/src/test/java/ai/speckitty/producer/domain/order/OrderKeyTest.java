package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderKeyTest {

    private static final Instant T = Instant.parse("2026-10-13T10:00:00Z");

    private static OrderKey key(int h, int c, Instant t, String i, String s) {
        return new OrderKey(h, c, t, i, s);
    }

    @Test
    void clocklessSortsBeforeClockedWhateverElseIs() {
        assertThat(key(0, 0, T.plusSeconds(1000), "Z", "z").compareTo(key(1, 0, T, "A", ""))).isNegative();
    }

    @Test
    void theClockComesBeforeTheTime() {
        assertThat(key(1, 4, T.plusSeconds(1000), "Z", "z").compareTo(key(1, 5, T, "A", ""))).isNegative();
    }

    @Test
    void theTimeComesBeforeTheId() {
        assertThat(key(1, 5, T, "Z", "z").compareTo(key(1, 5, T.plusNanos(1), "A", ""))).isNegative();
    }

    @Test
    void theIdComesBeforeTheSource() {
        assertThat(key(1, 5, T, "A", "z").compareTo(key(1, 5, T, "B", ""))).isNegative();
    }

    @Test
    void theSourceBreaksTheLastTieByCodePoint() {
        assertThat(key(1, 5, T, "A", "").compareTo(key(1, 5, T, "A", "/spec-kitty/missions/M1"))).isNegative();
        assertThat(key(1, 5, T, "A", "/spec-kitty/missions/M1").compareTo(key(1, 5, T, "A", "/spec-kitty/projects/P1"))).isNegative();
        assertThat(key(1, 5, T, "A", "/s/" + new String(Character.toChars(0xFFFF)))
                        .compareTo(key(1, 5, T, "A", "/s/" + new String(Character.toChars(0x10000)))))
                .isNegative();
    }

    @Test
    void equalKeysCompareEqual() {
        assertThat(key(1, 5, T, "A", "s").compareTo(key(1, 5, T, "A", "s"))).isZero();
    }

    @Test
    void comparisonIsAntisymmetric() {
        OrderKey a = key(1, 5, T, "A", "s");
        OrderKey b = key(1, 5, T, "A", "t");
        assertThat(Integer.signum(a.compareTo(b))).isEqualTo(-Integer.signum(b.compareTo(a)));
    }

    @Test
    void theRecordRefusesNullParts() {
        assertThatThrownBy(() -> key(0, 0, null, "A", "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> key(0, 0, T, null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> key(0, 0, T, "A", null)).isInstanceOf(NullPointerException.class);
    }
}
