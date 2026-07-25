# Tasks: Review-Cycle Read Authority

**Mission**: `review-cycle-read-authority-01KYB6Z0`
**Branch**: `fix/review-cycle-read-authority` → merge target `fix/review-cycle-read-authority`
**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Research**: [research.md](research.md)

## The one thing every implementer must know

A review-cycle file is written **only for a rejection**. An approval that supersedes it is recorded
as an **event-log annotation**, never as a file. Three sites read only the file and so report a
rejection forever.

**The trap**: `read_events` deliberately partitions annotations out (`status/store.py:717`) and
`reduce(events)` therefore leaves the `review` slot **always empty** (`reducer.py:201` sets it only
inside `_apply_annotation_delta`). A fix that threads that snapshot into the verdict call
type-checks, passes hand-built-dict unit tests, and returns `None` for every real override.

**Therefore**: every override test in this mission must be built from a **real event log
containing an `InnerStateChanged` review annotation**. A test that hand-constructs snapshot state
proves nothing. Reviewers must reject such tests.

## Subtask Index

| ID | Description | WP | Parallel |
|----|-------------|----|----------|
| T001 | Red-first: status board reports stale rejection despite complete override | WP01 | |
| T002 | Switch status.py event read to the annotation-aware stream | WP01 | |
| T003 | Resolve override and pass it to the canonical verdict read | WP01 | |
| T004 | Preserve tolerant degradation on absent/malformed log | WP01 | [P] |
| T005 | Prove genuine rejection and incomplete override still reported | WP01 | [P] |
| T006 | Red-first: tasks-status surface reports stale rejection despite override | WP02 | |
| T007 | Switch tasks_status_cmd event construction to the annotation-aware stream | WP02 | |
| T008 | Retire the override-blind verdict body onto the canonical read | WP02 | |
| T009 | Preserve the existing swallow-and-degrade behaviour | WP02 | [P] |
| T010 | Prove parity with the status-board answer on identical fixtures | WP02 | [P] |
| T011 | Red-first: approved→done refused despite a recorded override | WP03 | |
| T012 | Add the transactional stream read inside the existing boundary | WP03 | |
| T013 | Implement the effective-verdict projection | WP03 | |
| T014 | Prove the guard permits when a complete override exists | WP03 | |
| T015 | Prove all three refusal arms intact | WP03 | [P] |
| T016 | Prove override-evidence durability | WP03 | [P] |
| T017 | Add the snapshot-taking entry point to wp_review | WP04 | |
| T018 | Route post_merge onto it and delete the duplicate | WP04 | |
| T019 | Prove merge-blocking behaviour is unchanged | WP04 | [P] |
| T020 | Prove no per-WP re-reduction was introduced | WP04 | [P] |
| T021 | Author the two-pass enumeration gate | WP05 | |
| T022 | Encode the disposition table with per-site reasons | WP05 | |
| T023 | Prove the gate is non-vacuous | WP05 | |
| T024 | Set a concrete floor covering both passes | WP05 | [P] |

Record completion with `spec-kitty agent tasks mark-status T001 --status done`. Completion is
event-sourced; there is no checkbox to tick.

---

## WP01 — Status-board verdict honours the override

**Goal**: The status board stops reporting an approved work package as carrying a rejected review.
**Priority**: P1 · **Requirements**: FR-001, FR-002, FR-003, FR-006
**Independent test**: With a real event log carrying a complete override, `agent tasks status`
emits no stale-verdict warning.
**Prompt**: [tasks/WP01-status-board-override-aware.md](tasks/WP01-status-board-override-aware.md) (~320 lines)

**Subtasks**: T001, T002, T003, T004, T005

**Implementation sketch**: red-first test from a real event log → switch `:165-168` to the
annotation-aware stream → resolve the override per WP and pass it to the canonical verdict read →
prove degradation and the no-false-negative cases.

**Dependencies**: **WP04** (its snapshot entry point). **Risks**: the annotation-blind trap above;
NFR-002 — verify by spying on `reduce`, not by counting reads.

---

## WP02 — Tasks-status verdict honours the override

**Goal**: The same warning on the tasks-status surface reaches the same answer.
**Priority**: P1 · **Requirements**: FR-001, FR-002, FR-003, FR-004, FR-006
**Independent test**: identical fixture to WP01 yields an identical verdict on this surface.
**Prompt**: [tasks/WP02-tasks-status-override-aware.md](tasks/WP02-tasks-status-override-aware.md) (~320 lines)

**Subtasks**: T006, T007, T008, T009, T010

**Implementation sketch**: red-first test → switch `tasks_status_cmd.py:279-283` to the
annotation-aware stream → retire `_get_latest_review_cycle_verdict`'s body onto the canonical read
while preserving its `(verdict, path)` contract → prove degradation and parity.

**Dependencies**: **WP04** (its snapshot entry point). **Risks**: the function is re-exported and
shared with WP03, so its contract change has two consumers.

---

## WP03 — Transition guard receives the corrected verdict

**Goal**: An operator who already recorded an arbiter override is not forced to re-assert it.
**Priority**: P1 · **Requirements**: FR-007, FR-003
**Independent test**: an override-approved work package moves `approved → done` with no override
flags re-supplied.
**Prompt**: [tasks/WP03-transition-guard-effective-verdict.md](tasks/WP03-transition-guard-effective-verdict.md) (~420 lines)

**Subtasks**: T011, T012, T013, T014, T015, T016

**Implementation sketch**: red-first test → add `read_event_stream_transactional` inside the
existing lane-read boundary → project the two-valued rule onto the guard's single `str | None` →
prove permit, all three refusal arms, and override durability.

**Dependencies**: **WP02** (shares `_get_latest_review_cycle_verdict`).
**Risks**: highest of the mission — this changes when a lifecycle transition is refused.
`tasks_transition_core.py` is **read and pinned by tests, never edited** (C-002).

---

## WP04 — Retire the third override-resolution duplicate

**Goal**: `post_merge`'s private duplicate is routed onto the canonical seam, so SC-004 is true by
construction rather than by omission.
**Priority**: P2 · **Requirements**: FR-004, FR-005
**Independent test**: merge-blocking findings are byte-identical before and after, with the
duplicate deleted.
**Prompt**: [tasks/WP04-retire-post-merge-duplicate.md](tasks/WP04-retire-post-merge-duplicate.md) (~260 lines)

**Subtasks**: T017, T018, T019, T020

**Implementation sketch**: add a snapshot-taking entry point to `wp_review` that the existing two
delegate to → route `post_merge` onto it → delete `_snapshot_review_override` → prove parity.

**Dependencies**: none — **this is wave 1; WP01 and WP02 depend on it**.
**Risks**: the new entry point must be the *shared* implementation, not a fourth copy. Not a live
defect today — this is drift and a false SC-004 claim, not broken output.

---

## WP05 — Pin the disposition so the class cannot silently return

**Goal**: A newly-added unclassified reader fails a test instead of passing unnoticed.
**Priority**: P2 · **Requirements**: FR-005, SC-004
**Independent test**: adding an unclassified reader turns the gate red.
**Prompt**: [tasks/WP05-disposition-gate.md](tasks/WP05-disposition-gate.md) (~280 lines)

**Subtasks**: T021, T022, T023, T024

**Implementation sketch**: enumerate both passes (record readers by glob, override readers by slot
access) → encode the disposition table with reasons → prove non-vacuity by adding a decoy →
concrete floor covering both passes.

**Dependencies**: **WP01, WP02, WP03, WP04** — the in-scope set must be retired first or the gate
codifies the defect.
**Risks**: must not be a location-scoped grep; that shape was proven both too strict (fails
legitimately excluded sites) and too weak (passes a duplicate inside the canonical module).

---

## Parallelisation

- **Wave 1**: **WP04** alone — it builds the snapshot entry point WP01 and WP02 both need.
- **Wave 2** (parallel): WP01, WP02
- **Wave 3**: WP03 (after WP02)
- **Wave 4**: WP05 (after all)

> **Why WP04 leads.** Both display surfaces iterate every work package, and **both** existing
> `wp_review` entry points re-reduce the whole event stream on each call —
> `resolve_event_stream_review`'s body is `reduce(...).work_packages.get(wp_id)`, with no
> memoization. Calling either inside a per-WP loop costs N reductions and breaks NFR-002. Only
> WP04's already-materialized-snapshot entry point is O(1) per work package. An earlier version of
> this plan had WP01/WP02 calling `resolve_event_stream_review` per work package; the post-tasks
> gate caught that it is the same anti-pattern the prompts explicitly forbid.

**Verification note**: NFR-002 must be checked by **counting `reduce` calls** (spy asserting
exactly one), not by counting reads or grepping for a forbidden function name. A per-WP
`resolve_event_stream_review` passes every name-based check while reducing N times.

## MVP scope

**WP04 → WP01 + WP02** delivers the reported symptom fix (#2646). WP03 is the highest-value half
but carries lifecycle risk; WP05 is anti-recurrence.
