# M7 (crosslayer-composition-suite-01KYJA33) — Pre-Merge / Accept-Time Action Items

These are mission-level items that no single WP's `owned_files` covers, but
that must not be forgotten between tasks-authoring time and this mission's
accept/merge gate. Recorded here, in the tasks artifact, so they survive
independently of any one WP's own Definition of Done.

## 1. Stale coordination branch — MUST be reconciled before this mission merges

**Checked directly while authoring this tasks phase** (2026-07-27):

- Mission coordination branch `kitty/mission-crosslayer-composition-suite-01KYJA33`
  is at `c425bc188995b5b9a04bece05b511ba81896ce7f` (the mission's original base
  commit on `main`).
- Target branch `kitty/mission-crosslayer-composition-suite` is at
  `e4ef24f9e926eed3934989dc746313d8788016c9` — **6 commits ahead** of the
  coordination branch (the spec/plan authoring and remediation commits,
  including the IC-00 dissolution commit itself).
- `git merge-base` confirms the coordination branch has not advanced past
  the mission's original base; the target branch has moved on without it.

**Why this matters, concretely**: this is exactly the configuration that
caused mission M3's data loss (fork issue #33) — `spec-kitty merge` replaying
a stale coordination branch over newer work, silently reverting a shipped
fix. It is harmless right now (no WP has been implemented or merged yet), but
**it must be reconciled before any lane of this mission merges**, not
discovered for the first time at merge time.

**Action required, before WP01–WP05's lanes begin merging back**: bring
`kitty/mission-crosslayer-composition-suite-01KYJA33` up to date with
`kitty/mission-crosslayer-composition-suite` (fast-forward or equivalent,
whatever this fork's own coordination-branch update mechanism is), and
confirm via `git merge-base --is-ancestor
kitty/mission-crosslayer-composition-suite-01KYJA33
kitty/mission-crosslayer-composition-suite` (expect exit `0`) that the
coordination branch is no longer behind. This is flagged here as an explicit
action item, not fixed as part of this tasks-authoring pass — it is an
operator/infrastructure action, not a work-package deliverable.

## 2. C-002 (assembled diff) and C-003 must become explicit acceptance-matrix rows at accept time

Both constraints are cross-lane and review-time, not owned by any single
WP's task file (see spec.md's own `Lane` annotations on C-002/C-003, and
plan.md's "Cross-Lane / Review-Time Checks" section):

- **C-002** (diff-scope allow-list) runs **twice**: per-lane, before each
  WP's own merge (each of WP01–WP05's task files above carries this as its
  own final subtask), and once more over the fully assembled diff
  (`git diff --name-only main...HEAD`) as the cross-lane backstop, before
  this mission's accept/merge gate.
- **C-003** (fabricated-field grading-leakage audit) is a review-time textual
  audit only — the `grep`-based candidate-surfacing command in spec.md, run
  over **both** lanes' committed output together, with its deliberately
  inverted exit polarity (exit `1` = clean). It is never wired into a hard
  CI gate.

**The acceptance matrix is the one artifact `_evaluate_evidence_gate` can
actually see and act on.** Left as free-floating prose in the spec/plan
("cross-lane, no lane owns it"), this degrades into "nobody does it."
**Whoever runs this mission's accept gate must add explicit acceptance-matrix
rows for both C-002 (assembled-diff run) and C-003**, with their actual
observed results (per spec.md's verification commands), before this mission
can be considered accept-ready. This is not satisfied by any WP's own
Definition of Done alone.

## 3. Lane-c (WP05) sequencing must be independently verified, not gate-trusted

WP05's frontmatter declares `dependencies: [WP02, WP04]`, which drives the
real auto-merge and topological-sort mechanisms in this codebase. **This
repo's `merge_gates.mode` is `"warn"`, not `"block"`** (confirmed directly:
`.kittify/config.yaml` sets no `merge_gates` override, so
`policy/config.py`'s dataclass default applies) — an out-of-order merge is
not hard-blocked by the dependency gate. At accept time, independently
confirm via `git log`/`git merge-base` that WP02's and WP04's merge commits
actually precede WP05's lane branch base commit — do not accept the
frontmatter declaration alone as proof of correct sequencing.

## 4. FR-005's `eroded` verdict — accept-time confirmation

WP05's own Definition of Done requires `erosion-control-045` to be run for
real against a live endpoint with the `eroded` verdict actually observed
(not merely designed). At this mission's accept gate, re-confirm this
observation is present in WP05's work log with a real verdict string and
exit code — this mission's own standing requirement ("every grader ships a
rigged-impossible discrimination control that will be observed failing, not
merely written") is not satisfied by inspection of the case file's `expected`
block alone.

## 5. DIR-012 — tracker issue assignment (informational, closed by WP01/T001)

This mission's seed issue, `MOES-Media/spec-kitty#26`, had **zero assignees**
as of this tasks-authoring pass (checked directly via `gh issue view 26
--repo MOES-Media/spec-kitty --json assignees`) — unlike M1's precedent issue,
which was already assigned when that mission's tasks phase ran. WP01's T001
exists to close this before implementation starts; recorded here as well so
it is visible at a mission level, not only inside one WP's file.
