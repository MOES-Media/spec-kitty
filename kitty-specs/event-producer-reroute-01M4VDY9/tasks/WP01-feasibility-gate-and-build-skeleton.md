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
- tests/architectural/test_build_glue_thin.py
- tests/ci/test_event_producer_workflow.py
- .github/actions/producer-smoke/action.yml
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
- services/event-producer/launcher/src/main/resources/META-INF/native-image/ai.speckitty/producer-spike/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/spike/**
- services/event-producer/gradle/verification-metadata.xml
- services/event-producer/gradle/wrapper/**
- services/event-producer/tools/**
- services/event-producer/README.md
- .github/workflows/producer-native.yml
- .github/workflows/event-producer.yml
- .github/workflows/ci-fleet-verdict.yml
- .github/actions/producer-smoke/**
- scripts/ci/fleet_verdict.py
- tests/ci/test_fleet_verdict.py
- tests/architectural/test_no_duplicate_suite_execution.py
- contracts/tools/gradle_pin_check.py
- tests/contract/test_gradle_pin_check.py
- scripts/release/hatch_build_producer.py
- scripts/release/dist-set.json
- tests/cross_cutting/packaging/test_producer_stub_wheel.py
- tests/release/test_dist_set_platform_tags.py
- tests/architectural/test_build_glue_thin.py
- tests/ci/test_event_producer_workflow.py
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

Prove the native-image feasibility on all three platforms before anything else is built, stand up the Java build skeleton (including coverage-floor wiring and the producer SBOM task), the two new CI workflows with their fleet-verdict registration, the shared smoke action, and the hatch build hook with its stub-wheel test. Capture the pre-existing red baseline first.

## Context

This is the **feasibility gate** (plan section 2, "WP01 exit criteria"). It is the only WP that can change the wheel floor (FR-041). WP02 and WP08 start from here; every other WP depends on it transitively. Plan sections 1, 2, 3.1, 3.2, 9 ("Pin tooling", "CI home") and 10; research R-2, R-7, R-9, R-12. Python here is bounded build glue only (plan 13 item 2): `hatch_build_producer.py` and the `gradle_pin_check.py` third-`Subproject` entry; no runtime logic. The smoke is a shared composite action (YAML and shell), not a Python file.

**Chokepoints WP01 opens:** `pyproject.toml` (first editor: hook table only), `scripts/ci/fleet_verdict.py` / `ci-fleet-verdict.yml` / `tests/ci/test_fleet_verdict.py` (the shared CI gate fleet-verdict), `event-producer.yml` (later recorded edits by WP06 and WP10), the Gradle build files and `gradle/verification-metadata.xml` (WP01 settles the whole dependency set, the JaCoCo floors and the SBOM task, so later WPs add no build lines; the only later build-file edits are WP11's script plugin, applied by a hook WP01 pre-wires, and WP04's one `producerMainClass` line, recorded), and `scripts/release/dist-set.json` (WP01 writes the complete file; WP08 does not edit it).

**Check open PRs before the first commit:** run `gh pr list --state open --json number,files` and compare against the owned files. Known overlaps (tasks-overview section 4): PR #6148 touches `ci-fleet-verdict.yml` (rebase before the lane starts, or take theirs); PR #6205 touches `pyproject.toml` (low risk for the hook table; WP11 carries the real conflict).

**Size note (recorded, not split).** WP01 is the largest Java-side WP and the HALT gate: ten subtasks (T001 to T010) and 29 owned files (feasibility gate, Java skeleton, two workflows, fleet registration, hook, dist-set, pin tooling, SBOM check). Internal cut, as for WP06, in commit groups that already exist: (1) baseline and campsite (T001, T002); (2) skeleton, pins, floors, SBOM task and check, spike (T003, T004); (3) native workflow and hatch hook with the stub-wheel test (T005, T006); (4) CI registration and the shared smoke action (T007, T008; the registration is its own commit); (5) measurements and verdict (T009, T010). A sibling WP for group 4 is **not** free: the feasibility run needs the registration and the smoke action in the pushed tip (Evidence path), so the WP is not split now; if a reviewer cannot hold it, the cut is after group 3 with the evidence push at the end of group 4 (operator decision; numbering unchanged).

## Evidence path for the feasibility run (who pushes)

The decisive evidence is a real run on `macos-14` and `windows-2022`. The WP agent has no push right, and a `workflow_dispatch` workflow can only be dispatched once its file is on the default branch, so a dispatch is not a pre-merge path. The path is the `pull_request` trigger of `event-producer.yml`:

1. The agent commits the skeleton, the spike, `producer-native.yml`, the hook (with the stub-wheel test, T006), `event-producer.yml` with its registration (T007) **and the shared smoke action (T008)** (T003 to T008). Everything the `native-smoke` job calls is therefore in the pushed tip: it uses the stub wheel from T006 and the action of T008, so a push after T007 alone would make the scratch run red for a missing action, not for a feasibility reason. Then the agent **reports to the orchestrator that the lane is ready for a feasibility run**.
2. The **orchestrator pushes the lane branch and opens a scratch draft pull request** (operator-authorised, named `wp01 feasibility scratch`, closed after the verdict; the mission PR is a separate, later PR). This is the first of the pushes planned for the mission (WP10 has its own evidence path for the per-OS integration numbers); if the first run is red for a defect in the workflow, hook or action (not a feasibility finding), the agent commits the fix and the orchestrator pushes the same branch again (a second push to the same scratch pull request is explicitly allowed, each push's run ids are recorded).
3. The agent resumes at T009 and records the run ids and per-leg conclusions with `gh run view <id> --json conclusions,jobs` in `research/wp01-feasibility.md`. **T010 must cite those run ids. No PASS verdict is valid without them.** The runner access assumption (hosted `macos-14` and `windows-2022` available to the repository) is named in the record.

`producer-native.yml` is `workflow_call` plus `workflow_dispatch` (input `ref`), so it can be rerun by hand after the mission lands; that is not part of the pre-merge evidence.

## Feasibility gate: pass/fail and failure path

**PASS** needs all of, on each of linux x86_64 (`ubuntu-24.04`, `manylinux_2_28` container), macOS arm64 (`macos-14`), Windows x86_64 (`windows-2022`):
1. a trivial producer (stdin JSON in, JSON out, Jackson, one JSON-Schema validation, a `FileChannel` lock, a `gethostname` FFM downcall; `ws2_32` on Windows) builds with the pinned GraalVM Community JDK 25 and Kotlin DSL on JDK 25. The FFM downcall configuration files that were needed are listed per OS in the record;
2. the binary starts in under 100 ms **warm**, where warm is defined by `tools/measure-start.sh` (shell, run unchanged on all three legs, Git Bash on Windows): 25 consecutive spawns of the same stdin payload, the first 5 discarded, the threshold applies to the **median of the last 20**, the p95 is recorded;
3. a stub wheel built with WP01's own hook (`HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`, tag read from `dist-set.json`) installs into a fresh venv and the bundled binary runs by path on its OS;
4. the measured maximum `GLIBC_` symbol and macOS `minos` do not exceed the pinned floors.

**On failure of a leg:** try the fallbacks inside WP01: musl static on Linux; hand-written reachability metadata; for a macOS `minos` miss, a third fallback: **raise the pinned floor to the measured value if the operator accepts**, which is a decision recorded in the feasibility file and not an automatic HALT. If a leg still fails, **HALT: write the evidence to `research/wp01-feasibility.md`, commit it, stop, and report to the orchestrator. Do not continue to WP02 and do not start any other task of a later WP.** The mission floor (FR-041) is then an operator decision.

**State left in the tree on HALT:** the Java skeleton, the spike, the workflows and the registration commit are on the lane branch only; nothing from the lane merges to the mission branch on HALT, so the shared chokepoints (`fleet_verdict.py`, `ci-fleet-verdict.yml`, `test_fleet_verdict.py`, `pyproject.toml` hook table) are not changed on the target. The registration of T007 is its own commit (`ci(registration)`) so it can be dropped separately if the orchestrator keeps only the evidence. The orchestrator decides what to keep.

## Subtask T001: Baseline capture (first task, before any change)

**Purpose**: record pre-existing red so introduced red is attributable (plan 10, R-9).
**Steps**:
1. From the base commit (`git rev-parse HEAD` before WP01's first change), create a detached scratch worktree outside the repo (`git worktree add --detach <scratch> <sha>`); never switch the main checkout's branch.
2. Run each gate file of research R-7 and the owning fast tiers, **one invocation per file**, no whole-directory runs. Include `tests/contract/test_gradle_pin_check.py`, `test_verify_pins.py`, `tests/ci/test_fleet_verdict.py`, `tests/release/test_release_ci_ownership.py`, `tests/architectural/test_hosted_drain_gate.py`, `test_status_module_boundary.py`, `test_runtime_emitter_seam.py`, `test_completion_manifest_freshness.py`, `test_no_duplicate_suite_execution.py`, and the explicit files WP06 will run for `tests/status/` (`test_saas_fanout_timeout.py`, `test_drain_fanout.py`, `test_emit_fanout_after_adapter.py`, `test_conftest_restores_zeitgeist_registry.py`), `tests/architectural/test_clock_import_ban.py`, `test_clock_call_ban.py`, `test_fast_tier_marker_completeness.py`, `test_module_shard_registry.py`, `test_workflow_coherence.py`. Also record `contracts/tools/verify_pins.py --workflow` on `release.yml` and `ci-nightly.yml`: it is expected to report `UNPINNED_USES` (`actions/checkout@v6` is tag-pinned) and `UNPINNED_INSTALL` already on base; those lines are pre-existing and are **not** re-pinned by this mission (WP09 T063).
3. A failure shaped like the #3283 venv-lock cascade: `uv sync --frozen --all-extras`, rerun once alone, then record. A red node attributable to #3284: record "known red, tracked: #3284". Do not run known-P0 reproductions.
4. Write `kitty-specs/event-producer-reroute-01M4VDY9/research/baseline-red.txt`: base sha, per file the command, counts, failing node ids. Any other distinct pre-existing red: stop and report it to the orchestrator (the operator opens the issue) before continuing.
5. Remove the scratch worktree with `git worktree remove <path>` (explicit path, no globs).
**Order:** capture on the base sha first, but **commit the file after T002**: the campsite stays the first commit of the WP (plan 11). **Files**: `research/baseline-red.txt` (new). **Validation**: file lists the base sha and every R-7 gate file; commit via `spec-kitty safe-commit`.

## Subtask T002: Campsite commit (docs only, distinct)

**Steps**: replace the stale "PyPI Release" paragraph of `AGENTS.md` (it says `release.yml` is absent; that is the only place the stale claim lives, verified: `scripts/release/README.md` carries no such line, so it is **not** edited here) with the current truth. **Validation**: `tests/architectural/test_no_legacy_terminology.py` and docs-lint stay green. Own commit, `docs(campsite)`, the first commit of the WP.

## Subtask T003: Java skeleton, pins, dependency verification, coverage floors, SBOM task (red first)

**Steps**:
1. `services/event-producer/` with `settings.gradle.kts`, four subprojects `:domain :application :adapter :launcher` (package root `ai.speckitty.producer`), Kotlin DSL, `languageVersion = 25`. Each subproject build file carries the dependency rules of plan section 1 (`:domain` java.base only).
2. `tools/pins.json` (Gradle 8.14.5 via `contracts/tools/install_tools.py --pins services/event-producer/tools/pins.json --only gradle`, unchanged; GraalVM JDK 25 archive url + sha256; chosen `jdk-25.0.x` release recorded with its reason in `wp01-feasibility.md`).
3. Generate `gradle/verification-metadata.xml` (`sha256`) for every dependency and plugin (Jackson, a JSON-Schema validator, JUnit, ArchUnit, JaCoCo, the SBOM plugin; WP01 settles the whole Java dependency set so later WPs add no dependencies). Add the tamper check as in `contracts/` (a planted checksum change fails the build) as a test.
4. **Coverage floors are wired here, for all WPs.** One convention script, `tools/jacoco-floors.gradle.kts` (WP01-owned under `tools/**`), applied by `:domain`, `:application`, `:adapter` and the probe below: JaCoCo `:domain` 95% line and 90% branch, `:application` 90%, `:adapter` 80%, `:launcher` excluded per plan 13, attached to `check` (`gradle -p services/event-producer check` is the gate command everywhere; `test` alone does not run `jacocoTestCoverageVerification`). The floors are overridable by a Gradle property (`jacocoLineFloor`) for the tamper test only. **Floor-breach test (red first), against something that has code:** on WP01's tip `:domain` has no classes and no execution data, and `jacocoTestCoverageVerification` is skipped without execution data, so `:domain:check -PjacocoLineFloor=1.01` would stay green and prove nothing. WP01 therefore owns a tiny standalone build `tools/floor-probe/` (one class, one test, its own `settings.gradle.kts`, its own dependency-verification metadata, applying the same `tools/jacoco-floors.gradle.kts`). `tools/expect-failure.sh` runs two commands: a **positive control** `gradle -p services/event-producer/tools/floor-probe check` that must be green (so the probe measures something), then the same with `-PjacocoLineFloor=1.01`, which passes only if the build is red **with a coverage-verification failure in its output** (not any other red). The probe uses the exact script the real subprojects apply, so a breach there proves the wiring for all four; from WP02 on the real subprojects are covered by that same convention with no further WP01 edit. The `verify-pins` job runs it, and `tests/ci/test_event_producer_workflow.py` asserts the step exists.
5. **SBOM task:** a `cyclonedxBom` (or the pinned plugin's equivalent) task in `build.gradle.kts` listing the Java dependencies; `producer-native.yml` (T005) uploads it as `producer-sbom` beside the binary and the embedded toolchain (pins.json) is listed in `producer-manifest.json`. WP09 only consumes the artifact. **SBOM content check (AC-040, red first):** `tools/check-sbom.sh <sbom> <manifest>` (shell, under the WP01-owned `tools/**`) fails unless the SBOM names a pinned Java dependency (for example `jackson-core`) and `producer-manifest.json` lists the embedded toolchain (the `pins.json` GraalVM archive sha256). `tools/expect-failure.sh` runs it on a planted SBOM without the dependency and on a manifest without the toolchain entry; both must fail (mutation). Floor: the live SBOM names at least one dependency. `producer-native.yml` (T005) runs the check on each leg; WP09 T061 re-runs it on the downloaded artifact.
6. `adapter/build.gradle.kts` applies an optional script plugin if present: `file("../gradle/events-fetch.gradle.kts").takeIf { it.exists() }?.let { apply(from = it) }`. WP11 owns that script file, so it never edits this build file. (The fetch task is mandatory once WP11 lands; WP11's tests assert the file and task exist.)
7. `contracts/tools/gradle_pin_check.py`: third `Subproject` entry; `tests/contract/test_gradle_pin_check.py` gains planted-mismatch cases and a compared-values floor. Test committed red first.
8. `tests/architectural/test_build_glue_thin.py` (created here; the **single authority for the build-glue pin**, see Gates): closed import allowlist and forbidden names over the build-glue files, filesystem walk, floor and mutations; `check_dist_set.py` and the `check_exact_install.py --wheel-tag` edit are on its explicit "not yet landed" list that WP08's DoD empties.
9. **Test fixtures and test classpath, settled once (no later build-file edit).** `:domain` applies `java-test-fixtures` and `:adapter` declares `testImplementation(testFixtures(project(":domain")))` and `testImplementation(project(":domain"))`, so `:adapter` tests (WP11's released-artifact tests) see the `:domain` classes and the shared comparator mutants and fixture loader that WP02 places under `domain/src/testFixtures/**` (WP02-owned, T013). Plan section 1 lists `:adapter` as depending on `:application`; this is a **test-classpath-only** addition, `:domain` stays `java.base` only for main code, and `adapter/build.gradle.kts` main dependencies are unchanged (pinned by a Gradle verification task in the root `build.gradle.kts`, WP01-owned: `verifyModuleDependencyRules`, attached to `check`, which fails if any `:adapter` main configuration (`api`, `implementation`, `compileOnly`, `runtimeOnly`) declares `project(":domain")` or a `testFixtures` capability, or if `:domain` main declares any dependency at all; floor: it reports the four projects it inspected, and `tools/expect-failure.sh` runs it once with `-PplantAdapterDomainMain=true` (a property the task honours only to add the planted dependency) and passes only if the build is red with this task's own message. It is deliberately not an ArchUnit test, because that would need a `src/test` tree in a subproject a later WP owns). JaCoCo excludes `testFixtures`. The `gradle_pin_check.py` third entry and `verification-metadata.xml` cover these configurations.
10. **Launcher entry class, one property.** `launcher/build.gradle.kts` reads the native-image and application main class from one property, `producerMainClass`, set in WP01 to the spike entry (`ai.speckitty.producer.launcher.spike.SpikeMain`, WP01-owned). WP04's first commit changes that single line to the real entry `ai.speckitty.producer.launcher.Main` in the same commit that deletes the spike (a recorded second-editor edit; builds and `producer-native.yml` stay green across the handover because the property and the class change together). A launcher test (`launcher/src/test/java/ai/speckitty/producer/launcher/spike/**`, **WP01-owned** in `owned_files`; WP04's first commit deletes it with the spike, a recorded out-of-map deletion, and WP04's `protocol` test replaces it) asserts the class named by the property exists and has `main`.
**Validation**: `gradle -p services/event-producer check`, `uv run --frozen pytest -q tests/contract/test_gradle_pin_check.py tests/contract/test_verify_pins.py tests/architectural/test_build_glue_thin.py`; revert-check the pin entry.

## Subtask T004: Spike producer and native-image configuration

**Steps**: in `:launcher` package `spike`, entry class `ai.speckitty.producer.launcher.spike.SpikeMain` named by the `producerMainClass` property (T003 step 10) (WP04 replaces it; deleting it, and the `producer-spike` metadata directory, is a **recorded out-of-map deletion by WP04's first commit**): read one JSON object from stdin (the action's inline default request of T008 must be accepted), validate one field against a tiny schema, take an exclusive `FileChannel` lock on a temp file, call `gethostname` through the FFM API (`ws2_32` on Windows), write one JSON reply. Native-image resource and reachability configuration only under `META-INF/native-image/ai.speckitty/producer-spike/` (native-image merges every `META-INF/native-image/**` directory on the classpath, so each later WP owns its own sibling directory; see tasks-overview R5). Record cold/warm start times per OS with `tools/measure-start.sh`.
**Validation**: JVM run and native run give identical replies; the native binary is not dynamically linked beyond the pinned glibc floor.

## Subtask T005: `producer-native.yml` (reusable workflow)

**Steps**: `workflow_call` and `workflow_dispatch`, required input `ref`, matrix over the three legs, `fail-fast: false`; checks out `ref`; builds in the pinned `manylinux_2_28` container on Linux; **Windows leg runs `native-image` from a `vcvars64` environment** (a SHA-pinned MSVC-environment action, the SHA resolved and recorded at implementation time, plan 17); **macOS leg sets `MACOSX_DEPLOYMENT_TARGET=11.0`** and records `otool -l` `minos`; uploads `producer-<leg>` (binary plus `producer-manifest.json`: binary sha256, toolchain sha256, source commit, measured floor) and `producer-sbom`; fails when the measured floor exceeds the pinned floor; macOS binary never stripped, `codesign --verify` run; fork guard first on root jobs; SHA-pinned actions only (`verify_pins.py --pins services/event-producer/tools/pins.json --workflow <file>`); after producing the SBOM each leg runs `tools/check-sbom.sh` (T003 step 5, AC-040).
**Validation**: the three legs are green on the scratch pull request run described under "Evidence path"; `tests/contract/test_verify_pins.py` green.

## Subtask T006: Hatch hook, `pyproject.toml`, `.gitignore`, `dist-set.json` (red first)

**Steps**:
1. Tests first (red): `tests/release/test_dist_set_platform_tags.py` (`dist-set.json` holds exactly the three legs the workflows build; tags `py3-none-manylinux_2_28_x86_64`, `py3-none-macosx_11_0_arm64`, `py3-none-win_amd64`; and the `sdist` and `pure_wheels` keys, mutations: remove a leg, add an unexpected one); `tests/cross_cutting/packaging/test_producer_stub_wheel.py` (hook off: wheel and sdist identical to today's and binary-free **with a stub planted untracked in `src/specify_cli/_native/`**; hook on: platform wheel with the binary at `specify_cli/_native/<name>`, `PRODUCER.sha256`, exec bit `0755` on non-Windows).
2. `scripts/release/hatch_build_producer.py`: acts only when `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`; requires `SPEC_KITTY_PRODUCER_BINARY` and `SPEC_KITTY_PRODUCER_PLATFORM_TAG`; sets `pure_python = False`, explicit `tag`, `force_include`. No runtime logic; covered by `tests/architectural/test_build_glue_thin.py` (mutation: the hook with `import uuid` is red).
3. `pyproject.toml`: only `[tool.hatch.build.targets.wheel.hooks.custom]` with `path` and `enable-by-default = false`. `.gitignore`: `src/specify_cli/_native/`; `git check-ignore` pinned by a test.
4. `scripts/release/dist-set.json`, **written complete here**: `{"v":1,"platform_wheels":[{"leg","tag"}x3],"sdist":1,"pure_wheels":["py3-none-any"]}`. WP08 adds tests and the checker and does not edit this file.
**Validation**: `uv sync`, an editable install and `uv build --wheel` unchanged with the hook off (C-007, AC-038); hatchling is absent from the checkout venv, so use `--no-isolation` with the pinned test extra or the uv cache (plan 17).

## Subtask T007: `event-producer.yml`, fleet-verdict registration, ledger rows

**Steps**: workflow on `pull_request` and `push` to `main`. Path filters (every glob must match a tracked path when the workflow lands, `test_workflow_coherence.py` is run as validation): `services/event-producer/**`, `scripts/release/**`, `pyproject.toml`, `.github/actions/producer-smoke/**`, itself, `producer-native.yml`. (`src/specify_cli/status/producer_*.py` is added by WP06 when the files exist; `src/specify_cli/_native/**` is gitignored and never listed.) Jobs:
- `verify-pins`: `verify_pins.py` for both workflows, **`uv run --frozen --no-sync python contracts/tools/gradle_pin_check.py`** called directly as a script, **with no pytest in this job** (its pin tests, `tests/contract/test_gradle_pin_check.py` and `test_verify_pins.py`, carry the `corpus` marker and already run in the router's `tests (contract tools)` lane, which fires on `contracts/**`; a pytest call here would be a duplicate per-change suite execution and turn `tests/architectural/test_no_duplicate_suite_execution.py` red) (so a later edit of the Java build files or `pins.json` is caught here, since `contracts.yml` does not fire on `services/event-producer/**`), and the `tools/expect-failure.sh` floor-breach, verification tamper and planted `:adapter`-main-`:domain`-dependency steps;
- **`gradle-test`: a three-leg matrix `ubuntu-24.04`, `macos-14`, `windows-2022`**, `fail-fast: false`, JDK 25 on each leg from the same pinned archive mechanism as `producer-native.yml`, running the full `gradle -p services/event-producer check` (all four subprojects, :domain :application :adapter :launcher) on **all three legs**, so the lock, torn-line, golden-byte, node-id and the launcher's retention race and delete-under-lock tests (WP05 T032/T033, WP04 launcher e2e) run on every OS where lock, deletion and byte semantics differ; no leg narrows the task list. Each leg exports the live Python node id as `PRODUCER_LIVE_PY_NODE_ID` (`uv run --frozen python -c "from specify_cli.identity.project import generate_node_id; print(generate_node_id())"`) for WP03's live comparison, and also exports `REQUIRE_LIVE_NODE_ID=1` (the only place that makes a missing value a failure; `tests/ci/test_event_producer_workflow.py` asserts both exports are in `gradle-test` and the flag in no other job);
- `native-smoke` (three legs through `producer-native.yml`, then the matching stub wheel by path through the shared `.github/actions/producer-smoke` action, T008);
- terminal `event-producer-gate` (the `contracts-gate` pattern) whose `needs` lists **every** job and every matrix leg and fails unless each result is `success`: a missing or skipped leg is a failure, never a pass.
**Ledger sweep (class: a change-triggered job that reaches pytest is a duplicate suite execution unless it is a row of `AUTHORIZED_PER_CHANGE_SUITE_JOBS`, a closed ledger in `tests/architectural/test_no_duplicate_suite_execution.py`, whose stale-row check forbids pre-registering a job that does not exist yet).** Every job WP01 adds to `event-producer.yml` was checked: `verify-pins` (scripts, shell, gradle; no pytest), `gradle-test` (gradle; the `python -c` node-id export is not pytest), `native-smoke` (reusable workflow and shell action) and `event-producer-gate` reach no pytest, so WP01 adds no ledger row; `producer-native.yml` is `workflow_call` plus `workflow_dispatch` (a `NON_CHANGE_TRIGGERED_WORKFLOWS` row). The one later job that does reach pytest, `native-e2e`, gets its ledger row in WP10.
Register both workflows in `scripts/ci/fleet_verdict.py::PR_WORKFLOWS`, `ci-fleet-verdict.yml`, `tests/ci/test_fleet_verdict.py`; add the `NON_CHANGE_TRIGGERED_WORKFLOWS` ledger row for `producer-native.yml` (`workflow_call` plus `workflow_dispatch`; `tests/architectural/test_no_duplicate_suite_execution.py`). Registration and the workflow land in the same commit, which is its own `ci(registration)` commit (the closed-inventory test is red on any unregistered PR workflow). `tests/ci/test_event_producer_workflow.py` (new) asserts: no job's `run:` text contains `pytest` (WP10 narrows this to the single job `native-e2e`, whose ledger row it adds), the three-OS matrix, the `gradle_pin_check` step, the floor-breach step, the shared smoke action use, and the terminal gate's `needs` set.
**Validation**: `uv run --frozen pytest -q tests/ci/test_fleet_verdict.py tests/ci/test_fork_guard.py tests/ci/test_event_producer_workflow.py tests/architectural/test_no_duplicate_suite_execution.py tests/architectural/test_workflow_coherence.py`.

## Subtask T008: Shared smoke action and stub-wheel run on each OS

**Steps**: `.github/actions/producer-smoke/action.yml` is a composite action (shell only; `shell: bash`, which is Git Bash on Windows), the **single definition of the smoke** used by `native-smoke` here and by `smoke-wheels` in WP09. Inputs: `wheel` (path), `mode` (`binary` or `cli`), `expect` (`produced` or `binary-missing`), `command` (cli mode only). `binary` mode: fresh venv, install the stub wheel by path, `git init` a scratch repository, run the bundled binary by path in it with each request file (below) and assert exit 0, one JSON object on stdout and, on non-Windows, the exec bit. **Request files:** every file matching `services/event-producer/launcher/src/test/resources/**/smoke-request*.json` in the checkout, each run in turn; when none exists (WP01 to WP03, spike only) the action uses its inline default `{"v":1,"op":"produce","start_path":"."}`, which the T004 spike accepts. Later WPs add request files in their own owned resource directories (WP04: `protocol/smoke-request.json`; WP05: `retain/smoke-request-retain.json`; WP11 replaces the WP04 file with a valid-row request), so the same job exercises more of the real launcher as it grows with no edit of this action. `cli` mode (first exercised by WP09, which supplies `command`; not runnable before WP06 and WP11 are merged, so its only pre-merge evidence is the static test): the same venv install, then run the given CLI command in the scratch git repository and assert exit 0; **the publish log is found by a fixed lookup, not by asking the CLI** (no command reports a log path, NFR-006 bars absolute paths in replies): `find .kittify/events/publish -type f -name '*.jsonl'` under the scratch repository must yield **exactly one** non-empty file holding exactly one line (`wc -l`, no parsing), and stderr holds no `binary-missing` when `expect` is `produced` (or does when `expect` is `binary-missing`, in which case no log file is required). No Python file. `native-smoke` calls it in `binary` mode on each leg.
**Validation**: the three legs of the scratch pull request run (the stub wheel from T006 and this action are both in the pushed tip).

## Subtask T009: Latency and size numbers for AC-036

**Steps**: record cold and warm spawn times (per the protocol of the PASS block, median and p95) and binary size per OS in `research/wp01-feasibility.md`; these seed the relative limits WP10 sets.

## Subtask T010: Feasibility verdict

**Steps**: write `research/wp01-feasibility.md` (per leg: tool versions, floors measured vs pinned, timings, fallbacks tried, FFM configuration files, the scratch pull request **run ids and per-leg conclusions**, the runner access assumption, **verdict PASS or HALT**). No PASS without run ids. On HALT follow "On failure" above.

## Tests, markers, jobs

| New test file | Marker | Runs in |
|---|---|---|
| `tests/release/test_dist_set_platform_tags.py` | `fast` | shard (`tests/release`) |
| `tests/cross_cutting/packaging/test_producer_stub_wheel.py` | `integration` | packaging shard |
| `tests/architectural/test_build_glue_thin.py` | `architectural` | architectural tier |
| `tests/ci/test_event_producer_workflow.py` | `fast` | shard (`tests/ci`) |
| Java tests | n/a | `gradle-test` three-OS matrix |

Run `tests/architectural/test_fast_tier_marker_completeness.py` and `test_module_shard_registry.py`: a new test file with no explicit tier marker, or in no shard, fails or runs nowhere.

## Recorded out-of-map edits

None by WP01. Later editors of WP01 files (all listed in tasks-overview section 4): WP04 deletes `spike/**` (main and the spike test) and its metadata directory and changes the one `producerMainClass` line of `launcher/build.gradle.kts`; WP06 adds one path glob to `event-producer.yml`; WP08 empties the "not yet landed" list of `test_build_glue_thin.py`; WP10 adds the `native-e2e` job to `event-producer.yml` and `test_event_producer_workflow.py` and its ledger row to `tests/architectural/test_no_duplicate_suite_execution.py`; WP11 edits `pyproject.toml` (floor).

## Gates (plan 9)

`gradle -p services/event-producer check` (not `test`); `uv run --frozen ruff check` and `ruff format --check --force-exclude` on the Python glue; `make lint`; targeted pytest: `tests/contract/test_gradle_pin_check.py tests/contract/test_verify_pins.py tests/ci/test_fleet_verdict.py tests/ci/test_fork_guard.py tests/ci/test_event_producer_workflow.py tests/architectural/test_no_duplicate_suite_execution.py tests/architectural/test_pyproject_shape.py tests/architectural/test_build_glue_thin.py tests/architectural/test_workflow_coherence.py tests/release/test_dist_set_platform_tags.py tests/cross_cutting/packaging/test_producer_stub_wheel.py`. The build-glue pin is `tests/architectural/test_build_glue_thin.py` (created in T003, **the single enumerator**; the plan's thinness gate `test_producer_client_thinness.py` of WP06 cross-checks that this file exists and covers the plan 13.2 files). Its rules: for wholly owned glue (`hatch_build_producer.py`, later `check_dist_set.py`) a closed import allowlist (`argparse, hashlib` for file sha256 only, `json, os, pathlib, sys, zipfile, tarfile, typing, dataclasses, collections.abc`, `__future__`, plus `hatchling` for the hook) and no references to `uuid, ulid, datetime, time, secrets, random, jsonschema, spec_kitty_events` or envelope/id/clock/schema names; for edited existing files (`check_exact_install.py`, `gradle_pin_check.py`) the base-plus-allowed diff of names and imports; enumeration by filesystem walk; floor (glue files found, "not yet landed" list explicit); mutations: hook with `import uuid`, `check_dist_set.py` with `datetime.now`, `check_exact_install.py` with a `spec_kitty_events` import, a new function in `gradle_pin_check.py`, each red. `test_clock_import_ban.py` and `test_clock_call_ban.py` scan `scripts/` and `tests/`, so a raw `hashlib.sha256` in a test needs an inline `# noqa: TID251` justification. Each behaviour: test committed red first as its own commit, verified red on the base, green on the tip, revert-checked.

## Definition of Done

T001 to T010 done; baseline file committed (after the campsite); the three legs green on the scratch run with run ids cited; stub wheel installs and runs on each OS; verdict PASS recorded; new PR workflows registered; hook-off build output unchanged; floor-breach and tamper steps red when planted.

## Risks

GraalVM or Kotlin DSL on JDK 25 may fail a leg (HALT path). The macOS runner label and manylinux container are plan assumptions (R-12). `uv.lock` untouched here. The scratch pull request needs the orchestrator's push and the operator's authorisation.

## Reviewer Guidance

Check the HALT criteria were applied honestly (measurements and run ids, not assertions), that red-first commits exist, that the hook is inert when unset, that the campsite is the first commit, and that fleet-verdict registration matches the workflow trigger set.

## Implementation command

`spec-kitty agent action implement WP01 --agent claude --mission event-producer-reroute-01M4VDY9`
