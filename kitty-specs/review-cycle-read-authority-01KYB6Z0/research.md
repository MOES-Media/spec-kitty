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

## Decision 3 — Use the annotation-aware stream and the canonical `wp_review` seam

> **Corrected after the post-plan gate.** The first version of this decision claimed the override
> was "already in hand" at all three sites because they call `reduce(events)`. That was **false**
> and would have shipped a no-op. See Correction Note below.

**Decision**: each in-scope site resolves the override via
`specify_cli.status.wp_review.resolve_event_stream_review(event_stream, wp_id)`, backed by **one**
`read_event_stream` per mission. Pass the result as `snapshot_override=` to the canonical verdict
read.

**Rationale**:

1. **`read_events` deliberately discards what we need.** Its docstring
   (`status/store.py:717-722`): *"Backward-compatible transitions-only view (off-axis `annotation`
   events are partitioned out). Use `read_event_stream` when the reducer needs the annotations
   too."*
2. **`reduce(events)` therefore never populates the slot.** `state["review"]` is assigned at
   exactly one place — `reducer.py:201`, inside `_apply_annotation_delta` — which runs only over
   the `annotations` argument. `reduce(events)` defaults it to empty, so the `review` slot is
   *always absent*. Reading it would yield `None` unconditionally.
3. **A canonical seam for exactly this already exists.** `specify_cli/status/wp_review.py`
   (`dfe6b2ead`, 2026-07-21 — an ancestor of the `721165a22` this research verified against)
   declares itself *"the single canonical interpretation of the snapshot `review` slot"*, created
   precisely so the merge gate and the CLI could not diverge. C-003 mandates reusing it.
4. **The merge gate — this mission's reference implementation — already uses it.**
   `merge/done_bookkeeping.py:109-111` calls `resolve_event_stream_review(event_stream, wp_id)`.
   Copying `post_merge`'s private `_snapshot_review_override` instead would author a *third*
   implementation, which is the very drift C-003 and DIRECTIVE_044 forbid.

**Stream-level, not snapshot-level (NFR-002).** `wp_review` exposes two entry points.
`resolve_snapshot_review(feature_dir, wp_id)` re-reduces per call — using it inside a per-WP loop
would reduce once per work package and breach NFR-002. Therefore: read the stream **once per
mission**, then call `resolve_event_stream_review(event_stream, wp_id)` per work package.

**Per-site consequences** (this is real work, not free):

| Site | Today | Required change |
|------|-------|-----------------|
| `agent_utils/status.py:165-168` | `read_events` + `reduce(events)` | Switch to the annotation-aware stream read |
| `tasks_status_cmd.py:279-283` | `_st_read_events` + `_st_reduce` — **the real construction site** feeding `_apply_review_status_flags:349` | Same switch; thread the override into the verdict call |
| `tasks_move_task.py:552` | **No snapshot or stream read exists in this file at all** (zero `reduce(` calls) | Add a stream read + override lookup |

**Alternatives considered**: re-parsing artifact frontmatter (rejected — the canonical read keeps
that only as a migration-window fallback; a second parse reinstates the dual authority the FR-009
work retired); copying `_snapshot_review_override` (rejected — a third implementation, violates
C-003); `resolve_snapshot_review` per WP (rejected — breaches NFR-002).

### Correction Note

The original Decision 3 asserted "no new I/O is required" and cited `status.py:165-168` as already
holding the override. The post-plan adversarial gate refuted it against live code. The failure mode
mattered: the proposed change would have type-checked, passed unit tests using hand-built state
mappings, and silently returned `None` for every override in production — shipping a fix that
closes nothing. Recorded rather than quietly overwritten, because the *shape* of the error (reading
a slot that a chosen primitive never fills) is the reusable lesson.

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

## Decision 5 — The guard's one-field contract carries the *effective* verdict

**Problem**: the resolution rule is two-valued (`verdict`, `has_override`), but
`MoveTaskRequest.review_verdict` is a single `str | None`. The two do not compose without an
explicit mapping, and getting it wrong hits a refusal arm:

- passing `None` when an override exists trips the *"no parseable review verdict"* refusal
  (`tasks_transition_core.py:372`) — worse than today;
- passing `"rejected"` trips the rejected-verdict refusal — no change, defect persists.

**Decision**: `MoveTaskRequest.review_verdict` carries the **effective** verdict — the record's
verdict unless a complete override supersedes it, in which case it carries the override's resulting
state (`approved`). `review_artifact_name` continues to carry the record's filename so diagnostics
still name the underlying artifact.

**Rationale**: this satisfies C-002 exactly. The guard's arms are untouched; the value handed to
them stops being a half-truth. An override is a recorded approval decision, so reporting the
effective verdict as approved is not fabrication — it is the decision the operator already made and
that the merge gate already honours.

**Rejected alternative**: adding a `review_has_override` field for the guard to consult. That
rewrites the guard's condition, violating C-002, and creates a second override-recognition site —
the drift DIRECTIVE_044 forbids.

**Must be pinned by tests** (tasks to encode): the effective-verdict mapping is the load-bearing
contract of IC-03. Required cases — override present ⇒ effective `approved`, guard permits; no
override ⇒ effective `rejected`, guard refuses; incomplete override ⇒ effective `rejected`, guard
refuses; unparseable record ⇒ `None`, existing refusal unchanged.

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
| How callers obtain the override | Decision 3 — `wp_review.resolve_event_stream_review`, one `read_event_stream` per mission |
| Whether the guard's rules change | Decision 4 — no; only its input |
| Malformed cycle number semantics | Pinned in spec: ranks as cycle zero, stays a candidate |

## Observation B — the annotation-blind read is a wider pattern

Two independent surfaces (`agent_utils/status.py`, `tasks_status_cmd.py`) build a snapshot with
`read_events` + `reduce(events)` and then consult per-WP state. Any off-axis
`InnerStateChanged`-sourced slot — not only `review` — is invisible to both. This mission corrects
only the `review` slot on its in-scope path.

Whether other slots are being read the same blind way is **not investigated here** and is not
claimed either way. Flagged so a future reader knows the question is open rather than answered.
