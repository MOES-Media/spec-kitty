---
work_package_id: WP04
title: Java :application Produce, ports, launcher protocol, fault vocabulary, journal-row mission identity
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
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/ArchitectureTest.java
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/produce/**
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/port/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/produce/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/ArchitectureTest.java
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/*.java
- services/event-producer/launcher/src/test/**
role: implementer
tags: []
tracker_refs: []
---

# WP04: Java :application Produce, ports, launcher protocol, fault vocabulary, journal-row mission identity

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Implement the `:application` `Produce` use case behind ports, and the `:launcher` protocol (stdin/stdout, exit codes, deadline watchdog, mission-identity rule for journal rows).

## Context

Plan sections 1, 4.2 (Journal route, Mission identity), 5 (protocol), 9; data-model sections 1, 2, 3, 6; research R-4, R-24. `Produce` takes the `RowSchemaValidator` port; tests use a fake (the real schema-backed adapter is WP11). Ports are interfaces only. Unaffected by the events release.

## Subtask T023: Remove spike, define ports

**Steps**: first commit deletes WP01's `launcher/.../spike/**` and its native-image entries only if superseded. Ports in `application.port`: `RowSchemaValidator`, `PublishLog`, `ClockStore`, `RepoLocator`, `NodeIdentity`, `Clock`. ArchUnit test: `:application` imports no `:adapter` or Jackson (floor + self-mutation).

## Subtask T024: `Produce`

**Steps**: red tests first. Accept `row`, `slot`, `seam_args`, `start_path`; map to a CloudEvents 1.0 object (`id` = row `event_id` only, a row without one is refused; `time`; `source`; `type` prefix `ai.spec-kitty`; `dataschema`; `datacontenttype`; `subject`; extensions `lamportclock`, `nodeid`, `actor`/`executionmode` as extension attributes); validate via the port before writing; no member beyond the persisted row and envelope attributes (FR-005 structurally; AC-029 in WP11); stamp, dedupe (`skipped_duplicate`), append through the ports. `reason_source` round-trips (AC-021). **Covers** FR-001 (Java side), FR-005, FR-006, FR-007, FR-015, FR-016, FR-017, FR-018, AC-001 (Java), AC-002 (fake validator), AC-011, AC-021.

## Subtask T025: Refusals and fault classes

**Steps**: `rejected` (UUID id, bad time, validation failure, row without id), `clock-overflow`, `busy` (lock timeout); a refusal is a reply, never an exception. AC-005 Java classes. **Covers** FR-022 (Java), AC-005.

## Subtask T026: Mission identity (OD-MISSIONID (b))

**Steps**: `missionid` from `row.payload.mission_id` when non-empty, else `meta.json` in `start_path` when that directory holds one, else project-scoped `source` with `subject` = the aggregate, no `missionid`, **no fault**. Java never reads `seam_args`. Tests: empty payload `mission_id` plus `meta.json` gives that `missionid`; no `meta.json` gives project-scoped source (AC-017 Java half). **Covers** FR-009.

## Subtask T027: Launcher protocol

**Steps**: `[<binary>]` with no args; read one UTF-8 JSON object from stdin, write one to stdout; `"v": 1`; exit 0 (reply written), 64 (unreadable request or unsupported `v`), 75 (deadline watchdog on `deadline_ms`); never echo a row, absolute path or stack trace (NFR-006); no network (NFR-001). The `retain` operation is routed to a registration point WP05 fills; until then `retain` replies `refused` with an unsupported-operation reason. **Covers** FR-025 (Java), NFR-001, NFR-006.

## Subtask T028: End-to-end launcher tests and hygiene

**Steps**: launcher tests that spawn the JVM build with real ports on a temp repo: one valid row appended; re-fire gives one line; no write under `kitty-specs/` (AC-016 Java half; FR-038); scan of authored members for home paths and usernames (AC-035); JaCoCo `:application` 90%. NFR-007: at most one row per `(source, id)`.

## Gates

`gradle -p services/event-producer test` (all subprojects); no Python. Red-first, revert-checked.

## Definition of Done

T023 to T028 done; protocol versioning tested (`v` 2 gives 64); coverage floors met.

## Risks

Protocol shape is the contract for WP06; changing it after WP06 starts needs `v` handling per plan section 5.

## Reviewer Guidance

Check the producer never mints an id, never reads `seam_args`, and that every failure path yields a reply or a documented exit code.

## Implementation command

`spec-kitty agent action implement WP04 --agent claude --mission event-producer-reroute-01M4VDY9`
