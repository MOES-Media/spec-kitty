# Contract: behavioral gate evidence artifact

Status: normative for mission `doctrine-behavioral-suite-01KYW5XK`.

This describes the artifact the mission Acceptance Gate reads, the producer that
writes it, and the exact conditions under which the producer refuses to write.
It exists so a gate reviewer can check the artifact without reading the
generator, and so a later change to either side is a visible contract change
rather than a silent drift.

## Producer

`conformance/behavioral/scripts/build-evidence-artifact.sh`

```
build-evidence-artifact.sh --main <file> --control <file> --mission <mid8> \
                           --out-dir <dir> [--require-axes <n>]
```

The script must be mode `100755` in the git index, not merely executable on
disk. The workflow invokes it by path, so a `100644` blob is `exit 126`,
`Permission denied`, on the first CI run. Testing it as `bash script.sh` will
not reproduce that. The test suite asserts the index mode for this reason.

`--require-axes` is the guard against a silently partial run. With
`--require-axes 4`, a profile that reports three axes is a hard failure rather
than an artifact that merely looks thin.

## Output path

`conformance/behavioral/evidence/<ISO-date>-<mid8>.json`

The date comes from the run's own `ranAt`, never from the wall clock at build
time. Deriving it from the clock is a mutation the test suite rejects, because
it lets a rebuild silently rename an artifact that describes an older run.

## Shape

```jsonc
{
  "model": "gpt-4o-mini",           // must agree across main and control
  "endpointHost": "api.openai.com", // must agree across main and control
  "musterVersion": "1.2.2",
  "ranAt": "2026-08-02T02:59:45Z",  // ISO 8601, drives the filename

  "perProfile": {
    "<profile-id>": {
      "<axis>": { "passCount": 5, "totalRuns": 5, "runsErrored": 0 }
    }
  },

  "controlManifest": {
    "judgeControl":      { "passed": false, "passCount": 0, "totalRuns": 3, "runsErrored": 0 },
    "behavioralControl": { "passed": false, "passCount": 0, "totalRuns": 3, "runsErrored": 0 }
  },

  "doctrineManifests": [
    { "manifest": "...", "passed": true, "runsErrored": 0, "perCase": [ ... ] }
  ]
}
```

Every field is derived. Nothing is synthesised:

| field | derivation |
|---|---|
| `perProfile.<id>` | basename of the case's own `manifest` |
| `perProfile.<id>.<axis>` | `ruleId` minus its `-<profile-id>` suffix, KEBAB-UPPER to camelCase |
| `passCount` / `totalRuns` / `runsErrored` | copied from `perCase` |
| `controlManifest`, `model`, `endpointHost`, `ranAt` | copied from the source reports |

`AVOIDANCE-BOUNDARY-architect-alphonso` becomes
`perProfile["architect-alphonso"].avoidanceBoundary`.

### `doctrineManifests` is an addition, not a fabrication

The main suite also runs three FR-005 doctrine manifests, which are not
profiles and have no place in `perProfile`. They are real results from the same
run, so discarding them would make the merge lossy in the direction that hides
information from a reviewer. They are carried in a separate key. The gate's
required shape is unaffected.

## Exit codes

| code | meaning |
|---|---|
| 0 | artifact written |
| 1 | usage error: missing `--main` / `--control` / `--mission`, or a path that does not exist |
| 2 | `jq` not on PATH |
| 3 | an input is not exactly one valid JSON document: zero-byte, whitespace-only, malformed, or several concatenated documents |
| 4 | shape violation: zero profile cases, a `ruleId` with no profile suffix, two rule IDs collapsing to one axis key, a duplicate profile id, a `--require-axes` shortfall, a missing required key, or a non-ISO `ranAt` |
| 5 | provenance mismatch: main and control disagree on `model` or `endpointHost`, so they are not one run |

Exit 3 is deliberately not a `jq empty` check. `jq empty` accepts whitespace-only
input and accepts several concatenated documents, both of which would let a
truncated or doubled report through as valid. The check slurps and counts
instead, and requires exactly one document.

Exit 5 exists because the artifact asserts a single credentialed run. Merging a
main suite from one endpoint with a control from another would produce a
document that reads as one run and is not.

## Reading the artifact at the gate

`runsErrored: 0` does not by itself prove the endpoint was reached. A client
that no-ops also reports zero. The load-bearing proof is in the raw reports:
each case's `runs[]` must hold transcripts that are not byte-identical to one
another, which a cached or stubbed reply cannot produce.

When checking that, note that `runs[].transcript` is an **object** in muster
1.2.2, not the string the gate prose describes. `.transcript | length` returns
the key count, which is `5` for every run, and reads as five identical short
strings. Compare on `transcript | tojson`.

Also expect `transcript.model` to read `"mock"` and `transcript.baseUrl` to read
`"mock://test"` on genuinely credentialed runs. That is garrison-hq/muster#90,
a provenance-stamping defect in the `openclaw-sop` adapter. The transcript
entries themselves are real. Do not read those two fields as evidence the run
was mocked, and do not edit the reports to correct them.

## Tests

| suite | cases |
|---|---|
| `build-evidence-artifact.test.sh` | 40 |
| `check-runs-errored.test.sh` | 10 |
| `control-discrimination-guard.test.sh` | 9 |

Each suite carries recorded rejection runs, not only success runs. A check with
no recorded rejection is treated as unverified here, because eighteen checks in
this programme reported green while verifying nothing, and every one was caught
by running it against input it should reject rather than by reading it.

`control-discrimination-guard.test.sh` extracts the assertion's own bytes out of
the workflow YAML between markers, so the test cannot drift away from the text
that actually ships.
