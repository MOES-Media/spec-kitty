# `conformance/` — muster Skills Static Conformance Suite

This directory is the spec-kitty fork's side of the muster ⇄ Spec Kitty
agent-conformance programme, wave 1 (mission `sk-skills-static-conformance`,
seed `MOES-Media/spec-kitty#22`). It statically conformance-checks all 53
built-in `SKILL.md` files under `src/doctrine/skills/*` against
[`@garrison-hq/muster`](https://github.com/garrison-hq/muster)'s skills
adapter, gates every PR and push to `main` on the result via
`.github/workflows/conformance.yml`, and records the programme's design
decisions in [`DECISIONS.md`](./DECISIONS.md).

This suite makes **zero changes to muster or `muster-action`**. It consumes
both as external, pinned, published artifacts — an `npx`-invoked CLI and a
GitHub Action respectively — never as a source dependency in either
direction (C-001).

## Pinned version

**`@garrison-hq/muster@1.1.0`** — exact, never a range. This is the version
`.github/workflows/conformance.yml` pins (`version: '1.1.0'`, C-003, NFR-002)
and the version every command below uses. Do not substitute `latest`,
`^1.1.0`, or `~1.1.0` anywhere in this suite; a floating range would make
the suite's result depend on *when* it runs rather than *what commit* it
runs against.

## Local invocation — the pre-PR command

Run this before opening a pull request:

```sh
npx --offline @garrison-hq/muster@1.1.0 skills run conformance/skills/manifest.yaml \
  && node conformance/scripts/check-manifest-completeness.mjs \
  && echo "conformance: both checks green"
```

This runs, in order:

1. **`muster skills run`** against `conformance/skills/manifest.yaml` — the
   53 real skill cases plus the one FR-005 discrimination control case (54
   `type: static` cases total). Exit `0` means every case's actual result
   matched its declared `expectations.ok`.
2. **The manifest completeness check**
   (`conformance/scripts/check-manifest-completeness.mjs`) — a
   dependency-free Node script (Node stdlib only) that verifies every
   directory under `src/doctrine/skills/*` has exactly one manifest case,
   and vice versa. Exit `0` means the manifest and the real skill tree agree;
   exit `1` names every missing/extra skill by name; exit codes and the
   script's interface are documented in
   `kitty-specs/sk-skills-static-conformance-01KYG7GE/contracts/completeness-check-cli-contract.md`.

Both must exit `0` for the suite to be considered green.

## The two-step cache-warm-then-offline procedure

The pre-PR command above runs with `npx --offline`, which requires the
pinned `@garrison-hq/muster@1.1.0` package to already be present in npm's
local cache — a cold runner (or a cold local machine) has nothing to be
offline *with* yet. The suite is therefore always a two-step procedure:

**Step 1 — cache-warm (network enabled, one-time).** Either of:

- `npm install --no-save @garrison-hq/muster@1.1.0` — installs the exact
  pinned version into the local npm cache without touching this
  repository's own `package.json`/lockfile, **or**
- a pinned `devDependency` on `@garrison-hq/muster@1.1.0` restored via
  `npm ci` — if a project already carries the pin as a `devDependency`, a
  normal `npm ci` cache-restores it with no extra step.

**Step 2 — run fully offline (network disabled).**

```sh
npx --offline @garrison-hq/muster@1.1.0 skills run conformance/skills/manifest.yaml
```

`npx --offline` reaches into the cache step 1 warmed and never touches the
network. This two-step shape is what actually reaches `npm`'s registry
before the offline gate closes — a bare `npx @garrison-hq/muster@1.1.0 ...`
with no prior cache-warm step, run on a genuinely cold runner with no
network, would fail to resolve the package at all. `.github/workflows/conformance.yml`
(this suite's CI gate) performs the equivalent of step 1 implicitly via
`garrison-hq/muster-action@v1`'s own resolution of the pinned `version:`
input on GitHub's networked runner, then executes the pinned CLI — the
two-step shape is the same property, expressed through the Action's own
input contract rather than a literal `npm install` step in the workflow
file.

## Proving the suite discriminates (manual check, not run in CI)

A static checker that reports "pass" on every input is indistinguishable
from a no-op. `conformance/skills/control/name-mismatch/` is a deliberately
broken fixture (frontmatter `name` does not match its directory name) with
`expectations: {ok: false, violations: []}` declared in the manifest — the
case "passes" today because the harness's actual result (`ok: false`)
matches the declared expectation.

To manually prove the suite can also *fail* (documented here, not part of
CI, since it requires temporarily corrupting the manifest):

```sh
# Baseline: exits 0 today.
npx --offline @garrison-hq/muster@1.1.0 skills run conformance/skills/manifest.yaml
echo "baseline exit code: $?"        # 0

# Flip the control case's declared expectation (only that one line):
sed -i.bak 's/ok: false/ok: true/' conformance/skills/manifest.yaml

npx --offline @garrison-hq/muster@1.1.0 skills run conformance/skills/manifest.yaml
echo "flipped exit code: $?"         # non-zero

# Restore:
mv conformance/skills/manifest.yaml.bak conformance/skills/manifest.yaml
git diff --exit-code conformance/skills/manifest.yaml
```

## Manifest completeness check, both ways

```sh
# Baseline:
node conformance/scripts/check-manifest-completeness.mjs
echo "exit code: $?"   # 0

# Induce a mismatch (add an untracked skill directory), re-run, then clean up:
mkdir -p src/doctrine/skills/__temp-probe
echo '---
name: __temp-probe
description: temporary fixture, deleted immediately after use.
---
' > src/doctrine/skills/__temp-probe/SKILL.md
node conformance/scripts/check-manifest-completeness.mjs   # exit 1, names __temp-probe
rm -rf src/doctrine/skills/__temp-probe
node conformance/scripts/check-manifest-completeness.mjs   # exit 0 again
```

## CI gate

`.github/workflows/conformance.yml` runs the same two checks as the local
pre-PR command above, on every pull request and every push to `main`. It
requires **no repository secrets** (C-002) — the static path is fully
offline once muster's own package resolution completes on the runner — so
it also passes on pull requests opened from a fork.

### CI timing (NFR-001 — measured, never asserted)

Per this project's measured-not-asserted CI-budget policy (see
`docs/plans/testing/ci-job-timings.md` for the house pattern), this table
records a real workflow run's `run_id` and actual wall-clock minutes once
one exists. **No ceiling is asserted anywhere in this file** — this is a
record of what one real run took, not a target other runs must meet.

| `run_id` | Wall-clock minutes | Fork-PR, no-secret confirmed? |
|---|---|---|
| [`30227861005`](https://github.com/MOES-Media/spec-kitty/actions/runs/30227861005) | 0.4 | Not observed on a fork PR (this run was on branch `kitty/mission-sk-skills-static-conformance` in `MOES-Media/spec-kitty` directly, not a forked repository) |

**Status as of this writing: met.** A real GitHub Actions run of
`.github/workflows/conformance.yml` completed successfully: `run_id`
`30227861005`, `conclusion: success`, `headBranch`
`kitty/mission-sk-skills-static-conformance`, `createdAt`
`2026-07-27T00:37:15Z`, `updatedAt` `2026-07-27T00:37:39Z` — 24 seconds
(0.4 minutes) wall-clock. Both steps were green: `Run muster skills
conformance (FR-002)` logged `----- muster skills run
conformance/skills/manifest.yaml (exit 0) -----` then `skills: PASS —
54/54 cases passed, 0 failed`; `Verify manifest completeness (FR-007)`
logged `manifest completeness: OK (53 skills + 1 control = 54 cases)`.
Independently reproduce this via `gh run view 30227861005 --repo
MOES-Media/spec-kitty --json conclusion,headBranch,createdAt,updatedAt`.
This figure is byte-identical to the one recorded in WP03's Activity Log
(`kitty-specs/sk-skills-static-conformance-01KYG7GE/tasks/WP03-ci-conformance-workflow.md`)
per WP02's Definition of Done cross-check. No ceiling is asserted here —
this is a record of what one real run took, not a target other runs must
meet.

## Known muster gaps this suite runs on top of

This suite is built against `@garrison-hq/muster@1.1.0` as shipped. Two
latent behaviors of muster's own `skills run` implementation affect what
this suite can and cannot prove, and are not fixed here (out of this
mission's scope guard, C-001 — no muster change):

1. **The manifest is parsed with a bare TypeScript cast — no schema
   validation at runtime.** `doSkillsRun` does
   `const parsed = parseYaml(raw) as { cases: SkillsManifestCase[] };`
   (`src/cli/index.ts:996` at the pinned `v1.1.0` tag) with no Ajv (or
   equivalent) schema check. A structurally malformed
   `conformance/skills/manifest.yaml` — a missing required field, a
   misspelled key, a case with the wrong shape — will not be caught by a
   schema error; it will either silently produce `undefined` fields that
   fail downstream in a less legible way, or pass through unchecked
   depending on where the malformed field is read. Authoring discipline
   (this suite's own manifest convention) is currently the only guard.
2. **`expectations.violations` is never compared — only `expectations.ok`
   is.** The pass/fail rule is exactly
   `const passed = ok === c.expectations.ok;` (`src/cli/index.ts:956` at
   `v1.1.0`). A case's `violations: [...]` list in the manifest is
   **documentation only** — muster does not check that the actual lint
   violations match the declared ones, only that the boolean `ok` outcome
   matches. This suite's manifest always declares `violations: []` for
   every case for this reason; a populated `violations:` list anywhere in
   this manifest would not be enforced and should not be read as a
   guarantee.

A third, related behavior — **behavioral/trigger skill cases are
unconditionally skipped** by the CLI (`results.push({ id: c.id, type:
"behavioral", passed: true, skipped: true });`, `src/cli/index.ts:1010` at
`v1.1.0`; `runTriggerConformance` exists in muster's codebase but is
reachable only from muster's own test suite, not the CLI) — is why this
suite is **static-only**. This suite does not claim, and cannot claim, any
behavioral or trigger-routing coverage. It verifies exactly one thing:
that each built-in skill's `SKILL.md` frontmatter and layout pass muster's
static agentskills.io-derived gates (directory name equals frontmatter
`name`, name matches `^[a-z0-9-]+$` and is ≤64 characters, description is
≤1024 characters). Behavioral coverage is out of scope for this mission by
design (see the mission spec's Scope Guard) and is expected to land in a
later programme mission (M5, `garrison-hq/muster#59`) once muster's CLI
wires up the behavioral path that already exists in its test-only code.

## Decision record

The programme's design decisions (D1–D5: persona adapter vs. projector; the
behavioral-endpoint seam; rule-extraction authoring; mission placement
across repos; the rubric surface) — with their evidence and
recommendations — are recorded in full in [`DECISIONS.md`](./DECISIONS.md),
this programme's single canonical decision record (FR-004). Read it before
proposing an alternative to any of D1–D5; it exists precisely so later
missions do not have to re-litigate them from scratch.

## What this suite does not do

- It does not check behavioral or trigger-routing conformance (see above).
- It does not check agent profiles, directives, or cross-layer composition
  — those are separate, later programme missions (M2–M7), tracked in
  `DECISIONS.md`.
- It does not modify muster, `muster-action`, or any spec-kitty runtime
  source. The diff for this mission touches only `conformance/**` and
  `.github/workflows/conformance.yml`.
- It does not open a pull request against the upstream
  `Priivacy-ai/spec-kitty` repository — this suite is fork-resident for now
  (see `DECISIONS.md` D4's closing note on `OQ-2`).
