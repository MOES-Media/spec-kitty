---
work_package_id: WP02
title: Tasks-status verdict honours the approval override
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- FR-006
planning_base_branch: fix/review-cycle-read-authority
merge_target_branch: fix/review-cycle-read-authority
branch_strategy: Planning artifacts for this mission were generated on fix/review-cycle-read-authority. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into fix/review-cycle-read-authority unless the human explicitly redirects the landing branch.
subtasks:
- T006
- T007
- T008
- T009
- T010
phase: Phase 1 - Read-path correction
history:
- at: '2026-07-25T00:54:24Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/specify_cli/cli/commands/agent/
create_intent:
- tests/specify_cli/cli/commands/agent/test_tasks_status_review_override.py
execution_mode: code_change
model: claude-sonnet-5
owned_files:
- src/specify_cli/cli/commands/agent/tasks_status_cmd.py
- src/specify_cli/cli/commands/agent/tasks_parsing_validation.py
- tests/specify_cli/cli/commands/agent/test_tasks_status_review_override.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP02 – Tasks-status verdict honours the approval override

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

- **Has review feedback?**: Check the `review_ref` field in the event log.
- **You must address all feedback** before your work is complete.
- **Report progress** in the Activity Log as you address each item.

---

## Objective

Make the tasks-status surface's stale-verdict warning honour a recorded approval override, and
retire `_get_latest_review_cycle_verdict` as an independent verdict implementation — while
preserving its `(verdict, path)` contract for its two consumers.

## Branch Strategy

- **Planning base branch**: `fix/review-cycle-read-authority`
- **Final merge target**: `fix/review-cycle-read-authority`
- Execution happens in the worktree allocated for this WP's computed lane (`lanes.json`).

## Implementation command

```bash
spec-kitty agent action implement WP02 --agent claude
```

## Context you must absorb before writing code

Read `../research.md` Decisions 1 and 3, and `../data-model.md`. Key facts:

1. Review-cycle files exist **only for rejections**; approvals are event-log annotations.
2. `read_events` partitions annotations out (`status/store.py:717`); `reduce(events)` therefore
   leaves the `review` slot **always empty** (`reducer.py:201`). See WP01's trap section — it
   applies identically here.
3. `tasks_status_cmd.py:279-283` is the **actual construction site**: it builds `st.events` and
   `st.snapshot` and threads them into `_apply_review_status_flags` (`:349`), which reaches
   `_get_latest_review_cycle_verdict` at `tasks_parsing_validation.py:371`.
4. That function is **re-exported** (`tasks.py:196`, `__all__` at `tasks_parsing_validation.py:1006`)
   and its second consumer is `tasks_move_task.py:552` — **WP03's surface**. Your contract change
   has two consumers; coordinate via the shared contract, not by editing WP03's file.

> **Ownership boundary**: `tasks_move_task.py` belongs to **WP03**. Do not edit it. If your change
> to `_get_latest_review_cycle_verdict`'s signature would break that caller, keep the existing
> signature working (add an optional parameter) so WP03 can adopt it independently.

---

## Subtasks

### T006 — Red-first: prove the defect on this surface

**Purpose**: NFR-004 / ADR `2026-07-17-1`.

**Steps**:

1. Create `tests/specify_cli/cli/commands/agent/test_tasks_status_review_override.py`.
2. Build the same fixture shape as WP01: a rejected `review-cycle-1.md`, an `approved` lane, and a
   **real event log** with an `InnerStateChanged` annotation carrying a complete `ReviewOverride`.
3. Assert the tasks-status surface currently reports a stale verdict.

**Fixture rule**: the override must arrive through the event log. A hand-built
`state["review"]` mapping passes against the broken code and proves nothing.

**Validation**: red before T007/T008, green after.

---

### T007 — Switch the event construction to the annotation-aware stream

**Purpose**: Make the `review` slot reachable on this surface.

**Steps**:

1. In `tasks_status_cmd.py:279-283`, replace `_st_read_events` + `_st_reduce` with the
   annotation-aware stream read.
2. Thread the resolved stream (or the per-WP override derived from it) into
   `_apply_review_status_flags` at `:349`, so the verdict decision can see it.
3. Read and reduce **once per invocation** (NFR-002) — never once per work package.

**Files**: `src/specify_cli/cli/commands/agent/tasks_status_cmd.py`

**Validation**: the snapshot's per-WP state carries a `review` slot when the log has an annotation.

---

### T008 — Retire the override-blind verdict body onto the canonical read

**Purpose**: FR-004 — one implementation of "current review verdict".

**Steps**:

1. Rewrite `_get_latest_review_cycle_verdict` (`tasks_parsing_validation.py:286-320`) so it
   delegates to `latest_review_artifact_verdict(wp_dir, snapshot_override=...)` rather than
   globbing and parsing frontmatter itself.
2. **Preserve the `(verdict, path)` return contract** — the path feeds error messages naming the
   artifact, and both consumers depend on it. The canonical read returns a richer object; adapt it,
   do not leak it.
3. Accept the override as an **optional** parameter so the existing `tasks_move_task.py` caller
   keeps compiling unchanged (WP03 adopts it separately).
4. Obtain the override via `wp_review.resolve_event_stream_review(event_stream, wp_id)`. Do **not**
   copy `post_merge`'s `_snapshot_review_override` — that duplicate is being retired by WP04.
5. Preserve the existing `_VALID_VERDICTS` warning behaviour for out-of-vocabulary verdicts.

**Files**: `src/specify_cli/cli/commands/agent/tasks_parsing_validation.py`

**Validation**: T006 flips green; the re-export at `tasks.py:196` still resolves; no consumer
breaks.

---

### T009 — Preserve the existing swallow-and-degrade behaviour

**Purpose**: FR-006 / NFR-001.

**Steps**:

1. `tasks_status_cmd.py:283` currently wraps the read in a bare `except` that degrades to
   `st.events = []`. The stream read must degrade **the same way** — do not introduce a new raise
   path into the status command.
2. Prove: absent log, unparseable log, unparseable artifact frontmatter, and missing work-package
   directory each degrade rather than raising.

**Validation**: a test per case; the command still renders in every one.

---

### T010 — Prove parity with the status-board answer

**Purpose**: FR-002 / INV-2 — the surfaces must not disagree.

**Steps**:

1. Build one fixture set covering: overridden rejection; unoverridden rejection; incomplete
   override (parameterised over all four missing fields); no record at all.
2. Assert this surface's verdict matches WP01's status-board verdict for every case.
3. If WP01 has not landed yet, assert against the canonical read's answer directly — that is the
   shared authority both surfaces must converge on.

**Validation**: zero divergence across the matrix.

---

## Definition of Done

- [ ] T006's test was committed red before the fix (verifiable in history).
- [ ] Every override test drives the override through a **real event log**.
- [ ] `_get_latest_review_cycle_verdict` no longer globs or parses frontmatter itself.
- [ ] Its `(verdict, path)` contract is intact and both consumers still compile.
- [ ] `tasks_move_task.py` is **untouched** (WP03 owns it).
- [ ] Log read and reduced once per invocation; `resolve_snapshot_review` absent from per-WP loops.
- [ ] All degradation cases pass; the bare-except posture is preserved.
- [ ] Parity matrix green.
- [ ] `ruff` and `mypy` clean, no new suppressions.

## Risks

- **The annotation-blind trap** (see WP01) — identical here.
- **Shared-contract breakage**: this function has two consumers. Changing its signature
  non-optionally will break `tasks_move_task.py`, which you do not own.
- **Over-reach**: `tasks_move_task.py` and `tasks_transition_core.py` belong to WP03.

## Reviewer guidance

1. **Check fixtures first** — reject any override test that hand-builds snapshot state.
2. Confirm `tasks_status_cmd.py` no longer uses the annotation-stripping read.
3. Confirm `_get_latest_review_cycle_verdict` delegates rather than re-implements, and that its
   `(verdict, path)` contract survives.
4. Confirm `tasks_move_task.py` shows **zero** diff in this WP.
5. Verify red-first ordering in history.

## Activity Log

| At | Actor | Action |
|----|-------|--------|
| 2026-07-25T00:54:24Z | system | Prompt generated via /spec-kitty.tasks |
