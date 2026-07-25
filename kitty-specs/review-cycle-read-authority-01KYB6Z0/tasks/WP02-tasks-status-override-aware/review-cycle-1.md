---
affected_files: []
cycle_number: 1
mission_slug: review-cycle-read-authority-01KYB6Z0
reproduction_command:
reviewed_at: '2026-07-25T10:11:48Z'
reviewer_agent: unknown
verdict: rejected
wp_id: WP02
---

# WP02 review feedback — REJECT

## 1. [BLOCKING] Real regression: `_get_latest_review_cycle_verdict` now hard-blocks approval for real, already-on-disk review-cycle artifacts it used to pass through cleanly

`src/specify_cli/cli/commands/agent/tasks_parsing_validation.py:319-355`

`_get_latest_review_cycle_verdict` now delegates entirely to
`latest_review_artifact_verdict` → `ReviewCycleArtifact.from_file` → `from_dict`
(`src/specify_cli/review/artifacts.py:151-196`), which enforces
`verdict in REVIEW_ARTIFACT_VERDICTS == {"approved", "rejected"}` and raises
`ValueError` for anything else. The `except ValueError` branch at
`tasks_parsing_validation.py:342-343` collapses straight to `(None, artifact)`.

This is not the "unreachable, defensive-only" gap the docstring at
`tasks_parsing_validation.py:333-337` and the commit message for `f8faa1b0c`
claim. It is live today, against real data already checked into this repo:

```
kitty-specs/auth-tranche-2-5-cli-contract-consumption-01KQEJZK/tasks/WP05-integration-tests-and-dev-smoke/review-cycle-2.md
  frontmatter: verdict: approved_after_orchestrator_fix   (this IS in _VALID_VERDICTS)
```

Reproduced directly against the two function versions (before/after this WP,
same real file, same working tree):

```
HEAD~2  _get_latest_review_cycle_verdict(wp_dir) -> ("approved_after_orchestrator_fix", <path>)
HEAD    _get_latest_review_cycle_verdict(wp_dir) -> (None, <path>)
```

Consequence for `tasks_move_task.py:552` (WP03's file — zero diff, but its
*behavior* changed underneath it): `_guard_rejected_verdict`
(`tasks_transition_core.py:372-374`) treats `review_verdict is None` as
**"has no parseable review verdict... Repair the review artifact before
approving or marking done"** and refuses the transition. Before this WP, the
same WP received the raw string (neither `None` nor `"rejected"`) and the
guard let the transition proceed with no warning at all. So a WP whose latest
review artifact legitimately carries a `_VALID_VERDICTS`-recognized but
non-canonical verdict flips from **silently approvable** to **hard-refused,
requiring manual artifact repair** — a functional regression on a surface
this WP was told not to touch, delivered purely by retiring the shared
function's frontmatter parser.

A second, related failure mode: `kitty-specs/execution-context-unification-01KTPKST/tasks/WP08-retrospect-merge-reconciliation/review-cycle-1.md`
uses an older frontmatter schema (`work_package_id`/`review_cycle`/
`reviewed_commit` instead of `wp_id`/`cycle_number`/`reviewed_at`) — the old
raw-scalar parser still extracted `verdict: changes_requested` from it fine;
the new schema-validating path raises `ValueError` on the missing required
fields regardless of verdict, same `(None, path)` collapse. (This file
happens not to be the *latest* cycle for its WP, so it's not independently
guard-breaking today, but it shows the regression class is broader than one
enum mismatch — it is "any artifact that predates the current
`ReviewCycleArtifact` schema, in field names or verdict vocabulary.")

**Required change**: preserve the pre-existing pass-through-with-warning
behavior for a verdict that is in `_VALID_VERDICTS` but outside the canonical
`REVIEW_ARTIFACT_VERDICTS` schema (and, ideally, for the legacy-field-name
case too) — do not let the canonical schema's `ValueError` alone decide the
return value. This likely means keeping a raw-frontmatter fallback read in
the `except ValueError` branch (mirroring what the pre-WP02 implementation
did) before giving up and returning `(None, artifact)`, so the DoD's own
requirement — "preserve the existing `_VALID_VERDICTS` warning behaviour for
out-of-vocabulary verdicts" — is actually satisfied rather than documented as
impossible. If, after investigating, preserving it is genuinely impossible,
that needs to be escalated as a decision (data-model.md / research.md
amendment), not resolved unilaterally inside the WP with a docstring that
understates the blast radius.

Fix the docstring at `tasks_parsing_validation.py:333-337` once the behavior
itself is fixed — "unreachable... kept in case" is incorrect; it is reachable
against real on-disk data in this repository today.

## 2. [Test-suite gap tied to #1] `TestUnknownVerdictWarning`/`test_get_latest_review_cycle_verdict_schema_invalid_verdict_degrades`

`tests/specify_cli/cli/commands/agent/test_tasks.py` (edited class) and
`tests/specify_cli/cli/commands/agent/test_tasks_parsing_validation.py:319-329`

Both edited tests use a synthetic, never-real verdict string (`"super_approved"`)
and assert the new degrade-to-`None` behavior as correct. That's a reasonable
adaptation for a value that really is outside `_VALID_VERDICTS` — but neither
edited file adds a regression test using one of `_VALID_VERDICTS`'s own
legacy-but-recognized values (`approved_after_orchestrator_fix`,
`arbiter_override`) against a real, schema-conformant-except-for-verdict
artifact. That's exactly the case that regresses (see #1), and it would have
been caught by a fixture built the same way `_write_review_cycle` already
builds artifacts (bypassing `from_dict`'s validation), just with a
`_VALID_VERDICTS` member instead of a made-up string. Add that case once #1
is fixed, so the DoD's backward-compatibility guarantee has a real test
behind it.

## Adjudications (for the record — items 3-5 are not independently blocking, but must be read alongside #1)

**Adjudication 1 (edited pre-existing tests)**: The `test_tasks.py` and
`test_tasks_parsing_validation.py` edits are honest mechanical adaptations to
the stricter canonical schema for the case they cover — they don't weaken an
assertion out of laziness. But per #2, they stop short of testing the actual
backward-compatibility promise the DoD required, and in doing so they
normalize the regression in #1 as expected behavior. Fix alongside #1.

**Adjudication 2 (DoD item not satisfied as worded)**: The implementer's
structural claim (`REVIEW_ARTIFACT_VERDICTS ⊆ _VALID_VERDICTS` makes the
warning branch unreachable) is correct as far as it goes, but the framing —
"defensive, no real consequence, kept in case" — is wrong. It understates a
real, demonstrated regression (see #1). Documenting an impossibility is the
right instinct only when the impossibility claim is accurate; here it wasn't
verified against real data, and real data proves the consequence is worse
than a lost log line.

**Adjudication 3 (WP03 scope overlap)**: Confirmed — the effective-verdict
projection (`data-model.md`'s "Effective verdict" formula, research.md
Decision 5) now lives inside the shared `_get_latest_review_cycle_verdict`
(`tasks_parsing_validation.py:319-355`), not locally at
`tasks_move_task.py:550-556` as Decision 5 specifies. Because
`tasks_move_task.py:552` calls this function with `override=None`, it
inherits the fold via `latest_review_artifact_verdict`'s pre-existing
(FR-009/WP09) legacy-frontmatter-override fallback
(`ReviewCycleArtifact.has_complete_override`) even with zero diff in its own
file. This is real but narrow (only the legacy-frontmatter-override path is
covered; the event-sourced-override path stays off until WP03 wires
`override=`). Flag explicitly to whoever picks up WP03: part of the fix may
already be live at their call site, and Decision 5's prescribed local-mapping
approach may now be redundant if they reuse the same shared function.

**Adjudication 4 (tasks_move_task.py / tasks_transition_core.py diff)**:
Confirmed zero diff and zero commits — `git diff HEAD~2 --stat -- <files>`
and `git log HEAD~2..HEAD -- <files>` both empty.

**Adjudication 5 (pre-review gate skipped)**: Independently re-ran
`tests/specify_cli/agent_utils/ tests/specify_cli/cli/commands/agent/`:
**1548 passed, 2 xfailed, 0 failed** in 84s — matches the implementer's
reported numbers exactly, and matches the stated baseline + 17 new. The
load-sensitive coverage-ratchet test did not fail in this run. `ruff check`
clean on all changed files. `mypy` errors on changed files are confirmed
pre-existing at `HEAD~2` (identical 7 stub-import errors); the
`test_tasks.py:1146` `no-any-return` blames to a 2026-07-03 commit, unrelated
to this WP. None of this offsets #1 — the test suite is green because
nothing in it exercises the real legacy-artifact case that regresses.

## Confirmed OK, no action needed

- Trap 2 (fixture discipline): every override in
  `test_tasks_status_review_override.py` is driven through a real event log
  via `append_annotations_atomic_verified` + `InnerStateChanged`/
  `ReviewOverride` — no hand-built `state["review"]` shortcuts.
- NFR-002: `resolve_materialized_review` (O(1)) is the only per-WP lookup
  inside `_apply_review_status_flags`'s loop
  (`tasks_parsing_validation.py:424-428`). The spy patches
  `specify_cli.status.reduce` — confirmed to actually intercept (reverting
  `tasks_status_cmd.py` to its pre-fix form makes the spy test fail with
  the correct call count, and also flips
  `test_stale_verdict_honours_complete_event_sourced_override` and the
  parity-matrix `overridden_rejection` case red).
- Swallow-and-degrade posture at `tasks_status_cmd.py:283`: bare
  `except Exception: st.events = []` preserved; all T009 degrade cases pass.
- No false negative: unoverridden rejection still reported; all 4
  incomplete-override permutations still reported (parametrized T010 test).
- Red-first ordering confirmed in history: `877d41b15` (test, red) before
  `f8faa1b0c` (fix, green).
