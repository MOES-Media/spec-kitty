---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: event-producer-reroute-01M4VDY9
mission_id: 01M4VDY92YSGZAQVKS3E879VPH
generated_at: '2026-10-14T08:34:26.784049+00:00'
analyzer_agent: unknown
input_artifacts:
  spec.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/spec.md
    sha256: 442ec41365c30c1afeceaba29b6ded07e792f9c836a464e5f9d62a190ffdee23
  plan.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/plan.md
    sha256: ece2f8eda57ef977a14a8bb105a27b3465bc924bc2d96cb806770f670e4e8474
  tasks.md:
    path: kitty-specs/event-producer-reroute-01M4VDY9/tasks.md
    sha256: 8ae8e854c220d0e3aaeff969329a02ba1a5310ac6640dd01984431553d447046
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 54d554ac100b0e76760ed99ae6c55757677a0de2402970ef0d39810011dc87f4
verdict: ready
issue_counts:
  low: 13
  critical: 0
  high: 0
  medium: 8
  info: 0
findings:
- id: F-01
  severity: medium
  category: coverage
  summary: AC-023 / FR-019 has no named test. WP06 T042 only says to run existing state-contract tests; no WP asserts git check-ignore for publish log and fault log after init and upgrade, or that clock state lies under the git common directory.
- id: F-02
  severity: medium
  category: coverage
  summary: AC-040 falsifier (SBOM names a known Java dependency and the embedded toolchain) has no assertion in any WP; WP01 T003/T005 produce the SBOM and WP09 T061 attaches it, nothing checks its content.
- id: F-03
  severity: medium
  category: coverage
  summary: AC-039 is on the spec floor-rule list (floor plus self-mutation, changed hash input must fail) but WP03 T021/T022 carry no mutation case and no explicit floor assertion.
- id: F-04
  severity: medium
  category: charter-alignment
  summary: Spec section 3 item 2, section 9 (1) and FR-027 still say Python only for the shim plus FR-035 stamping; operator decision 10 (bounded build glue, plan 13.2 / F1) is not reflected in the spec, while WP01 and WP08 add Python glue under a separate build-glue gate.
- id: F-05
  severity: medium
  category: charter-alignment
  summary: WP12 adds a persisted-line schema-validating mode to scripts/lint_canonical_producers.py (new Python logic) which is on neither the thin-client list nor the plan 13.2 / tasks-overview section 5 build-glue list; only plan 4.2 last row mentions it as tooling outside gate (e).
- id: F-06
  severity: medium
  category: ambiguity
  summary: WP06 T036 hunk B2 expects the resolved-binding case to deliver start_path to the client stub, but FR-003 and carryover state the resolved-binding slot stays unregistered and passes causation_id, not a row; the red-first test as written is not satisfiable or contradicts FR-003.
- id: F-07
  severity: medium
  category: traceability
  summary: WP04 T026 says the client half of the no-mission_id case (no stderr, no fault row, count and exit code unchanged) is asserted by WP06 T036; T036 contains no such test. Only WP10 T066 (final, events-gated) covers it.
- id: F-08
  severity: medium
  category: dependency
  summary: 'The prerequisite spec-kitty-events PR (blocks approval of WP09, WP10, WP11, WP12 and the mission PR) has no tracked artifact in the mission (no WP, no issue-matrix row of its own, owner or sequencing record), and the gate is prose-only: the lane and approval tooling will not stop WP09 or WP11 being approved before the pin names the released wheel.'
- id: F-09
  severity: low
  category: inconsistency
  summary: 'PR shape fork is unresolved across artifacts: plan 11 says one PR, no slices and 16 says no other fork; tasks-overview section 1 says the slice decision belongs to the operator and keeps slices A and B open.'
- id: F-10
  severity: low
  category: inconsistency
  summary: Plan section 15 and research R-18 say the thirteen spec touch-ups are not edited and the orchestrator applies them, but the spec and data-model already carry all thirteen; the statement is stale.
- id: F-11
  severity: low
  category: inconsistency
  summary: tracer-design-decisions.md last row says Python build glue is accepted pending fork F1, but plan section 16 records F1 ruled (a) bounded on 2026-10-14.
- id: F-12
  severity: low
  category: charter-alignment
  summary: Charter has no Java gate row or platform-wheel row (known drift, plan 13.1 and 13.6, flagged not resolved); the new ADR is the only record. Java JaCoCo floor for :adapter is 80 percent against the charter 90 percent new-code figure (stated for pytest); choose and record it as deliberate.
- id: F-13
  severity: low
  category: charter-alignment
  summary: 'Charter burn-down policy requires every allowlist or pinned list as debt to name issue, owner and drain date; the thinness-gate pinned lists (c) and (e) are called priced debt deleted with #6193 but carry no owner or date and are not placed in tests/architectural/_baselines.yaml.'
- id: F-14
  severity: low
  category: charter-alignment
  summary: ATDD-first (charter C-011) is met per behaviour, but the mission-level default-install acceptance tests (AC-001 end to end, AC-017, AC-025) exist only in WP10, the last and events-gated WP, not as an early red commit.
- id: F-15
  severity: low
  category: tracking
  summary: "acceptance-matrix.json is an unfilled scaffold (46 FR rows each 'TODO: replace with a real acceptance criterion', no AC-001..AC-040 rows); issue-matrix.json row #6185 says 'WP01-WP11 deliver it' though there are twelve WPs, and several titles are placeholders."
- id: F-16
  severity: low
  category: ambiguity
  summary: data-model section 9 says 'Six sets in all (the AC-020 floor)' but lists three authorities (Lane, CANONICAL_LANES, events Lane) plus four census items; research R-14 and WP07 count six without the events Lane. State the six explicitly.
- id: F-17
  severity: low
  category: coverage
  summary: tasks-overview 8.3 maps C-006 to WP09 and WP11 only, although WP07 T050 is where C-006 is reconciled against the AGENTS.md changelog rule; C-002..C-008 are not machine-checked (known parser limit) so the table is their only trace.
- id: F-18
  severity: low
  category: duplication
  summary: Port ownership is blurred. Plan section 1 lists LedgerFacts as an :application port but R4 and WP03 T079 omit it (WP05 T029 defines it in application.retain); no port is named for local-tier listing and deletion; the fault vocabulary is assigned to both WP02 T015 and WP04 title and T025.
- id: F-19
  severity: low
  category: decomposition
  summary: WP01 carries ten subtasks and 29 owned files (feasibility gate, Java skeleton, two workflows, fleet registration, hook, dist-set, pin tooling) and is the HALT gate; only WP06 is flagged as large in tasks-overview.
- id: F-20
  severity: low
  category: hygiene
  summary: 'Cosmetic numbering and naming: subtask ids are non-monotonic (T079 first in WP03; T080..T084 appended; T044, T058, T064, T071 retired) and the WP07 prompt file slug still names markers although marker clearing moved to WP12.'
- id: F-21
  severity: low
  category: ambiguity
  summary: Normative detail lives in plan-carryover.md (89 KB, lettered sections E, L, Q, R, W, X, Y, AB, with copies of original spec text); spec FR-013 points to 'section 3' for the total order, which itself only points to data-model section 4. Definitions are reachable only by reference chain.
---

# Analysis report: event-producer-reroute-01M4VDY9 (spec-kitty#6185)

Analyst profile: reviewer-renata. Checkout state analysed: branch feat/event-producer-reroute-6185 at the tasks-review-final commit. Inputs read: charter, spec.md, data-model.md, plan.md, research.md (selected sections), plan-carryover.md (references), tasks.md, tasks-overview.md, wps.yaml, tasks/WP01..WP12, lanes.json, meta.json, issue-matrix.json, acceptance-matrix.json, tracer files. tasks-overview.md refinements R1 to R14 were treated as authoritative; nothing recorded there is re-flagged.

## Verdict

Verdict: ready. No critical or high finding. Eight medium findings are fixable by small edits to the task prompts or spec wording; thirteen low findings are hygiene. The mission can proceed to implementation, with F-01 to F-08 best folded first.

## Checks that passed (evidence)

- Coverage by requirement: every FR-001..FR-046 and NFR-001..NFR-007 and C-001 appears in a WP requirement_refs list; the tasks-overview 8.1 table equals wps.yaml exactly (scripted comparison). C-002..C-008 are traced only in tasks-overview 8.3 (known parser limit).
- Every AC-001..AC-040 is named in at least one WP body; the spec statement that every FR, NFR and C bar C-001 and C-006 appears in an AC bracket was verified by script. Six overview AC rows list a supporting WP whose body does not name the AC (AC-023 to WP03, AC-025 to WP08, AC-028/AC-032/AC-039/AC-040 to WP01); only AC-023, AC-039 and AC-040 matter (F-01..F-03).
- Consistency: wps.yaml, tasks.md (Dependencies lines), each WP frontmatter (dependencies, owned_files, requirement_refs, subtasks, title) and lanes.json agree; each lane write_scope equals the union of its WP owned_files; twelve lanes, acyclic; no two WPs share an owned path or glob. lanes.json and wps.yaml were committed together after the last WP edit.
- Subtasks: 80 unique ids, none duplicated, none orphaned.
- Public hygiene: no absolute home path, username, or private-redesign term in the mission directory (scripted grep); golden node-id fixtures use synthetic host and user names.
- Terminology: Mission and work package used throughout; no Feature alias outside code identifiers (feature_dir); fan-out is explicitly not called sync.
- Charter standing orders: tracer files seeded, campsite commits planned (WP01, WP06), red-first and revert-check per behaviour, floor plus self-mutation on census gates, issue-matrix present.

## Findings (detail, remediation)

### F-01 AC-023 has no test (medium, coverage)
Where: spec AC-023 and FR-019; tasks/WP06 T042; tasks-overview 8.2 (AC-023: WP06, WP03).
Claim: the AC needs git check-ignore for the publish log and fault log after fresh init and after upgrade, and the clock state under git rev-parse --git-common-dir. T042 only declares three StateSurface entries and says to run test_lifted_root_gitignore_contract.py and test_state_contract.py, which pre-date the new paths. WP03 never names AC-023; WP10 does not assert it.
Remediation: add a named red-first test file to WP06 create_intent (init and upgrade fixtures, check-ignore on both paths, floor of two paths, mutation removing the ignore). Add the clock-state assertion to WP03 T019 or WP10 T065 (primary checkout plus linked worktree resolve one common-dir path). Add AC-023 to the WP03 body.

### F-02 AC-040 SBOM content unasserted (medium, coverage)
Where: spec AC-040, G-5; WP01 T003 step 5 and T005; WP09 T061.
Claim: observable is an SBOM naming a known Java dependency and the toolchain; falsifier is an SBOM unchanged by the binary. No test reads the SBOM.
Remediation: in WP01 (producer-native.yml step or the stub-wheel test) assert the producer-sbom artifact lists one pinned dependency (for example Jackson) and the toolchain entry of producer-manifest.json, with a mutation that removes the dependency; reference it from WP09 T061.

### F-03 AC-039 lacks the mutation and floor (medium, coverage)
Where: spec section 6 floor rule and AC-039; WP03 T021, T022.
Claim: AC-039 is a census-style gate needing a floor (live case plus at least two injected strings) and a mutation (a changed hash input fails). WP03 contains no mutation anywhere.
Remediation: add to T021 a mutated hash input (for example hostname and username order swapped) that must fail the golden comparison, and an assertion that at least three cases ran.

### F-04 Spec not updated for the bounded Python build glue (medium, charter-alignment)
Where: spec section 3 item 2, section 9 (1), FR-027; plan 13.2 and 16 (F1 ruled a); tasks-overview section 5.
Claim: the spec limits Python to the shim and FR-035; the operator ruling allows hatch_build_producer.py, check_dist_set.py, the check_exact_install.py --wheel-tag edit and the gradle_pin_check.py entry, pinned by test_build_glue_thin.py. The spec and its gate text (FR-027) do not mention them, so spec and tasks disagree on the allowed surface.
Remediation: spec touch-up to FR-027, section 3 item 2 and section 9 (1) naming the F1 allowance and the build-glue pin; keep the new ADR consistent.

### F-05 WP12 lint-script logic outside the Python allowance (medium, charter-alignment)
Where: tasks/WP12 T047; plan 4.2 last row; tasks-overview section 5 Python allowance.
Claim: persisted-line mode validates JSON lines against released schemas inside scripts/lint_canonical_producers.py, which is neither runtime shim nor listed build glue. The overview allowance says "everything else is Java".
Remediation: either list the script as gate tooling in the allowance statement, the ADR and the thinness or build-glue pin, or place the schema validation in the test (test_exemption_ledger.py) and keep the script change to flag plumbing. Record the operator ruling.

### F-06 Resolved-binding start_path expectation contradicts FR-003 (medium, ambiguity)
Where: tasks/WP06 T036 (Hunk B2) and T037; spec FR-003; plan-carryover FR-003 rationale and slot table.
Claim: B2 says the AC-001 lifecycle case and the resolved-binding case assert the stub receives start_path, and that the resolved-binding fire uses the status slot. FR-003 says the client registers on status and lifecycle only, and the resolved-binding call passes causation_id, not a row. A red test for the resolved-binding fire cannot reach the client.
Remediation: restrict B2's client assertion to the lifecycle and status fires; state that repo_root forwarding in fire_resolved_binding_fanout serves hosted handlers only (or drop it); name the slot used by each new InnerStateChanged seam call in T037.

### F-07 Cross-reference to a test that does not exist (medium, traceability)
Where: tasks/WP04 T026 last sentence; WP06 T036/T038; WP10 T066; spec AC-005 final clause.
Claim: the client half of the no-mission_id case is attributed to WP06 T036, which has no such test. It is first covered end to end in WP10, after the events gate.
Remediation: add to WP06 (T036 or T038) a stub-binary test: reply produced for a project-scoped row gives empty stderr, no fault-log row, unchanged fault_summary, exit code and --json body; fix the WP04 reference.

### F-08 Prerequisite events PR untracked; gate is prose (medium, dependency)
Where: tasks-overview section 2; plan 6; WP09, WP10, WP11, WP12 "Precondition gate"; issue-matrix.json.
Claim: four WPs and the mission PR wait on a released spec-kitty-events version from another repository. It is a "recommended Op" with no id, owner or issue-matrix row (only #92 as not-applicable), and nothing in the lane or approval tooling prevents approving WP09 or WP11 early. WP01 and WP10 also depend on an orchestrator push of scratch draft pull requests, requiring operator authorisation at that time.
Remediation: record the Op (issue or note with owner and sequencing) in the issue-matrix or tasks-overview; add an explicit approval checklist line (reviewer verifies pin file and floor) to the WP09 and WP11 reviewer guidance; note the push authorisations in the dispatch plan.

### F-09 PR shape fork unresolved (low, inconsistency)
Where: plan 11 and 16; tasks-overview section 1.
Remediation: record the operator ruling (one PR, or slices A and B) in one place and align both documents.

### F-10 Stale statement on spec touch-ups (low, inconsistency)
Where: plan 15; research R-18.
Remediation: change to "applied" (all thirteen are present in spec.md and data-model.md) or remove.

### F-11 Stale tracer row (low, inconsistency)
Where: tracer-design-decisions.md last row.
Remediation: replace "pending fork F1" with "ruled (a), bounded, 2026-10-14".

### F-12 Charter has no Java row (low, charter-alignment)
Where: charter Technical Standards; plan 13.1, 13.6; plan 9 JaCoCo floors.
Claim: known drift, flagged in plan, not amended. The :adapter floor of 80 percent is below the charter's 90 percent new-code figure (written for pytest).
Remediation: state in the new ADR that the Java gates and floors are deliberate and why :adapter is 80 percent; propose a charter row in a separate change.

### F-13 Pinned lists lack owner and drain date (low, charter-alignment)
Where: plan 4.2 thinness gate (c), (e); charter Burn-down Policy; WP12 baseline files.
Remediation: name an owner and drain date next to the #6193 reference and record the pinned lists in tests/architectural/_baselines.yaml, or justify why a pinned list that must grow only inside this mission is not a baseline.

### F-14 Mission-level acceptance tests come last (low, charter-alignment)
Where: WP10 T065, T066, T067; charter ATDD-First Discipline.
Remediation: either add an early red end-to-end test for AC-001 with a stub binary to WP06 (a failing-first commit at the lane start) or state that WP06's stub-level census tests serve as the lane's ATDD commit.

### F-15 Matrices are scaffolds (low, tracking)
Where: acceptance-matrix.json; issue-matrix.json row #6185.
Remediation: key the acceptance matrix rows to AC-001..AC-040 before accept; correct the #6185 evidence reference to WP01 to WP12.

### F-16 "Six sets" count (low, ambiguity)
Where: data-model section 9; research R-14; WP07 T045.
Remediation: list the six sets in the data-model (Lane, CANONICAL_LANES, _VALID_LANES, _LANE_ORDER, _LEGACY_NOT_DONE_LANES, _PRE_APPROVED_LANE_VALUES) and keep the events-repo Lane separate.

### F-17 C-006 trace (low, coverage)
Where: tasks-overview 8.3.
Remediation: add WP07 (T050) to the C-006 row.

### F-18 Port and vocabulary ownership (low, duplication)
Where: plan 1; tasks-overview R4; WP03 T079; WP05 T029; WP02 T015; WP04 T025.
Remediation: state in R4 or T079 that LedgerFacts and the retain ports live in application.retain (WP05); state the fault vocabulary is WP02's and WP04 only uses it.

### F-19 WP01 size (low, decomposition)
Where: tasks/WP01.
Remediation: accept with a note, or cut the CI registration and shared smoke action (T007, T008) into a sibling after the feasibility verdict. The registration is already its own commit.

### F-20 Numbering and slug (low, hygiene)
Where: wps.yaml subtasks; tasks/WP07 file name.
Remediation: none required; record in tracer friction that slugs are frozen by the WPCreated event.

### F-21 Reference chain to carryover (low, ambiguity)
Where: spec sections 1, 2, 11, 13 and FR-013; plan-carryover.md.
Remediation: inline the glossary definitions (carryover L) and the out-of-scope wording (carryover Q) in the spec, and point FR-013 straight at data-model section 4.

## Charter alignment summary

Known drift confirmed and flagged, not resolved: no Java gate row (F-12); Python allowed only for the shim plus bounded build glue (F-04, F-05); events source not vendored (met by build-time pinned consumption); ADR 2026-10-13-2 D-1 to D-3 amendment planned in WP07 T050; events-repo charter amendment external.
