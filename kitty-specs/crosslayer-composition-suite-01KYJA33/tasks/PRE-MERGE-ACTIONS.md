# M7 (crosslayer-composition-suite-01KYJA33) — Pre-Merge / Accept-Time Action Items

These are mission-level items that no single WP's `owned_files` covers, but
that must not be forgotten between tasks-authoring time and this mission's
accept/merge gate. Recorded here, in the tasks artifact, so they survive
independently of any one WP's own Definition of Done.

## 1. Coordination/target branch divergence — MUST be reconciled before this mission merges

**Checked directly while authoring this tasks phase** (2026-07-27), **and
re-checked after running `spec-kitty agent mission finalize-tasks` for real**
— the second check changed the finding, recorded here honestly rather than
leaving the earlier, now-superseded version standing:

- **Before `finalize-tasks` ran**: mission coordination branch
  `kitty/mission-crosslayer-composition-suite-01KYJA33` was at
  `c425bc188995b5b9a04bece05b511ba81896ce7f` (the mission's original base
  commit on `main`), a clean **6 commits behind** target branch
  `kitty/mission-crosslayer-composition-suite` (then at `e4ef24f9e`) — a
  simple fast-forward gap.
- **After `finalize-tasks` ran** (this tasks phase's own required step): the
  command itself writes to *both* branches — `tasks.md`/`lanes.json`/the
  five WP prompt files landed on the **target** branch, while
  `acceptance-matrix.json`/`issue-matrix.md` and five
  `chore(spec-kitty): status transition WPxx` bookkeeping commits landed on
  the **coordination** branch. `git merge-base --is-ancestor
  kitty/mission-crosslayer-composition-suite-01KYJA33
  kitty/mission-crosslayer-composition-suite` now exits **non-zero** — the
  two branches have **genuinely diverged in both directions**, not merely
  "coordination is behind": the coordination branch carries six commits
  (five status-transition events plus its own tasks-add commit) the target
  branch does not have, and the target branch carries eight commits
  (spec/plan/remediation/IC-00-dissolution plus its own tasks-add and a
  bookkeeping-fix commit) the coordination branch does not have.

**Why this matters, concretely, and why the fix is no longer a simple
fast-forward**: this is exactly the configuration that caused mission M3's
data loss (fork issue #33) — `spec-kitty merge` replaying a stale
coordination branch over newer work, silently reverting a shipped fix. It is
harmless right now (no WP has been implemented or merged yet), but a naive
fast-forward-one-over-the-other reconciliation would now **discard real
commits from whichever side is forced to reset** — the coordination branch's
own status-transition/bookkeeping commits if target is force-applied, or the
target branch's spec/plan/remediation history if coordination is force-
applied. **This must be reconciled with an actual merge (or equivalent
history-preserving operation) that keeps both sides' unique commits**, not a
fast-forward or a hard reset, before any WP lane merges back.

**Action required, before WP01–WP05's lanes begin merging back**: perform a
real merge (or this fork's equivalent reconciliation mechanism) between
`kitty/mission-crosslayer-composition-suite-01KYJA33` and
`kitty/mission-crosslayer-composition-suite` that preserves both branches'
unique commits, then confirm via `git merge-base --is-ancestor
kitty/mission-crosslayer-composition-suite-01KYJA33
kitty/mission-crosslayer-composition-suite` (expect exit `0`) that the
coordination branch is fully caught up afterward. This is flagged here as an
explicit action item, not fixed as part of this tasks-authoring pass — per
this mission's own "flag forward, do not fix here" directive, it is an
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

**Post-tasks-review remediation update (M-2, done)**: real rows for
`C-001`, `C-002`, and `C-003` now exist in `acceptance-matrix.json` on the
**coordination** branch (`kitty/mission-crosslayer-composition-suite-01KYJA33`)
— previously that file held only `FR-001`..`FR-007` as
`"TODO: replace with a real acceptance criterion"` stubs, with no rows for
these three constraints at all. Each new row cites its spec.md verification
command, states a named owner (C-001: WP02; C-002 assembled-diff run and
C-003: the mission accept gate), and carries `pass_fail: "pending"` —
**this remediation added the rows, it did not run them**; whoever runs this
mission's accept gate still must execute each command for real and fill in
`evidence`/`pass_fail`/`verified_by`/`verified_at` before this mission is
accept-ready. Also note (item 1, above): this file (`acceptance-matrix.json`)
lives on the coordination branch while `tasks.md`/`lanes.json`/the WP prompt
files live on the target branch — the two branches remain in genuine
two-way divergence and are reconciled at merge time, not here.

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

## 6. WP04 lane merge — task-file and status.json conflicts (recorded at WP02/WP04 approval time)

Recorded while approving WP02/WP04 so the merge step is not surprised by
either conflict:

- **Resolve the WP04 task-file conflict explicitly in favor of the
  coordination branch.** Do **not** use `-X ours` or a union strategy. Three
  of the five conflicting hunks have real content on both sides, and lane-b's
  three unique lines there are older, superseded versions of that content —
  a union or `ours` resolution would silently regress lane-d's MEDIUM-2
  remediation (the inner PR-gate fix landed at lane-d `cfddb951b`). The
  coordination branch's version of the WP04 task file is the correct one to
  keep.
- **`status.json` also conflicts** at the current tip (`event_count` 21 vs
  15 on the two sides; WP02's lane shows `for_review` on one side and
  `in_progress` on the other). Do not hand-resolve this file. Regenerate it
  through the canonical reducer
  (`specify_cli.status.reducer.materialize` / `spec-kitty agent status
  materialize`) instead — a sibling doing exactly this on a related mission
  produced output byte-identical to the canonical reducer's, and hand-merging
  a generated artifact risks a silent, undetected drift from the event log it
  is derived from.
- **Lane-b's own copy of the WP02 task file is separately stale** against the
  planning tip (it predates the tip's later WP02 remediation commits). This
  one needs **no action**: it auto-merges cleanly and the `e00696874` entry
  (the WP02 mission-review remediation record) is retained either way. Noted
  here only so the merger recognizes it as an already-understood,
  no-action-needed conflict and doesn't spend time re-diagnosing it.
