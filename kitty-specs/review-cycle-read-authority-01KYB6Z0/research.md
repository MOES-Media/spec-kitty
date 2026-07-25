# Research: Review-Cycle Read Authority

**Mission**: `review-cycle-read-authority-01KYB6Z0`
**Date**: 2026-07-25
**Verified against**: `fix/review-cycle-read-authority` @ `main` ancestor `721165a22`

Phase 0 resolves the two questions the spec deferred: (1) the disposition of every site that
reaches for review-cycle records (FR-005), and (2) how a caller obtains the override.

---

## Decision 1 — Site disposition under the In-Scope Rule

**Decision**: three consumer sites are in scope, via two override-blind functions. Seven sites are
excluded.

**Rationale**: the In-Scope Rule asks whether a site *derives a verdict*. Enumerated from source
(`grep -rn 'glob("review-cycle-\*\.md")' src/`), ten live sites exist.

| # | Site | What it actually does | Disposition |
|---|------|----------------------|-------------|
| 1 | `review/artifacts.py:320` `latest_review_artifact_verdict` | Selects latest record **and** applies the override | **CANONICAL — the survivor** |
| 2 | `agent_utils/status.py:49` `_get_wp_review_verdict` | Derives a verdict for the stale-verdict warning | **IN SCOPE** |
| 3 | `tasks_parsing_validation.py:301` `_get_latest_review_cycle_verdict` | Derives a verdict; two consumers (below) | **IN SCOPE** |
| 4 | `review/artifacts.py:277` `ReviewCycleArtifact.latest` | Selects the latest record to source *feedback text*; sole caller is fix-mode prompt generation | **EXCLUDED** — see Decision 2 |
| 5 | `review/artifacts.py:294` `next_cycle_number` | Counts records | EXCLUDED (C-005) |
| 6 | `workflow.py:1709` | Counts records to compute the next cycle number | EXCLUDED (C-005) |
| 7 | `workflow_cores.py:387` | Existence probe (`has_prior_rejection`) — never reads a verdict | EXCLUDED |
| 8 | `post_merge/review_artifact_consistency.py:138` `_latest_review_artifact_path` | Resolves a path for a schema-error message; gated *ahead* of the real canonical call at `:189` | EXCLUDED |
| 9 | `review/arbiter.py:390` | Finds a record path | EXCLUDED (see Observation A) |
| 10 | `review/arbiter.py:536` | Iterates every record collecting `arbiter_override` blocks | EXCLUDED (C-005) |

The three in-scope **consumer** sites, reached through functions 2 and 3:

- `agent_utils/status.py:273` — stale-verdict warning on the status board (display).
- `tasks_parsing_validation.py:371` — stale-verdict warning in tasks status (display).
- `tasks_move_task.py:552` — feeds `MoveTaskRequest.review_verdict`, consumed by
  `tasks_transition_core._guard_rejected_verdict:364` (**lifecycle transition guard**).

**Alternatives considered**: a location-scoped rule ("everything outside `review/artifacts.py`
consolidates"). Rejected — it simultaneously fails on legitimately excluded sites (6, 10) and
passes site 4, which lives inside the canonical module. This is the defect the post-spec gate
caught in the second-pass SC-004.

---

## Decision 2 — `ReviewCycleArtifact.latest()` stays as-is

**Decision**: excluded, with a stated reason (the branch the In-Scope Rule anticipated).

**Rationale**: its sole caller is `workflow_executor.py:1106`, which picks *which review-cycle
file's feedback text* drives the fix-mode prompt. That is document selection, not verdict
derivation. An approval override does not change which rejection the implementer should read and
address — the feedback is still the feedback. Routing this through the override-aware read would
be wrong: it would suppress the feedback exactly when an override exists.

Its dataclass carries a `verdict` field, which is what makes it *look* like a verdict reader. The
field is incidental to this call path.

**Risk accepted**: it remains a latent verdict-bearing reader. If a future caller uses it to decide
approval state, the defect class returns. Mitigation is FR-005's recorded disposition plus a
docstring stating the function selects a document and must not be used to decide approval state.

**Alternatives considered**: routing it through the canonical read (rejected — suppresses feedback
under override); deleting it (rejected — the fix-mode path legitimately needs it).

---

## Decision 3 — The override is already in hand at every in-scope site

**Decision**: reuse `_snapshot_review_override`'s pattern — read the reduced snapshot's `review`
slot into a `ReviewOverride`, pass it as `snapshot_override=` to the canonical read.

**Rationale**: this is the proven third-leg pattern
(`post_merge/review_artifact_consistency.py:112-129`), already the single authority for override
recognition per its FR-009. Critically, **no new I/O is required at the in-scope sites**:

- `agent_utils/status.py:165-168` already does `events = read_events(feature_dir); snapshot =
  reduce(events)` and iterates `snapshot.work_packages.items()` at `:171`. The per-WP `state`
  mapping is exactly what `_snapshot_review_override` consumes. NFR-002 is satisfied by
  construction — the snapshot is already reduced exactly once per mission.
- `tasks_parsing_validation.py:371` sits in a loop that already holds per-WP state and already has
  `events` in scope (`_latest_status_event_time(events, wp_id)` at `:383`).
- `tasks_move_task.py:552` runs after the event-log read (the comment at `:545` says so
  explicitly).

**Alternatives considered**: re-parsing artifact frontmatter for the override (rejected — the
canonical read already keeps that only as a migration-window fallback, and adding a second
frontmatter parse would reintroduce the dual-authority the FR-009 work retired); a fresh
`read_events` per call site (rejected — violates NFR-002 and duplicates work already done).

---

## Decision 4 — The transition guard is corrected by input, not by rule change

**Decision**: `_guard_rejected_verdict` keeps every arm exactly as written. Only the value of
`MoveTaskRequest.review_verdict` reaching it becomes override-aware.

**Rationale**: C-002 permits a guard to behave differently *because it is given the correct
verdict*, never because its condition was rewritten. Today the guard refuses `approved`/`done`
whenever the record reads `rejected`, with no knowledge of a recorded override — so an operator who
already supplied `--skip-review-artifact-check --note` to reach `approved` is refused again on
`approved → done` and must re-assert the same override. The refusal is correct given its input; the
input is wrong.

**Consequence to pin with tests**: `_authorize_review_override:390` returns True only when
`review_verdict == "rejected"` **and** the skip flag **and** a note are present. If the verdict
becomes override-aware, that arm stops firing for an already-overridden WP — which is the intent
(no re-assertion), but it also means the "persist override evidence" side effect no longer
re-fires. Tests must prove the *original* override evidence remains durable and that a
**first** override still records evidence normally.

**Alternatives considered**: teaching the guard to consult the override directly (rejected —
rewrites the guard's condition, violating C-002, and creates a second override-recognition site).

---

## Observation A — latent defect, deliberately not folded

`review/arbiter.py:388-391` reads:

```python
for candidate in sorted(wp_subdir.glob("review-cycle-*.md")):
    return candidate  # Return the most recently created one
```

`sorted()` is lexical and the loop returns the **first** element, so this yields
`review-cycle-1.md` — the *oldest* record — while the comment claims the most recent. It is also
lexical rather than numeric, so `review-cycle-10.md` sorts before `review-cycle-2.md`.

**Not folded into this mission.** It is path-finding, not verdict derivation, so the In-Scope Rule
excludes it; folding it would widen the mission into the arbiter subsystem for a defect with no
established symptom. Recorded here so the next reader does not have to rediscover it, and flagged
for an explicit operator decision rather than silently carried or silently dropped.

---

## Resolved unknowns

| Unknown from spec | Resolution |
|---|---|
| Which sites are in scope (FR-005) | Decision 1 — 3 consumers via 2 functions; 7 excluded |
| `ReviewCycleArtifact.latest()` disposition | Decision 2 — excluded, reason recorded |
| How callers obtain the override | Decision 3 — reduced-snapshot `review` slot, already in hand |
| Whether the guard's rules change | Decision 4 — no; only its input |
| Malformed cycle number semantics | Pinned in spec: ranks as cycle zero, stays a candidate |
