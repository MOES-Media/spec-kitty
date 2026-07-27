# Contract: `conformance/scripts/check-doctrine-drift-gate.sh` (FR-004/FR-005)

**Mission**: `doctrine-rule-manifests-01KYH7AM` | **Date**: 2026-07-27

CLI contract for the jq-based CI gate, so the workflow step (new job
`sop-doctrine-conformance`) and the script's implementation can be built
and reviewed independently. See research.md §9–10 for the design rationale.

## Invocation

```sh
bash conformance/scripts/check-doctrine-drift-gate.sh
```

- **Working directory**: repository root (same convention as M1's
  `check-manifest-completeness.mjs`).
- **Requires**: `jq` (pre-installed on `ubuntu-latest`) and network access to
  resolve `npx @garrison-hq/muster@1.1.0` (cache-warmed implicitly by the
  runner's normal npm registry access — no secret required, C-002).
- **Arguments**: none. Manifest paths are discovered by globbing
  `conformance/doctrine/*.yaml` (the 13 shipped manifests) and a single
  hardcoded path to the control manifest,
  `conformance/doctrine/control/045-drifted.yaml`.

## Behavior

**Phase 1 — FR-004, the main gate** (must find nothing, for every shipped manifest):

```sh
for manifest in conformance/doctrine/*.yaml; do
  out=$(npx --yes @garrison-hq/muster@1.1.0 sop run "$manifest" --json)
  # muster's own exit code: 0 = passed, 1 = lint error/probe failure, 2 = execution error
  bad=$(printf '%s' "$out" | jq '[.lintFindings[] | select(.kind=="RULE_DRIFT" or .kind=="MISSING_SOURCE" or .kind=="MANIFEST_ERROR")]')
  count=$(printf '%s' "$bad" | jq 'length')
  # count MUST be 0 for every manifest
done
```

**Phase 2 — FR-005, the inverted control assertion** (must find at least one `RULE_DRIFT`):

```sh
out=$(npx --yes @garrison-hq/muster@1.1.0 sop run conformance/doctrine/control/045-drifted.yaml --json)
count=$(printf '%s' "$out" | jq '[.lintFindings[] | select(.kind=="RULE_DRIFT")] | length')
# count MUST be >= 1
```

The control's `ruleText` is `"Agents must never run \`git push origin main\`, \`git push --force\`, or \`gh pr"`
(one word changed from the real 045 directive's "must not run" →
"must never run"). Verified absent from the real file during planning:

```sh
$ grep -F -c "Agents must never run \`git push origin main\`, \`git push --force\`, or \`gh pr" \
    src/doctrine/directives/built-in/045-prs-only-and-read-intent.directive.yaml
0
```

This same `grep -F -c ... = 0` check should be re-run whenever the control's
`ruleText` is touched — a future edit that "softens" the mutation toward a
shorter, more generic string could silently start matching real content
again (count > 0), which would mean the control stops discriminating without
anyone noticing at review time by eye (the absence-lesson risk this mission
is explicitly instructed not to repeat).

## Exit codes

| Code | Meaning |
|---|---|
| `0` | All 13 shipped manifests are clean (zero `RULE_DRIFT`/`MISSING_SOURCE`/`MANIFEST_ERROR`) AND the control manifest produced at least one `RULE_DRIFT`. |
| `1` | At least one shipped manifest has a disallowed finding, OR the control manifest failed to discriminate (zero `RULE_DRIFT` findings) — the script names which manifest(s) and which finding(s) failed, never a bare count. |
| (never `2` from this script) | Reserved for "muster itself errored" (exit `2` from an underlying `sop run` invocation) — the script distinguishes this in its message text ("muster execution error" vs. "gate finding mismatch") even though both currently propagate as this script's own exit `1`, mirroring M1's `check-manifest-completeness-cli-contract.md`'s own non-goal note about not reusing muster's `2`. |

## Output shape

- **stdout, success**: one line per manifest confirming it is clean, then a
  confirmation line that the control discriminated, e.g.:
  ```
  checking: conformance/doctrine/001-architectural-integrity-standard.yaml — clean
  ...
  checking: conformance/doctrine/045-prs-only-and-read-intent.yaml — clean
  control OK: RULE_DRIFT present (1 finding) as expected
  ```
- **stdout/stderr, failure**: names the offending manifest and dumps the
  specific finding objects (not a bare count) via `jq .` on the filtered
  array, e.g.:
  ```
  GATE FAIL: conformance/doctrine/045-prs-only-and-read-intent.yaml — 1 disallowed finding(s):
  [ { "kind": "RULE_DRIFT", "location": "045-r1", ... } ]
  ```
  or, for a discriminated-control failure:
  ```
  GATE FAIL: control manifest did not produce a RULE_DRIFT finding — discrimination control is dead
  ```

## CI wiring

New job `sop-doctrine-conformance` in `.github/workflows/conformance.yml`
(research.md §9), step 2 of 3:

```yaml
- name: Run doctrine rule-manifest drift gate (FR-004/FR-005)
  run: bash conformance/scripts/check-doctrine-drift-gate.sh
```

## Non-goals

- Does not check `UNDEFINED_PRECEDENCE` or `TOOL_DRIFT` findings — both are
  reported-not-gating per FR-004, and `TOOL_DRIFT` is additionally
  unreachable via the CLI path at all (research.md §2) since `sop run`
  exposes no `--envTools` flag.
- Does not validate manifest YAML shape independently of what
  `loadAndValidateManifest` already validates — a `MANIFEST_ERROR` finding
  from a schema violation is caught by Phase 1's filter, not re-validated
  by this script.
- Does not check rule *count* completeness (a manifest missing an entire
  rule entry produces no finding at all — see
  `contracts/doctrine-manifest-completeness-contract.md` for that guard).
