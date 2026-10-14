package ai.speckitty.producer.application.retain;

import static ai.speckitty.producer.application.retain.Fakes.MISSION_A;
import static ai.speckitty.producer.application.retain.Fakes.MISSION_B;
import static ai.speckitty.producer.application.retain.Fakes.MISSION_C;
import static ai.speckitty.producer.application.retain.Fakes.file;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.retain.Fakes.FakeClock;
import ai.speckitty.producer.application.retain.Fakes.FakeGate;
import ai.speckitty.producer.application.retain.Fakes.FakeLedger;
import ai.speckitty.producer.application.retain.Fakes.FakeLocator;
import ai.speckitty.producer.application.retain.Fakes.FakeTier;
import ai.speckitty.producer.application.retain.Fakes.FakeTracked;
import ai.speckitty.producer.application.retain.TrackedFiles.Tracking;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.protocol.RetainItem;
import ai.speckitty.producer.domain.protocol.RetainReply;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.domain.retention.KeepReason;
import ai.speckitty.producer.domain.retention.LockState;
import ai.speckitty.producer.domain.retention.MissionState;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The retain operation over fakes: eligibility from derived facts, dry run first, whole-file delete under the clock lock. */
class RetainTest {

    private static final Duration OLD = Duration.ofDays(90);
    private static final Duration YOUNG = Duration.ofDays(2);

    private final FakeLocator locator = new FakeLocator();
    private final FakeClock clock = new FakeClock();
    private final FakeLedger ledger = new FakeLedger();
    private final FakeTier tier = new FakeTier();
    private final FakeTracked tracked = new FakeTracked();
    private final FakeGate gate = new FakeGate();
    private final Retain retain = new Retain(locator, clock, ledger, tier, tracked, gate);

    private static Map<String, Object> policy(Object keepRuns, Object maxAgeDays) {
        Map<String, Object> policy = new HashMap<>();
        if (keepRuns != null) {
            policy.put("keep_runs", keepRuns);
        }
        if (maxAgeDays != null) {
            policy.put("max_age_days", maxAgeDays);
        }
        return policy;
    }

    private static JsonNumber n(String literal) {
        return new JsonNumber(literal);
    }

    private static RetainRequest request(boolean dryRun, Map<String, Object> policy) {
        return new RetainRequest(Protocol.VERSION, "start", dryRun, policy, 4000);
    }

    private RetainReply run(boolean dryRun, Map<String, Object> policy) {
        RetainResult result = retain.retain(request(dryRun, policy));
        assertThat(result).isInstanceOf(RetainResult.Replied.class);
        return ((RetainResult.Replied) result).reply();
    }

    private static List<String> paths(List<RetainItem> items) {
        return items.stream().map(i -> i.path().value()).collect(Collectors.toList());
    }

    private static Map<String, String> reasons(List<RetainItem> items) {
        return items.stream().collect(Collectors.toMap(i -> i.path().value(), RetainItem::reason));
    }

    private void put(LocalFile file) {
        tier.files.put(file.path(), file);
    }

    /** Fills the tier with enough newer, closed files that the file under test falls outside the default 20 runs. */
    private void fillNewerPublishRuns(int count) {
        for (int i = 0; i < count; i++) {
            String id = "01J00000000000000000" + String.format("%06d", i);
            put(file(Fakes.mission(id), Duration.ofHours(1 + i)));
        }
    }

    // ---- policy ------------------------------------------------------------------------------------------------------------

    @Test
    void anInvalidSettingIsANamedPolicyErrorAlsoOnADryRunAndNothingIsListedOrDeleted() {
        put(file(Fakes.mission(MISSION_A), OLD));
        fillNewerPublishRuns(25);
        List<Map<String, Object>> invalid = List.of(
                policy("abc", null), policy(n("0"), null), policy(n("-1"), null), policy(n("1.5"), null), policy(true, null),
                policy("", null), policy(n("2147483648"), null), policy("007", null), policy(List.of(), null),
                policy(null, "abc"), policy(null, n("0")), policy(null, n("-3")), policy(null, n("2.0")), policy(null, false),
                policy(null, "1e3"), policy(null, " 5"));
        for (Map<String, Object> bad : invalid) {
            for (boolean dryRun : new boolean[] {true, false}) {
                RetainReply reply = run(dryRun, bad);
                assertThat(reply.policyError()).as(bad + " dryRun=" + dryRun).isNotNull();
                assertThat(reply.eligible()).isEmpty();
                assertThat(reply.kept()).isEmpty();
                assertThat(reply.deleted()).isEmpty();
                assertThat(reply.dryRun()).isEqualTo(dryRun);
            }
        }
        assertThat(tier.lists).as("a bad policy lists nothing").isZero();
        assertThat(tier.deleteAttempts).isEmpty();
        assertThat(gate.waits).isEmpty();
        assertThat(locator.asked).as("it is rejected before the repository is touched").isEmpty();
    }

    @Test
    void thePolicyErrorNamesTheKeyAndNeverEchoesTheValue() {
        assertThat(run(true, policy("abc", null)).policyError().key()).isEqualTo("ledger.retention.keep_runs");
        assertThat(run(true, policy(null, "abc")).policyError().key()).isEqualTo("ledger.retention.max_age_days");
        assertThat(run(true, policy("secret-value", null)).policyError().message()).doesNotContain("secret-value");
    }

    @Test
    void validSettingsAndAbsentOnesAreNotAnError() {
        for (Map<String, Object> good : List.of(policy(null, null), policy(n("1"), n("1")), policy("5", "7"), policy(n("20"), n("30")))) {
            assertThat(run(true, good).policyError()).as(good.toString()).isNull();
        }
    }

    @Test
    void theDefaultsAreTwentyRunsAndThirtyDays() {
        fillNewerPublishRuns(20);
        put(file(Fakes.mission(MISSION_A), OLD));
        RetainReply reply = run(true, policy(null, null));
        assertThat(paths(reply.eligible())).containsExactly(Fakes.mission(MISSION_A).value());

        tier.files.clear();
        fillNewerPublishRuns(19);
        put(file(Fakes.mission(MISSION_A), OLD));
        assertThat(run(true, policy(null, null)).eligible()).as("the 20th newest is kept").isEmpty();
    }

    // ---- eligibility -------------------------------------------------------------------------------------------------------

    @Test
    void eligibleOnlyOutsideBothLimits() {
        put(file(Fakes.mission(MISSION_A), OLD));       // rank 3, old
        put(file(Fakes.mission(MISSION_B), Duration.ofDays(10)));  // rank 2, old enough
        put(file(Fakes.mission(MISSION_C), YOUNG));     // rank 1
        RetainReply reply = run(true, policy(n("1"), n("5")));
        assertThat(reasons(reply.eligible())).containsEntry(Fakes.mission(MISSION_A).value(), KeepReason.ELIGIBLE)
                .containsEntry(Fakes.mission(MISSION_B).value(), KeepReason.ELIGIBLE).hasSize(2);
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_C).value(), "within-keep-runs");

        RetainReply moreKept = run(true, policy(n("2"), n("5")));
        assertThat(paths(moreKept.eligible())).containsExactly(Fakes.mission(MISSION_A).value());
        assertThat(reasons(moreKept.kept())).containsEntry(Fakes.mission(MISSION_B).value(), "within-keep-runs");

        RetainReply younger = run(true, policy(n("1"), n("30")));
        assertThat(reasons(younger.kept())).containsEntry(Fakes.mission(MISSION_B).value(), "within-max-age");
        assertThat(paths(younger.eligible())).containsExactly(Fakes.mission(MISSION_A).value());
    }

    @Test
    void theAgeBoundaryIsKeptAtExactlyTheLimitAndEligibleOneSecondPast() {
        put(file(Fakes.mission(MISSION_A), Duration.ofDays(30)));
        put(file(Fakes.mission(MISSION_B), Duration.ofDays(30).plusSeconds(1)));
        put(file(Fakes.mission(MISSION_C), Duration.ofHours(1)));
        RetainReply reply = run(true, policy(n("1"), n("30")));
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "within-max-age");
        assertThat(paths(reply.eligible())).containsExactly(Fakes.mission(MISSION_B).value());
    }

    @Test
    void aFileFromTheFutureIsKeptNotDeleted() {
        put(file(Fakes.mission(MISSION_A), Duration.ofDays(-400)));
        put(file(Fakes.mission(MISSION_B), Duration.ofDays(-500)));
        RetainReply reply = run(true, policy(n("1"), n("30")));
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_B).value(), "within-max-age");
    }

    @Test
    void everyKeepReasonIsReachedThroughFactsTheProducerDerives() {
        for (String id : List.of(MISSION_A, MISSION_B)) {
            put(file(Fakes.mission(id), OLD));
        }
        put(file(Fakes.journal("locked"), OLD, LockState.LIVE, false));
        put(file(Fakes.journal("unreadable"), OLD, LockState.UNREADABLE, false));
        put(file(Fakes.journal("unfinished"), OLD, LockState.NONE, true));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        ledger.states.put(MISSION_A, MissionState.OPEN);
        ledger.states.put(MISSION_B, MissionState.UNAVAILABLE);
        tier.unreadable.add(Fakes.fault("2024-01-01"));

        RetainReply reply = run(true, policy(n("1"), n("5")));
        Map<String, String> kept = reasons(reply.kept());
        assertThat(kept)
                .containsEntry(Fakes.mission(MISSION_A).value(), "mission-open")
                .containsEntry(Fakes.mission(MISSION_B).value(), "facts-unavailable")
                .containsEntry(Fakes.journal("locked").value(), "locked")
                .containsEntry(Fakes.journal("unreadable").value(), "lock-unreadable")
                .containsEntry(Fakes.journal("unfinished").value(), "unfinished-step")
                .containsEntry(Fakes.fault("2024-01-01").value(), "facts-unavailable");
        assertThat(reply.eligible()).isEmpty();
    }

    @Test
    void theMissionIsAskedOnlyForMissionPublishLogs() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.project(), OLD));
        put(file(Fakes.fault("2024-01-01"), OLD));
        put(file(Fakes.journal("r"), OLD));
        ledger.otherwise = MissionState.OPEN;
        RetainReply reply = run(true, policy(n("1"), n("5")));
        assertThat(ledger.asked).containsExactly(MISSION_A);
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "mission-open");
        assertThat(paths(reply.eligible())).as("the open flag belongs to a Mission, not to every file").contains(
                Fakes.fault("2024-01-01").value(), Fakes.journal("r").value());
    }

    @Test
    void runsAreRankedWithinTheirKindNewestFirst() {
        put(file(Fakes.mission(MISSION_A), Duration.ofDays(40)));
        put(file(Fakes.mission(MISSION_B), Duration.ofDays(50)));
        put(file(Fakes.project(), Duration.ofDays(60)));
        put(file(Fakes.fault("2024-01-01"), Duration.ofDays(400)));
        put(file(Fakes.fault("2024-01-02"), Duration.ofDays(399)));
        put(file(Fakes.journal("r1"), Duration.ofDays(70)));
        RetainReply reply = run(true, policy(n("2"), n("5")));
        Map<String, String> kept = reasons(reply.kept());
        // publish: A (rank 1) and B (rank 2) kept; the project file (rank 3) is outside
        assertThat(kept).containsEntry(Fakes.mission(MISSION_A).value(), "within-keep-runs")
                .containsEntry(Fakes.mission(MISSION_B).value(), "within-keep-runs");
        // faults: the newer day is kept, the older is outside; journals: the only one is rank 1
        assertThat(kept).containsEntry(Fakes.fault("2024-01-02").value(), "within-keep-runs")
                .containsEntry(Fakes.journal("r1").value(), "within-keep-runs");
        assertThat(paths(reply.eligible())).containsExactlyInAnyOrder(Fakes.project().value(), Fakes.fault("2024-01-01").value());
    }

    @Test
    void equalAgesAreRankedByPathSoTheAnswerIsDeterministic() {
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_A), OLD));
        RetainReply reply = run(true, policy(n("1"), n("5")));
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "within-keep-runs");
        assertThat(paths(reply.eligible())).containsExactly(Fakes.mission(MISSION_B).value());
    }

    @Test
    void theListsAreSortedByPath() {
        put(file(Fakes.mission(MISSION_C), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.fault("2024-01-01"), OLD));
        RetainReply reply = run(true, policy(n("1"), n("5")));
        assertThat(paths(reply.eligible())).isSorted();
        assertThat(paths(reply.kept())).isSorted();
    }

    // ---- dry run -----------------------------------------------------------------------------------------------------------

    @Test
    void aDryRunListsExactlyWhatARealRunWouldDeleteAndDeletesNothing() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        ledger.states.put(MISSION_B, MissionState.OPEN);
        RetainReply dry = run(true, policy(n("1"), n("5")));
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.deleted()).isEmpty();
        assertThat(tier.deleteAttempts).isEmpty();
        assertThat(gate.waits).as("a dry run takes no lock").isEmpty();
        assertThat(tier.inspected).as("a dry run re-reads nothing").isEmpty();

        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(real.dryRun()).isFalse();
        assertThat(real.eligible()).isEqualTo(dry.eligible());
        assertThat(real.kept()).isEqualTo(dry.kept());
        assertThat(real.deleted()).extracting(RelativePath::value).containsExactlyElementsOf(paths(dry.eligible()));
    }

    // ---- real run ----------------------------------------------------------------------------------------------------------

    @Test
    void aRealRunDeletesOnlyTheEligibleWholeFilesEachUnderTheClockLock() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        ledger.states.put(MISSION_B, MissionState.OPEN);
        RetainReply reply = run(false, policy(n("1"), n("5")));
        assertThat(tier.deleted).containsExactly(Fakes.mission(MISSION_A));
        assertThat(reply.deleted()).containsExactly(Fakes.mission(MISSION_A));
        assertThat(paths(reply.eligible())).containsExactly(Fakes.mission(MISSION_A).value());
        assertThat(paths(reply.kept())).containsExactlyInAnyOrder(Fakes.mission(MISSION_B).value(), Fakes.mission(MISSION_C).value());
        assertThat(gate.ran).as("one lock per deleted file").isEqualTo(1);
        assertThat(tier.files).containsKeys(Fakes.mission(MISSION_B), Fakes.mission(MISSION_C));
    }

    @Test
    void everyDeleteHappensInsideTheLockNotBeforeOrAfter() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        gate.busy = true;
        RetainReply busy = run(false, policy(n("1"), n("5")));
        assertThat(tier.deleteAttempts).as("no lock, no delete").isEmpty();
        assertThat(busy.deleted()).isEmpty();
        assertThat(reasons(busy.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "busy").containsEntry(Fakes.mission(MISSION_B).value(), "busy");
        assertThat(busy.eligible()).as("a file kept as busy is not also eligible").isEmpty();
    }

    @Test
    void aDeleteThatFailsIsKeptWithTheReasonDeleteFailed() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        tier.failDelete.add(Fakes.mission(MISSION_A));
        RetainReply reply = run(false, policy(n("1"), n("5")));
        assertThat(reasons(reply.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "delete-failed");
        assertThat(reply.deleted()).containsExactly(Fakes.mission(MISSION_B));
        assertThat(paths(reply.eligible())).containsExactly(Fakes.mission(MISSION_B).value());
    }

    @Test
    void eligibilityIsDecidedAgainUnderTheLockOnFreshFacts() {
        // each case changes the world between the listing and the lock
        record Change(String name, Runnable change, String reason) {}
        FakeTier world = tier;
        List<Change> changes = List.of(
                new Change("a producer appended a row", () -> world.files.put(Fakes.mission(MISSION_A), file(Fakes.mission(MISSION_A), YOUNG)), "within-max-age"),
                new Change("the Mission was reopened", () -> ledger.states.put(MISSION_A, MissionState.OPEN), "mission-open"),
                new Change("the ledger became unreadable", () -> ledger.states.put(MISSION_A, MissionState.UNAVAILABLE), "facts-unavailable"),
                new Change("a run took its lock", () -> world.files.put(Fakes.mission(MISSION_A), file(Fakes.mission(MISSION_A), OLD, LockState.LIVE, false)), "locked"),
                new Change("the lock became unreadable", () -> world.files.put(Fakes.mission(MISSION_A), file(Fakes.mission(MISSION_A), OLD, LockState.UNREADABLE, false)), "lock-unreadable"),
                new Change("a step became unfinished", () -> world.files.put(Fakes.mission(MISSION_A), file(Fakes.mission(MISSION_A), OLD, LockState.NONE, true)), "unfinished-step"),
                new Change("the file grew without a newer row", () -> world.files.put(Fakes.mission(MISSION_A),
                        new LocalFile(Fakes.mission(MISSION_A), 999, Fakes.NOW.minus(OLD), LockState.NONE, false)), "busy"),
                new Change("the newest row changed without growing", () -> world.files.put(Fakes.mission(MISSION_A),
                        new LocalFile(Fakes.mission(MISSION_A), 100, Fakes.NOW.minus(OLD).plusSeconds(1), LockState.NONE, false)), "busy"),
                new Change("the file vanished", () -> world.files.remove(Fakes.mission(MISSION_A)), "facts-unavailable"));
        for (Change change : changes) {
            tier.files.clear();
            tier.deleted.clear();
            tier.deleteAttempts.clear();
            ledger.states.clear();
            put(file(Fakes.mission(MISSION_A), OLD));
            put(file(Fakes.mission(MISSION_C), YOUNG));
            tier.beforeInspect = change.change();
            RetainReply reply = run(false, policy(n("1"), n("5")));
            assertThat(reasons(reply.kept())).as(change.name()).containsEntry(Fakes.mission(MISSION_A).value(), change.reason());
            assertThat(tier.deleteAttempts).as(change.name()).isEmpty();
            assertThat(reply.deleted()).as(change.name()).isEmpty();
            assertThat(reply.eligible()).as(change.name()).isEmpty();
            tier.beforeInspect = () -> {};
        }
        // the control: with no change the very same setup deletes the file
        tier.files.clear();
        tier.deleted.clear();
        ledger.states.clear();
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        assertThat(run(false, policy(n("1"), n("5"))).deleted()).containsExactly(Fakes.mission(MISSION_A));
    }

    @Test
    void theLockWaitIsBoundedByHalfTheRemainingDeadlineAndShrinksAsTimePasses() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        clock.step = Duration.ofMillis(1000);
        gate.busy = true;
        retain.retain(new RetainRequest(Protocol.VERSION, "start", false, policy(n("1"), n("5")), 4000));
        assertThat(gate.waits).hasSize(2);
        assertThat(gate.waits.get(0)).isBetween(1L, 2000L);
        assertThat(gate.waits.get(1)).isBetween(1L, gate.waits.get(0) - 1);
    }

    @Test
    void anExhaustedDeadlineStillAsksForAtLeastOneMillisecond() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        clock.step = Duration.ofSeconds(60);
        gate.busy = true;
        retain.retain(new RetainRequest(Protocol.VERSION, "start", false, policy(n("1"), n("5")), 100));
        assertThat(gate.waits).containsExactly(1L);
    }

    // ---- the committed ledgers are never candidates (AC-014) ---------------------------------------------------------------

    private static final List<String> TRACKED_LEDGERS = List.of(
            "kitty-specs/demo-01J00000/status.events.jsonl",
            "kitty-specs/demo-01J00000/meta.json",
            "kitty-specs/status.events.jsonl",
            ".worktrees/demo-coord/kitty-specs/demo/status.events.jsonl",
            ".git/spec-kitty/event-producer/clock.json",
            ".git/spec-kitty/event-producer/clock.lock",
            ".kittify/config.yaml");

    @Test
    void aCommittedLedgerOfferedAsACandidateIsNeverListedInspectedOrDeleted() {
        for (String ledgerPath : TRACKED_LEDGERS) {
            tier.extraListed.add(new RelativePath(ledgerPath));
            tracked.answers.put(new RelativePath(ledgerPath), Tracking.TRACKED);
        }
        assertThat(tier.extraListed).as("floor: tracked ledgers are offered").hasSizeGreaterThanOrEqualTo(5);
        put(file(Fakes.mission(MISSION_A), OLD)); // the control: a real candidate beside them
        put(file(Fakes.mission(MISSION_C), YOUNG));
        RetainReply dry = run(true, policy(n("1"), n("5")));
        RetainReply real = run(false, policy(n("1"), n("5")));
        for (RetainReply reply : List.of(dry, real)) {
            assertThat(paths(reply.eligible())).doesNotContainAnyElementsOf(TRACKED_LEDGERS);
            assertThat(paths(reply.kept())).doesNotContainAnyElementsOf(TRACKED_LEDGERS);
            assertThat(reply.deleted()).extracting(RelativePath::value).doesNotContainAnyElementsOf(TRACKED_LEDGERS);
        }
        assertThat(tier.deleteAttempts).extracting(RelativePath::value).doesNotContainAnyElementsOf(TRACKED_LEDGERS);
        assertThat(tier.inspected).extracting(RelativePath::value).doesNotContainAnyElementsOf(TRACKED_LEDGERS);
        assertThat(real.deleted()).as("the control is deleted: the harness can delete").containsExactly(Fakes.mission(MISSION_A));
    }

    @Test
    void aLedgerShapedPathIsRefusedEvenWhenGitDoesNotTrackIt() {
        for (String ledgerPath : TRACKED_LEDGERS) {
            tier.extraListed.add(new RelativePath(ledgerPath));
        }
        tracked.otherwise = Tracking.UNTRACKED;
        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(real.eligible()).isEmpty();
        assertThat(real.kept()).isEmpty();
        assertThat(tier.deleteAttempts).isEmpty();
        assertThat(tier.inspected).isEmpty();
    }

    @Test
    void aTrackedFileInsideALocalTierDirectoryIsNeverDeletedEitherWhileItsUntrackedTwinIs() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_B), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        tracked.answers.put(Fakes.mission(MISSION_A), Tracking.TRACKED);
        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(real.deleted()).containsExactly(Fakes.mission(MISSION_B));
        assertThat(tier.files).containsKey(Fakes.mission(MISSION_A));
        assertThat(tier.deleteAttempts).doesNotContain(Fakes.mission(MISSION_A));
        assertThat(paths(real.kept())).doesNotContain(Fakes.mission(MISSION_A).value());
    }

    @Test
    void aFileGitMayTrackButWhoseIndexCannotBeReadIsKept() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        tracked.answers.put(Fakes.mission(MISSION_A), Tracking.UNKNOWN);
        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(reasons(real.kept())).containsEntry(Fakes.mission(MISSION_A).value(), "facts-unavailable");
        assertThat(tier.deleteAttempts).isEmpty();
    }

    @Test
    void aFileThatBecameTrackedBetweenTheListingAndTheLockIsNotDeleted() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        tier.beforeInspect = () -> tracked.answers.put(Fakes.mission(MISSION_A), Tracking.TRACKED);
        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(tier.deleteAttempts).isEmpty();
        assertThat(real.deleted()).isEmpty();
    }

    // ---- other exits -------------------------------------------------------------------------------------------------------

    @Test
    void aCandidateWhoseFactsCannotBeReadIsKeptNeverDeleted() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        tier.unreadable.add(Fakes.journal("broken"));
        RetainReply real = run(false, policy(n("1"), n("5")));
        assertThat(reasons(real.kept())).containsEntry(Fakes.journal("broken").value(), "facts-unavailable");
        assertThat(tier.deleteAttempts).doesNotContain(Fakes.journal("broken"));
    }

    @Test
    void noRepositoryAboveTheStartPathIsANamedResultAndNothingIsListed() {
        locator.found = false;
        put(file(Fakes.mission(MISSION_A), OLD));
        RetainResult result = retain.retain(request(false, policy(null, null)));
        assertThat(result).isInstanceOf(RetainResult.RootUnresolvable.class);
        assertThat(tier.lists).isZero();
        assertThat(tier.deleteAttempts).isEmpty();
    }

    @Test
    void anEmptyTierIsAnEmptyReply() {
        RetainReply reply = run(false, policy(null, null));
        assertThat(reply.eligible()).isEmpty();
        assertThat(reply.kept()).isEmpty();
        assertThat(reply.deleted()).isEmpty();
        assertThat(reply.policyError()).isNull();
        assertThat(reply.version()).isEqualTo(Protocol.VERSION);
    }

    @Test
    void theStartPathIsPassedToTheLocatorAsGiven() {
        retain.retain(new RetainRequest(Protocol.VERSION, "some/start", true, policy(null, null), 1000));
        assertThat(locator.asked).extracting(Object::toString).containsExactly("some/start");
    }

    @Test
    void anUnparsableStartPathIsRootUnresolvableNotACrash() {
        locator.found = false;
        assertThat(retain.retain(new RetainRequest(Protocol.VERSION, "bad\0path", true, policy(null, null), 1000)))
                .isInstanceOf(RetainResult.RootUnresolvable.class);
    }

    @Test
    void theRequestNeverCarriesFactsAndTheReplyNeverCarriesAnAbsolutePath() {
        put(file(Fakes.mission(MISSION_A), OLD));
        put(file(Fakes.mission(MISSION_C), YOUNG));
        RetainReply reply = run(false, policy(n("1"), n("5")));
        for (RetainItem item : reply.kept()) {
            assertThat(item.path().value()).doesNotStartWith("/").doesNotContain(":");
        }
        for (RelativePath path : reply.deleted()) {
            assertThat(path.value()).startsWith(".kittify/");
        }
    }
}
