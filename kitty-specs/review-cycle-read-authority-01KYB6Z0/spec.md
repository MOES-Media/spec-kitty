# Mission Specification: Review-Cycle Read Authority

**Mission Branch**: `fix/review-cycle-read-authority`
**Created**: 2026-07-25
**Status**: Draft
**Input**: User description: "Review-cycle read authority: consolidate the duplicate 'find the latest review-cycle-N.md' implementations onto one canonical helper, and fix #2646 where the review-verdict scan reads the PRIMARY tasks directory instead of the COORD lifecycle authority, so an approved WP stays stale. Closes #2646."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An approved work package stops reporting itself rejected (Priority: P1)

A reviewer works a mission that uses a coordination branch. They reject a work package on the
first review cycle, the implementer fixes the finding, and the reviewer approves on the second
cycle. The approval — and its review evidence — is recorded on the coordination branch, which is
the lifecycle authority for that mission.

The operator then asks for mission status. The status view still flags that work package as
carrying a rejected review, because it looked for review evidence in the planning partition,
where only the stale first-cycle rejection exists. There is no operator action that clears the
warning: the approval is real, recorded, and canonical — it is simply not being read.

**Why this priority**: This is the reported defect (#2646). It makes the status view lie about
lifecycle state on exactly the missions that need coordination most, and the operator has no
workaround.

**Independent Test**: Construct a mission with a coordination topology where cycle 1 is rejected
on the planning partition and cycle 2 is approved on the lifecycle authority, then read mission
status. Delivers the fix on its own, with no dependency on Story 2.

**Acceptance Scenarios**:

1. **Given** a mission whose lifecycle authority holds an approved latest review cycle for a work
   package, **and** the planning partition holds an older rejected cycle for that same work
   package, **When** the operator reads mission status, **Then** no stale-verdict warning is
   reported for that work package.
2. **Given** the same mission, **When** the latest cycle on the lifecycle authority is itself a
   rejection, **Then** the stale-verdict warning IS reported — the fix must not suppress genuine
   rejections.
3. **Given** a mission with no coordination topology, **When** the operator reads mission status,
   **Then** verdict reporting is unchanged from today's behaviour.
4. **Given** a work package with no review evidence in either location, **When** the operator
   reads mission status, **Then** no verdict is reported and no error is raised.

---

### User Story 2 - One definition of "the latest review cycle" (Priority: P2)

Four separate places in the system each answer the question "which review cycle is the latest for
this work package?", and they answer it with four independently written implementations. They do
not agree in every case: they differ in how a malformed filename is treated and in how ordering is
established. A reader cannot tell which is authoritative, and a change to one does not propagate
to the others.

This divergence is what allowed Story 1's defect to exist and go unnoticed: because each caller
brought its own notion of where and how to find review evidence, no single place could be
corrected once.

**Why this priority**: Prevents recurrence. Without it, Story 1 is a point fix on one of four
copies and the next caller reintroduces the bug.

**Independent Test**: Point every latest-cycle caller at one shared definition and assert
identical selection behaviour across all call sites, including the malformed-filename case.

**Acceptance Scenarios**:

1. **Given** a work package directory containing several review cycles, **When** any caller asks
   for the latest cycle, **Then** every caller selects the same one.
2. **Given** a directory containing a file whose cycle number is malformed or absent, **When** any
   caller asks for the latest cycle, **Then** every caller ignores that file identically rather
   than ordering it inconsistently.
3. **Given** cycle numbers that are not zero-padded and exceed nine (so that lexical and numeric
   ordering disagree), **When** any caller asks for the latest cycle, **Then** the numerically
   highest cycle is selected.

---

### Edge Cases

- A work package directory exists on the lifecycle authority but not on the planning partition,
  or the reverse — neither absence may raise an error to the operator.
- Review evidence exists in both locations with the same cycle number but different verdicts; the
  lifecycle authority wins, and the resolution must be deterministic rather than filesystem-order
  dependent.
- Cycle numbering is sparse (cycle 1 and cycle 3 present, cycle 2 missing).
- A review-cycle file is present but its frontmatter is unparseable — this must degrade to "no
  verdict", never to a crash, preserving today's tolerant behaviour.
- A mission whose topology carries no separate lifecycle authority must resolve to exactly the
  same location it does today.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Title | User Story | Priority | Status |
|----|-------|------------|----------|--------|
| FR-001 | Review evidence resolves to the lifecycle authority | As an operator, I want review verdicts read from the partition that owns lifecycle evidence, so that an approval I just recorded is the one reported back to me. | High | Open |
| FR-002 | Review-cycle artifacts are a modeled artifact kind | As a maintainer, I want review-cycle evidence to have a declared artifact home like every other mission artifact, so that its location is resolved by the placement seam rather than inherited from whichever directory a caller happens to hold. | High | Open |
| FR-003 | Genuine rejections are still reported | As a reviewer, I want an authentic latest rejection to keep surfacing as a stale verdict, so that the fix narrows a false positive without introducing a false negative. | High | Open |
| FR-004 | Single canonical latest-cycle selection | As a maintainer, I want exactly one implementation of "select the latest review cycle", so that its behaviour cannot drift between callers. | Medium | Open |
| FR-005 | Every latest-cycle caller uses the canonical selection | As a maintainer, I want the four existing call sites routed through the canonical selection, so that no independent copy survives to diverge. | Medium | Open |
| FR-006 | Topologies without a separate authority are unaffected | As an operator on a single-branch mission, I want status output to be byte-identical to today, so that the fix is scoped to the topology that has the defect. | High | Open |

### Non-Functional Requirements

| ID | Title | Requirement | Category | Priority | Status |
|----|-------|-------------|----------|----------|--------|
| NFR-001 | Tolerant degradation preserved | Unreadable, absent, or malformed review evidence yields "no verdict" and never raises to the operator; zero new uncaught exception paths in the status read. | Reliability | High | Open |
| NFR-002 | No added status latency | Reading mission status performs at most one additional directory resolution per mission (not per work package); status read time for a 20-work-package mission stays within 10% of the pre-change baseline. | Performance | Medium | Open |
| NFR-003 | Deterministic selection | Latest-cycle selection depends only on parsed cycle numbers, never on filesystem enumeration order; repeated reads over an unchanged directory return identical results across 100 consecutive runs. | Reliability | High | Open |
| NFR-004 | Regression evidence precedes the fix | The reproduction for FR-001 is committed as a failing test before the corrective change, per the red-first discipline in ADR `2026-07-17-1`. | Maintainability | High | Open |

### Constraints

| ID | Title | Constraint | Category | Priority | Status |
|----|-------|------------|----------|----------|--------|
| C-001 | Read-path only | The mission changes how review evidence is READ. It must not change where review evidence is written, nor the review lifecycle itself. | Technical | High | Open |
| C-002 | Canonical placement seam | Artifact location must be resolved through the existing kind-aware placement seam; the mission must not introduce a parallel resolver or hardcode a partition. | Technical | High | Open |
| C-003 | Work-package frontmatter stays on the planning partition | The existing planning-partition reads for work-package task files and mission identity are correct and must be left intact; only review evidence moves authority. | Technical | High | Open |
| C-004 | Scope excludes adjacent cycle concerns | Next-cycle-number computation and iterate-all-cycles logic are deliberately out of scope for this mission and remain where they are. | Technical | Medium | Open |
| C-005 | Issue closure | The mission closes #2646 and must not claim to close #2626, which remains an independent open defect. | Business | High | Open |

### Key Entities

- **Review cycle evidence**: The record of one review pass over one work package — its cycle
  number and its verdict. Owned by the lifecycle authority of the mission it belongs to.
- **Work package**: The unit a review cycle is about. Its task definition and identity live on the
  planning partition; its lifecycle state and review evidence do not.
- **Lifecycle authority**: The partition that owns mutable lifecycle state for a mission. For
  coordination topologies this is distinct from the planning partition; for others they coincide.
- **Artifact kind**: The declared category that determines an artifact's home per topology.
  Review-cycle evidence currently has no such declaration, which is the structural root cause.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a coordination-topology mission with an approved latest cycle recorded on the
  lifecycle authority, the count of falsely-reported stale verdicts drops from 1 to 0.
- **SC-002**: The number of independent implementations of "select the latest review cycle" goes
  from 4 to 1, verified by a search of the source tree.
- **SC-003**: A genuinely rejected latest cycle is still reported in 100% of cases — no false
  negative is introduced.
- **SC-004**: Status output for missions without a separate lifecycle authority is unchanged,
  verified by comparing before-and-after output on at least one such mission.

## Assumptions

- The lifecycle authority already holds committed review evidence at the point the status view
  reads it; this mission does not need to make writes more reliable, only reads correct.
- The four latest-cycle call sites are behaviourally interchangeable today apart from their
  malformed-filename and ordering handling, so unifying them is behaviour-preserving for
  well-formed input.
- Review-cycle files continue to be named with an integer cycle suffix; no renaming scheme is
  introduced by this mission.

## Out of Scope

- #2626 (lane-transition auto-commit when a lane worktree is missing) — independent defect, stays
  open and unclaimed.
- Next-cycle-number computation and iterate-all-cycles call sites (per C-004).
- Any change to how or where review evidence is written.
