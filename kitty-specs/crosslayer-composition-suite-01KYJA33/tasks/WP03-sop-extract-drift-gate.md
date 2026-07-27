---
work_package_id: WP03
title: SOP policy extract and its own drift gate (lane-b part 2)
dependencies: []
requirement_refs:
- FR-007
- C-002
planning_base_branch: kitty/mission-crosslayer-composition-suite
merge_target_branch: kitty/mission-crosslayer-composition-suite
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-crosslayer-composition-suite. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into kitty/mission-crosslayer-composition-suite unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-crosslayer-composition-suite-01KYJA33
base_commit: 9bbed911bb4cc9fa93cef305891895511d6c10c8
created_at: '2026-07-27T20:39:34.660573+00:00'
subtasks:
- T014
- T015
- T016
- T017
agent: claude
history:
- timestamp: '2026-07-27T19:45:23Z'
  event: created
  by: /spec-kitty.tasks-outline (planner-priti)
agent_profile: implementer-ivan
authoritative_surface: conformance/crosslayer/
create_intent:
- conformance/crosslayer/sop-extract.md
- conformance/scripts/check-sop-extract-drift.sh
execution_mode: code_change
model: ''
owned_files:
- conformance/crosslayer/sop-extract.md
- conformance/scripts/check-sop-extract-drift.sh
role: implementer
tags: []
tracker_refs: []
---

# WP03 — SOP policy extract and its own drift gate

## ⚡ Do This First: Load Agent Profile

Use the `/ad-hoc-profile-load` skill to load the agent profile specified in the
frontmatter, and behave according to its guidance before parsing the rest of
this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

If no profile is specified, run `spec-kitty agent profile list` and select the
best match for this work package's `task_type` and `authoritative_surface`.

---

## Objective

Author a bounded, committed `AGENTS.md` operating-policy extract
(`conformance/crosslayer/sop-extract.md`, OQ-6 option (b), committed as
**final**, not provisional) with its own drift-check script
(`conformance/scripts/check-sop-extract-drift.sh`), mirroring FR-003's
persona-drift pattern exactly. This WP has no dependency on WP01 or WP02 —
it reads only `AGENTS.md`, a shared, read-only repo-root file neither lane
owns.

## Context (read first)

- Spec: `kitty-specs/crosslayer-composition-suite-01KYJA33/spec.md`
  — FR-007; Edge Cases ("AGENTS.md as SOP slot may swamp small-model context
  (OQ-6)" — `AGENTS.md` is 35,933 bytes, verified via `ls -la AGENTS.md` at
  this mission's base commit); Dependencies & Assumptions' OQ-6 decision
  bullet (option (b), committed as **final** — this WP's extract is not
  gated on the later baseline-degradation spike; author it unconditionally).
- Plan: `kitty-specs/crosslayer-composition-suite-01KYJA33/plan.md`
  — IC-03 (this WP's source concern).

**OQ-6 is settled, not open, for this WP's purposes**: the choice of
*whether* to extract (vs. shipping the whole file, or per-rule minimal SOPs)
is permanently decided in favor of a policy extract. The only thing left open
(a later spike measuring whether extract byte-length correlates with
baseline degradation) informs future tuning only and does not gate this WP —
build the extract and its drift gate now, unconditionally.

## Subtasks

### T014 — Author `conformance/crosslayer/sop-extract.md`

**Purpose**: A bounded subset of `AGENTS.md`'s operating-policy sections,
small enough to sit alongside a persona and skill in a composed context
window without the small-model risk the full 35,933-byte file would pose.

**Steps**:
1. Read `AGENTS.md` at the repo root and identify the operating-policy
   sections relevant to composed cross-layer checking (the sections this
   mission's own rule-survival cases will eventually cite, e.g. sections
   touching direct-push and signing policy — see `rule-survival-045`/`029`'s
   eventual dependency in WP05, blocked on M3).
2. Extract those sections verbatim into `conformance/crosslayer/sop-extract.md`
   — no paraphrasing, since T015's drift script re-extracts by matching
   against the same source sections.
3. Keep the extract's section boundaries stable and documented (e.g. a
   comment naming which `AGENTS.md` headings were extracted) so T015's script
   has an unambiguous, mechanically-repeatable extraction rule, not a
   judgment call re-made by hand each time.

**Files**: `conformance/crosslayer/sop-extract.md` (new).
**Validation**: covered by T016.

---

### T015 — Author `conformance/scripts/check-sop-extract-drift.sh`

**Purpose**: Mirror FR-003's persona-drift pattern exactly for the SOP-extract
side of the composition — without it, OQ-6's choice of a committed extract
(rather than the whole file) would have no equivalent drift protection,
an asymmetry FR-007 exists specifically to close.

**Steps**:
1. Write a script that re-extracts the same source sections from `AGENTS.md`
   (the same mechanical rule T014 documents) and
   `git diff --exit-code`s the result against the committed
   `sop-extract.md`.
2. Exit `0` on a clean tree, non-zero if the extract has drifted from what a
   fresh re-extraction of `AGENTS.md` would produce.
3. WP04's `crosslayer.yml` calls this script as a one-line call site — do not
   require any argument beyond a bare repo-root invocation.

**Files**: `conformance/scripts/check-sop-extract-drift.sh` (new).
**Validation**: covered by T016.

---

### T016 — Mandatory real-CLI verification (operator directive)

**Steps**:
```sh
bash conformance/scripts/check-sop-extract-drift.sh
```
Expect exit **0** on a clean tree. **Falsification**: hand-edit one committed
line of `conformance/crosslayer/sop-extract.md` (never `AGENTS.md` itself —
that file is shared, read-only input), rerun — expect exit **1**; restore the
line exactly and confirm `git diff --exit-code conformance/crosslayer/sop-extract.md`
shows a clean tree again. Paste the mid-test `git diff` output (captured
immediately after the hand-edit, before restoring) into the work log, not
just the restored-clean confirmation.

**Files**: none new.
**Validation**: both exit codes (clean, falsified) recorded verbatim; the
mid-test diff quoted.

---

### T017 — WP03 verification gate (Definition of Done + per-lane C-002)

**Steps** (run in order):
```bash
git diff --stat                                   # ONLY the two owned_files entries changed
git diff --stat AGENTS.md                          # MUST show no changes — shared, read-only input
git diff --name-only <mission-base>...<this-lane-branch> > /tmp/wp03-c002-diff.txt
if grep -qx "conformance/README.md" /tmp/wp03-c002-diff.txt; then echo "C-002 violation"; exit 1; fi
! (grep -v '^conformance/' /tmp/wp03-c002-diff.txt | grep -v '^kitty-specs/' | grep -v '^\.github/workflows/crosslayer\.yml$' | grep -q .)
```
The last two lines are this WP's **per-lane C-002 check**, this WP's own
responsibility before requesting review; the cross-lane assembled-diff run
happens again at mission review as the backstop.

## Definition of Done

- [ ] `sop-extract.md` committed, containing a bounded, documented subset of
      `AGENTS.md`'s operating-policy sections
- [ ] `check-sop-extract-drift.sh` committed, mirrors FR-003's pattern, exits
      `0` clean / non-zero on drift, both observed for real (T016)
- [ ] `AGENTS.md` itself is untouched by this WP
- [ ] Per-lane C-002 check (T017) passes against this WP's own lane diff
- [ ] No file outside `owned_files` modified

## Risks

- **Paraphrasing instead of verbatim extraction**: if T014's extract does not
  match the source sections character-for-character, T015's script will
  either always report drift (false positive) or never detect real drift
  (false negative) depending on how loosely it re-extracts. Keep extraction
  mechanical and documented.
- **OQ-6 spike confusion**: do not block this WP on the separate,
  later baseline-degradation spike — that spike informs future tuning only,
  per the spec's explicit decision; this extract ships now, unconditionally.

## Reviewer guidance

- **Reject if** `AGENTS.md` itself was edited by this WP.
- **Reject if** T016's falsification direction (mid-test diff) is not quoted
  in the work log.
- **Reject if** the extraction rule is not documented well enough for a
  reviewer to independently confirm the drift script's re-extraction logic
  matches what was actually committed.
- Confirm the per-lane C-002 check (T017) was actually run.

Implementation command: `spec-kitty agent action implement WP03 --agent claude`

## Activity Log

- **MEDIUM-1 remediation (`--write`'s `mv` was cross-device, so neither
  atomic nor mode-preserving)**: the `mktemp` scratch file `--write` builds
  its replacement in was created under `${TMPDIR:-/tmp}` (tmpfs, st_dev 44
  in this environment) while `EXTRACT_FILE` lives on the repo's filesystem
  (btrfs, st_dev 43) — a different device, so the closing `mv` always took
  coreutils' cross-device copy-then-unlink fallback instead of a real
  `rename(2)`. Two consequences, both confirmed for real before fixing:
  - **(a) Mode regression on every successful `--write`.** Verified this
    lane's on-disk `conformance/crosslayer/sop-extract.md` was already at
    mode `600` (`git ls-files -s` reports `100644`; `stat` reported `600`;
    `git status` reported clean throughout — git tracks only the
    executable bit, never the full mode, so the regression was invisible
    to git). Restored it to `644` in the fix commit.
  - **(b) Non-atomicity.** Reproduced the exact class of failure the
    reviewer described: ran a bigfile-instrumented copy of the pre-fix
    script's `--write` in the background, polled the destination path at
    high frequency, and `SIGKILL`ed it the instant the destination's size
    changed — left a 3141-byte extract as a ~227MB truncated partial file,
    mode `600`. Confirmed the fixed script cannot reproduce this: the same
    kill-on-first-size-change technique against the fixed script only ever
    observes the destination at its pristine original size or the full
    regenerated size, never partial, across three attempts.
  - **Fix**: `mktemp "${EXTRACT_FILE}.XXXXXX"` (scratch file beside the
    destination, guaranteeing same filesystem, so `mv` is always a true
    same-device `rename(2)` — atomic regardless of file size) plus
    `chmod --reference="${EXTRACT_FILE}" "${WRITE_TMP_FILE}"` before the
    move (needed regardless of filesystem, since even a same-device
    `rename(2)` hands the moved inode's `mktemp`-assigned `0600` straight
    to the destination path). Corrected the adjacent comment, which
    claimed "a failure here leaves EXTRACT_FILE completely untouched" in a
    way that was true for a `regenerate()`/`extract_section` failure but
    previously false for a kill during the `mv` itself; the comment now
    explains the `mv`'s atomicity explicitly rather than leaving it an
    unproven, implicit claim.
  - **Test**: extended `test_write_flag_regenerates_and_default_is_then_clean`
    (`tests/cross_cutting/test_check_sop_extract_drift.py`) to pin the
    extract's mode across a successful `--write`. Watched it fail against
    the pre-fix script first (`AssertionError ... got 0o600`), then pass
    after the fix. Full suite: 8 passed (count unchanged — an existing
    test was extended, not a new one added).
  - Re-ran the T016-style mutation sweep (first-line, last-line,
    single-byte mutations) against the fixed script in a fresh sandbox:
    exit 1 each time, mutation surviving on disk, hash-verified restore
    after each.
  - Ran 20 parallel default-invocation + 20 parallel `--write` invocations
    against a shared sandbox: no stray `sop-extract.md.XXXXXX` scratch
    files survive. Confirmed the `trap ... EXIT` cleanup also fires
    correctly on a normal (non-`SIGKILL`) failure path (a renamed pinned
    heading causing `extract_section` to fail): extract untouched, no
    stray scratch file left. Separately confirmed `SIGKILL` itself bypasses
    the `EXIT` trap (universal bash/OS behavior, not a regression from
    this fix, and consistent with how the truncation bug above was
    demonstrated) — a `kill -9` landing mid-`regenerate()` (before `mv` is
    even reached) can leave one stray scratch file on disk; this is an
    inherent limitation of `SIGKILL` semantics, not something this fix (or
    any trap-based cleanup) can address.
- **LOW-1 (fixed)**: swapped the `AGENTS_FILE` existence guard from `-f` to
  `-r` (`conformance/scripts/check-sop-extract-drift.sh` line ~87), so an
  unreadable-but-present source file (`chmod 000 AGENTS.md`, reproduced and
  confirmed) is now reported as "source file not found or not readable"
  instead of silently falling through to `awk`'s raw permission-denied
  error plus `extract_section`'s misleading "heading not found" fallback
  message. Trade-off accepted: `-r` no longer distinguishes "missing" from
  "present but a directory/unreadable", but the prior `-f` guard didn't
  either for the permission case, and this script's own real-awk-error
  printed immediately above the misdiagnosis already made the defect
  cosmetic — the fix is a clean-message improvement, not a new safety
  property.
- **LOW-2 (recorded, not changed)**: a duplicate `## Branch Protection and
  CI` heading further down `AGENTS.md` is silently ignored by
  `extract_section`'s "first match through the next `---`" rule — policy
  text placed under a second copy of a pinned heading would be invisible
  to both the extract and its drift check. This is an accepted property of
  the settled, mechanical extraction rule (first-occurrence, not
  last-occurrence or all-occurrences), not a defect this WP's scope covers;
  the rule itself is intentionally unchanged.
