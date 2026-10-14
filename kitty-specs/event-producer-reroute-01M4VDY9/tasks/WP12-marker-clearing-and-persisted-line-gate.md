---
work_package_id: WP12
title: Marker clearing and persisted-line validation against the released schemas (events-release gated)
dependencies:
- WP07
- WP11
requirement_refs:
- FR-032
- FR-033
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T047
- T078
history: []
agent_profile: python-pedro
authoritative_surface: scripts/
create_intent:
- tests/architectural/test_exemption_ledger.py
execution_mode: code_change
model: ''
owned_files:
- scripts/lint_canonical_producers.py
- scripts/canonical_producer_lint_baseline.txt
- tests/architectural/_baselines.yaml
- tests/architectural/dead_symbol_allowlist.yaml
- tests/architectural/test_exemption_ledger.py
- src/specify_cli/migration/mission_state.py
role: implementer
tags: []
tracker_refs: []
---

# WP12: Marker clearing and persisted-line validation against the released schemas (events-release gated)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Clear the `canonical-producer-exempt` markers whose persisted lines validate, add the persisted-line mode to the lint script, and land `test_exemption_ledger.py` (AC-019), validating against the **released** schemas read from the installed `spec_kitty_events` package.

## Context

Split out of the old WP07 (tasks-overview R6): this is the only part of that WP that reads a released artifact, so only it is gated by the events release. Plan 4.4 (markers), data-model section 8; research R-18, R-19, R-24. Python here is lint and gate code plus comment removal, no runtime logic. The persisted-line mode of `scripts/lint_canonical_producers.py` is bounded gate tooling (plan 4.2 last row; listed in the tasks-overview section 5 allowance): it loops over the released schemas and the persisted lines and holds no producer, envelope, id, clock or schema-authoring logic. Whether that needs an operator ruling is open item O-3.

## Precondition gate: EVENTS RELEASE (blocks approval, and the schema-reading tests; not authoring)

One definition, the same in every gated WP (tasks-overview section 2): the red-first test and the lint-mode code may be authored against a locally installed pinned release, but **this WP is not approved, and its AC-019 gate is not run to completion, until WP11 is approved and `services/event-producer/gradle/events-pin.properties` plus the `spec-kitty-events` floor in `pyproject.toml` name the released wheel `V_E`**, and the installed `spec_kitty_events` contains the released schemas. Record `V_E` and the pin sha256 in the WP notes (the reviewer checks both against the pin file). If no release exists: author what is possible, then STOP and report to the orchestrator.

## Subtask T047: Marker clearing and persisted-line mode
**Steps**: clear `canonical-producer-exempt` markers 1, 2 (`decisions/emit.py`), 5, 6 (`status/lifecycle_events.py`), 8 (`engine.py` comment); markers 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`), 7 (`status/lifecycle_events.py`) only if the persisted line validates in legacy-row form, else residual with an issue reference; 9 to 16 untouched; the eight #1200 markers in `glossary/events.py` stay. `scripts/lint_canonical_producers.py` gains persisted-line mode, baseline not widened (`scripts/canonical_producer_lint_baseline.txt`, `_baselines.yaml`, `dead_symbol_allowlist.yaml`). `tests/architectural/test_exemption_ledger.py` (AC-019): no marker in `src/` cites #1198 or #1248 except named residuals, #1200 stays x8; and it validates, against the **released** schemas read from the installed package:
- **(i)** publish-log lines, at least one per in-scope type from the AC-002 fixtures;
- **(ii) one persisted row per cleared marker, produced by the real writer, in legacy-row form**: `decisions/emit.py` (markers 1 and 2), `status/lifecycle_events.py` (markers 5 and 6), `engine._append_event` (marker 8), plus each of markers 3, 4 and 7 that is cleared. The run-journal kinds are an **additional** case, not a replacement for the per-marker rows.
**Floor:** schemas resolved above zero, and the cleared-marker count above zero and equal to the number of rows validated. **Mutation:** a schema-invalid persisted line must fail the gate, and an empty schema set must fail it. Run `tests/architectural/test_lifted_lint.py`. **Covers** FR-032, FR-033, AC-019.

## Subtask T078: Gate pass
**Steps**: run the touched-file gates individually (R-7) and compare to the baseline; `uv lock --check`.

## Tests, markers, jobs

| New test file | Marker | Runs in |
|---|---|---|
| `tests/architectural/test_exemption_ledger.py` | `architectural` | architectural tier (needs the released `spec_kitty_events` installed: it is, via the floor bump of WP11) |

## Recorded out-of-map edits

Comment-only removals of the markers in files owned by WP06, in dependency order after WP06:

| File | Edit | Marker |
|---|---|---|
| `src/specify_cli/decisions/emit.py` | marker comment removal | 1, 2 |
| `src/specify_cli/status/lifecycle_events.py` | marker comment removal | 5, 6, and 7 if it validates |
| `src/runtime/next/_internal_runtime/engine.py` | marker comment removal | 8 |
| `src/specify_cli/events/decision_log.py` | marker comment removal | 3 if it validates |

## Gates
`make lint`; ruff check and format; targeted pytest: `tests/architectural/test_exemption_ledger.py test_lifted_lint.py test_layer_rules.py test_producer_client_thinness.py` (each file by itself). Red-first, revert-check each behaviour.

## Definition of Done
T047 and T078 done; no marker cites #1198 or #1248 except residuals with an issue reference; one validated row per cleared marker; mutation red; `V_E` and pin sha256 in the notes.

## Risks
A marker whose persisted line does not validate stays residual (not an error). The events release slips: this WP and WP10 wait.

## Reviewer Guidance
Count the cleared markers against the validated rows; every residual marker needs an issue reference.

## Implementation command

`spec-kitty agent action implement WP12 --agent claude --mission event-producer-reroute-01M4VDY9`
