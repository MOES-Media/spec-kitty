# Known Gap: `spec-kitty` CLI status-tracking defects observed during WP02

**Not a muster or mission-content defect** — this documents a defect in the
`spec-kitty` CLI tooling itself (`MOES-Media/spec-kitty`), encountered while
running this WP's own status-tracking commands. Filed upstream as
[`MOES-Media/spec-kitty#45`](https://github.com/MOES-Media/spec-kitty/issues/45)
and recorded here per the mission's own Evidence Artifact principle — a
defect this significant to how work packages are tracked should not live
only in an issue tracker a future contributor might not think to search.

## What was observed

1. **`spec-kitty agent tasks mark-status`'s first invocation per mission is
   silently dropped while the command still reports
   `{"outcome": "updated"}`.** Reproduced twice on this mission
   (`doctrine-behavioral-suite-01KYW5XK`, WP02, subtask sequence
   T006→T007→T008→...): `T006` (the first call) was lost in both trials;
   `T007`/`T008` (the next two calls in the same sequence) persisted
   correctly in both trials. No error, no warning, no diagnostic
   distinguishing the dropped call's output from a genuinely successful one.

2. **`spec-kitty agent status materialize`'s human-readable `event_count`
   counts WP-lane-transition events only, not the raw line count of
   `status.events.jsonl`** (`src/specify_cli/status/reducer.py:366`,
   `event_count=len(sorted_events)`, where `sorted_events` is
   `EventStream.transitions`, `src/specify_cli/status/store.py:741`) — a
   confusing label regardless of the specific figures involved. An earlier
   pass over this mission additionally reported the more concerning shape
   `"0 events -> 1 WPs"` against a log that already had 2 `WPCreated`
   events; independent re-verification during this remediation pass, on
   this mission's current 8-line/2-`WPCreated` event log, produced
   `event_count: 2` with both WPs correctly present — internally
   consistent under the transitions-only scoping and **not** a
   reproduction of the original `0`/`1 WPs` figures. Filed as an open
   question for the maintainer (see the issue for the full reasoning), not
   asserted as a confirmed root cause.

Both are filed as further instances of the "reports success while silently
dropping/misreporting content" family previously identified in this
programme's other missions
(`MOES-Media/spec-kitty#33`, `#35`, `#36`, `#39`).

## Impact on this WP

None of this mission's own committed work (FR-005/FR-007/C-001/C-002
deliverables) depends on `mark-status`'s auto-commit or `materialize`'s
`event_count` field for correctness — both are status-tracking metadata,
not the behavioral suite's own graded artifacts. The impact is
operational: a mission's very first subtask-completion call can be lost
silently, which is easy to misdiagnose as an agent forgetting to mark a
subtask done rather than a CLI defect, and is worth a future contributor
knowing about before trusting `mark-status`'s own JSON output as proof a
status update actually landed.

## Workaround used in this WP

Every `mark-status`/status-tracking call this WP made was followed by a
`git status` + `git log --oneline` check (per this WP's own Definition of
Done) rather than trusting the command's own reported outcome — the
correct mitigation until the upstream defect is fixed.
