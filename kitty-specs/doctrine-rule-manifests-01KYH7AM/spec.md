# Feature Specification: Doctrine Rule Manifests

**Mission**: `doctrine-rule-manifests-01KYH7AM` (mission_id `01KYH7AMK2S2CQY18GE77CJEYS`)
**Created**: 2026-07-27
**Status**: Draft
**Mission Type**: software-dev
**Milestone**: muster ⇄ spec-kitty agent-conformance programme — wave 2, mission M3 (directives become machine-checkable)
**Input**: Bootstrap `conformance/doctrine/` in the spec-kitty fork: hand-authored SOP rule manifests, one per prioritised directive, with `sopFile:` pointing at the directive YAML itself so `ruleText` is a verbatim `integrity_rules` line and muster's `RULE_DRIFT` lint turns upstream directive edits into visible staleness. A jq gate parses `muster sop run <manifest> --json` output in CI — exit code alone is insufficient because drift findings are warnings that do not flip it. Zero muster changes; behavioral probes are deferred to wave-2 mission M4.
**Seeds**: GitHub issue `MOES-Media/spec-kitty#23` (this mission's source description, including the full FR/C requirement table, work-package/lane decomposition, acceptance criteria, discrimination control, normative citations, risks, and design decision D3 inlined verbatim in substance); `conformance/DECISIONS.md`'s D3 record (merged to `main` at `32722b5f1` — the programme's decision record of authority, cited here rather than restated); the muster ⇄ spec-kitty agent-conformance programme plan.

---

## Overview

Mission M1 (merged, `32722b5f1`) bootstrapped `conformance/` in the spec-kitty
fork with a skills-layer static manifest, a PR-gating CI workflow driven by
`garrison-hq/muster-action@v1`, and the programme's D1–D5 decision record.
That decision record's D3 entry already settles how this mission must work:
hand-author SOP rule manifests (not a generator), point each manifest's
`sopFile` at the directive YAML itself, and use the directive's own
`integrity_rules` lines verbatim as `ruleText` — so muster's existing
`RULE_DRIFT` static lint (`checkRuleTextPresence`,
`src/adapters/openclaw-sop/manifest.ts:426-446`) becomes, for free, a staleness
detector: whenever an upstream directive's wording changes, the manifest that
cites it goes stale and the lint says so.

This mission is the second wave-2 static signal (after M1's skills suite) in
the programme: it makes 13 of spec-kitty's 26 built-in directives
machine-checkable by muster's `openclaw-sop` adapter — the 9 directives that
are trace-decidable in whole or part (018, 028, 029, 030, 033, 034, 035, 042,
045) plus 4 proposed high-value judge directives (001, 010, 039, 044) — each
rule entry classed per the already-normative
`docs/rubric/sop-rule-taxonomy.md` (v1.0.0; 5 binary classes + 2 judge
classes; this mission cites those classes and adds zero new muster rubric
surface). Because all three static detectors
(`RULE_DRIFT`, `UNDEFINED_PRECEDENCE`, `TOOL_DRIFT`) emit `severity: "warning"`
and therefore never flip `muster sop run`'s exit code on their own
(`manifest.ts:343-446`), the mission also adds a CI step that parses
`muster sop run <manifest> --json` and fails the job on any `RULE_DRIFT` /
`MISSING_SOURCE` / `MANIFEST_ERROR` finding — proven to actually fire via a
single deliberately-drifted control manifest, because a jq gate matching
nothing (a typo) is indistinguishable from a healthy suite without an inverted
control.

Everything this mission does **not** do is deliberate and recorded: no
behavioral probes (`probeIds: []` throughout — probes are wave-2 mission M4's
concern), no manifest generator (D3's explicit rejection of that option at
this volume), no muster source change of any kind, and no coverage of
directive `038-structured-prompt-boundary` (carries neither `integrity_rules`
nor `validation_criteria`) or the advisory, non-numbered
`reconcile-change-scope-tensions` directive.

## User Scenarios & Testing

### Primary User Stories

1. **Spec-kitty contributor (PR gate)**: As a contributor opening a pull
   request against the spec-kitty fork, I want every hand-authored SOP rule
   manifest under `conformance/doctrine/` to be checked against muster's SOP
   static lint on every PR and push to `main`, so that a directive edit that
   silently invalidates a rule's `ruleText` (`RULE_DRIFT`), or a manifest
   authoring mistake (`MISSING_SOURCE`, `MANIFEST_ERROR`), is caught by CI
   before merge instead of discovered later as an unnoticed warning.

   **Why this priority**: This is the mission's core deliverable — a
   day-to-day-enforced conformance signal, not merely a one-time artifact.

   **Independent Test**: Flip one word inside a shipped manifest's `ruleText`
   locally (documented reproduction, not run in CI), run
   `muster sop run <manifest> --json`, and confirm the output contains a
   `RULE_DRIFT` finding for that rule and the jq gate exits non-zero;
   restoring the word restores a clean, zero-exit-code run.

   **Acceptance Scenarios**:

   1. **Given** a clean `conformance/doctrine/` tree, **When**
      `muster sop run <manifest> --json` is run for each shipped manifest,
      **Then** it exits `0` and the jq gate finds zero `RULE_DRIFT` /
      `MISSING_SOURCE` / `MANIFEST_ERROR` findings (AC-1, AC-2).
   2. **Given** a manifest with one word of a `ruleText` value deliberately
      changed so it is no longer a verbatim substring of its `sopFile`,
      **When** the same command and jq gate are run, **Then** the `--json`
      output contains a `RULE_DRIFT` finding for that rule and the jq gate
      exits non-zero (AC-2).

   ---

2. **Programme operator (second static signal / rule-inventory proof)**: As
   the operator driving the muster ⇄ spec-kitty agent-conformance programme,
   I want a real rule inventory — every rule traced to exactly one
   `sop-rule-taxonomy.md` class and one pinned upstream directive commit — so
   that D3's recommendation (hand-authored manifests keyed to `integrity_rules`
   verbatim) is proven out in the fork with a real, CI-enforced drift-coupling
   mechanism, closing the gap correction #11 identified: drift findings that
   exist but never gate anything.

   **Why this priority**: This closes the specific defect (silent-pass drift
   warnings) that motivates the entire mission; without it the manifests are
   inert documentation.

   **Independent Test**: Inspect any shipped manifest entry's `source` block
   and confirm `source.normative` cites a `docs/rubric/sop-rule-taxonomy.md`
   class and `source.supporting` cites the directive's GitHub URL pinned to a
   commit SHA; run the discrimination control (Story 1's independent test) to
   confirm the coupling is live, not merely declared.

   **Acceptance Scenarios**:

   1. **Given** the control manifest under `conformance/doctrine/control/`
      (deliberately drifted `ruleText`), **When** CI runs it through
      `muster sop run --json`, **Then** the output contains a `RULE_DRIFT`
      finding, CI-asserted (AC-3).

   ---

3. **Wave-2 mission M4 author (probe-attachment surface)**: As the author of
   the follow-on behavioral-probes mission (M4, `MOES-Media/spec-kitty#24`), I
   want this mission's manifests already scoped, `gradingClass`/`aggregation`-
   classed, and `probeIds: []`-scaffolded against a documented directive→class
   mapping, so I can attach real probes to an existing, validated rule
   inventory instead of re-deriving directive-to-class mappings and the
   9-trace-decidable / judge split from scratch.

   **Why this priority**: Lower priority than the gate itself, but this is the
   mission's explicit unblocking value for the next wave-2 mission.

   **Independent Test**: Read `conformance/doctrine/README.md`'s
   directive→class mapping table and coverage roadmap and confirm every
   in-scope directive (13 of 26) is accounted for with its taxonomy class,
   and every out-of-scope directive is named with a reason.

   **Acceptance Scenarios**:

   1. **Given** `conformance/doctrine/README.md`, **When** a reader looks up
      any of the 13 in-scope directives, **Then** they find its
      `sop-rule-taxonomy.md` class, its manifest file, and — for directives
      not yet covered — a roadmap entry explaining the remaining gap (FR-006).

### Edge Cases

- **Upstream directive text edit**: if a directive's `integrity_rules` line
  wording changes upstream, the manifest's `ruleText` no longer matches
  verbatim and `RULE_DRIFT` fires — this is the mission working as intended
  (a true positive, not a defect), at the cost of maintenance time budgeted in
  the issue's risk #1.
- **Multi-line YAML folding**: `integrity_rules` entries authored as folded or
  literal YAML block scalars can introduce whitespace or line-break
  differences that break a naively copy-pasted `ruleText`'s verbatim-substring
  match even though the *meaning* is unchanged — mitigated by quoting exact
  scalars during authoring and manually verifying each rule against AC-2's
  reproduction before commit (issue risk #2).
- **jq gate silently matching nothing**: a typo in the jq filter (e.g. a
  misspelled finding `kind`) would make the gate always report zero matches,
  indistinguishable from a genuinely clean suite — this is exactly why FR-005's
  inverted control manifest exists and is CI-asserted (AC-3), not merely
  documented as a manual check.
- **`038` and `reconcile-change-scope-tensions` excluded**: `038` carries
  neither `integrity_rules` nor `validation_criteria` (verified fact, not
  re-derived), so no `ruleText` source exists for it; `reconcile-change-scope-
  tensions` is advisory-enforcement and carries no numeric directive code —
  both are out of scope by construction, not by oversight.
- **Manifest fails to load at all**: a schema violation or a semantic-check
  throw from the loader (duplicate `ruleId`; empty `source.normative`;
  `pass-k` with `passThreshold !== k`; a `confirm-before-destructive`
  assertion missing `confirmationKind`) is a hard `MANIFEST_ERROR`-class
  failure, not a warning — AC-1 requires every shipped (non-control) manifest
  to load and exit `0`, so this class of failure must not occur in the
  delivered manifests.

## Requirements

### Functional Requirements

| ID | Statement | Status |
|----|------------|--------|
| FR-001 | Manifests cover the 9 trace-decidable directives (018, 028, 029, 030, 033, 034, 035, 042, 045) plus ≥4 high-value judge directives (proposed: 001, 010, 039, 044); each rule's `ruleText` is a verbatim `integrity_rules` line (RULE_DRIFT-clean by construction). | Proposed |
| FR-002 | `gradingClass`/`aggregation` per the sop-rule-taxonomy classes: pass-k with `passThreshold == k` for safety-critical rules (045 no-direct-push, 029 signing), k-of-n for stylistic; the loader's own semantic checks must pass (`manifest.ts:283-321`). | Proposed |
| FR-003 | Every entry: `source.normative` = `docs/rubric/sop-rule-taxonomy.md` §class; `source.supporting` = `https://github.com/Priivacy-ai/spec-kitty/blob/<SHA>/src/doctrine/directives/built-in/<file>` — the C-002 pattern with the directive as the pinned upstream doc. | Proposed |
| FR-004 | CI job: for each manifest, `muster sop run <manifest> --json` must exit 0 **and** contain zero findings of kind `RULE_DRIFT`/`MISSING_SOURCE`/`MANIFEST_ERROR` (jq gate — required because drift findings are warnings and do not flip the exit code, correction #11). `UNDEFINED_PRECEDENCE`/`TOOL_DRIFT` warnings are reported, not gating, in v1. | Proposed |
| FR-005 | One control manifest under `conformance/doctrine/control/` with deliberately drifted `ruleText`; CI asserts its `--json` output **does** contain `RULE_DRIFT` (discrimination for the drift detector itself). | Proposed |
| FR-006 | `conformance/doctrine/README.md` records the directive→class mapping table (mirroring the M2 (`garrison-hq/muster#58`) appendix) and the coverage roadmap for the remaining directives. | Proposed |

No Non-Functional Requirements beyond the issue's FR/C set are added by this
spec. The issue (`MOES-Media/spec-kitty#23`) defines none, and this project's
measured-not-asserted policy rejects invented, unmeasured thresholds (an
earlier mission's proposed "<3 min CI" NFR was rejected on exactly this
ground). If a genuinely measurable NFR becomes evidence-backed during
planning or implementation (for example, a real workflow `run_id`'s
wall-clock minutes once this manifest step lands in the shared CI job), it
will be added then, flagged as author-added, per house precedent (M1's
`NFR-001`).

### Constraints

| ID | Statement | Status |
|----|------------|--------|
| C-001 | Diff touches only `conformance/**` and the workflow file. | Proposed |
| C-002 | Fully offline on PRs; no secrets. | Proposed |
| C-003 | No probe entries in v1 (`probeIds: []`); the manifests must load under the current published muster (pinned version from M1). | Proposed |

### Key Entities

- **Rule manifest** (`SOPRuleManifest`, one YAML file per prioritised
  directive or small thematic group under `conformance/doctrine/`): carries
  `version`, `sopFile` (the directive YAML itself, path relative to the
  manifest), and `rules[]`.
- **Rule entry** (`SOPRuleManifestEntry`): `ruleId`, `ruleText` (verbatim
  `integrity_rules` line), `probeIds` (`[]` in v1, C-003), `gradingClass`
  (`binary`/`judge`), `aggregation` (`pass-k`/`k-of-n`), `k`,
  `passThreshold?`, `precedence?`, `source: {normative, supporting?}`.
- **Control manifest** (`conformance/doctrine/control/`): a manifest entry
  with a deliberately drifted `ruleText`, expected to trigger `RULE_DRIFT` —
  the mechanism by which the suite proves its drift detector actually fires
  (FR-005).
- **Directive→class mapping table & coverage roadmap**
  (`conformance/doctrine/README.md`, FR-006): documents which of the 26
  built-in directives map to which of the 7 `sop-rule-taxonomy.md` classes,
  which 13 are covered by this mission, and which remain for a later mission.
- **CI jq gate** (addition to the shared `.github/workflows/conformance.yml`,
  C-001): parses each manifest's `muster sop run --json` output and fails the
  job if any finding's `kind` is `RULE_DRIFT`, `MISSING_SOURCE`, or
  `MANIFEST_ERROR`.

## Success Criteria

- **SC-001**: A person or CI system can run one local command per manifest and
  get a pass/fail conformance signal for each of the 13 prioritised
  directives, with no network access required beyond the pinned muster
  package already cache-warmed by M1's documented procedure.
- **SC-002**: Every pull request and every push to `main` automatically
  receives this conformance signal via the existing shared CI workflow, gated
  on parsed finding kinds rather than on the muster CLI's bare exit code
  alone.
- **SC-003**: The suite provably discriminates: a deliberately drifted rule is
  reported as drift by the same mechanism a real upstream directive edit
  would trigger, and this is asserted in CI rather than left as a manual-only
  check.
- **SC-004**: Every rule in every shipped manifest traces to exactly one
  normative taxonomy class and one commit-pinned upstream directive, so no
  rule's grading basis is ambiguous or undocumented.
- **SC-005**: The directive→class mapping and remaining-coverage roadmap are
  durably recorded in a reviewable document, so a later mission can extend
  coverage or attach behavioral probes without re-deriving the mapping from
  the directive corpus.

## Dependencies & Assumptions

- **Depends on**: M1 (`MOES-Media/spec-kitty#22`, merged to `main` at
  `32722b5f1`) — this mission needs only M1's `conformance/` directory layout
  and the `.github/workflows/conformance.yml` CI skeleton it created. It does
  not depend on any of M1's skills-specific content.
- **Sequencing constraint**: `.github/workflows/conformance.yml` is shared
  with M1's step across missions. This is safe sequentially (this mission
  adds one more step) but not in parallel — this mission rebases on M1's
  merged state and does not run concurrently with any other mission editing
  that same file.
- **Unblocks**: M4 (`MOES-Media/spec-kitty#24`) — produces the rule inventory
  M4's behavioral probes attach `probeIds` to; the programme's second static
  signal.
- **Concurrency wave**: wave 2, alongside M6 (`MOES-Media/spec-kitty#25`)
  authoring and M7 (`MOES-Media/spec-kitty#26`) — disjoint trees in the fork,
  no shared-file conflict with this mission's scope.
- **Normative source, not restated here**: `docs/rubric/sop-rule-taxonomy.md`
  is already normative at v1.0.0 (5 binary classes: `never-call-tool`,
  `tool-order`, `confirm-before-destructive`, `exact-string-non-leakage`,
  `output-format`; 2 judge classes: `refusal-quality`,
  `tone-persona-adherence`). This mission cites these existing classes and
  defines none of its own.
- **Decision record, cited not restated**: `conformance/DECISIONS.md`'s D3
  entry (merged at `32722b5f1`) is this mission's design-decision record of
  authority — hand-authored manifests over a generator, `sopFile` = the
  directive YAML, `integrity_rules` → `ruleText`, `validation_criteria` →
  judge `rubricText`. D3's "what would change my mind" trigger (>~1
  stale-manifest PR/month) is the pressure valve for revisiting the
  hand-authoring decision; it is not expected to fire during this mission.
- **Citation pinning** (per `DECISIONS.md`'s two-baseline rule): claims about
  behaviour this mission's CI actually executes (`muster sop run --json`
  exit codes and finding output) pin to the exact muster version M1 pinned in
  `.github/workflows/conformance.yml`/`conformance/README.md`; architectural
  evidence about muster's manifest-loader semantics (`manifest.ts:283-321`,
  `:426-446`) pins to the immutable SHA at which each citation is confirmed
  true. Neither citation type ever pins to "HEAD".
- **Directive corpus** (verified fact, not re-derived): 26 built-in directives
  under `src/doctrine/directives/built-in/*.directive.yaml`; the schema
  requires only `id, schema_version, title, intent, enforcement`; 25 of 26
  carry `integrity_rules` and `validation_criteria`; `038-structured-prompt-
  boundary` carries neither; one directive
  (`reconcile-change-scope-tensions`) has no numeric code and is
  advisory-enforcement.
- **Real-CLI verification requirement** (operator directive, carried into
  this mission's later phases): this mission cannot be accepted on inspection
  of the manifests alone. Someone must run the built muster CLI for real
  against the shipped manifests and the control manifest, and record actual
  exit codes and `--json` output verbatim — a prose summary of expected
  behaviour is not sufficient evidence at any later gate.
- **Assumption**: `conformance/doctrine/**` manifests resolve `sopFile` paths
  relative to the manifest's own directory, consistent with muster's existing
  manifest-relative path-resolution behaviour used by M1's skills manifest.

## Scope Guard

Carried verbatim in substance from the mission source (issue
`MOES-Media/spec-kitty#23`, section 4) — not in this mission:

- Behavioral probes (`probeIds: []` throughout — shipped-example precedent
  `examples/sop/manifest.yaml`); these are wave-2 mission M4's concern.
- A manifest generator (D3's explicit recommendation against one at this
  volume).
- muster severity changes (open question OQ-4 — a `--mode strict` escalation
  of `RULE_DRIFT`/`MISSING_SOURCE` to errors is a possible future muster-side
  follow-up, not this mission's to make).
- Directive `038-structured-prompt-boundary` (no `integrity_rules`) and the
  advisory `reconcile-change-scope-tensions` directive.
- AGENTS.md-as-SOP (M7's concern, a different SOP artifact entirely).
