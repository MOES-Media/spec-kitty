---
work_package_id: WP07
title: Run-journal stamping, lane-list consolidation, glossary and ADRs
dependencies:
- WP06
requirement_refs:
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
- T045
- T046
- T048
- T049
- T050
- T051
history: []
agent_profile: python-pedro
authoritative_surface: src/specify_cli/
create_intent:
- tests/architectural/test_single_lane_list.py
- tests/runtime/test_journal_event_stamping.py
- tests/cross_cutting/encoding/test_contextive_event_producer_terms.py
- docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md
execution_mode: code_change
model: ''
owned_files:
- tests/architectural/test_single_lane_list.py
- tests/runtime/test_journal_event_stamping.py
- tests/specify_cli/migration/test_rebuild_state.py
- tests/contract/test_identity_contract_matrix.py
- src/specify_cli/migration/rebuild_state.py
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

# WP07: Run-journal stamping, lane-list consolidation, glossary and ADRs

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Stamp the Python run-journal rows (ULID `event_id`, row version) with gate (b), consolidate the lane lists, prove the readers are untouched, and add the glossary terms and ADRs. Marker clearing and the persisted-line lint mode, which validate against the released schemas, are **WP12** (tasks-overview R6); this WP reads no released artifact and is **not gated by the events release**.

## Context

Plan sections 4.2 (`engine.py`, constants rows), 4.4 (lane part), 9, 13 items 3, 6, 7; research R-14, R-18, R-19, R-24; data-model sections 8 (reference), 9. OD-JOURNAL is the ONE narrow modification of existing Python (field stamping only, no new logic, no new function).

**Chokepoints:** second editor of `engine.py` (after WP06) and of `tests/architectural/test_producer_client_thinness.py` (adds rule (b)); both are recorded edits below. **Check open PRs** on the owned files before the first commit: PR #6205 rewrites the ADR `docs/adr/4.x/2026-10-13-2-the-event-ledger-is-the-publication-seam-default-on-with-local-retention.md` that T050 amends; rebase onto it or take theirs, then re-apply D-1, D-2, D-3.

## Subtask T045: Lane-list census and consolidation (FR-034)
**Steps**: first, the census of data-model section 9 plus any further hit (read `tests/specify_cli/migration/test_rebuild_state.py` and the acceptance tests first). For each of `migration/rebuild_state.py::_VALID_LANES`, `acceptance/__init__.py::_LEGACY_NOT_DONE_LANES` (+ `doing` alias constant), `consolidation/done_bookkeeping.py::_PRE_APPROVED_LANE_VALUES`: red characterisation test (value and order equal to the pre-change literal), then derive per research R-14 (`CANONICAL_LANES` / `Lane` by name; none derived wholesale). Imports exactly the plan 4.2 rows (a cycle in `done_bookkeeping` makes it a reviewed exception). `tests/architectural/test_single_lane_list.py`: finds the census lists (floor six, exceptions listed), fails on a new full literal list (mutation case). `uninitialized` is excluded from the persisted lists. **AC-020 split:** this task verifies the one-definition census and the list exclusion; the producer-side observables (`doing` published as `in_progress`, a `uninitialized` row refused) are asserted by WP04 T024 (test-local registry) and WP11 T073 (released registry). **Covers** FR-034, AC-020 (census).

## Subtask T046: `_append_event` stamping and gate (b)
**Steps**: red test first: the real `_append_event` output has keys exactly `event_id,row_version,event_type,timestamp,payload`, `event_id` a 26-character Crockford ULID that `ulid` parses (`^[0-9A-HJKMNP-TV-Z]{26}$`), `row_version` 1; a journal without them is still read. Implementation: import `ulid` only (as `decisions/emit.py` does), no new function. Add rule (b) to the thinness gate: key-set equality, AST walk finds no `uuid4`, `hashlib`, `secrets`, `random` in the body, mutation with `str(uuid4())` fails. Update the `run.events.jsonl` expectation in `tests/contract/test_identity_contract_matrix.py`. **Covers** FR-035, AC-022.

## Subtask T048: Readers untouched
**Steps**: red-then-green test in the owned `tests/specify_cli/migration/test_rebuild_state.py`: feed a **pre-stamp legacy** `run.events.jsonl` (no `event_id`, no `row_version`) and a legacy status ledger fixture through the CLI readers (`rebuild_state` readers and the reducers) and assert (i) the file bytes are identical afterwards, (ii) the reduced output equals the pre-change golden, (iii) no row is rewritten. Floor: at least one legacy row read. Mutation: a reader variant that rewrites a row must fail (i). The full byte-identity run over archived Missions is AC-015, executed in WP10 T066. **Covers** FR-036, FR-037.

## Subtask T049: Glossary and Contextive
**Steps**: add Producer, Publish seam, Publish log, Ledger, Fan-out (not "sync"), Clockless row, Clocked row, Fault log to `docs/context/system-events.md` (definitions: carryover L); run `scripts/generate_contextive_glossaries.py generate` then `check`; add one targeted test running `check` (no CI job runs it today). Terminology guard green. **Covers** FR-046.

## Subtask T050: ADRs
**Steps**: amend ADR 2026-10-13-2 D-1, D-2, D-3 in place; new ADR `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md`; update `docs/adr/4.x/index.md`; the amendment names D-1, D-2, D-3 (checked by test); run docs spelling and changelog-style gates. **Covers** FR-045, AC-033 (ADR part).

## Subtask T051: Gate pass
**Steps**: run the touched-file gates individually (R-7) and compare to the baseline; `uv lock --check`; Gradle unaffected.

## Tests, markers, jobs

| New test file | Marker | Runs in |
|---|---|---|
| `tests/architectural/test_single_lane_list.py` | `architectural` | architectural tier |
| `tests/runtime/test_journal_event_stamping.py` | `fast` | runtime shard |
| `tests/cross_cutting/encoding/test_contextive_event_producer_terms.py` | per the folder's existing marker (check) | encoding shard |

Run `tests/architectural/test_fast_tier_marker_completeness.py` and `test_module_shard_registry.py` with the new files.

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `src/runtime/next/_internal_runtime/engine.py` (WP06) | `_append_event` stamping and the `ulid` import | FR-035; second editor after WP06 |
| `tests/architectural/test_producer_client_thinness.py` (WP06) | rule (b) | rule (b) lands with the stamping; second editor after WP06 |

## Gates
`make lint`; ruff check and format; mypy on touched modules; targeted pytest: `tests/architectural/test_producer_client_thinness.py test_single_lane_list.py test_lifted_lint.py test_layer_rules.py test_clock_import_ban.py test_clock_call_ban.py`, `tests/specify_cli/migration/test_rebuild_state.py`, `tests/contract/test_identity_contract_matrix.py`, the contextive test, the acceptance tests of `_LEGACY_NOT_DONE_LANES`. Red-first, revert-check each behaviour.

## Definition of Done
T045, T046, T048 to T051 done; gate (b) green with its mutation red; reader-immutability test green with its mutation red; contextive `check` green.

## Risks
The only Python-writer modification (flagged narrow exception): any extra logic fails gate (b). Lane consolidation can break `test_rebuild_state`.

## Reviewer Guidance
Diff of `engine.py` must show two keys added and one import; characterisation tests must be red on the base.

## Implementation command

`spec-kitty agent action implement WP07 --agent claude --mission event-producer-reroute-01M4VDY9`
