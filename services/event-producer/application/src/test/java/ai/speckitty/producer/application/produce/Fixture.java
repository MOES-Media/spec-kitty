package ai.speckitty.producer.application.produce;

import static ai.speckitty.producer.application.produce.Rows.obj;

import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.NodeIdentity;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.domain.clock.LamportClock;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Slot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The use case wired to in-memory fakes, with the recordings a test needs. */
final class Fixture {

    static final String EVENT = "01J0000000000000000000F001";
    static final String MISSION = "01J0000000000000000000MSN1";
    static final String OTHER_MISSION = "01J0000000000000000000MSN2";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";
    static final String NODE = "0123456789ab";
    static final String AT = "2026-01-01T00:00:00+00:00";
    static final String START = "work/missions/m1";
    static final RepoLayout LAYOUT = new RepoLayout(Path.of("/repo"), Path.of("/repo/.git"));

    /** What a fake locator answers. */
    ParseResult<RepoLayout> located = new ParseResult.Accepted<>(LAYOUT);
    final List<Path> locatedFrom = new ArrayList<>();

    /** The fake clock store. */
    final FakeStore store = new FakeStore();

    /** What the fake node identity does. */
    String nodeId = NODE;
    boolean nodeIdFails;

    /** What the validator does: a violation, a throw, or accept. */
    Optional<String> violation = Optional.empty();
    RuntimeException validatorFails;
    final List<Map<String, Object>> validated = new ArrayList<>();
    boolean testLocalLaneSchema;

    TypeCatalog catalog = MirroredRegistry.catalog();

    /** The start context. */
    Optional<Map<String, Object>> meta = Optional.empty();
    Optional<String> configProjectUuid = Optional.empty();
    final List<Path> metaReads = new ArrayList<>();

    /** The documents handed to the renderer, in order. */
    final List<Map<String, Object>> rendered = new ArrayList<>();

    Function<Map<String, Object>, String> renderer = document -> {
        rendered.add(new LinkedHashMap<>(document));
        return "{\"n\":" + rendered.size() + "}";
    };

    Produce produce() {
        RepoLocator locator = start -> {
            locatedFrom.add(start);
            return located;
        };
        NodeIdentity identity = () -> {
            if (nodeIdFails) {
                throw new IllegalStateException("no host name");
            }
            return nodeId;
        };
        RowSchemaValidator validator = document -> {
            validated.add(new LinkedHashMap<>(document));
            if (validatorFails != null) {
                throw validatorFails;
            }
            if (testLocalLaneSchema) {
                Optional<String> laneProblem = TestLaneSchema.check(document);
                if (laneProblem.isPresent()) {
                    return laneProblem;
                }
            }
            return violation;
        };
        StartContext context = new StartContext() {
            @Override
            public Optional<Map<String, Object>> metaJson(Path startPath) {
                metaReads.add(startPath);
                return meta;
            }

            @Override
            public Optional<String> projectUuid(RepoLayout layout) {
                return configProjectUuid;
            }
        };
        return new Produce(locator, identity, store, validator, catalog, context, renderer);
    }

    static ProduceRequest request(Map<String, Object> row) {
        return new ProduceRequest(1, row, Slot.STATUS, obj(), START, 1500);
    }

    ai.speckitty.producer.domain.protocol.ProduceReply run(Map<String, Object> row) {
        return produce().produce(request(row));
    }

    /** The one document rendered; fails the test when there is not exactly one. */
    Map<String, Object> onlyDocument() {
        if (rendered.size() != 1) {
            throw new AssertionError("expected one rendered document, found " + rendered.size());
        }
        return rendered.get(0);
    }

    /** A persisted lane row of Mission {@link #MISSION}. */
    static Map<String, Object> laneRow() {
        return obj(
                "event_id", EVENT,
                "mission_slug", "041-reducer-unification",
                "mission_id", MISSION,
                "wp_id", "WP01",
                "from_lane", "planned",
                "to_lane", "claimed",
                "at", AT,
                "actor", "claude",
                "force", Boolean.FALSE,
                "execution_mode", "worktree",
                "reason", null,
                "review_ref", null,
                "evidence", null,
                "policy_metadata", obj("agent", "claude", "shell_pid", Rows.num("4242")));
    }

    /** A persisted lifecycle row (status/lifecycle_events.py envelope) of type WPCreated. */
    static Map<String, Object> wpCreatedRow() {
        return obj(
                "event_id", EVENT,
                "event_type", "WPCreated",
                "aggregate_id", "WP02",
                "aggregate_type", "WorkPackage",
                "schema_version", "1.0.0",
                "timestamp", AT,
                "payload", obj("wp_id", "WP02", "mission_id", MISSION, "mission_slug", "041-reducer-unification", "title", "Second package"),
                "project_uuid", PROJECT,
                "project_slug", "demo");
    }

    /** A persisted mission-level lifecycle row (no work package) of type MissionCreated. */
    static Map<String, Object> missionCreatedRow() {
        return obj(
                "event_id", EVENT,
                "event_type", "MissionCreated",
                "aggregate_id", "041-reducer-unification",
                "aggregate_type", "Mission",
                "schema_version", "1.0.0",
                "timestamp", AT,
                "payload", obj("mission_id", MISSION, "mission_slug", "041-reducer-unification", "mission_type", "software-dev"),
                "project_uuid", PROJECT,
                "project_slug", "demo");
    }

    /** What the fake clock store was asked, and what it keeps. */
    static final class FakeStore implements ClockStore {
        final List<Request> requests = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final Map<String, Integer> clocks = new LinkedHashMap<>();
        int counter;
        FaultReason refuse;
        String refuseDetail = "refused";
        PublishLog.TornLine torn;

        @Override
        public ParseResult<Stamp> stampAndAppend(Request request) {
            requests.add(request);
            if (refuse != null) {
                return new ParseResult.Refused<>(refuse, refuseDetail);
            }
            String key = request.source() + "|" + request.id();
            Optional<PublishLog.TornLine> tornLine = Optional.ofNullable(torn);
            if (clocks.containsKey(key)) {
                return new ParseResult.Accepted<>(new Stamp(true, Optional.empty(), tornLine));
            }
            counter++;
            clocks.put(key, counter);
            lines.add(request.renderer().render(new LamportClock(counter)));
            return new ParseResult.Accepted<>(new Stamp(false, Optional.of(new LamportClock(counter)), tornLine));
        }
    }
}
