---
work_package_id: WP01
title: 'Feasibility gate: native image on three OS, build skeleton, CI registration, hatch hook'
dependencies: []
requirement_refs:
- FR-039
- FR-041
- FR-042
- NFR-002
- NFR-004
- C-001
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
- T007
- T008
- T009
- T010
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/
create_intent:
- services/event-producer/settings.gradle.kts
- services/event-producer/build.gradle.kts
- services/event-producer/domain/build.gradle.kts
- services/event-producer/application/build.gradle.kts
- services/event-producer/adapter/build.gradle.kts
- services/event-producer/launcher/build.gradle.kts
- services/event-producer/gradle/verification-metadata.xml
- services/event-producer/README.md
- .github/workflows/producer-native.yml
- .github/workflows/event-producer.yml
- scripts/release/hatch_build_producer.py
- scripts/release/dist-set.json
- tests/cross_cutting/packaging/test_producer_stub_wheel.py
- tests/release/test_dist_set_platform_tags.py
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/settings.gradle.kts
- services/event-producer/build.gradle.kts
- services/event-producer/domain/build.gradle.kts
- services/event-producer/application/build.gradle.kts
- services/event-producer/adapter/build.gradle.kts
- services/event-producer/launcher/build.gradle.kts
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/spike/**
- services/event-producer/launcher/src/main/resources/META-INF/native-image/**
- services/event-producer/gradle/verification-metadata.xml
- services/event-producer/gradle/wrapper/**
- services/event-producer/tools/**
- services/event-producer/README.md
- .github/workflows/producer-native.yml
- .github/workflows/event-producer.yml
- .github/workflows/ci-fleet-verdict.yml
- scripts/ci/fleet_verdict.py
- tests/ci/test_fleet_verdict.py
- tests/architectural/test_no_duplicate_suite_execution.py
- contracts/tools/gradle_pin_check.py
- tests/contract/test_gradle_pin_check.py
- scripts/release/hatch_build_producer.py
- scripts/release/dist-set.json
- tests/cross_cutting/packaging/test_producer_stub_wheel.py
- tests/release/test_dist_set_platform_tags.py
- pyproject.toml
- .gitignore
- AGENTS.md
role: implementer
tags: []
tracker_refs: []
---

# WP01: Feasibility gate: native image on three OS, build skeleton, CI registration, hatch hook

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Prove the native-image feasibility on all three platforms before anything else is built, stand up the Java build skeleton, the two new CI workflows with their fleet-verdict registration, and the hatch build hook with its stub-wheel test. Capture the pre-existing red baseline first.

## Context

This is the **feasibility gate** (plan section 2, "WP01 exit criteria"). It is the only WP that can change the wheel floor (FR-041). WP02 and WP08 start from here; every other WP depends on it transitively. Plan sections 1, 2, 3.1, 3.2, 9 ("Pin tooling", "CI home") and 10; research R-2, R-7, R-9, R-12. Python here is bounded build glue only (plan 13 item 2): `hatch_build_producer.py` and the `gradle_pin_check.py` third-`Subproject` entry; no runtime logic.

**Chokepoints WP01 opens:** `pyproject.toml` (first editor: hook table only), `scripts/release/dist-set.json`, `scripts/ci/fleet_verdict.py` / `ci-fleet-verdict.yml` / `tests/ci/test_fleet_verdict.py` (the shared CI gate fleet-verdict), the Gradle build files and `gradle/verification-metadata.xml` (later WPs append dependency lines serially, in dependency order).

## Feasibility gate: pass/fail and failure path

**PASS** needs all of, on each of linux x86_64 (`ubuntu-24.04`, `manylinux_2_28` container), macOS arm64 (`macos-14`), Windows x86_64 (`windows-2022`):
1. a trivial producer (stdin JSON in, JSON out, Jackson, one JSON-Schema validation, a `FileChannel` lock, a `gethostname` FFM downcall; `ws2_32` on Windows) builds with the pinned GraalVM Community JDK 25 and Kotlin DSL on JDK 25;
2. the binary starts in under 100 ms warm;
3. a stub wheel built with WP01's own hook (`HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`, tag read from `dist-set.json`) installs into a fresh venv and the bundled binary runs by path on its OS;
4. the measured maximum `GLIBC_` symbol and macOS `minos` do not exceed the pinned floors.

**On failure of a leg:** try the two fallbacks inside WP01 (musl static on Linux; hand-written reachability metadata). If a leg still fails, **HALT: write the evidence to `research/wp01-feasibility.md`, commit it, stop, and report to the orchestrator. Do not continue to WP02 and do not start any other task of a later WP.** The mission floor (FR-041) is then an operator decision.

## Subtask T001: Baseline capture (first task, before any change)

**Purpose**: record pre-existing red so introduced red is attributable (plan 10, R-9).
**Steps**:
1. From the base commit (`git rev-parse HEAD` before WP01's first change), create a detached scratch worktree outside the repo (`git worktree add --detach <scratch> <sha>`); never switch the main checkout's branch.
2. Run each gate file of research R-7 and the owning fast tiers, **one invocation per file**, no whole-directory runs. Include `tests/contract/test_gradle_pin_check.py`, `test_verify_pins.py`, `tests/ci/test_fleet_verdict.py`, `tests/release/test_release_ci_ownership.py`, `tests/architectural/test_hosted_drain_gate.py`, `test_status_module_boundary.py`, `test_runtime_emitter_seam.py`, `test_completion_manifest_freshness.py`, `test_no_duplicate_suite_execution.py`.
3. A failure shaped like the #3283 venv-lock cascade: `uv sync --frozen --all-extras`, rerun once alone, then record. A red node attributable to #3284: record "known red, tracked: #3284". Do not run known-P0 reproductions.
4. Write `kitty-specs/event-producer-reroute-01M4VDY9/research/baseline-red.txt`: base sha, per file the command, counts, failing node ids. Any other distinct pre-existing red: stop and report it to the orchestrator (the operator opens the issue) before continuing.
5. Remove the scratch worktree with `git worktree remove <path>` (explicit path, no globs).
**Files**: `research/baseline-red.txt` (new). **Validation**: file lists the base sha and every R-7 gate file; commit via `spec-kitty safe-commit`.

## Subtask T002: Campsite commit (docs only, distinct)

**Steps**: replace the stale "PyPI Release" paragraph of `AGENTS.md` (says `release.yml` is absent) and the matching line of `scripts/release/README.md` (the README is WP08's file afterwards; this is the one WP01 edit) with the current truth. **Validation**: `tests/architectural/test_no_legacy_terminology.py` and docs-lint stay green. Own commit, `docs(campsite)`.

## Subtask T003: Java skeleton, pins, dependency verification (red first)

**Steps**:
1. `services/event-producer/` with `settings.gradle.kts`, four subprojects `:domain :application :adapter :launcher` (package root `ai.speckitty.producer`), Kotlin DSL, `languageVersion = 25`. Each subproject build file carries the dependency rules of plan section 1 (`:domain` java.base only).
2. `tools/pins.json` (Gradle 8.14.5 via `contracts/tools/install_tools.py --pins services/event-producer/tools/pins.json --only gradle`, unchanged; GraalVM JDK 25 archive url + sha256; chosen `jdk-25.0.x` release recorded with its reason in `wp01-feasibility.md`).
3. Generate `gradle/verification-metadata.xml` (`sha256`) for every dependency and plugin (Jackson, a JSON-Schema validator, JUnit, ArchUnit, JaCoCo; WP01 settles the whole Java dependency set so later WPs add no dependencies). Add the tamper check as in `contracts/` (a planted checksum change fails the build) as a test.
4. `contracts/tools/gradle_pin_check.py`: third `Subproject` entry; `tests/contract/test_gradle_pin_check.py` gains planted-mismatch cases and a compared-values floor. Test committed red first.
**Validation**: `gradle -p services/event-producer build`, `uv run --frozen pytest -q tests/contract/test_gradle_pin_check.py tests/contract/test_verify_pins.py`; revert-check the pin entry.

## Subtask T004: Spike producer and native-image configuration

**Steps**: in `:launcher` package `spike` (WP04 replaces it; deleting it is WP04's first commit): read one JSON object from stdin, validate one field against a tiny schema, take an exclusive `FileChannel` lock on a temp file, call `gethostname` through the FFM API (`ws2_32` on Windows), write one JSON reply. Native-image resource and reachability configuration under `META-INF/native-image/`. Record cold/warm start times per OS.
**Validation**: JVM run and native run give identical replies; the native binary is not dynamically linked beyond the pinned glibc floor.

## Subtask T005: `producer-native.yml` (reusable workflow)

**Steps**: `workflow_call`, required input `ref`, matrix over the three legs, `fail-fast: false`; checks out `ref`; builds in the pinned `manylinux_2_28` container on Linux; uploads `producer-<leg>` (binary plus `producer-manifest.json`: binary sha256, toolchain sha256, source commit, measured floor); fails when the measured floor exceeds the pinned floor; macOS binary never stripped, `codesign --verify` run; fork guard first on root jobs; SHA-pinned actions only (`verify_pins.py --pins services/event-producer/tools/pins.json --workflow <file>`).
**Validation**: all three legs green on a throwaway branch run; `tests/contract/test_verify_pins.py` green.

## Subtask T006: Hatch hook, `pyproject.toml`, `.gitignore`, `dist-set.json` (red first)

**Steps**:
1. Tests first (red): `tests/release/test_dist_set_platform_tags.py` (`dist-set.json` holds exactly the three legs the workflows build; tags `py3-none-manylinux_2_28_x86_64`, `py3-none-macosx_11_0_arm64`, `py3-none-win_amd64`); `tests/cross_cutting/packaging/test_producer_stub_wheel.py` (hook off: wheel and sdist identical to today's and binary-free **with a stub planted untracked in `src/specify_cli/_native/`**; hook on: platform wheel with the binary at `specify_cli/_native/<name>`, `PRODUCER.sha256`, exec bit `0755` on non-Windows).
2. `scripts/release/hatch_build_producer.py`: acts only when `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`; requires `SPEC_KITTY_PRODUCER_BINARY` and `SPEC_KITTY_PRODUCER_PLATFORM_TAG`; sets `pure_python = False`, explicit `tag`, `force_include`. No runtime logic.
3. `pyproject.toml`: only `[tool.hatch.build.targets.wheel.hooks.custom]` with `path` and `enable-by-default = false`. `.gitignore`: `src/specify_cli/_native/`; `git check-ignore` pinned by a test.
4. `scripts/release/dist-set.json`: `{"v":1,"platform_wheels":[{"leg","tag"}x3]}`.
**Validation**: `uv sync`, an editable install and `uv build --wheel` unchanged with the hook off (C-007, AC-038); hatchling is absent from the checkout venv, so use `--no-isolation` with the pinned test extra or the uv cache (plan 17).

## Subtask T007: `event-producer.yml`, fleet-verdict registration, ledger rows

**Steps**: workflow on `pull_request` and `push` to `main` with the path filters of plan 9; jobs `verify-pins`, `gradle-test` (ubuntu, JDK 25, JaCoCo, ArchUnit, dependency verification, tamper check), `native-smoke` (three legs through `producer-native.yml`, then wheel by path and a smoke script per OS), terminal `event-producer-gate` (the `contracts-gate` pattern). Register both workflows in `scripts/ci/fleet_verdict.py::PR_WORKFLOWS`, `ci-fleet-verdict.yml`, `tests/ci/test_fleet_verdict.py`; add the `NON_CHANGE_TRIGGERED_WORKFLOWS` ledger row for `producer-native.yml` (`tests/architectural/test_no_duplicate_suite_execution.py`). Registration and the workflow land in the same commit (the closed-inventory test is red on any unregistered PR workflow).
**Validation**: `uv run --frozen pytest -q tests/ci/test_fleet_verdict.py tests/ci/test_fork_guard.py tests/architectural/test_no_duplicate_suite_execution.py`.

## Subtask T008: Stub-wheel run on each OS

**Steps**: in `native-smoke`, install the matching stub wheel by path into a fresh venv on its OS and run the bundled binary; assert exec bit (non-Windows).

## Subtask T009: Latency and size numbers for AC-036

**Steps**: record cold and warm spawn times and binary size per OS in `research/wp01-feasibility.md`; these seed the relative limits WP10 sets.

## Subtask T010: Feasibility verdict

**Steps**: write `research/wp01-feasibility.md` (per leg: tool versions, floors measured vs pinned, timings, fallbacks tried, **verdict PASS or HALT**). On HALT follow "On failure" above.

## Gates (plan 9)

`gradle -p services/event-producer test`; `uv run --frozen ruff check` and `ruff format --check --force-exclude` on the Python glue; `make lint`; targeted pytest: `tests/contract/test_gradle_pin_check.py tests/contract/test_verify_pins.py tests/ci/test_fleet_verdict.py tests/ci/test_fork_guard.py tests/architectural/test_no_duplicate_suite_execution.py tests/architectural/test_pyproject_shape.py tests/release/test_dist_set_platform_tags.py tests/cross_cutting/packaging/test_producer_stub_wheel.py`; the producer-client thinness gate does not exist yet (WP06) but the hook file must hold no runtime logic (no producer, envelope, id, clock or schema code). Each behaviour: test committed red first as its own commit, verified red on the base, green on the tip, revert-checked.

## Definition of Done

T001 to T010 done; baseline file committed; all three legs green; stub wheel installs and runs on each OS; verdict PASS recorded; new PR workflows registered; hook-off build output unchanged.

## Risks

GraalVM or Kotlin DSL on JDK 25 may fail a leg (HALT path). The macOS runner label and manylinux container are plan assumptions (R-12). `uv.lock` untouched here.

## Reviewer Guidance

Check the HALT criteria were applied honestly (measurements, not assertions), that red-first commits exist, that the hook is inert when unset, and that fleet-verdict registration matches the workflow trigger set.

## Implementation command

`spec-kitty agent action implement WP01 --agent claude --mission event-producer-reroute-01M4VDY9`
