---
work_package_id: WP05
title: Pin the verdict-reader disposition so the class cannot silently return
dependencies:
- WP01
- WP02
- WP03
- WP04
requirement_refs:
- FR-005
planning_base_branch: fix/review-cycle-read-authority
merge_target_branch: fix/review-cycle-read-authority
branch_strategy: Planning artifacts for this mission were generated on fix/review-cycle-read-authority. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into fix/review-cycle-read-authority unless the human explicitly redirects the landing branch.
subtasks:
- T021
- T022
- T023
- T024
phase: Phase 3 - Anti-recurrence
history:
- at: '2026-07-25T00:54:24Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: reviewer-renata
authoritative_surface: tests/architectural/
create_intent:
- tests/architectural/test_review_verdict_disposition.py
execution_mode: code_change
model: claude-sonnet-5
owned_files:
- tests/architectural/test_review_verdict_disposition.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP05 – Pin the verdict-reader disposition

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the frontmatter (or any
user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `reviewer-renata`
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

Make the mission's disposition decision **executable**, so a newly-added reader that derives a
review verdict without honouring the override fails a test instead of passing unnoticed.

## Branch Strategy

- **Planning base branch**: `fix/review-cycle-read-authority`
- **Final merge target**: `fix/review-cycle-read-authority`
- Execution happens in the worktree allocated for this WP's computed lane (`lanes.json`).

## Implementation command

```bash
spec-kitty agent action implement WP05 --agent claude
```

Depends on **WP01, WP02, WP03, WP04** — all must be `approved` or `done`. If you gate before the
in-scope set is retired, you codify the defect as the expected state.

## Context — two failed designs you must not repeat

This gate has already been designed wrong twice. Both attempts were caught by adversarial review;
their failure modes are the specification for what you build.

**Attempt 1 — vague criterion.** *"The number of implementations goes from 4 to 1, verified by a
search of the source tree."* No search method, and the count was wrong. Unfalsifiable.

**Attempt 2 — location-scoped grep.** *"A search of `src/` for review-cycle globbing returns
matches only inside the canonical helper module."* Wrong in **both** directions:

- **Too strict**: legitimately excluded sites live outside the canonical module by design —
  next-cycle-number (`workflow.py:1709`), iterate-all-cycles (`arbiter.py:536`). The check could
  never pass while the spec forbade moving them.
- **Too weak**: `ReviewCycleArtifact.latest()` (`review/artifacts.py:272-285`) is an override-blind,
  verdict-bearing reader that lives *inside* the canonical module. A location grep waves through
  the exact defect class the mission exists to kill.

**And a third blind spot**: the enumeration used only a file-glob query, so
`_snapshot_review_override` — an override reader with no glob — was invisible to it entirely.

**Therefore this gate must be behavioural and two-pass.** See `../research.md` Decision 1 for the
full disposition table and `../spec.md` "In-Scope Rule" for the classification rule.

---

## Subtasks

### T021 — Author the two-pass enumeration

**Purpose**: Find every site that reads **either** input to a verdict.

**Steps**:

1. Create `tests/architectural/test_review_verdict_disposition.py`.
2. **Pass 1 — record readers**: enumerate sites globbing `review-cycle-*.md` under `src/`.
3. **Pass 2 — override readers**: enumerate sites constructing a `ReviewOverride` from a `review`
   slot (`ReviewOverride.from_dict`, `.get("review")`).

   > **Two different `review` keys exist — do not conflate them.** The *override* slot
   > (`at`/`actor`/`wp_id`/`reason`) lives on the reduced per-WP snapshot. The *done-evidence*
   > block (`reviewer`/`verdict`/`reference`) lives on a done transition's evidence payload;
   > `status/validate.py:201` and `status/emit.py:287` read that one. Discriminate by **shape**,
   > not by the key name, or the gate will flag done-evidence readers as unclassified override
   > readers forever. See `../research.md` Decision 1 rows 15-16.
4. Enumerate from source (AST preferred; a well-scoped textual scan is acceptable if the AST route
   proves disproportionate — but justify it in a comment).
5. A single-pass gate is a defect: pass 1 cannot see override readers, pass 2 cannot see record
   readers, and the mission has already lost a duplicate to exactly that gap.

**Validation**: both passes find their known members from `../research.md` Decision 1.

---

### T022 — Encode the disposition table with reasons

**Purpose**: FR-005 — no site's fate decided by an ungoverned search.

**Steps**:

1. Encode each enumerated site with an explicit disposition: `CANONICAL`, `IN_SCOPE` (must route
   through the canonical read), or `EXCLUDED` (with a stated reason).
2. Reasons come from `../research.md` Decision 1 — do not invent new ones. Excluded categories:
   next-cycle-number, existence probe, iterate-all-cycles, path-for-diagnostics, raw projection,
   model definition, feedback-document selection (`ReviewCycleArtifact.latest()`, Decision 2).
3. **Fail on an unclassified site**, not merely on a count change. A new reader with no entry is
   the case this gate exists to catch.
4. The failure message must name the offending site and tell the reader to classify it in
   `research.md`, not to bump a number.

**Validation**: the table matches Decision 1 exactly; every entry carries a reason.

---

### T023 — Prove the gate is non-vacuous

**Purpose**: DIRECTIVE_043 — a gate that cannot fail is not a gate.

**Steps**:

1. Add a self-mutation check: introduce a decoy unclassified verdict-deriving reader (in a fixture
   or via a temporary synthetic input to the enumerator) and assert the gate **fails**.
2. Assert the gate **passes** on the real tree after WP01–WP04 land.
3. Assert it fails for a site classified `IN_SCOPE` that does **not** route through the canonical
   read — i.e. it checks the routing claim, not just the presence of a table row.
4. Do not let the decoy leak into the shipped source tree.

**Validation**: the gate is red for the decoy and green for the real tree. Demonstrate both.

---

### T024 — Set a concrete floor covering both passes

**Purpose**: Prevent vacuous passage if the enumeration silently returns nothing.

**Steps**:

1. Assert a minimum site count for **each** pass independently — a floor on the total would let one
   pass collapse to zero while the other carries the count.
2. Derive floors from the live tree after WP01–WP04, and comment why each number is what it is.
3. If a floor must ever be lowered, require a justification comment — floors shrink only
   deliberately.

**Validation**: breaking either enumerator (returning an empty list) turns the gate red.

---

## Definition of Done

- [ ] Both passes implemented; neither alone can satisfy the gate.
- [ ] Every enumerated site carries a disposition and a reason matching `../research.md` Decision 1.
- [ ] An unclassified site fails the gate with a message naming it.
- [ ] An `IN_SCOPE` site that does not route through the canonical read fails the gate.
- [ ] Non-vacuity demonstrated: decoy → red, real tree → green.
- [ ] Independent floors per pass, each with a rationale comment.
- [ ] No decoy fixture leaks into `src/`.
- [ ] `ruff` and `mypy` clean, no new suppressions.

## Risks

- **Rebuilding the location grep.** The most likely wrong turn. If your gate asks *where* a symbol
  lives rather than *what it does*, you have rebuilt attempt 2.
- **Single-pass enumeration.** Loses override readers, which is how the third duplicate survived.
- **Vacuous floors.** A gate anchored to a total, or to a count the enumerator can silently zero
  out, passes forever after the first refactor.
- **Gating too early.** Landing before WP01–WP04 codifies the defect as expected state.

## Reviewer guidance

1. Ask: *would this gate have caught `ReviewCycleArtifact.latest()`?* It lives inside the canonical
   module and is override-blind. If the gate would pass it, the design has regressed to attempt 2.
2. Ask: *would it have caught `_snapshot_review_override`?* It has no glob. If pass 2 is missing or
   weak, it would not.
3. Verify non-vacuity was actually demonstrated, not merely asserted in a comment.
4. Verify floors are per-pass and justified.
5. Confirm the failure message tells the reader to classify, not to bump a number.

## Activity Log

| At | Actor | Action |
|----|-------|--------|
| 2026-07-25T00:54:24Z | system | Prompt generated via /spec-kitty.tasks |
