---
work_package_id: WP01
title: Status-board verdict honours the approval override
dependencies:
- WP04
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-006
planning_base_branch: fix/review-cycle-read-authority
merge_target_branch: fix/review-cycle-read-authority
branch_strategy: Planning artifacts for this mission were generated on fix/review-cycle-read-authority. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into fix/review-cycle-read-authority unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
phase: Phase 1 - Read-path correction
history:
- at: '2026-07-25T00:54:24Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/specify_cli/agent_utils/
create_intent:
- tests/specify_cli/agent_utils/test_status_review_override.py
execution_mode: code_change
model: claude-sonnet-5
owned_files:
- src/specify_cli/agent_utils/status.py
- tests/specify_cli/agent_utils/test_status_review_override.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP01 – Status-board verdict honours the approval override

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the frontmatter (or any
user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

If no profile is specified, run `spec-kitty agent profile list` and select the best match for this
work package's `task_type` and `authoritative_surface`.

---

## ⚠️ IMPORTANT: Review Feedback

**Read this first if you are implementing this task!**

- **Has review feedback?**: Check the `review_ref` field in the event log (via
  `spec-kitty agent tasks status` or the Activity Log below).
- **You must address all feedback** before your work is complete.
- **Report progress**: as you address each item, update the Activity Log explaining what changed.

---

## Objective

Make the status board's stale-verdict warning honour a recorded approval override, so a work
package that was approved stops reporting itself as carrying a rejected review.

## Branch Strategy

- **Planning base branch**: `fix/review-cycle-read-authority`
- **Final merge target**: `fix/review-cycle-read-authority`
- Execution happens in the worktree allocated for this WP's computed lane (`lanes.json`). Do not
  create worktrees by hand; `spec-kitty agent action implement WP01 --agent claude` resolves it.

## Implementation command

```bash
spec-kitty agent action implement WP01 --agent claude
```

## Context you must absorb before writing code

Read `../research.md` Decision 3 and `../data-model.md` in full. The short version:

1. A `review-cycle-N.md` file is written **only for a rejection**. `validate_review_artifact`
   (`src/specify_cli/review/cycle.py:186`) hard-requires `verdict == "rejected"`. An approval never
   writes a file.
2. An approval that supersedes a rejection is recorded as an **event-log annotation**
   (`InnerStateChanged` → `WPInnerStateDelta.review`), surfacing as the `review` slot of the reduced
   per-WP snapshot.
3. The canonical verdict read is
   `specify_cli.review.artifacts.latest_review_artifact_verdict(sub_artifact_dir,
   snapshot_override=...)`. It already returns `has_override`.
4. The canonical override read is
   `specify_cli.status.wp_review.resolve_event_stream_review(event_stream, wp_id)`. The merge gate
   already uses it (`merge/done_bookkeeping.py:109-111`).

### ☠️ The trap that makes a wrong fix look right

`src/specify_cli/agent_utils/status.py:165-168` currently does:

```python
events = read_events(feature_dir)
snapshot = reduce(events)
```

`read_events` **deliberately partitions annotations out** — see its docstring at
`status/store.py:717-722`. `reduce(events)` leaves `annotations` empty, and `state["review"]` is
assigned at exactly one place (`reducer.py:201`) inside `_apply_annotation_delta`, which only runs
over that argument.

**Consequence**: the `review` slot of that snapshot is *always absent*. If you thread this snapshot
into the verdict call, the code compiles, type-checks, passes any test that hand-builds a state
dict — and returns `None` for every real override in production. It would close nothing while
looking green.

**You must switch to the annotation-aware stream read.** This is the whole point of the work
package.

---

## Subtasks

### T001 — Red-first: prove the defect from a real event log

**Purpose**: Land a failing test *before* the fix, per NFR-004 and ADR `2026-07-17-1`.

**Steps**:

1. Create `tests/specify_cli/agent_utils/test_status_review_override.py`.
2. Build a fixture mission with one work package that has:
   - a `review-cycle-1.md` record with `verdict: rejected` (valid per
     `validate_review_artifact` — it needs `cycle_number`, `wp_id`, `mission_slug`,
     `reviewer_agent`, `reviewed_at`, `body`);
   - a lane of `approved`;
   - **a real event log** containing an `InnerStateChanged` annotation carrying a complete
     `ReviewOverride` (all of `at`, `actor`, `wp_id`, `reason`).
3. Assert the status read reports a stale verdict for that work package. **This assertion is
   expected to pass now and must be inverted in T003** — or write it as the post-fix assertion and
   mark it `xfail(strict=True)`; either is acceptable, but the red state must be committed first.

**Fixture rule (load-bearing)**: the override must reach the code under test **through the event
log**. Do not construct `snapshot.work_packages[wp_id]["review"]` by hand — a test built that way
passes against the broken implementation and proves nothing.

**Validation**: the test is red before T002/T003 and green after.

---

### T002 — Switch the event read to the annotation-aware stream

**Purpose**: Make the `review` slot reachable at all.

**Steps**:

1. In `src/specify_cli/agent_utils/status.py`, replace the `read_events` + `reduce(events)` pair at
   `:165-168` with the annotation-aware stream read, so annotations survive into the snapshot.
2. Keep the existing `try/except` posture — this read is already defensive and must remain so
   (see T004).
3. Do **not** add a second read. NFR-002 requires the log be read and reduced **once per
   invocation**, never once per work package.

**Files**: `src/specify_cli/agent_utils/status.py`

**Validation**:
- Existing status tests still pass.
- The reduced snapshot's per-WP state now carries a `review` slot when the log has an annotation
  (assert this directly in a focused test — it is the pivot of the whole fix).

---

### T003 — Resolve the override and pass it to the canonical verdict read

**Purpose**: Combine the two inputs using the existing canonical helpers instead of a new one.

**Steps**:

1. Retire `_get_wp_review_verdict` (`:41-63`) as an independent implementation. Its replacement
   must delegate to `latest_review_artifact_verdict(sub_artifact_dir, snapshot_override=...)`.
2. Obtain the override using **WP04's already-materialized-snapshot entry point** in
   `specify_cli.status.wp_review`, indexing the single snapshot reduced in T002.
   - **Do NOT** call `resolve_event_stream_review(event_stream, wp_id)` inside the per-WP loop at
     `:266-273`. Its body is
     `reduce(event_stream.transitions, event_stream.annotations).work_packages.get(wp_id)` — a
     **full reduction on every call**, with no memoization. Calling it per work package reduces N
     times and breaks NFR-002.
   - **Do NOT** use `resolve_snapshot_review(feature_dir, wp_id)` — same re-reduction, plus disk
     I/O.
   - **Do NOT** copy `post_merge`'s private `_snapshot_review_override`. That is a duplicate being
     retired by WP04; copying it authors a fourth (C-003, DIRECTIVE_044).
   - Reduce once (T002), then index. That is the only shape that satisfies NFR-002.
3. At the call site (`:273`), treat the work package as carrying a live rejection only when
   `verdict == "rejected" and not has_override`.

**Files**: `src/specify_cli/agent_utils/status.py`

**Validation**: T001 flips to green. The warning disappears for an overridden rejection.

---

### T004 — Preserve tolerant degradation

**Purpose**: FR-006 / NFR-001 — damaged state must not break the operator's diagnostics.

**Steps**: prove each of these degrades rather than raising:

1. Event log absent entirely → status still renders; verdict falls back to the file-only answer.
2. Event log present but unparseable → same.
3. `review-cycle-N.md` present but frontmatter unparseable → "no verdict", no raise.
4. Work-package directory missing → no verdict, no raise.

**Validation**: zero new uncaught exception paths in the status read. Each case has a test.

---

### T005 — Prove no false negative was introduced

**Purpose**: FR-003. Removing a false positive must not silence a real rejection.

**Steps**: assert the warning **still fires** for:

1. A rejection with **no** override recorded.
2. A rejection whose override is **incomplete** — missing any one of `at`, `actor`, `wp_id`,
   `reason`. Parameterise across all four omissions; each must remain reported.

**Validation**: `ReviewOverride.complete` is the only completeness authority — do not re-implement
the predicate.

---

## Definition of Done

- [ ] T001's test was committed red before the fix landed (verifiable in history).
- [ ] Every override test drives the override **through a real event log**; none hand-builds
      snapshot state.
- [ ] `_get_wp_review_verdict` no longer independently selects a record or decides a verdict.
- [ ] **A spy on `specify_cli.status.reducer.reduce` asserts exactly ONE call** for a mission with
      several work packages. Counting reads or checking for absent function names is **not**
      sufficient — a per-WP `resolve_event_stream_review` passes those checks while reducing N
      times.
- [ ] Neither `resolve_event_stream_review` nor `resolve_snapshot_review` appears inside a per-WP
      loop.
- [ ] All four degradation cases pass; no new raise reaches the operator.
- [ ] No-override and all four incomplete-override cases still report the rejection.
- [ ] `ruff` and `mypy` clean, with no new suppressions.

## Risks

- **The annotation-blind trap** — the single most likely failure. If your tests pass but manual
  reproduction still shows the warning, you almost certainly kept `read_events`/`reduce(events)`.
- **NFR-002 regression** — reaching for `resolve_snapshot_review` per work package is the easy
  wrong turn; it re-reduces every call.
- **Over-reach** — do not touch `review/artifacts.py`, `tasks_*`, or `post_merge`. Those belong to
  other work packages and overlapping edits will collide.

## Reviewer guidance

1. **Check the fixture first.** If any override test constructs snapshot state by hand, reject —
   it cannot distinguish a working fix from a no-op.
2. Confirm `read_events`/`reduce(events)` is gone from this file's status path.
3. Confirm the verdict decision delegates to `latest_review_artifact_verdict`, and that
   `has_override` — not a re-implemented predicate — drives suppression.
4. Count reductions: exactly one per invocation.
5. Verify the red-first ordering in git history, not just the final green state.

## Activity Log

| At | Actor | Action |
|----|-------|--------|
| 2026-07-25T00:54:24Z | system | Prompt generated via /spec-kitty.tasks |
