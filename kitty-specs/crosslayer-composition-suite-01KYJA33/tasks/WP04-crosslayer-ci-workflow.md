---
work_package_id: WP04
title: 'crosslayer.yml CI workflow: static PR gate + cadence scaffold (lane-b part 3)'
dependencies: []
requirement_refs:
- FR-004
- FR-005
- C-002
planning_base_branch: kitty/mission-crosslayer-composition-suite
merge_target_branch: kitty/mission-crosslayer-composition-suite
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-crosslayer-composition-suite. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-crosslayer-composition-suite unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-crosslayer-composition-suite-01KYJA33
base_commit: 478b4d5f37ffb869eb0b49fba534a338bb1f27bb
created_at: '2026-07-27T21:47:12.656709+00:00'
subtasks:
- T018
- T019
- T020
- T021
- T022
agent: claude
history:
- timestamp: '2026-07-27T19:45:23Z'
  event: created
  by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: node-norris
authoritative_surface: .github/workflows/
create_intent:
- .github/workflows/crosslayer.yml
- conformance/crosslayer/README.md
- tests/cross_cutting/misc/test_crosslayer_workflow.py
execution_mode: code_change
model: ''
owned_files:
- .github/workflows/crosslayer.yml
- conformance/crosslayer/README.md
- tests/cross_cutting/misc/test_crosslayer_workflow.py
role: implementer
tags: []
tracker_refs: []
---

# WP04 — crosslayer.yml CI workflow: static PR gate + cadence scaffold

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the
frontmatter, and behave according to its guidance before parsing the rest of
this prompt.

- **Profile**: `node-norris`
- **Role**: `implementer`
- **Agent/tool**: `claude`

If no profile is specified, run `spec-kitty agent profile list` and select the
best match for this work package's `task_type` and `authoritative_surface`.

---

## Objective

Author `.github/workflows/crosslayer.yml`: a static PR-gate job (every PR,
`garrison-hq/muster-action@<pinned-sha>` against FR-004's manifest,
path-filtered to both `conformance/**` and
`src/doctrine/agent_profiles/built-in/**`) plus a cadence-job scaffold
(`schedule:` + `workflow_dispatch:`, secrets from GitHub Actions repository
secrets only, zero real cases until WP05/lane-c lands). This is a **new**
workflow file, isolated from the shared `.github/workflows/conformance.yml`
by design — never edit that shared file. This WP has no dependency on WP01,
WP02, or WP03's source — only on their **paths**, all fixed in advance by
plan.md's Project Structure.

## Context (read first)

- Spec: `kitty-specs/crosslayer-composition-suite-01KYJA33/spec.md`
  — FR-004 (CI wiring clause), FR-005 (infra-only, not case content);
  Dependencies & Assumptions (workflow-file collision avoidance,
  `conformance/README.md` collision avoidance, the M1 post-spec finding on
  trigger-path scope).
- Plan: `kitty-specs/crosslayer-composition-suite-01KYJA33/plan.md`
  — IC-04 (this WP's source concern, including its own risk notes about an
  empty cadence job and the shared-file collision surfaces).

**Path-filter requirement, stated precisely**: the static job's trigger
paths must cover **both** `conformance/**` **and**
`src/doctrine/agent_profiles/built-in/**` — not just the former. A
profile-only PR that changes an agent profile must see and be able to fix
the persona-drift check its own diff affects (regenerating the committed
persona under `conformance/crosslayer/personas/` is a `conformance/**` edit,
so that PR's author can make the fix even though it did not originate this
mission); a PR touching neither path must never see this job at all.

**Never touch**: `.github/workflows/conformance.yml` (M3's PR #30 modifies
it; a collision here would block concurrent work), `conformance/README.md`
(also M3-touched, and inside C-002's general allow-list unless explicitly
excluded — this mission documents itself in `conformance/crosslayer/README.md`
instead), `conformance/scripts/check-manifest-completeness.mjs` (M3-touched,
unrelated to this mission).

## Subtasks

### T018 — Verify the real `muster-action` input schema and pinned SHA convention

**Purpose**: This mission's spec requires CI to invoke
`garrison-hq/muster-action@<pinned-sha>` — the same cache-warm-equivalent
pattern `conformance.yml` already uses — not a bare `npx`. Confirm the actual
shipped input names before writing the step that depends on them (do not
assume a briefed shape is correct without checking).

**Steps**:
1. Inspect the real `garrison-hq/muster-action` repository's `action.yml` at
   whatever pinned commit/tag this fork's own `conformance.yml` already uses
   (`grep -n "muster-action" .github/workflows/conformance.yml` to find the
   exact pin this fork already trusts — reuse it, do not introduce a new,
   unreviewed pin).
2. Confirm the actual input names (`command`/`args`/`version`/etc.) against
   that real file, not a design briefing.
3. Record the confirmation (or correction) in the work log.

**Files**: none (verification only).
**Validation**: work log states either "confirmed: matches
`conformance.yml`'s existing usage" or "corrected: real input names are
X/Y/Z", with the source consulted.

---

### T019 — Author `.github/workflows/crosslayer.yml`

**Purpose**: The static PR gate and the cadence scaffold, in one new file.

**Steps**:
1. Static job: triggers on `pull_request` (any branch), path-filtered to
   `conformance/**` and `src/doctrine/agent_profiles/built-in/**` (both, per
   the M1 post-spec finding above). Steps: checkout (match this fork's
   existing pin convention, `grep -rn "actions/checkout@"
   .github/workflows/*.yml`), `garrison-hq/muster-action@<pinned-sha>`
   against `conformance/crosslayer/manifest.yaml --static-only`, then the two
   one-line drift-check call sites:
   `bash conformance/scripts/check-persona-drift.sh` (WP01's script) and
   `bash conformance/scripts/check-sop-extract-drift.sh` (WP03's script,
   this WP's own sibling file).
2. Cadence job: `schedule:` trigger plus `workflow_dispatch:` for on-demand
   manual runs. `MUSTER_ENDPOINT`/`MUSTER_API_KEY` sourced from GitHub
   Actions **repository secrets only** — never a manifest value, never
   argv, never a log line. Runs FR-005's cases once they exist.
3. **Zero real cases exist yet** (WP05/lane-c is blocked on M3 and on this
   WP + WP02 merging first). If the cadence job globs a
   `cases/rule-survival-*.yaml`/`erosion-control-*.yaml` pattern that
   currently matches nothing, `muster crosslayer run` may exit `0` trivially
   (no cases = no failures). **Add an explicit inline YAML comment** at that
   step stating this plainly — a green cadence job before WP05 lands must
   never be mistaken for FR-005 being satisfied. This is not optional
   documentation; it is the guard against a specific, previously-seen
   failure mode (an unexercised detector read as a passing one).
4. No `secrets:` reference anywhere in the static job — it must remain fully
   offline/zero-network and runnable on a fork PR with zero repository
   secrets available.

**Files**: `.github/workflows/crosslayer.yml` (new).
**Validation**: covered by T021; inline comment from step 3 confirmed present
by inspection.

---

### T020 — Author `conformance/crosslayer/README.md`

**Purpose**: This mission's own documentation, entirely separate from the
shared top-level `conformance/README.md` (never edited by this mission).

**Steps**:
1. Document this suite's manifest layout, the two lint/rule-survival check
   classes, and how a contributor runs the static check locally.
2. Do not touch `conformance/README.md` under any circumstance.

**Files**: `conformance/crosslayer/README.md` (new).
**Validation**: `git diff --stat conformance/README.md` shows no changes.

---

### T021 — Real CI verification (mandatory, may be legitimately blocked)

**Purpose**: This cannot be simulated locally — it requires an actual GitHub
Actions run on this mission's own PR, and it requires both this WP's workflow
file and WP01's/WP02's/WP03's committed artifacts to coexist on a pushed
branch.

**Steps**:
1. Once this mission's lanes are merged onto a branch carrying both this
   WP's `crosslayer.yml` and the manifest/persona/sop-extract files it
   references, confirm the workflow actually triggers on a real PR.
2. Confirm the static job's steps (muster-action static run, both drift-check
   call sites) show green in that run's logs.
3. If no such combined, pushed branch exists yet at the time this WP is
   otherwise complete, **report this as blocked pending lane integration** —
   the same honest non-fabrication this mission's own sibling missions have
   required (do not invent a `run_id`; do not claim a green run that did not
   happen). Record exactly what is missing (which lane's merge is
   outstanding) so the blocker is actionable, not vague.
4. Once unblocked, record the real `run_id`, `conclusion`, and wall-clock
   minutes, independently confirmed via
   `gh run view <run_id> --repo MOES-Media/spec-kitty --json
   conclusion,headBranch,createdAt,updatedAt`.

**Files**: none new.
**Validation**: either a real, independently-confirmed green run recorded, or
an honest, specific blocked-status entry naming what is outstanding.

---

### T022 — WP04 verification gate (Definition of Done + per-lane C-002)

**Owned-files/scope-gate widening (C-011 remediation)**: this WP's
`owned_files`/`create_intent` originally admitted only the two CI/README
deliverables, with no path a C-011-compliant failing-first test could live
at. That is a task-file defect, not a reason to skip ATDD-first discipline
— `owned_files`, `create_intent`, and the per-lane C-002 gate below are
widened to admit exactly one additional file,
`tests/cross_cutting/misc/test_crosslayer_workflow.py` (the structural
pytest suite pinning this WP's user-observable behavior: trigger paths,
static-job step wiring, cadence-job secrets sourcing, the zero-real-cases
comment). Nothing else under `tests/` is opened up.

**Steps** (run in order):
```bash
git diff --stat                                              # ONLY the three owned_files entries changed
git diff --stat .github/workflows/conformance.yml             # MUST show no changes
git diff --stat conformance/README.md                         # MUST show no changes
grep -n "secrets:" .github/workflows/crosslayer.yml            # MUST appear only in the cadence job, never the static job
git diff --name-only <mission-base>...<this-lane-branch> > /tmp/wp04-c002-diff.txt
if grep -qx "conformance/README.md" /tmp/wp04-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
! (grep -v '^conformance/' /tmp/wp04-c002-diff.txt | grep -v '^kitty-specs/' | grep -v '^\.github/workflows/crosslayer\.yml$' | grep -v '^tests/cross_cutting/misc/test_crosslayer_workflow\.py$' | grep -q .)
```
The last two lines are this WP's **per-lane C-002 check**, this WP's own
responsibility before requesting review; the cross-lane assembled-diff run
happens again at mission review as the backstop.

## Definition of Done

- [ ] C-011 (ATDD-first): `tests/cross_cutting/misc/test_crosslayer_workflow.py`
      committed RED (failing) before any implementation commit, confirmed
      GREEN at the final commit; both runs' exit codes recorded, and
      collection under CI's exact selector
      (`pytest tests/e2e/ tests/cross_cutting/ -m "not distribution and not windows_ci"`)
      proven
- [ ] T018's input-schema verification recorded in the work log
- [ ] `crosslayer.yml` triggers on PR, path-filtered to both
      `conformance/**` and `src/doctrine/agent_profiles/built-in/**`
- [ ] Static job has no `secrets:` reference; cadence job sources
      `MUSTER_ENDPOINT`/`MUSTER_API_KEY` from repository secrets only
- [ ] Both drift-check call sites (`check-persona-drift.sh`,
      `check-sop-extract-drift.sh`) wired as one-liners
- [ ] Cadence job carries an explicit inline comment stating it has zero real
      cases until WP05/lane-c lands
- [ ] `conformance/crosslayer/README.md` authored; shared
      `conformance/README.md` untouched
- [ ] T021's real CI verification recorded — either a real, independently
      confirmed green run, or an honest, specific blocked-status entry
- [ ] Per-lane C-002 check (T022) passes against this WP's own lane diff
- [ ] No file outside `owned_files` modified; `.github/workflows/conformance.yml`
      and `conformance/README.md` untouched

## Risks

- **Empty cadence job read as evidence FR-005 works**: this is the single
  biggest risk this WP creates for the rest of the mission. The inline
  comment (T019 step 3) exists specifically so a reviewer or operator does
  not mistake "cadence job is green" for "FR-005 is satisfied" before WP05
  lands.
- **Shared-file collision**: `conformance.yml` and `conformance/README.md`
  are both out of scope; a careless edit to either reproduces exactly the
  concurrent-work collision this mission's own Dependencies section goes to
  some length to avoid.
- **Fabricating a CI run**: do not report a green `run_id` that was not
  independently confirmed via `gh run view`. If lane integration has not
  happened yet, report the blocker honestly (T021 step 3).

## Reviewer guidance

- **Reject if** `secrets:` appears anywhere in the static job.
- **Reject if** the path filter covers only `conformance/**` and omits
  `src/doctrine/agent_profiles/built-in/**`.
- **Reject if** the cadence job's zero-case state is not called out with an
  explicit inline comment.
- **Reject if** `.github/workflows/conformance.yml` or
  `conformance/README.md` shows any diff.
- **Reject if** T021's CI run cannot be independently confirmed via
  `gh run view` when claimed as green.
- Confirm the per-lane C-002 check (T022) was actually run.

Implementation command: `spec-kitty agent action implement WP04 --agent claude`

## Activity Log

- **T018 (muster-action input-schema verification)**: confirmed —
  `conformance.yml`'s existing usage matches the shape this WP needs.
  `grep -n "muster-action" .github/workflows/conformance.yml` shows
  `garrison-hq/muster-action@b40681a514f9500f5958b4f9f3efeacd30aae6ca # v1`
  invoked with `with: {command: 'skills run', args: 'conformance/skills/manifest.yaml',
  version: '1.1.0'}`. Input names (`command`/`args`/`version`) reused
  as-is; no new/unreviewed pin introduced. `actions/checkout` pin reused
  identically: `actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803 # v6`.
- **C-011 (ATDD-first) remediation**: this WP's `owned_files`/`create_intent`
  as authored admitted only `.github/workflows/crosslayer.yml` and
  `conformance/crosslayer/README.md` — no path a failing-first test could
  live at. Widened both lists (and the T022 per-lane C-002 gate) to also
  admit `tests/cross_cutting/misc/test_crosslayer_workflow.py`, scoped
  narrowly to that one file. A RED commit (the test alone, workflow file
  absent) was made before any implementation commit on the lane-d branch;
  confirmed failing via
  `uv run python -m pytest tests/cross_cutting/misc/test_crosslayer_workflow.py -m "not distribution and not windows_ci"`
  (13 failed / 1 passed, exit 1). CI-collection proven via the exact
  selector `pytest tests/e2e/ tests/cross_cutting/ -m "not distribution and not windows_ci" --collect-only`,
  which lists all test functions (14 once GREEN). Final GREEN confirmed at
  14 passed, exit 0. Four checks were falsified directly (path-filter
  omission, secrets injection into the static job, `--write` reintroduction
  on the sop-extract-drift call site, zero-real-cases comment removal) —
  each reverted, confirmed the corresponding test(s) failed, then restored.
- **ci-quality.yml path-gap (out of WP04 scope)**: confirmed by reading
  `.github/workflows/ci-quality.yml`'s top-level `on.pull_request.paths`/
  `on.push.paths` (lines 3-60) — neither list contains `conformance/**` nor
  `AGENTS.md`. A PR touching only `conformance/scripts/check-sop-extract-drift.sh`
  would not trigger `ci-quality.yml`'s `tests/e2e/ tests/cross_cutting/`
  job, so WP03's unit tests pinning that script would not re-run against a
  script-only edit. This WP does not fix it: `ci-quality.yml` is not in
  `owned_files`/`create_intent` for any WP in this mission, and this WP's
  own `crosslayer.yml` gate (path-filtered to `conformance/**` +
  `src/doctrine/agent_profiles/built-in/**`) still runs and still catches
  drift for that exact case via the bare `check-sop-extract-drift.sh` call
  site — only `ci-quality.yml`'s own unit-test re-run would be missed, and
  that workflow's path-filter ownership belongs to whoever maintains
  `ci-quality.yml`, not this mission.
- **T021 (real CI verification) — BLOCKED pending lane integration.**
  `git worktree list` at implementation time shows lane-a
  (`kitty/mission-crosslayer-composition-suite-01KYJA33-lane-a`, WP01),
  lane-b (`...-lane-b`, WP02), and lane-c (`...-lane-c`, WP03) all still on
  their own, separate, unmerged lane branches — none has merged into this
  mission's coordination branch
  (`kitty/mission-crosslayer-composition-suite-01KYJA33`), let alone the
  mission target branch. This WP's `crosslayer.yml` calls
  `conformance/scripts/check-persona-drift.sh` (WP01), `conformance/crosslayer/manifest.yaml`
  (WP02), and `conformance/scripts/check-sop-extract-drift.sh` (WP03) —
  none of those three artifacts exist on any branch this WP's own commits
  sit on. A real GitHub Actions run of `crosslayer.yml` right now would
  fail immediately (missing files), which would not be evidence of a real
  defect in this WP's own workflow — it would only be evidence of the
  known, expected lane-isolation gap. **No `run_id` is invented or
  claimed.** What is missing, concretely, before T021 can be completed for
  real: WP01's lane-a merge (personas + `check-persona-drift.sh`), WP02's
  lane-b merge (`manifest.yaml` + cases + control + C-001 fixture), and
  WP03's lane-c merge (`sop-extract.md` + `check-sop-extract-drift.sh`)
  must all land on a single pushed branch alongside this WP's
  `crosslayer.yml`, and a real PR against that combined branch must then be
  opened so the workflow's `pull_request` trigger actually fires. Static,
  locally-runnable proof of this WP's own file (the pytest suite above) is
  complete and GREEN; T021's real-CI half is honestly deferred, not
  fabricated.
