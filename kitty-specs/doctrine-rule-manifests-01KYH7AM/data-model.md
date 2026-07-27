# Data Model: Doctrine Rule Manifests

**Mission**: `doctrine-rule-manifests-01KYH7AM` | **Date**: 2026-07-27

Every entity here is **data** (YAML/Markdown/scripts under `conformance/**`
plus one shared workflow file) — nothing modifies `src/doctrine/**`
(spec-kitty runtime) or any muster source file (C-001). Structural types
(`SOPRuleManifest`, `SOPRuleManifestEntry`) are muster's own, fixed by
`src/adapters/openclaw-sop/manifest.ts` — this mission does not redefine
them, only populates them per `contracts/doctrine-rule-manifest-shape.md`.

---

### DoctrineRuleManifest

One file per in-scope directive under `conformance/doctrine/`, 13 total —
an instance of muster's `SOPRuleManifest`:

```yaml
version: "1.0.0"
sopFile: "../../src/doctrine/directives/built-in/<directive-file>"
rules: [ DoctrineRuleManifestEntry, ... ]
```

**Invariants**:
- Exactly 13 shipped manifests, one per FR-001's directive list (018, 028,
  029, 030, 033, 034, 035, 039, 042, 045, 001, 010, 044) — 9 trace-decidable
  + 4 proposed judge.
- `sopFile` resolves, relative to the manifest's own directory, to the real
  directive YAML under `src/doctrine/directives/built-in/` — never a copy,
  never a reflowed/edited version (spec Edge Cases rejects reflowing
  outright, C-001 forbids it structurally).
- `rules[]` length matches the directive's own `integrity_rules` bullet
  count exactly (001→3, 010→2, 018→2, 028→3, 029→2, 030→3, 033→2, 034→3,
  035→3, 039→11, 042→4, 044→3, 045→4 — sums to 45), enforced by
  `check-doctrine-manifest-completeness.mjs` on every run, not trusted as a
  one-time authoring fact.

---

### DoctrineRuleManifestEntry

An instance of muster's `SOPRuleManifestEntry`:

| Field | Value in this mission |
|---|---|
| `ruleId` | `<directive-number>-r<n>`, sequential, unique within the file |
| `ruleText` | verbatim full `integrity_rules` line (35 of 45), or a single-line fragment per the spec's fragment convention (10 of 45 — see `contracts/rule-classification-and-citation.md`) |
| `probeIds` | `[]` always (C-003) |
| `gradingClass` | `"binary"` (25 rules) or `"judge"` (20 rules, including all 20 UNMAPPED fallbacks) — full per-rule assignment in `contracts/rule-classification-and-citation.md` |
| `aggregation` | `"pass-k"` for every binary entry, `"k-of-n"` for every judge entry (matches the taxonomy's two-tier model uniformly, not case-by-case) |
| `k` | `3` (binary) or `5` (judge) |
| `passThreshold` | `3` in both cases — equal to `k` for binary (loader-enforced invariant), the taxonomy's documented `Math.ceil(k/2)` majority default made explicit for judge |
| `precedence` | never set — no two rules within any one manifest share a `triggerPrefix` (text before the first comma/period, lowercased) with conflicting `gradingClass`/`aggregation`; `detectUndefinedPrecedence` is warning-only regardless (FR-004), but the risk is checked, not assumed, at real-CLI verification time (quickstart.md) |
| `source.normative` | `"docs/rubric/sop-rule-taxonomy.md#<class-anchor>"` — a specific class anchor for the 25 mapped rules, `#judge-required-rule-classes` for the 20 UNMAPPED |
| `source.supporting` | `"https://github.com/Priivacy-ai/spec-kitty/blob/<SHA>/src/doctrine/directives/built-in/<file>"`, `<SHA>` = the directive file's last-touch commit upstream, verified byte-identical (research.md §5) |

**Invariant — the four loader guards** (binding constraint 3): every entry
in every one of the 14 files (13 shipped + control) satisfies all four
guards in `manifest.ts:283-321` by construction, not by luck — see
`contracts/doctrine-rule-manifest-shape.md`'s guard table. In particular,
**no entry anywhere in this mission sets an `assertionKind` field**, which
is what makes the `confirm-before-destructive`-without-`confirmationKind`
guard structurally unreachable here (research.md §6).

---

### RuleClassAssignment (the FR-006/binding-constraint-2 mapping)

Not a runtime type — a planning/documentation entity: the full 45-row table
in `contracts/rule-classification-and-citation.md`, feeding both each
manifest entry's `gradingClass`/`source.normative` and
`conformance/doctrine/README.md`'s FR-006 table.

**Invariant**: every one of the 45 rules has exactly one row, with an
explicit fit-quality tag (`clean` / `best-fit (caveat)` / `UNMAPPED`) — no
rule is left implicitly "the class" without a stated reason. **20 of 45
rows are `UNMAPPED`** (44%): all 11 of directive 039, all 3 of 001, all 2
of 010, and one rule each from 030, 033, 034, 035 — recorded as a named,
open taxonomy gap in the README's coverage roadmap, not silently defaulted.

---

### ControlManifest

`conformance/doctrine/control/045-drifted.yaml` — one `DoctrineRuleManifestEntry`
whose `sopFile` points at the **real** `045-prs-only-and-read-intent.directive.yaml`
but whose `ruleText` is deliberately mutated ("must never run" replacing the
real file's "must not run" in the flagship fragment).

**Invariants**:
- `ruleText` must return `grep -F -c` = `0` against the real directive file
  (verified during planning; must be re-verified whenever this file is
  touched — the control's entire purpose depends on this staying `0`).
- Excluded from the FR-004 "must be clean" gate loop (its own file lives
  under `control/`, not matched by `conformance/doctrine/*.yaml`'s glob).
- Asserted, inverted, by CI: `muster sop run` on this file **must** produce
  at least one `RULE_DRIFT` finding (FR-005/AC-3) — a failure to
  discriminate is itself a CI failure, the opposite polarity of every other
  gate in this mission (mission brief's constraint 7).
- Excluded from `check-doctrine-manifest-completeness.mjs`'s per-directive
  count comparison (it doesn't correspond 1:1 with a directive's full rule
  set); checked instead for bare existence + exactly 1 rule entry by that
  same script (so a control that is deleted, or hollowed to zero rules, is
  also caught — closing the "control silently deleted" absence case).

---

### DriftGateResult

Runtime output shape `conformance/scripts/check-doctrine-drift-gate.sh`
reasons about (not a file it writes — a description of what it inspects
per manifest, drawn directly from muster's real `SOPSuiteReport`):

```typescript
interface DriftGateCheck {
  manifestPath: string;
  museterExitCode: 0 | 1 | 2;               // muster's own exit code
  disallowedFindings: SOPLintFinding[];      // kind in {RULE_DRIFT, MISSING_SOURCE, MANIFEST_ERROR}
  gatePassed: boolean;                        // disallowedFindings.length === 0 (inverted for the control)
}
```

**Invariants**:
- For all 13 shipped manifests: `disallowedFindings.length === 0` required.
- For the control manifest: the analogous filter restricted to
  `kind === "RULE_DRIFT"` must have `length >= 1` (inverted).
- `TOOL_DRIFT` and `UNDEFINED_PRECEDENCE` findings are never inspected by
  this gate (reported-not-gating, FR-004) — `TOOL_DRIFT` is additionally
  confirmed unreachable via the CLI path at all (research.md §2), so its
  permanent absence from every report is expected, not evidence of
  anything.

---

### ManifestCompletenessResult (absence guard, author-added)

```typescript
interface DoctrineCompletenessResult {
  perDirective: Array<{
    directiveStem: string;               // e.g. "045-prs-only-and-read-intent"
    expectedRuleCount: number;           // from the directive's integrity_rules block
    manifestPath: string;
    manifestExists: boolean;
    actualRuleCount: number;             // 0 if manifest missing
    ok: boolean;                          // actualRuleCount === expectedRuleCount && manifestExists
  }>;
  controlOk: boolean;                     // control manifest exists with exactly 1 rule
  ok: boolean;                             // every perDirective.ok === true && controlOk
}
```

**Invariants**:
- Exit `0` iff `ok === true`; exit `1` otherwise, naming every offending
  directive/manifest explicitly (never a bare aggregate count).
- Recomputes `expectedRuleCount` fresh from the real directive files on
  every run — never a hardcoded lookup table — so a *future* directive edit
  that adds/removes an `integrity_rules` bullet is caught the same way a
  manifest edit that drops a rule entry is.

---

### ConformanceWorkflow (addition to the shared file)

`.github/workflows/conformance.yml` gains:
- A renamed top-level `name:` (`Skills Static Conformance` →
  `Static Conformance`) — the file now hosts two distinct suites.
- A workflow-level `permissions: contents: read` block — new in this
  mission; the existing file had no `permissions:` block at all (verified,
  not assumed — research.md §9).
- One new job, `sop-doctrine-conformance` (job-level `name: SOP doctrine
  conformance (muster)`), independent of and parallel-safe with the
  existing `skills-conformance` job (disjoint file sets in both jobs'
  effective read scope; neither job writes anything).

**Invariants**:
- No `secrets:` reference anywhere in the new job (C-002) — identical
  no-secret posture to the sibling job.
- The new job's `actions/checkout` step uses the same `@v6` tag reference
  as the sibling job (internal consistency; SHA-pinning both jobs together
  is a documented, not-fixed-here follow-up — research.md §9).
- Trigger block (`on: pull_request` / `push: main`) is shared, unchanged —
  no new trigger condition is added.

---

## Invariants Summary

| Invariant | Source | Enforced in |
|---|---|---|
| All 13 shipped manifests load without error and exit `0` on a clean tree | AC-1, FR-001/002 | Real-CLI verification (quickstart.md); drift gate |
| Zero `RULE_DRIFT`/`MISSING_SOURCE`/`MANIFEST_ERROR` findings across all 13 shipped manifests | FR-004, AC-1 | `check-doctrine-drift-gate.sh` Phase 1 |
| Control manifest's `--json` output contains ≥1 `RULE_DRIFT` finding | FR-005, AC-3 | `check-doctrine-drift-gate.sh` Phase 2 (inverted) |
| Every fragment-cited rule's `ruleText` occurs exactly once in its directive file | Binding constraint 1 | `grep -F -c` = 1, verified in planning (research.md §3), re-verified at implementation |
| No manifest silently drops a rule entry | Absence lesson (mission brief) | `check-doctrine-manifest-completeness.mjs` |
| No manifest, `sopFile` target, or the control manifest can go missing without a loud, named failure | Absence lesson (mission brief) | muster's own `MANIFEST_ERROR`/`STRUCTURAL_ABSENCE` (manifest/sopFile) + `check-doctrine-manifest-completeness.mjs` (control existence) |
| Every rule traces to exactly one taxonomy class citation (or an explicit `UNMAPPED` disposition) and one commit-pinned directive URL | SC-004, binding constraint 2/5 | `contracts/rule-classification-and-citation.md`; manual review at merge |
| All four manifest loader guards are structurally unreachable-as-failures across all 14 files | Binding constraint 3 | `contracts/doctrine-rule-manifest-shape.md` guard table; real-CLI verification |
| No `src/doctrine/**` (spec-kitty runtime) file is modified | C-001 | Diff review at merge; scope guard |
| No muster source file is modified | C-001 | Diff review at merge; scope guard |
| `directive` file content this mission cites matches the pinned upstream SHA byte-for-byte | Binding constraint 5 | research.md §5 (verified during planning); re-verify only if a directive file changes before merge |
