# Quickstart: Doctrine Rule Manifests — Local & CI Verification

**Mission**: `doctrine-rule-manifests-01KYH7AM` | **Date**: 2026-07-27

All commands run from the spec-kitty fork's repository root. Requires
Node (for `npx`/the completeness script) and `jq` (pre-installed on
`ubuntu-latest`, and on most developer machines). Network access is needed
only to resolve the pinned `@garrison-hq/muster@1.1.0` package via `npx` —
no environment variable, endpoint, or secret is required (every manifest's
`probeIds: []` means the static-lint path never constructs a live client —
research.md §2).

This file doubles as the mission's mandatory real-CLI verification
procedure (binding constraint 6 / plan.md's Verification Strategy). Every
step must be run for real during implementation, with the actual command
and actual `--json`/exit-code output recorded verbatim in the work log — a
prose summary of expected behavior is explicitly insufficient (spec
Dependencies & Assumptions, "Real-CLI verification requirement").

---

## 1. AC-1 / AC-2 — every shipped manifest, clean tree, zero disallowed findings

```sh
for manifest in conformance/doctrine/*.yaml; do
  echo "=== $manifest ==="
  npx --yes @garrison-hq/muster@1.1.0 sop run "$manifest" --json | tee /tmp/out.json
  echo "exit code: $?"     # MUST print 0
  jq '[.lintFindings[] | select(.kind=="RULE_DRIFT" or .kind=="MISSING_SOURCE" or .kind=="MANIFEST_ERROR")]' /tmp/out.json
  # MUST print []
done
```

Record, per manifest, the exit code and the `jq` filter's output verbatim —
13 pairs of (exit code, filter result), not a single "all passed" summary.

---

## 2. AC-2 — flip one word locally, confirm `RULE_DRIFT` fires, then restore

```sh
manifest=conformance/doctrine/034-test-first-development.yaml
cp "$manifest" "$manifest.bak"

# Change one word in 034-r1's ruleText, e.g. "must not be written" -> "must never be written"
sed -i 's/must not be written ahead/must never be written ahead/' "$manifest"

npx --yes @garrison-hq/muster@1.1.0 sop run "$manifest" --json | jq '.lintFindings[] | select(.kind=="RULE_DRIFT")'
echo "exit code: $?"     # exit code is still likely 0 (RULE_DRIFT is a warning) — this IS the point of FR-004's jq gate

mv "$manifest.bak" "$manifest"
git diff --exit-code "$manifest"
```

**This is the exact demonstration FR-004's own rationale depends on**:
`RULE_DRIFT` is a `severity: "warning"` finding, so muster's own exit code
stays `0` even with the word flipped — the jq gate, not the bare exit code,
is what must be asserted in CI. Record both the flipped-run's exit code
(still `0`) and its `jq` filter output (non-empty) verbatim, so the
distinction between "muster's exit code" and "the jq gate's verdict" is
demonstrated, not merely asserted in prose.

---

## 3. AC-4 — the fragment convention, real-execution proof for 042/044/045

```sh
for d in 042 044 045; do
  manifest=$(ls conformance/doctrine/${d}-*.yaml)
  echo "=== $manifest ==="
  npx --yes @garrison-hq/muster@1.1.0 sop run "$manifest" --json > /tmp/out-$d.json
  echo "exit code: $?"    # MUST print 0
  jq '[.lintFindings[] | select(.kind=="RULE_DRIFT")]' /tmp/out-$d.json
  # MUST print [] for all three — proving the fragment convention actually
  # matches on a real run, not merely "should match" by inspection
done
```

Record all three manifests' exact command, exit code, and `--json`
`lintFindings` array verbatim in the work log — this is spec.md's own
Acceptance Scenario 3, added post-spec-gate specifically because "a
convention never observed matching on a real run is unverified."

---

## 4. Fragment / control uniqueness — mechanical proof (binding constraint 1)

```sh
# Example: 045-r1's fragment, must return exactly 1
grep -F -c "Agents must not run \`git push origin main\`, \`git push --force\`, or \`gh pr" \
  src/doctrine/directives/built-in/045-prs-only-and-read-intent.directive.yaml
# MUST print 1

# The control's deliberately-drifted text, must return exactly 0
grep -F -c "Agents must never run \`git push origin main\`, \`git push --force\`, or \`gh pr" \
  src/doctrine/directives/built-in/045-prs-only-and-read-intent.directive.yaml
# MUST print 0
```

Run this for all 10 fragment-cited rules (`contracts/
rule-classification-and-citation.md` lists every fragment) before
committing each manifest — every fragment must print `1`; the control's
mutated text must print `0`.

---

## 5. AC-3 — the control manifest discriminates (inverted polarity)

```sh
npx --yes @garrison-hq/muster@1.1.0 sop run conformance/doctrine/control/045-drifted.yaml --json \
  | jq '[.lintFindings[] | select(.kind=="RULE_DRIFT")] | length'
# MUST print a number >= 1 (NOT zero — this is the one gate in this mission
# where "found nothing" is the failure, not the success)
```

---

## 6. The absence guard, both ways (author-added, mirrors M1's FR-007 pattern)

```sh
# Baseline: the true tree — 13 manifests, 45 rules, 1 control.
node conformance/scripts/check-doctrine-manifest-completeness.mjs
echo "baseline exit code: $?"   # MUST print 0

# Induce a mismatch: comment out (or delete) one rule entry from a real manifest.
cp conformance/doctrine/045-prs-only-and-read-intent.yaml /tmp/045.bak
# ... remove the 045-r4 entry's 8 lines by hand ...
node conformance/scripts/check-doctrine-manifest-completeness.mjs
echo "mismatch exit code: $?"   # MUST print non-zero (1) and name
                                  # "045-prs-only-and-read-intent" with
                                  # "expected 4, found 3"

# Restore:
cp /tmp/045.bak conformance/doctrine/045-prs-only-and-read-intent.yaml
node conformance/scripts/check-doctrine-manifest-completeness.mjs
echo "restored exit code: $?"   # MUST print 0 again
```

Also verify the manifest-deletion and control-deletion cases (research.md
§8's absence table):

```sh
# Manifest file entirely missing:
mv conformance/doctrine/029-agent-commit-signing-policy.yaml /tmp/
npx --yes @garrison-hq/muster@1.1.0 sop run conformance/doctrine/029-agent-commit-signing-policy.yaml --json
echo "exit code: $?"    # MUST print 1 (MANIFEST_ERROR, muster's own error path — no jq needed)
mv /tmp/029-agent-commit-signing-policy.yaml conformance/doctrine/

# Control manifest deleted:
mv conformance/doctrine/control/045-drifted.yaml /tmp/
node conformance/scripts/check-doctrine-manifest-completeness.mjs
echo "exit code: $?"    # MUST print 1, naming the missing control manifest
mv /tmp/045-drifted.yaml conformance/doctrine/control/
```

**This step must be run for real** during implementation, with every
recorded exit code and message excerpt captured in the work log — this
directly discharges the mission brief's "observe those behaviours rather
than assume them" instruction.

---

## 7. Full CI gate, locally (what a contributor runs before opening a PR)

```sh
bash conformance/scripts/check-doctrine-drift-gate.sh \
  && node conformance/scripts/check-doctrine-manifest-completeness.mjs \
  && echo "doctrine conformance: both checks green"
```

This is the exact sequence `conformance/doctrine/README.md`'s pre-PR
section documents, and the exact sequence the new `sop-doctrine-conformance`
CI job runs (`.github/workflows/conformance.yml`).

---

## 8. CI workflow — real run

This step cannot be simulated locally; it requires a real GitHub Actions
run on the mission's own PR:

1. Open the mission's PR against `MOES-Media/spec-kitty` on branch
   `kitty/mission-doctrine-rule-manifests`.
2. Confirm the renamed workflow (`Static Conformance`) runs **both** jobs
   green: the pre-existing `Skills static conformance (muster)` job
   (unaffected by this mission) and the new `SOP doctrine conformance
   (muster)` job (all three of its steps green).
3. Record that run's `run_id` and wall-clock minutes in `conformance/
   README.md`'s timing table, alongside M1's existing entry — measured,
   never asserted as a ceiling (same house policy M1 already established).
4. If the PR is opened from a fork, confirm the new job still completes
   green with no secret-related failure (C-002) — identical no-secret
   posture to the sibling job.
