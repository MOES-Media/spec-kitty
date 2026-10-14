---
work_package_id: WP09
title: 'Release workflow: build-producer, platform wheels, smoke-wheels, SBOM, attestation (events-release gated)'
dependencies:
- WP08
- WP11
requirement_refs:
- FR-040
- FR-041
- FR-042
- FR-043
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T058
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
- scripts/release/producer_smoke.py
execution_mode: code_change
model: ''
owned_files:
- .github/workflows/release.yml
- .github/workflows/ci-nightly.yml
- RELEASE_CHECKLIST.md
- CONTRIBUTING.md
- tests/release/test_release_ci_ownership.py
- tests/release/test_release_producer_stages.py
- scripts/release/producer_smoke.py
role: implementer
tags: []
tracker_refs: []
---

# WP09: Release workflow: build-producer, platform wheels, smoke-wheels, SBOM, attestation (events-release gated)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Wire the release: `build-producer`, platform-wheel builds, set and content checks, `smoke-wheels`, SBOM, provenance, attestation, nightly call, release docs.

## Context

Plan sections 2 (provenance), 3.3, 3.4, 9, 13 item 2; research R-3, R-7, R-12, R-13. **Sole owner of `release.yml`** (a chokepoint: tests pin names and step texts in `tests/release/test_release_ci_ownership.py`). Standing Order 9: every new stage is a `needs` edge, no `continue-on-error`. **External dependency: gated on the spec-kitty-events release** (the release must publish a CLI whose floor names the released events wheel).

## Precondition gate (T058): EVENTS RELEASE

**First task.** WP11 complete and the pin plus `pyproject.toml` floor name the released wheel. If not, **STOP; WP09 is not started or approved.** Report to the orchestrator.

## Subtask T058: Events-release precondition check

**Steps**: as above. No code.

## Subtask T059: `build-producer` and `build-release`

**Steps**: red test first (`test_release_ci_ownership.py`: `build-producer` with its `ref` input, `needs` edges). New job `build-producer` calls `producer-native.yml` passing the release ref; repeat the `RELEASE_REF` expression in the job-level `with:` (`env` unavailable there; a test pins equal texts; confirm the `env` context behaviour, plan 17). `build-release` keeps its name, gains `needs: build-producer`; fails unless every manifest `source commit` equals `git rev-parse HEAD` and recomputes each sha256 before building a wheel (checksum continuity, AC-032); builds three platform wheels with the hook env vars (tag from `dist-set.json`), the pure wheel and sdist; runs `check_dist_set.py --verify-binaries`, `check_exact_install.py --wheel-tag`, looped wheel-content verification (per wheel), `twine check` on every wheel, `uv lock --check`. **Covers** FR-041, FR-042, AC-024, AC-032, G-1, G-4, G-6, G-8.

## Subtask T060: `smoke-wheels` and `producer_smoke.py`

**Steps**: job with three OS legs installing the matching wheel by path into a fresh venv and running the smoke script (one valid row appended); the pure wheel on one leg reports `binary-missing`; `publish-pypi` needs both. The smoke script is a thin invocation of the CLI (build glue, no logic). **Covers** FR-043, G-3, G-7.

## Subtask T061: SBOM and attestation

**Steps**: producer SBOM listing the Java dependencies and the embedded toolchain (additive edit of `services/event-producer/build.gradle.kts` and `gradle/verification-metadata.xml`, serial after WP01/WP11); `publish-pypi` re-runs `check_dist_set.py` and attaches an attestation. No reproducibility claim. **Covers** FR-042, AC-040, G-5, G-9.

## Subtask T062: Nightly and docs

**Steps**: `ci-nightly.yml` calls the same smoke path and records the AC-036 numbers; `RELEASE_CHECKLIST.md` and `CONTRIBUTING.md` updated (unsigned macOS and Windows binaries: Gatekeeper/SmartScreen may warn; extra arches out of scope). **Covers** AC-036 (nightly).

## Subtask T063: Gate pass

**Steps**: run `tests/release/test_release_ci_ownership.py`, `test_check_exact_install.py`, `test_release_prep.py`, `tests/ci/test_fork_guard.py`, `tests/architectural/test_no_duplicate_suite_execution.py`, `tests/contract/test_verify_pins.py` and `verify_pins.py --workflow` for touched workflows; `actionlint` if configured. `workflow_run`-type behaviour cannot be proven from a PR (record what is only confirmable on a release candidate).

## Gates

`make lint`; targeted pytest above; `uv lock --check`; Gradle `test` for the SBOM edit. Red-first and mutation (a missing wheel fails the release).

## Definition of Done

T058 to T063 done; every new stage a `needs` edge; publish blocked when any wheel or checksum is wrong.

## Risks

SHA-pinned action versions (plan 17); job-level `with:` context; secrets/OIDC for attestation exist only in the real release.

## Reviewer Guidance

Read the workflow graph, not the diff: confirm `publish-pypi` cannot run unless build, set-check and smoke succeeded.

## Implementation command

`spec-kitty agent action implement WP09 --agent claude --mission event-producer-reroute-01M4VDY9`
