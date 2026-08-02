# Skill Trigger-Routing Conformance Suite

This documents the **behavioral** trigger-routing suite authored by mission
`skill-trigger-routing-suite-01KYVRB9` (MOES-Media/spec-kitty issue
https://github.com/MOES-Media/spec-kitty/issues/25):
`conformance/skills/behavioral-manifest.yaml`,
`conformance/skills/trigger-queries/**`, and
`conformance/skills/trigger-evidence/**`.

This is a **different suite** from `conformance/README.md` (one directory
up), which documents the pre-existing **static** suite
(`conformance/skills/manifest.yaml`, parse/validate/layout checks only,
never touches the network). The two suites are independent: this one asks
"does the model route to the right skill?" against a live
`MUSTER_ENDPOINT`; the static suite asks "is this `SKILL.md` well-formed?"
entirely offline. Nothing here extends or duplicates the static suite's
README — there was no prior `conformance/skills/README.md` file for this
document to extend.

## What this suite tests

Five legacy/`spk-*` duplicate skill pairs and a three-member `spk-run-*`
run-family cluster (13 skills, 14 manifest cases including the
discrimination control) are graded by muster's own
`runTriggerConformance`: for each skill, a `shouldTrigger` query set (should
invoke the skill) and a `nearMiss` query set (should not) are run against a
real chat model, `runsPerQuery` times each, and aggregated into a
`triggerRate` per axis.

## Local invocation

Requires Node 22+ and the pinned muster CLI (`@garrison-hq/muster@1.2.1` —
**never** a floating range, C-003):

```sh
npm install --no-save @garrison-hq/muster@1.2.1
npx --offline @garrison-hq/muster@1.2.1 --version   # confirm the exact pin resolved
```

**Offline structural checks** (no `MUSTER_ENDPOINT` required):

```sh
node conformance/scripts/check-trigger-queryset-shape.mjs conformance/skills/trigger-queries/*.yaml
node conformance/scripts/check-twin-phrasing.mjs conformance/skills/trigger-queries/
```

**Live run** (requires `MUSTER_ENDPOINT`/`MUSTER_API_KEY`; unset either and
every behavioral case degrades to `{passed: true, skipped: true}` — a green
run under those conditions verifies nothing about routing and must never be
mistaken for evidence):

```sh
export MUSTER_ENDPOINT=<your endpoint>
export MUSTER_API_KEY=<your key, env only, never committed>
export MUSTER_MODEL=gpt-4o-mini   # optional override; the manifest's own default matches this
npx --offline @garrison-hq/muster@1.2.1 skills run conformance/skills/behavioral-manifest.yaml --json > /tmp/report.json
```

**Discrimination-control check** (FR-004 — always run with an explicit
`--mode`; it is never silently defaulted to either condition):

```sh
node conformance/scripts/check-control-discrimination.mjs /tmp/report.json --mode healthy
```

**Build + validate an evidence artifact** (FR-005):

```sh
node conformance/scripts/build-evidence-artifact.mjs /tmp/report.json /tmp/evidence.json
node conformance/scripts/check-evidence-artifact-shape.mjs /tmp/evidence.json
```

**Full local pre-merge check** (offline-only portion; the live run and its
control-discrimination/evidence-artifact steps require a real endpoint and
are run separately, as above):

```sh
node conformance/scripts/check-trigger-queryset-shape.mjs conformance/skills/trigger-queries/*.yaml \
  && node conformance/scripts/check-twin-phrasing.mjs conformance/skills/trigger-queries/ \
  && node conformance/scripts/check-evidence-artifact-shape.mjs conformance/skills/trigger-evidence/*.json
node_chain_status=$?

command grep -rE '(sk-|api[_-]?key\s*[:=]\s*["\047][A-Za-z0-9]{16,})' conformance/skills/behavioral-manifest.yaml .github/workflows/skill-trigger-routing.yml
grep_status=$?

if [ "$node_chain_status" -eq 0 ] && [ "$grep_status" -eq 1 ]; then
  echo "conformance: all local checks green"
else
  echo "conformance: FAILED (node checks exit=$node_chain_status, credential-grep exit=$grep_status)"
  exit 1
fi
```

The two status codes are captured on their own lines and tested
separately for exactly this reason: a single `A && B && C && D; [ $? -eq 1
]` chain is indistinguishable from success when `D` (the credential grep,
which is expected to exit `1` for "no leak found") happens to share its
success exit code with an *earlier* command's failure — any of `A`/`B`/`C`
failing with exit `1` short-circuits the chain, and `$?` after the `;` is
that same `1`, which the old form then read as "green." Observed against a
real failing case (`shouldTrigger` truncated to 7 entries): the old form
printed `trigger-queryset-shape: FAIL` immediately followed by
`conformance: all local checks green` and exited `0`; the form above
prints `conformance: FAILED (node checks exit=1, credential-grep exit=1)`
and exits `1` for the identical input, and still prints `conformance: all
local checks green` (exit `0`) against the real, passing files.

## Cadence workflow

`.github/workflows/skill-trigger-routing.yml` runs the live sequence above
on `workflow_dispatch` only — **never** `pull_request` (this workflow needs
live-model credentials, `MUSTER_ENDPOINT`/`MUSTER_API_KEY`, injected as
GitHub Actions repository secrets, never written to any file in this repo).
Cron/schedule cadence is explicitly out of scope for this mission
(deferred to a later mission, garrison-hq/muster-action#2); triggering a
run today is a manual `workflow_dispatch` action.

A healthy run's exit code is expected to be **1**, not 0 — the
discrimination-control case is designed to fail (`passed: false`) even on a
fully healthy endpoint, so a healthy run "including its required control"
legitimately returns a non-zero CLI exit code (garrison-hq/muster#77). The
"Run behavioral trigger-routing manifest" step's shell *does* assert on
that bare exit code — it must, or Actions' default `bash -e {0}` would
abort the job on that very exit 1 before any later step ever ran — but the
assertion only rejects a code neither `0` nor `1`; both legitimate values
are let through unexamined. Every actual pass/fail *verdict* in this
workflow (the FR-003 skip-guard, the FR-004 discrimination check, the
FR-005 evidence-artifact shape check) comes from a later step inspecting
the JSON report's per-case fields, never from the bare `skills run` exit
code itself.

## `[CONVENTION]` — twin-phrasing near-miss sets (D-1)

This mission's near-miss query sets follow a convention it invented and
this suite is, for now, the only place it is written down: for a duplicate
pair (or a run-family cluster), each skill's `nearMiss` set includes at
least one phrase drawn verbatim from its twin's (or siblings')
`shouldTrigger` set. This is the sharpest available discrimination test —
if a model can correctly decline to trigger skill A even when given a
query phrased exactly the way skill B's own should-trigger queries are
phrased, that is much stronger evidence of real discrimination than an
unrelated filler near-miss query would be.

This convention does not yet exist in muster's own shipped rubric
(`docs/rubric/skills-trigger-taxonomy.md` at `v1.2.1` has no "twin"
language). The intended long-term home for this methodology is a small
addendum PR to that file in `garrison-hq/muster` — a separate repository
this mission's own diff never touches — so a future mission authoring
query sets for a different confusable cluster inherits the convention
without re-deriving it from this README. Until that addendum PR lands,
this section is the convention's only written specification, and is tagged
`[CONVENTION]` rather than treated as an established upstream rubric rule.

## `[LIMITATION]` — single-tool bias is structural at muster `1.2.1` (muster#82)

`runBehavioralSkillCase` (`src/cli/index.ts:1414-1465`, muster commit
`16f0d34c3126fab5df2ee0b6e1e304a4d9bcb8e3`, tag `v1.2.1`) builds exactly
**one** `ToolDefinition` per case — a fixed-length-1 array literal at
`:1458-1463`, derived from the target skill's own frontmatter (or the
rigged-impossible substitution, for the control case). `SkillsManifestBehavioralCase`
(`:1254`) has no field for a second, distractor tool at all.

**Consequence**: this suite's `shouldTrigger`/`nearMiss` axes can only ever
ask the model "should you call the one tool you were given?" — never "does
tool A win over tool B when both are offered?". A model that always calls
whatever single tool it is handed would score identically to a model making
a genuinely well-reasoned choice among competing candidates. This suite's
near-miss axis can therefore detect an **actively repellent** description
(one the model correctly declines even when it is the only option offered)
but cannot detect **fine-grained quality differences** among plausible
candidates the way a real multi-tool routing decision would.

This is a structural limitation of muster `1.2.1`'s manifest schema, not a
gap this mission's YAML authoring can close (garrison-hq/muster#82,
tracked upstream). Lifting it requires a muster-side change — e.g. a
`distractorTools`-shaped field on `SkillsManifestBehavioralCase` — tracked
the same way D-1's rubric addendum is tracked: as a dependency note for a
future mission, not this mission's own diff (no code under `src/core/` or
`src/adapters/` is touched here, per this mission's own scope guard).

## Findings

Duplicate-pair/run-family cases whose near-miss axis trigger rate meets or
exceeds its threshold are filed as spec-kitty GitHub issues (never fixed by
editing a `SKILL.md` — this suite reports, it does not remediate) and
indexed here by full URL:

- [MOES-Media/spec-kitty#43](https://github.com/MOES-Media/spec-kitty/issues/43)
  — live `gpt-4o-mini` run (`runsPerQuery: 3`, `threshold: 0.5`,
  `conformance/skills/trigger-evidence/2026-08-01T23-16-10.435Z.json`)
  found 8 of 13 duplicate-pair/run-family cases at or above the near-miss
  threshold: `spk-doctrine-profile-load` (0.625), `spec-kitty-runtime-next`
  (0.625), `spk-run-next` (0.625), `spec-kitty-runtime-review` (0.542),
  `spk-run-review-wp` (0.500), `spk-run-implement-review` (0.625),
  `spec-kitty-git-workflow` (0.625), and `spk-admin-git-workflow` (0.625).
  This 8-of-13 finding is real (the same run's rigged-impossible control
  shows `passed: false, runsErrored: 0` — the grader is discriminating
  correctly) and is the first substantive thing this suite's checks have
  said about their subject. The three-member run-family cluster itself
  (siblings distinguishing themselves from each other, not from a legacy
  twin) showed a lower near-miss rate in this run (`spk-run-next-run-family`
  0.375, `spk-run-review-wp-run-family` 0.250,
  `spk-run-implement-review-run-family` 0.250, none over threshold).

  **What the data does not support**: attributing this split to legacy-vs-
  `spk-*` **naming** overstates what a single-tool grading run (see
  `[LIMITATION]` above) can separate from two confounds. First,
  `0.625 = 15/24`, and only **one** of `spk-run-next`'s eight near-miss
  queries is its borrowed twin phrase (`"run the next step"`,
  `spk-run-next-duplicate-pair-queries.yaml`) — its maximum possible
  contribution is `3/24 = 0.125`; at least `12/24` of the observed rate
  comes from the other seven, unrelated filler near-miss queries. Under
  this suite's own documented single-tool bias, a model given one
  action-shaped tool and an action-shaped filler query will tend to call
  it regardless of naming. Second, `spk-run-next` appears twice against the
  same tool with different near-miss sets and different rates —
  duplicate-pair `0.625` vs. run-family `0.375` — and that split tracks
  **query genre** (the duplicate-pair set's near-miss queries are
  imperative workflow actions; the run-family set's are mostly explanatory
  questions), not pair membership. The run-family cluster's lower rate is
  therefore weaker evidence of "no naming confusion among siblings" than it
  looks: it may just be asking gentler questions.

  **A genuinely contaminated fixture**:
  `spk-admin-git-workflow-duplicate-pair-queries.yaml:23` uses `"git
  workflow"` as its twin-borrowed near-miss phrase (correctly borrowed,
  per the `[CONVENTION]` above, from `spec-kitty-git-workflow`'s own
  `shouldTrigger` set) — but `"git workflow"` is also a verbatim substring
  of `spk-admin-git-workflow`'s **own** tool description (`"Operate Spec
  Kitty git workflows, worktrees, safe commits, merge preflights, stale
  state checks, and recovery."`). A query drawn from the target tool's own
  description cannot discriminate anything; this probe should be fixed or
  excluded, not read as evidence. The finding survives its removal:
  crediting it with the most generous plausible outcome (all 3 of its runs
  triggered) and recomputing over the remaining 7 near-miss queries still
  gives `spk-admin-git-workflow` a `(15-3)/(24-3) = 12/21 = 0.571` near-miss
  rate — still over the `0.5` threshold.

  **The claim the data does support, and that is falsifiable**: in both of
  this run's one-sided pairs, the side that passed is the verbose legacy
  skill carrying an explicit `Does NOT handle:` clause in its description,
  and the side that failed is the terse `spk-*` twin without one —
  `ad-hoc-profile-load` (0.208, pass) vs. `spk-doctrine-profile-load`
  (0.625, fail); `spec-kitty-implement-review` (0.250, pass) vs.
  `spk-run-implement-review` (0.625, fail). This is bounded by the same
  `[LIMITATION]` above (a real multi-tool routing decision might score
  differently), but it is a specific, checkable claim about description
  content that the naming attribution was not.

  **This claim is scoped to the two one-sided pairs above, not to every
  skill with a `Does NOT handle:` clause**: three of the five legacy
  skills in this run's finding also carry that clause and still failed —
  `spec-kitty-runtime-next` (0.625), `spec-kitty-runtime-review` (0.542),
  and `spec-kitty-git-workflow` (0.625). The clause is therefore not a
  general predictor of passing; the claim above holds only for the
  specific verbose-vs-terse comparison within each of the two one-sided
  pairs it names.
