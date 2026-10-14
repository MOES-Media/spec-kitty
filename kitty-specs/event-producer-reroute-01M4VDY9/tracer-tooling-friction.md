# Tracer: tooling friction

Seeded at plan time (Standing Order 3); append during implementation, assess at close.

## Seen while planning

- `spec-kitty plan --json` was not needed to scaffold: `plan.md` and `research.md` were written directly into the existing mission directory. The plan template headings were not consulted for that reason.
- `hatchling` is not installed in the checkout venv (it lives only in the uv cache), so build-hook behaviour had to be read from source instead of run. Expect the same for any local wheel experiment: use `--no-isolation` with the pinned test extra.
- `contracts/tools/install_tools.py` unpacks only `.zip` and `.tar.gz`; it cannot install the events `.whl`, so the pinned fetch is a plain Gradle task.
- `tests/release/test_release_ci_ownership.py` pins the text and names of `release.yml` steps; restructuring the long "Verify wheel contents" step is therefore not a free campsite refactor.
- A new PR-triggered workflow edits `scripts/ci/fleet_verdict.py`, `ci-fleet-verdict.yml` and `tests/ci/test_fleet_verdict.py` (closed inventory). Easy to miss.
- Nothing in `.github/` runs the Contextive generator's `check`, although the spec calls it CI-enforced.
- `#3283` (venv-lock bootstrap cascade, cited in `.github/actions/warmup/action.yml`) and `#3284` (sanitation mission base failures, rows in its `issue-matrix.md`) are in this repository; the baseline method uses them (plan section 10). A new PR workflow is registered in WP01, not WP09.

## To append

- Friction met running the targeted gate files, the native-image spike on each OS, and the stub-wheel build.

## Seen while authoring tasks (2026-10-14)

- `spec-kitty agent tasks` (4.0.0rc7) has no `tasks-outline` or `tasks-packages` subcommand; they exist only as slash-command flows. `wps.yaml` and the WP prompt files were written by hand; `agent mission finalize-tasks` is the only CLI step. `agent tasks finalize-tasks` also exists.
- `tasks.md` is regenerated from `wps.yaml` ("do not edit directly"), so narrative (PR shape, coverage, external dependency) lives in `tasks-overview.md`.
- The requirement parser reads only `C-001` from the spec's inline constraint paragraph; `C-002` to `C-008` fail as `unknown_spec_id`. Acceptance criteria and gate ids are not requirement ids either, so the AC and G coverage tables are in `tasks-overview.md`.
- `INVALID_WP_OWNED_FILES_KITTY_SPECS`: a code WP cannot own mission research files; literal new files need `create_intent` in the WP frontmatter.
- Lane computation merges WPs whose `owned_files` globs overlap; combined with a dependency chain between them this yields `LANE_DEPENDENCY_CYCLE` (twice: `domain/src/**` vs a later `domain` test file; `gradle/**` vs `gradle/events-pin.properties`). Error text names lanes, not the overlapping paths.

## Seen while fixing the tasks review (round 1)

- `finalize-tasks --validate-only` reports every greenfield `owned_files` glob as "matches zero files" (warnings only); `create_intent` is the place for literal new files, and globs there are accepted.
- Splitting a WP (WP07 into WP07 and WP12) is cheap in `wps.yaml` but the prompt file slug of the original keeps its old name, because `prompt_file` is recorded in the `WPCreated` event; renaming would desynchronise the event log.
- `workflow_dispatch` workflows can only be dispatched once the file is on the default branch, so a new workflow has no pre-merge dispatch path; only a `pull_request` trigger (and a pushed branch) gives pre-merge evidence.
- `contracts/tools/verify_pins.py --workflow` is red on `release.yml` and `ci-nightly.yml` on the base commit (tag-pinned actions, non-prelude installs); it cannot gate WP09 as a whole-file check.
- `tests/architectural/test_no_duplicate_suite_execution.py` treats a plain branch `push` trigger as change-triggered, so a feasibility-only `push` trigger on `producer-native.yml` would need a fleet-verdict registration.
- `kernel.clock` is the single wall-clock door (`test_clock_import_ban.py`, `test_clock_call_ban.py` scan `src`, `tests` and `scripts`); a client-side `_now_utc` must call it.

## Seen while fixing the tasks review (round 2)

- A Java test class in a Gradle subproject can only see build outputs of that subproject: the events wheel fetch task applied in `:adapter` leaves nothing on the `:domain` test classpath, and `:domain` (java.base only) cannot depend on `:adapter`. Tests that read fetched artifacts belong in the subproject that applies the fetch script.
- `native-image` checks only reachable code, so a native-smoke job cannot validate reflection metadata of classes the launcher does not yet reach; the smoke action reads request files from the launcher test resources so each WP can widen reachability without editing the action.
- No CLI command reports a publish-log path (NFR-006 bars absolute paths in replies), so a smoke that asserts on the log has to find it by a fixed `find` under the scratch repository.

## Seen while fixing the tasks review (round 3)

- `tests/integration` and `tests/perf` are collected by directory outside any per-PR job (nightly `integration-next`, `integration-slice`), and `integration-next` is a member of `nightly-summary.needs`. A new test there that fails closed when a native binary is absent (here keyed on `CI`) would turn the release nightly red; the fail-on-missing switch has to be a flag only the building job sets.
- Gradle's `jacocoTestCoverageVerification` is skipped when a project has no execution data, so a floor-breach test aimed at an empty subproject stays green; the breach test needs a project that has code (a probe build sharing the convention script, with a positive control).
- A Gradle subproject's test classpath sees another subproject's tests only through `java-test-fixtures`; moving a test between subprojects therefore moves its shared fixtures' build wiring into the earlier WP's build files, which are settled once.
- `AGENTS.md` ties any `__init__.py` change to a version bump and a `CHANGELOG.md` entry, while the spec forbids assigning a version; the entry is owned by one WP and the bump is declared not performed.
