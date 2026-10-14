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

## Where the real-binary tests run (fail-on-missing only in the one job that builds the binary)

Python PR shards never build the GraalVM binary, so a `skipif binary-missing` fallback inside the `native-e2e` job would pass vacuously. The real-binary tests (T065 to T068) carry the existing `integration` marker (excluded from `make test-fast`) and run in the `native-e2e` job of `event-producer.yml` (T083), per OS, **by named file** (the four files of T083: the three real-binary files and the helper test). They obtain the binary through `tests/integration/_native_binary.py::require_native_binary()`.

**The fail-on-missing switch is `REQUIRE_NATIVE_PRODUCER=1` and nothing else.** `CI` is not consulted. Reason (defect class: any new test that needs a native binary or a job that other lanes do not build): `tests/integration/` and `tests/perf/` are also collected **by directory** outside this mission's workflow, by the nightly `integration-next` job (`pytest tests/integration tests/next`, a member of `nightly-summary.needs`, which the release nightly-gate reads) and by the `integration-slice` lane (`scripts/ci/integration_slice.py::SLICE_MARKER_EXPR`, `integration and not ...` over `tests/integration`). Neither builds the binary, so a CI-wide fail-on-missing would turn both red every night and block the release. The rule is therefore:

- `REQUIRE_NATIVE_PRODUCER=1` (set only by `native-e2e`): a missing or unrunnable binary is a **failure**, never a skip.
- flag unset (every other lane, and a local run): the test **skips** with a message naming the flag. This is not vacuous, because the one job that guarantees the real binary meets the real client is `native-e2e`, and the planted-mutation test below pins that the flag turns a missing binary into a failure.

No new marker, `pytest.ini` line, nightly deselection or edit of `SLICE_MARKER_EXPR` / `ci-nightly.yml` is needed, so none is added (WP09 owns `ci-nightly.yml` and this WP adds no edit of it). `tests/integration/test_native_binary_helper.py` is `fast` and needs no binary: with the flag set and no binary the helper must raise; with the flag unset it must raise the pytest skip with the message (mutation: a helper that skips under the flag, or fails without it, is red). Two guard tests in the same file pin the defect class: (1) no file in `tests/integration` or `tests/perf` outside an allowlist calls `require_native_binary()`; the allowlist is the three real-binary test files (`test_event_producer_e2e.py`, `test_event_producer_degraded.py`, `test_event_producer_overhead.py`) plus the helper test file `test_native_binary_helper.py`, which only exercises the function under a monkeypatched environment and needs no binary (so the real-binary exposure stays the three files), and (2) a nightly-shaped collection (`REQUIRE_NATIVE_PRODUCER` unset, no binary) of the three real-binary files yields only skips and no failure.

## Evidence path for the per-OS runs (who pushes)

The macOS and Windows legs of `native-e2e` run only in CI, the AC-036 limits are set from their numbers, and "the mutation turns the job red" is a job result. The WP agent has no push right, so, mirroring WP01:

1. The agent authors T065 to T070 and T083 (red tests, helper, job, ledger row, path filters), commits, and **reports to the orchestrator that the lane is ready for a CI run**. The relative AC-036 limits are not guessed: they are first committed as a placeholder that only records (no assertion), so the first run measures.
2. The **orchestrator pushes the lane branch** (to a scratch draft pull request, or to the mission pull request once it exists; operator-authorised either way). `native-e2e` runs on `pull_request`.
3. The agent resumes, records the run ids and the per-leg conclusions (`gh run view <id> --json conclusions,jobs`) and the per-OS numbers in `research/wp10-measurements.md`, sets the relative limits from those numbers, and reports again; a **second push** confirms green with the limits asserted (each push's run ids recorded).
4. The planted-missing-binary mutation is shown by a recorded scratch run: a commit that points the job at a missing binary, pushed to the scratch pull request, which must be red, then dropped. Its run id is recorded.

No approval without the run ids. This push is in addition to WP01's feasibility push (tasks-overview R12 (d)).

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
**Steps**: red first: `tests/integration/_native_binary.py` and its planted-mutation test (above). Then add the job `native-e2e` to `event-producer.yml` (recorded edit): three legs, `needs: native-smoke`, downloads the `producer-<leg>` artifact, builds the platform wheel by hook, installs it by path, sets `REQUIRE_NATIVE_PRODUCER=1` (only this job sets it), runs `pytest` on the named files `tests/integration/test_event_producer_e2e.py tests/integration/test_event_producer_degraded.py tests/perf/test_event_producer_overhead.py tests/integration/test_native_binary_helper.py`, and is added to the `needs` list of the terminal `event-producer-gate`.
**Path filters (so the guard survives the merge):** `event-producer.yml` today fires only on `services/event-producer/**`, `scripts/release/**`, `pyproject.toml`, the smoke action, itself and `producer-native.yml` (WP01 T007), plus WP06's `src/specify_cli/status/producer_*.py`. A later change to the seam, the call sites, the e2e tests or the perf test would not run `native-e2e`. Add these globs (each verified to match a tracked path when added, `tests/architectural/test_workflow_coherence.py` as validation): `tests/integration/test_event_producer_*.py`, `tests/integration/_native_binary.py`, `tests/integration/test_native_binary_helper.py`, `tests/perf/test_event_producer_overhead.py`, `tests/_perf_helpers.py`, and the seam files of plan 4.2: `src/specify_cli/status/adapters.py`, `src/specify_cli/status/emit.py`, `src/specify_cli/status/lifecycle_events.py`, `src/specify_cli/coordination/outbound.py`, `src/specify_cli/coordination/status_transition.py`, `src/specify_cli/decisions/emit.py`, `src/specify_cli/retrospective/lifecycle_events.py`, `src/specify_cli/events/runtime_moments.py`, `src/specify_cli/events/decision_log.py`, `src/runtime/next/_internal_runtime/events.py`, `src/runtime/next/runtime_bridge_retrospective.py`, `src/runtime/next/_internal_runtime/engine.py`, `src/specify_cli/cli/commands/_event_producer_doctor.py`. **Duplicate-suite ledger (same commit as the job, recorded edit of a WP01 file):** `event-producer.yml` triggers on `pull_request`, so `native-e2e` is a change-triggered job that reaches pytest and `tests/architectural/test_no_duplicate_suite_execution.py` is red without a row. Add to `AUTHORIZED_PER_CHANGE_SUITE_JOBS` (the closed ledger in that file) the key `("event-producer.yml", "native-e2e")` with the reason: the only job that builds the real GraalVM binary, so every other lane skips these files (flag unset) and nothing runs them twice. Keep exactly one pytest invocation per matrix leg (`AUTHORIZED_SUITE_INVOCATIONS_PER_JOB = 1`; the four files go in the one command). The row cannot be added earlier (the stale-row check, `test_every_authorized_ledger_row_still_names_a_live_suite_job`, rejects a row for a job that does not exist) and the gate file is run in WP10's gates. Sweep result for the mission: `verify-pins`, `gradle-test`, `native-smoke` and `event-producer-gate` (WP01) reach no pytest, WP09 adds no change-triggered job (`release.yml` is a tags-only push and `ci-nightly.yml` is scheduled, both rows of `NON_CHANGE_TRIGGERED_WORKFLOWS`), so `native-e2e` is the single new ledger row. Extend `tests/ci/test_event_producer_workflow.py` (recorded edit) to assert that `pytest` appears in no job of the workflow other than `native-e2e` (replacing WP01's no-pytest assertion), the job, the flag (set in this job and in no other job or workflow), the gate edge and every glob above (a floor: the asserted glob list is non-empty and each glob matches at least one tracked file; mutation: dropping one glob is red). **Covers** AC-001, AC-018, AC-036 (reproducibility).

## Tests, markers, jobs

| New test file | Marker | Runs in |
|---|---|---|
| `tests/integration/test_event_producer_e2e.py`, `test_event_producer_degraded.py` | `integration` | `native-e2e` (named files, real binary, per OS); skip with a message in the nightly `integration-next` and `integration-slice` lanes, which do not set `REQUIRE_NATIVE_PRODUCER` |
| `tests/perf/test_event_producer_overhead.py` | `integration` (check the perf tier's existing marker with the completeness gate) | `native-e2e` (per OS); skips elsewhere (flag unset) |
| `tests/integration/test_native_binary_helper.py` | `fast` | `tests/integration` shard |

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `.github/workflows/event-producer.yml` (WP01) | add job `native-e2e`, its gate edge and the path-filter globs of T083 | the real-binary tests need a CI home, and later seam or test edits must still reach it; after WP01 and WP06 |
| `tests/ci/test_event_producer_workflow.py` (WP01) | assert the job, the flag (set only there), the gate edge and the globs; pytest only in `native-e2e` | same |
| `tests/architectural/test_no_duplicate_suite_execution.py` (WP01) | add the `AUTHORIZED_PER_CHANGE_SUITE_JOBS` row `("event-producer.yml", "native-e2e")` with its reason | same commit as the job; the row would be stale earlier |

## Gates
All of the above; `tests/architectural/test_no_duplicate_suite_execution.py` and `tests/ci/test_event_producer_workflow.py`; `make lint`, ruff check and format, mypy, targeted pytest, Gradle `check`, build-glue and thinness gates, packaging and release gates (tests only; the real release is not run here), `test_clock_import_ban.py`, `test_clock_call_ban.py`, `test_fast_tier_marker_completeness.py`, `test_module_shard_registry.py`.

## Definition of Done
T065 to T070 and T083 done; no new red against the baseline; AC-036 limits set from CI numbers and green, **citing the CI run ids and per-leg conclusions recorded in `research/wp10-measurements.md`**; the planted-missing-binary mutation shown red by a recorded scratch run (run id cited); the duplicate-suite ledger row landed with the job.

## Risks
Per-OS legs run only in CI (see the evidence path); latency miss triggers the amendment path.

## Reviewer Guidance
Confirm each AC in the coverage table has a named test, that the real-binary tests cannot skip inside `native-e2e` (flag set) yet cannot turn the nightly `integration-next` or `integration-slice` lanes red (flag unset), and that the measurement numbers, not guesses, set the limits.

## Implementation command

`spec-kitty agent action implement WP10 --agent claude --mission event-producer-reroute-01M4VDY9`
