---
work_package_id: WP06
title: Thin Python client, seam wiring, census, thinness gate, fault log, doctor pass-through
dependencies:
- WP04
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

Plan sections 4.1, 4.2 (the allowed-surface table is the complete list of Python this WP may change), 4.3, 5, 9, 12 (#6164 hunks), 13 item 2; research R-7, R-11, R-16, R-17, R-20, R-22, R-24. Python is allowed **only** for this thin shim: no schema, id, event-clock, order, dedupe or type knowledge. The client is written against the real protocol built in WP04/WP05. **Size note:** the diff is large (about 20 existing files plus 3 new modules plus gates) but most edits are call sites and keyword additions; if the reviewer cannot hold it in a sitting, splitting at the commit-order cut after step 4 is the natural seam (operator decision; numbering unchanged).

**Chokepoints:** sole owner of `status/adapters.py`, `state/contract.py`, `tests/status/conftest.py`; first editor of `engine.py` (WP07 is second). `status/lifecycle_events.py`, `decisions/emit.py`, `events/decision_log.py` take the wiring edit here and the marker comment removal in WP07 (sequential).

## Commit order (plan 11/12)

1. Campsite (2): fold the three `try/except` ladders of the `fire_*` functions behind `_dispatch_handlers`, characterised by `tests/status/test_saas_fanout_timeout.py` and `test_drain_fanout.py` (behaviour-preserving, own commit). A campsite scout on `status/emit.py` and `events/runtime_moments.py` runs first (orchestrator); record the findings, fold or defer.
2. Client modules (T034, T035).
3. `adapters.py` hunks A (drain gates and `hosted_posture` import deleted; the gate moves into the Zeitgeist handlers in `zeitgeist_bridge.py`), B (`row` and `record_fault` in busy/timeout branches, only for a client `_handler_key`), C (register the client outside the `moment_handlers_disabled_reason` branch with `bound_resolver=_saas_fanout_timeout_s`), D (docstring, fixture).
4. Seam call sites (T037), emitter route (T038), thinness gate, census (T039, T040), doctor (T041), state contract (T042).

## Subtask T034: `producer_client.py` (red first)

**Steps**: `locate_binary` (package-relative `_native/`, no env override), `ProducerClient.produce`/`retain`/`_run`/`_read_reply` (argv list, no shell, 1 MiB cap, the one reply decode site), `effective_client_timeout(bound)` = 3.0 s when bound <= 0 else `min(3.0, max(bound/2, bound-1.0))` (AC-006 boundary cases 0.5, 1.0, 1.5, 0, negative), terminate own child by handle, per-process breaker, `_classify` to the fault classes, `produce_status_handler`, `produce_lifecycle_handler` that never raise. Tests patch `locate_binary`; stub binaries. **Covers** FR-025, FR-026, AC-006.

## Subtask T035: `producer_faults.py`

**Steps**: `FaultClass` (enum-equality test: `disabled` absent), `record_fault` (stderr line in every mode; fault-log append `.kittify/events/faults/<UTC date>.jsonl`, the single writer), `record_notice` (`torn-line`), `write_stderr_warning`, `fault_summary` (counts fault rows not notices, in the retention window; success does not reset), `_now_utc` (the only wall-clock read), `_read_fault_rows` (second decode site). Fault rows hold repo-relative paths only; opaque copies of the row's `event_type`/`event_id`. **Covers** FR-022, FR-023, FR-024, AC-005 (each class), AC-007.

## Subtask T036: Bound, busy and timeout branches

**Steps**: `fire_*` busy and timeout branches call `record_fault` only for a client handler; slow Zeitgeist handler yields no producer fault row (AC-006). Re-point `test_drain_fanout.py` (producer fires with drain off, Zeitgeist silent) and the pin test at every Zeitgeist handler (AC-003 f). **Covers** FR-002, FR-025, C-003.

## Subtask T037: Seam call sites (FR-002 to FR-004)

**Steps**: edit exactly the symbols of plan 4.2 rows for `emit.py`, `coordination/outbound.py`, `coordination/status_transition.py`, `lifecycle_events.py`, `decisions/emit.py`, `retrospective/lifecycle_events.py`, `events/runtime_moments.py`; each hands the persisted row (after sanitisation) as `row=`; every persisted `InnerStateChanged` row including bundled sites reaches the seam once; no new function, no extraction. **Covers** FR-003, FR-004, AC-001, AC-003, AC-004.

## Subtask T038: Journal route

**Steps**: `emit_decision_authority_denied` on the `RuntimeEventEmitter` Protocol, `NullEmitter`, `DecisionGitLog`, `_BufferingRuntimeEmitter`, `RuntimeMomentProducer`; one call from `_authorize_audit_answer` in `engine.py` (new `emitter` parameter; `engine.py` never calls the seam directly and imports nothing from `specify_cli`). `JournalRecord.row` raw parsed line. Journal-identity test drives the real `RuntimeMomentProducer` route with empty payload `mission_id` into a recording stub binary and asserts `start_path` = the feature directory and `row` keys within the five persisted keys. Fakes in `tests/runtime/test_bridge_engine.py` gain the method. Run `tests/architectural/test_runtime_emitter_seam.py` and `test_layer_rules.py`.

## Subtask T039: Thinness gate (a, c, d, e) with floors and mutations

**Steps**: `tests/architectural/test_producer_client_thinness.py` per plan 4.2 and R-16, rules (a), (c), (d), (e) and the build-glue pin (hook, `check_dist_set.py`, `check_exact_install.py --wheel-tag`, `gradle_pin_check.py` entry hold no runtime logic); rule (b) is WP07's. Floors: every module and table file found; self-mutations (client with an id mint, `producer_faults.py` with a clock outside `_now_utc`, `emit.py` with `import uuid`, `engine.py`/`rebuild_state.py` with `secrets`). Also `test_producer_fault_enum.py` and `test_publish_log_single_writer.py` (AC-030: no Python opens the publish log path for write; floor: constant and scanned modules found). **Covers** FR-026, FR-027, FR-020, AC-012, AC-030.

## Subtask T040: Census gate

**Steps**: `tests/architectural/test_seam_census.py` (AC-004): writers derive from the persisted row kinds (`AUTHORITATIVE_NON_LANE_EVENT_TYPES`, retrospective, lane and annotation rows, run-journal kinds); one forced row per writer yields exactly one seam call, with a stubbed client, `row` keys within the persisted keys. **Covers** FR-003.

## Subtask T041: Doctor sibling

**Steps**: `cli/commands/_event_producer_doctor.py` (self-registering; `register`, `events_health`, `events_retention`); forwards raw `ledger.retention.keep_runs`/`max_age_days` exactly as read (a test that `"abc"` reaches Java unchanged), `--apply` sends `dry_run=false`, renders the reply, counts only; `status/__init__.py` `__all__` gains the client's public names (`test_status_module_boundary.py`); regenerate `src/specify_cli/_completion_manifest.json` (`python -m specify_cli.completion --regenerate`) and `docs/api/cli-commands.md`. **Covers** FR-030, FR-024.

## Subtask T042: State contract and gitignore

**Steps**: three `StateSurface` entries in `state/contract.py` (publish log, fault log, clock state; decide `StateRoot`/`GIT_COMMON_DIR` handling and record it); path literals in one constants module; `git check-ignore` succeeds after fresh `init` and after upgrade (AC-023); run `test_lifted_root_gitignore_contract.py`, `tests/specify_cli/test_state_contract.py`. `tests/status/conftest.py` also restores the client. **Covers** FR-019 (Python half), AC-023.

## Subtask T043: Implicated gates and no-network proof

**Steps**: run the R-7 list for touched files individually (`test_hosted_drain_gate`, `test_egress_consent_boundary`, `test_integration_boundary`, `test_no_dead_modules`, `test_status_events_writes_gate`, `test_mission_write_discipline`, `test_no_write_side_rederivation`, `test_ledger_floor`, `test_completion_manifest_freshness`, `test_docs_cli_reference_parity`, `test_destructive_op_routing`); compare against `research/baseline-red.txt`. Orchestrator-api contract tests unchanged and `--json` snapshots unchanged (AC-018, C-002). Coverage: new Python modules 95%, diff-cover 90%.

## Gates

`make lint`; `uv run --frozen ruff check` and `ruff format --check --force-exclude`; mypy on the client modules; targeted pytest: `tests/status/ tests/specify_cli/status/test_event_producer_doctor.py tests/architectural/test_producer_client_thinness.py test_seam_census.py test_producer_fault_enum.py test_publish_log_single_writer.py` (named files only, no whole directories); Gradle `test` still green. Red-first per behaviour, revert-checked, one commit each.

## Definition of Done

T034 to T043 done; client holds no logic (gate green with mutations red); every seam call site covered by the census; no new red against the baseline; `hosted.drain` keeps its meaning.

## Risks

`status/adapters.py` conflicts with #6164 (hunk handling in plan 12); `engine.py` second editor ordering; latency (AC-036 measured in WP10, contingency batching changes FR-003/FR-026/AC-004).

## Reviewer Guidance

Check against the allowed-surface table symbol by symbol; confirm the commit order; run the thinness gate mutation cases by hand; confirm no Python mints an id, reads a clock outside `_now_utc`, or opens the publish log for write.

## Implementation command

`spec-kitty agent action implement WP06 --agent claude --mission event-producer-reroute-01M4VDY9`
