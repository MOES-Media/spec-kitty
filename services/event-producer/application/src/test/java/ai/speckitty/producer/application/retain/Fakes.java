package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.Clock;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.TrackedFiles.Tracking;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.retention.LockState;
import ai.speckitty.producer.domain.retention.MissionState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** In-memory collaborators of {@link Retain}, recording every call so that "nothing was touched" can be asserted. */
final class Fakes {

    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final RepoLayout LAYOUT = new RepoLayout(Path.of("fixture-root"), Path.of("fixture-root", ".git"));
    static final String MISSION_A = "01J0000000000000000000MSN1";
    static final String MISSION_B = "01J0000000000000000000MSN2";
    static final String MISSION_C = "01J0000000000000000000MSN3";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";

    private Fakes() {}

    static RelativePath mission(String id) {
        return new RelativePath(".kittify/events/publish/missions/" + id + ".jsonl");
    }

    static RelativePath project() {
        return new RelativePath(".kittify/events/publish/project-" + PROJECT + ".jsonl");
    }

    static RelativePath fault(String day) {
        return new RelativePath(".kittify/events/faults/" + day + ".jsonl");
    }

    static RelativePath journal(String run) {
        return new RelativePath(".kittify/runtime/runs/" + run + "/run.events.jsonl");
    }

    static LocalFile file(RelativePath path, Duration age) {
        return new LocalFile(path, 100, NOW.minus(age), LockState.NONE, false);
    }

    static LocalFile file(RelativePath path, Duration age, LockState lock, boolean unfinished) {
        return new LocalFile(path, 100, NOW.minus(age), lock, unfinished);
    }

    /** A clock that can advance by a step on every read. */
    static final class FakeClock implements Clock {
        Instant now = NOW;
        Duration step = Duration.ZERO;
        int reads;

        @Override
        public Instant now() {
            reads++;
            Instant current = now;
            now = now.plus(step);
            return current;
        }
    }

    static final class FakeLocator implements RepoLocator {
        boolean found = true;
        final List<Path> asked = new ArrayList<>();

        @Override
        public ParseResult<RepoLayout> locate(Path start) {
            asked.add(start);
            return found ? new ParseResult.Accepted<>(LAYOUT) : new ParseResult.Refused<>(FaultReason.REJECTED, ROOT_UNRESOLVABLE);
        }
    }

    static final class FakeLedger implements LedgerFacts {
        final Map<String, MissionState> states = new HashMap<>();
        MissionState otherwise = MissionState.CLOSED;
        final List<String> asked = new ArrayList<>();

        @Override
        public MissionState missionState(RepoLayout layout, String missionId) {
            asked.add(missionId);
            return states.getOrDefault(missionId, otherwise);
        }
    }

    static final class FakeTracked implements TrackedFiles {
        final Map<RelativePath, Tracking> answers = new HashMap<>();
        Tracking otherwise = Tracking.UNTRACKED;
        Runnable beforeAsk = () -> {};

        @Override
        public Map<RelativePath, Tracking> of(RepoLayout layout, Collection<RelativePath> paths) {
            beforeAsk.run();
            Map<RelativePath, Tracking> result = new LinkedHashMap<>();
            for (RelativePath path : paths) {
                result.put(path, answers.getOrDefault(path, otherwise));
            }
            return result;
        }
    }

    static final class FakeGate implements ClockGate {
        boolean busy;
        final List<Long> waits = new ArrayList<>();
        int ran;

        @Override
        public <T> Optional<T> underClockLock(RepoLayout layout, long waitMillis, Supplier<T> action) {
            waits.add(waitMillis);
            if (busy) {
                return Optional.empty();
            }
            ran++;
            return Optional.ofNullable(action.get());
        }
    }

    /** A local tier over a map, which records every inspect and delete. A hook runs just before each inspect, to change the world. */
    static final class FakeTier implements LocalTier {
        final Map<RelativePath, LocalFile> files = new LinkedHashMap<>();
        final List<RelativePath> unreadable = new ArrayList<>();
        final List<RelativePath> extraListed = new ArrayList<>();
        final List<RelativePath> inspected = new ArrayList<>();
        final List<RelativePath> deleted = new ArrayList<>();
        final List<RelativePath> deleteAttempts = new ArrayList<>();
        final List<RelativePath> failDelete = new ArrayList<>();
        Runnable beforeInspect = () -> {};
        int lists;

        @Override
        public List<Entry> list(RepoLayout layout) {
            lists++;
            List<Entry> entries = new ArrayList<>();
            files.values().forEach(f -> entries.add(new Entry.Present(f)));
            unreadable.forEach(p -> entries.add(new Entry.Unreadable(p)));
            for (RelativePath extra : extraListed) {
                entries.add(new Entry.Present(file(extra, Duration.ofDays(400))));
            }
            return entries;
        }

        @Override
        public Optional<LocalFile> inspect(RepoLayout layout, RelativePath path) {
            inspected.add(path);
            beforeInspect.run();
            return Optional.ofNullable(files.get(path));
        }

        @Override
        public boolean delete(RepoLayout layout, RelativePath path) {
            deleteAttempts.add(path);
            if (failDelete.contains(path)) {
                return false;
            }
            deleted.add(path);
            files.remove(path);
            return true;
        }
    }
}
