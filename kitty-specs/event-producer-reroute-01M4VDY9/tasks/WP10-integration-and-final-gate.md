---
work_package_id: WP10
title: 'Integration: end-to-end default install, AC-036 measurement, mid-flight Mission, final gate pass (events-release gated)'
dependencies:
- WP09
- WP11
- WP12
requirement_refs:
- FR-038
- FR-043
- NFR-001
- NFR-002
- NFR-004
- NFR-006
- NFR-007
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T065
- T066
- T067
- T068
- T069
- T070
- T083
history: []
agent_profile: python-pedro
authoritative_surface: tests/
create_intent:
- tests/integration/test_event_producer_e2e.py
- tests/integration/test_event_producer_degraded.py
- tests/perf/test_event_producer_overhead.py
- tests/integration/_native_binary.py
- tests/integration/test_native_binary_helper.py
execution_mode: code_change
model: ''
owned_files:
- tests/integration/test_event_producer_e2e.py
- tests/integration/test_event_producer_degraded.py
- tests/_perf_helpers.py
- tests/perf/test_event_producer_overhead.py
- tests/integration/_native_binary.py
- tests/integration/test_native_binary_helper.py
role: implementer
tags: []
tracker_refs: []
---

# WP10: Integration: end-to-end default install, AC-036 measurement, mid-flight Mission, final gate pass (events-release gated)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Prove the whole mission end to end on a default install, take the AC-036 measurement and set its limits, and run the final gate pass.

## Context

Plan sections 9 (performance, CI home), 13 item 8, 17; research R-9, R-17. Read-mostly: tests and measurements. **External dependency: gated on the spec-kitty-events release** (runs against the real released wheel and real binary). Direct dependencies WP09, WP11 and WP12 imply every other WP transitively (tasks-overview R6).

## Precondition gate: EVENTS RELEASE (blocks approval and the schema-reading and measurement tasks, not authoring)

One definition, the same in every gated WP (tasks-overview section 2): `events-pin.properties` and the `pyproject.toml` floor name the released wheel `V_E` and WP11 is approved. Record `V_E` and the pin sha256 in the WP notes. If not: author what is possible, then **STOP and report**. The mission PR is not marked ready before this passes.

## Where the real-binary tests run (no skip-on-missing)

Python PR shards never build the GraalVM binary, so a `skipif binary-missing` fallback would pass vacuously. The real-binary tests (T065 to T068) carry the existing `integration` marker (excluded from `make test-fast`) and run in the `native-e2e` job of `event-producer.yml` (T083), per OS, **by named file**. They obtain the binary through `tests/integration/_native_binary.py::require_native_binary()`: when `REQUIRE_NATIVE_PRODUCER=1` (set by that job, and by `CI`) a missing binary is a **failure**, never a skip; a skip is allowed only locally, with a message. `tests/integration/test_native_binary_helper.py` carries the planted-missing-binary mutation: with the flag set and no binary the helper must raise.

## Subtask T065: Default-install end to end
**Steps**: red tests then green: a default install (platform wheel built via the hook, no setting, drain off, network denied) appends one publish-log line per in-scope type whose `id` is the ledger row's normalised `event_id`, from a lane worktree, a coordination worktree and a **Mission subdirectory**, per slot; the line validates against the released schema; a degraded run (missing binary, crash stub, slow stub) exits 0, leaves every `--json` body unchanged and records a warning (never silent). An unresolvable root gives the stderr warning, `rejected`, and **no fault-log row**. Orchestrator-api contract tests and `--json` snapshots unchanged (AC-018). **Covers** AC-001, AC-002, AC-005, AC-018, NFR-001.

## Subtask T066: Compatibility and freeze
**Steps**: read every archived Mission's `status.events.jsonl` with the CLI readers: byte-identical afterwards (floor at least one read; mutation: a reader that rewrites); the producer on a scratch repo changes no `kitty-specs/**` file on `main` and creates none (AC-016); a Mission open before upgrade keeps its ledger byte-unchanged and its publish log starts at the first post-upgrade event; a Mission without `mission_id` publishes project-scoped and, end to end, shows **empty stderr, an unchanged fault log, an unchanged `fault_summary`, and a byte-identical exit code and `--json` body** compared with a run of the same command with the producer absent (AC-017, AC-005, OD-MISSIONID (b)); **torn line end to end (AC-026)**: truncate the publish log mid-line, append, and observe the intact new line, one stderr line and one notice row. **Covers** AC-015, AC-016, AC-017, AC-026, FR-038.

## Subtask T067: `binary-missing` path
**Steps**: clean venv from the sdist, from the pure wheel, and with no matching wheel: lifecycle command succeeds and warns `binary-missing` (AC-025); editable install works (AC-038 runtime half). **Covers** AC-025, FR-043.

## Subtask T068: AC-036 measurement
**Steps**: `tests/_perf_helpers.py` gains relative producer limits (`test_perf_limit_authority.py` rejects absolute ones): `emit_status_transition_batch` with a real producer over the same with a no-op handler, cold and warm, per OS. The perf test runs in the same `native-e2e` job on the three legs, so the numbers that set the limits are reproduced by a CI job; they are recorded in `research/wp10-measurements.md` next to WP01's. On failure apply the NFR-002 contingency: **do not implement batching here; stop and report**, because it requires amending FR-003, FR-026 and AC-004 first (operator decision). **Covers** AC-036, NFR-002.

## Subtask T069: Hygiene and determinism sweep
**Steps**: scan fixtures, fault records and the members the producer authors for absolute home paths or usernames (AC-035); same row, clock and node id give byte-identical lines on all three OS (AC-034, via the three legs of `native-e2e` and the `gradle-test` matrix); no BOM, CR or raw newline. **Covers** AC-034, AC-035, NFR-003, NFR-004, NFR-006, NFR-007.

## Subtask T070: Reflexivity check and final gate pass
**Steps**: confirm no gate depends on the producer for this mission's own events (plan 13.8); run every new gate (thinness, build-glue, census, fault enum, single writer, single lane list, no committed events schema, exemption ledger) with its floor and mutation; every R-7 gate file individually against the baseline; `uv lock --check`, `spec-kitty regen --check`, docs lint, terminology guard; Gradle `check`; record the result list in `research/wp10-measurements.md`. **Covers** AC-028, G-11.

## Subtask T083: `native-e2e` job and the fail-on-missing-binary helper
**Steps**: red first: `tests/integration/_native_binary.py` and its planted-mutation test (above). Then add the job `native-e2e` to `event-producer.yml` (recorded edit): three legs, `needs: native-smoke`, downloads the `producer-<leg>` artifact, builds the platform wheel by hook, installs it by path, sets `REQUIRE_NATIVE_PRODUCER=1`, runs `pytest` on the named files `tests/integration/test_event_producer_e2e.py tests/integration/test_event_producer_degraded.py tests/perf/test_event_producer_overhead.py tests/integration/test_native_binary_helper.py`, and is added to the `needs` list of the terminal `event-producer-gate`. Extend `tests/ci/test_event_producer_workflow.py` (recorded edit) to assert the job, the flag and the gate edge. **Covers** AC-001, AC-018, AC-036 (reproducibility).

## Tests, markers, jobs

| New test file | Marker | Runs in |
|---|---|---|
| `tests/integration/test_event_producer_e2e.py`, `test_event_producer_degraded.py` | `integration` | `native-e2e` (named files, real binary, per OS) |
| `tests/perf/test_event_producer_overhead.py` | `integration` (check the perf tier's existing marker with the completeness gate) | `native-e2e` (per OS) |
| `tests/integration/test_native_binary_helper.py` | `fast` | `tests/integration` shard |

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `.github/workflows/event-producer.yml` (WP01) | add job `native-e2e` and its gate edge | the real-binary tests need a CI home; after WP01 |
| `tests/ci/test_event_producer_workflow.py` (WP01) | assert the job, the flag and the gate edge | same |

## Gates
All of the above; `make lint`, ruff check and format, mypy, targeted pytest, Gradle `check`, build-glue and thinness gates, packaging and release gates (tests only; the real release is not run here), `test_clock_import_ban.py`, `test_clock_call_ban.py`, `test_fast_tier_marker_completeness.py`, `test_module_shard_registry.py`.

## Definition of Done
T065 to T070 and T083 done; no new red against the baseline; AC-036 limits set from CI numbers and green; the planted-missing-binary mutation turns the job red.

## Risks
Per-OS legs run only in CI; latency miss triggers the amendment path.

## Reviewer Guidance
Confirm each AC in the coverage table has a named test, that the real-binary tests cannot skip under CI, and that the measurement numbers, not guesses, set the limits.

## Implementation command

`spec-kitty agent action implement WP10 --agent claude --mission event-producer-reroute-01M4VDY9`
