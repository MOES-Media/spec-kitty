package ai.speckitty.producer.launcher.e2e;

import ai.speckitty.producer.application.produce.ActorKind;
import ai.speckitty.producer.application.produce.ActorRule;
import ai.speckitty.producer.application.produce.ListedCatalog;
import ai.speckitty.producer.application.produce.RegisteredType;
import ai.speckitty.producer.application.produce.Shape;
import ai.speckitty.producer.domain.envelope.TypeEntry;
import java.util.List;
import java.util.Map;

/**
 * The registered types the launcher tests use: a test-local slice of the spec-kitty-events v10.5.0 registry (the strings
 * {@code type}, {@code dataschema} and {@code schemaversion} are the registry's own). The released artifact is consumed by a later
 * work package; nothing of the events repository is committed here.
 */
public final class E2eTypes {

    public static final String LANE_TYPE = "ai.spec-kitty.lane.transitioned.v1";
    public static final String WP_CREATED_TYPE = "ai.spec-kitty.wp.created.v1";
    public static final String RUN_STARTED_TYPE = "ai.spec-kitty.run.started.v1";

    private E2eTypes() {}

    public static ListedCatalog catalog() {
        RegisteredType lane = new RegisteredType(
                LANE_TYPE,
                "https://schemas.example.invalid/spec-kitty/lane.transitioned.v1",
                "4.0.0",
                new TypeEntry("lane.transitioned", 1, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.WORK_PACKAGE,
                        List.of(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("at", "time"),
                                new TypeEntry.Lift("mission_slug", "missionslug"), new TypeEntry.Lift("mission_id", "missionid"),
                                new TypeEntry.Lift("wp_id", "subject"), new TypeEntry.Lift("actor", "actor"),
                                new TypeEntry.Lift("execution_mode", "executionmode")),
                        List.of(), Map.of()),
                Shape.FLAT, null, null, null, Map.of(), new ActorRule("actor", ActorKind.ACTOR_IDENTITY, "actorbinding"));
        RegisteredType wpCreated = new RegisteredType(
                WP_CREATED_TYPE,
                "https://schemas.example.invalid/spec-kitty/wp.created.v1",
                "4.0.0",
                new TypeEntry("wp.created", 1, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.WORK_PACKAGE,
                        List.of(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("timestamp", "time"),
                                new TypeEntry.Lift("aggregate_id", "subject")),
                        List.of("event_type", "aggregate_type", "schema_version", "project_uuid", "project_slug"), Map.of()),
                Shape.PAYLOAD_ENVELOPE, "event_type", "WPCreated", "payload", Map.of(), null);
        RegisteredType runStarted = new RegisteredType(
                RUN_STARTED_TYPE,
                "https://schemas.example.invalid/spec-kitty/run.started.v1",
                "4.0.0",
                new TypeEntry("run.started", 1, TypeEntry.Scope.MISSION, TypeEntry.SubjectRule.NONE,
                        List.of(new TypeEntry.Lift("event_id", "id"), new TypeEntry.Lift("timestamp", "time")),
                        List.of("event_type", "row_version"), Map.of()),
                Shape.PAYLOAD_ENVELOPE, "event_type", "MissionRunStarted", "payload", Map.of(), null);
        return new ListedCatalog(List.of(lane, wpCreated, runStarted));
    }
}
