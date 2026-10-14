---
work_package_id: WP04
title: Java :application Produce, launcher protocol and wiring, fault vocabulary, journal-row mission identity
dependencies:
- WP03
requirement_refs:
- FR-001
- FR-005
- FR-006
- FR-007
- FR-009
- FR-015
- FR-016
- FR-017
- FR-018
- FR-022
- FR-025
- FR-038
- NFR-001
- NFR-006
- NFR-007
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T023
- T024
- T025
- T026
- T027
- T028
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/application/
create_intent:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/produce/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/ArchitectureTest.java
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/produce/**
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/*.java
- services/event-producer/launcher/src/main/resources/META-INF/native-image/ai.speckitty/producer-launcher/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/protocol/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/e2e/**
- services/event-producer/launcher/src/test/resources/protocol/**
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/produce/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/produce/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/ArchitectureTest.java
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/*.java
- services/event-producer/launcher/src/main/resources/META-INF/native-image/ai.speckitty/producer-launcher/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/protocol/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/e2e/**
- services/event-producer/launcher/src/test/resources/protocol/**
role: implementer
tags: []
tracker_refs: []
---

# WP04: Java :application Produce, launcher protocol and wiring, fault vocabulary, journal-row mission identity

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Implement the `:application` `Produce` use case behind ports, and the `:launcher` protocol (stdin/stdout, exit codes, deadline watchdog, mission-identity rule for journal rows).

## Context

Plan sections 1, 4.2 (Journal route, Mission identity), 5 (protocol), 9; data-model sections 1, 2, 3, 6; research R-4, R-24. `Produce` takes the `RowSchemaValidator` port, declared by WP03 with the other ports (this WP defines none; a needed signature change is a recorded second-editor edit of `application/port/**`). Unit tests use a fake validator; the real schema-backed adapter is WP11. **Composition root:** this WP owns the launcher entry and `Wiring` (the class that constructs `Produce` with the real adapters). **Until WP11 binds the released schema adapter the default binding is a validator that fails closed (refuses every row with a named reason), never accept-all**, so the real binary cannot validate nothing and still look green; WP11 T084 replaces the binding. Unaffected by the events release.

## Subtask T023: Remove spike, ArchUnit, composition root

**Steps**: first commit deletes WP01's `launcher/.../spike/**` and its `META-INF/native-image/ai.speckitty/producer-spike/` directory (a **recorded out-of-map deletion**: WP04 replaces the spike; the deletion commit is separate and says so). ArchUnit test (`ArchitectureTest`): `:application` imports no `:adapter` or Jackson (floor + self-mutation). `Wiring` constructs `Produce` from the real adapters of WP03 and the **fail-closed default `RowSchemaValidator`** (test: with the default binding a well-formed row replies `rejected` with reason `validator-unavailable`; mutation: an accept-all default must fail it). Operation dispatch: an `OperationHandler` interface and a dispatcher; `produce` is registered directly; further operations are discovered with `java.util.ServiceLoader` from `META-INF/services/ai.speckitty.producer.launcher.OperationHandler` (see T027).

## Subtask T024: `Produce`

**Steps**: red tests first. Accept `row`, `slot`, `seam_args`, `start_path`; map to a CloudEvents 1.0 object (`id` = row `event_id` only, a row without one is refused; `time`; `source`; `type` prefix `ai.spec-kitty`; `dataschema`; `datacontenttype`; `subject`; extensions `lamportclock`, `nodeid`, `actor`/`executionmode` as extension attributes); validate via the port before writing; no member beyond the persisted row and envelope attributes (FR-005 structurally; AC-029 in WP11); stamp, dedupe (`skipped_duplicate`), append through the ports. `reason_source` round-trips (AC-021). **Lane vocabulary (AC-020, producer side):** Java holds no lane mapping code; the alias (`doing` to `in_progress`) and the persisted-vocabulary exclusion (`uninitialized` refused) are registry and schema data. Cases: a legacy `to_lane` of `doing` appears as `in_progress` in `data` and a `to_lane` of `uninitialized` is `rejected`, both with a **test-local registry entry**; WP11 T073 repeats them against the released registry. **Covers** FR-001 (Java side), FR-005, FR-006, FR-007, FR-015, FR-016, FR-017, FR-018, AC-001 (Java, with the fake validator; not vacuous because the default binding fails closed), AC-002 (fake validator here; the released schema in WP11), AC-011, AC-020 (producer half), AC-021.

## Subtask T025: Refusals and fault classes

**Steps**: `rejected` (UUID id, bad time, validation failure, row without id, **unresolvable root**, **type absent from the registry**, **unknown scope**), `clock-overflow`, `busy` (lock timeout); a refusal is a reply with a named reason, never an exception. The unresolvable-root reply (start path with no repository above it, from WP03 T018) carries the reason `root-unresolvable` and the client writes no fault-log row for it (there is no root to hold one); the stderr warning still appears. An unknown type or scope is never written (the silent-success path of an empty or permissive registry). AC-005 Java classes. **Covers** FR-022 (Java), AC-005.

## Subtask T026: Mission identity (OD-MISSIONID (b))

**Steps**: `missionid` from `row.payload.mission_id` when non-empty, else `meta.json` in `start_path` when that directory holds one, else project-scoped `source` with `subject` = the aggregate, no `missionid`, **no fault**. Java never reads `seam_args`. Tests: empty payload `mission_id` plus `meta.json` gives that `missionid`; no `meta.json` gives project-scoped source (AC-017 Java half); **a slug-only payload** still gets the project-scoped `source` and is never given the slug as `missionid`; an empty-string `missionid` is never emitted. Mutations, each red: the producer refuses such a row, faults it, emits an empty `missionid`, or emits the slug as `missionid`. The client half (no stderr line, no fault-log row, fault count and exit code unchanged) is asserted by WP06 T036 and WP10 T066. **Covers** FR-009.

## Subtask T027: Launcher protocol

**Steps**: `[<binary>]` with no args; read one UTF-8 JSON object from stdin, write one to stdout; `"v": 1`; exit 0 (reply written), 64 (unreadable request or unsupported `v`), 75 (deadline watchdog on `deadline_ms`); never echo a row, absolute path or stack trace (NFR-006); no network (NFR-001). **Registration mechanism (explicit, so WP05 edits no WP04 file):** the dispatcher loads extra operations through `ServiceLoader<OperationHandler>`; WP05 registers `retain` by adding the one provider-configuration file `launcher/src/main/resources/META-INF/services/ai.speckitty.producer.launcher.OperationHandler` (WP05-owned); until then `retain` replies `refused` with an unsupported-operation reason (test). **Golden protocol fixtures:** the real launcher (JVM build) generates request, reply and exit-code fixtures for `produce` (valid, using the test-local validator; malformed; `v` 2; deadline) under `launcher/src/test/resources/protocol/`, committed; WP06's stub binaries and `_classify` tests replay these files, so the client cannot pass against a fiction (field names, exit codes 64 and 75, `v`). A launcher test regenerates them and fails on drift. **Native-image metadata:** this WP's own directory `launcher/src/main/resources/META-INF/native-image/ai.speckitty/producer-launcher/`. **Covers** FR-025 (Java), NFR-001, NFR-006.

## Subtask T028: End-to-end launcher tests and hygiene

**Steps**: launcher tests that spawn the JVM build with the real file-system adapters on a temp repo and a **test-local validator injected through a test-only `Wiring` factory** (the default binding fails closed and is covered by T023): one valid row appended; re-fire gives one line; no write under `kitty-specs/` (AC-016 Java half; FR-038); scan of authored members for home paths and usernames (AC-035); JaCoCo `:application` 90%. NFR-007: at most one row per `(source, id)`.

## Gates

`gradle -p services/event-producer check` (all subprojects, JaCoCo enforced by `check`); no Python. Red-first, revert-checked.

## Definition of Done

T023 to T028 done; protocol versioning tested (`v` 2 gives 64); fail-closed default tested with an accept-all mutation red; golden protocol fixtures committed; coverage floors met; native-smoke green.

## Risks

Protocol shape is the contract for WP06; changing it after WP06 starts needs `v` handling per plan section 5.

## Reviewer Guidance

Check the producer never mints an id, never reads `seam_args`, and that every failure path yields a reply or a documented exit code.

## Tests, markers, jobs

All Java: `gradle-test` three-OS matrix (`check`); the launcher end-to-end tests also run on the `native-smoke` leg through the built binary.

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `launcher/.../launcher/spike/**` and `META-INF/native-image/ai.speckitty/producer-spike/**` (WP01) | delete | WP04 replaces the spike; WP01 is closed; own commit, first in WP04 |
| `application/.../port/**` (WP03) | only if a signature must change | recorded with the reason in the WP notes; no second interface |

## Implementation command

`spec-kitty agent action implement WP04 --agent claude --mission event-producer-reroute-01M4VDY9`
