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
**Primary Dependencies**: none added — the canonical read (`specify_cli.review.artifacts`) and the
status reducer (`specify_cli.status`) already exist and are already imported at the call sites
**Storage**: filesystem — review-cycle Markdown records (PRIMARY partition) plus the append-only
`status.events.jsonl` event log
**Testing**: pytest; red-first regression tests per ADR `2026-07-17-1` (NFR-004), run via
`PWHEADLESS=1 pytest tests/ -n auto --dist loadfile`
**Target Platform**: Linux/macOS/Windows CLI
**Project Type**: single
**Performance Goals**: no additional event-log read or snapshot reduction at any in-scope site —
the reduced snapshot is already in hand at all three (NFR-002)
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
| Canonical sources | ✅ | Reuses `latest_review_artifact_verdict` and the `_snapshot_review_override` pattern rather than improvising |

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
├── cli/commands/agent/
│   ├── tasks_parsing_validation.py            # IC-02: retire _get_latest_review_cycle_verdict
│   ├── tasks_move_task.py                     # IC-03: override-aware verdict into MoveTaskRequest
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
- **Affected surfaces**: `src/specify_cli/agent_utils/status.py` (`_get_wp_review_verdict:41`, call
  site `:273`); the reduced snapshot already available at `:165-171`
- **Sequencing/depends-on**: none
- **Risks**: The per-WP `state` mapping must be threaded from the existing `snapshot.work_packages`
  loop to the verdict call without a second reduction (NFR-002). Tolerant degradation must survive:
  an absent or malformed event log falls back to the file-only answer rather than raising (FR-006).

### IC-02 — Tasks-status verdict becomes override-aware

- **Purpose**: The same stale-verdict warning on the tasks-status surface must reach the same
  answer as IC-01 and as the merge gate.
- **Relevant requirements**: FR-001, FR-002, FR-003, FR-004
- **Affected surfaces**: `src/specify_cli/cli/commands/agent/tasks_parsing_validation.py`
  (`_get_latest_review_cycle_verdict:301`, display call site `:371`)
- **Sequencing/depends-on**: none — independent of IC-01
- **Risks**: This function is re-exported (`tasks.py:196`, `__all__` at `:1006`) and shared with
  IC-03, so its signature change has two consumers. It returns `(verdict, path)` where the path
  feeds error messages; the canonical read returns a richer object, so the adapter must preserve
  the path-for-diagnostics contract.

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

### IC-04 — Disposition is pinned so the class cannot silently return

- **Purpose**: Make the research disposition executable, so a newly-added unclassified
  review-cycle reader fails a test rather than passing unnoticed.
- **Relevant requirements**: FR-005, SC-004
- **Affected surfaces**: `tests/architectural/`
- **Sequencing/depends-on**: IC-01, IC-02, IC-03 (the in-scope set must be retired first)
- **Risks**: Must be non-vacuous per DIRECTIVE_043 — it needs a concrete floor (the enumerated
  site count) and must fail on an unclassified new site, not merely on a count change. It must not
  be a location-scoped grep; that check was already proven both too strict and too weak.

## Phase Status

- [x] Phase 0 — research complete ([research.md](research.md))
- [x] Phase 1 — design complete ([data-model.md](data-model.md), [quickstart.md](quickstart.md))
- [ ] Phase 2 — `/spec-kitty.tasks` (not started; user invokes)

**No `contracts/` directory**: this mission adds no API, endpoint, event, or externally visible
payload. It corrects an internal read. Generating an empty contracts folder would be noise.
