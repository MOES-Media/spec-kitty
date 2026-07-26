# Implementation Plan: Review-Cycle Read Authority

**Mission**: `review-cycle-read-authority-01KYB6Z0`
**Branch**: `fix/review-cycle-read-authority` | **Date**: 2026-07-25 | **Spec**: [spec.md](spec.md)
**Target / merge branch**: `fix/review-cycle-read-authority`
**Research**: [research.md](research.md)

## Summary

Three sites derive a review verdict by reading the latest review-cycle record and stopping, never
consulting the event-sourced approval override. Route all three through the existing canonical
override-aware read. No artifact moves partition; no guard rule is rewritten.

## Technical Context

**Language/Version**: Python 3.11+
**Primary Dependencies**: none added — the canonical verdict read
(`specify_cli.review.artifacts.latest_review_artifact_verdict`) and the canonical override seam
(`specify_cli.status.wp_review.resolve_event_stream_review`) both already exist
**Storage**: filesystem — review-cycle Markdown records (PRIMARY partition) plus the append-only
`status.events.jsonl` event log
**Testing**: pytest; red-first regression tests per ADR `2026-07-17-1` (NFR-004), run via
`PWHEADLESS=1 pytest tests/ -n auto --dist loadfile`
**Target Platform**: Linux/macOS/Windows CLI
**Project Type**: single
**Performance Goals**: the annotation-aware event stream is read **once per mission**, then
`resolve_event_stream_review(event_stream, wp_id)` is called per work package (NFR-002). The
per-call `resolve_snapshot_review(feature_dir, wp_id)` must NOT be used inside a per-WP loop — it
re-reduces on every call.

> **Design note (corrected after the post-plan gate).** In-scope sites today call `read_events` +
> `reduce(events)`. `read_events` deliberately partitions annotations out (`status/store.py:717`)
> and `state["review"]` is assigned only inside `_apply_annotation_delta` (`reducer.py:201`), so
> that snapshot's `review` slot is **always empty**. Every in-scope site must move to the
> annotation-aware stream read. This is real work — an earlier draft wrongly assumed the override
> was already in hand and would have shipped a silent no-op.
**Constraints**: no artifact may change partition (C-001); guard conditions unchanged, only their
input corrected (C-002); reuse the canonical read rather than authoring a second (C-003); override
completeness semantics inherited unchanged (C-004)
**Scale/Scope**: 2 override-blind functions retired onto 1 canonical read; 3 consumer sites; 7
enumerated sites explicitly excluded with recorded reasons

## Charter Check

| Principle | Status | Note |
|-----------|--------|------|
| Single canonical authority | ✅ Advances it | Retires two override-blind readers onto the one canonical verdict read (DIRECTIVE_044) |
| Architectural alignment | ✅ | Preserves `coord-commit-integrity-01KY5JS8`'s PRIMARY-partition decision; C-001 forbids reversing it |
| ATDD-first / red-first | ✅ Planned | Every FR gets a failing test before its fix (NFR-004, ADR `2026-07-17-1`) |
| Close defect classes by construction (DIRECTIVE_043) | ⚠️ Partial | Consolidation removes the duplicates, but nothing structurally prevents a *new* override-blind reader. See Complexity Tracking. |
| Terminology adherence | ✅ | Mission/WP canon; no `feature*` aliases introduced |
| Canonical sources | ✅ | Reuses `latest_review_artifact_verdict` (verdict) and `status.wp_review` (override) rather than improvising. **`_snapshot_review_override` is a duplicate to retire (IC-05), not a pattern to imitate** — an earlier draft of this row wrongly cited it as exemplary |

Post-design re-evaluation: no new violations. The one partial is tracked below rather than
silently accepted.

## Project Structure

### Documentation (this mission)

```
kitty-specs/review-cycle-read-authority-01KYB6Z0/
├── spec.md
├── research.md          # Phase 0 — site disposition + override retrieval
├── data-model.md        # Phase 1 — verdict resolution entities
├── quickstart.md        # Phase 1 — how to reproduce and verify
└── checklists/requirements.md
```

### Source Code (repository root)

```
src/specify_cli/
├── review/artifacts.py                        # canonical read (unchanged behaviour; may gain guard docstring)
├── agent_utils/status.py                      # IC-01: retire _get_wp_review_verdict
├── status/wp_review.py                        # canonical override seam — REUSED, not modified
├── cli/commands/agent/
│   ├── tasks_status_cmd.py                    # IC-02: owns the events/snapshot construction (:279-283)
│   ├── tasks_parsing_validation.py            # IC-02: retire _get_latest_review_cycle_verdict
│   ├── tasks_move_task.py                     # IC-03: ADDS a stream read; override-aware verdict
│   └── tasks_transition_core.py               # IC-03: arms unchanged — pinned by tests only
└── post_merge/review_artifact_consistency.py  # reference pattern; not modified

tests/
├── specify_cli/agent_utils/                   # IC-01 regressions
├── specify_cli/cli/commands/agent/            # IC-02, IC-03 regressions
└── architectural/                             # IC-04 non-vacuous disposition gate
```

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| DIRECTIVE_043 only partially satisfied — consolidation without a structural bar to new override-blind readers | A true structural gate ("no function may return a verdict derived from review-cycle records except the canonical read") needs AST analysis of return-value provenance, disproportionate to a 3-site fix and would land an unproven gate in the architectural suite | A plain grep ratchet was rejected as vacuous: the post-spec gate proved a location-scoped check both false-fails excluded sites and passes a duplicate inside the canonical module. IC-04 lands the honest, non-vacuous middle — a test pinning the *recorded disposition* of every enumerated site, which fails when a new unclassified site appears. |

## Implementation Concern Map

> Implementation concerns are NOT work packages. `/spec-kitty.tasks` translates these into
> executable WPs.

### IC-01 — Status-board verdict becomes override-aware

- **Purpose**: The status board's stale-verdict warning must honour a recorded approval override so
  an approved work package stops reporting itself rejected.
- **Relevant requirements**: FR-001, FR-002, FR-003, FR-006
- **Affected surfaces**: `src/specify_cli/agent_utils/status.py` — `_get_wp_review_verdict:41`,
  call site `:273`, **and the event read at `:165-168`** which must move from
  `read_events`/`reduce(events)` to the annotation-aware stream
- **Sequencing/depends-on**: none
- **Risks**: The annotation-blind read is the trap — a change that threads the *existing* snapshot
  state into the verdict call type-checks and passes hand-built-dict unit tests while returning
  `None` for every real override. Tests must be built from a **real event log containing an
  `InnerStateChanged` review annotation**, not from synthetic state mappings, or they will prove
  nothing. Tolerant degradation must survive (FR-006): an absent or malformed log falls back to the
  file-only answer rather than raising.

### IC-02 — Tasks-status verdict becomes override-aware

- **Purpose**: The same stale-verdict warning on the tasks-status surface must reach the same
  answer as IC-01 and as the merge gate.
- **Relevant requirements**: FR-001, FR-002, FR-003, FR-004
- **Affected surfaces**: `src/specify_cli/cli/commands/agent/tasks_parsing_validation.py`
  (`_get_latest_review_cycle_verdict:301`, display call site `:371`) **and
  `cli/commands/agent/tasks_status_cmd.py:279-283`** — the actual construction site that builds
  `st.events`/`st.snapshot` and threads them into `_apply_review_status_flags:349`. The override
  must be resolved where the stream is read, which is `tasks_status_cmd.py`, not
  `tasks_parsing_validation.py`.
- **Sequencing/depends-on**: none — independent of IC-01
- **Risks**: This function is re-exported (`tasks.py:196`, `__all__` at `:1006`) and shared with
  IC-03, so its signature change has two consumers. It returns `(verdict, path)` where the path
  feeds error messages; the canonical read returns a richer object, so the adapter must preserve
  the path-for-diagnostics contract. `tasks_status_cmd.py:283` also swallows read failures in a
  bare `except` — the override path must degrade the same way rather than introducing a new raise.

### IC-03 — Transition guard receives the corrected verdict

- **Purpose**: An operator who already recorded an arbiter override must not be forced to
  re-assert it when the work package advances to `done`.
- **Relevant requirements**: FR-007, FR-003
- **Affected surfaces**: `src/specify_cli/cli/commands/agent/tasks_move_task.py:548-556` (populates
  `MoveTaskRequest.review_verdict`); `tasks_transition_core.py:364-400`
  (`_guard_rejected_verdict`, `_authorize_review_override`) — **read and pinned by tests, not
  modified**
- **Sequencing/depends-on**: IC-02 (shares `_get_latest_review_cycle_verdict`)
- **Risks**: Highest-risk concern — it changes when a lifecycle transition is refused. Three
  refusal arms must be proven intact (no override, incomplete override, unparseable verdict), and
  `_authorize_review_override` stops firing for an already-overridden WP, so the durability of the
  *original* override evidence must be proven separately from the first-override path.
  **`tasks_move_task.py` has no snapshot or stream read today** (zero `reduce(` calls; its
  `read_events_transactional` uses at `:1734`/`:2411` are lane determination, unrelated to
  annotations). A stream read must be *added* here — unlike IC-01/IC-02 this is not a switch of an
  existing read. It must use `read_event_stream_transactional`
  (`coordination/status_transition.py:1109`), the same primitive the merge gate uses
  (`merge/done_bookkeeping.py:290`), so the guard cannot decide on a torn view of the log. A plain
  `read_event_stream` here would reintroduce exactly the torn read the transactional variant
  exists to prevent. Also carries the effective-verdict projection (research.md Decision 5) — the
  guard takes one `str | None`, so the two-valued rule must be projected, and getting that wrong
  trips a *different* refusal arm rather than failing loudly.

### IC-04 — Disposition is pinned so the class cannot silently return

- **Purpose**: Make the research disposition executable, so a newly-added unclassified
  review-cycle reader fails a test rather than passing unnoticed.
- **Relevant requirements**: FR-005, SC-004
- **Affected surfaces**: `tests/architectural/`
- **Sequencing/depends-on**: IC-01, IC-02, IC-03, IC-05 (the in-scope set must be retired first)
- **Risks**: Must be non-vacuous per DIRECTIVE_043 — it needs a concrete floor and must fail on an
  unclassified new site, not merely on a count change. It must not be a location-scoped grep; that
  check was proven both too strict and too weak. **Its floor must cover both enumeration passes** —
  record readers (glob) *and* override readers (`ReviewOverride.from_dict` / `review`-slot). A
  gate anchored only to the glob pass is blind to the duplicate class IC-05 retires, which is
  precisely how that duplicate escaped the first enumeration.

### IC-05 — Retire the third override-resolution duplicate

- **Purpose**: `post_merge`'s private `_snapshot_review_override` independently re-implements the
  canonical override read and feeds a merge-blocking verdict. Leaving it makes SC-004 true only by
  omission.
- **Relevant requirements**: FR-004, FR-005, SC-004; C-003
- **Affected surfaces**: `src/specify_cli/post_merge/review_artifact_consistency.py:112-127` (call
  site `:183`); `src/specify_cli/status/wp_review.py` — gains a snapshot-taking entry point
- **Sequencing/depends-on**: none — independent of IC-01/02/03
- **Risks**: The call site holds an already-materialized snapshot inside a per-WP loop, so neither
  existing `wp_review` entry point fits (`resolve_snapshot_review` re-reduces per call and would
  breach NFR-002). Adding the third entry point is the point of this concern — but it must be the
  *shared* implementation the other two delegate to, not a fourth parallel copy, or this concern
  causes the very drift it exists to remove. Not a live defect: `materialize` is annotation-aware,
  so both implementations agree today. This is drift and a false SC-004 claim, not broken output.

## Phase Status

- [x] Phase 0 — research complete ([research.md](research.md))
- [x] Phase 1 — design complete ([data-model.md](data-model.md), [quickstart.md](quickstart.md))
- [ ] Phase 2 — `/spec-kitty.tasks` (not started; user invokes)

**No `contracts/` directory**: this mission adds no API, endpoint, event, or externally visible
payload. It corrects an internal read. Generating an empty contracts folder would be noise.
