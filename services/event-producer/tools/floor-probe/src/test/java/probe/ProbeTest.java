package probe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProbeTest {
    @Test
    void doubles() {
        assertEquals(42, Probe.twice(21));
    }
}
