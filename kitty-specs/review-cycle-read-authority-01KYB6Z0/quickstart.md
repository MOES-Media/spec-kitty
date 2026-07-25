# Quickstart: Review-Cycle Read Authority

**Mission**: `review-cycle-read-authority-01KYB6Z0`
**Date**: 2026-07-25

How to reproduce the defect and verify the fix. Written so a reviewer can check the claims without
re-deriving the analysis.

## The shape of the bug in one paragraph

A rejection produces a file. An approval that supersedes it produces an **event**, not a file. Any
reader that looks only at files sees the rejection forever. The merge gate combines both and gets
it right; three other readers do not.

## Reproduce — before the fix

Both scenarios need a work package with a rejection record plus a complete approval override.

### A. Status board reports a stale rejection (FR-001)

```bash
spec-kitty agent tasks status --mission <handle>
```

**Expected before**: the work package is flagged with a rejected review artifact even though it is
`approved`.
**Expected after**: no stale-verdict warning.

### B. Transition guard demands a second override (FR-007)

```bash
# The WP reached `approved` earlier via an explicit arbiter override.
spec-kitty agent tasks move-task <WP> --to done
```

**Expected before**: refused — *"has a rejected review artifact … re-run with
`--skip-review-artifact-check --note <reason>`"* — even though that override was already recorded.
**Expected after**: permitted, with no override flags re-supplied.

## Verify the fix

### Every consumer agrees (INV-2, SC-002)

For the same work package, these must report the same verdict:

```bash
spec-kitty agent tasks status --mission <handle>     # status board
spec-kitty agent tasks status --mission <handle>     # tasks-status stale-verdict warning
spec-kitty agent tasks move-task <WP> --to done      # transition guard
```

### Refusal arms still fire (FR-003, SC-005)

The fix must remove a false positive without creating a false negative. All three must still
refuse:

| Case | Expected |
|------|----------|
| Rejection, **no** override | Refused |
| Rejection, override missing any of `at`/`actor`/`wp_id`/`reason` | Refused |
| Record whose verdict is unparseable | Refused with the "no parseable review verdict" message |

### Override durability (INV-6)

- A **first** arbiter override still records evidence normally.
- Evidence recorded by an earlier override is not erased when the verdict becomes override-aware.

### Disposition stays pinned (FR-005, SC-004)

```bash
pytest tests/architectural/ -k review_cycle -q
```

Must fail when a new review-cycle reader is added without a recorded disposition. Confirm it is
non-vacuous by temporarily adding an unclassified reader and observing a red test.

## Performance check (NFR-002)

No in-scope site may add an event-log read or snapshot reduction — the reduced snapshot is already
in hand at all three. Verify by inspection: no new `read_events` or `reduce` call appears in the
diff at `agent_utils/status.py`, `tasks_parsing_validation.py`, or `tasks_move_task.py`.

## Boundaries — what must NOT change

- No artifact changes partition (C-001). Review-cycle records stay PRIMARY. A diff that moves them
  reverses `coord-commit-integrity-01KY5JS8` and must be rejected.
- No guard condition is rewritten (C-002). `_guard_rejected_verdict`'s arms are pinned by tests,
  not edited.
- `ReviewCycleArtifact.latest()` stays override-blind on purpose — it selects a feedback document
  for the fix prompt, not a verdict. Routing it through the override-aware read would suppress
  reviewer feedback exactly when an override exists (research.md, Decision 2).
