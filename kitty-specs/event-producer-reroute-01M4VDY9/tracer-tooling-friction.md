# Tracer: tooling friction

Seeded at plan time (Standing Order 3); append during implementation, assess at close.

## Seen while planning

- `spec-kitty plan --json` was not needed to scaffold: `plan.md` and `research.md` were written directly into the existing mission directory. The plan template headings were not consulted for that reason.
- `hatchling` is not installed in the checkout venv (it lives only in the uv cache), so build-hook behaviour had to be read from source instead of run. Expect the same for any local wheel experiment: use `--no-isolation` with the pinned test extra.
- `contracts/tools/install_tools.py` unpacks only `.zip` and `.tar.gz`; it cannot install the events `.whl`, so the pinned fetch is a plain Gradle task.
- `tests/release/test_release_ci_ownership.py` pins the text and names of `release.yml` steps; restructuring the long "Verify wheel contents" step is therefore not a free campsite refactor.
- A new PR-triggered workflow edits `scripts/ci/fleet_verdict.py`, `ci-fleet-verdict.yml` and `tests/ci/test_fleet_verdict.py` (closed inventory). Easy to miss.
- Nothing in `.github/` runs the Contextive generator's `check`, although the spec calls it CI-enforced.
- `#3283` and `#3284` (baseline friction) are cited by the design pipeline but are not in this repository.

## To append

- Friction met running the targeted gate files, the native-image spike on each OS, and the stub-wheel build.
