---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: event-producer-reroute-01M4VDY9
mission_id: 01M4VDY92YSGZAQVKS3E879VPH
generated_at: '2026-10-14T08:50:58.051993+00:00'
analyzer_agent: unknown
input_artifacts:
  spec.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/spec.md
    sha256: 61a69ac04a1399aa5eac77fa84f4562928b85ac0fe39c9ecae6fa7db5f3ddbf5
  plan.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/plan.md
    sha256: e24313448ce2c482bd23576181bc38a5de2bd922c3d97dbf80d3f1b6fb44857a
  tasks.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/tasks.md
    sha256: cedf40d6a28c34fde95814cacd9aa2d170d5a0841dd2afbf1c9723c12fc893d7
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 54d554ac100b0e76760ed99ae6c55757677a0de2402970ef0d39810011dc87f4
verdict: ready
issue_counts:
  medium: 0
  high: 0
  critical: 0
  low: 6
  info: 0
findings:
- id: F-01
  severity: low
  category: ambiguity
  summary: WP06 Commit order was renumbered (the lane ATDD commit is now step 3, adapters hunks step 4, seam call sites step 5), but the Context size note still says "splitting at the commit-order cut after step 4". The cut now lands after hunk C registers the client and before the seam call sites, so the first half would ship a registered client with the lane acceptance test (committed red at step 3) still red; the old step 4 was the final step, so the sentence meant something different before the renumbering.
- id: F-02
  severity: low
  category: ambiguity
  summary: The WP06 tests table row for tests/status/test_producer_state_ignore.py says the path "must be in the status shard registry for the module-shard gate", but the shard gate (test_module_shard_registry.py and the interpreter shard roster) claims whole directories (tests/status is claimed as a directory), so no per-file registry entry exists or is owned; the instruction cannot be executed as written and could prompt an out-of-map edit.
- id: F-03
  severity: low
  category: traceability
  summary: 'Requirement-ref drift narrowed but not closed: WP03 T020 asserts the NFR-006 absolute-path scan and WP01 cites NFR-006, yet WP03 requirement_refs (wps.yaml, frontmatter, tasks.md) and the tasks-overview 8.1 NFR-006 row (WP04, WP05, WP10) omit WP03. C-002..C-008 remain traced only in overview 8.3 (known parser limit, stated there).'
- id: F-04
  severity: low
  category: ambiguity
  summary: New open item O-8 labels the out-of-scope pointer as carryover section Q "Section 1 out-of-scope, original wording", but carryover section Q is titled "Open-decision, scope and reflexivity prose (spec sections 1, 10, 12)"; the quoted label does not exist. O-8 also gives the 70 KB cap as the reason without stating the current figure (spec.md 46754 plus data-model.md 21825 = 68579 bytes, about 1.4 KB headroom), so a reader cannot tell the residual is nearly exhausted (carry-over of first-pass F-12).
- id: F-05
  severity: low
  category: inconsistency
  summary: The AC-040 SBOM planted-failure step was added to WP01 T007 (verify-pins steps, workflow-test assertions, tests table), but tasks-overview R12(c) and section 5 gates still describe verify-pins and the Java gate without the SBOM check; overview is authoritative for CI shape, so the refinement text is now behind WP01.
- id: F-06
  severity: low
  category: decomposition
  summary: 'WP05 T029 now places LocalTier in adapter/ledger/localtier/ and states it survives the #6193 replacement, but ownership stays the coarse glob adapter/ledger/** (wps.yaml, lanes.json) in the package named for the committed-ledger reader that #6193 replaces; the survival claim is prose only, and a replacement that removes or rewrites the whole ledger package would silently delete retention. Residual of first-pass F-09 (mostly addressed).'
---

# Analysis report (final re-analysis): event-producer-reroute-01M4VDY9 (spec-kitty#6185)

Analyst profile: reviewer-renata. State analysed: branch feat/event-producer-reroute-6185 at the second fix commit (docs(tasks): fix analysis findings (round 2)), following the first fix commit. Fresh pass over spec.md, data-model.md, plan.md, tasks.md, tasks-overview.md, wps.yaml, tasks/WP01..WP12, lanes.json and the charter. Overview refinements R1 to R14 were treated as authoritative over plan section 11. Open items O-1 to O-8 were read as legitimate open decisions and are not counted as defects.

## Verdict

ready. No critical, high or medium finding. Six low findings (F-01 to F-06), all wording or traceability polish; none blocks task execution.

## Mechanical checks (scripted, all passed)

- wps.yaml, tasks.md and each WP frontmatter agree on dependencies, owned_files, requirement_refs and subtasks for all 12 WPs (including the new FR-028 on WP02, the unchanged FR-019 on WP06, and the WP10 FR-003, FR-026, NFR-003 additions).
- lanes.json: 12 lanes, each write_scope equals the union of its WP owned_files, lane dependencies equal the WP dependency edges, graph acyclic (WP10 after WP09, WP11, WP12; WP12 after WP07, WP11).
- Overview 8.1 FR/NFR/C-001 table equals wps.yaml requirement_refs for every row; overview section 3 dependency column equals wps.yaml.
- Every FR-001..FR-046 and NFR-001..NFR-007 is in some requirement_refs; all 40 ACs are named in a WP body; every supporting WP in overview 8.2 names its AC in its body (first-pass F-11 supporting-WP half is closed).
- WP01 size note and overview now both say 31 owned files (matches wps.yaml).
- WP01 verify-pins description, workflow-test assertions and tests table now include the SBOM planted-failure step (AC-040).
- Public hygiene scan of the new text: no absolute home path or username.

## Disposition of the previous report's findings

| Previous | Status |
|---|---|
| F-01 SBOM wiring | Fixed (WP01 T007 and tests table). Overview mirror: F-05 here. |
| F-02 WP06 table row | Fixed (row added); its shard wording: F-02 here. |
| F-03 carryover chain | Recorded as O-8 (accepted residual); label slip: F-04 here. |
| F-04 carryover section D | Fixed (FR-027 wording). |
| F-05 29 vs 31 files | Fixed in WP01 and overview. |
| F-06 section 1 heading | Fixed. |
| F-07 Hunk B2 | Fixed: only the lifecycle assertion is red; resolved-binding forwarding dropped; R11 rewritten consistently. |
| F-08 lane ATDD ordering | Fixed as Commit order step 3 with a failing-assertion definition; stale cut sentence: F-01 here. |
| F-09 LocalTier location | Mostly fixed (subpackage, survival stated): F-06 here. |
| F-10 gate checklists | Fixed for WP10 and WP12. |
| F-11 traceability | Mostly fixed (refs and overview rows aligned); residual NFR-006 on WP03: F-03 here. |
| F-12 size headroom | Unchanged, now cited in O-8; see F-04 here. |

## Findings (detail, remediation)

### F-01 Stale commit-order cut (low, ambiguity)
Where: tasks/WP06 Context size note; Commit order steps 3 to 5.
Remediation: re-point the cut to "after step 3" (ATDD red, client unwired) only if the red commit is kept with the first half, or name the step explicitly by content ("after the adapters hunks, with the ATDD test marked as the second half's acceptance test").

### F-02 Shard registry wording (low, ambiguity)
Where: tasks/WP06 tests table, test_producer_state_ignore.py row.
Remediation: replace with "tests/status is claimed as a directory by the module-shard registry; no per-file entry is needed" after confirming with test_module_shard_registry.py.

### F-03 NFR-006 on WP03 (low, traceability)
Where: wps.yaml and WP03 frontmatter requirement_refs; overview 8.1 NFR-006 row.
Remediation: add NFR-006 to WP03 requirement_refs (CLI path) and the 8.1 row, or reword the T020 line so it does not claim the requirement.

### F-04 O-8 label and headroom (low, ambiguity)
Where: tasks-overview section 11 O-8.
Remediation: cite carryover Q by its real title and state the current 68579-byte figure so the cap-revisit trigger is concrete.

### F-05 Overview behind WP01 on verify-pins (low, inconsistency)
Where: tasks-overview R12(c), section 5 Java gate line.
Remediation: add "and the AC-040 planted-SBOM step" to the verify-pins description in R12(c).

### F-06 LocalTier survival is prose only (low, decomposition)
Where: tasks/WP05 T029; wps.yaml WP05 owned_files.
Remediation: add a one-line test (an ArchUnit or file-existence check) that LocalTier lives in its own package and has no dependency on the LedgerFacts reader classes, so a #6193 replacement cannot drag it out unnoticed.

## Charter alignment summary

Known drift remains flagged, not resolved, and correctly routed: no Java gate row and no JaCoCo floor row in the charter (O-2, ADR amendment in WP07 T050); Python limited to the thin shim, FR-035 stamping and bounded build glue (spec section 3 item 2, section 9 (1), FR-027, now also carryover D); lint-script logic awaits O-3; pinned-list debt recorded in WP06 and O-4; events source is consumed as a pinned released artifact, not vendored; the events-repo charter amendment is external. Terminology (Mission, work package, Producer, Publish seam, fan-out not sync) is consistent across the artifacts.
