---
work_package_id: WP01
title: Profile-to-Soul.md projector, mapping doc, committed personas
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- C-002
planning_base_branch: kitty/mission-crosslayer-composition-suite
merge_target_branch: kitty/mission-crosslayer-composition-suite
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-crosslayer-composition-suite. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-crosslayer-composition-suite unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-crosslayer-composition-suite-01KYJA33
base_commit: 230ae7f0be81083f98bd80d1ffaed8bd577bffe6
created_at: '2026-07-27T20:31:41.914275+00:00'
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
- T007
agent: claude
history:
- timestamp: '2026-07-27T19:45:23Z'
  event: created
  by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: python-pedro
authoritative_surface: conformance/tools/
create_intent:
- conformance/tools/profile2soul.py
- conformance/tools/PROJECTION.md
- conformance/crosslayer/personas/architect-alphonso.Soul.md
- conformance/crosslayer/personas/reviewer-renata.Soul.md
- conformance/scripts/check-persona-drift.sh
execution_mode: code_change
model: ''
owned_files:
- conformance/tools/profile2soul.py
- conformance/tools/PROJECTION.md
- conformance/crosslayer/personas/architect-alphonso.Soul.md
- conformance/crosslayer/personas/reviewer-renata.Soul.md
- conformance/scripts/check-persona-drift.sh
role: implementer
tags: []
tracker_refs: []
---

# WP01 — Profile-to-Soul.md projector, mapping doc, committed personas

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the
frontmatter, and behave according to its guidance before parsing the rest of
this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

If no profile is specified, run `spec-kitty agent profile list` and select the
best match for this work package's `task_type` and `authoritative_surface`.

---

## Objective

Build the deterministic `*.agent.yaml → Soul.md` projector
(`conformance/tools/profile2soul.py`), document its field mapping and
fidelity-loss table (`conformance/tools/PROJECTION.md`), commit the two
personas this mission needs (`architect-alphonso.Soul.md`,
`reviewer-renata.Soul.md`) under `conformance/crosslayer/personas/`, and give
that drift check its own committed, lane-a-owned script
(`conformance/scripts/check-persona-drift.sh`) rather than leaving it as an
inline command only lane-b could fix.

This mission's own artifact — the projector — is, by D1's own words, "the
programme's least-principled artifact": it fabricates RFC-1 fields
(`voice`, `interaction`, `locale`, four empty lists) that are never graded
(C-003) and never seen by `contradiction-lint.ts` (verified directly against
muster's source, see Context). Do not let that fabrication leak into any
check's stated reason for passing or failing.

## Context (read first)

- Spec: `kitty-specs/crosslayer-composition-suite-01KYJA33/spec.md`
  — FR-001, FR-002, FR-003; Edge Cases ("Fabricated-field grading leakage",
  "Projector regeneration drift"); Dependencies & Assumptions' "Lane
  isolation" bullet (post-plan-review corrected form) and the "Citation
  pinning" bullet.
- Plan: `kitty-specs/crosslayer-composition-suite-01KYJA33/plan.md`
  — IC-01 (this WP's source concern, including the hazard-3 restructuring
  that gives FR-003 its own script instead of an inline-only command);
  IC-00 ("dissolved, not resolved" — read this in full: it is why lane-b
  (WP02) does **not** need this WP's literal projector output to start or
  finish its own work, only the two filenames below).

**Verified independently before this task file was written** (do not take the
plan's claim on faith — it was re-checked against muster's actual source at
the pinned commit `624edd6dddedb86fb89f13084510f02b5a2c7d25`):
`resolvePersonaLayer` (`composition.ts:281-320`) returns only
`personaDoc.body.trim()` into `layerTexts`; `contradiction-lint.ts`'s own
docstring states plainly "C-003: Lint runs on resolved.layerTexts — never raw
fixture files," and `extractClauses`/`analyseLayerPair` operate exclusively
on that map. RFC-1 front-matter (the fabricated fields this WP invents) is
only ever consulted, structurally, by `resolvePersonaLayer`'s own RFC-1
strict-mode presence/shape check — never by the lint. This is why this WP's
correctness is judged on its body-text mapping (FR-001) and its committed
persona files being real and regenerate-clean (FR-003), not on inventing
"the right" fabricated values — there is no such thing; C-003 forbids citing
them as evidence either way.

**The one thing lane-b (WP02) actually depends on from this WP**: the exact
committed filenames — `conformance/crosslayer/personas/architect-alphonso.Soul.md`
and `conformance/crosslayer/personas/reviewer-renata.Soul.md` — must match
byte-for-byte as *paths* (not content) what WP02's `manifest.yaml`/case files
declare as `fixturePath`. WP02 does not read this WP's output to do its own
work (lanes are parallel, no shared pre-step); it only needs the filenames to
agree. Do not rename these two files after committing them without checking
whether WP02 has already merged.

**DIR-012 status, checked, not assumed**: this mission's seed is GitHub issue
`MOES-Media/spec-kitty#26`. `gh issue view 26 --repo MOES-Media/spec-kitty
--json assignees` was checked while authoring this task file and returned
**zero assignees** (unlike M1's issue #22, which was already assigned when
that mission's WP01 was authored). This is a real, outstanding gate — T001
below is not a formality here.

## Subtasks

### T001 — Satisfy DIR-012 (tracker issue assigned to HiC)

**Purpose**: Charter gate DIR-012 requires the tracker-backed seed issue to be
assigned to the Human-in-Charge before implementation starts on this
mission's first work package. Unlike M1's precedent, this is **not**
already satisfied.

**Steps**:
1. Run `gh issue view 26 --repo MOES-Media/spec-kitty --json assignees` and
   confirm at least one assignee is present. If it still returns zero
   assignees, assign the issue to the Human-in-Charge
   (`gh issue edit 26 --repo MOES-Media/spec-kitty --add-assignee <HiC-login>`)
   before proceeding.
2. Record the confirmation (assignee login, timestamp, and whether it was
   already assigned or assigned by this step) as a one-line work-log entry.
   Do not proceed to T002 until this is recorded.

**Files**: none (verification/administrative only).
**Validation**: the work log contains an explicit DIR-012 confirmation line
naming a real assignee login.

---

### T002 — Author `conformance/tools/profile2soul.py` (FR-001)

**Purpose**: Deterministic, byte-stable projection from a built-in agent
profile YAML to an RFC-1-conformant `Soul.md`.

**Steps**:
1. Map fields per FR-001's table: `profile-id → id`, `name → name`,
   `initialization-declaration` + `purpose` + `description` +
   `specialization.primary-focus` + `specialization.avoidance-boundary` →
   body sections (the profile's own boundary statement is instructional
   content — carry it, do not drop it).
2. Fabricate the required-but-absent RFC-1 keys from a frozen, in-script
   defaults table this task authors: `locale`, four `voice` 0–100 integers,
   four `interaction` enums, and empty `composition`/`profiles`/
   `profile_overrides`/`extensions` lists. Document the table's values in
   `PROJECTION.md` (T003) — do not invent new values later without updating
   both files together.
3. Emit a header comment recording `generated: true` plus a source-profile
   content hash (this is also C-003's textual-audit anchor — the corrected
   exclusion pattern reviewers use, `^#.*generated:\s*true`, depends on this
   exact shape; do not vary it).
4. Use Python stdlib only (no new runtime dependency) per plan.md's Technical
   Context — if `*.agent.yaml` parsing needs a YAML library, reuse whatever
   this fork already vendors for agent-profile parsing rather than adding
   one.
5. No wall-clock timestamps, no unordered dict/set iteration anywhere in the
   output path — this is the determinism property T006 falsifies directly.

**Files**: `conformance/tools/profile2soul.py` (new).
**Validation**: covered by T006 (real execution, both directions).

---

### T003 — Author `conformance/tools/PROJECTION.md` (FR-002)

**Purpose**: Document the field mapping, the fabricated-defaults table, and a
fidelity-loss table naming exactly what the projection cannot carry.

**Steps**:
1. Write the field-mapping section matching T002's actual mapping.
2. Write the fabricated-defaults table (the frozen values T002 uses).
3. Write a `## Fidelity Loss` section naming fields the projection
   structurally cannot carry because no RFC-1 key exists for them:
   `capabilities`, `routing-priority`, `context-sources`,
   `directive-references`, `tactic-references`. **Do not** list
   `purpose`, `initialization-declaration`, `description`, or
   `specialization.*` here — those fields *are* carried (T002's mapping),
   and FR-002's own corrected verification command (T006) specifically
   checks that `initialization-declaration` is absent from this section,
   using the post-spec-corrected assertion form (see T006 — the old
   `grep -qv` form was a vacuous check that this mission's own spec.md
   documents catching).

**Files**: `conformance/tools/PROJECTION.md` (new).
**Validation**: covered by T006.

---

### T004 — Generate and commit the two personas (FR-003)

**Purpose**: Commit `architect-alphonso.Soul.md` and `reviewer-renata.Soul.md`
under `conformance/crosslayer/personas/`, projected from this fork's real
built-in profiles.

**Steps**:
1. `python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > conformance/crosslayer/personas/architect-alphonso.Soul.md`
2. `python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/reviewer-renata.agent.yaml > conformance/crosslayer/personas/reviewer-renata.Soul.md`
3. Confirm the exact filenames above — WP02 (a different lane) declares these
   two paths as `fixturePath` values in its own case files without reading
   this WP's source; a filename mismatch here breaks WP02's manifest
   silently, only surfacing once both lanes are merged.
4. Commit both files now (do not leave them staged only) — WP02 needs the
   *paths* to exist in this WP's own commit history for the eventual merge,
   though (per IC-00's dissolution) it does not need to read their bytes to
   do its own work in the meantime.

**Files**: `conformance/crosslayer/personas/architect-alphonso.Soul.md`,
`conformance/crosslayer/personas/reviewer-renata.Soul.md` (both new,
committed).
**Validation**: both files exist at the exact paths above; `git log` shows
them committed on this WP's lane branch.

---

### T005 — Author `conformance/scripts/check-persona-drift.sh` (hazard-3 restructuring)

**Purpose**: Give FR-003's drift check its own committed, lane-a-owned
script — the same pattern FR-007 gets (`check-sop-extract-drift.sh`, a
different lane's WP) — so the party who owns the checked artifact (this WP)
is also the party who can fix a broken drift check, instead of that logic
living only inline inside WP04's `crosslayer.yml` (a file this WP never
touches).

**Steps**:
1. Write a script that regenerates both personas from their source profiles
   (the same two commands as T004) into a temp location, then
   `git diff --exit-code` compares them against the committed copies under
   `conformance/crosslayer/personas/`.
2. Exit `0` on a clean (no-drift) result, non-zero if either persona differs
   from its regenerated form.
3. WP04's `crosslayer.yml` will call this script as a one-line call site
   (`bash conformance/scripts/check-persona-drift.sh`) — do not require any
   argument or environment variable beyond what a bare invocation from the
   repo root provides.

**Files**: `conformance/scripts/check-persona-drift.sh` (new).
**Validation**: covered by T006.

---

### T006 — Mandatory real-CLI verification (operator directive)

This mission cannot be called done on inspection alone. Run every command
below for real and record the exact observed exit code (and, where
specified, exact text) in the work log.

**Purpose**: Prove FR-001's determinism, FR-002's corrected fidelity-loss
check, and FR-003/T005's drift gate all behave as specified — using the spec's
own exact commands, not paraphrases.

**Steps**:
1. **FR-001 determinism** (verbatim from spec.md):
   ```sh
   python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > /tmp/a.md
   python3 conformance/tools/profile2soul.py src/doctrine/agent_profiles/built-in/architect-alphonso.agent.yaml > /tmp/b.md
   diff /tmp/a.md /tmp/b.md
   ```
   Expect exit **0**. **Falsification** (must be observed, not merely
   asserted possible): make a *local, uncommitted* copy of the projector,
   inject one non-canonicalized/unstable source into it (a
   `time.time_ns()` line, or unordered dict iteration), rerun the identical
   two-step comparison against the modified copy — `diff` must exit **1**.
   Record both exit codes. Discard the modified copy afterward; it must
   never be committed.
2. **FR-002 fidelity-loss check** (verbatim, corrected H1 form):
   ```sh
   grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md | grep -q "capabilities" && \
   grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md | grep -q "routing-priority" && \
   ! grep -A20 "^## Fidelity Loss" conformance/tools/PROJECTION.md | grep -q "initialization-declaration"
   ```
   Expect exit **0**. **Falsification**: temporarily edit a local copy of
   `PROJECTION.md` so its Fidelity Loss section also lists
   `initialization-declaration` alongside `capabilities`/`routing-priority`,
   rerun the identical command against that copy — expect exit **1**. Do not
   use the old `grep -qv "initialization-declaration"` form; the spec
   documents it as a vacuous pass (exits 0 whenever *any* other line in the
   20-line window fails to match, regardless of whether the target string is
   present) — do not reintroduce it.
3. **FR-003 drift gate, both directions**:
   ```sh
   bash conformance/scripts/check-persona-drift.sh
   ```
   Expect exit **0** on a clean tree. **Falsification**: hand-edit one byte
   of one committed persona file, rerun — expect exit **1**; then restore the
   file exactly and confirm `git diff --exit-code
   conformance/crosslayer/personas/` shows a clean tree again. Paste the
   `git diff` output captured immediately after the hand-edit (before
   restoring) into the work log, not just the restored-clean confirmation —
   a restored-clean diff alone does not prove the falsification direction was
   actually exercised.

**Files**: none new — this subtask exercises T002–T005's outputs only.
**Validation**: all exit codes above (six total: 1 pass + 1 fail for each of
three checks) recorded verbatim in the work log; the FR-002 falsification's
edited-section text and the FR-003 falsification's mid-test `git diff` output
both quoted, not just described.

---

### T007 — WP01 verification gate (Definition of Done gate + per-lane C-002)

**Steps** (run in order):
```bash
git diff --stat                                   # ONLY the five owned_files entries changed
git diff --stat src/doctrine/                     # MUST show no changes — read-only input, never edited
git diff --stat .github/                          # MUST show no changes — not this WP's concern
git diff --name-only <mission-base>...<this-lane-branch> > /tmp/wp01-c002-diff.txt
if grep -qx "conformance/README.md" /tmp/wp01-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
! (grep -v '^conformance/' /tmp/wp01-c002-diff.txt | grep -v '^kitty-specs/' | grep -q .)
```
The last two lines are this WP's **per-lane C-002 check** (spec.md's C-002
verification command, scoped to `<mission-base>...<this-lane-branch>` instead
of `main...HEAD` — the cross-lane assembled-diff run happens again later, at
mission review, as the backstop; this per-lane run is this WP's own
responsibility and must pass before requesting review). Substitute this WP's
actual base commit and lane branch name once the lane worktree is allocated.

## Definition of Done

- [ ] DIR-012 satisfied and recorded (T001), including the assignee login,
      before T002 began
- [ ] `profile2soul.py` maps every field FR-001 names, fabricates the six
      RFC-1 key groups from a frozen, documented table, and emits the
      `generated: true` + source-hash header comment in the exact shape
      C-003's audit pattern expects
- [ ] `PROJECTION.md` documents the mapping, the fabricated-defaults table,
      and a Fidelity Loss section that names the five structurally-dropped
      fields and omits every carried field
- [ ] Both personas committed at the exact required paths
- [ ] `check-persona-drift.sh` exists, is lane-a-owned, and is a thin
      one-line call site away from WP04's `crosslayer.yml`
- [ ] All of T006's six real exit codes recorded verbatim in the work log,
      including both falsification directions' actual observed output (not
      "should fail" — the real command output)
- [ ] No file outside `owned_files` modified; `src/doctrine/**` and
      `.github/**` untouched
- [ ] Per-lane C-002 check (T007) passes against this WP's own lane diff

## Risks

- **Filename mismatch with WP02**: this is one of five path-only couplings
  across this mission's task files (M-3 post-tasks-review finding, spec.md
  Dependencies & Assumptions — this pair is no longer the only one named).
  If either persona filename changes after WP02 has already authored its
  case files against the original names, WP02's manifest silently breaks
  only once both lanes are merged. Communicate any filename change
  immediately if WP02 is concurrently in progress.
- **Fabricated-field leakage (C-003)**: it is tempting to describe *why* a
  fabricated `voice`/`interaction` value was chosen in `PROJECTION.md`'s
  prose in a way that reads as grading justification. C-003 forbids citing
  these values as evidence for any pass/fail — keep the defaults table
  purely descriptive (this is what gets fabricated and why the projector
  needs to), never evaluative (this value is why the check passed).
- **DIR-012 was not pre-satisfied for this mission**, unlike M1's precedent —
  T001 is a real gate here, not a formality; do not skip it on the
  assumption it already holds.

## Reviewer guidance

- **Reject if** T001's DIR-012 confirmation names no real assignee.
- **Reject if** any of T006's six exit codes is missing from the work log, or
  if a falsification direction's actual output (not just "expected: 1") is
  absent.
- **Reject if** `PROJECTION.md`'s Fidelity Loss section lists
  `initialization-declaration`, `purpose`, `description`, or
  `specialization.*` — those are carried fields (T002's mapping), and their
  presence here would itself fail T006 step 2's corrected check.
- **Reject if** `check-persona-drift.sh` requires any argument or environment
  variable beyond a bare repo-root invocation — WP04's call site assumes
  none.
- **Reject if** the header comment's `generated: true` shape differs from
  `^#.*generated:\s*true` — C-003's reviewer-facing audit command
  (spec.md) depends on this exact anchor to avoid a false-positive on a
  rubric sentence that happens to contain the word "generated."
- Confirm `git diff --stat` touches exactly the five `owned_files` entries
  and nothing under `src/doctrine/**` or `.github/**`.

Implementation command: `spec-kitty agent action implement WP01 --agent claude`

## Activity Log

(none yet — populated during implementation)
