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

**Rationale**: the In-Scope Rule asks whether a site *derives a verdict*. Enumeration needs **two
passes**, because a verdict has two inputs and each is found by a different query.

> **Methodology correction (post-plan gate).** The first version used only
> `grep -rn 'glob("review-cycle-\*\.md")' src/`. That finds *record* readers and is structurally
> blind to *override* readers, which contain no glob. A duplicate on a merge-blocking path was
> therefore missing from the table entirely — and SC-004's "exactly one implementation remains"
> would have been true only by omission. Both passes are now required, and IC-04's floor covers
> both.

**Pass 1 — record readers**: `grep -rn 'glob("review-cycle-\*\.md")' src/` → ten live sites.

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

**Pass 2 — override readers**: `grep -rn 'ReviewOverride.from_dict|get("review")' src/`.

| # | Site | What it actually does | Disposition |
|---|------|----------------------|-------------|
| 11 | `status/wp_review.py` `resolve_event_stream_review` / `resolve_snapshot_review` | The declared canonical interpretation of the `review` slot | **CANONICAL — the survivor** |
| 12 | `post_merge/review_artifact_consistency.py:112-127` `_snapshot_review_override` | Re-implements `.get("review")` + `ReviewOverride.from_dict` independently; feeds `rejected_review_artifact_for_terminal_lane:189` inside `find_rejected_review_artifact_conflicts` — a **merge-blocking** verdict | **IN SCOPE** — see Decision 6 |
| 13 | `status/wp_view.py:173` | Projects the raw slot mapping into a display group; no `ReviewOverride` construction, no completeness rule, no verdict | EXCLUDED — projection, not resolution |
| 14 | `status/models.py:553-554` | `from_dict` on the model itself — the definition, not a reader | EXCLUDED |

| 15 | `status/validate.py:201` | Validates the **done-evidence** `review` block (`reviewer`/`verdict`/`reference`) | EXCLUDED — different `review` key |
| 16 | `status/emit.py:287` | Reads the same done-evidence `review` block when building done evidence | EXCLUDED — different `review` key |

(`retrospective/generator.py:285`, `migration/mission_state.py:900`, and
`tasks_move_task.py:871` also match the query but read unrelated `review` keys — evidence blocks,
migration payloads, and a config section respectively. Not review overrides.)

> **Two `review` keys exist and must not be conflated.** The *override* slot (`ReviewOverride`:
> `at`/`actor`/`wp_id`/`reason`) lives on the reduced per-WP snapshot. The *done-evidence* block
> (`reviewer`/`verdict`/`reference`) lives on a done transition's evidence payload. Rows 15-16 are
> the latter. WP05's gate must distinguish them by shape, or it will flag done-evidence readers as
> unclassified override readers forever.

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

**Reduce once, then index (NFR-002).**

> **Corrected after the post-tasks gate.** An earlier version of this paragraph said "read the
> stream once per mission, then call `resolve_event_stream_review(event_stream, wp_id)` per work
> package". That is **also** a per-WP reduction. See Decision 8.

`resolve_event_stream_review`'s body is
`reduce(event_stream.transitions, event_stream.annotations).work_packages.get(wp_id)` — a full
reduction **on every call**, with no memoization anywhere in `specify_cli/status/`.
`resolve_snapshot_review` merely adds disk I/O on top of the same cost. Neither is safe inside a
per-WP loop.

**The rule**: reduce the annotation-aware stream **exactly once per invocation** into a snapshot,
then look up each work package's `review` slot from that single snapshot. This is precisely the
shape WP04/IC-05 builds for `post_merge` — so the display surfaces adopt that same entry point
rather than inventing a parallel one.

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

## Decision 6 — `_snapshot_review_override` is consolidated, not exempted

**Decision**: route `post_merge/review_artifact_consistency.py`'s `_snapshot_review_override` onto
the canonical `wp_review` seam. This requires adding a snapshot-taking entry point to `wp_review`
(see below) and is scoped as IC-05.

**Rationale**: its own docstring calls it *"the third leg of the both-halves pair"* — it knows it is
a third implementation. It feeds a merge-blocking verdict, so it is squarely a verdict-deriving
site under the In-Scope Rule. Leaving it would make SC-004's "exactly one implementation remains"
true only because the enumeration could not see it. C-003 says route callers onto the canonical
read rather than author a second — that obligation applies to the duplicate that already exists,
not only to the three new callers.

**Not a live defect**: it reads state from `materialize(feature_dir)` (`:168`), which *is*
annotation-aware, so it currently agrees with the canonical seam. This is drift risk and a false
SC-004 claim, not a wrong verdict shipping today. Severity is "must not claim otherwise", not
"users are broken".

**Implementation wrinkle to hand to tasks**: `wp_review` exposes
`resolve_event_stream_review(event_stream, wp_id)` and `resolve_snapshot_review(feature_dir,
wp_id)`. The post-merge call site holds an **already-materialized snapshot** inside a per-WP loop,
so neither fits: the stream variant needs a stream it does not have, and the `feature_dir` variant
re-reduces per call, breaching NFR-002. The consolidation therefore adds a third entry point
(resolve from an already-materialized snapshot) that the other two delegate to. That is a genuine
addition to the canonical module, and it must not become a fourth parallel implementation.

**Rejected alternative**: recording an exemption (the Decision 2 route). Rejected because, unlike
`ReviewCycleArtifact.latest()`, this site *is* deriving an approval verdict — there is no
behavioural reason it should read the slot differently from every other consumer.

## Decision 7 — IC-03's read joins the existing transactional boundary

**Decision**: `tasks_move_task.py` obtains its stream via `read_event_stream_transactional`
(`coordination/status_transition.py:1109`) — the same primitive the merge gate uses
(`merge/done_bookkeeping.py:290`).

**Rationale**: IC-03's risk note requires the override read to sit in the same transactional
boundary as the lane read, so the guard cannot decide on a torn view. Naming the existing primitive
prevents an implementer from reaching for a plain `read_event_stream` and reintroducing the torn
read the transactional variant exists to prevent, or from authoring a bespoke locking wrapper.

## Decision 8 — Both display surfaces adopt IC-05's snapshot entry point

**Decision**: WP01 and WP02 depend on WP04 and use its already-materialized-snapshot entry point.
They do **not** call `resolve_event_stream_review` per work package.

**Rationale**: both surfaces iterate every work package —
`agent_utils/status.py:266-273` and `tasks_parsing_validation.py:362-380`. Calling a re-reducing
helper inside those loops costs N reductions for an N-work-package mission, which NFR-002 forbids
in as many words ("for a 20-work-package mission the log is read and reduced exactly once, not
twenty times"). The distinction the earlier draft drew between the two `wp_review` entry points was
false: `resolve_snapshot_review` re-reduces **and** re-reads; `resolve_event_stream_review`
re-reduces. Only a snapshot-indexing lookup is O(1) per work package.

**Consequence for sequencing**: IC-05 stops being an independent anti-drift chore and becomes the
**foundation** for IC-01 and IC-02. Wave 1 is WP04 alone.

**Consequence for verification**: the acceptance check must assert the **reduction count**, not the
read count. The original quickstart check ("no `resolve_snapshot_review` inside a per-WP loop, one
stream read") would have passed an implementation that reduced N times — a vacuous gate for the
NFR it claimed to enforce. Both WP01 and WP02 now require a spy on `reduce` asserting exactly one
call.

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
