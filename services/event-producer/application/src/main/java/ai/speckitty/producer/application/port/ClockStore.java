package ai.speckitty.producer.application.port;

import ai.speckitty.producer.application.port.PublishLog.Target;
import ai.speckitty.producer.application.port.PublishLog.TornLine;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.Objects;
import java.util.Optional;

/**
 * The per-repository Lamport counter, kept under the git common directory (OD-CLOCK). Stamp and append are one operation: the
 * exclusive lock is held from reading the state to the line being durable, and the state is written first.
 */
public interface ClockStore {

    /** Builds the exact line (no LF) for a stamp. Called under the lock, so it must be pure and quick. */
    interface LineRenderer {
        String render(LamportClock clock);
    }

    /**
     * One stamp-and-append.
     *
     * @param layout the repository
     * @param nodeId the node id the state belongs to
     * @param target the publish-log file
     * @param source the event's {@code source}, for the duplicate check
     * @param id the event's normalised id, for the duplicate check
     * @param waitMillis the longest to wait for the lock, from the request's {@code deadline_ms}; at least 1
     * @param renderer builds the line from the stamped clock
     */
    record Request(
            RepoLayout layout, String nodeId, Target target, String source, String id, int waitMillis, LineRenderer renderer) {
        public Request {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(renderer, "renderer");
            if (waitMillis < 1) {
                throw new IllegalArgumentException("the wait is at least 1 ms");
            }
        }
    }

    /**
     * What happened. {@code clock} is present exactly when a line was appended; {@code torn} when a torn final line was found
     * first.
     */
    record Stamp(boolean duplicate, Optional<LamportClock> clock, Optional<TornLine> torn) {
        public Stamp {
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(torn, "torn");
            if (duplicate == clock.isPresent()) {
                throw new IllegalArgumentException("a duplicate has no clock and a new row has one");
            }
        }
    }

    /**
     * Takes the exclusive lock (waiting at most {@code waitMillis}), skips a duplicate, stamps {@code max(state, recovered) + 1},
     * writes the state, appends the line and releases. Refused as {@code busy} when the lock cannot be had in time, as
     * {@code clock-overflow} above 2^31-1 (nothing written), as {@code rejected} when the rendered line is unusable.
     */
    ParseResult<Stamp> stampAndAppend(Request request);
}
