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
- T072
- T073
- T074
- T075
- T076
- T077
- T084
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/
create_intent:
- services/event-producer/gradle/events-pin.properties
- services/event-producer/gradle/events-fetch.gradle.kts
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/schema/**
- services/event-producer/adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-schema/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/binding/**
- tests/architectural/test_no_committed_events_schema.py
- tests/architectural/pinning_rule_inventory.json
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/gradle/events-pin.properties
- services/event-producer/gradle/events-fetch.gradle.kts
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/schema/**
- services/event-producer/adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-schema/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/schema/**
- services/event-producer/adapter/src/test/resources/schema/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/binding/**
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

Plan section 6 (all), 9, 11; research R-15, R-24. **External dependency: the spec-kitty-events PR (plan section 6) must be released first (FR-040).** It is not a lane of this mission (see tasks-overview.md). One schema set, one writer per type: events#92 keeps the envelope schema, validator, upcaster and registry; this WP reads only the FR-014 list. **Chokepoint:** last editor of `pyproject.toml` (after WP01), a recorded edit. The fetch task lives in the WP11-owned script plugin `gradle/events-fetch.gradle.kts`, which WP01's `adapter/build.gradle.kts` applies when present, so no WP01 build file is edited. Not owned by this WP: the Java dependency set (settled in WP01). **Check open PRs** on the owned files before the first commit: PR #6205 adopts a `spec-kitty-events` floor and rewrites `pyproject.toml`, `uv.lock` and `.kittify/release/shared-package-compatibility.json`; rebase onto it (or take theirs and re-apply this WP's floor) before T076. The claim "the floor bump is the only `pyproject.toml` change after WP01" is relative to the base at that time.

## Precondition gate: EVENTS RELEASE (blocks approval and the released-artifact runs, not authoring)

One definition, the same in every gated WP (tasks-overview section 2): "complete" means WP11 **approved** with `events-pin.properties` naming the released wheel. Obtain `V_E` (the version assigned by the events repo; no version is assigned here, C-006). WP11 may be authored and tested against a pinned **released** version; `-PeventsWheel=<path>` exists for development and is refused when `CI` is set. **WP11 is not approved, and the mission PR is not marked ready, until `events-pin.properties` names the released wheel.** If no released wheel exists: author what is possible, then STOP and report.

## Subtask T072: Pin and fetch task (red first)

**Steps**: first confirm the released wheel's URL and sha256 (PyPI is the only release channel) and record `V_E` and the sha256 in the WP notes. `gradle/events-pin.properties` (URL, sha256); a plain Gradle task in `gradle/events-fetch.gradle.kts` fetching `spec_kitty_events-<V_E>-py3-none-any.whl`, sha256 checked **before** unzip, schemas and registry extracted into `build/` and embedded as native-image resources. Nothing from the events repo is committed: `tests/architectural/test_no_committed_events_schema.py` with floor and planted-schema mutation (AC-027). **Silent-success negatives (red tests):** a planted wrong sha256 fails the fetch **before** unzip; an empty or missing registry fails the build (a wheel whose extracted registry or schema set is empty must never yield a validator that accepts everything); a floor that the extracted schema count is above zero. The task is mandatory: a test asserts `events-fetch.gradle.kts` exists and the task is wired (WP01's optional-apply hook cannot silently skip it). **Native-image metadata:** resource-config for the embedded schemas and registry in this WP's own directory `adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-schema/`. **Covers** FR-014 (consumer), FR-040, C-008.

## Subtask T073: Schema-backed validator adapter

**Steps**: `adapter/schema/**` implements `RowSchemaValidator` by the registry (type, scope, `subject` rule, lifted members, schema file, `dataschema` URIs) so Java has no per-type code. Contract tests against the pinned wheel: per in-scope type a valid and an invalid line, legacy-row-form variants; **a row of a type absent from the registry, and an unknown scope, are `rejected` with a named reason and are never written**; a floor that the per-type loop iterated **every** data-model section 3 type; and the AC-020 producer observables against the released registry (`doing` is published as `in_progress`, `uninitialized` is refused; WP04 T024 holds the test-local twin). **Covers** FR-006, FR-015, FR-017, AC-002, AC-020 (producer half).

## Subtask T074: AC-029 guard (FR-005)

**Steps**: `RegistryDataSubsetTest` in the `:adapter` test package `schema.released` (under WP11's `adapter/src/test/.../adapter/schema/**`; it drives the `:domain` envelope and `order` classes from the `:adapter` test classpath, because the fetch script and the extracted registry are `:adapter` build outputs and `:domain` depends on `java.base` only and has no pre-wired fetch hook, R3/R9 of the overview; the `:domain` classes are on the `:adapter` test classpath by WP01 T003 step 9, so this WP edits no build file): over every type of data-model section 3 driven by the registry, assert the `data` key set is a subset of the persisted row's keys (for lifecycle rows, its `payload`) plus the lifted-member rules (`actorbinding` only for a structured actor). Floor: every section 3 type resolved. Mutation: an injected member (a `meta.json` or environment value) fails. **Covers** FR-005, AC-029.

## Subtask T075: Released comparator and nine fixtures

**Steps**: `ReleasedComparatorFixturesTest` lives in the `:adapter` test package `schema.released` (same reason as T074); run the released comparator specification and fixtures through the `:domain` `order` package from there (AC-008 against the released copy; floor all nine; the **mutant table of WP02 T013** (naive, no-source, raw-id dedupe, invalid survivor, dropped error row) is consumed here **from `testFixtures(project(":domain"))`** (`domain/src/testFixtures/**`, WP02-owned, wired by WP01 T003 step 9): no copy exists in this WP, so the two runs cannot drift, and each mutant must be red against the released fixtures; a floor asserts the five mutants were iterated); produce the confirmation list for the events side (INTERIM names, `source` tie-break, `schemaversion`, `dataschema` base URI). **Covers** FR-013, AC-008.

## Subtask T076: Floor bump and consumer contract

**Steps**: raise `spec-kitty-events` to `V_E` in `pyproject.toml` (last editor), `uv.lock` follows (`uv lock --check`); extend `tests/contract/spec_kitty_events_consumer/` pinning the new schema surface (removing a schema in the events repo breaks them); run `tests/architectural/test_pyproject_shape.py` **unchanged** (`test_events_dependency_floor_rejects_pre_v10_contract` there only mutates the dependency to the retired `>=9,<10` range, so a floor bump to `V_E` needs no edit; the file is not owned by WP11 and an edit of it would have to be added to the recorded out-of-map edits and the overview section 4 table with the reason), update `pinning_rule_inventory.json`, `.kittify/release/shared-package-compatibility.json`, `check-spec-kitty-events-alignment.yml`. No path, git or editable override merged. **Covers** FR-040, AC-027.

## Subtask T084: Bind the schema adapter in the launcher
**Steps**: red test first, in `launcher/src/test/java/.../launcher/binding/**` (WP11-owned): with the **released** schema adapter bound in `Wiring`, a launcher test with an invalid row replies `rejected` using the released schema, and a valid row of every in-scope type is accepted; WP04's fail-closed validator stays in the tree as the **explicit fallback** `Wiring.failClosedFallback()` (selected only when no registry is embedded; every WP11 build embeds it), and `Wiring.production()` now returns the schema-backed validator. WP04's own tests construct the fallback through that explicit factory and never through `production()`, so **no WP04-owned test is edited here** and none goes red; an accept-all binding of `production()` is a mutation that must fail this WP's binding test. The edit is one binding in `launcher/.../Wiring.java` (WP04's composition root; a recorded second-editor edit). `Wiring` takes the registry-driven validator from `adapter/schema`; the `native-smoke` leg builds the real launcher with the schemas embedded and the metadata of T072. **Native write path:** replace WP04's `launcher/src/test/resources/protocol/smoke-request.json` with a request carrying a valid row of one in-scope type (a recorded second-editor edit), so the shared smoke action takes the write path in the native binary on each leg (FFM `gethostname`, lock, clock store, publish log, schema validation); the `producer-adapter-fs`, `producer-launcher` and `producer-schema` metadata are first exercised end to end here, and a missing registration is fixed in this WP as a fix-only edit of the owning directory. **Covers** FR-006, FR-015, AC-001, AC-002 (the real binary validates).

## Subtask T077: Gate pass

**Steps**: Gradle `check`; `tests/architectural/test_pyproject_shape.py test_shared_package_boundary.py`, `tests/release/test_pinning_inventory_fresh.py`, `tests/contract/test_gradle_pin_check.py`; `uv lock --check`; ruff check and format on touched Python.

## Gates

Gradle `check` (JaCoCo floors unchanged, enforced by `check`), the Python files named, `make lint`. Red-first, revert-checked; Python only in the consumer tests and `pyproject.toml`/`uv.lock` (no new runtime code).

## Definition of Done

T072 to T077 and T084 done; pin names a released wheel; AC-027, AC-029, AC-008 green; the real launcher validates against the released schema.

## Risks

Events release slips (blocks approval of WP09, WP10, WP11, WP12; WP07 is no longer gated); events-repo file names unverified (plan 17); wheel-only fetch relies on a stable layout.

## Reviewer Guidance

Confirm nothing from the events repo is committed, sha256 is verified before unzip, and the floor bump is the only `pyproject.toml` change after WP01.

## Tests, markers, jobs

| New or extended test file | Marker | Runs in |
|---|---|---|
| Java tests (`adapter/schema`, `released`, `binding`) | n/a | `gradle-test` three-OS matrix (`check`) |
| `tests/architectural/test_no_committed_events_schema.py` | `architectural` | architectural tier |
| `tests/contract/spec_kitty_events_consumer/**` | existing `contract` marker of that folder | contracts shard |

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `pyproject.toml` (WP01) | raise the `spec-kitty-events` floor to `V_E` | last editor, after WP01 (R3) |
| `launcher/src/main/java/ai/speckitty/producer/launcher/Wiring.java` (WP04) | `production()` binds the schema-backed validator; `failClosedFallback()` is kept | the fail-closed validator is demoted to an explicit fallback, WP04's tests target the fallback only; after WP04 |
| `launcher/src/test/resources/protocol/smoke-request.json` (WP04) | replace with a valid-row request | makes native-smoke take the write path; after WP04 |
| `adapter/.../META-INF/native-image/ai.speckitty/producer-adapter-fs/**` (WP03), `launcher/.../META-INF/native-image/ai.speckitty/producer-launcher/**` (WP04) | fix-only, if the first native write path shows a missing registration | recorded with the reason |

## Implementation command

`spec-kitty agent action implement WP11 --agent claude --mission event-producer-reroute-01M4VDY9`
