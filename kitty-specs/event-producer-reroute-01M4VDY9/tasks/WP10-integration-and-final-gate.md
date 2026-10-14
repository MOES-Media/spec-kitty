---
work_package_id: WP10
title: 'Integration: end-to-end default install, AC-036 measurement, mid-flight Mission, final gate pass (events-release gated)'
dependencies:
- WP01
- WP02
- WP03
- WP04
- WP05
- WP06
- WP07
- WP08
- WP09
- WP11
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
- T064
- T065
- T066
- T067
- T068
- T069
- T070
history: []
agent_profile: python-pedro
authoritative_surface: tests/
create_intent:
- tests/integration/test_event_producer_e2e.py
- tests/integration/test_event_producer_degraded.py
- tests/perf/test_event_producer_overhead.py
execution_mode: code_change
model: ''
owned_files:
- tests/integration/test_event_producer_e2e.py
- tests/integration/test_event_producer_degraded.py
- tests/_perf_helpers.py
- tests/perf/test_event_producer_overhead.py
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

Plan sections 9 (performance, CI home), 13 item 8, 17; research R-9, R-17. Read-mostly: tests and measurements. **External dependency: gated on the spec-kitty-events release** (runs against the real released wheel and real binary). Depends on all other WPs.

## Precondition gate (T064): EVENTS RELEASE

**First task.** Pin names a released wheel, WP11 complete. If not, **STOP, report.** The mission PR is not marked ready before this passes.

## Subtask T064: Events-release precondition check

## Subtask T065: Default-install end to end

**Steps**: red tests then green: a default install (platform wheel built via the hook, no setting, drain off, network denied) appends one publish-log line per in-scope type whose `id` is the ledger row's normalised `event_id`, from a lane worktree and a Mission checkout, per slot; the line validates against the released schema; a degraded run (missing binary, crash stub, slow stub) exits 0, leaves every `--json` body unchanged and records a warning (never silent). Orchestrator-api contract tests and `--json` snapshots unchanged (AC-018). **Covers** AC-001, AC-002, AC-005, AC-018, NFR-001.

## Subtask T066: Compatibility and freeze

**Steps**: read every archived Mission's `status.events.jsonl` with the CLI readers: byte-identical afterwards (floor at least one read; mutation: a reader that rewrites); the producer on a scratch repo changes no `kitty-specs/**` file on `main` and creates none (AC-016); a Mission open before upgrade keeps its ledger byte-unchanged and its publish log starts at the first post-upgrade event; a Mission without `mission_id` publishes project-scoped with no fault (AC-017, OD-MISSIONID (b)). **Covers** AC-015, AC-016, AC-017, FR-038.

## Subtask T067: `binary-missing` path

**Steps**: clean venv from the sdist, from the pure wheel, and with no matching wheel: lifecycle command succeeds and warns `binary-missing` (AC-025); editable install works (AC-038 runtime half). **Covers** AC-025, FR-043.

## Subtask T068: AC-036 measurement

**Steps**: `tests/_perf_helpers.py` gains relative producer limits (`test_perf_limit_authority.py` rejects absolute ones): `emit_status_transition_batch` with a real producer over the same with a no-op handler, cold and warm, per OS; numbers from WP01 and this WP in `research/wp10-measurements.md`. On failure apply the NFR-002 contingency: **do not implement batching here; stop and report**, because it requires amending FR-003, FR-026 and AC-004 first (operator decision). **Covers** AC-036, NFR-002.

## Subtask T069: Hygiene and determinism sweep

**Steps**: scan fixtures, fault records and the members the producer authors for absolute home paths or usernames (AC-035); same row, clock and node id give byte-identical lines on all three OS (AC-034, via CI legs); no BOM, CR or raw newline. **Covers** AC-034, AC-035, NFR-003, NFR-004, NFR-006, NFR-007.

## Subtask T070: Reflexivity check and final gate pass

**Steps**: confirm no gate depends on the producer for this mission's own events (plan 13.8); run every new gate (thinness, census, fault enum, single writer, single lane list, no committed events schema, exemption ledger) with its floor and mutation; every R-7 gate file individually against the baseline; `uv lock --check`, `spec-kitty regen --check`, docs lint, terminology guard; Gradle `test`; record the result list in `research/wp10-measurements.md`. **Covers** AC-028, G-11.

## Gates

All of the above; `make lint`, ruff check and format, mypy, targeted pytest, Gradle tests, thinness gate, packaging and release gates (tests only; the real release is not run here).

## Definition of Done

T064 to T070 done; no new red against the baseline; AC-036 limits set and green.

## Risks

Per-OS legs run only in CI; latency miss triggers the amendment path.

## Reviewer Guidance

Confirm each AC in the coverage table has a named test, and that the measurement numbers, not guesses, set the limits.

## Implementation command

`spec-kitty agent action implement WP10 --agent claude --mission event-producer-reroute-01M4VDY9`
