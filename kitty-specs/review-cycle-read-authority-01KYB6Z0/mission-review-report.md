# Mission Review Report: review-cycle-read-authority-01KYB6Z0

**Reviewer**: Claude Opus 5 (post-merge mission review)
**Date**: 2026-07-26
**Mission**: `review-cycle-read-authority-01KYB6Z0` — Review-Cycle Read Authority (mission_number 186)
**Baseline commit**: `721165a22` (merge-base with `upstream/main`)
**HEAD at review**: `d8613bd79`
**WPs reviewed**: WP01–WP05 (all `done`)

> **Note on `baseline_merge_commit`**: `meta.json` records `dc6675527`, which is a
> runtime-state commit created *during* the merge, not a pre-mission baseline. All diffs
> in this report use the true fork point `721165a22` instead. The stale field is recorded
> as **DRIFT-3**.

---

## Gate Results

### Gate 1 — Contract tests
- Command: `SPEC_KITTY_ENABLE_SAAS_SYNC=1 pytest tests/contract/ -q`
- Exit code: 0 · **PASS**
- 294 passed, 3 skipped, 0 failed.

### Gate 2 — Architectural tests
- Command: `pytest tests/architectural/ -q`
- Exit code: 0 · **PASS**
- 1112 passed, 4 skipped, 0 failed — run on the **merged branch**, not a lane.
- Pre-mission baseline was 1092 passed / 4 skipped / 0 failed; the +20 delta is exactly
  WP05's new disposition gate. Arithmetic reconciles with no unexplained movement.

### Gate 3 — Cross-repo E2E
- Command: `SPEC_KITTY_ENABLE_SAAS_SYNC=1 pytest spec-kitty-end-to-end-testing/scenarios/ -q`
- **NOT RUN** — `Priivacy-ai/spec-kitty-end-to-end-testing` is a separate private repo, never
  cloned locally. It exists and is accessible (`gh repo view` succeeds, last push 2026-07-16);
  CI fetches it via `.github/workflows/release.yml:303`.
- **Assessed as N/A rather than EXCEPTION.** ADR `2026-04-26-3-e2e-hard-gate.md` scopes the
  hard gate to missions touching cross-repo behaviour (events / tracker / SaaS / sync / merge /
  intake / runtime). This mission changes a **local review-verdict read path** with no
  cross-repo surface: the diff touches no events schema, no tracker, no SaaS client, no sync,
  and no intake. No `mission-exception.md` was authored because the exception path is for
  environmental blockers on in-scope missions, and claiming one here would misrepresent an
  out-of-scope gate as a waived one.
- **Operator decision required** — see Open Items. If the operator judges the gate in-scope,
  the repo should be cloned and the gate run rather than waived.

### Gate 4 — Issue Matrix
- File: `kitty-specs/review-cycle-read-authority-01KYB6Z0/issue-matrix.md`
- Rows: 2 · Empty/`unknown` verdicts: **0** · `in-mission` survivors: **0**
- `#2646` → `fixed` (evidence names the delivering surface per WP)
- `#2626` → `deferred-with-followup`, follow-up handle = the open upstream issue #2626 itself,
  scoped out by spec C-006
- **PASS**

---

## Headline Evidence — the fix works on the case that motivated it

The mission's own board was the live reproduction. Before merge, the installed CLI reported:

```
WP02 — Tasks-status verdict honours the approval override   ⚠ review artifact: verdict=rejected
```

An **approved** work package flagged as carrying a rejected review — on the very work package
titled "Tasks-status verdict honours the approval override". WP02 holds a rejected
`review-cycle-1.md` plus a complete `ReviewOverride`, i.e. exactly the #2646 shape.

Running the **merged code** against that same unmodified mission data:

```
MERGED CODE — stale-verdict warnings: 0
WP02 lane: done
```

Same data, same work package, different code. This is stronger than any synthetic fixture:
the defect reproduced on the mission's own lifecycle record, and the merged code clears it.

---

## FR Coverage Matrix

| FR | Description | WP Owner | Test File(s) | Adequacy | Finding |
|----|-------------|----------|--------------|----------|---------|
| FR-001 | Status verdict honours the override | WP01, WP02 | `test_status_review_override.py`, `test_tasks_status_review_override.py` | ADEQUATE | — |
| FR-002 | Status view and merge gate agree | WP01–03 | `test_status_review_override.py`, `test_wp_review_snapshot_entry.py` | ADEQUATE | — |
| FR-003 | Genuine rejections still reported | WP01–03 | `test_move_task_override_guard.py` (3 refusal arms, exact message text) | ADEQUATE | — |
| FR-004 | Override-blind reads retired | WP02, WP04 | `test_review_verdict_disposition.py` | ADEQUATE | — |
| FR-005 | Every verdict-input site has a disposition | WP05 | `test_review_verdict_disposition.py` (2-pass, 9 + 13 sites) | ADEQUATE | — |
| FR-006 | Tolerant degradation preserved | WP01, WP02 | `test_status_review_override.py` (4 degradation cases) | ADEQUATE | — |
| FR-007 | Override asserted once, not repeatedly | WP03 | `test_move_task_override_guard.py:285` + 2 durability tests | ADEQUATE (behaviour) | **DRIFT-1** (ID uncited) |

**Anti-synthetic verification**: all three override suites drive the override through a **real
event log** (`append_annotations_atomic_verified` + `InnerStateChanged`), never a hand-built
snapshot dict. This was an explicit rejection criterion in every WP prompt, because a
hand-built fixture passes against the *broken* implementation and proves nothing. Reviewers
independently confirmed non-vacuity by reverting fixes and observing red.

---

## Drift Findings

### DRIFT-1: FR-007 has no test citing its ID

**Type**: PUNTED-FR (traceability only) · **Severity**: LOW
**Spec reference**: FR-007
**Evidence**: `grep -rl "FR-007" tests/` returns only `tests/_arch_shard_map.py`,
`tests/agent/test_orchestrator_commands_integration.py`, and a coverage README — none of them
this mission's tests. WP03's suite cites only FR-003.

**Analysis**: This is a **documentation gap, not a delivery gap**. FR-007's behaviour is
directly tested by `test_complete_override_permits_transition_without_reasserting_flags`
(`test_move_task_override_guard.py:285`), plus `test_first_override_still_persists_evidence:362`
and `test_prior_override_evidence_survives_second_move_without_duplication:381`. The reviewer
independently proved these fail without the fix by reverting to `f393c0aa4`. The FR ID simply
was never written into a docstring or comment, so an automated coverage grep under-reports it.

### DRIFT-2: WP02 carries 5 forced transitions

**Type**: PROCESS · **Severity**: LOW
**Evidence**: coord event log — `force=true` on 5 WP02 transitions; 0 for all other WPs.
0 self-approval events mission-wide.

**Analysis**: Each force is individually justified and carries a rationale in its `--note`:
(1) the reviewer's rejection, (2) the arbiter approval after fix cycle 1, (3) an agent-attribution
repair, (4) reopening to re-record a proper override, (5) restoring `approved`. Items 3–5 were
orchestrator remediation of tooling defects (RISK-2, RISK-3), not implementation churn. Noted so
a future reader can account for the count rather than read it as instability.

### DRIFT-3: `meta.json` `baseline_merge_commit` is not a baseline

**Type**: METADATA · **Severity**: LOW
**Evidence**: `meta.json` records `dc6675527d5a4ffe28aa172dc74ff6ccaabf4423`, which is
`chore(status): primary-partition runtime state from the override sync` — a commit created
*during* this mission's merge, on this mission's own branch.

**Analysis**: Any future reviewer diffing `baseline_merge_commit..HEAD` sees almost none of the
mission's changes. The true fork point is `721165a22`. This is a spec-kitty field-population
defect, not something a WP introduced — but it silently breaks the primary review workflow that
this very skill prescribes in Step 3.

---

## Risk Findings

### RISK-1: Post-merge stale-assertion analyzer false positive (verified benign)

**Type**: TEST-INTEGRITY · **Severity**: LOW (resolved)
**Location**: `tests/unit/migration/test_backfill_runtime_state.py:147,509`, `tests/dossier/test_events.py`

**Analysis**: The merge-time analyzer flagged 4 tests asserting on a `'review'` string literal
"removed from `review_artifact_consistency.py:123`" — WP04 deleting `_snapshot_review_override`.
**Verified false positive**: those assertions target the snapshot's `review` *slot*
(`wp["review"]["actor"]`), which is alive and is the central entity of this mission — not the
deleted function's internals. Confirmed by running them: **61 passed**. This is the analyzer's
known relocation/re-export false-positive class.

### RISK-2: Review rejection silently blanks agent attribution

**Type**: TOOLING-DEFECT (upstream, not this mission's code) · **Severity**: MEDIUM
**Location**: `src/specify_cli/status/reducer.py:179-181` (`_apply_annotation_delta` → `_REPLACE_SLOTS`)

> **CORRECTED**: an earlier version of this finding cited the claim arm at `:148-150`. The
> demonstrated blanking is event `01KYCC4Y1AQHK25RJS513HFJ63` (`delta` keys `agent`/`shell_pid`/
> `subtasks`, `agent=""`), which folds through the **annotation** arm at `:179-181`. Both arms
> carry the same `is not None` guard, but only the annotation arm is evidenced here. The emit site
> that produced the empty value is **not verified** — the resolved-binding annotation originally
> blamed carries no `agent` key at all.

**Analysis**: `agent` is a latest-wins replace slot; a value of `""` is not `None`, so it
overwrote the correct `'claude'`. Canonical runtime
state ended with an empty agent, which blocked `spec-kitty accept`. `spec-kitty agent status
doctor` reported the mission **Healthy** throughout — the defect is silent until acceptance.
Only WP02 was affected: it is the only WP that took a rejection cycle. A `if agent:` guard
would prevent it. **Not introduced by this mission**; surfaced by it.

### RISK-3: ReviewOverride annotations land on PRIMARY; merge gate reads COORD

**Type**: TOOLING-DEFECT (upstream) · **Severity**: HIGH
**Location**: partition resolution for annotation writes vs `find_rejected_review_artifact_conflicts`

**Analysis**: The most severe finding, and it generalises well beyond this mission.

- Transitions land on COORD (76 events, correct lane progression).
- `ReviewOverride` annotations land on PRIMARY (6 events; lane never initialises there).
- `spec-kitty merge`'s review-artifact gate materialises **COORD**.

Consequently the override is structurally unreachable by the gate. Combined with
`validate_review_artifact` hard-requiring `verdict == "rejected"` — an approval can *never* write
an approved artifact — the chain is:

1. A rejected-then-approved WP permanently has a rejected latest artifact.
2. The only suppressor is a complete `ReviewOverride`.
3. Overrides go to PRIMARY; the gate reads COORD.

**Therefore any coord-topology mission that had a review rejection cannot merge.** The gate's own
remediation text — *"Run another review cycle that writes an approved review-cycle artifact"* — is
impossible by construction, the same un-followable-remediation shape as #2831.

**Resolution applied**: event `01KYCDFEPYM7TXJ4ZK13SK5PSP` (the reviewer's genuine fix-cycle-1
override, `actor=operator`, 1914-char rationale) was copied **verbatim** — same `event_id`,
timestamp and payload — from the primary log into coord, with a duplicate-guard assertion and a
backup. This repairs a partition split; it does not author evidence. Commit `20fad557a` carries
the full analysis.

### RISK-4: Doctrine skills prescribe the retired retrospective path

> **CORRECTED 2026-07-26 after tracker triage.** The original RISK-4 claimed the retrospective was
> written to the *wrong* path. **That finding was inverted and is withdrawn.** The generator behaved
> correctly; the defect is in the documentation that verifies it — including the skill this report
> was produced under.

**Type**: DOCTRINE-DRIFT · **Severity**: LOW
**Location**: `spec-kitty-mission-review` SKILL.md and three sibling doctrine skills

**Analysis**: `retrospective/writer.py:89-98` (`canonical_record_path`) resolves the record to
`kitty-specs/<slug>/retrospective.yaml` per **FR-001/003 (#2119)**, explicitly "never the ephemeral
coordination worktree (the #1771 coord-leak)". `_legacy_record_path` at `:102-110` documents
`.kittify/missions/<mission_id>/` as "the pre-#1771 (gitignored) record path for back-compat reads
only… **New writes never target this path.**" The terminus facilitator wrote to the canonical
location. Nothing was misplaced.

**How the original finding went wrong — worth recording, because it is a reusable trap.** The
verification command prescribed by this skill points at the *retired* path, so it reported the
record missing. I then "corroborated" that by observing three other missions using
`.kittify/missions/<id>/` and concluded this mission was the outlier. Those three records are all
dated **2026-06-20** — authored before the FR-006 relocation. I compared current behaviour against
legacy artifacts and concluded the correct behaviour was the deviation. **Age-check comparators
before treating them as a convention.**

**The real defect**: four doctrine `SKILL.md` files still prescribe the retired path. An operator
following them sees "missing" and runs `spec-kitty retrospect create`, authoring a **duplicate**
record. Filed as part of #2961.

---

## Silent Failure Candidates

| Location | Condition | Silent result | Assessment |
|----------|-----------|---------------|------------|
| `agent_utils/status.py:77` | event log absent / unreadable | falls back to file-only verdict | **Intended** (FR-006/INV-5). Notably *narrowed* a pre-existing blanket `except Exception` to `except (ValueError, OSError)` — an improvement. |
| `tasks_parsing_validation.py:153` | canonical schema rejects artifact | raw-frontmatter fallback, then `(None, path)` | **Intended** (WP02 fix cycle 1). Legacy verdicts and legacy field-name schemas are preserved rather than collapsing to `None`. |
| `wp_review.py:70-77` | absent / malformed `review` slot | `None` | **Intended** — documented contract; incomplete overrides are honoured nowhere (C-004). |
| `tasks_status_cmd.py:283` | event read fails | `st.events = []` | **Pre-existing** posture, deliberately preserved by WP02. |

No unintended silent-failure paths found. Every degradation is spec-mandated and test-covered.

---

## Security Notes

| Area | Result |
|------|--------|
| subprocess / `shell=True` | None introduced |
| Path construction from user input | None introduced |
| Network / HTTP | None introduced |
| Credentials / tokens | None touched |
| New suppressions | 1 × `# noqa: PLC0415` on a lazy import — the file's existing convention, not a correctness suppression |

The mission reads local files and an append-only log. **No new attack surface.**

---

## Final Verdict

**PASS WITH NOTES**

### Verdict rationale

All seven FRs are adequately covered by tests that constrain real runtime behaviour, verified
against the anti-synthetic criterion: every override test drives through a real event log, and
reviewers independently proved non-vacuity by reverting fixes and observing red. Both locked
constraints hold mechanically — C-001 (no artifact changes partition) and C-002
(`tasks_transition_core.py` shows **zero diff**). Gates 1, 2 and 4 pass; Gate 3 is assessed
out-of-scope pending operator confirmation. No dead code: all three new symbols have live
callers. Security surface unchanged. The strongest evidence is behavioural — the merged code
clears the exact false positive the mission's own board was displaying.

No CRITICAL or HIGH finding exists **in this mission's code**. RISK-3 is HIGH but is an upstream
tooling defect that the mission surfaced and worked around with a documented, verbatim repair;
it does not affect the shipped implementation.

### Open items (non-blocking)

1. **Operator decision on Gate 3** — clone `Priivacy-ai/spec-kitty-end-to-end-testing` and run it
   if the gate is judged in-scope, or confirm N/A. Do not author a `mission-exception.md`; the
   exception path is for environmental blockers, not out-of-scope gates.
2. **File RISK-3** — any coord-topology mission with a review rejection cannot merge. Highest-value
   report from this mission.
2b. **RISK-4 corrected and filed** — the original finding was inverted and is withdrawn; the
   generator was right. Real defect is four doctrine skills prescribing the retired path. Filed in
   #2961. **RISK-3 and RISK-4 do NOT share a root cause** — that hypothesis is dead.
3. **File RISK-2** — `if agent is not None` should be `if agent:` at `reducer.py:148`.
4. **File DRIFT-3** — `baseline_merge_commit` populated with an in-merge commit, breaking the
   prescribed mission-review diff workflow.
5. **DRIFT-1** — add `FR-007` to a docstring in `test_move_task_override_guard.py` for grep-based
   coverage tooling. Cosmetic.
6. **WP02 mislabeled test** (carried from re-review, non-blocking):
   `test_out_of_schema_verdict_with_complete_override_resolves_approved` does not exercise the
   fallback — its fixture uses `verdict: rejected`, which *is* schema-valid, so the canonical path
   never raises. The DoD requirement is proven by its sibling test; the docstring overstates what
   this one covers.
7. **Five further tooling defects** surfaced during execution and worth filing: pre-review gate
   timed out 8/8 (#2573); acceptance-matrix scaffolded all-`pending` with TODO placeholders
   (#2743 territory); software-dev path conventions require `contracts/` for missions with no
   contracts (#2744 territory); `spec-kitty merge` has no skip flag for the review-artifact gate;
   `agent status doctor` reports Healthy while canonical runtime state is corrupt.

---

## Tracker Disposition (planner-priti, 2026-07-26)

9 defects → **3 new issues, 6 comments**. Deliberately not over-filed.

| Issue | Covers | Notes |
|-------|--------|-------|
| [#2959](https://github.com/Priivacy-ai/spec-kitty/issues/2959) | RISK-3 + no merge escape flag | Sub-issue of epic #2160. Distinct from #1817 — that mechanism is fixed, and fixing it does not help because the override is absent from the log the gate reads. P0-vs-P1 flagged for operator decision. |
| [#2960](https://github.com/Priivacy-ai/spec-kitty/issues/2960) | RISK-2 + doctor blind to corrupt state | Corruption and missing detection are one coherent fix. |
| [#2961](https://github.com/Priivacy-ai/spec-kitty/issues/2961) | RISK-4 (corrected) + DRIFT-3 | Both are broken prescribed steps of this same post-merge workflow. |

Comments added to **#2573** (gate evidence + its "no skip flag" body is stale —
`--skip-pre-review-gate` exists at `tasks.py:668-671`), **#2329** (path conventions — *not* #2744,
which is research-mission-scoped), **#2743** (context only), **#1817** and **#2275** (cross-links),
and **#2646** (keep-open note so a maintainer does not close it before the cross-fork PR lands).

**Recommendation: do not open a mission for these.** RISK-3 is not independently scopable — it
clusters with #1817, #2275 and #2646 under one root (review-verdict write/read authority under
coord topology), all homed under epic #2160. The right mission is over that cluster, folding
#2959 + #2275 + #1817 and retiring the un-followable remediation text. The remaining defects are
small, unrelated, and are fold-ins for whatever mission next touches `status/`, doctrine skills,
and `merge/`.

---

## Retrospective Reminder

The canonical post-merge sequence is **mission review → author or verify retrospective →
surface findings**.

The retrospective was captured at terminus (commit `d8613bd79`) at its **canonical** location:

```bash
cat kitty-specs/review-cycle-read-authority-01KYB6Z0/retrospective.yaml
```

> **Do not use** `.kittify/missions/<mission_id>/retrospective.yaml` — that is the retired
> pre-#1771 path (`writer.py:102-110`, "back-compat reads only… New writes never target this
> path"). Several doctrine skills, including the one that produced this report, still prescribe it;
> following them reports the record missing and leads to authoring a duplicate. See RISK-4 / #2961.

`findings_status` is populated; `proposals` is empty (0), so `synthesize` has nothing to apply for
this mission — and `retrospect create` must **not** be run.

Then surface findings:

```bash
spec-kitty retrospect summary                                                    # cross-mission, read-only
spec-kitty agent retrospect synthesize --mission review-cycle-read-authority-01KYB6Z0        # dry-run
spec-kitty agent retrospect synthesize --mission review-cycle-read-authority-01KYB6Z0 --apply
```
