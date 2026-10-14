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
