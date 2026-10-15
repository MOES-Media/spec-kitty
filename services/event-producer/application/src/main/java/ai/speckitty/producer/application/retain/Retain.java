package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.Clock;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.TrackedFiles.Tracking;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.protocol.RetainItem;
import ai.speckitty.producer.domain.protocol.RetainReply;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.domain.retention.Decision;
import ai.speckitty.producer.domain.retention.KeepReason;
import ai.speckitty.producer.domain.retention.MissionState;
import ai.speckitty.producer.domain.retention.RetentionEligibility;
import ai.speckitty.producer.domain.retention.RetentionFacts;
import ai.speckitty.producer.domain.retention.RetentionPolicy;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Retention of the local tier (FR-028 to FR-031). It derives every fact itself, from the committed ledgers, the lock sidecars and
 * the files, and decides with the pure rules of {@code domain.retention}; the request carries a policy and nothing else.
 *
 * <p>A dry run lists and deletes nothing. A real run deletes whole files only, one at a time, each under the clock lock that
 * {@code produce} holds around an append, after the facts were read again and the file's length and newest row were found
 * unchanged. A path that is not one of the {@link LocalTierPaths} shapes, or that git tracks, is not a candidate: it is neither
 * listed nor read nor deleted, so a committed ledger can never be selected.
 */
public final class Retain {

    private final RepoLocator locator;
    private final Clock clock;
    private final LedgerFacts ledgers;
    private final LocalTier tier;
    private final TrackedFiles tracked;
    private final ClockGate gate;

    public Retain(RepoLocator locator, Clock clock, LedgerFacts ledgers, LocalTier tier, TrackedFiles tracked, ClockGate gate) {
        this.locator = locator;
        this.clock = clock;
        this.ledgers = ledgers;
        this.tier = tier;
        this.tracked = tracked;
        this.gate = gate;
    }

    /** Answers a {@code retain} request; a policy error is a reply, and it is decided before anything is looked at. */
    public RetainResult retain(RetainRequest request) {
        RetentionPolicy.Parsed parsed = RetentionPolicy.parse(request.policy().get("keep_runs"), request.policy().get("max_age_days"));
        if (parsed instanceof RetentionPolicy.Parsed.Invalid invalid) {
            return new RetainResult.Replied(new RetainReply(
                    Protocol.VERSION, request.dryRun(), List.of(), List.of(), List.of(), invalid.error()));
        }
        RetentionPolicy policy = ((RetentionPolicy.Parsed.Valid) parsed).policy();
        if (!(locator.locate(startOf(request.startPath())) instanceof ParseResult.Accepted<RepoLayout> located)) {
            return new RetainResult.RootUnresolvable();
        }
        RepoLayout layout = located.value();
        Instant started = clock.now();

        List<Candidate> candidates = new ArrayList<>();
        List<RetainItem> kept = new ArrayList<>();
        gather(layout, candidates, kept);
        rank(candidates);

        Map<String, MissionState> missions = new HashMap<>();
        List<RetainItem> eligible = new ArrayList<>();
        List<Candidate> toDelete = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Decision decision = decide(layout, candidate.file, candidate.rank, started, policy, missions);
            if (decision instanceof Decision.Kept keep) {
                kept.add(new RetainItem(candidate.file.path(), keep.reason().wire()));
            } else if (request.dryRun()) {
                eligible.add(new RetainItem(candidate.file.path(), KeepReason.ELIGIBLE));
            } else {
                toDelete.add(candidate);
            }
        }
        List<RelativePath> deleted = new ArrayList<>();
        for (Candidate candidate : toDelete) {
            Optional<KeepReason> refusal = deleteUnderLock(layout, candidate, started, request.deadlineMs(), policy);
            if (refusal.isPresent()) {
                kept.add(new RetainItem(candidate.file.path(), refusal.get().wire()));
            } else {
                eligible.add(new RetainItem(candidate.file.path(), KeepReason.ELIGIBLE));
                deleted.add(candidate.file.path());
            }
        }
        eligible.sort(Comparator.comparing(item -> item.path().value()));
        kept.sort(Comparator.comparing(item -> item.path().value()));
        deleted.sort(Comparator.comparing(RelativePath::value));
        return new RetainResult.Replied(new RetainReply(Protocol.VERSION, request.dryRun(), eligible, kept, deleted, null));
    }

    /** A listed file that is a real candidate, with the kind its path has and its rank among files of that group. */
    private static final class Candidate {
        final LocalFile file;
        final LocalTierPaths.Shape shape;
        int rank;

        Candidate(LocalFile file, LocalTierPaths.Shape shape) {
            this.file = file;
            this.shape = shape;
        }
    }

    /** Sorts the listing into candidates and kept files, dropping what is not a candidate or is tracked. */
    private void gather(RepoLayout layout, List<Candidate> candidates, List<RetainItem> kept) {
        List<LocalTier.Entry> entries = tier.list(layout);
        List<RelativePath> shaped = new ArrayList<>();
        for (LocalTier.Entry entry : entries) {
            if (LocalTierPaths.classify(entry.path()).isPresent()) {
                shaped.add(entry.path());
            }
        }
        Map<RelativePath, Tracking> tracking = shaped.isEmpty() ? Map.of() : tracked.of(layout, shaped);
        for (LocalTier.Entry entry : entries) {
            Optional<LocalTierPaths.Shape> shape = LocalTierPaths.classify(entry.path());
            if (shape.isEmpty()) {
                continue;
            }
            Tracking answer = tracking.getOrDefault(entry.path(), Tracking.UNKNOWN);
            if (answer == Tracking.TRACKED) {
                continue;
            }
            if (answer == Tracking.UNKNOWN || entry instanceof LocalTier.Entry.Unreadable) {
                kept.add(new RetainItem(entry.path(), KeepReason.FACTS_UNAVAILABLE.wire()));
                continue;
            }
            candidates.add(new Candidate(((LocalTier.Entry.Present) entry).file(), shape.get()));
        }
    }

    /** Rank 1 is the newest of its group; equal times are ordered by path. */
    private static void rank(List<Candidate> candidates) {
        Map<LocalKind.Group, List<Candidate>> groups = new EnumMap<>(LocalKind.Group.class);
        for (Candidate candidate : candidates) {
            groups.computeIfAbsent(candidate.shape.kind().group(), g -> new ArrayList<>()).add(candidate);
        }
        for (List<Candidate> group : groups.values()) {
            group.sort(Comparator.<Candidate, Instant>comparing(c -> c.file.newest()).reversed().thenComparing(c -> c.file.path().value()));
            for (int i = 0; i < group.size(); i++) {
                group.get(i).rank = i + 1;
            }
        }
    }

    private Decision decide(
            RepoLayout layout, LocalFile file, int rank, Instant now, RetentionPolicy policy, Map<String, MissionState> missions) {
        LocalTierPaths.Shape shape = LocalTierPaths.classify(file.path()).orElseThrow();
        MissionState mission = MissionState.NOT_APPLICABLE;
        if (shape.kind() == LocalKind.PUBLISH_MISSION) {
            mission = missions != null
                    ? missions.computeIfAbsent(shape.id(), id -> ledgers.missionState(layout, id))
                    : ledgers.missionState(layout, shape.id());
        }
        Duration age = Duration.between(file.newest(), now);
        return RetentionEligibility.decide(new RetentionFacts(rank, age, mission, file.lock(), file.unfinishedStep()), policy);
    }

    /** Deletes one eligible file under the clock lock; empty when it was deleted, else the reason it was kept. */
    private Optional<KeepReason> deleteUnderLock(RepoLayout layout, Candidate candidate, Instant started, int deadlineMs, RetentionPolicy policy) {
        long elapsed = Math.max(0, Duration.between(started, clock.now()).toMillis());
        long wait = Math.max(1, (deadlineMs - elapsed) / 2);
        Optional<Optional<KeepReason>> outcome = gate.underClockLock(layout, wait, () -> {
            RelativePath path = candidate.file.path();
            if (tracked.of(layout, List.of(path)).getOrDefault(path, Tracking.UNKNOWN) != Tracking.UNTRACKED) {
                return Optional.of(KeepReason.FACTS_UNAVAILABLE);
            }
            Optional<LocalFile> fresh = tier.inspect(layout, path);
            if (fresh.isEmpty()) {
                return Optional.of(KeepReason.FACTS_UNAVAILABLE);
            }
            // the rank was decided on the whole listing; every other fact is read again
            Decision decision = decide(layout, fresh.get(), candidate.rank, clock.now(), policy, null);
            if (decision instanceof Decision.Kept keep) {
                return Optional.of(keep.reason());
            }
            if (fresh.get().length() != candidate.file.length() || !fresh.get().newest().equals(candidate.file.newest())) {
                return Optional.of(KeepReason.BUSY);
            }
            return tier.delete(layout, path) ? Optional.<KeepReason>empty() : Optional.of(KeepReason.DELETE_FAILED);
        });
        return outcome.orElse(Optional.of(KeepReason.BUSY));
    }

    private static Path startOf(String startPath) {
        try {
            return Path.of(startPath);
        } catch (InvalidPathException e) {
            return Path.of("/nonexistent-start-path-" + Integer.toHexString(startPath.hashCode()));
        }
    }
}
