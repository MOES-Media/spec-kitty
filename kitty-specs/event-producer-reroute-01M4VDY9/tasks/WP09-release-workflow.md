---
work_package_id: WP09
title: 'Release workflow: build-producer, platform wheels, smoke-wheels, attestation (approval gated on the events release)'
dependencies:
- WP08
requirement_refs:
- FR-040
- FR-041
- FR-042
- FR-043
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T059
- T060
- T061
- T062
- T063
history: []
agent_profile: implementer-ivan
authoritative_surface: .github/workflows/
create_intent:
- tests/release/test_release_producer_stages.py
execution_mode: code_change
model: ''
owned_files:
- .github/workflows/release.yml
- .github/workflows/ci-nightly.yml
- RELEASE_CHECKLIST.md
- CONTRIBUTING.md
- tests/release/test_release_ci_ownership.py
- tests/release/test_release_producer_stages.py
role: implementer
tags: []
tracker_refs: []
---

# WP09: Release workflow: build-producer, platform wheels, smoke-wheels, attestation (approval gated on the events release)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Wire the release: `build-producer`, platform-wheel builds, set, manifest and content checks, `smoke-wheels`, attestation, nightly informational job, release docs. State honestly what runs before merge and what is only confirmable on a tag.

## Context

Plan sections 2 (provenance), 3.3, 3.4, 9, 13 item 2; research R-3, R-7, R-12, R-13. **Sole owner of `release.yml`** (a chokepoint: tests pin names and step texts in `tests/release/test_release_ci_ownership.py`). Standing Order 9: every new stage is a `needs` edge, no `continue-on-error`. **No new Python file:** the smoke is the shared composite action `.github/actions/producer-smoke` (WP01), and the checksum and set checks are modes of `check_dist_set.py` (WP08, `--verify-binaries`, `--verify-manifests`). The SBOM Gradle task and its artifact are WP01's (T003, T005); this WP consumes `producer-sbom`. **Open PRs:** none known on `release.yml` or `ci-nightly.yml`; check before the first commit.

## Precondition gate: EVENTS RELEASE (blocks approval, not authoring)

One definition, the same in every gated WP (tasks-overview section 2): this WP reads no released artifact, so it is authored now. **It is not approved until WP11 is approved and the pin plus the `pyproject.toml` floor name the released wheel `V_E`**, because the release must publish a CLI whose floor names it. The reviewer checks the pin file. WP09 therefore depends on WP08 only. **Reviewer approval checklist (the tooling will not stop an early approval):** (1) `services/event-producer/gradle/events-pin.properties` names the released wheel `V_E` and its sha256; (2) the `spec-kitty-events` floor in `pyproject.toml` names `V_E`; (3) WP11 is approved; (4) `V_E` and the pin sha256 are in this WP's notes. Approving without all four is a review defect. The prerequisite events PR is external (tasks-overview section 2); its tracking row is an open item for the orchestrator (O-6).

## Subtask T059: `build-producer` and `build-release`
**Steps**: red test first (`test_release_ci_ownership.py`: `build-producer` with its `ref` input, `needs` edges). New job `build-producer` calls `producer-native.yml` passing the release ref; repeat the `RELEASE_REF` expression in the job-level `with:` (`env` unavailable there; a test pins equal texts; confirm the `env` context behaviour, plan 17). `build-release` keeps its name, gains `needs: build-producer`, and runs, in this order:
1. **Pure wheel and sdist, unchanged:** `python -m build` (sdist and wheel).
2. `check_dist_set.py --verify-manifests` against the downloaded `producer-<leg>` manifests and `git rev-parse HEAD` (checksum continuity, AC-032; the logic and its mutations are executable tests in WP08, the workflow only calls it).
3. **Platform wheels, built from the same checkout (not from the sdist: plan 3.1 does not widen the sdist include list, so the hook file and the ignored `_native/` directory are not in it):** a loop over the `platform_wheels` entries of `dist-set.json`, each iteration with a clean build directory: `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true SPEC_KITTY_PRODUCER_BINARY=<leg binary> SPEC_KITTY_PRODUCER_PLATFORM_TAG=<tag> python -m build --wheel --outdir dist`.
4. `check_dist_set.py --verify-binaries`, `check_exact_install.py --wheel-tag` per wheel, looped wheel-content verification (per wheel), `twine check` on every wheel, `uv lock --check`.
`tests/release/test_release_producer_stages.py` asserts the step order, the `--wheel --outdir dist` form (no `sdist` argument for platform wheels), and that **every new `uses:` line is `owner/repo@<40-hex>`** (the pre-existing tag-pinned lines such as `actions/checkout@v6` are held in a pinned list captured from the base commit; a mutation adding a tag-pinned new action must fail). **Covers** FR-041, FR-042, AC-024, AC-032, G-1, G-4, G-6, G-8.

## Subtask T060: `smoke-wheels` through the shared action
**Steps**: job with three OS legs installing the matching wheel by path through `.github/actions/producer-smoke` in `cli` mode (`expect: produced`; the `command` input is the cheapest lifecycle command that persists an in-scope row in a scratch repository, chosen here and recorded in the WP notes), and the pure wheel on one leg with `expect: binary-missing`; `publish-pypi` needs both. `native-smoke` (WP01) and `smoke-wheels` use the **same** action; `test_release_producer_stages.py` asserts both `uses: ./.github/actions/producer-smoke` (drift between the PR-time and release-time check is a test failure). `smoke-wheels` runs `cli` mode, which needs the client (WP06) and the schema-backed binding (WP11) in the built wheel; WP09's lane has neither, so the job cannot run in this lane and its only pre-merge evidence is the static graph test and the action's own definition (WP01 T008: the publish log is found by a fixed `find` under the scratch repository, exactly one non-empty file with one line, not by asking the CLI). It first runs on a release tag (tasks-overview section 10). **Covers** FR-043, G-3, G-7.

## Subtask T061: Attestation and SBOM attach
**Steps**: `publish-pypi` re-runs `check_dist_set.py` on the downloaded artifact and attaches an attestation, and attaches the `producer-sbom` artifact built by `producer-native.yml` (no Gradle edit here). No reproducibility claim. The AC-040 content check is WP01's `tools/check-sbom.sh`; `publish-pypi` re-runs it on the downloaded `producer-sbom` before attaching it. **Covers** FR-042, AC-040, G-5, G-9.

## Subtask T062: Nightly and docs
**Steps**: `ci-nightly.yml` gains a producer job that calls the same smoke path and records the AC-036 numbers. **It is non-gating and informational (like the sonar job):** it is NOT in the `nightly-summary` `needs` list, so a macOS or Windows hosted-runner flake or a GraalVM download outage cannot block a release through the fail-closed `nightly-gate`; the release gate relies on `smoke-wheels` in `release.yml` itself. A test asserts the job is absent from `nightly-summary.needs` and says why. `RELEASE_CHECKLIST.md` and `CONTRIBUTING.md` updated (unsigned macOS and Windows binaries: Gatekeeper/SmartScreen may warn; extra arches out of scope). **Covers** AC-036 (nightly).

## Subtask T063: Gate pass, pre-merge evidence, deferred verification
**Steps**: run `tests/release/test_release_ci_ownership.py`, `test_release_producer_stages.py`, `test_check_exact_install.py`, `test_release_prep.py`, `tests/ci/test_fork_guard.py`, `tests/architectural/test_no_duplicate_suite_execution.py`, `tests/architectural/test_workflow_coherence.py`, `tests/contract/test_verify_pins.py`; `actionlint` if configured.
**`verify_pins.py --workflow` scope:** it reports `UNPINNED_USES` and `UNPINNED_INSTALL` for the pre-existing lines of `release.yml` (`actions/checkout@v6`, `uv sync` without `--no-install-project`, `.venv/bin/pip install`) on the base commit (recorded in `research/baseline-red.txt`), so it is red regardless of this WP. Run it **only on `producer-native.yml` and `event-producer.yml`** (WP01 files). For `release.yml` and `ci-nightly.yml` the new-`uses:` pin rule above is the check; the old `@v6` lines are pre-existing and are **not** re-pinned (out of scope unless the operator asks).
**Pre-merge evidence (what actually runs on the pull request):** the executable tests of `check_dist_set.py` including all mutations (WP08); the shared smoke action in `native-smoke` on three OS (WP01); the static graph tests (`test_release_ci_ownership.py`, `test_release_producer_stages.py`: `needs` edges, step order, call of the checks, pins).
**Only confirmable on a tag.** `release.yml` runs on a tag push or a `workflow_dispatch` of the default-branch copy only, so these stages are unexecuted until the first 4.0 release candidate. Write `kitty-specs/event-producer-reroute-01M4VDY9/research/wp09-deferred-verification.md` (committed with `safe-commit`, not owned) as a table: item (build-producer reusable call on the release ref; three platform-wheel builds with the real binaries; `--verify-manifests` and `--verify-binaries` on real artifacts; `smoke-wheels` on three OS with real wheels; attestation and OIDC publish; `nightly-gate` interplay; `verify-pypi-installability` on a multi-wheel index), who runs it (the maintainer on the release candidate), on which tag, and the pass criterion. State that **rc tags publish to the real PyPI, so this is not a harmless rehearsal and no Test-PyPI leg exists** (adding one is an operator decision, not taken here), and that the mission pull request body says these are **unproven pre-merge**. **Covers** G-9.

**Ledger sweep (no new change-triggered pytest job).** `release.yml` (tags-only push plus `workflow_dispatch`) and `ci-nightly.yml` (schedule plus `workflow_dispatch`) are rows of `NON_CHANGE_TRIGGERED_WORKFLOWS`, so `build-producer`, the platform-wheel jobs, `smoke-wheels` (shell action, no pytest) and the nightly producer job add no row to `AUTHORIZED_PER_CHANGE_SUITE_JOBS`; a test or step in these files that adds a `pull_request` or branch `push` trigger would change that and is out of scope. The only new ledger row of the mission is WP10's `native-e2e`.

## Tests, markers, jobs

| New or extended test file | Marker | Runs in |
|---|---|---|
| `tests/release/test_release_ci_ownership.py`, `test_release_producer_stages.py` | `fast` | `tests/release` shard |

## Recorded out-of-map edits

None.

## Gates
`make lint`; targeted pytest above; `uv lock --check`. Red-first and mutation (a missing wheel fails the set check; a tag-pinned new action fails the pin rule; the smoke action unshared fails the drift test).

## Definition of Done
T059 to T063 done; every new stage a `needs` edge; `publish-pypi` unreachable unless build, set-check, manifest-check and smoke succeeded (static graph test plus the executable mutations of the checkers); deferred-verification table committed; approval waits for the pin.

## Risks
SHA-pinned action versions (plan 17); job-level `with:` context; secrets/OIDC for attestation exist only in the real release; the first real execution is the release candidate.

## Reviewer Guidance
Run the approval checklist of the Precondition gate first. Read the workflow graph, not the diff: confirm `publish-pypi` cannot run unless build, set-check and smoke succeeded, and that the deferred table is honest.

## Implementation command

`spec-kitty agent action implement WP09 --agent claude --mission event-producer-reroute-01M4VDY9`
