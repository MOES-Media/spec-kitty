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

Each surface reads and reduces the event log **once per invocation**, never once per work package.

Verify by inspection of the diff:

- `agent_utils/status.py` and `tasks_status_cmd.py` **do** change their event read — from
  `read_events`/`reduce(events)` to the annotation-aware stream. That switch is required, not a
  regression.
- `tasks_move_task.py` **gains** a stream read; it has none today.
- **Assert the reduction count, not the read count.** Spy on
  `specify_cli.status.reducer.reduce` and require exactly **one** call for a multi-work-package
  mission. Both `resolve_snapshot_review` *and* `resolve_event_stream_review` re-reduce the whole
  stream on every call, so a per-WP lookup through either one reduces N times while passing any
  check that merely counts reads or greps for a banned name. Per-WP lookups must index the single
  already-reduced snapshot.

## The trap this mission nearly fell into

A change can thread the *existing* `reduce(events)` snapshot into the verdict call, type-check
cleanly, and pass unit tests that build state mappings by hand — while returning `None` for every
real override, because `read_events` partitions annotations out and `reduce(events)` leaves the
`review` slot unpopulated.

**Therefore**: every regression test for FR-001 and FR-007 must be built from a **real event log
containing an `InnerStateChanged` review annotation**. A test that hand-constructs the snapshot
state proves nothing about production behaviour. Reviewers should reject any override test whose
fixture does not go through the event log.

## Boundaries — what must NOT change

- No artifact changes partition (C-001). Review-cycle records stay PRIMARY. A diff that moves them
  reverses `coord-commit-integrity-01KY5JS8` and must be rejected.
- No guard condition is rewritten (C-002). `_guard_rejected_verdict`'s arms are pinned by tests,
  not edited.
- `ReviewCycleArtifact.latest()` stays override-blind on purpose — it selects a feedback document
  for the fix prompt, not a verdict. Routing it through the override-aware read would suppress
  reviewer feedback exactly when an override exists (research.md, Decision 2).
