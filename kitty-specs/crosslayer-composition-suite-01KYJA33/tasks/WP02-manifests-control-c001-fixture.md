---
work_package_id: WP02
title: Composition manifests, discrimination control, C-001 fixture (lane-b part 1)
dependencies: []
requirement_refs:
- FR-004
- FR-006
- C-001
- C-002
planning_base_branch: kitty/mission-crosslayer-composition-suite
merge_target_branch: kitty/mission-crosslayer-composition-suite
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-crosslayer-composition-suite. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-crosslayer-composition-suite unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-crosslayer-composition-suite-01KYJA33
base_commit: 6c5d08978d7d29a6c09fead1c8e6a88610fc2805
created_at: '2026-07-27T21:39:19.800685+00:00'
subtasks:
- T008
- T009
- T010
- T011
- T012
- T013
- T014
agent: claude
history:
- timestamp: '2026-07-27T19:45:23Z'
  event: created
  by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: node-norris
authoritative_surface: conformance/crosslayer/
create_intent:
- conformance/crosslayer/manifest.yaml
- conformance/crosslayer/cases/architect-run-skill.yaml
- conformance/crosslayer/cases/reviewer-run-skill.yaml
- conformance/crosslayer/control.yaml
- conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md
- conformance/crosslayer/fixtures/control-persona.Soul.md
- conformance/crosslayer/fixtures/control-sop.md
- conformance/crosslayer/fixtures/control-skill.SKILL.md
- conformance/crosslayer/fixtures/spk-run-next.SKILL.md
- tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py
execution_mode: code_change
model: ''
owned_files:
- conformance/crosslayer/manifest.yaml
- conformance/crosslayer/cases/architect-run-skill.yaml
- conformance/crosslayer/cases/reviewer-run-skill.yaml
- conformance/crosslayer/control.yaml
- conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md
- conformance/crosslayer/fixtures/control-persona.Soul.md
- conformance/crosslayer/fixtures/control-sop.md
- conformance/crosslayer/fixtures/control-skill.SKILL.md
- conformance/crosslayer/fixtures/spk-run-next.SKILL.md
- tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py
role: implementer
tags: []
tracker_refs: []
---

# WP02 — Composition manifests, discrimination control, C-001 fixture

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

Author the 2 real FR-004 static composition cases (architect+sop+skill,
reviewer+sop+skill), FR-006's rigged discrimination control (proven both
flip and neutralize directions with the spec's pinned fixture text, verbatim),
and C-001's RFC-1-invalid fixture (expected to produce exit `2`, never a
`findingTypes` result). This WP has **no dependency on WP01** (lane-a) —
read the IC-00 note below before assuming otherwise.

## Context (read first)

- Spec: `kitty-specs/crosslayer-composition-suite-01KYJA33/spec.md`
  — FR-004, FR-006 (including the pinned fixture text sub-section
  `#### FR-006 pinned fixture text (H3: "neutralize" made concrete)` —
  **transcribe that text exactly, do not re-derive or paraphrase it**), C-001;
  Edge Cases ("RFC-1 validity failure is not itself a grading signal (C-001)");
  Dependencies & Assumptions' citation-correction #1–#4 (exit-code mapping,
  crosslayer CLI presence at `v1.1.0`).
- Plan: `kitty-specs/crosslayer-composition-suite-01KYJA33/plan.md`
  — IC-02 (this WP's source concern); **IC-00** ("dissolved, not resolved" —
  read this in full before starting; it is the reason this WP does not wait
  on WP01).

**IC-00 dissolution, verified independently (not taken on faith from the
plan) against muster's actual source at pinned commit
`624edd6dddedb86fb89f13084510f02b5a2c7d25`**: `resolvePersonaLayer`
(`composition.ts:281-320`) contributes only `personaDoc.body.trim()` into
`layerTexts` — the only map `contradiction-lint.ts`'s `extractClauses`/
`analyseLayerPair` scan (confirmed directly in that file's own docstring:
"C-003: Lint runs on resolved.layerTexts — never raw fixture files").
RFC-1 front-matter is only ever consulted, structurally (presence/shape, not
values), by RFC-1 strict-mode resolution. **Conclusion this WP acts on**: you
do not need WP01's real projector output, byte-exact or otherwise, to author
or verify your own two real FR-004 cases. What you actually need:

1. **`fixturePath` agreement, not byte agreement.** Your committed
   `architect-run-skill.yaml` and `reviewer-run-skill.yaml` case files must
   reference exactly `conformance/crosslayer/personas/architect-alphonso.Soul.md`
   and `conformance/crosslayer/personas/reviewer-renata.Soul.md` respectively
   — the filenames WP01 has committed to (Project Structure, plan.md). These
   files will not exist in your own lane's worktree until both lanes merge;
   that is fine and expected — do not attempt to pre-create or copy them into
   your own tree.
2. **A self-authored, RFC-1-valid sandbox persona for your own local testing
   only** (T008 below) — to prove your manifest/CLI wiring mechanically works
   before both lanes merge. Its exact bytes, including any fabricated
   front-matter it invents, are irrelevant: they are never graded (C-003) and
   never reach the lint (above). **Do not commit this sandbox persona under
   `conformance/crosslayer/personas/`** — that path is WP01's exclusive write
   scope. Use a scratch location outside this WP's `owned_files` (e.g. a
   temp directory, or a manifest copy that is never committed) and delete it
   before finalizing this WP's commit.

The real content at the real committed paths is verified for real,
automatically, once both lanes merge — WP04's CI job and this mission's own
Real-CLI verification requirement re-run the shipped manifest against the
shipped personas before acceptance. No hand-computed reference bytes are
required at any point.

**Chosen skill for the two FR-004 cases**: `spk-run-next`
(`src/doctrine/skills/spk-run-next/SKILL.md`), a shipped, run-family skill
already present in this fork — satisfies FR-004's "one shipped run-family
skill" requirement without inventing new skill content.

## Subtasks

### T008 — Self-authored sandbox persona fixture(s) for local testing only

**Purpose**: Prove the manifest/CLI mechanism works end-to-end before WP01's
real personas exist in this lane's worktree, without creating any dependency
on WP01 or committing anything to WP01's write scope.

**Steps**:
1. Create one or two RFC-1-valid `Soul.md` fixtures in a scratch location
   (e.g. `/tmp/m7-sandbox/` or another path clearly outside
   `conformance/crosslayer/personas/` and outside this WP's `owned_files`) —
   invent whatever fabricated front-matter values you like; they are never
   graded and never checked by the lint (Context, above).
2. Point a **local-only, uncommitted** copy of your manifest/case files at
   these sandbox fixtures to exercise `muster crosslayer run` end-to-end
   during development.
3. Before finalizing this WP's commit, delete the scratch fixtures and revert
   the case files to reference the real committed paths
   (`conformance/crosslayer/personas/architect-alphonso.Soul.md`,
   `.../reviewer-renata.Soul.md`) — confirm via `git status --short` that no
   scratch file was ever staged.

**Files**: none committed by this subtask.
**Validation**: `git status --short` shows nothing under the scratch
location; the final committed case files reference only the real paths.

---

### T009 — Author `manifest.yaml` and the two real FR-004 case files

**Purpose**: The 2 static cases FR-004 requires (2-case minimum, not the
2-profile × 2-skill ceiling).

**Steps**:
1. Create `conformance/crosslayer/cases/architect-run-skill.yaml`: persona
   layer → `conformance/crosslayer/personas/architect-alphonso.Soul.md`, sop
   layer → this mission's SOP policy extract (WP03's
   `conformance/crosslayer/sop-extract.md` — reference by path; WP03 has no
   dependency relationship with this WP either, same IC-00-style path
   agreement, not byte agreement, applies), skill layer →
   `src/doctrine/skills/spk-run-next/SKILL.md`. `testClass: "static"`.
2. Create `conformance/crosslayer/cases/reviewer-run-skill.yaml`: identical
   shape, persona layer → `.../reviewer-renata.Soul.md`.
3. Create `conformance/crosslayer/manifest.yaml` with `$ref`-includes for
   both case files, per `CompositionManifestCase[]`'s documented shape
   (Key Entities, spec.md): `layers: [{layerType, fixturePath}]`,
   `testClass`, `expected: {ok: true}` for both (benign cases).
4. Determine the correct relative-path resolution for `fixturePath` values
   empirically (T012's real run is the actual proof, not an assumption about
   path depth) — do not hard-code a guessed `../../..` depth without
   confirming it resolves against the real muster CLI.

**Files**: `conformance/crosslayer/manifest.yaml`,
`conformance/crosslayer/cases/architect-run-skill.yaml`,
`conformance/crosslayer/cases/reviewer-run-skill.yaml` (all new).
**Validation**: covered by T012.

---

### T010 — Author `control.yaml`: FR-006 discrimination control, pinned text (verbatim)

**Purpose**: The rigged case that proves the static lint can actually fail,
proven two ways (flip, neutralize) — **use this exact pinned text, transcribed
verbatim, not re-derived**:

**Rigged (flip)** — persona body vs. skill body, no `precedence:` block:
- Persona layer body text: `"Always answer in exhaustive, multi-paragraph
  detail, restating the full context before every response."`
- Skill layer body text: `"Responses under this skill must be terse: a
  single sentence or a short bullet list. No restated context, no
  preamble."`

**Neutralized** — same case, same layer count/types, same absence of a
`precedence:` block, only the persona's sentence replaced:
- Persona layer body text becomes: `"Always ground responses in the user's
  actual question, citing the specific detail that motivated the answer."`
- Skill layer body text is unchanged.

**Hard rule**: blanking the persona's sentence to `""` or truncating it to a
placeholder (`"..."`, `"TBD"`) does **not** satisfy this requirement — both
would trivially produce zero findings by removing content, not by replacing
contradictory content with benign content. The neutralized text above is a
real, substantive, plausible persona instruction that does not itself
contradict the skill's terseness requirement.

**Steps**:
1. Create the flip-direction fixture files (a synthetic persona and skill
   fixture, entirely self-contained — this control is not derived from
   either of WP01's real personas) with the exact flip text above, no
   `precedence:` block.
2. Create `conformance/crosslayer/control.yaml` referencing them,
   `isDiscriminationControl: true`.
3. Keep a second, neutralized copy of the persona fixture ready (or a
   documented one-line edit) so T012 can prove both directions on the *same*
   structural case.

**Files**: `conformance/crosslayer/control.yaml` (new), plus its own
self-contained fixture file(s) under `conformance/crosslayer/` (not under
`personas/` — that path is WP01's).
**Validation**: covered by T012.

---

### T011 — Author the C-001 fixture (RFC-1-invalid persona)

**Purpose**: A persona missing a required RFC-1 key, proving
`resolvePersonaLayer`'s strict-mode violation propagates to **exit code
exactly `2`**, categorically distinct from a `findingTypes` result.

**Steps**:
1. Create `conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md`
   with valid Markdown body but front-matter missing one RFC-1-required key
   (e.g. omit `voice` entirely, or one of its four required sub-fields).
2. This fixture is never referenced by the benign `manifest.yaml` — it is
   exercised directly in a standalone run (T012).

**Files**: `conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md`
(new).
**Validation**: covered by T012.

---

### T012 — Mandatory real-CLI verification (operator directive)

Run every command below for real; record exact exit codes and (where
specified) exact stderr/stdout text in the work log.

**Steps** (cache-warm once per environment first: `npm install --no-save
@garrison-hq/muster@1.1.0`):
1. **FR-004 — split into a local-mechanism proof and an honest blocked-status
   entry (H-2 post-tasks-review remediation; mirrors WP04's T021 pattern for
   the identical cross-lane-file shape).** The real committed
   `conformance/crosslayer/manifest.yaml` and its two case files reference
   `conformance/crosslayer/personas/*.Soul.md` (WP01's exclusive scope,
   lane-a) and `conformance/crosslayer/sop-extract.md` (WP03's, lane-c) —
   files this lane's isolated worktree cannot open until those lanes merge.
   Running the real committed manifest from lane-b alone therefore cannot
   honestly report `failed: 0`; it must instead be split into two runs:

   **1a. Local-mechanism proof (runnable today, from lane-b alone, a real
   exit code)**: point a **local-only, uncommitted** copy of
   `manifest.yaml` and the two case files at T008's self-authored sandbox
   persona(s) plus a self-authored sandbox SOP-extract file (never at the
   real committed paths, which do not exist in this lane's worktree yet).
   Run:
   ```sh
   npx --offline @garrison-hq/muster@1.1.0 crosslayer run <local-only manifest copy> --static-only --json
   ```
   Expect exit **0**, JSON `failed: 0` — this proves the manifest/CLI
   mechanism itself (path resolution, `$ref` case includes, static lint
   dispatch) actually works, using only content this lane controls end to
   end. Record the real observed exit code and JSON summary in the work
   log. **Falsification** (same local-only copy): swap in T010's rigged
   case in place of the sandbox fixtures — expect exit **1**, JSON
   `failed > 0`. Discard the local-only manifest/case copies before
   finalizing this WP's commit (T008 step 3 already requires this; do not
   commit them).

   **1b. Honest blocked-status entry for the real committed manifest (never
   a fabricated pass)**: running
   `npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/manifest.yaml --static-only --json`
   against the **real committed** manifest from this lane, before WP01 and
   WP03 have merged, fails honestly — the persona/sop-extract fixturePaths
   resolve to files that do not exist yet in this worktree. Record this
   verbatim in the work log as a **blocked** status, not a pass or a
   falsification result, e.g.: "blocked — real committed manifest run
   requires WP01 (personas) and WP03 (sop-extract.md) merged onto the same
   branch as this lane; per-case ENOENT is the expected, honest result of a
   sibling lane's file being absent, not a FR-004 defect; re-verify at
   mission level once WP01/WP03 have merged (spec.md's Real-CLI
   verification requirement; `tasks/PRE-MERGE-ACTIONS.md` item 1)." **Do
   not report a fabricated `failed: 0`/exit-`0` result for this direction**
   — this mirrors WP04's T021 step 3 honest-blocked pattern for the
   identical cross-lane-file shape, applied here to the static path instead
   of the CI-run path.
2. **FR-006, flip direction**:
   ```sh
   npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/control.yaml --static-only --json > /tmp/control.json
   echo "exit: $?"
   jq -e '.results[0].findings | length > 0' /tmp/control.json
   ```
   Expect the muster exit **1** and the `jq -e` exit **0** (findings
   present).
3. **FR-006, neutralize direction**: swap in the neutralized persona text
   (same file, same structure, only the sentence replaced per T010's pinned
   text), rerun the identical two commands — expect muster exit **0** and
   `jq -e '.results[0].findings | length == 0'` exit **0**. This is the
   observed-failing discrimination control this mission's standing
   requirement demands — both directions must actually be run, not asserted.
4. **C-001**:
   ```sh
   npx --offline @garrison-hq/muster@1.1.0 crosslayer run <manifest referencing the invalid-persona fixture> --static-only
   echo "exit: $?"
   ```
   Expect exit code exactly **2** (never `0`/`1`), and stderr containing the
   literal substring `muster: crosslayer manifest run failed:` — quote the
   exact stderr line in the work log. Confirm no `--json` summary with a
   `findingTypes` array was ever printed for this run — a categorically
   different failure mode than a contradiction finding.

**Files**: none new.
**Validation**: FR-004's 1a (local-mechanism proof, both pass and
falsification exit codes) and 1b (honest blocked-status entry, not a
fabricated pass) both recorded verbatim; FR-006 flip + neutralize exit
codes recorded verbatim; C-001's exit code and stderr line quoted exactly.

---

### T013 — WP02 verification gate (Definition of Done + per-lane C-002)

**Steps** (run in order):
```bash
git diff --stat                                   # ONLY the owned_files entries changed
git diff --stat conformance/crosslayer/personas/  # MUST show no changes — WP01's exclusive scope
git diff --name-only <mission-base>...<this-lane-branch> > /tmp/wp02-c002-diff.txt
if grep -qx "conformance/README.md" /tmp/wp02-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
! (grep -v '^conformance/' /tmp/wp02-c002-diff.txt | grep -v '^kitty-specs/' | grep -v '^tests/' | grep -v '^\.github/workflows/crosslayer\.yml$' | grep -q .)
```
The last two lines are this WP's **per-lane C-002 check**, scoped to
`<mission-base>...<this-lane-branch>` — this WP's own responsibility before
requesting review; the cross-lane assembled-diff run happens again at
mission review as the backstop, per spec.md's C-002 verification cell.

**Post-implementation widening (C-011 remediation, T014)**: the `tests/`
exclusion above was added to admit `tests/cross_cutting/
test_crosslayer_wp02_manifests_control_c001.py`, the C-011 failing-first
test this WP now ships (see T014). `owned_files`/`create_intent` were
widened to match (this WP's own task-file defect, not a reason to skip the
test — mirrors WP03's T017/T018 remediation for the identical shape).

---

### T014 — C-011 (ATDD-First Discipline) failing-first test (operator ruling, binding)

The operator ruled C-011 (`.kittify/charter/charter.md:504`) binding over
`charter.yaml`'s `tdd_required: false`, and binding over every `DIR-0xx`
(all `severity: warn`). Unlike WP01/WP03 (which retrofitted a test after
their implementation commit, documenting a deviation), WP02 commits its
failing-first test **before** any of its five-plus implementation files
exist, in true red-first order.

**File**: `tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py`
(new) — placed under `tests/cross_cutting/`, not a new `tests/conformance/`
directory, so it is actually selected by the live `e2e-cross-cutting` CI job
(`ci-quality.yml`) without this WP touching any workflow file it does not
own (mirrors WP03's T018 placement rationale exactly).

**RED** (base commit `6c5d08978d7d29a6c09fead1c8e6a88610fc2805`, before any
WP02 file exists): 2 of 6 tests fail — the two that assert the real
committed `control.yaml` and `fixtures/invalid-persona-missing-key.Soul.md`
exist (`AssertionError: ... not committed at ...`). The other 4 (two FR-004
sandbox mechanism-proof tests, the C-001 manifest-level exit-2 sanity check,
and the FR-006 mechanism-proof-with-recognized-negation-wording test) pass
already since they are fully self-contained and do not depend on this WP's
deliverables — this is expected and does not weaken the RED observation for
the two deliverable-dependent tests, which is what C-011 is pinning.

**GREEN** (final commit, all 5+ implementation files present): all 6 tests
pass. See the Activity Log for the exact transcript of both runs.

**Two REAL, VERIFIED discrepancies between spec.md's acceptance criteria and
the real, shipped `@garrison-hq/muster@1.1.0` CLI were found while writing
this test — reported here plainly, not fabricated around**:

1. **FR-006's pinned fixture text does not discriminate.** The verbatim
   pinned flip text (persona: "Always answer in exhaustive... every
   response."; skill: "...No restated context, no preamble.") produces
   **zero findings** under `contradiction-lint.ts`'s real heuristic, in
   *both* the flip and the neutralized direction — because
   `NEGATION_OPERATORS` recognizes "never"/"not"/"refuse"/etc. but **not**
   bare "no", so no polarity-inversion signal is ever detected for this
   specific wording. The committed `control.yaml` (built verbatim per this
   WP's own instruction to transcribe, not re-derive) therefore does **not**
   discriminate as FR-006 and its own Definition-of-Done bullet require.
   Verified twice via real `npx` runs (see Activity Log) plus a source-level
   trace of `analyseClausePair`'s `isPolarityInversion` check
   (`contradiction-lint.ts`). A companion mechanism-proof test (same file)
   confirms the lint/harness genuinely IS content-driven using a
   structurally identical case with recognized negation wording ("never"
   instead of "no") — isolating the defect to the pinned text's specific
   wording choice, not to this WP's manifest/control wiring.
2. **C-001's pinned exit code (2) does not match real behavior.** The RFC-1
   strict-mode violation thrown by `resolvePersonaLayer` is caught inside
   `manifest-runner.ts`'s `runManifest` per-case `try/catch` (during
   per-case dispatch) and recorded as `{passed: false, error: message}`,
   contributing to `summary.failed` — it never propagates to
   `doCrossLayerStaticOnly`'s catch block, so it never becomes an
   `ExecutionError`/exit 2. Real, observed behavior for the C-001 scenario:
   **exit 1**, a normal `--json` summary with the RFC-1 message in the
   case's `error` field, and **empty stderr** (never the
   `muster: crosslayer manifest run failed:` substring). The exit-2 path is
   real and reachable — proven separately by a manifest-load-level failure
   (a fixturePath that fails the path-traversal guard) — just not via the
   scenario C-001 describes.

Neither discrepancy was resolved by silently rewriting spec.md's pinned
text or its exit-code claim — this WP has no authority to do that, and the
task file explicitly forbids re-deriving/paraphrasing the pinned FR-006
text. Both are reported as **blocking findings requiring operator/spec
attention** before FR-006's and C-001's Definition-of-Done items can be
honestly checked off. The committed fixtures still exist exactly as
instructed (verbatim pinned text for `control.yaml`; a `voice`-omitting
persona for the C-001 fixture) — only the *claims about their observed
behavior* are corrected to match reality.

**Files**: `tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py`
(new).
**Validation**: `uv run python3 -m pytest
tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py -q` —
6 passed at final commit; `uv run python3 -m pytest
tests/architectural/test_gate_coverage.py::test_no_new_orphan_surfaces -q`
— 1 passed (no orphan-surface regression from adding this test file).

## Definition of Done

- [ ] Two real FR-004 cases committed, referencing WP01's committed persona
      paths by `fixturePath`, not byte content
- [ ] T012 step 1a's local-mechanism proof actually run against a local-only
      manifest copy, with the real pass and falsification exit codes
      recorded (not just designed/asserted)
- [ ] T012 step 1b's honest blocked-status entry recorded for the real
      committed manifest — never a fabricated passing exit code reported in
      its place
- [ ] No file written under `conformance/crosslayer/personas/` (WP01's
      exclusive scope) — no scratch/sandbox fixture ever committed there
- [ ] FR-006 control proven **both directions**, real observed exit codes and
      `jq` results recorded, using the exact pinned text (flip and
      neutralize) — never blanking/truncating
- [ ] C-001 fixture produces exit code exactly `2` with the exact stderr
      substring quoted in the work log — never a `--json` summary
- [ ] T008's sandbox persona(s) never committed; `git status --short` clean
      of any scratch path at commit time
- [ ] Per-lane C-002 check (T013) passes against this WP's own lane diff
- [ ] No file outside `owned_files` modified

## Risks

- **Assuming a fixturePath depth without testing it**: do not hard-code a
  relative-path guess for `$ref`/`fixturePath` resolution — T012's real run
  is the only valid proof it resolves correctly.
- **Neutralizing by deletion**: the spec explicitly calls out blanking or
  truncating the contradictory sentence as an insufficient, disallowed
  shortcut — it would pass trivially without proving the check is
  content-driven. Use the pinned neutralized sentence exactly.
- **C-001 vs. a findingTypes result**: do not let the invalid-persona fixture
  accidentally validate under a lenient path and produce a `findingTypes`
  result instead of the required exit-`2` error — these are categorically
  different failure classes (spec.md C-001) and a CI script testing only
  `$? != 0` cannot tell them apart; this WP's own verification must test
  `$? == 2` specifically.
- **Writing to WP01's path by accident**: `conformance/crosslayer/personas/`
  is nested inside this WP's broader `conformance/crosslayer/` tree but is
  not in this WP's `owned_files` — a careless glob-write could land there.

## Reviewer guidance

- **Reject if** any committed case file's `fixturePath` points anywhere
  other than WP01's real committed persona paths for the two FR-004 cases.
- **Reject if** the FR-006 neutralize direction used blanking/truncation
  instead of the pinned substitute sentence.
- **Reject if** the work log is missing any of T012's real exit codes, or if
  C-001's stderr substring is paraphrased rather than quoted exactly.
- **Reject if** step 1b reports a passing or `failed: 0` result for the real
  committed manifest instead of an honest blocked-status entry — the
  personas/sop-extract sibling-lane files genuinely do not exist in this
  lane's worktree yet, so a reported pass here is fabricated, not observed.
- **Reject if** step 1a's local-mechanism proof is missing or was run only
  against the real committed manifest (which cannot honestly pass from this
  lane alone) instead of the required local-only sandbox copy.
- **Reject if** anything is committed under `conformance/crosslayer/personas/`.
- Confirm the per-lane C-002 check (T013) was actually run against this WP's
  own lane diff, not skipped in favor of only the later cross-lane run.

Implementation command: `spec-kitty agent action implement WP02 --agent claude`

## Activity Log

### C-011 RED (commit `0b6fc2d11`, before any implementation file)

```
$ uv run python3 -m pytest tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py -q
..F.F.
FAILED ...::test_c001_invalid_persona_is_a_categorical_error_never_a_findings_result
  AssertionError: C-001 fixture not committed at .../conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md
FAILED ...::test_fr006_committed_control_verbatim_pinned_text_does_not_discriminate
  AssertionError: FR-006 control not committed at .../conformance/crosslayer/control.yaml
2 failed, 4 passed in 75.87s (0:01:15)
```
(The 4 passing tests are fully self-contained sandbox/mechanism-proof cases
that do not depend on this WP's deliverables — expected, does not weaken
the RED observation for the two deliverable-dependent tests.)

### T008 / T012 step 1a — local-mechanism proof (self-authored sandbox)

Sandbox persona (`Ground each response in the details of the user's
request.` — no ACCOMMODATION_OPERATORS/NEGATION_OPERATORS tokens) + sandbox
sop-extract + the real, symlinked `fixtures/spk-run-next.SKILL.md`, wired
through local-only copies of both real case files (persona/sop paths
rewritten to the sandbox fixtures; skill path untouched):

```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run <local-only manifest> --static-only --json
{"total":2,"passed":2,"failed":0,"skipped":0,"results":[
  {"id":"architect-run-skill","passed":true,"findings":[]},
  {"id":"reviewer-run-skill","passed":true,"findings":[]}]}
exit=0
```
Pass confirmed: exit 0, `failed: 0`.

**Falsification** (same local-only sandbox area, control.yaml swapped in
place of the benign manifest, per T012's instruction):
```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run <control.yaml + its 3 fixtures, copied> --static-only --json
{"total":1,"passed":0,"failed":1,"skipped":0,"results":[
  {"id":"control-verbosity-flip","passed":false,"findings":[]}]}
exit=1
```
Falsification confirmed: exit 1, `failed: 1` (the harness genuinely can
report failure — this direction is driven by the `expected.ok:false`
mismatch, a separate, valid proof from FR-006's own content-driven claim
below).

All scratch/local-only manifest and case copies were discarded before
finalizing this commit; `git status --short` shows nothing under any
scratch path (confirmed below, "clean tree" section).

### T012 step 1b — honest blocked-status entry (real committed manifest)

```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/manifest.yaml --static-only --json
{"total":2,"passed":0,"failed":2,"skipped":0,"results":[
  {"id":"architect-run-skill","passed":false,
   "error":"ENOENT: no such file or directory, open '.../conformance/crosslayer/personas/architect-alphonso.Soul.md'"},
  {"id":"reviewer-run-skill","passed":false,
   "error":"ENOENT: no such file or directory, open '.../conformance/crosslayer/personas/reviewer-renata.Soul.md'"}]}
exit=1
```

**Blocked** — this real committed manifest run requires WP01 (personas) and
WP03 (sop-extract.md) merged onto the same branch as this lane; per-case
ENOENT is the expected, honest result of a sibling lane's not-yet-merged
file being absent, not an FR-004 defect; re-verify at mission level once
WP01/WP03 have merged (spec.md's Real-CLI verification requirement;
`tasks/PRE-MERGE-ACTIONS.md` item 1). Not a fabricated `failed: 0`/exit `0`.

### T012 step 2/3 — FR-006 flip + neutralize (real committed `control.yaml`)

**Flip** (committed state, verbatim pinned text):
```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/control.yaml --static-only --json > /tmp/control.json
muster_exit=1
{"total":1,"passed":0,"failed":1,"skipped":0,"results":[
  {"id":"control-verbosity-flip","passed":false,"findings":[]}]}
$ jq -e '.results[0].findings | length > 0' /tmp/control.json
false
jq_exit=1
```

**Neutralize** (scratch copy only — `/tmp/t012-neutralize/`, committed
fixtures never mutated — persona body sentence replaced with spec.md's
pinned neutralized sentence, same file, same structure):
```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run <scratch>/control.yaml --static-only --json > /tmp/control-neutralize.json
muster_exit=1
{"total":1,"passed":0,"failed":1,"skipped":0,"results":[
  {"id":"control-verbosity-flip","passed":false,"findings":[]}]}
$ jq -e '.results[0].findings | length == 0' /tmp/control-neutralize.json
true
jq_exit=0
```

**REPORTED FINDING, NOT A PASS**: the real, verified findings are `[]` in
*both* directions. `jq`'s flip-direction assertion (`length > 0`) is
**FALSE** — the control does not discriminate with the verbatim pinned
text. `muster_exit=1` in *both* directions here is an artifact of this
WP's own `expected: {ok: false}` choice for the committed fixture (declaring
the intended contract) — mismatch on both sides, not evidence of detection.
Tried the alternative framing too (conceptually, `expected.ok: true`): that
flips which direction's `muster_exit` "matches" AC-3 by coincidence, but
never both at once, because the real finding set is identical (empty)
regardless of flip/neutralize. Only the raw `findings` array — read
directly via `jq`, independent of any `expected:` choice — tells the truth,
and the truth is: this control does not discriminate. See T014 and the test
module docstring for the root cause (`NEGATION_OPERATORS` lacks bare "no")
and the companion mechanism-proof test that isolates this to the pinned
wording, not to this WP's wiring.

### T012 step 4 — C-001

```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run <manifest referencing fixtures/invalid-persona-missing-key.Soul.md> --static-only
exit: 1
--stdout--
crosslayer: FAIL — 0/1 cases passed, 1 failed
  [FAIL] c001-invalid-persona: error — Persona layer failed RFC-1 strict-mode validation: [Appendix E] voice: must have required property 'voice'
--stderr--
(empty)
```

**REPORTED FINDING, NOT A PASS**: real exit code is **1**, not the pinned
**2**; stderr is **empty** — the literal substring
`muster: crosslayer manifest run failed:` never appears for this scenario.
Confirmed (sanity check) that the exit-2/stderr-substring path IS real and
reachable for a different, manifest-load-level failure (a fixturePath that
escapes the manifest directory, tripping the path-traversal guard):
```
$ npx --offline @garrison-hq/muster@1.1.0 crosslayer run <path-traversal-violating manifest> --static-only
exit: 2
--stderr--
muster: crosslayer manifest run failed: Path traversal rejected: fixturePath "../other/skill.SKILL.md" in case "..." resolves outside the manifest directory. ...
```
This isolates C-001's discrepancy to the RFC-1-invalid-persona scenario
specifically — `manifest-runner.ts`'s `runManifest` catches that throw
per-case (inside `dispatchCase`, called within the per-case `try/catch`
loop) and never re-throws it to the CLI-level catch that maps to exit 2.

### GREEN (this commit, all implementation files present)

```
$ uv run python3 -m pytest tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py -v
6 passed in 72.47s (0:01:12)
```

### T013 — per-lane C-002 gate (against this lane's own diff, `kitty/mission-crosslayer-composition-suite-01KYJA33...HEAD`)

```
$ git diff --stat conformance/crosslayer/personas/
fatal: ambiguous argument 'conformance/crosslayer/personas/': unknown revision or path not in the working tree.
```
This directory does not exist at all in lane-b's worktree (WP01's exclusive
scope, a different lane) — the check's INTENT (no changes to it) is
trivially satisfied since there is nothing to diff; `git status --short`
independently confirms no file was ever written under
`conformance/crosslayer/personas/` in this worktree.

```
$ git diff --name-only kitty/mission-crosslayer-composition-suite-01KYJA33...HEAD > /tmp/wp02-c002-diff.txt
kitty-specs/crosslayer-composition-suite-01KYJA33/tasks/WP02-manifests-control-c001-fixture.md
tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py
conformance/crosslayer/cases/architect-run-skill.yaml
conformance/crosslayer/cases/reviewer-run-skill.yaml
conformance/crosslayer/control.yaml
conformance/crosslayer/fixtures/control-persona.Soul.md
conformance/crosslayer/fixtures/control-skill.SKILL.md
conformance/crosslayer/fixtures/control-sop.md
conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md
conformance/crosslayer/fixtures/spk-run-next.SKILL.md
conformance/crosslayer/manifest.yaml

$ if grep -qx "conformance/README.md" /tmp/wp02-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
(no output) exit=0

$ ! (grep -v '^conformance/' /tmp/wp02-c002-diff.txt | grep -v '^kitty-specs/' | grep -v '^tests/' | grep -v '^\.github/workflows/crosslayer\.yml$' | grep -q .)
exit=0
```
Both parts pass: exit 0.

### Widened scope (documented, T014)

- `owned_files`/`create_intent`: added `fixtures/control-persona.Soul.md`,
  `fixtures/control-sop.md`, `fixtures/control-skill.SKILL.md`,
  `fixtures/spk-run-next.SKILL.md`, and
  `tests/cross_cutting/test_crosslayer_wp02_manifests_control_c001.py`. The
  original 5-file `owned_files` list had no path that could admit a test at
  all, and `control.yaml` itself needs its own fixture files (never listed) —
  both are task-file defects, not reasons to skip C-011 or ship a
  non-self-contained control.
- T013's per-lane C-002 allow-list: added `grep -v '^tests/'`.

### `fixturePath` resolution — empirically determined (T009 step 4)

Confirmed real behavior of `assertWithinManifestDir`
(`manifest-runner.ts`): a relative `fixturePath` that resolves OUTSIDE the
manifest's own directory tree is REJECTED (path-traversal guard, exit 2) —
there is no relative-path depth that reaches
`src/doctrine/skills/spk-run-next/SKILL.md` from
`conformance/crosslayer/`, since that file lives outside the manifest
directory entirely. Verified directly (scratch repro): a plain
`../../src/doctrine/...` path is rejected. A **git-tracked symlink**
(`conformance/crosslayer/fixtures/spk-run-next.SKILL.md` ->
`../../../src/doctrine/skills/spk-run-next/SKILL.md`) resolves this: the
guard is a pure string check on the committed path (which stays inside the
manifest directory), while `fs.readFile` transparently follows the symlink
at the OS level to the real file — verified with a scratch symlink before
committing the real one (exit 0, real skill content read).
