# `conformance/doctrine/` — SOP Doctrine Rule Manifests

This directory is the spec-kitty fork's second static signal in the muster ⇄
Spec Kitty agent-conformance programme, wave 2, mission M3
(`doctrine-rule-manifests-01KYH7AM`, seed `MOES-Media/spec-kitty#23`). It
makes 13 of spec-kitty's 26 built-in directives (`src/doctrine/directives/
built-in/*.directive.yaml`) machine-checkable via
[`@garrison-hq/muster`](https://github.com/garrison-hq/muster)'s
`openclaw-sop` adapter: each directive gets a hand-authored SOP rule
manifest whose `sopFile:` points at the directive YAML itself, so muster's
own `RULE_DRIFT` static lint turns an upstream directive-wording edit into
a visible drift finding — for free, with zero muster changes (C-001).

This suite makes **zero changes to muster**. It consumes muster as an
external, pinned, published `npx`-invoked CLI (`@garrison-hq/muster@1.1.0`,
the same exact pin M1's skills suite uses), never as a source dependency.

## A. Directive → class mapping table (FR-006)

45 rule entries across 13 directives. **Coverage** column: `full-line` means
`ruleText` is the rule's complete `integrity_rules` bullet, copied
byte-for-byte from the directive file; `fragment` means `ruleText` is the
longest contiguous substring of the rule that lies entirely on one physical
line of the directive file's raw bytes (10 of 45 rules wrap across a
physical line break in the raw YAML and cannot be cited in full — see
`contracts/rule-classification-and-citation.md` for the fragment provenance
and uniqueness verification of each one). **Class** `UNMAPPED` means no
existing `sop-rule-taxonomy.md` class fits the rule; `gradingClass: judge`
is then the schema's structural fallback, not a real class assignment.

| Directive | ruleId | Coverage | Class | gradingClass |
|---|---|---|---|---|
| 001 | 001-r1 | full-line | UNMAPPED | judge |
| 001 | 001-r2 | full-line | UNMAPPED | judge |
| 001 | 001-r3 | full-line | UNMAPPED | judge |
| 010 | 010-r1 | full-line | output-format | binary |
| 010 | 010-r2 | full-line | output-format | binary |
| 018 | 018-r1 | full-line | output-format | binary |
| 018 | 018-r2 | full-line | output-format | binary |
| 028 | 028-r1 | full-line | never-call-tool | binary |
| 028 | 028-r2 | full-line | output-format | binary |
| 028 | 028-r3 | full-line | never-call-tool | binary |
| 029 | 029-r1 | full-line | never-call-tool | binary |
| 029 | 029-r2 | full-line | never-call-tool | binary |
| 030 | 030-r1 | full-line | tool-order | binary |
| 030 | 030-r2 | full-line | UNMAPPED | judge |
| 030 | 030-r3 | full-line | output-format | binary |
| 033 | 033-r1 | full-line | never-call-tool | binary |
| 033 | 033-r2 | full-line | UNMAPPED | judge |
| 034 | 034-r1 | full-line | tool-order | binary |
| 034 | 034-r2 | full-line | output-format | binary |
| 034 | 034-r3 | full-line | UNMAPPED | judge |
| 035 | 035-r1 | full-line | output-format | binary |
| 035 | 035-r2 | full-line | tool-order | binary |
| 035 | 035-r3 | full-line | UNMAPPED | judge |
| 039 | 039-r1 | full-line | UNMAPPED | judge |
| 039 | 039-r2 | full-line | UNMAPPED | judge |
| 039 | 039-r3 | full-line | UNMAPPED | judge |
| 039 | 039-r4 | full-line | UNMAPPED | judge |
| 039 | 039-r5 | full-line | UNMAPPED | judge |
| 039 | 039-r6 | full-line | UNMAPPED | judge |
| 039 | 039-r7 | full-line | UNMAPPED | judge |
| 039 | 039-r8 | full-line | UNMAPPED | judge |
| 039 | 039-r9 | full-line | UNMAPPED | judge |
| 039 | 039-r10 | full-line | UNMAPPED | judge |
| 039 | 039-r11 | full-line | UNMAPPED | judge |
| 042 | 042-r1 | fragment | never-call-tool | binary |
| 042 | 042-r2 | fragment | output-format | binary |
| 042 | 042-r3 | fragment | output-format | binary |
| 042 | 042-r4 | full-line | output-format | binary |
| 044 | 044-r1 | fragment | UNMAPPED | judge |
| 044 | 044-r2 | fragment | UNMAPPED | judge |
| 044 | 044-r3 | fragment | UNMAPPED | judge |
| 045 | 045-r1 | fragment | never-call-tool | binary |
| 045 | 045-r2 | fragment | never-call-tool | binary |
| 045 | 045-r3 | fragment | tool-order | binary |
| 045 | 045-r4 | fragment | tone-persona-adherence | judge |

**Summary counts**: 45 rules total across 13 directives; 10 fragment-cited
rules (042×3, 044×3, 045×4), 35 full-line rules. **24 rules mapped to an
existing class**: `never-call-tool`×8, `output-format`×11, `tool-order`×4,
`confirm-before-destructive`×0 (this mission ships zero examples of that
class — not a structural problem, the taxonomy does not require every
mission to exercise every class), `tone-persona-adherence`×1. **21 rules
UNMAPPED (judge-fallback)**: all 11 of directive 039, all 3 of 001, all 3 of
044, plus one each from 030, 033, 034, 035. The full per-rule fit rationale
(why each class was picked, including the two post-plan-gate reclassification
passes) lives in `kitty-specs/doctrine-rule-manifests-01KYH7AM/contracts/
rule-classification-and-citation.md` — this table is a rendering of that
document's facts, not a second canonical copy.

## B. Cross-repo note

`docs/rubric/sop-rule-taxonomy.md` — the normative source every mapping-table
row above cites in each manifest entry's `source.normative` field — lives
only in the `garrison-hq/muster` package, **not in this repository**. Do not
go looking for that file here; it is cited by path as an external normative
document, the same C-002 citation pattern this mission uses for the
directive files themselves (pinned by upstream commit SHA, cited not
restated).

## C. Citation-anchor deviation note

Every manifest entry's `source.normative` in this mission cites
`docs/rubric/sop-rule-taxonomy.md#<class-anchor>` (e.g.
`#1-never-call-tool`, `#judge-required-rule-classes`), appending a
`#<anchor>` fragment. The taxonomy's own "Citation Format for Manifest
Entries" section specifies the literal string `"docs/rubric/
sop-rule-taxonomy.md"` with **no anchor**. This mission deviates
deliberately, for reader precision (a human can jump straight to the exact
class section rather than the top of a long document) — this is harmless to
muster's own loader, which only checks that `source.normative` is a
non-empty string and does not compare against the literal path. It is not
an oversight; it is recorded here explicitly so a future reader does not
have to wonder.

## D. Coverage roadmap

The 13 directives this mission covers are listed in section A above. The 13
built-in directives this mission does **not** cover, with a reason for each:

| Directive | Title | Why not covered |
|---|---|---|
| 003-decision-documentation-requirement | Decision Documentation Requirement | Not in this mission's prioritised set (issue #23); candidate for a future coverage-extension mission |
| 024-locality-of-change | Locality of Change | Not in this mission's prioritised set; candidate for future coverage |
| 025-boy-scout-rule | Boy Scout Rule | Not in this mission's prioritised set; candidate for future coverage |
| 031-context-aware-design | Context-Aware Design | Not in this mission's prioritised set; candidate for future coverage |
| 032-conceptual-alignment | Conceptual Alignment | Not in this mission's prioritised set; candidate for future coverage |
| 036-black-box-integration-testing | Black-Box Integration Testing | Not in this mission's prioritised set; candidate for future coverage |
| 037-living-documentation-sync | Living Documentation Sync | Not in this mission's prioritised set; candidate for future coverage |
| 038-structured-prompt-boundary | Structured Prompt Change-Boundary | **Excluded by construction, not oversight**: carries neither `integrity_rules` nor `validation_criteria` — no `ruleText` source exists for it (verified: `grep -c "^integrity_rules:"` on the real file returns `0`) |
| 040-recurring-bug-structural-intervention | Recurring-Bug Structural-Intervention Discipline | Not in this mission's prioritised set; candidate for future coverage |
| 041-tests-as-scaffold-not-friction | Tests as Scaffold, Not Friction | Not in this mission's prioritised set; candidate for future coverage |
| 043-close-defect-class-by-construction | Close Defect Classes by Construction | Not in this mission's prioritised set; candidate for future coverage |
| 046-readable-consistent-prs | Readable and Consistent Pull Requests | Not in this mission's prioritised set; candidate for future coverage |
| reconcile-change-scope-tensions | Reconciling Change-Scope Tensions | **Excluded by construction**: advisory-enforcement, carries no numeric directive code — a different kind of artifact than the numbered directives this mission targets |

## E. Local invocation — the pre-PR command

Run this before opening a pull request:

```sh
bash conformance/scripts/check-doctrine-drift-gate.sh \
  && node conformance/scripts/check-doctrine-manifest-completeness.mjs \
  && echo "doctrine conformance: both checks green"
```

This is the exact sequence a contributor runs locally, and the exact
sequence the `sop-doctrine-conformance` CI job (below) runs.

1. **`check-doctrine-drift-gate.sh`** (FR-004/FR-005) — runs
   `muster sop run <manifest> --json` for each of the 13 shipped manifests
   under `conformance/doctrine/*.yaml` and asserts zero findings of kind
   `RULE_DRIFT`, `MISSING_SOURCE`, `MANIFEST_ERROR`, or `STRUCTURAL_ABSENCE`
   (Phase 1), then runs the control manifest
   (`conformance/doctrine/control/045-drifted.yaml`) and asserts, inverted
   polarity, that it **does** produce at least one `RULE_DRIFT` finding
   (Phase 2) — proof the drift detector itself actually fires. See
   `kitty-specs/doctrine-rule-manifests-01KYH7AM/contracts/
   doctrine-drift-gate-contract.md` for the full CLI contract.
2. **`check-doctrine-manifest-completeness.mjs`** (absence guard,
   author-added) — closes the one gap muster's own error paths do not
   cover: a rule entry silently dropped from a manifest produces no finding
   of any kind and a clean `exit 0` from muster itself. Recomputes, fresh on
   every run, the expected `integrity_rules` count per directive and the
   actual `- ruleId:` count per manifest, and asserts they match for all 13
   directives, plus that the control manifest exists with exactly 1 rule
   entry. See `kitty-specs/doctrine-rule-manifests-01KYH7AM/contracts/
   doctrine-manifest-completeness-contract.md`.

**Division of responsibility between the two scripts (deliberate, stated
explicitly so it is never assumed to be double-covered).** The completeness
script pairs each manifest to its directive **by filename stem only**
(`conformance/doctrine/<directive-stem>.yaml`) and never reads or validates
a manifest's `sopFile:` field — a manifest can exist at the right path, with
the right rule count, and still point `sopFile:` at a deleted directive file
or a typo'd path, and the completeness script reports `OK` for it regardless.
**The drift gate's `STRUCTURAL_ABSENCE` jq filter entry is the sole guard**
against a dangling or typo'd `sopFile:` target. Neither script re-implements
the other's guard; both must run for the suite to be considered complete.

Both scripts must exit `0` for the suite to be considered green.

## F. CI gate and timing

`.github/workflows/conformance.yml`'s `sop-doctrine-conformance` job (added
by this mission, alongside M1's pre-existing `skills-conformance` job in the
same shared file) runs the same two checks as the local pre-PR command
above, on every pull request and every push to `main`. It requires **no
repository secrets** (C-002) — resolving `npx @garrison-hq/muster@1.1.0`
needs only normal npm-registry network access, which a GitHub-hosted runner
has by default, so the job is designed to also pass on a fork PR with zero
repository secrets available.

### CI timing (measured, never asserted)

Per this project's measured-not-asserted CI-budget policy, this table
records a real workflow run's `run_id` and actual wall-clock minutes once
one exists — no ceiling is asserted anywhere in this file.

| `run_id` | Wall-clock minutes | Job | Fork-PR, no-secret confirmed? |
|---|---|---|---|
| [`30227861005`](https://github.com/MOES-Media/spec-kitty/actions/runs/30227861005) | 0.4 | `skills-conformance` (M1) | Not observed on a fork PR |
| _pending_ | _pending_ | `sop-doctrine-conformance` (this mission) | **Not yet recorded — see "Known gaps" below.** |

**Known gap, disclosed rather than fabricated**: this WP's own operating
constraints prohibited pushing to a remote branch or opening a pull request
against `MOES-Media/spec-kitty`, so T020's real GitHub Actions run could not
be produced during this implementation pass. Every check in this row is
fully verified **locally** against the real built muster CLI (see
"Verification transcripts" in the mission work log), but the row above is
left as `_pending_` rather than filled with a fabricated `run_id` — this
project's own measured-not-asserted policy rejects invented figures. A
human with push/PR authority must open the PR, observe the real run, and
fill in this row before this line item of the mission's Definition of Done
can be marked complete.

## G. `TOOL_DRIFT` exercise disclosure

**`TOOL_DRIFT` is unexercised across this mission — disclosed here plainly,
not omitted.** `detectToolDrift` (`index.ts:128-144` in the muster package)
is skipped entirely unless the `sop run` invocation passes `--env-tools`.
None of this mission's `sop run` invocations — not WP01's or WP02's
per-manifest verification, and not this WP's own
`check-doctrine-drift-gate.sh` — pass `--env-tools`. That means every
"zero `TOOL_DRIFT`" result recorded anywhere in this mission's work log
proves nothing: the detector never ran, it wasn't observed clean. Rules
`033-r1`, `042-r3`, `042-r4`, `045-r1`, and `045-r2` contain backticked
identifiers (literal command strings such as `` `git add -A` ``,
`` `git push --force` ``) that would be genuine `TOOL_DRIFT` candidates if
the detector were exercised for real.

This mission does not exercise `--env-tools` for real (out of scope for
this WP — `sop run`'s CLI surface would need an environment-tools fixture
constructed and verified, which is not part of this WP's authoring
surface). An unexercised detector silently reported as "clean" is the same
failure shape as an unfired discrimination control (see the FR-005 control
above) — this mission has already spent significant effort guarding against
exactly that class of false-clean signal (see `STRUCTURAL_ABSENCE` in the
local-invocation section above), and applies the same honesty standard
here: **unexercised, not clean.**

`checkRuleTextPresence` (the source of every `RULE_DRIFT` result reported
in this mission) always runs regardless of `--env-tools`, so the zero-
`RULE_DRIFT` results recorded for the 13 shipped manifests elsewhere in this
mission's work log remain genuine and are not affected by this disclosure.

## What this suite does not do

- It does not check `UNDEFINED_PRECEDENCE` or `TOOL_DRIFT` findings — both
  are reported, not gating, per FR-004; `TOOL_DRIFT` is additionally
  unexercised entirely (see section G above).
- It does not validate a manifest's `sopFile:` target existence in the
  completeness script — that is the drift gate's `STRUCTURAL_ABSENCE`
  filter entry's job alone (see the division-of-responsibility note in
  section E above).
- It does not include behavioral probes (`probeIds: []` throughout) — probes
  are wave-2 mission M4's concern.
- It does not modify muster or any `src/doctrine/**` directive file. The 13
  manifests reference those files read-only via `sopFile:`.
