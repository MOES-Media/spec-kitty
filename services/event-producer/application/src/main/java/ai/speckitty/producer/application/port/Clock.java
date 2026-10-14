package ai.speckitty.producer.application.port;

import java.time.Instant;

/** The wall clock, behind a port so that no use case reads it directly. */
public interface Clock {

    /** The current instant. */
    Instant now();
}
