---
work_package_id: WP04
title: Retire the third override-resolution duplicate
dependencies: []
requirement_refs:
- FR-004
- FR-005
planning_base_branch: fix/review-cycle-read-authority
merge_target_branch: fix/review-cycle-read-authority
branch_strategy: Planning artifacts for this mission were generated on fix/review-cycle-read-authority. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into fix/review-cycle-read-authority unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-review-cycle-read-authority-01KYB6Z0
base_commit: f661529e319c218932c546fd74dcfad98bc170da
created_at: '2026-07-25T08:16:14.137184+00:00'
subtasks:
- T017
- T018
- T019
- T020
phase: Phase 1 - Canonical consolidation
history:
- at: '2026-07-25T00:54:24Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: randy-reducer
authoritative_surface: src/specify_cli/status/wp_review.py
create_intent:
- tests/specify_cli/status/test_wp_review_snapshot_entry.py
execution_mode: code_change
model: claude-sonnet-5
owned_files:
- src/specify_cli/status/wp_review.py
- src/specify_cli/post_merge/review_artifact_consistency.py
- tests/specify_cli/status/test_wp_review_snapshot_entry.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP04 – Retire the third override-resolution duplicate

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the frontmatter (or any
user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `randy-reducer`
- **Role**: `implementer`
- **Agent/tool**: `claude`

If no profile is specified, run `spec-kitty agent profile list` and select the best match.

---

## ⚠️ IMPORTANT: Review Feedback

- **Has review feedback?**: Check the `review_ref` field in the event log.
- **You must address all feedback** before your work is complete.
- **Report progress** in the Activity Log.

---

## Objective

`post_merge/review_artifact_consistency.py` carries a private third implementation of override
resolution. Route it onto the canonical `wp_review` seam so SC-004's "exactly one implementation
remains" is true by construction rather than by omission.

> **⚠️ This work package is now the mission's foundation — land it first.** WP01 and WP02 both
> depend on it. Every display surface iterates all work packages, and both existing `wp_review`
> entry points **re-reduce the entire event stream on every call**
> (`resolve_event_stream_review`'s body is `reduce(...).work_packages.get(wp_id)`; no memoization
> anywhere in `specify_cli/status/`). Only the already-materialized-snapshot entry point you build
> in T017 is O(1) per work package, so it is the sole shape that satisfies NFR-002 inside a loop.
> Design it as a general seam, not a post-merge-specific helper.

## Branch Strategy

- **Planning base branch**: `fix/review-cycle-read-authority`
- **Final merge target**: `fix/review-cycle-read-authority`
- Execution happens in the worktree allocated for this WP's computed lane (`lanes.json`).

## Implementation command

```bash
spec-kitty agent action implement WP04 --agent claude
```

## Context

`_snapshot_review_override` (`post_merge/review_artifact_consistency.py:112-127`) independently
re-implements `state.get("review")` + `ReviewOverride.from_dict(...)` — exactly what
`status/wp_review.py` centralises. Its own docstring calls it *"the third leg of the both-halves
pair"*: it knows it is a third implementation. It is consumed at `:183` and feeds
`rejected_review_artifact_for_terminal_lane` at `:189`, inside `find_rejected_review_artifact_conflicts`
— a **merge-blocking** verdict, not diagnostics.

**This is not a live defect.** It reads state from `materialize(feature_dir)` (`:168`), which *is*
annotation-aware, so it currently agrees with the canonical seam. You are removing drift risk and a
false SC-004 claim, **not** fixing broken output. Behaviour must be identical before and after.

### The wrinkle that defines this work package

`wp_review` exposes:

- `resolve_event_stream_review(event_stream, wp_id)` — needs a stream the call site does not have;
- `resolve_snapshot_review(feature_dir, wp_id)` — re-reduces on **every call**, and the call site
  is inside a per-WP loop, so this would breach NFR-002.

The call site holds an **already-materialized snapshot**. Neither entry point fits, which is why
the duplicate was written. The fix is to add the missing entry point — and to make it the shared
implementation the other two delegate to.

---

## Subtasks

### T017 — Add the snapshot-taking entry point to `wp_review`

**Purpose**: Give the canonical module the shape the post-merge call site actually needs.

**Steps**:

1. Add an entry point to `src/specify_cli/status/wp_review.py` that resolves a `ReviewOverride`
   from an **already-materialized snapshot** plus a `wp_id` — no re-reduction, no filesystem access.
2. **Refactor the two existing entry points to delegate to it.** This is the whole point: after
   this change there must be exactly one body performing `review` slot → `ReviewOverride`
   construction, with `resolve_event_stream_review` and `resolve_snapshot_review` reduced to thin
   wrappers that obtain a snapshot and call through.
   - If you add the entry point *alongside* the existing bodies without collapsing them, you have
     authored a **fourth** implementation and this work package has caused the drift it exists to
     remove.
3. Export it from `specify_cli.status` alongside the existing two.
4. Add `tests/specify_cli/status/test_wp_review_snapshot_entry.py` covering: complete override
   returned; incomplete override rejected (parameterised over all four missing fields); absent slot
   → `None`; malformed slot → `None`, no raise.
5. **Determinism (NFR-003)**: assert repeated resolution over an unchanged snapshot returns an
   identical result, and that the answer never depends on filesystem enumeration order. Because
   every in-scope consumer delegates here after this WP, this is the one place the property can be
   established for the whole mission — it is otherwise unverified anywhere.

**Files**: `src/specify_cli/status/wp_review.py`

**Validation**: exactly one construction body; the other two delegate; existing `wp_review` tests
still pass unchanged.

---

### T018 — Route `post_merge` onto it and delete the duplicate

**Purpose**: FR-004 — retire the third implementation.

**Steps**:

1. Replace the `_snapshot_review_override(state)` call at `:183` with the new canonical entry point.
2. **Delete** `_snapshot_review_override` (`:112-127`) entirely. Leaving it as a deprecated wrapper
   defeats the purpose — SC-004 counts implementations, not call sites.
3. Leave `_latest_review_artifact_path` (`:132-141`) **alone** — it resolves a path for schema-error
   messages and is explicitly excluded (research.md Decision 1, row 8).
4. Do not change what `materialize(feature_dir)` is called on, or the loop structure.

**Files**: `src/specify_cli/post_merge/review_artifact_consistency.py`

**Validation**: no `ReviewOverride.from_dict` remains in this file.

---

### T019 — Prove merge-blocking behaviour is unchanged

**Purpose**: This is a refactor. Any behaviour change is a defect.

**Steps**:

1. Exercise `find_rejected_review_artifact_conflicts` across: rejection with complete override
   (no finding); rejection without override (finding); incomplete override (finding);
   non-terminal lane (no finding); no artifact (no finding).
2. Assert findings are identical to the pre-change implementation — same count, same work packages,
   same messages.
3. Existing `post_merge` tests must pass **unmodified**. If you need to change an existing
   assertion, stop: that is a behaviour change, not a refactor, and it needs escalation rather than
   a test edit.

**Validation**: full existing `post_merge` suite green with no edits.

---

### T020 — Prove no per-WP re-reduction was introduced

**Purpose**: NFR-002.

**Steps**:

1. Assert the new entry point performs **no** reduction and **no** filesystem access — it takes a
   snapshot it is given.
2. Assert `find_rejected_review_artifact_conflicts` still calls `materialize` exactly once for a
   multi-work-package mission (spy or counter).
3. Confirm `resolve_snapshot_review` does not appear inside the per-WP loop.

**Validation**: reduction count is one for an N-work-package mission, independent of N.

---

## Definition of Done

- [ ] Exactly **one** body constructs a `ReviewOverride` from a `review` slot; the other entry
      points delegate to it.
- [ ] `_snapshot_review_override` is **deleted**, not deprecated.
- [ ] `_latest_review_artifact_path` is untouched.
- [ ] Existing `post_merge` tests pass **unmodified**.
- [ ] Merge-blocking findings identical across the five-case matrix.
- [ ] `materialize` called once per invocation regardless of work-package count.
- [ ] `ruff` and `mypy` clean, no new suppressions.

## Risks

- **Authoring a fourth implementation.** Adding the entry point without collapsing the existing two
  is the failure mode this WP exists to prevent. Check the final state: one body, two wrappers.
- **Silent behaviour drift.** Both implementations agree today. If your refactor changes a finding,
  you have introduced a bug into a merge-blocking path — the most expensive place to be wrong.
- **Ownership**: `agent_utils/status.py` (WP01), `tasks_*` (WP02/WP03) are not yours.

## Reviewer guidance

1. Count `ReviewOverride.from_dict` call sites in `src/` after the change — the `wp_review` body
   should be the only override-resolution one (`status/models.py` is the model's own definition).
2. Confirm `_snapshot_review_override` is gone, not wrapped.
3. Confirm the existing `post_merge` test file has **zero diff** — an edited assertion means
   behaviour changed.
4. Confirm the new entry point does no I/O and no reduction.

## Activity Log

| At | Actor | Action |
|----|-------|--------|
| 2026-07-25T00:54:24Z | system | Prompt generated via /spec-kitty.tasks |
