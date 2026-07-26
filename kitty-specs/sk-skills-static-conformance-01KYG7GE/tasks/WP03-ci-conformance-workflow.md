---
work_package_id: WP03
title: CI conformance workflow
dependencies: []
requirement_refs:
- FR-003
- FR-007
- C-001
- C-002
- C-003
- NFR-002
planning_base_branch: kitty/mission-sk-skills-static-conformance
merge_target_branch: kitty/mission-sk-skills-static-conformance
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-sk-skills-static-conformance. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-sk-skills-static-conformance unless the human explicitly redirects the landing branch.
subtasks:
- T011
- T012
- T013
- T014
history:
- timestamp: '2026-07-26T23:20:00Z'
  event: created
  by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: node-norris
authoritative_surface: .github/workflows/
create_intent:
- .github/workflows/conformance.yml
execution_mode: code_change
model: ''
owned_files:
- .github/workflows/conformance.yml
role: implementer
tags: []
tracker_refs: []
---

# WP03 — CI conformance workflow

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

Author `.github/workflows/conformance.yml`: a PR/push-to-main gate that runs
`garrison-hq/muster-action@v1` against the skills manifest, followed by the
FR-007 completeness-check step, with no secrets and an exact version pin.
Then obtain a real green run of it and hand its `run_id` and wall-clock
minutes to WP02 (a different lane), which needs that number to close its own
Definition of Done.

This is the **sole** file in this WP's write scope. Do not touch anything
under `conformance/**` — that is lane-a's (WP01/WP02) territory, and this
mission's lane split has zero file overlap by design.

## Context (read first)

- Spec: `kitty-specs/sk-skills-static-conformance-01KYG7GE/spec.md`
  — FR-003, FR-007, C-002, C-003; Acceptance Scenarios 3, 8, 9, 12, 13
- Plan: `kitty-specs/sk-skills-static-conformance-01KYG7GE/plan.md`
  — IC-06; Work-Package Outline's WP03 section (no dependency on WP01/WP02
  source, only on the contract file); Verification Strategy step 4
- Research: `kitty-specs/sk-skills-static-conformance-01KYG7GE/research.md`
  — §5 (`muster-action@v1` input surface — flagged as **unverified against
  the live action.yml**, see T011 below)
- Data model: `kitty-specs/sk-skills-static-conformance-01KYG7GE/data-model.md`
  — `ConformanceWorkflow` (trigger, steps, invariants)
- Contract (this WP's only dependency — not WP01's source):
  `kitty-specs/sk-skills-static-conformance-01KYG7GE/contracts/completeness-check-cli-contract.md`
  §"CI wiring (the lane-b side of this contract)" — gives you everything
  needed about the completeness-check step: its invocation, working
  directory, and exit-code meaning, with zero need to read
  `conformance/scripts/check-manifest-completeness.mjs`'s own source.
- Quickstart: `kitty-specs/sk-skills-static-conformance-01KYG7GE/quickstart.md`
  §4 (the real-CI-run verification procedure this WP must complete)

**Hard rules for the whole WP**:

1. Touch ONLY `.github/workflows/conformance.yml` — this WP's share of
   **C-001** (no spec-kitty runtime code changes).
2. No `secrets:` reference anywhere in the file (C-002) — the whole workflow
   is fully offline/static and must pass on a fork PR with zero repository
   secrets available.
3. The `version:` input to `muster-action@v1` must be an exact, quoted string
   (`'1.1.0'`) — never `^1.1.0`, `~1.1.0`, or `latest` (C-003, Acceptance
   Scenario 13). This exact pin is also the structural precondition for
   **NFR-002** (deterministic given a pinned version) — a floating range
   would make CI's result depend on when it runs, not just what commit it
   runs against.
4. **Before finalizing this file**, verify the actual shipped input schema of
   `garrison-hq/muster-action@v1` — research.md §5 explicitly flags that the
   `command`/`args`/`version` input names below are inferred from a design
   briefing (`briefings/muster-github-action.md` in the muster repo), not
   confirmed against the real Action. If the live `action.yml` uses different
   input names (e.g. a positional `manifest` input instead of `args`), adjust
   this file to match the real Action — this mission has no latitude to
   change muster-action itself, only to consume it correctly as shipped.

## Subtasks

### T011 — Verify the real `muster-action@v1` input schema

**Purpose**: Close research.md §5's flagged risk before writing the workflow
step that depends on it.

**Steps**:
1. Inspect the real `garrison-hq/muster-action` repository's `action.yml` at
   the `v1` tag (e.g. `gh api repos/garrison-hq/muster-action/contents/action.yml?ref=v1`
   or by cloning/fetching the tag) to confirm the actual input names.
2. Compare against the briefed shape:
   ```yaml
   - uses: garrison-hq/muster-action@v1
     with:
       command: 'skills run'
       args: 'conformance/skills/manifest.yaml'
       version: '1.1.0'
   ```
3. If the real schema differs, record the actual input names in the work log
   and use them in T012 instead of the briefed names. If it matches, record
   that confirmation in the work log.

**Files**: none (verification only).
**Validation**: work log states either "confirmed: matches briefing" or
"corrected: real input names are X/Y/Z", with the source consulted.

---

### T012 — Author `.github/workflows/conformance.yml` (IC-06, FR-003, FR-007)

**Purpose**: The PR/push-to-main gate.

**Steps**:
1. Create `.github/workflows/conformance.yml` triggering on `pull_request`
   (any branch) and `push` to `main` (spec Acceptance Scenario 3).
2. Step 1: checkout (`actions/checkout@v6`, matching this fork's existing
   workflow convention — check `.github/workflows/*.yml` in this checkout for
   the exact version already in use and match it, don't introduce a new
   pin).
3. Step 2: `garrison-hq/muster-action@v1` using T011's confirmed input names,
   with `command`/subcommand equivalent to `skills run`, the manifest path
   `conformance/skills/manifest.yaml`, and `version: '1.1.0'` exact.
4. Step 3: `node conformance/scripts/check-manifest-completeness.mjs` — per
   the CLI contract's "CI wiring" section, this needs nothing beyond the
   script's stable path and exit-code contract (`0` = complete, `1` =
   mismatch). Place this step after step 2 so both a static-gate failure and
   a completeness failure are visible in one job's log.
5. Do not add a `setup-node` step — confirm the `ubuntu-latest` runner has a
   working `node` on `PATH` after the `muster-action` step completes (this
   mission does not add one, per plan.md IC-06's risk note); if it does not,
   record that as a blocker rather than silently adding a toolchain step this
   plan did not scope.
6. No `secrets:` token anywhere in the file (hard rule 2).
7. `version:` is the exact quoted string `'1.1.0'` (hard rule 3) — grep the
   final file for `version:\s*['"]?1\.1\.0['"]?$` with no `^`/`~`/`latest` to
   confirm before committing.

**Files**: `.github/workflows/conformance.yml` (new).
**Validation**: file has the two triggers, three steps in order, no secrets,
exact version pin — confirmed by inspection and the grep in step 7.

---

### T013 — Real CI verification (quickstart.md §4, mandatory)

**Purpose**: This cannot be simulated locally — it requires an actual GitHub
Actions run on the mission's own PR.

**Steps**:
1. Once this mission's changes (including WP01's manifest/script and this
   WP's workflow file) are on a PR against `MOES-Media/spec-kitty` on branch
   `kitty/mission-sk-skills-static-conformance`, confirm
   `.github/workflows/conformance.yml` actually triggers.
2. Confirm both steps (`muster-action@v1` skills run, then the FR-007
   completeness check) show green in that run's logs.
3. Record that run's `run_id` and actual wall-clock minutes — this is the
   number WP02's T010 needs to close its own Definition of Done. Communicate
   it (work log entry visible to WP02, or directly if the same session is
   handling both WPs) rather than letting it sit only in the GitHub UI.
4. If the PR is opened from a fork (no repository secrets available),
   explicitly confirm the job still completes green with no secret-related
   failure (C-002, Acceptance Scenario 12).

**Files**: none new.
**Validation**: work log records the real `run_id`, real wall-clock minutes,
and (if observable) the fork-PR-no-secrets confirmation.

---

### T014 — WP03 verification (Definition of Done gate)

**Steps** (run in order):
```bash
git diff --stat                                        # ONLY .github/workflows/conformance.yml changed
git diff --stat conformance/                            # MUST show no changes — lane-a is not this WP's concern
grep -n "secrets:" .github/workflows/conformance.yml     # MUST return nothing
grep -n "version:" .github/workflows/conformance.yml     # MUST show '1.1.0' exact, no ^ or ~ or latest
```

## Definition of Done

- [ ] T011's input-schema verification recorded in the work log (confirmed or
      corrected against the real `action.yml`)
- [ ] `.github/workflows/conformance.yml` triggers on PR and push-to-main
- [ ] `muster-action@v1` step uses the real, verified input names with
      `version: '1.1.0'` exact (never a range)
- [ ] Completeness-check step runs `node conformance/scripts/check-manifest-completeness.mjs`
      after the muster step, per the CLI contract, with no knowledge of the
      script's internals beyond that contract
- [ ] No `secrets:` reference anywhere in the file
- [ ] No `setup-node` step added without recording why it was needed (or
      confirmed unnecessary)
- [ ] Real GitHub Actions run recorded: `run_id`, wall-clock minutes, both
      steps green (T013) — handed off for WP02's T010
- [ ] No file outside `owned_files` is modified; `conformance/**` untouched
      by this WP

## Risks

- **Unverified Action schema**: research.md §5 explicitly flags that the
  input names are inferred from a design briefing, not the live
  `action.yml`. T011 exists specifically to close this before T012 commits
  to names that might be wrong. If the real schema differs and this is
  discovered only after T012, redo T012 with the corrected names before
  requesting review — do not ship the briefed names unverified.
- **Missing `node` on `PATH` after the Action step**: plan.md IC-06 flags
  this as a real risk this plan does not add a `setup-node` step to cover.
  If the completeness-check step fails purely because `node` is unavailable,
  record this as a blocker rather than silently patching around it with a
  step this WP's scope did not anticipate.
- **Cross-WP handoff**: WP02's T010 is blocked on this WP's T013 output. This
  WP has no `dependencies` on WP01/WP02 in `wps.yaml` (by design — lane-b
  starts and runs independently), but the mission-level sequencing still
  needs this WP's real run_id to exist before WP02 can be signed off. Surface
  the run_id and minutes clearly in the work log so this handoff doesn't get
  lost.

## Reviewer guidance

- **Reject if** the workflow file references `secrets:` anywhere.
- **Reject if** `version:` is not the exact string `'1.1.0'` (a range or
  `latest` fails C-003 and Acceptance Scenario 13 outright).
- **Reject if** T011's verification was skipped — i.e., the PR uses the
  briefed `command`/`args`/`version` input names with no record of having
  checked them against the real `action.yml`.
- **Reject if** the work log has no real `run_id`/wall-clock-minutes entry
  from an actual GitHub Actions run — a workflow file that has never
  actually executed does not satisfy this WP's Definition of Done.
- Confirm `git diff --stat` shows exactly one file changed:
  `.github/workflows/conformance.yml`.
- Confirm the completeness-check step's `run:` line matches the CLI
  contract's documented invocation verbatim
  (`node conformance/scripts/check-manifest-completeness.mjs`), with no
  extra flags or working-directory assumptions beyond "repository root,"
  which GitHub Actions provides by default after checkout.

Implementation command: `spec-kitty agent action implement WP03 --agent claude`
