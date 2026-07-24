---
title: 'Issue #2831 — Implement gate `charter_source missing`: reproduction and behavioral contracts'
description: "Verified reproduction of the P0 implement-gate false-fail on legacy-bundle projects, expressed as BDD behavioral contracts: the emitted remediation cannot clear the check it is attached to, and charter presence resolves to two disagreeing sources."
doc_status: draft
updated: '2026-07-25'
related:
- docs/development/manage-issue-tracker.md
- docs/adr/3.x/2026-07-17-1-red-main-is-honest-ci-is-release-authority.md
---
# Issue #2831 — Implement gate `charter_source missing`: reproduction and behavioral contracts

**Author:** Architect Alphonso (ad-hoc profile session, Op `01KYB2BY08QFSGY8C8X0B1TNTB`)
**Date:** 2026-07-25
**Issue:** [Priivacy-ai/spec-kitty#2831](https://github.com/Priivacy-ai/spec-kitty/issues/2831) — *Implement gate false-fails with 'charter_source missing' despite charter sync passing* (`priority:P0`)
**Verified on:** `main@721165a22` (originally hit on v3.2.5)
**Posted to the issue as:** [comment 5074868902](https://github.com/Priivacy-ai/spec-kitty/issues/2831#issuecomment-5074868902)

---

## Precondition (the trigger)

A project synthesized before #2773 (landed 2026-07-18) — its charter dir holds the
legacy four-file bundle (`directives.yaml`, `governance.yaml`, `metadata.yaml`,
`references.yaml`) and **no `charter.yaml`**.

## Evidence

| # | Call | Observed | Authority |
|---|---|---|---|
| E1 | `run_charter_preflight(root)` | `passed=false`, `blocked_reason="charter_source missing; run \`spec-kitty charter sync\`"` | `charter_runtime/preflight/runner.py` |
| E2 | `_compute_charter_source(root)` | `state=missing`, `remediation="spec-kitty charter sync"` | `charter_runtime/freshness/computer.py` — keys **solely** off `charter.yaml` |
| E3 | `spec-kitty charter sync --json` | `{"result":"noop","success":false,"stale_before":true,"files_written":[]}` — `charter.yaml` still absent | `cli/commands/charter/sync.py` |
| E4 | `spec-kitty charter context --action implement` | `exit=0`, `Source: .kittify/charter/charter.md` | reads **charter.md** |
| E5 | `ConsolidateCharterBundleMigration().apply(root)` | `success=True` → gate flips `passed=True`, `charter_source=fresh` | `m_unify_charter_activation_finalize.py` (`consolidate_charter_bundle_fold`, `target_version=3.2.6`) |

E3 is not a conditional miss — `sync.py` states it outright:

> Since the IC-04 triad retirement, `charter.sync.sync()` is a **pure staleness reporter** —
> `synced` is always `False` and `files_written` always empty.

## Behavioral contracts

Expressed per `tactic:development-bdd` (observable outcomes at public interfaces).

### BC-1 — legacy bundle blocks the implement gate ✅ holds today

> **Given** a project with the legacy four-file bundle and no `charter.yaml`
> **When** the charter preflight runs
> **Then** it blocks with `charter_source: missing`

Correct behaviour; keep as a guard-rail. No fresh-project exemption applies —
`_is_optional_missing_charter_fresh_project` requires all three layers `missing`, and
`synthesized_drg` here is `built_in_only`.

### BC-2 — an emitted remediation must be able to clear the check that emitted it ❌ VIOLATED

> **Given** a preflight check reporting a non-passing state with remediation `R`
> **When** the operator executes `R` and re-runs the preflight
> **Then** that check's state must change

**Observed:** `R = "spec-kitty charter sync"` is structurally incapable of writing
`charter.yaml` (E3). Re-running yields the identical `blocked_reason` forever.

This is the P0: not a slow fix, an **unexitable loop**. Correct remediation is
`spec-kitty upgrade`.

### BC-3 — "does the charter exist" resolves to exactly one canonical source ❌ VIOLATED

> **Given** a project whose charter state is being interrogated
> **When** any surface reports charter presence
> **Then** all surfaces agree

**Observed:** the implement gate reads `charter.yaml` (E2); `charter context` and
`charter sync` read `charter.md` (E4). Both internally consistent, mutually
contradictory — which is exactly why `charter sync`, `charter context --action implement`,
and `check-prerequisites` all report healthy while `implement` false-fails.

### BC-4 — the consolidation migration clears the block ✅ holds today

> **Given** the same legacy-bundle project
> **When** `consolidate_charter_bundle_fold` applies
> **Then** `charter.yaml` exists, the four legacy files are gone, `config.yaml` gains
> `charter: .kittify/charter/charter.yaml`, and the gate passes

Regression anchor for any fix.

## Directive violations

- **BC-3 ⇒ `DIRECTIVE_044` (Canonical Sources and Unification).** Two parallel surfaces
  answer one question. Consolidation onto one resolver is the compliant fix; a parity
  patch to the non-canonical surface is not.
- **BC-2 ⇒ `DIRECTIVE_043` (Close Defect Classes by Construction).** A remediation string
  is a discipline reminder; nothing structurally binds it to being *effective*. Per the
  directive the compliant response is a gate, not a corrected string.

## Proposed structural mechanism (BC-2, non-vacuous per DIRECTIVE_043)

A remediation-effectiveness gate over the preflight check registry: for every check
capable of emitting a non-`None` remediation, a fixture drives it into that state,
executes the remediation, and asserts the state changed. Concrete floor = the current
count of remediation-emitting checks, so it cannot pass vacuously if the registry
empties. This closes the whole class; correcting only this one string leaves the next
one free to drift.

## Reproduction

Rebuilds the fixture from any project carrying the legacy bundle. Substitute your own
legacy-bundle source for `$SRC`.

```bash
PROJ=$(mktemp -d)/legacy-bundle-proj
mkdir -p "$PROJ/.kittify/charter"

# Populate with a legacy four-file bundle + charter.md, and a config.yaml
# that carries NO `charter:` key.
cp "$SRC"/.kittify/charter/{charter.md,directives.yaml,governance.yaml,metadata.yaml,references.yaml,synthesis-manifest.yaml} \
   "$PROJ/.kittify/charter/"
cp "$SRC"/.kittify/config.yaml "$PROJ/.kittify/"
git -C "$PROJ" init -q . && git -C "$PROJ" add -A && git -C "$PROJ" commit -qm "legacy bundle state"

# E1/E2 — the gate blocks
python - <<'PY'
import json, os
from pathlib import Path
from specify_cli.charter_runtime.preflight.runner import run_charter_preflight
r = run_charter_preflight(Path(os.environ["PROJ"]))
print(json.dumps(r.to_dict(), indent=2))
PY

# E3 — the prescribed remediation is a no-op
(cd "$PROJ" && spec-kitty charter sync --json)
ls "$PROJ/.kittify/charter/charter.yaml"   # still absent

# E4 — the masking: a different surface reports healthy
(cd "$PROJ" && spec-kitty charter context --action implement)

# E5 — what actually fixes it
python - <<'PY'
import os
from pathlib import Path
from specify_cli.upgrade.migrations.m_unify_charter_activation_finalize import (
    ConsolidateCharterBundleMigration,
)
print(ConsolidateCharterBundleMigration().apply(Path(os.environ["PROJ"])).success)
PY
```

## Provenance

Fix confirmed downstream at `spec-kitty-mission-control@9c87617f`
("chore(charter): migrate legacy bundle") — same shape as E5: +278 `charter.yaml`,
four legacy files deleted, one `config.yaml` line.

## Out of scope / unverified

On a **minimal** fixture (bare `.kittify/`, no agent command dirs) `spec-kitty upgrade --force`
aborted at `✗ Cannot apply 0.10.1_populate_slash_commands: No mission command templates found`
before reaching `consolidate_charter_bundle_fold`. Almost certainly a fixture artifact — the
real downstream project upgraded cleanly. Recorded only in case an early-migration hard-abort
can strand a real project; **not** claimed as part of this defect.

## See also

- [Investigations home](index.md)
