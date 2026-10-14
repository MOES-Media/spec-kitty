package ai.speckitty.producer.adapter.clock;

import ai.speckitty.producer.application.port.Clock;
import java.time.Instant;

/** The wall clock. */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
