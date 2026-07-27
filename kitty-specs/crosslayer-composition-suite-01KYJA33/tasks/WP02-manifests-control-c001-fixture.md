---
work_package_id: "WP02"
title: "Composition manifests, discrimination control, C-001 fixture (lane-b part 1)"
dependencies: []
requirement_refs:
  - FR-004
  - FR-006
  - C-001
  - C-002
subtasks:
  - T008
  - T009
  - T010
  - T011
  - T012
  - T013
owned_files:
  - "conformance/crosslayer/manifest.yaml"
  - "conformance/crosslayer/cases/architect-run-skill.yaml"
  - "conformance/crosslayer/cases/reviewer-run-skill.yaml"
  - "conformance/crosslayer/control.yaml"
  - "conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md"
create_intent:
  - "conformance/crosslayer/manifest.yaml"
  - "conformance/crosslayer/cases/architect-run-skill.yaml"
  - "conformance/crosslayer/cases/reviewer-run-skill.yaml"
  - "conformance/crosslayer/control.yaml"
  - "conformance/crosslayer/fixtures/invalid-persona-missing-key.Soul.md"
authoritative_surface: "conformance/crosslayer/"
execution_mode: "code_change"
planning_base_branch: kitty/mission-crosslayer-composition-suite
merge_target_branch: kitty/mission-crosslayer-composition-suite
branch_strategy: "Planning artifacts for this mission were generated on kitty/mission-crosslayer-composition-suite. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-crosslayer-composition-suite unless the human explicitly redirects the landing branch."
base_branch: kitty/mission-crosslayer-composition-suite-01KYJA33
base_commit: c425bc188995b5b9a04bece05b511ba81896ce7f
created_at: '2026-07-27T19:45:23Z'
history:
  - timestamp: '2026-07-27T19:45:23Z'
    event: created
    by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: node-norris
role: implementer
agent: claude
model: ''
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
git diff --stat                                   # ONLY the five owned_files entries changed
git diff --stat conformance/crosslayer/personas/  # MUST show no changes — WP01's exclusive scope
git diff --name-only <mission-base>...<this-lane-branch> > /tmp/wp02-c002-diff.txt
if grep -qx "conformance/README.md" /tmp/wp02-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
! (grep -v '^conformance/' /tmp/wp02-c002-diff.txt | grep -v '^kitty-specs/' | grep -v '^\.github/workflows/crosslayer\.yml$' | grep -q .)
```
The last two lines are this WP's **per-lane C-002 check**, scoped to
`<mission-base>...<this-lane-branch>` — this WP's own responsibility before
requesting review; the cross-lane assembled-diff run happens again at
mission review as the backstop, per spec.md's C-002 verification cell.

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

(none yet — populated during implementation)
