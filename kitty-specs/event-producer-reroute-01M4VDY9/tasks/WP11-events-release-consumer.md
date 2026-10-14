---
work_package_id: WP11
title: 'Events-release consumer: pin fetch, schema-backed validator, AC-029, released comparator fixtures, floor bump (events-release gated)'
dependencies:
- WP04
requirement_refs:
- FR-005
- FR-006
- FR-013
- FR-014
- FR-015
- FR-017
- FR-040
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T071
- T072
- T073
- T074
- T075
- T076
- T077
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/
create_intent:
- services/event-producer/gradle/events-pin.properties
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/released/RegistryDataSubsetTest.java
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/released/ReleasedComparatorFixturesTest.java
- tests/architectural/test_no_committed_events_schema.py
- tests/architectural/pinning_rule_inventory.json
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/gradle/events-pin.properties
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/schema/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/schema/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/released/RegistryDataSubsetTest.java
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/released/ReleasedComparatorFixturesTest.java
- tests/contract/spec_kitty_events_consumer/**
- tests/architectural/test_no_committed_events_schema.py
- .kittify/release/shared-package-compatibility.json
- .github/workflows/check-spec-kitty-events-alignment.yml
- tests/architectural/pinning_rule_inventory.json
- uv.lock
role: implementer
tags: []
tracker_refs: []
---

# WP11: Events-release consumer: pin fetch, schema-backed validator, AC-029, released comparator fixtures, floor bump (events-release gated)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Consume the released spec-kitty-events artifact: pin and fetch task, schema-backed `RowSchemaValidator` adapter, the AC-029 guard, the released comparator fixtures, contract tests, and the `spec-kitty-events` floor bump with `uv.lock`.

## Context

Plan section 6 (all), 9, 11; research R-15, R-24. **External dependency: the spec-kitty-events PR (plan section 6) must be released first (FR-040).** It is not a lane of this mission (see tasks-overview.md). One schema set, one writer per type: events#92 keeps the envelope schema, validator, upcaster and registry; this WP reads only the FR-014 list. **Chokepoint:** last editor of `pyproject.toml` (after WP01). Additive edits to `adapter/build.gradle.kts` for the fetch task (WP01 file, serial). Not owned by this WP: the Java dependency set (settled in WP01).

## Precondition gate (T071): EVENTS RELEASE (blocks approval, not authoring)

**First task.** Obtain `V_E` (the version assigned by the events repo; no version is assigned here, C-006). WP11 may be authored and tested against a pinned **released** version; `-PeventsWheel=<path>` exists for development and is refused when `CI` is set. **WP11 is not approved, and the mission PR is not marked ready, until `events-pin.properties` names the released wheel.** If no released wheel exists: author what is possible, then STOP and report.

## Subtask T071: Release gate check

**Steps**: confirm the released wheel's URL and sha256 (PyPI is the only release channel). Record `V_E`.

## Subtask T072: Pin and fetch task (red first)

**Steps**: `gradle/events-pin.properties` (URL, sha256); a plain Gradle task fetching `spec_kitty_events-<V_E>-py3-none-any.whl`, sha256 checked **before** unzip, schemas and registry extracted into `build/` and embedded as native-image resources. Nothing from the events repo is committed: `tests/architectural/test_no_committed_events_schema.py` with floor and planted-schema mutation (AC-027). **Covers** FR-014 (consumer), FR-040, C-008.

## Subtask T073: Schema-backed validator adapter

**Steps**: `adapter/schema/**` implements `RowSchemaValidator` by the registry (type, scope, `subject` rule, lifted members, schema file, `dataschema` URIs) so Java has no per-type code. Contract tests against the pinned wheel: per in-scope type a valid and an invalid line, legacy-row-form variants. **Covers** FR-006, FR-015, FR-017, AC-002.

## Subtask T074: AC-029 guard (FR-005)

**Steps**: `RegistryDataSubsetTest` in the `:domain` test package `released` (WP11 owns that package; WP02 owns the other test packages): over every type of data-model section 3 driven by the registry, assert the `data` key set is a subset of the persisted row's keys (for lifecycle rows, its `payload`) plus the lifted-member rules (`actorbinding` only for a structured actor). Floor: every section 3 type resolved. Mutation: an injected member (a `meta.json` or environment value) fails. **Covers** FR-005, AC-029.

## Subtask T075: Released comparator and nine fixtures

**Steps**: run the released comparator specification and fixtures through the Java `order` package (AC-008 against the released copy; floor all nine); produce the confirmation list for the events side (INTERIM names, `source` tie-break, `schemaversion`, `dataschema` base URI). **Covers** FR-013, AC-008.

## Subtask T076: Floor bump and consumer contract

**Steps**: raise `spec-kitty-events` to `V_E` in `pyproject.toml` (last editor), `uv.lock` follows (`uv lock --check`); extend `tests/contract/spec_kitty_events_consumer/` pinning the new schema surface (removing a schema in the events repo breaks them); update `test_events_dependency_floor_rejects_pre_v10_contract`, `pinning_rule_inventory.json`, `.kittify/release/shared-package-compatibility.json`, `check-spec-kitty-events-alignment.yml`. No path, git or editable override merged. **Covers** FR-040, AC-027.

## Subtask T077: Gate pass

**Steps**: Gradle `test`; `tests/architectural/test_pyproject_shape.py test_shared_package_boundary.py`, `tests/release/test_pinning_inventory_fresh.py`, `tests/contract/test_gradle_pin_check.py`; `uv lock --check`; ruff check and format on touched Python.

## Gates

Gradle (JaCoCo floors unchanged), the Python files named, `make lint`. Red-first, revert-checked; Python only in the consumer tests and `pyproject.toml`/`uv.lock` (no new runtime code).

## Definition of Done

T071 to T077 done; pin names a released wheel; AC-027, AC-029, AC-008 green.

## Risks

Events release slips (blocks WP07, WP09, WP10, WP11 approval); events-repo file names unverified (plan 17); wheel-only fetch relies on a stable layout.

## Reviewer Guidance

Confirm nothing from the events repo is committed, sha256 is verified before unzip, and the floor bump is the only `pyproject.toml` change after WP01.

## Implementation command

`spec-kitty agent action implement WP11 --agent claude --mission event-producer-reroute-01M4VDY9`
