# Specification Quality Checklist: Review-Cycle Read Authority

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-07-25
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Requirement types are separated (Functional / Non-Functional / Constraints)
- [x] IDs are unique across FR-###, NFR-###, and C-### entries
- [x] All requirement rows include a non-empty Status value
- [x] Non-functional requirements include measurable thresholds
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

### Second pass — post-spec adversarial gate (2026-07-25)

The first draft was **misdiagnosed** and has been rewritten. The post-spec gate
(architect-alphonso lens, confirmed by an independent skeptic against live code) found that
FR-001, FR-002, User Story 1 and Key Entities all rested on a false premise: that review-cycle
evidence is COORD-owned and undeclared, and that the status view reads the wrong partition.

Verified against live code before accepting the finding:

- `review/cycle.py:186` — `validate_review_artifact` hard-requires `verdict == "rejected"`. An
  approval never writes a review-cycle file, so there is no approved file to "read from the right
  partition".
- `review/cycle.py:30-52` — `_review_cycle_wp_dir` declares review-cycle files a PRIMARY
  `WORK_PACKAGE_TASK` artifact, "NEVER the coordination husk", explicitly retiring the
  #2646/#2697/#2275 misplacement.
- `97f24d9bf` (coord-commit-integrity-01KY5JS8, merged 2026-07-23) is an ancestor of HEAD, so that
  decision is live on this branch.
- `review/artifacts.py:298` — `latest_review_artifact_verdict` already combines the file verdict
  with the event-sourced override; `agent_utils/status.py` contains **zero** references to
  `ReviewOverride`.

The original draft would have reversed a freshly-merged, operator-signed-off invariant. C-001 now
forbids exactly that. The mission is now narrower and better-founded: propagate an existing proven
read to the callers that lack it.

### First pass — 2026-07-25

Two items required a rewrite before they passed:

1. **Technology-agnostic success criteria.** SC-002 initially named specific source files and line
   numbers. Rewritten to state the observable outcome ("4 implementations become 1, verified by a
   search of the source tree") without pinning file paths, which belong in plan/tasks rather than
   the spec.

2. **Measurable NFR thresholds.** NFR-002 initially said "no meaningful latency regression".
   Replaced with a bounded threshold (at most one additional directory resolution per mission, and
   within 10% of baseline for a 20-work-package mission) so it can be failed.

Residual judgement calls, recorded rather than hidden:

- **NFR-002's 10% bound is an engineering estimate, not a measured baseline.** No baseline has been
  captured yet. If plan finds the true variance exceeds 10% run-to-run, the threshold should be
  re-derived from a real measurement rather than kept as written.
- **The Assumptions section asserts the four call sites are behaviourally interchangeable for
  well-formed input.** This was established by reading them, not by executing them
  side-by-side. Plan should verify it before treating consolidation as behaviour-preserving,
  because FR-005 depends on it.
- **C-004 excludes adjacent cycle concerns by operator decision**, not because they are unrelated.
  A reader may reasonably ask why the DIRECTIVE_044 consolidation stops where it does; the answer
  is blast-radius control, and that rationale lives here rather than in the spec body.
