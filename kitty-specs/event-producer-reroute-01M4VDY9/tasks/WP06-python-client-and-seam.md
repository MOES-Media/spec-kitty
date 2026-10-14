---
work_package_id: WP06
title: Thin Python client, seam wiring, census, thinness gate, fault log, doctor pass-through
dependencies:
- WP05
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- FR-020
- FR-022
- FR-023
- FR-024
- FR-025
- FR-026
- FR-027
- FR-030
- C-001
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T034
- T035
- T036
- T037
- T038
- T039
- T040
- T041
- T042
- T043
- T080
- T081
history: []
agent_profile: python-pedro
authoritative_surface: src/specify_cli/status/
create_intent:
- src/specify_cli/status/producer_client.py
- src/specify_cli/status/producer_faults.py
- src/specify_cli/cli/commands/_event_producer_doctor.py
- tests/status/test_producer_client.py
- tests/status/test_producer_faults.py
- tests/status/test_producer_seam_wiring.py
- tests/specify_cli/status/test_event_producer_doctor.py
- tests/architectural/test_producer_client_thinness.py
- tests/architectural/test_seam_census.py
- tests/architectural/test_producer_fault_enum.py
- tests/architectural/test_publish_log_single_writer.py
- tests/status/test_producer_client_windows.py
- tests/status/test_producer_state_ignore.py
execution_mode: code_change
model: ''
owned_files:
- src/specify_cli/status/producer_client.py
- src/specify_cli/status/producer_faults.py
- src/specify_cli/status/adapters.py
- src/specify_cli/status/zeitgeist_bridge.py
- src/specify_cli/status/emit.py
- src/specify_cli/status/lifecycle_events.py
- src/specify_cli/status/__init__.py
- src/specify_cli/coordination/outbound.py
- src/specify_cli/coordination/status_transition.py
- src/specify_cli/decisions/emit.py
- src/specify_cli/retrospective/lifecycle_events.py
- src/specify_cli/events/runtime_moments.py
- src/specify_cli/events/decision_log.py
- src/runtime/next/_internal_runtime/events.py
- src/runtime/next/runtime_bridge_retrospective.py
- src/runtime/next/_internal_runtime/engine.py
- src/specify_cli/cli/commands/_event_producer_doctor.py
- src/specify_cli/state/contract.py
- src/specify_cli/_completion_manifest.json
- docs/api/cli-commands.md
- tests/status/conftest.py
- tests/status/test_producer_client.py
- tests/status/test_producer_faults.py
- tests/status/test_producer_seam_wiring.py
- tests/status/test_drain_fanout.py
- tests/status/test_saas_fanout_timeout.py
- tests/specify_cli/status/test_event_producer_doctor.py
- tests/architectural/test_producer_client_thinness.py
- tests/architectural/test_seam_census.py
- tests/architectural/test_producer_fault_enum.py
- tests/architectural/test_publish_log_single_writer.py
- tests/status/test_producer_client_windows.py
- tests/status/test_producer_state_ignore.py
- tests/status/test_emit_fanout_after_adapter.py
- tests/status/test_conftest_restores_zeitgeist_registry.py
- tests/architectural/test_status_module_boundary.py
- tests/runtime/test_bridge_engine.py
- tests/runtime/test_bridge_decision_log_flush.py
- tests/next/test_mission_run_back_reference.py
- tests/next/test_internal_runtime_coverage.py
- tests/next/test_runtime_bridge_unit.py
- tests/next/test_engine_commit_primitives.py
- tests/specify_cli/events/test_decision_log.py
- tests/specify_cli/events/test_runtime_moments_unit.py
role: implementer
tags: []
tracker_refs: []
---

# WP06: Thin Python client, seam wiring, census, thinness gate, fault log, doctor pass-through

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Write the thin Python client, wire every seam call site, add the fault log and doctor pass-through, and land the thinness gate (rules a, c, d, e) and the census. This is the largest WP; it lands as the ordered commits listed under "Commit order".

## Context

Plan sections 4.1, 4.2 (the allowed-surface table is the complete list of Python this WP may change), 4.3, 5, 9, 12 (#6164 hunks), 13 item 2; research R-7, R-11, R-16, R-17, R-20, R-22, R-24. Python is allowed **only** for this thin shim: no schema, id, event-clock, order, dedupe or type knowledge. The client is written against the real protocol built in WP04/WP05, **and replays the golden protocol fixtures WP04 commits** under `services/event-producer/launcher/src/test/resources/protocol/`: the stub binaries and the `_classify` tests read those files, so a stub cannot drift from the Java reply shape. **Size note:** the diff is large (about 20 existing files plus 3 new modules plus gates) but most edits are call sites and keyword additions; if the reviewer cannot hold it in a sitting, splitting at the commit-order cut after step 4 is the natural seam (operator decision; numbering unchanged).

**Chokepoints:** sole owner of `status/adapters.py`, `state/contract.py`, `tests/status/conftest.py`; first editor of `engine.py` (WP07 is second, WP12 clears marker 8). `status/lifecycle_events.py`, `decisions/emit.py`, `events/decision_log.py` take the wiring edit here and the marker comment removal in WP12 (sequential). **Check open PRs** on the owned files before the first commit: PR #5540 touches `docs/api/cli-commands.md` (regenerate after rebasing, never hand-merge a generated file).

**`CHANGELOG.md`:** this WP edits `status/__init__.py`; the single mission-wide CHANGELOG entry (and the reconciliation of the AGENTS.md `__init__.py` rule with spec C-006, no version bump) is owned by WP07 T050, so this WP adds none.

**No new module, no constants module, no new top-level symbol beyond plan 4.1/4.2.** Rule (a) pins the exact symbol set of the three new modules and rule (c) the module census, so path literals are not extracted. The fault-log path is a string inside `record_fault` and `_read_fault_rows`; the publish-log, fault-log and clock-state paths are declared once as the three `StateSurface` entries of `state/contract.py` (T042), and the AC-030 gate keys on the publish-log `StateSurface` entry. `producer_faults.py` imports `kernel.clock` (the repository's single wall-clock door; tasks-overview R10): `_now_utc` returns `kernel.clock.now_utc()`; raw `datetime.now` or `time.time` is banned by `tests/architectural/test_clock_import_ban.py` and `test_clock_call_ban.py`, which scan `src`, `tests` and `scripts`.

## Commit order (plan 11/12)
1. Campsite (2): fold the three `try/except` ladders of the `fire_*` functions behind `_dispatch_handlers`, characterised by `tests/status/test_saas_fanout_timeout.py` and `test_drain_fanout.py` (behaviour-preserving, own commit). A campsite scout on `status/emit.py` and `events/runtime_moments.py` runs first (orchestrator); record the findings, fold or defer.
2. Client modules (T034, T035). In the same commit series add the glob `src/specify_cli/status/producer_*.py` to the path filters of `event-producer.yml` (recorded out-of-map edit; the files exist now, so the glob is live) and run `tests/architectural/test_workflow_coherence.py`.
3. `adapters.py` hunks A (drain gates and `hosted_posture` import deleted; the gate moves into the Zeitgeist handlers in `zeitgeist_bridge.py`), B (`row` and `record_fault` in busy/timeout branches, only for a client `_handler_key`), **B2 (repo_root forwarding, see T036)**, C (register the client outside the `moment_handlers_disabled_reason` branch with `bound_resolver=_saas_fanout_timeout_s`), D (docstring, fixture).
4. Seam call sites (T037), emitter route (T038), thinness gate, census (T039, T040), doctor (T041), state contract (T042), AC-003 sub-observables (T080), Windows subset (T081).

## Subtask T034: `producer_client.py` (red first)
**Steps**: `locate_binary` (package-relative `_native/`, no env override), `ProducerClient.produce`/`retain`/`_run`/`_read_reply` (argv list, no shell, 1 MiB cap, the one reply decode site), `effective_client_timeout(bound)` = 3.0 s when bound <= 0 else `min(3.0, max(bound/2, bound-1.0))` (AC-006 boundary cases 0.5, 1.0, 1.5, 0, negative), terminate own child by handle, per-process breaker, `_classify` to the fault classes, `produce_status_handler`, `produce_lifecycle_handler` that never raise. Tests patch `locate_binary`; stub binaries **replay the golden fixtures of WP04** (valid reply, malformed reply, exit 64, exit 75, `v` 2) and `_classify` tests are table-driven over the same files. The fixtures are the protocol contract in both directions: WP04's launcher test regenerates them from the real launcher and fails on drift, and this WP replays them, so a stub cannot diverge silently; the real binary is exercised by WP10 (`native-e2e`). **Covers** FR-025, FR-026, AC-006 (boundary values here; batch, breaker and override cases in T036).

## Subtask T035: `producer_faults.py`
**Steps**: `FaultClass` (enum-equality test: `disabled` absent), `record_fault` (stderr line in every mode; fault-log append `.kittify/events/faults/<UTC date>.jsonl`, the single writer), `record_notice` (`torn-line`), `write_stderr_warning`, `fault_summary` (counts fault rows not notices, in the retention window; success does not reset), `_now_utc` (the only wall-clock read, through `kernel.clock`; tests inject it), `_read_fault_rows` (second decode site). Fault rows hold repo-relative paths only; opaque copies of the row's `event_type`/`event_id`.
**Window rule (spec touch-up 11, FR-026):** the window is the fault-log day files whose name date lies within the raw `max_age_days` of today; an absent or non-numeric value means every file present. Red tests with an injected `_now_utc`: a file dated exactly `max_age_days` ago (inside), one day older (outside), `max_age_days` absent, `"abc"` (every file), day files that hold only notice rows (not counted), and "success does not reset" as a separate assertion from "notice rows are counted apart".
**Unreadable is not zero:** `fault_summary` returns an explicit status: `none recorded` (no log), counts, or `unreadable` (counted separately). Red tests: a corrupt line, a directory in place of the file, and a permission error each give `unreadable`, never `0 faults`; a missing log is reported as "none recorded", not as a pass. `events_health` (T041) shows `unreadable` as a visible warning line.
**Torn-line notice (AC-026 client half):** a producer reply carrying the notice `torn-line` yields exactly one stderr line and one notice row naming the repo-relative path and line position; `fault_summary` is unchanged (a notice is not a fault, FR-024).
**Covers** FR-022, FR-023, FR-024, AC-005 (each class), AC-007, AC-026 (client).

## Subtask T036: Bound, busy, timeout, root forwarding
**Steps**: `fire_*` busy and timeout branches call `record_fault` only for a client handler; slow Zeitgeist handler yields no producer fault row (AC-006). A seam **busy-skip** and a **`TimeoutError`** each yield a fault row, asserted in **separate** tests, each also asserting the `--json` body and exit code unchanged (AC-005, AC-006).
**Hunk B2 (repo_root forwarding, FR-004):** `fire_lifecycle_saas_fanout` and `fire_resolved_binding_fanout` take `repo_root` as a keyword and drop it today (only `fire_saas_fanout` re-joins it into `kwargs`). Red-first test: the AC-001 lifecycle case (`fire_lifecycle_saas_fanout`) and the plain status case (`fire_saas_fanout`) assert the stub receives `start_path`; then set `kwargs["repo_root"] = repo_root` before dispatch in both forwarding functions (both are already named in the plan 4.2 row; no symbol is added). **The resolved-binding slot stays unregistered by the client (FR-003)** and its call passes `causation_id`, not a row, so no client or stub assertion is made on it; its `repo_root` forwarding serves hosted handlers only and is asserted with a recording fake handler on that slot in `test_producer_seam_wiring.py`. The persisted `InnerStateChanged` row of a resolved-binding annotation reaches the client through its own new seam call on the `status` slot (T037).
**Lane ATDD commit (charter C-011).** The stub-level end-to-end AC-001 case (default wiring, drain off, a recording stub binary, one lifecycle transition gives exactly one `produce` request carrying `start_path` and the persisted row) is committed red at the start of the seam work, before the hunks of Commit order step 3, and is the lane's acceptance test; WP10 T065 repeats the scenario against the real binary and released wheel (only that run waits for the events release).
**Project-scoped reply (client half of AC-005/AC-017, OD-MISSIONID (b)):** red-first stub-binary test in `tests/status/test_producer_client.py`: the stub replays WP04's golden `produced` reply for a row with no `mission_id`; the client prints no stderr line, writes no fault-log row, leaves `fault_summary` unchanged and the exit code and `--json` body byte-identical to a run with the producer absent. Mutation: a client that treats a missing `mission_id` as a fault must fail it.
**Unresolvable root (AC-005):** a producer reply `rejected` / `root-unresolvable` gives the stderr warning, **no fault-log row** and an unchanged `--json` body and exit code.
**Batch, breaker and bound-override tests (AC-006; red first, each revert-checked; in `tests/status/test_producer_client.py` and `tests/status/test_saas_fanout_timeout.py`, both WP06-owned):** (1) a batch of N rows (N >= 3, a floor on N) against a hanging stub asserts exactly one spawn and exactly N fault rows (the per-process breaker of T034, FR-025); mutation: a breaker that never opens (N spawns) must fail it; (2) with the seam bound disabled (`SPEC_KITTY_SAAS_FANOUT_TIMEOUT` set to the disabling value read by `adapters.py`) the client's own `effective_client_timeout` fires first and the stub is terminated by handle; (3) with the override lowered below the client default the same assertion holds with the lowered value; the boundary values stay in T034. Each asserts no child remains.
Re-point `test_drain_fanout.py` (producer fires with drain off, Zeitgeist silent) and the pin test at every Zeitgeist handler (AC-003 f). **Covers** FR-002, FR-004, FR-025, C-003.

## Subtask T037: Seam call sites (FR-002 to FR-004)
**Steps**: edit exactly the symbols of plan 4.2 rows for `emit.py`, `coordination/outbound.py`, `coordination/status_transition.py`, `lifecycle_events.py`, `decisions/emit.py`, `retrospective/lifecycle_events.py`, `events/runtime_moments.py`; each hands the persisted row (after sanitisation) as `row=`; every persisted `InnerStateChanged` row including bundled sites reaches the seam once, through a new call on the `status` slot (`fire_saas_fanout`; the unregistered resolved-binding slot is never used for it); no new function, no extraction. **Covers** FR-003, FR-004, AC-001, AC-003, AC-004.

## Subtask T038: Journal route
**Steps**: `emit_decision_authority_denied` on the `RuntimeEventEmitter` Protocol, `NullEmitter`, `DecisionGitLog`, `_BufferingRuntimeEmitter`, `RuntimeMomentProducer`; one call from `_authorize_audit_answer` in `engine.py` (new `emitter` parameter; `engine.py` never calls the seam directly and imports nothing from `specify_cli`). `JournalRecord.row` raw parsed line. Journal-identity test drives the real `RuntimeMomentProducer` route with empty payload `mission_id` into a recording stub binary and asserts `start_path` = the Mission directory and `row` keys within the five persisted keys.
**Census of emitter fakes (the Protocol gains a method):** `grep -rn "def emit_significance_evaluated" src tests` and add `emit_decision_authority_denied` to every class that implements the surface **or prove, in the commit message, that the engine path under test never reaches it**. Known implementers, all owned by this WP: `tests/runtime/test_bridge_engine.py`, `tests/runtime/test_bridge_decision_log_flush.py`, `tests/next/test_mission_run_back_reference.py` (`_NullSyncEmitter`), `tests/next/test_internal_runtime_coverage.py` (`ZeitgeistRuntimeEmitterProducer`), `tests/next/test_runtime_bridge_unit.py` (`FakeEmitter`), `tests/next/test_engine_commit_primitives.py`, `tests/specify_cli/events/test_decision_log.py`, `tests/specify_cli/events/test_runtime_moments_unit.py`. Run each of those files individually. Run `tests/architectural/test_runtime_emitter_seam.py` and `test_layer_rules.py`.

## Subtask T039: Thinness gate (a, c, d, e) with floors and mutations
**Steps**: `tests/architectural/test_producer_client_thinness.py` per plan 4.2 and R-16, rules (a), (c), (d), (e); rule (b) is WP07's. **Build-glue pin:** the single enumerator is `tests/architectural/test_build_glue_thin.py` (WP01, emptied of "not yet landed" by WP08); this gate does **not** re-enumerate the glue, it asserts that file exists and names each plan 13.2 file (hook, `check_dist_set.py`, `check_exact_install.py`, the `gradle_pin_check.py` entry) so the two cannot drift.
- **Rule (c) walks the filesystem** (`status/`, `events/`, `retrospective/`, `decisions/`), never `git ls-files` or a diff, so an uncommitted new module is seen (the failure mode of diff-based gates).
- **Rule (a) clock vocabulary:** a call into `kernel.clock` counts as the wall-clock read, allowed only inside `_now_utc`, as do `datetime.now` and `time.time`.
- **Floors:** every module and table file found; the walk finds all three new modules; the pinned census list is non-empty.
- **Self-mutations (each red):** client with an id mint; `producer_faults.py` with a clock read (raw and through `kernel.clock`) outside `_now_utc`; `emit.py` with `import uuid`; `engine.py`/`rebuild_state.py` with `secrets`; **a new untracked module `status/_mint_id.py` and a new function in an existing table file, written into a temporary copy of the tree** (rule c and rule e); and the AC-012 mutants: a client copy with a schema import, with a publish-log read, with the bridge's envelope code.
- **Positive "allowed capabilities" fixture** that must pass: stderr line, bound resolution, child termination, breaker, fault-log append and read, opaque identifier copy, root resolution, retention pass-through.
**Debt record (charter Burn-down Policy).** The pinned lists of rules (c) and (e) are debt: owner = the #6193 mission (the event-log write side that retires the Python writers), drain = deleted when #6193 or #6195 lands. It is recorded in the gate module's header comment, in the new ADR's consequences (WP07 T050) and in tasks-overview section 11. No `_baselines.yaml` entry exists or is claimed: the lists are fixed pins that fail on growth, not mutable allowlists (whether to add a row is open item O-4).
Also `test_producer_fault_enum.py` and `test_publish_log_single_writer.py` (AC-030: no Python opens the publish log path for write; the path is read from the publish-log `StateSurface` entry; floor: entry and scanned modules found). **Covers** FR-026, FR-027, FR-020, AC-012, AC-030.

## Subtask T040: Census gate
**Steps**: `tests/architectural/test_seam_census.py` (AC-004): writers derive from the persisted row kinds (`AUTHORITATIVE_NON_LANE_EVENT_TYPES`, retrospective, lane and annotation rows, run-journal kinds) with no hand list. **Floor:** the in-scope writers of data-model section 3, plus the annotation row written **standalone and bundled** by each of `emit_status_transition` and `emit_status_transition_batch`. One forced row per writer yields exactly one seam call with a stubbed client, `row` keys within the persisted keys; **and one annotation row yields exactly one invocation of the registered client** (a client-level assertion, not only seam-level). **Named census exemption:** the three registered decision kinds with no emitter in `src/` are listed as an exemption entry that **fails when an emitter appears**. **Self-mutations (each red):** an injected writer that persists with no seam call; a persisted kind in no list; a truncated set. **Covers** FR-003.

## Subtask T041: Doctor sibling
**Steps**: `cli/commands/_event_producer_doctor.py` (self-registering; `register`, `events_health`, `events_retention`); forwards raw `ledger.retention.keep_runs`/`max_age_days` exactly as read (a test that `"abc"` reaches Java unchanged), `--apply` sends `dry_run=false`, renders the reply, counts only; `status/__init__.py` `__all__` gains the client's public names (`tests/architectural/test_status_module_boundary.py` is owned and edited only if the boundary test requires it); regenerate `src/specify_cli/_completion_manifest.json` (`python -m specify_cli.completion --regenerate`) and `docs/api/cli-commands.md`. **Retain failures are not event faults (FR-023, spec touch-up 7):** red test: a `retain` that returns `policy_error`, and one with a stubbed crash or timeout, render an error, write **zero** fault-log rows and leave `fault_summary` unchanged. `events_health` renders `unreadable` and `none recorded` distinctly (T035). **Covers** FR-030, FR-024, AC-013 (doctor dry-run pass-through: the default `events-retention` sends `dry_run=true`, asserted by the stub).

## Subtask T042: State contract and gitignore
**Steps**: three `StateSurface` entries in `state/contract.py` (publish log, fault log, clock state). **Recorded decision:** all three use the existing `StateRoot.GIT_INTERNAL`/project roots as already defined, with a documented note on the clock-state entry that for a linked worktree the root is the git **common** directory (`.git/spec-kitty/` of the primary checkout, which Java resolves; Python only declares the surface). No new `StateRoot` member (a new member would need a plan 4.2 row), no constants module, no new top-level symbol. **AC-023 test (red first):** `tests/status/test_producer_state_ignore.py` (init and upgrade fixtures): `git check-ignore` succeeds for the publish-log path and the fault-log path after fresh `init` and after upgrade; the clock-state entry is declared under the git-internal root (its path under the common directory is asserted in Java, WP03 T019); floor: both paths checked in both fixtures (four checks); mutation: removing the ignore rule for either path makes it fail. Also run `test_lifted_root_gitignore_contract.py`, `tests/specify_cli/test_state_contract.py`, which pre-date the new paths. `tests/status/conftest.py` also restores the client. **Covers** FR-019 (Python half), AC-023.

## Subtask T043: Implicated gates and no-network proof
**Steps**: run the R-7 list for touched files individually (`test_hosted_drain_gate`, `test_egress_consent_boundary`, `test_integration_boundary`, `test_no_dead_modules`, `test_status_events_writes_gate`, `test_mission_write_discipline`, `test_no_write_side_rederivation`, `test_ledger_floor`, `test_completion_manifest_freshness`, `test_docs_cli_reference_parity`, `test_destructive_op_routing`, `test_clock_import_ban`, `test_clock_call_ban`, `test_fast_tier_marker_completeness`, `test_module_shard_registry`, `test_workflow_coherence`); compare against `research/baseline-red.txt`. Orchestrator-api contract tests unchanged and `--json` snapshots unchanged (AC-018, C-002). Coverage: new Python modules 95%, diff-cover 90%.

## Subtask T080: AC-003 sub-observables
**Steps**: one named test per sub-observable of AC-003, in `tests/status/test_producer_seam_wiring.py`, each revert-checked (revert the FR-002 hunk it covers; the test must fail): **(b)** the producer fires with `zeitgeist_bridge` and `hosted_posture` absent (an import-free test that hides both modules); **(c)** a run-journal row through `RuntimeMomentProducer` yields a seam call; **(d)** for **each** hosted kill switch the producer still fires, the list enumerated from `specify_cli.core.env.moment_handlers_disabled_reason` (the gate `adapters.py` consults) (`SPEC_KITTY_NO_MOMENT_HANDLERS`, `SPEC_KITTY_SYNC_DISABLE`, the deprecated alias) and a floor that the enumerated list is non-empty; **(e)** `reset_handlers` plus the restore in `tests/status/conftest.py` re-register the client (`test_conftest_restores_zeitgeist_registry.py` is extended). Rule from plan 12: (a) is skipped when `zeitgeist_bridge.py` is gone, (b) always runs. **Covers** FR-002, C-003, AC-003.

## Subtask T081: Windows termination subset
**Steps**: `tests/status/test_producer_client_windows.py`, marked `windows_ci` (the `ci-windows.yml` job discovers `windows_ci` files with `git grep`, so no workflow edit is needed): the client timeout terminates a hanging stub by handle and no orphan child remains, argv quoting with a path containing a space. Carries the explicit tier marker the completeness gate requires (verify with `test_fast_tier_marker_completeness.py`). The Windows leg of AC-006 is therefore verified for the shipped client; the shipped wheel's smoke proves only a valid row (residual recorded in tasks-overview section 10). **Covers** FR-025, AC-006.

## Tests, markers, jobs

| New or extended test | Marker | Runs in |
|---|---|---|
| `tests/status/test_producer_client.py`, `test_producer_faults.py`, `test_producer_seam_wiring.py` | `fast` (tests/status is a fast-tier dir; an unmarked file fails the completeness gate) | fast tier and the status shard |
| `tests/specify_cli/status/test_event_producer_doctor.py` | `fast` | status shard |
| `tests/architectural/test_producer_client_thinness.py`, `test_seam_census.py`, `test_producer_fault_enum.py`, `test_publish_log_single_writer.py` | `architectural` | architectural tier |
| `tests/status/test_producer_client_windows.py` | `windows_ci` | `ci-windows.yml` |

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `.github/workflows/event-producer.yml` (WP01) | add the path glob `src/specify_cli/status/producer_*.py` | the files exist only from this WP; the glob must be live (`test_workflow_coherence.py`) |

## Gates
`make lint`; `uv run --frozen ruff check` and `ruff format --check --force-exclude`; mypy on the client modules; targeted pytest, **named files only**: `tests/status/test_producer_client.py tests/status/test_producer_faults.py tests/status/test_producer_seam_wiring.py tests/status/test_drain_fanout.py tests/status/test_saas_fanout_timeout.py tests/status/test_emit_fanout_after_adapter.py tests/status/test_conftest_restores_zeitgeist_registry.py tests/specify_cli/status/test_event_producer_doctor.py tests/architectural/test_producer_client_thinness.py tests/architectural/test_build_glue_thin.py tests/architectural/test_seam_census.py tests/architectural/test_producer_fault_enum.py tests/architectural/test_publish_log_single_writer.py tests/architectural/test_status_module_boundary.py tests/architectural/test_clock_import_ban.py tests/architectural/test_clock_call_ban.py tests/architectural/test_fast_tier_marker_completeness.py tests/architectural/test_module_shard_registry.py` plus the census files of T038 (each file by itself, matching the per-file baseline of WP01 T001); Gradle `check` still green. Red-first per behaviour, revert-checked, one commit each.

## Definition of Done
T034 to T043, T080 and T081 done; client holds no logic (gate green with mutations red); every seam call site covered by the census; no new red against the baseline; `hosted.drain` keeps its meaning; every emitter fake covered or proved unreached.

## Risks
`status/adapters.py` conflicts with #6164 (hunk handling in plan 12); `engine.py` second editor ordering; latency (AC-036 measured in WP10, contingency batching changes FR-003/FR-026/AC-004).

## Reviewer Guidance
Check against the allowed-surface table symbol by symbol; confirm the commit order; run the thinness gate mutation cases by hand (including the untracked-module one); confirm no Python mints an id, reads a clock outside `_now_utc`, or opens the publish log for write; confirm the stubs replay WP04's fixtures.

## Implementation command

`spec-kitty agent action implement WP06 --agent claude --mission event-producer-reroute-01M4VDY9`
