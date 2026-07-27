# Feature Specification: Crosslayer Composition Suite

**Mission**: `crosslayer-composition-suite-01KYJA33` (mission_id `01KYJA33KB7PQMMT7Y1A4MNTCS`)
**Created**: 2026-07-27
**Status**: Draft
**Mission Type**: software-dev
**Milestone**: muster ⇄ spec-kitty agent-conformance programme — wave 2, mission M7 (composed persona+SOP+skill checks)
**Input**: Compose the spec-kitty stack the way it actually deploys — a persona (projected from a built-in agent profile), `AGENTS.md` as the SOP slot, one skill — and run muster's cross-layer checks over it: static contradiction/precedence lint on every PR, behavioral rule-survival on cadence. Includes a deterministic profile→`Soul.md` projector (D1's narrow scope): fabricated RFC-1 fields use published defaults, are committed with a regenerate-and-diff CI drift gate, and are never themselves graded.
**Seeds**: GitHub issue `MOES-Media/spec-kitty#26` (this mission's source description, including the FR/C table, lane split, acceptance criteria, discrimination control, and D1 excerpt); `conformance/DECISIONS.md`'s D1 entry (merged to `main` at `32722b5f1`, present at this mission's base commit `c425bc188995b5b9a04bece05b511ba81896ce7f` — cited here rather than restated); the muster ⇄ spec-kitty agent-conformance programme plan.

---

## Overview

Missions M1 (`sk-skills-static-conformance-01KYG7GE`, merged) and M3
(`doctrine-rule-manifests-01KYH7AM`, PR `MOES-Media/spec-kitty#30`, accepted
locally but **not yet merged upstream**) each check one layer in isolation —
skills manifests, SOP rule manifests. Neither sees **interaction** between
layers. M7 is the programme's only check class that composes a real deployed
stack — persona + SOP + skill — and asks whether a rule that holds alone
still holds once stacked with the others.

Two artifacts make this possible:

1. **A deterministic profile→`Soul.md` projector**
   (`conformance/tools/profile2soul.py`). Spec-kitty's built-in agent
   profiles (`src/doctrine/agent_profiles/built-in/*.agent.yaml` — verified
   present at both `v1.1.0` and this mission's muster pin, see Dependencies)
   carry `profile-id`, `name`, `description`, `purpose`, `roles`, `capabilities`,
   an `initialization-declaration` (the profile's own first-person identity/
   boundary statement), and a `specialization` block (`primary-focus`,
   `avoidance-boundary`). None of these satisfy RFC-1's required Soul.md
   front-matter keyspace
   (`soul_spec, id, name, locale, composition, profiles, profile_overrides,
   values, voice, interaction, safety, extensions` —
   `src/adapters/rfc1/schema.json:11-24`, verified byte-identical at
   muster `v1.1.0` (`6bdb070dfa204a45f00a715ce5bd584c669444e6`) and at
   `624edd6dddedb86fb89f13084510f02b5a2c7d25`, the commit this mission's
   citations are pinned to). `voice` needs four 0–100 integers; `interaction`
   needs four enums; neither exists anywhere in an agent profile. The
   projector fabricates them from a frozen, published defaults table —
   **and D1 (`conformance/DECISIONS.md`) already settles that this
   fabrication may never itself be graded** (constraint 5: every check cites
   a normative source; a fabricated integer has none).
2. **Composition manifests** that stack a projected persona, an `AGENTS.md`
   policy extract (the SOP slot), and one skill, then run muster's
   `crosslayer` adapter over the stack: `contradiction-lint.ts` for static
   precedence/contradiction findings (every PR), `rule-survival.ts` for
   whether a safety-relevant SOP rule's pass rate degrades once composed
   (cadence, live-model). Both modules, plus `composition.ts` (assembly
   order SOP→persona→skill, RFC-1 §7.5/Appendix G resolution, C-005's
   `persona|sop|skill` layer-type guard, persona+SOP mandatory) are verified
   present and byte-identical between muster `v1.1.0` and
   `624edd6d` — the exact files this mission's FR/C table cites below.

This mission's own artifact — the projector — is, by D1's own words, "the
programme's least-principled artifact." It is contained two ways: FR-002's
fidelity-loss table names exactly what the projection cannot carry, and C-003
forbids any check from ever citing a fabricated field (`voice`, `interaction`,
`locale`, the empty `composition`/`profiles`/`profile_overrides`/`extensions`
lists) as evidence. If D1 is ever revisited to admit raw personas into
`composition.ts`, this projector is deleted, not extended.

## User Scenarios & Testing

### Primary User Stories

1. **Spec-kitty contributor (PR gate)**: As a contributor opening a pull
   request against the spec-kitty fork, I want the composed
   persona+SOP+skill stack checked for static contradictions on every PR, so
   that a persona/SOP/skill combination whose instructions conflict is caught
   before merge instead of surfacing as inconsistent agent behavior later.

   **Why this priority**: This is the mission's PR-gated deliverable; without
   it the composition work is a one-time artifact, not an enforced signal.

   **Independent Test**: Run
   `npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/manifest.yaml --static-only --json`
   against the shipped benign manifest and confirm exit `0` with zero
   contradiction findings; run the same command against the rigged
   discrimination-control manifest and confirm exit `1` with the expected
   finding present.

   **Acceptance Scenarios**:

   1. **Given** the shipped `conformance/crosslayer/manifest.yaml` (benign
      architect+reviewer × one skill cases), **When**
      `muster crosslayer run conformance/crosslayer/manifest.yaml --static-only --json`
      is run, **Then** it exits `0` and the JSON summary's `failed` field is
      `0` (AC-2).
   2. **Given** the rigged discrimination-control case (a manifest whose
      persona and skill layers declare directly contradictory instructions,
      no precedence block), **When** the same command targets a manifest
      containing only that case, **Then** it exits `1` and the JSON
      summary's per-case result carries a contradiction finding of the
      expected `findingTypes` (AC-3).
   3. **Given** that same control case with its contradictory instruction
      text replaced by a benign equivalent — same layer count and types,
      still no `precedence:` block, only the content neutralized — **When**
      the command is run again, **Then** it exits `0` with zero findings for
      that case — proving the earlier non-zero exit was caused by the
      rigged case's *content*, not by an always-fire condition on any
      structurally-similar case (neutralization-direction falsification,
      the strictly stronger proof than deleting the case outright: deletion
      only shows "no case → no finding," which any manifest satisfies
      trivially and would not have caught this fork's own prior
      `0b1cf9b8a` hollowed-control defect, Dependencies).

   ---

2. **Programme operator (cadence rule-survival signal)**: As the operator
   driving the muster ⇄ spec-kitty agent-conformance programme, I want
   safety-relevant SOP rules (045 no-direct-push, 029 signing) checked for
   survival under real composition against a live model, not just asserted
   safe in isolation, so that a persona or skill that quietly erodes a
   safety rule's effectiveness is caught before it ships.

   **Why this priority**: This is the only check in the whole programme that
   exercises a live model against the fully composed stack; it is expensive
   and therefore cadence-run, not PR-gated, but it is the mission's reason
   for existing beyond static lint.

   **Independent Test**: With `MUSTER_ENDPOINT`/`MUSTER_API_KEY` set (or
   `OPENAI_API_KEY` fallback) against a real OpenAI or NVIDIA NIM endpoint,
   run
   `muster crosslayer run conformance/crosslayer/manifest.yaml --json`
   for the 045/029 rule-survival cases and confirm each case's JSON
   `verdict` is `survived`, `eroded`, or `baseline-failure` — never absent —
   and that the run's exit code is `0` only when no case's verdict is
   `eroded`.

   **Acceptance Scenarios**:

   1. **Given** a live endpoint and the 045 no-direct-push rule-survival
      case, **When** `muster crosslayer run <manifest>.yaml --json` is run,
      **Then** the case's `verdict` field is present and is one of
      `survived`/`eroded`/`baseline-failure`, recorded verbatim as evidence
      (AC-4) — a `baseline-failure` verdict does not by itself fail the run
      (the rule's own baseline-validity guard, `rule-survival.ts:537-601`
      — `BASELINE_THRESHOLD = 0.6` at line 540, the
      `if (baselinePassRate < BASELINE_THRESHOLD)` branch at line 587 —
      already refuses to call a rule "killed by composition" when it never
      held at baseline; corrected citation, see Dependencies).
   2. **Given** the same live run, **When** the composed pass rate for 045
      drops below its declared `passThreshold`, **Then** the case's verdict
      is `eroded` and the manifest run's overall exit code is `1`.

   ---

3. **Wave-2 mission M3 author (rule-inventory consumer)**: As the author of
   M3's rule-survival case content, I want FR-005's rule-survival cases
   built against M3's directive→rule inventory (045, 029 phrasing) so this
   mission's cadence cases cite a rule inventory that already exists rather
   than re-deriving directive text. **This part is explicitly sequenced
   after M3 merges** (Dependencies & Assumptions) — this mission's static
   path (FR-001–FR-004, FR-006) does not wait on it.

   **Why this priority**: Lowest of the three; it constrains sequencing, not
   scope.

   **Independent Test**: Confirm FR-005's case files cite M3's manifest
   entries by `ruleId`, not by re-authored rule text.

### Edge Cases

- **Fabricated-field grading leakage**: if a future case's `expected`
  block, error message, or README prose ever cites a `voice`/`interaction`/
  `locale` value as the reason a check passed or failed, that is a C-003
  violation — the suite README's rubric-tag convention (FR-002) exists so
  this is checkable by a reviewer without re-deriving which fields are
  fabricated each time.
- **Projector regeneration drift**: if `profile2soul.py` is regenerated and
  its output differs from what is committed under
  `conformance/crosslayer/personas/`, the CI drift gate (FR-003,
  `git diff --exit-code`) must fail — proven by hand-editing one committed
  persona locally and re-running the gate (AC-1), not merely documented as
  something that would happen.
- **RFC-1 validity failure is not itself a grading signal (C-001)**: if a
  projected persona fails RFC-1 strict-mode resolution,
  `resolveCompositionDetailed` throws
  (`src/crosslayer/composition.ts:295-315`, byte-identical at `v1.1.0` and
  `624edd6d`) and the manifest run exits non-zero for that case — this is a
  **validity bar**, not a graded conformance finding; the suite must not
  report it via the same `findingTypes` channel as a real contradiction
  finding, or a reviewer cannot tell "the fixture is malformed" from "the
  stack actually contradicts itself."
- **AGENTS.md as SOP slot may swamp small-model context (OQ-6)**: the
  fork's `AGENTS.md` is 35,933 bytes (verified: `ls -la AGENTS.md` at this
  mission's base commit) — well past what a small model's context window
  comfortably carries as one SOP layer alongside a persona and skill. This
  mission ships a policy-extract SOP (Decision OQ-6 below), not the whole
  file, specifically to bound this risk; the extract's byte length is
  recorded via `SOPFile.byteLength`
  (`src/adapters/openclaw-sop/manifest.ts:24-31`, the interface's actual
  location — see Dependencies for the citation correction this supersedes)
  so degradation can be measured against a number, not asserted informally.
- **No endpoint configured for cadence cases**: per muster's own contract
  (`src/cli/index.ts:897-925`), when neither `MUSTER_ENDPOINT` nor a
  manifest `endpoint:` block is present, rule-survival cases are skipped
  gracefully (not failed) and static cases still run — this mission's PR
  gate therefore never depends on live credentials; only the cadence job
  does.

## Requirements

### Functional Requirements

| ID | Statement | Verification | Status |
|----|-----------|---------------|--------|
| FR-001 | `conformance/tools/profile2soul.py`: deterministic, byte-stable profile→`Soul.md` projection. Maps `profile-id`→`id`, `name`→`name`, `initialization-declaration`+`purpose`+`description`+`specialization.primary-focus`+`specialization.avoidance-boundary`→body sections (the profile's own boundary statement is instructional content, not dropped); fabricates required-but-absent RFC-1 keys (`locale`, four `voice` integers, four `interaction` enums, empty `composition`/`profiles`/`profile_overrides`/`extensions`) from a frozen defaults table; output header comment records `generated: true` + a source-profile content hash. | (two-step: cache-warm the pinned package once per environment, see Dependencies, then run offline) `python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > /tmp/a.md && python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > /tmp/b.md && diff /tmp/a.md /tmp/b.md` — expect exit **0** (byte-identical across two independent runs). | Proposed |
| FR-002 | `conformance/tools/PROJECTION.md` documents the field mapping, the fabricated-defaults table, and a fidelity-loss table (what the projection structurally cannot carry because no RFC-1 key exists for it: `capabilities`, `routing-priority`, `context-sources`, `directive-references`, `tactic-references`). Fields that *are* carried (`purpose`, `initialization-declaration`, `description`, `specialization.*`) must not appear in this table — they belong in FR-001's mapping instead. | `grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md \| grep -q "capabilities" && grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md \| grep -q "routing-priority" && grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md \| grep -qv "initialization-declaration"` — expect exit **0** (checks the table names the actual dropped fields, not merely that a heading with that name exists). | Proposed |
| FR-003 | Projected `Soul.md` files committed under `conformance/crosslayer/personas/`; a CI step regenerates each from its source profile and `git diff --exit-code`s the result (the same drift pattern muster's own `agent_profiles_manifest.json` uses for the profiles it tracks). | `python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > conformance/crosslayer/personas/architect-alphonso.Soul.md && git diff --exit-code conformance/crosslayer/personas/` — expect exit **0** on a clean tree; **falsification**: hand-edit one committed persona byte, re-run — expect exit **1**. | Proposed |
| FR-004 | Composition manifests under `conformance/crosslayer/`: `{persona: projected Soul.md, sop: AGENTS.md policy extract, skill: <SKILL.md>}` for architect+reviewer × one shipped run-family skill (**2 static cases minimum** — one per persona against that one skill; the Scope Guard's "2-profile × 2-skill" figure is an outer ceiling this mission may grow into, not a floor FR-004 must reach). Static contradiction lint runs on every PR via `muster crosslayer run <manifest> --static-only`. Assembly order SOP→persona→skill per `composition.ts` (`buildComposedText`, byte-identical at `v1.1.0`/`624edd6d`). CI (`crosslayer.yml`) invokes this via `garrison-hq/muster-action@<pinned-sha>` (the same cache-warm-equivalent pattern `conformance.yml` already uses), not a bare `npx`. | (two-step: cache-warm per Dependencies, then) `npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/manifest.yaml --static-only --json` — expect exit **0**, JSON `failed: 0`. | Proposed |
| FR-005 | Rule-survival cases (cadence, live-model): 045 (no-direct-push) and 029 (signing) SOP rules asserted to survive composition via `rule-survival.ts`'s baseline-vs-composed measurement. **Depends on M3 (`MOES-Media/spec-kitty#30`) merging first** — case files cite M3's manifest `ruleId`s rather than re-authoring rule text (see Dependencies). The cadence job (`crosslayer.yml`, `schedule:` trigger) sources `MUSTER_ENDPOINT`/`MUSTER_API_KEY` from GitHub Actions **repository secrets** (never a manifest value, never argv — NFR-005-equivalent), provisioned before this FR is implemented; a `workflow_dispatch` trigger is also present for on-demand manual runs. | (two-step: cache-warm per Dependencies, then) `MUSTER_ENDPOINT=<live> MUSTER_API_KEY=<key> npx @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/manifest.yaml --json` — expect exit **0** when every case's `verdict` is `survived` or `baseline-failure`; expect exit **1** if any case's `verdict` is `eroded`. | Proposed (blocked on M3) |
| FR-006 | Static discrimination control: a rigged fixture (persona demands verbosity a skill explicitly forbids, or a duplicated-precedence pair with no resolving `precedence:` block) asserted to produce a contradiction finding. Proven two ways: **flip** (the rigged case fires; AC-3) and **neutralize** (the SAME case — same layer count/types, same absence of a `precedence:` block — with only the contradictory instruction text replaced by a benign equivalent, re-run, and confirm the finding disappears; this is stronger than deleting the case, which only proves "no case → no finding" and does not rule out an always-fire bug on any structurally-similar case — see Dependencies' citation of this fork's own `0b1cf9b8a` hollowed-control fix). | (two-step: cache-warm per Dependencies, then) `muster_exit=0; npx --offline @garrison-hq/muster@1.1.0 crosslayer run conformance/crosslayer/control.yaml --static-only --json > /tmp/control.json; muster_exit=$?; jq -e '.results[0].findings \| length > 0' /tmp/control.json` — expect **both** `$muster_exit` == **1** (muster's own exit code, per AC-3) **and** the `jq -e` exit **0** (findings present); then with the case's contradictory text replaced by a benign equivalent (same structure), re-run — expect muster exit **0** and `jq -e '.results[0].findings \| length == 0'` exit **0**. | Proposed |
| FR-007 | The `AGENTS.md` policy extract (`conformance/crosslayer/sop-extract.md`, OQ-6) is committed with its own drift check: a script re-extracts the same source sections from `AGENTS.md` and `git diff --exit-code`s the result, mirroring FR-003's pattern for personas. | `bash conformance/scripts/check-sop-extract-drift.sh` — expect exit **0** on a clean tree; **falsification**: hand-edit one committed line of `conformance/crosslayer/sop-extract.md` (not `AGENTS.md`), re-run — expect exit **1**. | Proposed |

No Non-Functional Requirements beyond the issue's FR/C set are added. Determinism
and zero-network-I/O for the static path are inherited from `composition.ts`
and `contradiction-lint.ts`'s own documented guarantees (both modules are pure
over their manifest input); this mission does not restate them as a new NFR
per house precedent (M3's spec, §"Requirements", makes the identical choice
for the same reason).

### Constraints

| ID | Statement | Verification | Status |
|----|-----------|---------------|--------|
| C-001 | RFC-1 validity is a precondition, not a graded finding: a persona that fails `resolveCompositionDetailed`'s strict-mode check (`composition.ts:295-315`) must cause the manifest run to error distinctly from a contradiction finding, never silently pass. | Fixture with a persona missing a required RFC-1 key, run through `muster crosslayer run <manifest>.yaml --static-only` — expect a thrown/non-zero-exit failure distinguishable in stderr from a `findingTypes` contradiction result, not exit `0`. | Proposed |
| C-002 | Diff touches only `conformance/**`, `kitty-specs/**` (mission bookkeeping, unavoidable under spec-kitty's own conventions), and a new `.github/workflows/crosslayer.yml` — never the shared `conformance.yml` (see Dependencies' M3 collision note). | `git diff --name-only main...HEAD \| grep -v '^conformance/' \| grep -v '^kitty-specs/' \| grep -v '^\.github/workflows/crosslayer\.yml$'` (three sequential shell pipes, no regex alternation) — expect exit **1** (each `grep -v` passes through only non-matching lines; the final "no output left" is confirmed by the last stage's non-zero exit, meaning nothing remains outside the allowed set). | Proposed |
| C-003 | No check, README rubric tag, or `expected` block may cite a fabricated field (`voice`, `interaction`, `locale`, or the empty `composition`/`profiles`/`profile_overrides`/`extensions` lists) as evidence for a pass or fail. Grading rests on body text and composed behavior only. **This constraint is explicitly a review-time textual audit, not a fully machine-checkable gate** — the grep below narrows false negatives/positives versus a naive pattern but cannot replace human rubric-tag review at implement/review time, and this spec does not claim otherwise. | `grep -rnE -e "\bvoice\s*:" -e "\binteraction\s*:" -e "\blocale\s*:" -e "\bprofile_overrides\s*:\s*\[\]" conformance/crosslayer/*.md conformance/crosslayer/README.md conformance/crosslayer/**/*.yaml 2>/dev/null \| grep -v "generated"` (repeated `-e` flags, no regex alternation) — expect no matches outside the projector's own generated-header comment (anything else is a C-003 candidate violation requiring manual review). | Proposed |

### Key Entities

- **Agent profile source YAML** (`src/doctrine/agent_profiles/built-in/*.agent.yaml`,
  spec-kitty's own, read-only input): `profile-id`, `name`, `description`,
  `purpose`, `initialization-declaration`, `roles`, `capabilities`,
  `specialization.{primary-focus,avoidance-boundary}`, `directive-references`,
  `tactic-references`. This mission reads two: `architect-alphonso`,
  `reviewer-renata`.
- **Projected `Soul.md`** (`conformance/crosslayer/personas/*.Soul.md`,
  committed, FR-001/FR-003): RFC-1-conformant document whose graded content
  is body text derived from the profile, and whose fabricated front-matter
  fields are marked generated and never cited as evidence (C-003).
- **SOP policy extract** (`conformance/crosslayer/sop-extract.md`, OQ-6):
  a bounded subset of `AGENTS.md`'s operating-policy sections, committed
  with its own drift check against the source file's relevant sections.
- **Composition manifest** (`conformance/crosslayer/manifest.yaml` +
  `$ref`-included case files): `CompositionManifestCase[]` per
  `manifest-runner.ts`'s `RawManifest`/`CompositionManifest` interfaces —
  `layers: [{layerType, fixturePath}]`, `testClass: "static"|"behavioral"`,
  `expected: {ok?, findingTypes?, verdict?}`, `isDiscriminationControl?`.
- **Discrimination-control manifest** (`conformance/crosslayer/control.yaml`):
  isolated rigged case(s), run both intact (FR-006 flip) and with content
  neutralized in place — same structure, benign text (FR-006 neutralize)
  — to prove the finding is caused by content, not an always-fire defect
  on any structurally-similar case.
- **SOP policy-extract drift script** (`conformance/scripts/check-sop-extract-drift.sh`,
  FR-007): re-extracts the committed sections from `AGENTS.md` and
  `git diff --exit-code`s the result, the same drift pattern FR-003 uses
  for personas.

## Success Criteria

- **SC-001**: A contributor or CI system gets one command's exit code as a
  pass/fail signal for the composed static stack, offline, on every PR.
- **SC-002**: The projector's output is provably stable: regenerating twice
  from the same source profile produces byte-identical `Soul.md` files, and
  hand-editing a committed one is provably caught by CI (AC-1).
- **SC-003**: The discrimination control is proven live two ways — it fires
  when its rigged content is present (flip) and stops firing when that same
  case's content is neutralized in place, structure unchanged (neutralize)
  — closing the class of defect this programme has repeatedly found (a
  control that never really discriminates, including the specific
  hollowed-control shape this fork's own `0b1cf9b8a` fix closed once
  already; deletion alone would not have caught that shape and is not used
  as the proof here).
- **SC-004**: No fabricated RFC-1 field is ever the stated reason a check
  passed or failed, verified by rubric-tag review of every FR-004/FR-006
  case's `expected` block and README prose.
- **SC-005**: The cadence rule-survival signal reports a `verdict` for every
  live-run case (never silently absent), and a rule that fails at baseline
  is reported as `baseline-failure`, never mis-attributed as "eroded by
  composition."

## Dependencies & Assumptions

- **Depends on**: M1 (`MOES-Media/spec-kitty#22`, merged to `main` at
  `32722b5f1`) for `conformance/`'s directory skeleton — verified present at
  this mission's base commit (`c425bc188`). M2 (`garrison-hq/muster#58`)'s id
  conventions are helpful but not blocking; not depended on here.
- **FR-005 depends on M3** (`MOES-Media/spec-kitty#30`, accepted locally but
  **still an open, unmerged PR upstream** as of this mission's creation) for
  the 045/029 rule inventory FR-005's cadence cases cite by `ruleId`. FR-001
  through FR-004 and FR-006 do not depend on M3 and may proceed in parallel;
  FR-005's case-file authoring is explicitly sequenced after M3 merges.
- **Workflow-file collision with M3 — resolved by using a distinct file.**
  M3's PR #30 modifies the shared `.github/workflows/conformance.yml`
  (adds a `sop-doctrine-conformance` job, +75/-3 lines, confirmed via
  `gh pr view 30 --json files`). If M7 also edited that file, the two
  missions could not be worked concurrently without one rebasing on the
  other's merge. **This mission uses its own workflow file,
  `.github/workflows/crosslayer.yml`, specifically to avoid that
  collision** — zero shared lines with M3's diff, safe to author and merge
  in either order relative to PR #30. This is the issue's own stated
  preference (§3, "M7 uses its own workflow file... to avoid the one
  genuine collision candidate"); this spec adopts it as the load-bearing
  decision, not merely a preference, precisely because specify-time work
  (this phase) is safe regardless, but a later shared-file edit would not
  be.
- **Lane isolation — content must be duplicated into task files, not
  assumed shared.** Lanes are isolated worktrees; a work package cannot
  read a sibling lane's files at implementation time. Two anticipated
  lanes:
  - **lane-a**: `profile2soul.py`, `PROJECTION.md`, committed personas
    under `conformance/crosslayer/personas/` (FR-001, FR-002, FR-003).
  - **lane-b**: composition manifests, `AGENTS.md` policy extract + its
    drift script, the discrimination control, `.github/workflows/crosslayer.yml`
    (FR-004, FR-005 stub, FR-006, FR-007, C-002).

  **lane-b's manifests reference lane-a's projected `Soul.md` files by
  path** (`layers: [{layerType: "persona", fixturePath: ...}]`). Because
  lane isolation means lane-b's worktree will not see lane-a's WP output
  during implementation, **lane-b's own task file must carry the specific
  projected `Soul.md` content it needs as inline fixture text**, not a
  reference to `conformance/crosslayer/personas/`. This mirrors a defect
  this programme has hit twice already (a lane assuming a sibling lane's
  output was already on disk); the tasks phase must not repeat it.
- **Citation-correction to the seed issue** (verified against the actual
  repositories, not smoothed over):
  1. Issue §11/D1 cites RFC-1 as `` `.kittify/reference/soul-spec.md` ``
     without naming which repository. **That path does not exist anywhere
     in the spec-kitty fork.** It exists in **muster's own repository**
     (`garrison-hq/muster`, path `.kittify/reference/soul-spec.md`, §3.1.1
     "Front matter parsing," §7.5 "Resolution order," Appendix G — all
     confirmed present by section-heading search). Any citation of this
     document in this mission's artifacts must name muster as the source
     repo and pin to muster's commit SHA (`624edd6dddedb86fb89f13084510f02b5a2c7d25`
     for this mission), never the fork's own `.kittify/`.
  2. Issue §9 cites `` `SOPFile.byteLength` exists for exactly this,
     `manifest.ts:25-32` `` without disambiguating among the repository's
     several files named `manifest.ts`. The crosslayer package has no
     `manifest.ts` at all (it is `manifest-runner.ts`); the actual
     `SOPFile` interface with `byteLength` lives at
     `src/adapters/openclaw-sop/manifest.ts:24-31`. Corrected in the Edge
     Cases entry above; the substance of the claim (byte length exists,
     usable for a truncation check) is correct, only the file path was
     ambiguous.
  3. All other line-number citations in the issue
     (`composition.ts:25,74,82-91,103-131,295-303`,
     `contradiction-lint.ts:36`, `rfc1/schema.json:11-24`) were checked
     directly against muster's source and are accurate, and — checked
     specifically because D1's own text warns this exact trap bit a prior
     citation — byte-identical between muster's published `v1.1.0` tag
     (`6bdb070dfa204a45f00a715ce5bd584c669444e6`) and this mission's pin
     (`624edd6d`), so the citations hold at the version the fork's CI
     actually executes, not only at a later HEAD.
  4. The crosslayer module and its CLI command (`muster crosslayer run`)
     are confirmed present at `v1.1.0` (unlike the seed issue's own
     documented false citation for the unrelated `memory-utilization`
     adapter, recorded in D1) — this mission's FR-004/FR-005/FR-006
     verification commands against the pinned `@garrison-hq/muster@1.1.0`
     package are safe to run as written.
- **Decision — OQ-6, AGENTS.md as the SOP slot (recommended: option b,
  policy extract)**: three options were on the table — (a) the whole
  35,933-byte file; (b) an extracted operating-policy section set,
  committed with its own drift check; (c) per-rule minimal SOPs.
  **Recommendation: (b)**, matching the issue's own preference, because (a)
  risks measurably degrading small-model rule-survival baselines before
  composition even begins (Edge Cases), and (c) discards too much of
  `AGENTS.md`'s actual cross-rule context to be a faithful SOP slot. This
  mission's spike (early implementation, not specify) must measure whether
  the extract's `SOPFile.byteLength` correlates with any baseline
  degradation on the reference model before this is treated as settled
  rather than provisional.
- **Decision — upstream PR timing (recommended: hold until after M3
  merges)**: this mission's fork-local branch and PR may be opened any
  time (it touches no file M3 touches), but the mission brief's own
  "PR upstream only when ripe" note is best read as: open the upstream PR
  to `Priivacy-ai/spec-kitty` after M3's PR #30 merges, so the upstream
  reviewer sees a directive→rule inventory FR-005 can actually cite,
  rather than a stub that will need a follow-up PR the moment M3 lands.
  This is a sequencing recommendation, not a spec requirement — it does
  not gate this mission's own local acceptance.
- **Unblocks**: nothing hard in the programme graph; delivers the only
  check class that sees layer interaction.
- **Concurrency wave**: wave 2, alongside M3 and M6-authoring — disjoint
  file trees from both once the separate `crosslayer.yml` decision above is
  followed.
- **Citation pinning**: architectural evidence about muster's crosslayer/
  RFC-1/openclaw-sop source (all file:line citations in this spec) pins to
  `624edd6dddedb86fb89f13084510f02b5a2c7d25`, confirmed identical to the
  fork's actually-consumed `@garrison-hq/muster@1.1.0`
  (`6bdb070dfa204a45f00a715ce5bd584c669444e6`) for every cited file. Claims
  about this mission's own repository pin to `c425bc188995b5b9a04bece05b511ba81896ce7f`
  (this mission's base commit on `main`). Neither citation type pins to
  `HEAD` or a branch name.
- **Real-CLI verification requirement** (operator directive): this mission
  cannot be accepted on unit tests or inspection alone. The built muster CLI
  must be run for real against the shipped manifests, the discrimination
  control (both flip and neutralize directions), and — for FR-005 — a live
  OpenAI or NVIDIA NIM endpoint (credentials from `~/dev/n8n-app-team/.env`,
  loaded as environment variables only, never logged or placed in argv),
  with actual exit codes and `--json` output recorded verbatim as evidence.
- **Cache-warm prerequisite for every `npx --offline` command in this
  spec** (verified against this fork's own documented convention,
  `conformance/README.md`'s "two-step cache-warm-then-offline procedure"):
  `npx --offline @garrison-hq/muster@1.1.0 ...` requires the pinned
  package already present in npm's local cache — a cold environment has
  nothing to be offline *with*. Before running any Verification command
  in the FR/C tables above on a machine or CI runner that has not already
  warmed the cache this session, first run
  `npm install --no-save @garrison-hq/muster@1.1.0` (network enabled,
  one-time) or rely on an existing `devDependency` restored via `npm ci`.
  `crosslayer.yml` (this mission's CI) performs the equivalent implicitly
  via `garrison-hq/muster-action@<pinned-sha>`, the same pattern
  `conformance.yml` already uses for the skills and doctrine jobs — this
  mission's workflow does not re-invent that mechanism.

## Scope Guard

Not in this mission:

- Grading fabricated persona fields (`voice`/`interaction`/`locale`/empty
  composition lists exist only to satisfy `resolveCompositionDetailed`'s
  structural requirement; C-003 forbids citing them as evidence for
  anything).
- Changing muster's C-005 layer-type set (`persona|sop|skill`) or its own
  crosslayer rubric surface — this mission consumes
  `contradiction-lint.ts`'s existing `"muster cross-layer rubric (2026)"`
  citation as-is (correction #12, recorded upstream in muster issue
  `garrison-hq/muster#60`; not this programme's job to fix for non-SK
  layers).
- Full `AGENTS.md` rule extraction as a general-purpose capability — one
  bounded policy extract is authored for this suite (OQ-6); this is not a
  reusable AGENTS.md-slicing tool.
- More than a 2-profile × 2-skill composition matrix; wider combinatorics
  are explicitly deferred, not attempted at reduced rigor.
- Editing the shared `.github/workflows/conformance.yml` — this mission's
  CI addition lives entirely in a new `crosslayer.yml` (Dependencies).
- Being, or becoming, an agent framework, a runtime, a prompt optimizer or
  generator, a registry, or a hosted service (muster's own scope guard,
  `BRIEF.md:83-108`) — the projector fabricates front-matter to satisfy a
  structural precondition; it does not generate personas for use outside
  this suite, and its output is never itself the thing under test.
