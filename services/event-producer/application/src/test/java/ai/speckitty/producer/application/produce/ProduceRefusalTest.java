package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Fixture.AT;
import static ai.speckitty.producer.application.produce.Fixture.EVENT;
import static ai.speckitty.producer.application.produce.Rows.num;
import static ai.speckitty.producer.application.produce.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import ai.speckitty.producer.domain.protocol.Slot;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProduceRefusalTest {

    private static void assertRefused(Fixture f, ProduceReply reply, RefusalReason reason) {
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(reason);
        assertThat(reply.type()).isNull();
        assertThat(f.store.requests).as("nothing is stamped or appended").isEmpty();
        assertThat(f.store.lines).isEmpty();
        assertThat(f.rendered).isEmpty();
    }

    // ----- id (FR-007) ------------------------------------------------------------------------------------------------------

    @Test
    void aRowWithoutAnEventIdIsRefusedAndNeverGivenOne() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.remove("event_id");
        ProduceReply reply = f.run(row);
        assertRefused(f, reply, RefusalReason.ID_NOT_ULID);
        assertThat(reply.eventId()).isNull();
    }

    @Test
    void aNullOrEmptyOrNonStringEventIdIsRefused() {
        for (Object bad : new Object[] {null, "", 7, List.of("x"), Boolean.TRUE, "not-an-id", "01J00000000000000000000F0"}) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.laneRow();
            row.put("event_id", bad);
            assertRefused(f, f.run(row), RefusalReason.ID_NOT_ULID);
        }
    }

    @Test
    void everyUuidFormIsRefusedAndTheReplyNamesItsNormalisedForm() {
        String hyphenated = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
        for (String uuid : List.of(hyphenated, hyphenated.toUpperCase(), hyphenated.replace("-", ""), hyphenated.replace("-", "").toUpperCase())) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.laneRow();
            row.put("event_id", uuid);
            ProduceReply reply = f.run(row);
            assertRefused(f, reply, RefusalReason.ID_NOT_ULID);
            assertThat(reply.eventId()).as(uuid).isEqualTo(hyphenated);
        }
    }

    @Test
    void aLowerCaseUlidIsAcceptedAndWrittenUpperCase() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("event_id", EVENT.toLowerCase());
        ProduceReply reply = f.run(row);
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(reply.eventId()).isEqualTo(EVENT);
        assertThat(f.onlyDocument()).containsEntry("id", EVENT);
        assertThat(f.store.requests.get(0).id()).isEqualTo(EVENT);
    }

    @Test
    void theReplyOfARefusedRowWithAUlidNamesTheUpperCaseUlid() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("event_id", EVENT.toLowerCase());
        row.put("at", "yesterday");
        ProduceReply reply = f.run(row);
        assertRefused(f, reply, RefusalReason.TIME_UNPARSEABLE);
        assertThat(reply.eventId()).isEqualTo(EVENT);
    }

    // ----- time (FR-008) and the WP02 carry-forward F1 ---------------------------------------------------------------------

    @Test
    void aRowWithNoTimeIsRefusedAndNeverGivenOne() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.remove("at");
        ProduceReply reply = f.run(row);
        assertRefused(f, reply, RefusalReason.TIME_UNPARSEABLE);
        assertThat(reply.reasonClass()).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void aLifecycleRowWithNoTimestampAndADecisionRowWithNoAtAreRefusedToo() {
        Fixture lifecycle = new Fixture();
        Map<String, Object> wp = Fixture.wpCreatedRow();
        wp.remove("timestamp");
        assertRefused(lifecycle, lifecycle.run(wp), RefusalReason.TIME_UNPARSEABLE);

        Fixture decision = new Fixture();
        Map<String, Object> row = obj("event_id", EVENT, "event_type", "DecisionPointOpened", "payload", obj());
        assertRefused(decision, decision.run(row), RefusalReason.TIME_UNPARSEABLE);

        Fixture journal = new Fixture();
        Map<String, Object> run = obj("event_id", EVENT, "row_version", num("1"), "event_type", "MissionRunStarted", "payload", obj());
        assertRefused(journal, journal.run(run), RefusalReason.TIME_UNPARSEABLE);
    }

    @Test
    void anUnparseableOrNonStringTimeIsRefused() {
        for (Object bad : new Object[] {null, "", "yesterday", "2026-01-01", "2026-01-01 00:00:00Z", "2026-13-01T00:00:00Z", "2026-01-01T00:00:00", 5,
                num("20260101"), List.of(AT), Boolean.TRUE, " " + AT}) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.laneRow();
            row.put("at", bad);
            assertRefused(f, f.run(row), RefusalReason.TIME_UNPARSEABLE);
        }
    }

    @Test
    void theTimeIsTheRowsOwnTextWithItsOffsetNeverConvertedOrSynthesised() {
        for (String time : List.of("2026-10-13T12:00:00+02:00", "2026-10-13T10:00:00.5Z", "2026-10-13t10:00:00z", "2026-10-13T10:00:00.123456789-05:30")) {
            Fixture f = new Fixture();
            Map<String, Object> row = Fixture.laneRow();
            row.put("at", time);
            f.run(row);
            assertThat(f.onlyDocument()).containsEntry("time", time);
        }
    }

    // ----- root, type, schema ------------------------------------------------------------------------------------------------

    @Test
    void anUnresolvableRootIsRefusedBeforeAnythingElseIsLookedAt() {
        Fixture f = new Fixture();
        f.located = new ParseResult.Refused<>(FaultReason.REJECTED, "root-unresolvable");
        Map<String, Object> row = Fixture.laneRow();
        row.remove("event_id");
        ProduceReply reply = f.run(row);
        assertRefused(f, reply, RefusalReason.ROOT_UNRESOLVABLE);
        assertThat(reply.reasonClass()).isEqualTo(FaultReason.REJECTED);
        assertThat(f.validated).isEmpty();
        assertThat(f.metaReads).isEmpty();
    }

    @Test
    void aTypeAbsentFromTheRegistryIsRefusedAndNeverWritten() {
        Fixture f = new Fixture();
        ProduceReply reply = f.run(obj("event_id", EVENT, "event_type", "NoSuchThing", "timestamp", AT, "payload", obj()));
        assertRefused(f, reply, RefusalReason.TYPE_UNKNOWN);
        assertThat(f.validated).isEmpty();
    }

    @Test
    void anEmptyRegistryRefusesEveryRowRatherThanPassingThemThrough() {
        Fixture f = new Fixture();
        f.catalog = new ListedCatalog(List.of());
        assertRefused(f, f.run(Fixture.laneRow()), RefusalReason.TYPE_UNKNOWN);
    }

    @Test
    void noRegistryAtAllIsValidatorUnavailable() {
        Fixture f = new Fixture();
        f.catalog = TypeCatalog.UNAVAILABLE;
        assertRefused(f, f.run(Fixture.laneRow()), RefusalReason.VALIDATOR_UNAVAILABLE);
    }

    @Test
    void aSchemaViolationIsRefusedWithOneBoundedLineAndNothingIsWritten() {
        Fixture f = new Fixture();
        f.violation = Optional.of("/data/to_lane: not one of the persisted lanes");
        ProduceReply reply = f.run(Fixture.laneRow());
        assertRefused(f, reply, RefusalReason.SCHEMA_INVALID);
        assertThat(reply.detail()).isEqualTo("/data/to_lane: not one of the persisted lanes");
    }

    @Test
    void aMultiLineOrOverlongViolationIsMadeIntoOneBoundedLine() {
        Fixture f = new Fixture();
        f.violation = Optional.of("first\nsecond\r\nthird " + "x".repeat(2000));
        ProduceReply reply = f.run(Fixture.laneRow());
        assertThat(reply.reason()).isEqualTo(RefusalReason.SCHEMA_INVALID);
        assertThat(reply.detail()).doesNotContain("\n").doesNotContain("\r").hasSizeLessThanOrEqualTo(ProduceReply.MAX_DETAIL_LENGTH);
        assertThat(reply.detail()).startsWith("first second third");
    }

    @Test
    void aViolationNeverEchoesTheRootOrTheStartPath() {
        Fixture f = new Fixture();
        f.violation = Optional.of("cannot read /repo/.kittify/x and work/missions/m1/meta.json");
        ProduceReply reply = f.run(Fixture.laneRow());
        assertThat(reply.detail()).doesNotContain("/repo").doesNotContain("work/missions/m1");
    }

    @Test
    void theValidatorSentinelIsValidatorUnavailableNotAnOrdinaryViolation() {
        Fixture f = new Fixture();
        f.violation = Optional.of(Produce.VALIDATOR_UNAVAILABLE_VIOLATION);
        assertRefused(f, f.run(Fixture.laneRow()), RefusalReason.VALIDATOR_UNAVAILABLE);
    }

    @Test
    void aValidatorThatThrowsFailsClosed() {
        Fixture f = new Fixture();
        f.validatorFails = new IllegalStateException("boom at /home/someone/file");
        ProduceReply reply = f.run(Fixture.laneRow());
        assertRefused(f, reply, RefusalReason.VALIDATOR_UNAVAILABLE);
        assertThat(String.valueOf(reply.detail())).doesNotContain("boom").doesNotContain("/home");
    }

    @Test
    void theFailClosedValidatorRefusesAWellFormedRowAndAnAcceptAllOneWouldNot() {
        Fixture closed = new Fixture();
        Produce produce = new Produce(
                s -> new ParseResult.Accepted<>(Fixture.LAYOUT), () -> Fixture.NODE, closed.store, new FailClosedValidator(),
                MirroredRegistry.catalog(), new NullContext(), d -> "{}");
        ProduceReply reply = produce.produce(Fixture.request(Fixture.laneRow()));
        assertRefused(closed, reply, RefusalReason.VALIDATOR_UNAVAILABLE);

        // The control: the same wiring with an accept-all validator writes the row, so the refusal above is the validator's doing.
        Fixture open = new Fixture();
        Produce permissive = new Produce(
                s -> new ParseResult.Accepted<>(Fixture.LAYOUT), () -> Fixture.NODE, open.store, d -> Optional.empty(),
                MirroredRegistry.catalog(), new NullContext(), d -> "{}");
        assertThat(permissive.produce(Fixture.request(Fixture.laneRow())).outcome()).isEqualTo(Outcome.PRODUCED);
    }

    private static final class NullContext implements StartContext {
        @Override
        public Optional<Map<String, Object>> metaJson(java.nio.file.Path startPath) {
            return Optional.empty();
        }

        @Override
        public Optional<String> projectUuid(ai.speckitty.producer.application.port.RepoLocator.RepoLayout layout) {
            return Optional.empty();
        }
    }

    // ----- envelope attribute rules --------------------------------------------------------------------------------------

    @Test
    void aValueThatIsNotACloudEventsAttributeTypeIsRefusedNotDropped() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("execution_mode", obj("a", "b"));
        assertRefused(f, f.run(row), RefusalReason.SCHEMA_INVALID);

        Fixture g = new Fixture();
        Map<String, Object> row2 = Fixture.laneRow();
        row2.put("actor", List.of("x"));
        assertRefused(g, g.run(row2), RefusalReason.SCHEMA_INVALID);
    }

    @Test
    void aFractionalOrOutOfRangeIntegerAttributeIsRefused() {
        Fixture f = new Fixture();
        Map<String, Object> row = Fixture.laneRow();
        row.put("execution_mode", num("5.0"));
        assertRefused(f, f.run(row), RefusalReason.SCHEMA_INVALID);
        Fixture g = new Fixture();
        Map<String, Object> row2 = Fixture.laneRow();
        row2.put("execution_mode", num("2147483648"));
        assertRefused(g, g.run(row2), RefusalReason.SCHEMA_INVALID);
    }

    // ----- clock store outcomes ------------------------------------------------------------------------------------------

    @Test
    void aClockOverflowIsRefusedAsClockOverflow() {
        Fixture f = new Fixture();
        f.store.refuse = FaultReason.CLOCK_OVERFLOW;
        ProduceReply reply = f.run(Fixture.laneRow());
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.CLOCK_OVERFLOW);
        assertThat(reply.reasonClass()).isEqualTo(FaultReason.CLOCK_OVERFLOW);
        assertThat(f.store.lines).isEmpty();
    }

    @Test
    void aLockTimeoutIsABusyReplyNotAnExceptionNorAnotherClass() {
        Fixture f = new Fixture();
        f.store.refuse = FaultReason.BUSY;
        ProduceReply reply = f.run(Fixture.laneRow());
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.BUSY);
        assertThat(reply.reasonClass()).isEqualTo(FaultReason.BUSY);
        assertThat(reply.eventId()).isEqualTo(EVENT);
    }

    @Test
    void aRefusedLineIsASchemaInvalidReplyNeverAClassTheClientDoesNotKnow() {
        Fixture f = new Fixture();
        f.store.refuse = FaultReason.REJECTED;
        f.store.refuseDetail = "the rendered line is not one JSON object";
        ProduceReply reply = f.run(Fixture.laneRow());
        assertThat(reply.reason()).isEqualTo(RefusalReason.SCHEMA_INVALID);
        assertThat(reply.detail()).isEqualTo("the rendered line is not one JSON object");
    }

    @Test
    void anyOtherClassTheStoreReportsStillBecomesAReplyWithAKnownReason() {
        for (FaultReason other : List.of(FaultReason.CRASH, FaultReason.TIMEOUT, FaultReason.MALFORMED_REPLY, FaultReason.BINARY_MISSING)) {
            Fixture f = new Fixture();
            f.store.refuse = other;
            ProduceReply reply = f.run(Fixture.laneRow());
            assertThat(reply.outcome()).as(other.name()).isEqualTo(Outcome.REFUSED);
            assertThat(reply.reason()).as(other.name()).isEqualTo(RefusalReason.SCHEMA_INVALID);
        }
    }

    @Test
    void theLockWaitIsHalfTheDeadlineAndAtLeastOneMillisecond() {
        for (int[] pair : new int[][] {{1500, 750}, {1000, 500}, {3, 1}, {2, 1}, {1, 1}, {20000, 10000}}) {
            Fixture f = new Fixture();
            f.produce().produce(new ProduceRequest(1, Fixture.laneRow(), Slot.STATUS, obj(), Fixture.START, pair[0]));
            assertThat(f.store.requests.get(0).waitMillis()).as("deadline " + pair[0]).isEqualTo(pair[1]);
        }
    }

    @Test
    void anUnavailableNodeIdIsRefusedAsNodeidUnavailable() {
        Fixture f = new Fixture();
        f.nodeIdFails = true;
        ProduceReply reply = f.run(Fixture.laneRow());
        assertRefused(f, reply, RefusalReason.NODEID_UNAVAILABLE);
        assertThat(String.valueOf(reply.detail())).doesNotContain("host");
    }

    // ----- the row is the only input ---------------------------------------------------------------------------------------

    @Test
    void seamArgsAreNeverReadNotEvenForTheirSize() {
        Fixture f = new Fixture();
        Map<String, Object> hostile = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        throw new AssertionError("seam_args was read");
                    }

                    @Override
                    public int size() {
                        throw new AssertionError("seam_args was read");
                    }
                };
            }

            @Override
            public Object get(Object key) {
                throw new AssertionError("seam_args was read");
            }

            @Override
            public boolean containsKey(Object key) {
                throw new AssertionError("seam_args was read");
            }
        };
        ProduceReply reply = f.produce().produce(new ProduceRequest(1, Fixture.laneRow(), Slot.LIFECYCLE, hostile, Fixture.START, 1500));
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
    }

    @Test
    void aSeamEnvelopeInSeamArgsNeverSuppliesTheIdTheTypeOrTheTime() {
        Fixture f = new Fixture();
        Map<String, Object> seam = obj("envelope", obj("event_id", "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", "timestamp", "1999-01-01T00:00:00Z"));
        Map<String, Object> row = Fixture.laneRow();
        row.remove("event_id");
        ProduceReply reply = f.produce().produce(new ProduceRequest(1, row, Slot.STATUS, seam, Fixture.START, 1500));
        assertRefused(f, reply, RefusalReason.ID_NOT_ULID);
    }

    @Test
    void everyReplyIsOneOfTheThreeOutcomesAndNeverEchoesARowValue() {
        List<ProduceReply> replies = new ArrayList<>();
        Fixture a = new Fixture();
        Map<String, Object> secret = Fixture.laneRow();
        secret.put("reason", "SECRET-REASON-TEXT");
        secret.put("at", "SECRET-TIME");
        replies.add(a.run(secret));
        Fixture b = new Fixture();
        b.violation = Optional.of("violation about the document");
        replies.add(b.run(secret));
        for (ProduceReply reply : replies) {
            assertThat(String.valueOf(reply.detail())).doesNotContain("SECRET");
            assertThat(reply.toString()).doesNotContain("SECRET-REASON-TEXT");
        }
    }
}
