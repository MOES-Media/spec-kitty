---
work_package_id: WP07
title: Run-journal stamping, lane-list consolidation, marker clearing, glossary and ADRs (events-release gated)
dependencies:
- WP06
- WP11
requirement_refs:
- FR-032
- FR-033
- FR-034
- FR-035
- FR-036
- FR-037
- FR-045
- FR-046
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T044
- T045
- T046
- T047
- T048
- T049
- T050
- T051
history: []
agent_profile: python-pedro
authoritative_surface: src/specify_cli/
create_intent:
- tests/architectural/test_single_lane_list.py
- tests/architectural/test_exemption_ledger.py
- tests/runtime/test_journal_event_stamping.py
- tests/cross_cutting/encoding/test_contextive_event_producer_terms.py
- docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md
execution_mode: code_change
model: ''
owned_files:
- scripts/lint_canonical_producers.py
- scripts/canonical_producer_lint_baseline.txt
- tests/architectural/_baselines.yaml
- tests/architectural/dead_symbol_allowlist.yaml
- tests/architectural/test_single_lane_list.py
- tests/architectural/test_exemption_ledger.py
- tests/runtime/test_journal_event_stamping.py
- tests/specify_cli/migration/test_rebuild_state.py
- tests/contract/test_identity_contract_matrix.py
- src/specify_cli/migration/rebuild_state.py
- src/specify_cli/migration/mission_state.py
- src/specify_cli/acceptance/__init__.py
- src/specify_cli/consolidation/done_bookkeeping.py
- docs/context/system-events.md
- src/specify_cli/.contextive/**
- .kittify/traceability/contextive-map.yaml
- tests/cross_cutting/encoding/test_contextive_event_producer_terms.py
- docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md
- docs/adr/4.x/index.md
- docs/adr/4.x/2026-10-13-2-*.md
role: implementer
tags: []
tracker_refs: []
---

# WP07: Run-journal stamping, lane-list consolidation, marker clearing, glossary and ADRs (events-release gated)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Stamp the Python run-journal rows (ULID `event_id`, row version), consolidate the lane lists, clear exemption markers, add glossary terms and ADRs, and add gate (b).

## Context

Plan sections 4.2 (`engine.py`, constants and marker rows), 4.4, 9, 13 items 3, 6, 7; research R-14, R-18, R-19, R-24; data-model sections 8, 9. OD-JOURNAL is the ONE narrow modification of existing Python (field stamping only, no new logic, no new function). **External dependency: gated on the spec-kitty-events release**; see the gate below.

**Chokepoints:** second editor of `engine.py` (after WP06); owns the lint script, `_baselines.yaml`, `dead_symbol_allowlist.yaml`; second editor of `tests/architectural/test_producer_client_thinness.py` (adds rule (b)).

## Precondition gate (T044): EVENTS RELEASE

**First task.** WP11 must be complete and `services/event-producer/gradle/events-pin.properties` plus the `spec-kitty-events` floor in `pyproject.toml` must name the **released** wheel `V_E`; the installed `spec_kitty_events` must contain the released schemas. If not: **STOP; WP07 is not started, approved or its gate (AC-019) run against anything unreleased.** Report to the orchestrator.

## Subtask T044: Events-release precondition check

**Steps**: verify as above; record the `V_E` in the WP notes. No code.

## Subtask T045: Lane-list census and consolidation (FR-034)

**Steps**: first, the census of data-model section 9 plus any further hit (read `tests/specify_cli/migration/test_rebuild_state.py` and the acceptance tests first). For each of `migration/rebuild_state.py::_VALID_LANES`, `acceptance/__init__.py::_LEGACY_NOT_DONE_LANES` (+ `doing` alias constant), `consolidation/done_bookkeeping.py::_PRE_APPROVED_LANE_VALUES`: red characterisation test (value and order equal to the pre-change literal), then derive per research R-14 (`CANONICAL_LANES` / `Lane` by name; none derived wholesale). Imports exactly the plan 4.2 rows (a cycle in `done_bookkeeping` makes it a reviewed exception). `tests/architectural/test_single_lane_list.py`: finds the census lists (floor six, exceptions listed), fails on a new full literal list (mutation case). `uninitialized` excluded. **Covers** FR-034, AC-020.

## Subtask T046: `_append_event` stamping and gate (b)

**Steps**: red test first: the real `_append_event` output has keys exactly `event_id,row_version,event_type,timestamp,payload`, `event_id` a 26-character Crockford ULID that `ulid` parses (`^[0-9A-HJKMNP-TV-Z]{26}$`), `row_version` 1; a journal without them is still read. Implementation: import `ulid` only (as `decisions/emit.py` does), no new function. Add rule (b) to the thinness gate: key-set equality, AST walk finds no `uuid4`, `hashlib`, `secrets`, `random` in the body, mutation with `str(uuid4())` fails. Update the `run.events.jsonl` expectation in `tests/contract/test_identity_contract_matrix.py`. **Covers** FR-035, AC-022.

## Subtask T047: Marker clearing and persisted-line mode

**Steps**: clear `canonical-producer-exempt` markers 1, 2, 5, 6, 8 (marker 8 is the `engine.py` comment); markers 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`), 7 (`status/lifecycle_events.py`) only if the persisted line validates in legacy-row form, else residual with an issue reference; 9 to 16 untouched; the eight #1200 markers in `glossary/events.py` stay. `scripts/lint_canonical_producers.py` gains persisted-line mode, baseline not widened (`scripts/canonical_producer_lint_baseline.txt`, `_baselines.yaml`, `dead_symbol_allowlist.yaml`). `tests/architectural/test_exemption_ledger.py` (AC-019): no marker in `src/` cites #1198 or #1248 except named residuals, #1200 stays x8; validates (i) publish-log lines, at least one per in-scope type, against the **released** schemas read from the installed package, and (ii) one row per run-journal kind in legacy-row form. Run `tests/architectural/test_lifted_lint.py`. **Covers** FR-032, FR-033, AC-019.

## Subtask T048: Readers untouched

**Steps**: gate/test that the Python readers (`rebuild_state` readers, reducers) are unchanged in behaviour and no legacy row is rewritten (FR-036, FR-037); the full byte-identity run of archived Missions is AC-015, executed in WP10.

## Subtask T049: Glossary and Contextive

**Steps**: add Producer, Publish seam, Publish log, Ledger, Fan-out (not "sync"), Clockless row, Clocked row, Fault log to `docs/context/system-events.md` (definitions: carryover L); run `scripts/generate_contextive_glossaries.py generate` then `check`; add one targeted test running `check` (no CI job runs it today). Terminology guard green. **Covers** FR-046.

## Subtask T050: ADRs

**Steps**: amend ADR 2026-10-13-2 D-1, D-2, D-3 in place; new ADR `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md`; update `docs/adr/4.x/index.md`; the amendment names D-1, D-2, D-3 (checked by test); run docs spelling and changelog-style gates. **Covers** FR-045, AC-033 (ADR part).

## Subtask T051: Gate pass

**Steps**: run the touched-file gates individually (R-7) and compare to the baseline; `uv lock --check`; Gradle unaffected.

## Gates

`make lint`; ruff check and format; mypy on touched modules; targeted pytest: `tests/architectural/test_producer_client_thinness.py test_single_lane_list.py test_exemption_ledger.py test_lifted_lint.py test_layer_rules.py`, `tests/specify_cli/migration/test_rebuild_state.py`, `tests/contract/test_identity_contract_matrix.py`, the contextive test, the acceptance tests of `_LEGACY_NOT_DONE_LANES`. Red-first, revert-check each behaviour.

## Definition of Done

T044 to T051 done; gate (b) green with its mutation red; no marker cites #1198/#1248 except residuals; contextive `check` green.

## Risks

The only Python-writer modification (flagged narrow exception): any extra logic fails gate (b). Lane consolidation can break `test_rebuild_state`.

## Reviewer Guidance

Diff of `engine.py` must show two keys added and one import; characterisation tests must be red on the base; every residual marker needs an issue reference.

## Implementation command

`spec-kitty agent action implement WP07 --agent claude --mission event-producer-reroute-01M4VDY9`
