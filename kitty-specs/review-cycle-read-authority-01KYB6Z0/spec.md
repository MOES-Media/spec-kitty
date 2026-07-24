# Mission Specification: Review-Cycle Read Authority

**Mission Branch**: `fix/review-cycle-read-authority`
**Created**: 2026-07-25
**Status**: Draft
**Input**: User description: "Review-cycle read authority: consolidate the duplicate 'find the latest review-cycle-N.md' implementations onto one canonical helper, and fix #2646 where the review-verdict scan reads the PRIMARY tasks directory instead of the COORD lifecycle authority, so an approved WP stays stale. Closes #2646."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An approved work package stops reporting itself rejected (Priority: P1)

A reviewer rejects a work package. That rejection is recorded as a review-cycle file stored with
the work package. The implementer fixes the finding and the reviewer then approves.

An approval does not produce a review-cycle file — only rejections do. The approval is recorded
instead as an override in the append-only status event log, and that override is what the merge
and acceptance gates already honour when they decide the work package is genuinely approved.

The operator then asks for mission status. The status view still flags the work package as
carrying a rejected review, because it reads only the rejection file and never asks whether an
approval override supersedes it. There is no operator action that clears the warning: the approval
is real, recorded, and already authoritative everywhere else — the status view simply does not
consult it.

**Why this priority**: This is the reported symptom (#2646). It makes the status view contradict
the merge gate about the same work package, and the operator has no workaround.

**Independent Test**: Construct a work package with a recorded rejection and a subsequent complete
approval override in the event log, then read mission status. Delivers the fix on its own, with no
dependency on Story 2.

**Acceptance Scenarios**:

1. **Given** a work package whose latest review-cycle file is a rejection **and** whose status
   event log carries a complete approval override, **When** the operator reads mission status,
   **Then** no stale-verdict warning is reported for that work package.
2. **Given** the same work package with **no** override recorded, **When** the operator reads
   mission status, **Then** the stale-verdict warning IS reported — the fix must not suppress
   genuine rejections.
3. **Given** a work package whose override is incomplete (missing any required field), **When** the
   operator reads mission status, **Then** the override is NOT honoured and the rejection is still
   reported, matching the rule the merge gate already applies.
4. **Given** a work package with no review-cycle file at all, **When** the operator reads mission
   status, **Then** no verdict is reported and no error is raised.
5. **Given** any of the above, **When** the merge gate and the status view are both consulted,
   **Then** they report the same verdict for the same work package.

---

### User Story 2 - One definition of "the latest review verdict" (Priority: P2)

The system already has a canonical way to answer "what is the current review verdict for this work
package?" — one that reads the latest review-cycle file *and* applies any approval override. The
merge and acceptance gates use it, which is why they get Story 1 right.

Alongside it sit hand-written copies that re-implement only the first half: they find the latest
review-cycle file and stop, never asking about an override. Those copies are not merely redundant
— being override-blind *is* Story 1's defect. Each copy is an independent opportunity for the
status surface to disagree with the merge gate.

**Why this priority**: Prevents recurrence. Without it, Story 1 is a point fix on one copy and the
next override-blind caller reintroduces the same contradiction.

**Independent Test**: Route the override-blind callers through the canonical verdict read and
assert every caller reports the same verdict for the same work package state.

**Acceptance Scenarios**:

1. **Given** a work package in any review state, **When** any caller asks for its current verdict,
   **Then** every caller returns the same answer.
2. **Given** a directory containing a file whose cycle number is malformed or absent, **When** any
   caller asks for the current verdict, **Then** every caller ignores that file identically.
3. **Given** cycle numbers that are not zero-padded and exceed nine (so that lexical and numeric
   ordering disagree), **When** any caller asks for the current verdict, **Then** the numerically
   highest cycle is the one whose verdict is reported.
4. **Given** the consolidation is complete, **When** the source tree is searched for hand-written
   latest-review-cycle selection, **Then** only the canonical implementation remains.

---

### Edge Cases

- An override exists but is incomplete (missing any required field) — it must not be honoured, on
  either the snapshot leg or the migration-window fallback leg.
- An override exists for a work package that has no rejection file at all — must not fabricate a
  verdict or raise.
- Cycle numbering is sparse (cycle 1 and cycle 3 present, cycle 2 missing).
- A review-cycle file is present but its frontmatter is unparseable — this must degrade to "no
  verdict", never to a crash, preserving today's tolerant behaviour.
- The status event log is absent or unreadable — the verdict read must degrade to the file-only
  answer rather than failing the whole status view.
- A legacy on-disk override that predates the event-sourced snapshot must continue to be honoured
  for as long as the migration-window fallback is retained.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Title | User Story | Priority | Status |
|----|-------|------------|----------|--------|
| FR-001 | Status verdict honours the approval override | As an operator, I want the status view to treat a recorded approval override as superseding an earlier rejection, so that a work package I approved stops being reported as rejected. | High | Open |
| FR-002 | Status and merge gates agree | As an operator, I want the status view and the merge gate to reach the same verdict for the same work package, so that I am never given two contradictory answers about the same state. | High | Open |
| FR-003 | Genuine rejections are still reported | As a reviewer, I want an unoverridden rejection to keep surfacing, and an incomplete override to be refused, so that the fix removes a false positive without introducing a false negative. | High | Open |
| FR-004 | Override-blind verdict reads are retired | As a maintainer, I want the hand-written verdict reads that ignore overrides routed through the canonical read, so that override-blindness cannot survive anywhere. | Medium | Open |
| FR-005 | One canonical verdict implementation remains | As a maintainer, I want exactly one implementation of "current review verdict", so that its behaviour cannot drift between callers. | Medium | Open |
| FR-006 | Tolerant degradation is preserved | As an operator, I want an unreadable event log or malformed artifact to degrade to a partial answer rather than break the status view, so that diagnostics stay available when state is damaged. | High | Open |

### Non-Functional Requirements

| ID | Title | Requirement | Category | Priority | Status |
|----|-------|-------------|----------|----------|--------|
| NFR-001 | Tolerant degradation preserved | Unreadable, absent, or malformed review evidence yields "no verdict" and never raises to the operator; zero new uncaught exception paths in the status read. | Reliability | High | Open |
| NFR-002 | Override lookup does not scale per work package | The status read materializes the event-sourced snapshot at most once per mission, never once per work package; for a 20-work-package mission the snapshot is reduced exactly once. | Performance | Medium | Open |
| NFR-003 | Deterministic selection | Latest-cycle selection depends only on parsed cycle numbers, never on filesystem enumeration order; repeated reads over an unchanged directory return identical results across 100 consecutive runs. | Reliability | High | Open |
| NFR-004 | Regression evidence precedes the fix | The reproduction for FR-001 is committed as a failing test before the corrective change, per the red-first discipline in ADR `2026-07-17-1`. | Maintainability | High | Open |

### Constraints

| ID | Title | Constraint | Category | Priority | Status |
|----|-------|------------|----------|----------|--------|
| C-001 | No artifact may change partition | The mission must NOT relocate review-cycle artifacts. Their PRIMARY home was decided, rationalized and operator-signed-off by the `coord-commit-integrity-01KY5JS8` mission (its C-001, "No other kind moves"), merged at `97f24d9bf`. Reversing it would reintroduce the write/read split that mission retired. | Technical | High | Open |
| C-002 | Read-path only | The mission changes how the current verdict is READ. It must not change what is written, when it is written, or the review lifecycle itself. | Technical | High | Open |
| C-003 | Reuse the existing canonical read | The override-aware verdict read already exists and is proven in the merge and acceptance gates. The mission must route callers onto it, not author a second one. | Technical | High | Open |
| C-004 | Override semantics are inherited, not redefined | Completeness rules for an override, and the snapshot-first/fallback precedence, are already established. The mission must adopt them unchanged so status and merge cannot diverge. | Technical | High | Open |
| C-005 | Scope excludes adjacent cycle concerns | Next-cycle-number computation and iterate-all-cycles logic remain where they are. | Technical | Medium | Open |
| C-006 | Issue closure | The mission closes #2646 and must not claim to close #2626, which remains an independent open defect. | Business | High | Open |

### Key Entities

- **Review-cycle record**: The record of one *rejection* against one work package — its cycle
  number and verdict. Rejections are the only verdict that produces such a record; it is stored
  with its work package and stays there (C-001).
- **Approval override**: The event-log record that an approval superseded an earlier rejection.
  This — not a file — is how an approval is represented. It is honoured only when complete.
- **Current review verdict**: The answer to "what is this work package's review state now",
  derived by combining the latest review-cycle record with any complete override. This combination
  is the thing that must have exactly one implementation.
- **Work package**: The unit a review cycle is about, and the key by which a record and an
  override are matched.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a work package carrying a rejection plus a complete approval override, the count
  of falsely-reported stale verdicts drops from 1 to 0.
- **SC-002**: The status view and the merge gate return the same verdict for 100% of work-package
  states exercised by the test matrix, including the incomplete-override case.
- **SC-003**: An unoverridden rejection is still reported in 100% of cases — no false negative is
  introduced.
- **SC-004**: Exactly one implementation of "current review verdict" remains, verified by a
  reproducible check rather than inspection: a search of `src/` for review-cycle file globbing
  (`glob("review-cycle-*.md")` and equivalent) returns matches **only** inside the canonical
  helper module. The exhaustive call-site list is deliberately deferred to plan, which must
  enumerate it from the live tree rather than inherit a count from this spec — an unlisted
  survivor must fail this criterion, not slip past it.

## Assumptions

- The override-aware read already used by the merge and acceptance gates is correct; this mission
  propagates it rather than re-deriving its semantics.
- Every override-blind caller is intended to report the same verdict the merge gate would. If plan
  finds a caller that deliberately wants the file-only answer, it is an exception to be recorded
  explicitly rather than silently consolidated.
- Review-cycle files continue to be named with an integer cycle suffix; no renaming scheme is
  introduced by this mission.

## Out of Scope

- Relocating any artifact between partitions (C-001) — explicitly forbidden.
- #2626 (lane-transition auto-commit when a lane worktree is missing) — independent defect, stays
  open and unclaimed.
- Next-cycle-number computation and iterate-all-cycles call sites (per C-005).
- Any change to how, when, or where review evidence is written.

## Diagnosis Provenance

The original framing of #2646 (filed 2026-07-14) described a write/read partition split that no
longer exists: the `coord-commit-integrity-01KY5JS8` mission (merged `97f24d9bf`, 2026-07-23)
retired that misplacement and fixed review-cycle artifacts to their PRIMARY home. The first draft
of this spec inherited the issue's stale narrative and proposed reversing that decision; a
post-spec adversarial gate caught it against live code. The symptom in #2646 is real and still
reproduces — the mechanism is override-blindness in the status read, not partition mismatch.
