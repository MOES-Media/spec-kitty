# Data Model: Review-Cycle Read Authority

**Mission**: `review-cycle-read-authority-01KYB6Z0`
**Date**: 2026-07-25

No schema changes. This mission adds no entity, field, or storage location. It corrects which
existing entities are combined when answering one question. This document records the entities and
the resolution rule so the correctness conditions are explicit.

## Entities

### Review-cycle record

| Property | Value |
|----------|-------|
| Physical form | `review-cycle-<N>.md` with YAML frontmatter |
| Home | PRIMARY partition, `tasks/<wp>/` — **fixed by C-001, must not move** |
| Written when | A reviewer **rejects**. Never on approval (`validate_review_artifact` requires `verdict == "rejected"`) |
| Key fields | `cycle_number`, `verdict`, `reviewer_agent`, `reviewed_at`, `body` |
| Ordering | By parsed integer suffix. An unparseable suffix ranks as **0** and stays a candidate |

### Approval override

| Property | Value |
|----------|-------|
| Physical form | The `review` slot of the reduced per-WP snapshot, from the append-only event log |
| Home | Event log — the lifecycle authority |
| Written when | An operator supplies the arbiter-override flags with a durable reason |
| Required fields | `at`, `actor`, `wp_id`, `reason` — **all four** required for completeness |
| Completeness | `ReviewOverride.complete`. An incomplete override is honoured **nowhere** (C-004) |
| Legacy fallback | Artifact frontmatter (`has_complete_override`) retained only for the migration window; snapshot-first, not dual |

### Current review verdict (derived)

Not stored. Computed by combining the two above. This derivation is the thing that must have
exactly one implementation.

| Field | Meaning |
|-------|---------|
| `path` | The record the verdict came from — feeds diagnostics |
| `cycle_number` | Its cycle number |
| `verdict` | The record's raw verdict |
| `has_override` | Whether a complete override supersedes it |

## Resolution rule

```
records := review-cycle-*.md in the work package directory
if records is empty            -> no verdict            (not an error)
latest  := record with the highest parsed cycle number  (unparseable ranks 0)
override := complete override from the snapshot `review` slot, else legacy frontmatter fallback
verdict  := (latest.verdict, has_override = override is present and complete)
```

A consumer treats a work package as carrying a live rejection when
`verdict == "rejected" AND NOT has_override`. Every in-scope consumer must apply exactly this
predicate — that is the whole of the fix.

## State transitions

Unchanged. Recorded to pin what must **not** move (C-002):

| From | To | Guard today | After this mission |
|------|----|-------------|--------------------|
| any | `approved` / `done` | Refuse when record reads `rejected` and no skip flag | **Same rule.** The input verdict now carries `has_override`, so an already-overridden WP is no longer seen as a live rejection |
| any | `approved` / `done` | Refuse when verdict is unparseable | Unchanged |
| any | `approved` / `done` | Refuse when skip flag supplied without a note | Unchanged |
| any | non-approval lanes | Guard does not apply | Unchanged |

The guard's arms are not edited. Only the value handed to them becomes correct.

## Invariants

- **INV-1** — One derivation. Exactly one implementation computes "current review verdict"; every
  in-scope consumer calls it.
- **INV-2** — Agreement. The status board, the tasks-status surface, the transition guard, and the
  merge gate return the same verdict for the same work-package state.
- **INV-3** — Incomplete overrides are inert. An override missing any required field is honoured on
  no leg and by no consumer.
- **INV-4** — Partition stability. No artifact changes home. Reads resolve where writes already
  land.
- **INV-5** — Tolerant degradation. Absent, unreadable, or malformed inputs yield a partial or
  absent verdict, never an exception reaching the operator.
- **INV-6** — Override durability. Correcting the verdict must not erase or invalidate override
  evidence already recorded; a *first* override must still record evidence normally.
