package ai.speckitty.producer.adapter.clock;

import ai.speckitty.producer.adapter.fs.AtomicFile;
import ai.speckitty.producer.adapter.fs.ExclusiveLock;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.FaultInjector.KillPoint;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.PublishLog.Probe;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The clock state under the git common directory. One exclusive lock is held from the duplicate check to the line being durable,
 * and the state reaches disk before the line does, so a kill leaves no advance, or an advance and no line (a gap), and never a
 * reused value. Retention takes the same lock (WP05), so a delete cannot unlink a file between its open and its append.
 */
public final class FileClockStore implements ClockStore {

    private final PublishLog publishLog;
    private final FaultInjector injector;

    public FileClockStore(PublishLog publishLog, FaultInjector injector) {
        this.publishLog = publishLog;
        this.injector = injector;
    }

    @Override
    public ParseResult<Stamp> stampAndAppend(Request request) {
        RepoLayout layout = request.layout();
        try {
            Optional<ExclusiveLock> acquired = ExclusiveLock.acquire(ClockPaths.lockFile(layout), request.waitMillis());
            if (acquired.isEmpty()) {
                return new ParseResult.Refused<>(FaultReason.BUSY, "the clock lock was not free in time");
            }
            try (ExclusiveLock ignored = acquired.get()) {
                return stampUnderLock(request);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ParseResult<Stamp> stampUnderLock(Request request) throws IOException {
        RepoLayout layout = request.layout();
        Probe probe = publishLog.probe(layout, request.target(), request.source(), request.id());
        Optional<PublishLog.TornLine> torn = Optional.ofNullable(probe.torn());
        if (probe.duplicate()) {
            return new ParseResult.Accepted<>(new Stamp(true, Optional.empty(), torn));
        }
        Optional<LamportClock> stored = ClockState.read(ClockPaths.stateFile(layout), request.nodeId());
        LamportClock base = stored.orElseGet(() -> recovered(layout));
        if (!(base.successor() instanceof ParseResult.Accepted<LamportClock> next)) {
            return new ParseResult.Refused<>(FaultReason.CLOCK_OVERFLOW, "the clock is at 2^31-1");
        }
        LamportClock clock = next.value();
        String line = request.renderer().render(clock);
        String problem = mismatch(request, clock, line);
        if (problem != null) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, problem);
        }
        injector.at(KillPoint.BEFORE_STATE_WRITE);
        AtomicFile.write(ClockPaths.stateFile(layout), ClockState.render(request.nodeId(), clock));
        injector.at(KillPoint.AFTER_STATE_WRITE);
        ParseResult<Boolean> appended = publishLog.append(layout, request.target(), line);
        if (appended instanceof ParseResult.Refused<Boolean> refused) {
            return new ParseResult.Refused<>(refused.reason(), refused.detail());
        }
        injector.at(KillPoint.AFTER_APPEND);
        return new ParseResult.Accepted<>(new Stamp(false, Optional.of(clock), torn));
    }

    /** State loss: the highest clock readable in the publish log, or zero when there is none. */
    private LamportClock recovered(RepoLayout layout) {
        OptionalInt highest = publishLog.highestClock(layout);
        return new LamportClock(highest.orElse(0));
    }

    /** Why the rendered line is not the one for this request and stamp, or null when it is. */
    private static String mismatch(Request request, LamportClock clock, String line) {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            return "the rendered line holds a raw line break";
        }
        if (!(Json.parseObject(line) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
            return "the rendered line is not one JSON object";
        }
        Map<String, Object> row = parsed.value();
        if (!request.source().equals(row.get("source")) || !request.id().equals(row.get("id"))) {
            return "the rendered line is for another event";
        }
        if (!new JsonNumber(Integer.toString(clock.value())).equals(row.get("lamportclock"))) {
            return "the rendered line does not carry the stamped clock";
        }
        if (!request.nodeId().equals(row.get("nodeid"))) {
            return "the rendered line does not carry the node id";
        }
        return null;
    }
}
