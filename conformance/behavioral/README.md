# Behavioral Conformance Suite (M4)

Mission `doctrine-behavioral-suite-01KYW5XK`. This suite grades a real
model, over a real bring-your-own-model (BYOM) endpoint, against the
deployed system prompts spec-kitty's built-in agent profiles actually
produce, plus a handful of directive-attached behavioral rules layered onto
three of M3's static doctrine manifests. Everything here is graded by
`@garrison-hq/muster@1.2.2`'s `sop` adapter (`muster sop run <manifest>`),
consumed as an external, published, pinned CLI — nothing in this suite
patches or forks muster.

## What this suite is, and is not

This suite tests model+context, not harness — that is, **model + context
only**. There is no real tool loop, no
skill-routing machinery, and no Claude Code harness underneath any scenario
— every probe replays a scripted conversation directly against a chat
completions endpoint and grades the raw text reply. A profile's declared
`capabilities`/`collaboration.handoff-to`/`collaboration.canonical-verbs`
fields never reach the model under test at all (the projected Claude Code
system prompt, `conformance/behavioral/projected/<id>.md`, does not carry
them — see `conformance/behavioral/tools/render_profile.py` and
`ClaudeCodeProfileRenderer.render()`); they are supplied only to the
**judge**, as `promptTemplate` context, per muster's rubric doc's own
Integration Contract. A `passed: true` verdict here is evidence about a
model's behavior under a scripted, single-turn conversation — it is not a
harness-fidelity claim, and it should never be read as one. If model-only
results are later shown to diverge from real in-harness behavior, the
escape hatch is an A2A façade over `claude -p` in a separate repository —
not built in this mission.

## The muster pin: `@garrison-hq/muster@1.2.2`, not `@1.2.1`

Always pin `@garrison-hq/muster@1.2.2` exactly in every command below and
in any CI workflow that invokes this suite. An earlier draft of this
mission's spec and plan pinned `@1.2.1`. That pin is stale and actively
harmful: at `1.2.1`, `runComplianceProbeEntry`
(`src/adapters/openclaw-sop/runner.ts`) passed the manifest's rule-level
`passThreshold` — intended for the *outer* k-run aggregation — into
`gradeJudgeCompliance`'s *inner* per-run order-swap vote, where the
achievable maximum is `1`. Every judge-graded rule with a resolved
threshold `>= 2` (which is every `pass-k`/`k-of-n` row this suite ships,
per `FR-006`) was therefore permanently unpassable, for any model, however
compliant. `garrison-hq/muster` commit `db80a4295` ("fix(openclaw-sop):
stop applying the k-run passThreshold to a single run's judge vote",
`garrison-hq/muster#89`, closing `garrison-hq/muster#88`) fixes it and is
included in the published `v1.2.2` release (confirmed via `git merge-base
--is-ancestor db80a4295 v1.2.2`, true; against `v1.2.1`, false). Confirm
`npx @garrison-hq/muster@1.2.2 --version` resolves to `1.2.2` before
trusting any result from this suite — a stale lockfile or a caret range
can silently resolve `1.2.1` instead. **Never "fix" a permanently-failing
pass-k row by weakening its `passThreshold` to `1`** — that masks the
defect above rather than avoiding it; pin the corrected version instead.

## Endpoint matrix

This suite is BYOM: it never ships or depends on a hosted model. Point
`MUSTER_ENDPOINT` at any OpenAI-compatible chat completions endpoint.

| Endpoint kind | Example `MUSTER_ENDPOINT` | Notes |
|---|---|---|
| Local Ollama | `http://localhost:11434/v1` | No API key required in practice, but `MUSTER_API_KEY` must still be set to a dummy non-empty value — an empty/unset key falls back to reading `OPENAI_API_KEY` from the environment, which can silently authenticate against a *different*, unintended endpoint. |
| DGX (self-hosted, OpenAI-compatible) | `http://<dgx-host>:<port>/v1` | Same API-key caveat as Ollama. |
| NVIDIA Inference Microservice (NIM) | `https://<nim-host>/v1` | Real API key required; NIM's own OpenAI-compatible chat completions surface. |
| Hosted (OpenAI-compatible) | `https://api.openai.com/v1` | Real API key required; billed per the provider's own pricing (see Cost below). |

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `MUSTER_ENDPOINT` | Yes, for any behavioral run | none (absent → `SOP_NOOP_CLIENT`, see Exit codes below) | Base URL of the OpenAI-compatible chat completions endpoint. |
| `MUSTER_MODEL` | No | `gpt-4o-mini` | Model name passed to the endpoint. Always set it explicitly for a reproducible run — the default is muster's own, not this suite's. |
| `MUSTER_API_KEY` | No | falls back to `OPENAI_API_KEY` | Bearer credential for the endpoint. Set it explicitly even against a local/no-auth endpoint (a dummy value is fine) so a contributor's personal `OPENAI_API_KEY` never silently authenticates a request aimed at a different endpoint. |

**Credentials are always inline on the command, never in a file, never in
argv position, never logged**:

```bash
MUSTER_API_KEY="$(command grep '^OPENAI_TOKEN=' ~/dev/n8n-app-team/.env | cut -d= -f2-)" \
MUSTER_ENDPOINT=https://api.openai.com/v1 MUSTER_MODEL=gpt-4o-mini \
npx @garrison-hq/muster@1.2.2 sop run conformance/behavioral/profiles/architect-alphonso.yaml --json
```

Never create a `.env` file for these credentials in this repository —
project convention scans the whole tree, including gitignored files, for
accidentally committed secrets.

## Cost

Each profile-axis manifest ships 4 judge-graded rules at `k: 5`. A judge
call is always an order-swap pair (2 calls per run). One full manifest run
therefore issues, per rule: 5 generation calls (the scenario turn against
the model under test) plus up to 10 judge calls (2 per run x 5 runs) — a
worst case of roughly 15 completions per rule, 60 per profile manifest,
300 across all 5 profile manifests. Against a small hosted model
(`gpt-4o-mini`-class pricing), a full 5-profile run costs a few cents to
low tens of cents in API spend and typically completes in well under ten
minutes; a self-hosted Ollama/DGX/NIM endpoint has no per-call API cost at
all, only local compute time. `MUSTER_MODEL` and endpoint choice are the
two levers that move this cost — pick a small, fast model for iteration
and reserve a larger "competent model" run for the mission's post-merge
Acceptance Gate.

## Trivial-refusal guard

Before any judge call runs at all, muster's judge (`judge.ts:210-230`)
checks whether the transcript under test is an **all-refusal** transcript —
every assistant turn declines to engage, with no substantive content at
all — and fails the case immediately if so, without ever invoking the
judge model. This exists so a model that simply stonewalls every turn
cannot pass by accident (refusal is not evidence of correct behavior for
any of the four axes this suite grades — see muster's rubric doc §3.3's
explicit "refusal is not evidence of correct handoff behavior" note).

This has a direct consequence for scenario authoring: **a scenario prompt
must be one a compliant model would actually engage with**, not one that
reads as a demand to be refused outright. A scenario whose only compliant
response is a flat, contentless "I can't do that" will trigger this guard
and fail every run — including runs from a genuinely well-behaved model —
which looks identical, from the report alone, to a model that failed the
rubric on the merits. When triaging a `passed: false` case, check the
run's `grades` for a `TRIVIAL_REFUSAL` measurement before concluding the
model failed the rubric itself: this was observed live during this WP's
own manifest verification (a `CAPABILITY-CONTAINMENT` scenario prompting a
flat refusal from `gpt-4o-mini` rather than the intended graceful
redirect-while-staying-in-domain reply), and is expected scenario-tuning
work for the mission's post-merge Acceptance Gate, not evidence of a
manifest defect by itself.

## Exit codes

`sop` (`doSopRun`, `src/cli/index.ts`) returns `report.passed ? 0 : 1`.
There is **no exit-2 endpoint-fatal path** — exit `2` is reserved
exclusively for an unreadable manifest file, thrown before any client is
even built. When `MUSTER_ENDPOINT` is unset, `buildSopClient()` returns
`undefined` and `doSopRun` falls back to `SOP_NOOP_CLIENT`, whose `chat()`
unconditionally throws; that throw is contained per-run (an errored run
counts as a failed run, never a skip, per the charter's own aggregation
rule), so every run for every case errors and `report.passed` is `false` —
exit `1`, never exit `0` and never exit `2`, for a dead or unset endpoint.

| Exit code | Meaning |
|---|---|
| `0` | All static lint checks passed and all probe cases passed. |
| `1` | At least one lint error, or at least one probe case failed (includes: a genuinely non-compliant model; a dead/unset `MUSTER_ENDPOINT`; a weak model). |
| `2` | The manifest file itself could not be read or was structurally invalid — never an endpoint condition. |

## `sopFileHash` / content-hash citation

Every manifest under `conformance/behavioral/profiles/*.yaml` carries a
top-level `sopFileHash: sha256:<hex>` field alongside its `sopFile:` path,
citing the **source** `*.agent.yaml` file's content hash (not the
projected `.md` body's hash) — this is the mechanism chosen for C-003's
"cite the projected file path plus its content hash" requirement.
`conformance/behavioral/tools/render_profile.py` computes and prints this
same hash to stderr (`<sha256:hex>  <source_path>`) on every invocation; a
companion `conformance/behavioral/projected/<id>.md.sha256` file, captured
from that stderr output when each projected body was generated, is the
committed record a manifest author copies the hash from — chosen over
re-running the generator at manifest-authoring time so the citation is a
static, greppable fact rather than something recomputed on demand.

## Regenerating the projected bodies

```bash
python3 conformance/behavioral/tools/render_profile.py \
  src/doctrine/agent_profiles/built-in/<id>.agent.yaml \
  > conformance/behavioral/projected/<id>.md \
  2> conformance/behavioral/projected/<id>.md.sha256
```

`git diff --exit-code conformance/behavioral/projected/` after
regenerating all 5 files from the committed source profiles must return
clean (exit `0`) on an unmodified checkout — this is the drift check
FR-009 requires and this suite's CI cadence workflow runs on every
invocation.

## Running the suite locally

```bash
MUSTER_API_KEY="<key>" MUSTER_ENDPOINT="<endpoint>" MUSTER_MODEL="<model>" \
npx @garrison-hq/muster@1.2.2 sop run conformance/behavioral/profiles/architect-alphonso.yaml --json
```

Repeat per profile (`architect-alphonso`, `reviewer-renata`,
`implementer-ivan`, `planner-priti`, `debugger-debbie`), or glob across
`conformance/behavioral/profiles/*.yaml` — never a hand-maintained literal
file list — in a CI cadence job (`.github/workflows/behavioral.yml`, owned
by this mission's lane-b).
