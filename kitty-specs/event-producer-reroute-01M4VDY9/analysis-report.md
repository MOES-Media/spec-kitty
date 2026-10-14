---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: event-producer-reroute-01M4VDY9
mission_id: 01M4VDY92YSGZAQVKS3E879VPH
generated_at: '2026-10-14T08:44:16.186958+00:00'
analyzer_agent: reviewer-renata
input_artifacts:
  spec.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/spec.md
    sha256: 61a69ac04a1399aa5eac77fa84f4562928b85ac0fe39c9ecae6fa7db5f3ddbf5
  plan.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/plan.md
    sha256: e24313448ce2c482bd23576181bc38a5de2bd922c3d97dbf80d3f1b6fb44857a
  tasks.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/tasks.md
    sha256: 4b5d5876276cea0f77f6237ff115b0b6caa4d3e61ab04070175979fe9443e65f
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 54d554ac100b0e76760ed99ae6c55757677a0de2402970ef0d39810011dc87f4
verdict: unknown
issue_counts:
  low:
  critical:
  info:
  high:
  medium:
findings: []
---

---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: event-producer-reroute-01M4VDY9
mission_id: 01M4VDY92YSGZAQVKS3E879VPH
generated_at: '2026-10-14T08:43:33.000000+00:00'
analyzer_agent: reviewer-renata
input_artifacts:
  spec.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/spec.md
    sha256: 61a69ac04a1399aa5eac77fa84f4562928b85ac0fe39c9ecae6fa7db5f3ddbf5
  plan.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/plan.md
    sha256: e24313448ce2c482bd23576181bc38a5de2bd922c3d97dbf80d3f1b6fb44857a
  tasks.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/tasks.md
    sha256: 4b5d5876276cea0f77f6237ff115b0b6caa4d3e61ab04070175979fe9443e65f
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 54d554ac100b0e76760ed99ae6c55757677a0de2402970ef0d39810011dc87f4
verdict: ready
issue_counts:
  low: 10
  critical: 0
  high: 0
  medium: 1
  info: 1
findings:
- id: F-01
  severity: medium
  category: coverage
  summary: 'The AC-040 SBOM content check added in the fix round (WP01 T003 step 5, tools/check-sbom.sh plus its planted-SBOM and planted-manifest mutation in tools/expect-failure.sh) is not wired to any job or test: the verify-pins description (WP01 T007) lists only the floor-breach, verification-tamper and planted-dependency steps, test_event_producer_workflow.py is not extended to assert the SBOM step, and the WP01 tests table has no row for it. The mutation can therefore rot unrun.'
- id: F-02
  severity: low
  category: hygiene
  summary: The WP06 "Tests, markers, jobs" table omits the new tests/status/test_producer_state_ignore.py (AC-023); the file needs an explicit tier marker and shard to satisfy the marker-completeness and module-shard gates, and the overview rule is that every WP lists its new test files with marker and job.
- id: F-03
  severity: low
  category: ambiguity
  summary: 'First-round F-21 is only half closed: spec FR-013 now points at data-model section 4, but spec sections 1, 2, 9, 11 and 13 still defer definitions (glossary terms, out-of-scope wording, evidence table) to plan-carryover.md lettered sections, and the residual is neither fixed nor recorded as an open item.'
- id: F-04
  severity: low
  category: inconsistency
  summary: 'plan-carryover.md section D item 1 still says "Python is allowed only for the shim", and spec section 9 sends the reader there for detail; this contradicts the corrected spec section 9 (1) and FR-027, which now allow the FR-035 stamping and the bounded build glue.'
- id: F-05
  severity: low
  category: inconsistency
  summary: 'The WP01 size note and tasks-overview section 3 say WP01 has "29 owned files"; wps.yaml, the WP01 frontmatter and lanes.json (lane-a) list 31. The sizing statement the fix round added is stale on arrival.'
- id: F-06
  severity: low
  category: inconsistency
  summary: 'tasks-overview section 1 is still headed "PR shape: one PR, no slices" while its body keeps slices A and B as an option and open item O-1 asks the operator to confirm; the heading overstates the decision. O-1 itself is correctly recorded.'
- id: F-07
  severity: low
  category: ambiguity
  summary: 'WP06 T036 Hunk B2 now asserts start_path on the plain status fire (fire_saas_fanout) as red-first, but that function already re-joins repo_root, so the assertion is green before the change and only the lifecycle assertion is red; and the resolved-binding forwarding edit goes beyond FR-004, which names the lifecycle fire only. Restrict the red-first claim to the lifecycle fire and justify or drop the resolved-binding forwarding.'
- id: F-08
  severity: low
  category: ambiguity
  summary: 'The WP06 lane ATDD commit (stub-level AC-001, committed red "at the start of the seam work, before the hunks of Commit order step 3") has no step in the Commit order list, and the stub-binary test needs the client modules of step 2 to exist, so "red" is either a collection error or a step-2 sub-commit; the overview also describes each WP as an independently green run of commits.'
- id: F-09
  severity: low
  category: decomposition
  summary: WP05 T029 places the LocalTier implementation (local-tier listing and whole-file deletion) in adapter/ledger/**, the package whose purpose is the committed-ledger reader that mission #6193 replaces; the replacement boundary and the local-tier adapter are conflated. Name a separate adapter package or state that LocalTier survives the replacement.
- id: F-10
  severity: low
  category: dependency
  summary: The reviewer approval checklist for the events-release gate was added to WP09 and WP11 only; WP10 and WP12 are gated by the same release (tasks-overview section 2) but carry no checklist line, so an early approval of them is equally unguarded.
- id: F-11
  severity: low
  category: traceability
  summary: 'Unchanged from the first round and now unrecorded: tasks-overview 8.2 lists supporting WPs whose body never names the AC (AC-025 to WP08; AC-028, AC-032 and AC-039 to WP01), and some WP bodies cover requirements absent from their requirement_refs (WP02 FR-028, WP06 FR-019, WP10 FR-003, FR-026, NFR-003); C-002..C-008 remain traced only in the overview table.'
- id: F-12
  severity: info
  category: hygiene
  summary: 'Size check passes but with little headroom: spec.md 46754 bytes plus data-model.md 21825 bytes is 68579 bytes against the roughly 70 KB bound, leaving about 1.4 KB for any further touch-up.'
---

# Analysis report (re-analysis): event-producer-reroute-01M4VDY9 (spec-kitty#6185)

Analyst profile: reviewer-renata. Checkout state analysed: branch feat/event-producer-reroute-6185 at the fix commit following the first analysis (the commit titled "fix analysis findings (round 1)"). This is a fresh pass over the current artifacts: spec.md, data-model.md, plan.md, tasks.md, tasks-overview.md, wps.yaml, tasks/WP01..WP12, lanes.json and the charter. tasks-overview refinements R1 to R14 were treated as authoritative over plan section 11 rows. Open items O-1 to O-7 (tasks-overview section 11) were read as legitimate open items and are not counted as defects.

## Verdict

Verdict: ready. No critical and no high finding. One medium finding (F-01, an unwired mutation check) and ten low findings are fixable by small wording edits; one info note. Of the first-round findings F-01 to F-21, every one is either fixed or recorded as an open item, with the residuals listed below as F-03, F-04, F-11.

## Mechanical checks that passed (scripted, evidence)

- wps.yaml, tasks.md (Dependencies and Owned Files lines), each WP frontmatter (title, dependencies, owned_files, requirement_refs, subtasks) agree; no mismatch in 12 WPs.
- lanes.json: twelve lanes, each lane write_scope equals the union of its WP owned_files (including the new tests/status/test_producer_state_ignore.py in the WP06 lane); the lane graph is acyclic and matches the WP dependency edges; no two WPs share an owned path or glob.
- 80 unique subtask ids, none orphaned, each WP body carries exactly its subtask headings.
- Coverage: every FR-001..FR-046, NFR-001..NFR-007 appears in a WP requirement_refs list; C-001 likewise; C-002..C-008 are traced only in tasks-overview 8.3 (known parser limit). All AC-001..AC-040 are named in at least one WP body; AC-023, AC-039 and AC-040 now have named tests (WP06 test_producer_state_ignore.py and WP03 T019; WP03 T021 floor and mutation; WP01 check-sbom).
- Public hygiene: no absolute home path, username or private-redesign term in the mission directory (the two grep hits are the text "Contents/Home" in review files, not a path).
- Terminology: Mission and work package used throughout; fan-out is explicitly not called sync.
- Charter alignment: Java gate row and JaCoCo 80 percent floor recorded as O-2 and routed to the new ADR (WP07 T050); Python limited to shim, FR-035 stamping and bounded build glue, now reflected in spec section 3 item 2, section 9 (1) and FR-027; lint script recorded as O-3; pinned-list debt recorded in WP06 and O-4; ATDD placement stated in WP06 and WP10.

## Disposition of first-round findings

| First round | Status |
|---|---|
| F-01 AC-023 test | Fixed: WP06 T042 names test_producer_state_ignore.py with floor and mutation; WP03 T019 asserts the common-dir anchor. Residual table omission: F-02 here. |
| F-02 AC-040 SBOM | Fixed in WP01 (check-sbom.sh with mutation) and WP09 T061; wiring gap: F-01 here. |
| F-03 AC-039 floor and mutation | Fixed in WP03 T021. |
| F-04 spec Python surface | Fixed in spec section 3 item 2, section 9 (1), FR-027; stale copy in carryover: F-04 here. |
| F-05 lint script | Recorded: allowance text in overview section 5, WP12 note, open item O-3. |
| F-06 resolved-binding expectation | Fixed in R11 and WP06 T036/T037; refinement of the red-first claim: F-07 here. |
| F-07 missing client test | Fixed: WP06 T036 project-scoped-reply stub test; WP04 T026 reference corrected. |
| F-08 events PR tracking | Recorded: O-5, O-6, checklists in WP09/WP11; gap for WP10/WP12: F-10 here. |
| F-09 PR shape | Recorded: O-1; heading stale: F-06 here. |
| F-10 stale touch-up statement | Fixed in plan section 15 and research R-18. |
| F-11 stale tracer row | Fixed. |
| F-12 charter Java row | Recorded: O-2, WP07 T050 wording. |
| F-13 pinned-list debt | Recorded: WP06 debt record, O-4. |
| F-14 ATDD last | Fixed in text: WP06 lane ATDD commit, WP10 ATDD placement; ordering detail: F-08 here. |
| F-15 matrices | Recorded: O-7. |
| F-16 six sets | Fixed in data-model section 9 (the six are listed; the events Lane is the counterpart, not a seventh). |
| F-17 C-006 trace | Fixed: WP07 added to the C-006 row. |
| F-18 port ownership | Fixed in R4, WP03 T079, WP02 T015, WP04 T025; placement question: F-09 here. |
| F-19 WP01 size | Recorded as a size note; count wrong: F-05 here. |
| F-20 numbering and slug | Recorded in tasks-overview section 9. |
| F-21 reference chain | Partly fixed (FR-013 pointer); residual: F-03 here. |

## Findings (detail, remediation)

### F-01 SBOM mutation not wired (medium, coverage)
Where: tasks/WP01 T003 step 5, T007 verify-pins description, Tests table; tasks/WP09 T061.
Claim: AC-040's falsifier is the planted SBOM and manifest. The mutation lives in tools/expect-failure.sh, but the verify-pins job description enumerates the steps it runs (floor-breach, tamper, planted dependency) without it, test_event_producer_workflow.py is not said to assert it, and the native legs only run the live check.
Remediation: add the SBOM planted-failure step to the verify-pins list and to the workflow-test assertion in T007, and add a row to the WP01 tests table.

### F-02 New test missing from the WP06 table (low, hygiene)
Where: tasks/WP06 "Tests, markers, jobs".
Remediation: add a row for tests/status/test_producer_state_ignore.py with its marker (fast, or the git-fixture tier it needs) and the status shard.

### F-03 Carryover reference chain, residual of first-round F-21 (low, ambiguity)
Where: spec sections 1, 2, 9, 11, 13; plan-carryover.md.
Remediation: inline the glossary definitions and out-of-scope wording in the spec, or record the reference chain as an accepted open item in tasks-overview section 11.

### F-04 Stale carryover section D (low, inconsistency)
Where: plan-carryover.md section D item 1; spec section 9 pointer.
Remediation: update the item to the FR-027 wording (shim, FR-035 stamping, bounded build glue).

### F-05 Stale WP01 file count (low, inconsistency)
Where: tasks/WP01 size note; tasks-overview section 3 WP01 row.
Remediation: change 29 to 31 (or restate without a number).

### F-06 Section 1 heading (low, inconsistency)
Where: tasks-overview section 1 heading.
Remediation: retitle to "PR shape: one PR (slices kept as an option, O-1)".

### F-07 Hunk B2 red-first claim (low, ambiguity)
Where: tasks/WP06 T036 Hunk B2; spec FR-004; tasks-overview R11.
Remediation: state that only the lifecycle assertion is red before the change; justify or drop the resolved-binding repo_root edit, which FR-004 does not require.

### F-08 Lane ATDD commit placement (low, ambiguity)
Where: tasks/WP06 T036 "Lane ATDD commit" and Commit order.
Remediation: add an explicit Commit order step after the client modules (step 2) and before step 3, and say the red state is a failing assertion against the stub, not a collection error.

### F-09 LocalTier adapter location (low, decomposition)
Where: tasks/WP05 T029; wps.yaml WP05 owned_files (adapter/ledger/**).
Remediation: give the local-tier adapter its own package (a separate native-image metadata directory is already per-WP) or state it survives the #6193 replacement.

### F-10 Gate checklist coverage (low, dependency)
Where: tasks/WP10 and WP12 reviewer guidance; tasks-overview section 2.
Remediation: add the same approval checklist line (pin file and floor name V_E) to WP10 and WP12.

### F-11 Overview supporting-WP and requirement_refs drift (low, traceability)
Where: tasks-overview 8.2 rows AC-025, AC-028, AC-032, AC-039; WP02, WP06, WP10 bodies.
Remediation: name the AC in the supporting WP body or drop the supporting WP from the row; add FR-019 to WP06 requirement_refs (a wps.yaml and frontmatter edit through the CLI path).

### F-12 Size headroom (info)
spec.md plus data-model.md is 68579 bytes; any further spec touch-up should move wording to plan-carryover.md rather than grow the pair.

## Charter alignment summary

Known drift confirmed and flagged, not resolved: no Java gate row and no platform-wheel row in the charter (O-2); Python allowed only for the shim, the FR-035 stamping and bounded build glue, now stated in the spec (F-04 first round fixed); lint-script logic awaits O-3; events source is not vendored (met by build-time pinned consumption); ADR 2026-10-13-2 D-1 to D-3 amendment is planned in WP07 T050; the events-repo charter amendment is external.
