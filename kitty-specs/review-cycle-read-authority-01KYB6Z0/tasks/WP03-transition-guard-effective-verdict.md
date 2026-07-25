---
work_package_id: WP03
title: Transition guard receives the effective verdict
dependencies:
- WP02
requirement_refs:
- FR-002
- FR-003
- FR-007
planning_base_branch: fix/review-cycle-read-authority
merge_target_branch: fix/review-cycle-read-authority
branch_strategy: Planning artifacts for this mission were generated on fix/review-cycle-read-authority. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into fix/review-cycle-read-authority unless the human explicitly redirects the landing branch.
subtasks:
- T011
- T012
- T013
- T014
- T015
- T016
phase: Phase 2 - Lifecycle correction
history:
- at: '2026-07-25T00:54:24Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/specify_cli/cli/commands/agent/tasks_move_task.py
create_intent:
- tests/specify_cli/cli/commands/agent/test_move_task_override_guard.py
execution_mode: code_change
model: claude-sonnet-5
owned_files:
- src/specify_cli/cli/commands/agent/tasks_move_task.py
- tests/specify_cli/cli/commands/agent/test_move_task_override_guard.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP03 – Transition guard receives the effective verdict

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the frontmatter (or any
user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
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

An operator who already recorded an arbiter override must not be forced to re-assert it when the
work package advances. Correct the verdict handed to the transition guard — **without editing the
guard**.

> **This is the highest-risk work package in the mission.** It changes when a lifecycle transition
> is refused. Read the constraint section before writing anything.

## Branch Strategy

- **Planning base branch**: `fix/review-cycle-read-authority`
- **Final merge target**: `fix/review-cycle-read-authority`
- Execution happens in the worktree allocated for this WP's computed lane (`lanes.json`).

## Implementation command

```bash
spec-kitty agent action implement WP03 --agent claude
```

Depends on **WP02** — it must be `approved` or `done` before this WP can be claimed.

## The defect, precisely

1. `tasks_move_task.py:548-556`: when the target lane is `approved` or `done`, the code calls
   `_get_latest_review_cycle_verdict` and stores the result in `MoveTaskRequest.review_verdict`.
2. `tasks_transition_core._guard_rejected_verdict:364-388` **refuses** the transition when that
   verdict reads `rejected`, unless `--skip-review-artifact-check` **and** `--note` are supplied.
3. Supplying those flags is exactly what *creates* the override
   (`_authorize_review_override:390-399`).
4. The guard never consults an existing override. So a work package already override-approved is
   refused **again** on `approved → done`, and the operator must re-assert an override that is
   already durably recorded and that the merge gate already honours.

The guard is correct given its input. **The input is wrong.**

## ⛔ Binding constraints

- **C-002 — do not edit the guard.** `tasks_transition_core.py` is **read and pinned by tests,
  never modified**. Its arms may behave differently only because they are given the correct
  verdict. Rewriting a condition — or adding a `review_has_override` field for the guard to
  consult — violates C-002 and creates a second override-recognition site (DIRECTIVE_044).
  `tasks_transition_core.py` is deliberately **not** in this WP's `owned_files`.
- **C-004 — inherit override semantics.** `ReviewOverride.complete` is the only completeness
  authority. Do not re-implement the predicate.

## The effective-verdict projection (research.md Decision 5)

The resolution rule is two-valued (`verdict`, `has_override`) but `MoveTaskRequest.review_verdict`
is a single `str | None`. Getting the projection wrong hits a *different* refusal arm instead of
failing loudly:

- passing `None` when an override exists trips *"no parseable review verdict"* (`:372`) — **worse
  than today**;
- passing `"rejected"` trips the rejected-verdict arm — no change, defect persists.

**Required projection**:

```
effective_verdict :=
    None          if no record exists or its verdict is unparseable
    "approved"    if verdict == "rejected" and has_override
    verdict       otherwise
```

`review_artifact_name` keeps naming the underlying record so diagnostics do not lose the file
reference.

---

## Subtasks

### T011 — Red-first: prove the double-assertion defect

**Purpose**: NFR-004 / ADR `2026-07-17-1`.

**Steps**:

1. Create `tests/specify_cli/cli/commands/agent/test_move_task_override_guard.py`.
2. Fixture: a work package in lane `approved`, with a rejected `review-cycle-1.md` and a **real
   event log** carrying a complete `ReviewOverride`.
3. Attempt `move-task <WP> --to done` with **no** override flags.
4. Assert it is currently refused with the rejected-review-artifact message.

**Fixture rule**: drive the override through the event log. A hand-built state mapping proves
nothing.

**Validation**: red before T012/T013, green after T014.

---

### T012 — Add the transactional stream read inside the existing boundary

**Purpose**: Obtain the override without letting the guard decide on a torn view of the log.

**Steps**:

1. `tasks_move_task.py` has **no snapshot or stream read today** — zero `reduce(` calls. Its
   `read_events_transactional` uses at `:1734`/`:2411` are lane determination and are unrelated.
   You are **adding** a read, not switching one.
2. Use `read_event_stream_transactional` (`coordination/status_transition.py:1109`) — the same
   primitive the merge gate uses (`merge/done_bookkeeping.py:290`). A plain `read_event_stream`
   reintroduces the torn read the transactional variant exists to prevent. Do not author a bespoke
   locking wrapper.
3. Place it inside the same transactional boundary as the lane-determination read, so lane and
   override are decided from one consistent view.
4. Read once per invocation (NFR-002).

**Files**: `src/specify_cli/cli/commands/agent/tasks_move_task.py`

**Validation**: no second read introduced; the override and the lane come from the same view.

---

### T013 — Implement the effective-verdict projection

**Purpose**: Bridge the two-valued rule onto the guard's one-field contract.

**Steps**:

1. Resolve the override via `wp_review.resolve_event_stream_review(event_stream, wp_id)` using the
   stream from T012.

   > **Why this is permitted here when WP01/WP02 forbid it.** That prohibition is **loop-scoped**:
   > those surfaces iterate every work package, so a per-call re-reduction costs N reductions.
   > `move-task` operates on exactly **one** work package, so a single call is a single reduction
   > and satisfies NFR-002. Do **not** "fix" this for consistency with WP01/WP02, and reviewers
   > must not reject it on that basis.
2. Adopt WP02's optional override parameter on `_get_latest_review_cycle_verdict` so the record
   verdict and `has_override` are both available.
3. Apply the projection above when populating `MoveTaskRequest.review_verdict` at `:550-556`.
4. Leave `review_artifact_name` unchanged — it must still name the record.

**Files**: `src/specify_cli/cli/commands/agent/tasks_move_task.py`

**Validation**: `tasks_transition_core.py` shows **zero diff**.

---

### T014 — Prove the guard permits under a complete override

**Purpose**: FR-007.

**Steps**: with the T011 fixture, assert `move-task <WP> --to done` **succeeds** with no override
flags re-supplied, and that the transition is recorded normally.

**Validation**: T011 flips green.

---

### T015 — Prove all three refusal arms are intact

**Purpose**: FR-003 — no false negative. This is the safety net for the whole work package.

**Steps**: assert refusal still occurs for:

1. **No override** — rejection with nothing recorded → refused with the unchanged message.
2. **Incomplete override** — parameterise over each of `at`, `actor`, `wp_id`, `reason` missing;
   all four must refuse.
3. **Unparseable verdict** — record present, verdict absent or malformed → the existing *"no
   parseable review verdict"* refusal, unchanged.

Also assert the guard remains inert for non-approval target lanes.

**Validation**: six or more refusal cases, each asserting the **existing** message text.

---

### T016 — Prove override-evidence durability

**Purpose**: INV-6. A subtle consequence that must not regress silently.

**Context**: `_authorize_review_override:390-399` fires only when `review_verdict == "rejected"`
**and** the skip flag **and** a note are present. Once the verdict becomes override-aware, that arm
**stops firing for an already-overridden work package** — which is the intent (no re-assertion),
but it also means its "persist override evidence" side effect no longer re-runs.

**Steps**:

1. Assert a **first** arbiter override (rejection, no prior override, flags supplied) still records
   its evidence exactly as today.
2. Assert evidence recorded by that first override is **still present and unchanged** after a
   subsequent `approved → done` move that no longer re-triggers the arm.
3. Assert no duplicate override evidence is written on the second move.

**Validation**: the first-override path is untouched; prior evidence is durable.

---

## Definition of Done

- [ ] T011's test was committed red before the fix (verifiable in history).
- [ ] `tasks_transition_core.py` shows **zero diff**.
- [ ] The override is read via `read_event_stream_transactional`, inside the lane read's boundary.
- [ ] The effective-verdict projection matches `../data-model.md` exactly.
- [ ] Permit case green; all three refusal arms proven intact with unchanged messages.
- [ ] First-override evidence still records; prior evidence durable; no duplicates.
- [ ] Every override test drives the override through a **real event log**.
- [ ] `ruff` and `mypy` clean, no new suppressions.

## Risks

- **Editing the guard.** The tempting fix is to teach `_guard_rejected_verdict` about overrides.
  That violates C-002 and creates a second recognition site. Correct the input instead.
- **Projection error trips a different arm.** Returning `None` under an override converts one
  refusal into another — and a careless test asserting "refused" would still pass. Assert the
  **specific message**, not merely that it refused.
- **Torn read.** A non-transactional stream read can decide the override from a different view than
  the lane, producing decisions that are individually valid and jointly wrong.
- **Ownership**: `tasks_parsing_validation.py` belongs to WP02. Adopt its optional parameter; do
  not edit it.

## Reviewer guidance

1. **`git diff` `tasks_transition_core.py` — must be empty.** Non-empty is an automatic reject.
2. Confirm `read_event_stream_transactional` is used, not a plain stream read.
3. Confirm the projection matches the data model, especially the unparseable → `None` case.
4. Check refusal tests assert **message text**, not just failure.
5. Check the durability tests distinguish first-override from subsequent-move.
6. Verify red-first ordering in history.

## Activity Log

| At | Actor | Action |
|----|-------|--------|
| 2026-07-25T00:54:24Z | system | Prompt generated via /spec-kitty.tasks |
