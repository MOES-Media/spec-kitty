package ai.speckitty.producer.adapter.clock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SystemClockTest {

    @Test
    void nowIsBetweenTwoReadingsOfTheSystemClock() {
        Instant before = Instant.now();
        Instant read = new SystemClock().now();
        Instant after = Instant.now();
        assertThat(read).isBetween(before, after);
    }
}
