# Research: event-producer-reroute-01M4VDY9

Supporting detail for [`plan.md`](plan.md). Every path below was checked against this checkout at plan time; symbols are cited by name, never by line. Items that could not be verified are collected in section 12.

## 1 Evidence added by the plan phase (beyond carryover E1 to E15)

| # | Fact | Where |
|---|---|---|
| P1 | `release.yml` exists; the "PyPI Release" paragraph of `AGENTS.md` (and its `CLAUDE.md` symlink) still says it does not. | `.github/workflows/release.yml`, `AGENTS.md` |
| P2 | Release jobs: `nightly-gate` -> `build-release` -> `publish-pypi` -> `verify-pypi-installability`. `build-release` runs `tests/release`, `validate_release.py --mode tag`, `python -m build`, `check_shared_package_drift.py`, `check_exact_install.py`, the "Verify wheel contents" step (`WHEEL_FILE=$(ls dist/*.whl)`), `cyclonedx-py environment`, `twine check dist/*`, `sha256sum dist/*`, upload of one `release-distributions` artifact. `publish-pypi` uploads `dist/` through `pypa/gh-action-pypi-publish` (every file in `dist/`). | `release.yml` |
| P3 | `scripts/release/check_exact_install.py::locate_wheel` refuses more than one wheel. `tests/_support/shared_build_artifacts.py::published_build_artifacts` picks `wheels[-1]` of `spec_kitty_cli-*.whl`. `validate_release.py` has no wheel handling (grep: no `whl`, no `dist/`). | files named |
| P4 | `tests/release/test_release_ci_ownership.py` pins job and step names of `release.yml`: `test_release_wheel_gate_counts_charter_offering_and_skills` reads `jobs["build-release"]["steps"]` step "Verify wheel contents" and asserts substrings (`git ls-files src/charter/offering`, `find `, `wheel_check/charter/offering`, the skills pair); `test_release_workflow_classifies_hotfix_channel` runs step "Classify release channel" of BOTH `build-release` and `publish-pypi`; `test_restored_workflows_use_stock_runners` forbids `blacksmith`/`runner-group` text in `release.yml`. | that file |
| P5 | Fork-guard gate: every self-starting job of every workflow with a `schedule`/`push`/`workflow_run` trigger must open its `if:` with the parenthesised guard; `release.yml` is the only exempt workflow. A new workflow with `push`/`pull_request` triggers needs the guard on its root jobs. | `tests/ci/test_fork_guard.py` |
| P6 | `scripts/ci/fleet_verdict.py::PR_WORKFLOWS` is a closed set compared for equality with every workflow that has a `pull_request` trigger (`applicable_workflows` raises "PR workflow inventory changed"). `ci-fleet-verdict.yml` lists the workflow display names; `tests/ci/test_fleet_verdict.py` pins both. A new PR-triggered workflow therefore edits `fleet_verdict.py`, `ci-fleet-verdict.yml` and that test. | files named |
| P7 | `tests/architectural/test_no_duplicate_suite_execution.py` keeps a ledger `NON_CHANGE_TRIGGERED_WORKFLOWS` (workflows outside the per-change scan, one reason each; `module-tests.yml` is the `workflow_call`-only precedent). A new `workflow_call`-only workflow needs a row. `tests/architectural/_gate_coverage.py::WORKFLOW_FILES` lists only suite-running workflows; a Gradle-only workflow is not added. | files named |
| P8 | `contracts.yml` is the precedent for a Gradle workflow: its own `pull_request`/`push` path filters, SHA-pinned `actions/setup-java` (`temurin`, 21), Gradle installed by `contracts/tools/install_tools.py --pins <file> --only gradle` (sha256 verified before unpack), a terminal `contracts-gate` job, no pytest. `acceptance-jvm` pins JDK 25 (ADR 2026-10-01-2, amended) and is nightly-only. Both builds are Groovy DSL, have no committed wrapper jar, and commit a Gradle-generated `gradle/verification-metadata.xml` (`verify-signatures` false). `install_tools.py` unpacks only `.zip` and `.tar.gz` (`UNSUPPORTED_FORMAT` otherwise), so it cannot install a `.whl`. | files named |
| P9 | The router (`ci-router.yml`) selects by path groups; a path under no group is not routed (the `unmatched` catch-all covers `src/**` only). A top-level `services/event-producer/**` tree needs its own workflow, as `contracts/**` has. | `ci-router.yml` |
| P10 | `hatchling` 1.31.0 (the `[build-system]` pin): a build hook declared under `[tool.hatch.build.targets.wheel.hooks.custom]` is dropped from the active set when `enable-by-default = false` unless `HATCH_BUILD_HOOKS_ENABLE` (all hooks) or `HATCH_BUILD_HOOK_ENABLE_CUSTOM` is true; the custom hook raises if its script path is missing, but only when active. `build_data` honours `tag`, `pure_python`, `infer_tag` and `force_include`; an explicit `tag` skips inference (the inferred tag would carry a CPython ABI). Read from the installed hatchling source; hatchling is not in this checkout's venv (it is in the uv cache). | hatchling `builders/config.py`, `builders/wheel.py` |
| P11 | `tests/_support/shared_build_artifacts.py::default_wheel_sdist_builder` builds with `--no-isolation` (hatchling is a pinned test extra); `ci-quality.yml` `build-wheel` runs `uv build --wheel` and `clean-install-verification` installs `dist/spec_kitty_cli-*.whl`. With the hook disabled by default both keep producing the single pure wheel. | files named |
| P12 | `test_wheel_contains_only_known_packages` allows the prefixes `specify_cli/`, `doctrine/`, `charter/`, `kernel/`, `glossary/`, `mission_runtime/`, `runtime/`, `packs/built-in/`; a binary under `specify_cli/_native/` needs no prefix change. | `tests/cross_cutting/packaging/test_packaging_safety.py` |
| P13 | The `doctor` group auto-discovers `cli/commands/_*_doctor.py` siblings exposing `register(app)`: a new doctor command needs no edit to `doctor.py`. | `cli/commands/doctor.py::_auto_discover_doctor_siblings` |
| P14 | `.kittify/events/` is gitignored in this repo (`.gitignore`) and in consumer projects (`upgrade/migrations/m_2_0_9_state_gitignore.py`); `state/contract.py` declares each state surface (`glossary_fallback_events` is the neighbour) with root, git class and owner. | files named |
| P15 | The events wheel installed here (10.4.4) ships `spec_kitty_events/schemas/*.schema.json` and `spec_kitty_events/conformance/fixtures/**`. PyPI shows 10.4.6 as latest (uploaded 2026-10-13). The CLI floor is `>=10.4.4,<11`. | installed dist-info; PyPI JSON |
| P16 | GraalVM Community releases for JDK 25: `jdk-25.0.0` (Sep), `jdk-25.0.1` (Oct), `jdk-25.0.2` (Jan), then innovation drops (`graal-25.1.3` ... `graal-25.4.4.1.1`, Sep); archives are named `graalvm-community-jdk-<ver>_<linux-x64|macos-aarch64|windows-x64>_bin.{tar.gz,zip}`. | GitHub releases page, fetched at plan time |
| P17 | Sonar analyses `src` and `tests` only (`sonar.sources=src`); no workflow runs Bandit, pip-audit or mypy in CI (they are declared in the `lint` extra and `make typecheck` names two files). `commit-msg` and `markdownlint` end in `|| true`. Nothing in `.github/` runs `generate_contextive_glossaries.py`; only an integration-marked unit test of the generator exists. | grep of workflows, Makefile |
| P18 | A producer-written file type must be declared as a state surface (`state/contract.py`) and, for Python path literals, survive the path-literal gates (`tests/architectural/test_no_dead_src_path_literals.py`, `tests/audit/test_no_legacy_path_literals.py`). | files named |
| P19 | `kernel.locks` release truncates the sidecar and never unlinks it (guarantee G3); `read_lock_record` returns `None` for a missing, empty or malformed file and reads the bytes without taking the OS lock (on Windows a held mandatory lock refuses the read, so it also reports `None`); `LockRecord` carries `pid`, `started_at`, `host`, `version`; `STALE_AFTER_S_DEFAULT` is 60.0. The run-cursor lock is the per-run sidecar `<run_dir>/state.json.lock` (ADR 2026-10-08-1), taken through `kernel.locks.machine_file_lock`. | `src/kernel/locks.py`, `docs/adr/4.x/2026-10-08-1-serialise-run-dir-advance.md` |
| P20 | `.gitignore` has no `_native` entry (`git check-ignore` prints nothing for `src/specify_cli/_native/...`); wheel `packages` includes `src/specify_cli` and sdist `include` has `src/**/*`, so, if hatch honours `.gitignore` as reviewed, an untracked binary there would ship in the pure wheel and the sdist unless ignored. | `.gitignore`, `pyproject.toml` |
| P21 | `contracts/tools/gradle_pin_check.py` is the single pin-versus-build authority: a `Subproject` dataclass (pins, build, metadata, wrapper, candidates, required, plugin tool, job, workflows) with `_contracts_subproject` and `_acceptance_jvm_subproject`, a floor (`ZERO_COMPARISONS`) and `check_all`; `contracts/tools/verify_pins.py` takes `--pins` and repeatable `--workflow`, its default workflow glob is `contracts*.yml`. Tests: `tests/contract/test_gradle_pin_check.py`, `tests/contract/test_verify_pins.py`. | files named |
| P22 | `release.yml` has `workflow_dispatch` with a `tag` input; `RELEASE_REF` is a workflow-level `env` (tag for dispatch, `github.ref_name` otherwise) and `build-release` checks it out. A called workflow's checkout defaults to `github.sha`, which for a dispatch is the dispatching branch. | `release.yml` |
| P23 | The four census lists differ in shape: `_LANE_ORDER` (ordered five-step list, no `in_review`/`approved`), `_VALID_LANES` (eight lanes, no `in_review`), `_LEGACY_NOT_DONE_LANES` (a tuple with the non-member alias `doing`), `_PRE_APPROVED_LANE_VALUES` (four-member subset). `CANONICAL_LANES` is a literal in `src/specify_cli/status_lanes.py`, whose docstring says it avoids importing status orchestration. | files named |
| P24 | `status/adapters.py`: `fire_saas_fanout`, `fire_resolved_binding_fanout` and `fire_lifecycle_saas_fanout` hold three near-identical `try/except` ladders (`_FanoutHandlerBusyError`, `TimeoutError`, `Exception`) inside a loop over all registered handlers; `_handler_key(handler)` identifies a handler; `_saas_fanout_timeout_s` accepts any finite float (values at or below 0 run the handler inline). | `status/adapters.py` |
| P25 | `#3283` is cited in `.github/actions/warmup/action.yml` (venv-lock bootstrap cascade; one build per lockfile and rev; a restored `.venv` whose interpreter symlink dangles dies with exit 127); `#3283` and `#3284` have rows in `docs/reports/test-sanitation/assertive-test-suite-sanitation-01KZME3P/issue-matrix.md` (`#3284`: base 27 failures + 3 teardown errors, resolved/adjudicated by that mission). | files named |

## 2 Native image per platform (detail of plan section 2)

**Toolchain.** GraalVM Community JDK 25 (P16): the same Java line as `acceptance-jvm`, so one JDK major serves the repo. The exact release is chosen in WP01 from the `jdk-25.0.x` tags (the stable line; innovation drops move monthly) and recorded with the archive URL and sha256 in `services/event-producer/tools/pins.json`; the build fails when the running `native-image --version` differs from the pin. The same file pins Gradle 8.14.5 (the repo's current pin) for `contracts/tools/install_tools.py`, which is reused unchanged.

**Build flags (starting point, WP01 confirms).** `-O2`, `--no-fallback`, `-H:+ReportExceptionStackTraces`, `-H:IncludeResources` for the embedded schemas and registry, `--enable-native-access=ALL-UNNAMED` plus the Foreign Function and Memory API support flag for the `gethostname` downcall, `-march=x86-64-v2` on Linux (a portable CPU baseline: the default `native` would pin the CI runner's CPU), `-march=armv8-a` on macOS arm64. Reachability metadata for Jackson and the schema validator comes from the GraalVM reachability-metadata repository where available, else a committed `reflect-config` produced by the tracing agent during the contract tests.

**Per-OS recipe.**

| OS | Runner label | Specifics |
|---|---|---|
| Linux x86_64 | `ubuntu-24.04` | Build inside a pinned `manylinux_2_28` x86_64 container (image digest recorded in `pins.json`) so the glibc floor is 2.28; the wheel tag is the pinned floor of `dist-set.json` and the measured maximum `GLIBC_` symbol of the built binary is asserted not to exceed it (plan section 2, tag authority). Fallback if GraalVM cannot run in that image: static musl build (`--static --libc=musl`), tag `manylinux_2_17_x86_64.musllinux_1_1_x86_64`. WP01 chooses; the choice is a recorded decision, not an operator fork. |
| macOS arm64 | `macos-14` (pinned, not `macos-latest`) | `MACOSX_DEPLOYMENT_TARGET=11.0`; the tag is the pinned floor; `otool -l` `minos` of the built binary is asserted not to exceed it. arm64 executables require a code signature to run; the linker emits an ad-hoc one, so the binary must not be stripped or modified afterwards, and the smoke job runs `codesign --verify`. |
| Windows x86_64 | `windows-2022` | MSVC environment set up by a SHA-pinned action step before `native-image`; binary `spec-kitty-event-producer.exe`; tag `win_amd64`. |

**One job per OS, one assembling job.** Reusable workflow `.github/workflows/producer-native.yml` (`workflow_call`) holds the per-OS job (matrix over the three legs, `fail-fast: false`). Each leg: checkout, pinned JDK, Gradle install, `gradle :launcher:nativeCompile` under dependency verification, `producer-manifest.json` (binary sha256, toolchain version and archive sha256, source commit, OS, measured platform floor, native-image argument digest), upload. The workflow takes a required `ref` input and checks that ref out (P22). Callers: `release.yml` (job `build-producer`, release ref), `event-producer.yml` (PR and nightly, `github.sha`), nothing else. The assembling job is the existing `build-release`, which keeps its name (P4).

**Provenance and checksum continuity (FR-042, AC-032).** (1) Each leg records the binary sha256 in its manifest and in the uploaded artifact. (2) `build-release` downloads the three artifacts, fails unless every manifest `source commit` equals `git rev-parse HEAD` of its own checkout, and recomputes every sha256 before any wheel is built (a mismatch fails the job). (3) The hook copies the binary into the wheel and writes the same sha256 to `specify_cli/_native/PRODUCER.sha256`. (4) `scripts/release/check_dist_set.py --verify-binaries` opens every platform wheel, hashes the member and compares with the manifest (AC-032). (5) `publish-pypi` attaches a build-provenance attestation for the binaries and wheels (`actions/attest-build-provenance`, SHA-pinned at WP09; needs `attestations: write` and `id-token: write`, which the job already uses for trusted publishing). (6) The manifests ship as release assets. No bit-for-bit reproducibility is claimed.

**Unsigned binaries.** pip does not set the macOS quarantine attribute and does not add the Windows mark-of-the-web, so a pip-installed binary is not blocked by Gatekeeper or SmartScreen in the ordinary path; a user who copies the binary out of a browser download would be. Defender may scan the first run, which the first-run row of AC-036 measures. The release notes state "unsigned in this mission" (F-5 trim).

## 3 Wheel machinery, file by file (plan section 3)

| File | Change | Gate(s) it touches |
|---|---|---|
| `pyproject.toml` (WP01 hook table; WP11 events floor) | add `[tool.hatch.build.targets.wheel.hooks.custom]` with `path = "scripts/release/hatch_build_producer.py"`, `enable-by-default = false`; no change to `packages`, `artifacts`, `force-include`, `exclude`, `[build-system]`. The sdist `include` list is NOT widened: platform wheels are built from the checkout, never from the sdist. | `test_pyproject_shape.py`, `test_packaging_safety.py`, `uv lock --check` (no dependency change, so the lock is unchanged), `release-readiness.yml` (path filter includes `pyproject.toml`) |
| `scripts/release/hatch_build_producer.py` (new, WP01) | `BuildHookInterface` subclass. `initialize(version, build_data)`: read `SPEC_KITTY_PRODUCER_BINARY`, `SPEC_KITTY_PRODUCER_PLATFORM_TAG` (both required when the hook is active, else raise; the caller reads the tag from `dist-set.json`); set `build_data["pure_python"] = False`, `build_data["tag"] = "py3-none-<tag>"`, `build_data["force_include"][<binary copy>] = "specify_cli/_native/<name>"`, plus the `PRODUCER.sha256` file. On non-Windows tags the copy is chmod-ed `0755` first: `actions/upload-artifact`/`download-artifact` do not keep permission bits, and hatchling's `normalize_file_permissions` records 755 only when the file stat has the owner exec bit. No other logic. Python build glue (plan section 13, fork F1). | `ruff` (the `scripts/` tree is linted), TID251 |
| `scripts/release/dist-set.json` (new, WP01: the three platform entries only) and `scripts/release/check_dist_set.py` (new, WP08, which extends the json additively) | `dist-set.json` is the pinned expected list: `sdist` x1, `py3-none-any` x1, `py3-none-manylinux_2_28_x86_64` (or the WP01 tag), `py3-none-macosx_11_0_arm64`, `py3-none-win_amd64`. The script lists `dist/`, fails on any missing or extra file, and with `--verify-binaries` re-hashes each platform wheel's binary and asserts the member's zip external-attribute mode is executable for the Linux and macOS wheels (mutation: a 0644 member is red). Mutation: remove one wheel -> red. `dist-set.json` is the single authority for the three platform tags. | AC-037, AC-024, AC-032, G-12 |
| `scripts/release/check_exact_install.py` | `locate_wheel(dist_dir, package_name, *, wheel_tag=None)`: with a tag select that wheel, without one keep today's "exactly one" rule; new `--wheel-tag` option. | `tests/release/test_check_exact_install.py` (extend: tagged selection; untagged multi-wheel still refuses) |
| `.github/workflows/release.yml` | see plan 3.3. | `test_release_ci_ownership.py`, `test_fork_guard.py` (exempt), `test_no_duplicate_suite_execution.py` (exempt) |
| `.github/workflows/producer-native.yml` (new, `workflow_call`) | per-OS native build. | `test_no_duplicate_suite_execution.py` ledger row; stock-runner text check (no `blacksmith`) |
| `tests/_support/shared_build_artifacts.py` | add a fixture variant `platform_wheel_artifacts` that builds a platform wheel with the hook enabled and a stub binary; `published_build_artifacts` untouched (hook-off build stays the single pure wheel). | `tests/_support/test_shared_build_artifacts.py` |
| `tests/cross_cutting/packaging/test_packaging_safety.py`, `test_package_bundling.py` | add: platform wheel has exactly one binary at `specify_cli/_native/`, with the executable bit even when the staged source is mode 0644 (the artifact round trip); `packs/internal/` absent; the test **plants a stub file untracked in `src/specify_cli/_native/`** and asserts the sdist and the hook-off wheel contain no binary and build with no env (AC-038), the hook-on wheel carries it, and `git check-ignore` names the `.gitignore` entry. A CI checkout has no binary in `_native/`, so without the planted stub these assertions would pass vacuously. WP01 owns the minimal version, WP08 the rest. | G-1, G-7, G-10 |
| `.gitignore` (WP01) | add `src/specify_cli/_native/` (P20) | AC-038 |
| `RELEASE_CHECKLIST.md`, `scripts/release/README.md`, `CONTRIBUTING.md` "What the Release Workflow Does" | describe the new stages and the set check. | `tests/release/test_release_ci_ownership.py` (asserts the checklist names `release.yml`) |

**Behaviour of the other build paths.** `uv sync` and editable installs never enable the hook (default off), so `src/specify_cli/_native/` is absent and the client reports `binary-missing` unless a developer copies a locally built binary there (the directory becomes gitignored by this mission's `.gitignore` entry; it was not before, P20). `python -m build` without the two env vars yields the sdist and the pure wheel as today.

**Tag preference.** pip and uv prefer the most specific compatible tag, so a `py3-none-<platform>` wheel wins over `py3-none-any` where it matches; an old pip that cannot read `manylinux_2_28` falls back to the pure wheel and degrades to `binary-missing`, which is the stated contract.

## 4 Protocol shapes (plan section 5)

Request (stdin, one UTF-8 JSON object, then EOF):

```json
{"v":1,"op":"produce","deadline_ms":1500,"start_path":"<path>","slot":"status","seam_args":{},"row":{}}
{"v":1,"op":"retain","deadline_ms":20000,"start_path":"<path>","dry_run":true,"policy":{"keep_runs":"20","max_age_days":null}}
```

`seam_args` and `row` are the seam's keyword arguments and the persisted row, serialised without interpretation (`json.dumps` with `default=str` is the only transformation, so serialisation never raises). `policy` carries the raw `ledger.retention` values exactly as read from `.kittify/config.yaml` (scalars of any type, or absent keys).

Reply (stdout, exactly one UTF-8 JSON object, LF, then exit 0):

```json
{"v":1,"op":"produce","outcome":"produced","event_id":"01J...","type":"ai.spec-kitty.lane.transitioned.v1"}
{"v":1,"op":"produce","outcome":"skipped_duplicate","event_id":"01J...","type":"..."}
{"v":1,"op":"produce","outcome":"refused","reason_class":"rejected","reason":"id-not-ulid","event_id":"01J...","detail":"..."}
{"v":1,"op":"produce","outcome":"produced","notice":{"kind":"torn-line","path":".kittify/events/publish/missions/<id>.jsonl","line":42}}
{"v":1,"op":"retain","dry_run":true,"eligible":[{"path":"...","reason":"closed-mission-outside-limits"}],"kept":[{"path":"...","reason":"mission-open"}],"deleted":[],"policy_error":null}
```

Rules: the reply never carries the row, an absolute path, or a stack trace; `reason` is a closed vocabulary (`root-unresolvable`, `id-not-ulid`, `time-unparseable`, `schema-invalid`, `type-unknown`, `clock-overflow`, `nodeid-unavailable`, ); `detail` is one repo-relative line. Exit codes: 0 a reply was written (including `refused`); 64 request unreadable or `v` unsupported (no reply is written, so the client records `crash`); any other non-zero, a signal, or empty stdout is `crash`. stderr is discarded by the client (it may carry JVM or native-image diagnostics and absolute paths, NFR-006). Client reads at most 1 MiB of stdout. The `deadline_ms` budget is enforced by the producer through a watchdog thread that exits with code 75 after cleanup, so no child outlives an abandoned seam call.

**Fault mapping table (client).**

| Observation | Class |
|---|---|
| no executable at the package-relative path | `binary-missing` |
| spawn error (permission, exec format) | `binary-missing` |
| non-zero exit, signal, empty stdout | `crash` |
| client timeout elapsed (child terminated by handle) | `timeout` |
| stdout not one JSON object, wrong `v`/`op`, missing `outcome`, unknown `reason_class` | `malformed-reply` |
| reply `refused` with `reason_class` `clock-overflow` or `rejected` | that class |
| seam raised `_FanoutHandlerBusyError` for a client handler | `busy` |
| seam raised `TimeoutError` (the client handler outlived the seam bound) | `timeout` |

The seam loop is shared by every registered handler (P24). The busy and timeout branches call `record_fault` only when `_handler_key(handler)` equals the key of a client handler, so a slow Zeitgeist handler (a real, bounded 10 s case with drain on) records no producer fault and prints no "event not produced" warning; the AC-006 tests add that case. The client handlers never raise, so the `except Exception` branch needs no change.

**Client timeout rule.** `effective_client_timeout(bound)`: `bound <= 0` (the seam runs handlers inline) gives the 3.0 s default; otherwise `min(3.0, max(bound / 2, bound - 1.0))`. Worked values: 0.5 gives 0.25, 1.0 gives 0.5, 1.5 gives 0.75, 2.0 gives 1.0, 4.0 gives 3.0, 10.0 gives 3.0. The result is positive and strictly below every positive bound, and continuous in the bound. `deadline_ms` is the value in whole milliseconds, at least 1. `adapters.py` injects its resolver (`ensure_producer_client(bound_resolver=_saas_fanout_timeout_s)`), so the client never imports a private name from `adapters` (no import cycle, SR-2 intact).

## 5 Clock state, lock, kill safety (plan section 7)

- Paths (all under `git rev-parse --git-common-dir`, resolved by reading `.git`/`commondir` files, not by spawning git): `<common>/spec-kitty/event-producer/clock.json` (state), `<common>/spec-kitty/event-producer/clock.lock` (lock file, zero bytes, never replaced).
- State format: `{"v":1,"nodeid":"<12 hex>","clock":<int>}`. One counter per `nodeid` is one counter (the file holds the single local node).
- Operation under one exclusive `FileChannel.lock()` on `clock.lock`: read state (missing or unreadable -> recover from the publish log: highest `lamportclock` over every publish-log file, skipping torn fragments); `next = max(state, recovered) + 1`; refuse above 2^31-1 (`clock-overflow`); write state to a temp file in the same directory, `fsync`, atomic move over `clock.json`; compute dedupe and torn-line state; append the line to the publish log, `fsync`; release. Kill points: before the state move (no advance), after it and before the append (a gap), after the append (row present). Never a reused value, because the state is written first.
- Semantics: Linux and macOS lock through `fcntl` byte-range locks (advisory, owned by the process; closing any descriptor of the lock file releases them, so the process opens the lock file once and keeps one channel); Windows through `LockFileEx` (mandatory, byte-range). Inside one JVM a second channel on the same file raises `OverlappingFileLockException`, which cannot occur because each producer process handles one row. Lock wait is bounded by `deadline_ms`; on expiry the producer exits through its deadline watchdog and the client records `timeout`. Network file systems are not supported; the doctor note says so.
- Worktrees: the lock and state sit under the common dir, so the primary checkout and every linked worktree share them. Separate clones share only the `nodeid` (accepted residual).
- State loss: next stamp exceeds the publish log's highest clock. State never pruned: retention never lists `clock.json` or `clock.lock` as candidates (tested).

## 6 Local-tier file placement and "run" (plan section 8)

| File | Path | Writer | "Run" for retention | Age |
|---|---|---|---|---|
| Publish log | `.kittify/events/publish/missions/<missionid>.jsonl`; project-scoped rows `.kittify/events/publish/project-<project_uuid>.jsonl` (canonical root) | producer | one Mission (all its rows); the project file counts as one run | newest row `time` in the file, else file mtime |
| Fault log | `.kittify/events/faults/<YYYY-MM-DD>.jsonl` (UTC date of the fault's `at`) | client | one UTC day | the date in the name |
| Run journal | `.kittify/runtime/runs/<run_id>/run.events.jsonl` (existing, Python-written) | engine | one run | newest row `timestamp` |

**Lock facts (FR-029).** The fact is the sidecar's content, never its presence (P19). Python-owned sidecars are the per-run `state.json.lock` of each run directory. A run is locked iff the sidecar holds a non-empty parsable `LockRecord` (`schema_version`, `pid`, `started_at`, `host`, `version`) younger than 60 s; an empty or malformed sidecar is no lock; an unreadable one keeps the run. Java reads the bytes only: `FileChannel.tryLock` on Linux and macOS and Python's `fcntl.flock` are different advisory-lock families, so a Java lock test would report a live Python holder as free. `clock.lock` (zero bytes, always present) is never a lock fact. AC-013 fixtures: a sidecar released by the real Python primitive (empty, eligible), a live record (kept), a stale record (eligible), an unreadable sidecar (kept).

**Retain versus produce.** `produce` holds `clock.lock` across stamp, dedupe and append, so a delete between its open and its append would unlink the inode and lose a row reported produced (on Windows the delete fails with a sharing violation). `Retain` therefore takes `clock.lock` (wait bounded by `deadline_ms`) around each candidate publish-log delete, re-checks eligibility, file length and newest row under it, and maps a lock timeout or a failed delete to kept-with-reason (`busy`, `delete-failed`). Opening `clock.lock` is not opening a candidate for write. Fault-log candidates are old day files (the current day is inside the window) and run journals have one writer per live run, which eligibility excludes. Test: two processes, `produce` against `retain --apply` on an eligible file.

A whole file is deleted only when eligible. The unit of "newest `keep_runs`" is the run as above; for a Mission the file is the run. Contingency: if AC-036 shows the per-row dedupe scan is slow on very large Mission files, segment the publish log by the UTC date of the row's own `time` (a pure placement change; the run unit stays the Mission and the retention code needs only a glob).

"Mission open": a work package is open when its latest lane is neither `done` nor `canceled`, where latest means the row with the greatest `at` for that `wp_id` (file order breaks ties), read from `kitty-specs/<slug>/status.events.jsonl` in the canonical checkout and, when a coordination worktree exists, from its copy as well; the Mission is open if either copy says so. A Mission with an unreadable, truncated, or non-parseable ledger, or no ledger found, is kept ("facts unavailable"; F-6). A publish-log file whose Mission directory is not found in the canonical checkout is kept. The reader is a 150-line read-only adapter behind a port, replaced wholesale by #6193's model.

## 7 Gate-implication audit

Method: for each touched file or concept, grep `tests/architectural`, `tests/ci`, `tests/cross_cutting`, `tests/contract`, `tests/release` (and a wider grep where noted), then read the head of each hit. Specific files only; the bare directories are never run as a gate (charter `NO_FULL_HEAVY_SUITES_IN_MISSION`). "Action" says what the WP does about it.

| Touched | Implicated file | Why | Action |
|---|---|---|---|
| `status/adapters.py` | `tests/architectural/test_hosted_drain_gate.py` | drain gate scan (caller-side fan-out pre-flight is gated behaviourally, not structurally, by `tests/integration/test_hosted_posture_matrix.py`) | read; extend the behavioural matrix for "producer fires with drain off"; confirm the structural scan is unaffected |
| same | `tests/architectural/test_egress_consent_boundary.py` | sink scan over `src/`: a `subprocess` spawn is not a network sink, but the file is allowlist-keyed | run; the client must add no HTTP vocabulary |
| same | `tests/architectural/test_integration_boundary.py` | CORE (`status/`, `core/`) must not import INTEGRATION; allowlist length pinned to 0 | client imports only stdlib, `kernel`, `core` |
| same | `tests/architectural/test_status_module_boundary.py` (SR-2) | nothing outside `specify_cli.status` may import a `specify_cli.status.*` submodule | the doctor sibling uses facade names, so `status/__init__.py` `__all__` gains the client's public names |
| same | `tests/architectural/test_runtime_emitter_seam.py` | single `RuntimeEventEmitter`; bridge call-site needles; "no live producer wired" ADR statement | read before editing `runtime_moments.py` or the Protocol; the producer is registered at the status seam, not as a second emitter |
| same | `tests/architectural/test_no_dead_modules.py`, `_baselines.yaml` (`zeitgeist_bridge`), `dead_symbol_allowlist.yaml` | module and symbol liveness; adapters entries | new modules need a non-test caller (the import tail / doctor sibling); no baseline growth |
| same | `tests/status/conftest.py`, `tests/status/test_conftest_restores_zeitgeist_registry.py`, `tests/status/test_drain_fanout.py`, `tests/status/test_saas_fanout_timeout.py`, `tests/status/test_emit_fanout_after_adapter.py` | the fixture restores only the Zeitgeist wiring; drain tests assert silence with drain off | fixture also restores the client; drain tests re-pinned (producer fires, Zeitgeist stays silent) |
| `status/emit.py`, `coordination/outbound.py`, `status/lifecycle_events.py`, `retrospective/lifecycle_events.py`, `decisions/emit.py` | `tests/architectural/test_status_events_writes_gate.py`, `test_mission_write_discipline.py`, `test_no_write_side_rederivation.py`, `test_ledger_floor.py` | write-site ledgers; mission-id/root re-derivation; `drain_posture`/`ledger_posture` referenced only in named places | seam calls add no write site and no `drain_posture` reference; the client resolves the root only through `resolve_canonical_root` |
| `runtime/next/_internal_runtime/engine.py::_append_event` | `test_mission_write_discipline.py` rule 1 (run-log rewrite), `tests/contract/test_identity_contract_matrix.py` (`run.events.jsonl`), `tests/contract/spec_kitty_events_consumer/test_consumer_contract.py` | append-only stays; journal shape | stamping adds two keys; update the matrix expectation |
| CLI (`doctor` sibling) | `tests/architectural/test_completion_manifest_freshness.py`, `test_docs_cli_reference_parity.py`, `tests/docs/test_build_cli_reference.py`, `tests/architectural/test_destructive_op_routing.py` | completion manifest and CLI reference must match the live Typer tree; destructive-op literals | regenerate `src/specify_cli/_completion_manifest.json` (`python -m specify_cli.completion --regenerate`) and `docs/api/cli-commands.md`; the Python side deletes nothing |
| `state/contract.py` (three new surfaces) | `tests/architectural/test_lifted_root_gitignore_contract.py`, `tests/specify_cli/test_state_contract.py`, `tests/state/` | state-surface registry vs gitignore and roots | register publish log, fault log, clock state (a root outside the work tree needs a new `StateRoot` member or a `GIT_COMMON_DIR` note; decided in WP06) |
| path literals | `tests/architectural/test_no_dead_src_path_literals.py`, `tests/audit/test_no_legacy_path_literals.py` | new `.kittify/events/...` literals in `src/` | literals live in one constants module |
| exempt markers | `tests/architectural/test_lifted_lint.py`, `scripts/lint_canonical_producers.py`, `scripts/canonical_producer_lint_baseline.txt` | marker semantics, baseline | markers cleared per data-model section 8; baseline not widened; persisted-line mode added to the lint |
| lane lists | `tests/specify_cli/migration/test_rebuild_state.py` (the synthesized event chain from `_LANE_ORDER`/`_VALID_LANES`), the acceptance tests of `_LEGACY_NOT_DONE_LANES`, `tests/specify_cli/status/test_lane_uninitialized.py` (its docstring still says `_VALID_LANES` derives from `Lane`); `tests/architectural/` names no census list | P23 | first task of WP07 is the census (FR-034); characterisation tests per list (section 14); all three named in WP07's blast radius |
| `pyproject.toml` | `tests/architectural/test_pyproject_shape.py`, `test_shared_package_boundary.py`, `test_layer_rules.py`, `tests/cross_cutting/packaging/*`, `tests/release/test_validate_release.py`, `test_pinning_inventory_fresh.py` (`pinning_rule_inventory.json`) | wheel `packages` inventory, dependency ranges, release metadata, pin inventory | hook table only; floor bump of `spec-kitty-events` also touches `uv.lock`, `test_events_dependency_floor_rejects_pre_v10_contract`, the pin inventory, `.kittify/release/shared-package-compatibility.json` and `check-spec-kitty-events-alignment.yml` |
| `release.yml` and release scripts | `tests/release/test_release_ci_ownership.py`, `test_check_exact_install.py`, `test_release_prep.py`, `tests/ci/test_fork_guard.py`, `tests/architectural/test_no_duplicate_suite_execution.py`, `tests/architectural/_workflow_jobs.py` consumers | P4 to P7 | keep job and step names; add ledger rows |
| new `event-producer.yml` and `producer-native.yml` | `tests/ci/test_fleet_verdict.py` (asserts `applicable_workflows == PR_WORKFLOWS` against the real tree), `scripts/ci/fleet_verdict.py`, `ci-fleet-verdict.yml`, `tests/ci/test_fork_guard.py`, `tests/architectural/test_no_duplicate_suite_execution.py` (`NON_CHANGE_TRIGGERED_WORKFLOWS`), `tests/ci/test_contracts_workflows.py` (shape reference only) | P5 to P7 | WP01, the WP that creates the workflows, registers both and adds the ledger row for `producer-native.yml`; guard the root jobs |
| `services/event-producer/tools/pins.json`, its workflows | `tests/contract/test_gradle_pin_check.py`, `tests/contract/test_verify_pins.py`, `contracts/tools/gradle_pin_check.py`, `contracts/tools/verify_pins.py` | P21 | third `Subproject` in the existing authority with planted mismatches and a floor on compared values; `verify_pins.py --pins ... --workflow ...` for the new workflows; both test files run in the baseline and in WP01 |
| `tests/_support/shared_build_artifacts.py`, hook | `tests/cross_cutting/packaging/test_packaging_safety.py`, `test_package_bundling.py`, `.gitignore` | P20 | see section 3 |
| Contextive / glossary | `tests/cross_cutting/encoding/test_contextive_traceability.py` (generator unit tests), `docs/context/*.md`, `.kittify/traceability/contextive-map.yaml`, `src/specify_cli/.contextive/*.yml` | generated files must match | add terms to `docs/context/system-events.md`, run `generate` then `check`; add one targeted test that runs `check` (no CI step does today, P17) |
| import boundaries | `ruff check --select TID251` (`import-linter` job) | banned APIs (raw `hashlib`, etc.) | the client uses none; Python mints no ids |
| `docs/adr/4.x` | `docs/adr/4.x/index.md`, docs freshness and spelling gates (`scripts/docs/check_spelling`, `check_changelog_style`) | ADR inventory, spelling | update index; run both docs gates |
| `AGENTS.md` | `tests/architectural/test_no_legacy_terminology.py` and CLI-snippet parity | wording | docs-only campsite edit |

## 8 Enforced CI gates considered (plan section 9 summary)

Verified against `.github/workflows` in this checkout.

| Gate | Where | Included | Reason |
|---|---|---|---|
| `ruff check .` and `ruff format --check .` | `ci-router.yml` `ruff` job (also `ci-quality.yml` `lint`) | yes (hard) | all new and edited Python; `--force-exclude` per-file runs |
| `ruff check --select TID251` | `ci-router.yml` `import-linter` | yes | client and hook |
| mypy | not run in CI (`Makefile` `typecheck` names two files) | run locally on the new modules, not a gate | charter says `mypy --strict`; flagged in drift |
| `uv lock --check` | `ci-router.yml` `uv-lock`, `ci-quality.yml` | yes | the floor bump changes `uv.lock` |
| `spec-kitty regen --check` | `ci-router.yml` `regen-check` | yes | glossary or command-surface regeneration side effects |
| layer rules and pyproject shape | `ci-router.yml` `layer-rules` | yes | new modules, hook table |
| terminology guard | `ci-router.yml` `terminology` | yes | new prose |
| docs lint (spelling, changelog style) | `ci-router.yml` `docs-lint` | yes | ADR, glossary, CHANGELOG entry |
| architectural fast and heavy | `ci-router.yml` | CI owns; locally only the files in section 7 | `NO_FULL_HEAVY_SUITES_IN_MISSION` |
| module matrix | `ci-modules.yml` | CI owns; locally the owning test directories (`tests/status`, `tests/specify_cli/status`, `tests/runtime`, `tests/release`, `tests/cross_cutting/packaging`) by file | blast radius |
| diff-cover >= 90% | `ci-aggregate.yml` | yes | Python changed lines |
| SonarCloud | `sonar-pr` (informational), `sonar.yml` | reported, not gating; Java is outside `sonar.sources` | said in the PR |
| commit message lint, markdownlint | `ci-router.yml` | run locally; both end `|| true` in CI | advisory in CI |
| Bandit, pip-audit | declared in `lint` extra, invoked nowhere | not included | no enforcing job exists; Java dependency verification (G-13) is the new supply-chain gate |
| `release-readiness.yml` | PR paths include `pyproject.toml`, `scripts/release/**` | yes (runs by path) | touched |
| `ci-windows.yml` | Windows-critical, path filtered | not extended | cannot prove a built wheel (carryover G-3) |
| `contracts.yml` | path-filtered | not triggered (no `contracts/**` change) | the new workflow reuses its installer unchanged |
| `check-spec-kitty-events-alignment.yml` | drift check | yes at the floor bump | `pyproject.toml` events range changes |

## 9 Baseline method (plan section 10)

1. Before the first change, from a detached scratch worktree of the mission's base commit (`git worktree add --detach <scratch> <base-sha>`, created and removed by the orchestrator), run each gate file named in plan section 9 and the owning test directories' fast tiers once with `.venv/bin/python -m pytest <files> -q -p no:cacheprovider`, each file as a separate invocation so a red file is attributed to a file.
2. Record `kitty-specs/event-producer-reroute-01M4VDY9/research/baseline-red.txt`: command, base sha, per-file passed/failed counts, failing node ids. (`ruff.toml` excludes `kitty-specs/*/research/**` from lint.)
3. **#3283** (P25) is the venv-lock bootstrap cascade of the shared test environment. A failure of that shape, a shared test-venv lock timeout or `.venv/bin/python` missing with exit 127, is environmental: re-sync (`uv sync --frozen --all-extras`, the stale-venv rule of `AGENTS.md`), rerun the file once alone; a second identical failure is recorded as red. **#3284** (P25) is a tracked issue (the sanitation mission's base failures, resolved or adjudicated there); a red node attributable to it is recorded "known red, tracked: #3284". No node ids are seeded: the issue matrix lists none, so the captured list is the source.
4. After each WP, a red node id is **pre-existing** when it is in the baseline list with the same failure text, **introduced** otherwise. Only introduced red is the WP's to fix. Each other distinct pre-existing red is reported to the operator, who opens the issue the charter requires before work continues past it (the agent cannot write to GitHub).
5. Known-P0 reproductions (`p0_repro`) are not run locally (AGENTS.md).

## 10 Alternatives considered

| Topic | Chosen | Rejected | Why |
|---|---|---|---|
| Wheel tags | explicit `py3-none-<platform>` from the hook | `infer_tag` | inferred tag carries a CPython ABI; one wheel must serve every Python >= 3.11 |
| Where wheels are built | assembling job builds all platform wheels from the downloaded binaries | each OS builds its wheel | one place for set check, checksum check and `twine check`; hatch is pure Python |
| Hook enablement | env var, default off | always-on hook with a no-op branch | an unconditional hook breaks `uv sync`, editable and sdist-to-wheel builds (C-007) |
| Events artifact | pinned wheel fetched by a plain Gradle task with sha256 check | git submodule, copied schemas, Maven artifact | charter no-vendoring rule; no Maven artifact exists |
| Client placement | `status/` beside `zeitgeist_bridge.py` (routed by the existing `status` group) | new top-level `specify_cli/event_producer/` | a new subpackage falls into the router's catch-all and needs a new group |
| Dev override of the binary | none in production code; tests patch `locate_binary`; developers copy a built binary into `_native/` (gitignored by this mission, P20) | env var naming the binary | an env-selected executable on every command is an avoidable hazard, and a bad value would be a de-facto opt-out (OD-OPTOUT) |
| CI home of Java gates | new `event-producer.yml` (the `contracts.yml` pattern) | add jobs to the router | the router is src-glob driven and heavily pinned |
| ADR | amendment of 2026-10-13-2 for D-1 to D-3, new ADR for the delivery decision | one new ADR superseding | one authority per decision |

## 11 #6164 interaction, hunk by hunk (plan section 12)

Hunks this mission makes in `status/adapters.py`, each in its own commit: (A) three `fire_*` drain gates removed and the `hosted_posture` import dropped; (B) `row` and fault-recorder calls in the busy and timeout branches; (C) `ensure_producer_client()` plus the import tail registering it outside the `moment_handlers_disabled_reason` branch; (D) `reset_handlers` docstring and the test fixture call. Conflicts if #6164 lands first: (A) and the `zeitgeist_bridge` import block (#6164 deletes both; take theirs, our commit becomes empty), the import tail (take theirs, re-add (C) beneath it). Conflicts if this mission lands first: #6164 deletes the Zeitgeist registration lines we left; no change of ours remains in them. The other silencers named in FR-002 sit outside `adapters.py`: `RuntimeMomentProducer._publish` (`events/runtime_moments.py`) and the status test fixtures; #6164 is expected to touch the first only if it removes `hosted_posture`, in which case our deletion is a no-op.

## 12 Claims not verified

- GraalVM: the exact `jdk-25.0.x` build, its sha256, flag spelling for the FFM support and `-march` values, and whether GraalVM runs in the pinned `manylinux_2_28` image are WP01 outputs, not facts.
- Action versions for the MSVC environment step and the attestation step; WP09 pins them by SHA.
- Whether `native-image` honours `MACOSX_DEPLOYMENT_TARGET`; the measured `minos` is the source of truth.
- That the workflow-level `env` context is unavailable in a job-level `with:` of a reusable-workflow call (GitHub's context-availability table, not exercised here); the plan repeats the `RELEASE_REF` expression from the `github` and `inputs` contexts and WP09 confirms.
- That hatchling excludes a `.gitignore`d file reached through the wheel `packages` and the sdist `include` (review finding; not executed here). The planted-stub packaging test is the check.
- That the Windows `gethostname` of Python and the FFM downcall agree on every configuration: the per-OS golden test (AC-039) is the check.
- The final names of the events repo's schema files and registry file: that repo decides.
- Whether `tests/architectural/test_pyproject_shape.py` objects to a `hooks` table: to be run in WP01 (its visible assertions concern `packages` and dependencies).
- That `spec-kitty plan --help` scaffolds nothing needed: plan.md was written directly; the command was not run.

## 13 `release.yml` step list and gate map (plan sections 3.3 and 3.4)

1. New job `build-producer` (`uses` the reusable workflow with the release `ref`; `needs: [nightly-gate]`; same `if:` as `build-release` for the waiver case).
2. `build-release` keeps its name and every step; adds `needs: [nightly-gate, build-producer]`. After "Build distributions": download the three producer artifacts; recompute sha256; check every manifest `source commit` against `git rev-parse HEAD`; build each platform wheel with `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true` and the two env vars (`python -m build --wheel --outdir dist`); `check_dist_set.py` (set equals `dist-set.json`; `--verify-binaries`); `check_exact_install.py --wheel-tag py3-none-any` and for the Linux tag; "Verify wheel contents" becomes a loop over `dist/*.whl` keeping the substrings the test pins; `twine check dist/*` and `sha256sum dist/*` already glob; SBOM adds `sbom-producer.cdx.json` (Gradle CycloneDX plugin plus the toolchain component, G-5); upload adds the manifests and the producer SBOM.
3. New job `smoke-wheels` (matrix of the three OS labels, `needs: build-release`): download `release-distributions`, fresh venv, `pip install` the matching platform wheel by path, run the smoke script (one valid row appended, `binary-missing` absent), install the pure wheel on one leg to prove the fallback (G-2, G-3).
4. `publish-pypi` becomes `needs: [build-release, smoke-wheels]` with the `!cancelled() && results == success` condition extended to both; it re-runs `check_dist_set.py` on the downloaded artifact first and adds the attestation step. A failed or cancelled leg anywhere blocks publish (G-9, Standing Order 9); no `continue-on-error` in any new stage.
5. `verify-pypi-installability` gains the platform wheel tag on Linux only.

**G map.** G-1 hook test and packaging test; G-2 `check_exact_install --wheel-tag` per wheel; G-3 `smoke-wheels`, `event-producer.yml` (PRs touching the Java tree, packaging config, the client) and nightly; G-4 `twine check`; G-5 producer SBOM; G-6 `uv lock --check`; G-7 sdist/editable test; G-8 manifests, attestation, `--verify-binaries`; G-9 `needs` edges; G-10 `test_packaging_safety.py`; G-11 plan section 9; G-12 `check_dist_set.py`; G-13 Gradle dependency verification plus tamper check. AC-037/AC-024: the pinned list in `dist-set.json`, iteration in every release step, the one-removed-wheel mutation test.

## 14 Lane-list dispositions (plan section 4.4, FR-034)

| List | File | Shape (P23) | Disposition | Test |
|---|---|---|---|---|
| `Lane` | `status/models.py` | StrEnum, 11 members including non-persisted `uninitialized` | the authority | n/a |
| `CANONICAL_LANES` | `src/specify_cli/status_lanes.py` | nine-string literal in an import-light module | stays literal (reviewed exception, reason: that module must not import status orchestration; WP07 confirms the import direction, and if `models` already imports it the tuple stays the source) | value equality with the post-genesis `Lane` members |
| `_VALID_LANES` | `migration/rebuild_state.py` | frozenset of eight (no `in_review`) | `frozenset(CANONICAL_LANES) - {"in_review"}`, the exclusion explicit with a comment; new import `CANONICAL_LANES` from `specify_cli.status_lanes` | equality with the pre-change literal |
| `_LANE_ORDER` | same | ordered five-step history path | stays literal, reviewed exception ("a path, not a vocabulary"); no import added | order and value equality |
| `_LEGACY_NOT_DONE_LANES` | `acceptance/__init__.py` | tuple, order significant, includes alias `doing` | selected by name from `Lane`, `doing` an explicit alias constant, order kept | order and value equality |
| `_PRE_APPROVED_LANE_VALUES` | `consolidation/done_bookkeeping.py` | frozenset of four | selected by name from `Lane` | value equality |

Each characterisation test is committed red-first against the pre-change literal. `test_single_lane_list.py` lists the reviewed exceptions with their reasons and fails on a new full literal list. WP07's blast radius names `tests/specify_cli/migration/test_rebuild_state.py`, `tests/specify_cli/status/test_lane_uninitialized.py` and the acceptance tests. A revert of any derivation changes a pinned value and so fails its test.

## 15 Work-package ownership audit (plan section 11)

Rule: a WP that adds a file or workflow owns every gate edit it forces; a chokepoint owner exists only for files several WPs edit. Audit of every row after review round 1:

| Forced edit | Forced by | Owner | Note |
|---|---|---|---|
| `fleet_verdict.py`, `ci-fleet-verdict.yml`, `test_fleet_verdict.py` | new PR workflow `event-producer.yml` (WP01) | WP01 | was WP09; an unregistered PR workflow is red on its own gates |
| `NON_CHANGE_TRIGGERED_WORKFLOWS` row | new `workflow_call` workflow `producer-native.yml` (WP01) | WP01 | was WP09 |
| hook file, `pyproject.toml` hook table, `.gitignore`, minimal packaging test | WP01's stub-wheel exit criterion | WP01 | was WP08; WP08 depends on WP01, so the later `pyproject.toml` floor bump is sequential |
| `gradle_pin_check.py` third `Subproject` and its tests | new `pins.json` and Gradle build (WP01) | WP01 | |
| `state/contract.py` entries | the first Python writer (fault log, WP06) | WP06 | was WP07 |
| `status/adapters.py`, `tests/status/conftest.py` | seam wiring (WP06) | WP06 | sole owners, as before |
| `_baselines.yaml`, `dead_symbol_allowlist.yaml`, `engine.py`, lint script | marker clearing and stamping (WP07) | WP07 | unchanged; WP07 no longer owns the doctor sibling |
| completion manifest, CLI reference | doctor sibling | WP06 | the sibling and its regeneration land together; the sibling is WP06's because the thinness gate (WP06) floors on all three new modules and `events_retention` needs only the `retain` protocol of WP05 |
| seam call sites, emitter route, census | FR-002 to FR-004, AC-004 | WP06 (all of them) | the engine stamping and gate (b) are WP07's, so no gate precedes its edit; rule (e) uses "within" |
| events-pin fetch task, `events-pin.properties`, schema-backed `RowSchemaValidator` adapter, AC-029 test, released comparator fixtures run | first reader of released events artifacts | WP11 (the only WP of the Java side gated by the release) | WP02 and WP04 read no schema (WP02 order tests use data-model section 4 examples as test-local inputs; WP04's `Produce` takes the validator port, tests use a fake); WP07 (AC-019, Python) reads the installed package after WP11's floor bump (R-24) |
| `release.yml`, `ci-nightly.yml`, release docs, `test_release_ci_ownership.py` assertions | release wiring (WP09) | WP09 | |
| `dist-set.json` (three platform entries) | the hook caller and the floor assertions of WP01's workflows and stub-wheel test | WP01 | was WP08 (round 3: WP01 could not be green alone); WP08 extends it |
| `check_dist_set.py`, `check_exact_install.py`, fixtures, packaging tests | wheel machinery (WP08) | WP08 | no `pyproject.toml` edit, so WP08 is ungated by the events release |
| events floor bump, `uv.lock`, `tests/contract/spec_kitty_events_consumer/` | first dependency on the released wheel (WP11) | WP11 | last editor of `pyproject.toml` |

Checked and left: `test_no_dead_modules.py` and `test_status_module_boundary.py` are satisfied inside WP06 (the client modules are imported by `adapters.py` and the facade in the same WP); the dead-symbol gate walks `__all__` of `src/charter/` and `src/kernel/` only.

## 16 Thinness gate rules (plan section 4.2)

`tests/architectural/test_producer_client_thinness.py` (new). Rules, each with a floor (the thing scanned is found and counted above zero) and a self-mutation case:

- (a) The three new modules `producer_client.py`, `producer_faults.py`, `_event_producer_doctor.py`: closed import allowlist (no `uuid`, `ulid`, `hashlib`, `jsonschema`, `spec_kitty_events`, `secrets`, `random`); no `open()` or `Path.open()` in write mode on a path containing the publish-log constant (also AC-030); `json.loads` only in `_read_reply` (client) and `_read_fault_rows` (faults); a wall-clock read (`datetime.now`, `datetime.utcnow`, `time.time`) only inside `_now_utc` of `producer_faults.py`; the exact top-level symbol set per module equals the plan 4.1 table (`producer_client.py`: `locate_binary`, `effective_client_timeout`, `ProducerClient`, `produce_status_handler`, `produce_lifecycle_handler`; `ensure_producer_client` lives in `status/adapters.py` only and is a gate (e) name there).
- (b) `_append_event` writes exactly `event_id,row_version,event_type,timestamp,payload`; the AST of its body references none of `uuid4`, `hashlib`, `secrets`, `random` (the first two are already imported at module level in `engine.py`, so gate (e) is blind to them); a test calls the real `_append_event` on a temp run dir and asserts the written `event_id` matches `^[0-9A-HJKMNP-TV-Z]{26}$` and parses with `ulid`. Mutation: a copy whose body stamps `str(uuid4())` or a `hashlib` digest fails.
- (c) The emission-area module census (`status/`, `events/`, `retrospective/`, `decisions/` plus the three new files) equals a pinned list, so a new module fails.
- (d) The client imports none of `_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`.
- (e) For every existing Python file of the plan section 4.2 table: the set of top-level functions, classes and methods, and the set of imports, lie within the base commit's plus that file's row of the table (names and imports). A helper such as `_mint_id`, or an import of `uuid`, `ulid`, `hashlib`, `datetime`, `secrets` or `random` outside the file's row, added to `emit.py`, `lifecycle_events.py` or `rebuild_state.py` fails; `engine.py` may add only `ulid`. "Within" (not "equal to") lets a row's edit land in a later WP than the gate. Rules (a) to (d) alone pin symbols only for the new modules and count modules, so they cannot catch logic added inside an existing allowed file, which is the main route by which logic would leak into Python. The pinned base sets are priced debt: the owner refreshes them when an unrelated change on `main` adds a name, and they are deleted with (c) when #6193 retires the Python writers.

Mutations (AC-012): a copy of the client gaining an id mint, a timestamp, a schema import, a publish-log read, the bridge's envelope code or an extra module; a copy of `producer_faults.py` with a clock read outside `_now_utc`; a copy of `emit.py` with an added helper or an added `import uuid`; a copy of `engine.py` or `rebuild_state.py` with an import absent from both the base file and its row (`secrets`; `uuid4` and `hashlib` would be vacuous in `engine.py`, which has them). The client's allowed capabilities pass: stderr line, bound resolution, child termination, breaker, fault-log append and read, opaque identifier copy, root resolution, retention pass-through.

Per-file base of rule (e), identical to the plan 4.2 rows. Rows for the same file are unioned (the loader merges per file; the marker row's four files also carry the wiring rows `status/lifecycle_events.py`, `decisions/emit.py` and the route row `events/decision_log.py`; comment removal adds no names); (sweep of every existing file of plan 4.2; "none" = no new name, no new import). Imports are `(module, name)` pairs at any depth.

| File | New names | New imports |
|---|---|---|
| `status/adapters.py` | `_dispatch_handlers`, `ensure_producer_client` | `status.producer_client`, `status.producer_faults.record_fault` (`hosted_posture` deleted) |
| `status/zeitgeist_bridge.py` | none | `specify_cli.core.hosted_posture` |
| `status/emit.py`, `coordination/outbound.py`, `coordination/status_transition.py` | none | none |
| `status/lifecycle_events.py`, `decisions/emit.py` | none (wiring row unioned with the marker row) | none |
| `retrospective/lifecycle_events.py` | none | one function-local fan-out name from `specify_cli.status` |
| `events/runtime_moments.py` | `emit_decision_authority_denied` (plus changed, not new: `JournalRecord.row`, `latest_matching_record`, `_publish_journalled`) | `dataclasses.field` (`hosted_posture` deleted) |
| `runtime/next/_internal_runtime/events.py` | `emit_decision_authority_denied` on `RuntimeEventEmitter` and `NullEmitter` | none |
| `events/decision_log.py` | `DecisionGitLog.emit_decision_authority_denied` | none |
| `runtime/next/runtime_bridge_retrospective.py` | `_BufferingRuntimeEmitter.emit_decision_authority_denied` | none |
| `runtime/next/_internal_runtime/engine.py` | none (`_append_event`, `_authorize_audit_answer` change in place) | `ulid` |
| `status/__init__.py` | none | the client's public names |
| `status/models.py`, `status_lanes.py` | none | none |
| `migration/rebuild_state.py` | none | `specify_cli.status_lanes.CANONICAL_LANES` |
| `acceptance/__init__.py` | the `doing` alias constant | `Lane` (added to its existing `specify_cli.status` import); `CANONICAL_LANES` is not needed (plan 4.4 selects by name) |
| `consolidation/done_bookkeeping.py` | none | `Lane` at runtime |
| `migration/mission_state.py` (marker row), `state/contract.py` | none | none |

Excluded from (e): `scripts/lint_canonical_producers.py` (its own tests), `tests/status/conftest.py`, generated files. Journal route: the layer test caps `runtime -> specify_cli` edges, so the engine imports nothing from `specify_cli`; the chain is `engine` -> `DecisionGitLog` (wrapper from `emitter_for_engine`) -> `_BufferingRuntimeEmitter` -> `RuntimeMomentProducer`. Non-`NullEmitter` fakes in tests (`tests/runtime/test_bridge_engine.py`) gain the method; `test_runtime_emitter_seam.py` (one Protocol class, one registration site) is unchanged and listed as implicated.

## 17 Traceability (plan section 14)

| Requirement group | Plan section |
|---|---|
| FR-001 to FR-004 (seam, coverage) | 4.2, 5, 12; ACs 001 to 004 in 9 |
| FR-005 (nothing beyond the persisted row) | 1 (envelope), 9; AC-029 |
| FR-006 to FR-010 (envelope, ids) | 1 (domain), 6, 9 |
| FR-011 to FR-013 (nodeid, clock, order) | 1, 7, 6 (comparator in events PR); AC-008 to AC-010, AC-039 |
| FR-014 to FR-018 (schemas, types, lanes) | 6, 4.4, 9 |
| FR-019 to FR-021 (publish log) | 7, 8, 9; AC-023, AC-026, AC-030, AC-034 |
| FR-022 to FR-025 (faults, bound) | 4.1, 5; AC-005 to AC-007 |
| FR-026, FR-027 (thin client, gate) | 4.1, 4.2; AC-012 |
| FR-028 to FR-031 (retention) | 8, 4.3; AC-013, AC-014, AC-031 |
| FR-032 to FR-035 (markers, lanes, journal) | 4.4, 4.2; AC-019 to AC-022 |
| FR-036 to FR-038 (compatibility, freeze) | 13.8, 9; AC-015 to AC-017 |
| FR-039, FR-040 (Java module, release order) | 1, 6; AC-027, AC-028 |
| FR-041 to FR-044 (wheels) | 2, 3; AC-024, AC-025, AC-032, AC-037, AC-038 |
| FR-045, FR-046 (ADR, glossary) | 13; AC-033 |
| NFR-001 to NFR-007 | 5 (no network, latency), 9 (determinism, rigour, hygiene scan AC-035), 7, 8 |
| C-001 to C-008 | 4.2 (C-002/003/004 via gates), 3.1 (C-007), 6 (C-008), 15 |
| G-1 to G-13 | 3.4, R-13 |

## 18 Spec touch-ups (plan section 15, exact wording)

Applied to spec.md and data-model.md (all thirteen present); the wording below is the record.
1. **FR-027**, add: "The allowed Python surface is the file, symbol and new-import list of plan section 4.2; the gate fails on any other new module or function in the status and emission area, and on a new top-level name or import in an existing file of that list beyond that file's row; the list is its data."
2. **FR-028 / data-model section 6** (F-2), add: "Python forwards the raw `ledger.retention.*` values from `.kittify/config.yaml` unread; Java applies the defaults 20 and 30 and validates them, returning the named policy error that `doctor` renders."
3. **C-008** (F-3), replace with: "The events PR follows spec-kitty-events#92 for the `dataschema` base URI, the `lamportclock` type, lifted-member lists and minimal-envelope timing, and for type names except the operator-ruled prefix `ai.spec-kitty` and the INTERIM names of data-model section 3, which that PR is asked to adopt; the `source` tie-break added to the total-order key goes to that PR to confirm (OD-EVENTS)."
4. **data-model section 4 clockless definition** (F-4), replace the sentence with: "A clockless row has no `lamportclock` member at all; a present value that is not an integer in 0..2^31-1 (out of range, `null`, a string, a boolean) is not clockless but an error row." and in the error-row list: "a `lamportclock` that is not an integer in 0..2^31-1".
5. **FR-046 / AC-033**: replace "CI-enforced" by "enforced by a targeted test added by this mission (no CI job runs the generator's `check` today)".
6. **data-model section 6 Retain** (F-6), add: "An unreadable, unparseable or absent committed ledger makes that Mission's items kept ('facts unavailable'); AC-013 covers it."
7. **FR-023**, state that the `retain` operation's failures are `policy_error` or kept-with-reason replies, not event faults.
8. **FR-029**, replace "or a lock file present" by "or a live lock record (a Python-owned lock sidecar holding a non-empty parsable lock record younger than the stale threshold; an empty or truncated sidecar is not a lock; the sidecar's presence alone is not a lock; `clock.lock` is never a lock fact)".
9. **FR-030 / AC-013 / data-model section 6**: replace "no file opened for write" by "no candidate file opened for write"; add "Retain takes `clock.lock` around each candidate delete, re-checks eligibility and length under it, and maps a lock timeout or delete failure to kept-with-reason; a two-process test races produce against retain".
10. **FR-026**, append: "The client may read the wall clock only to stamp its own fault-log row's `at` and to name the UTC day file, in `producer_faults.py` alone; never for an event." **AC-012**: "the timestamp mutation is applied to the client module; a second mutation adds a clock read to `producer_faults.py` outside its one clock function."
11. **FR-026 / data-model FaultLogRow**: "the fault-count window is the fault-log day files whose name date lies within the raw `max_age_days` of today; with that value absent or non-numeric, every file present."
13. **FR-009**, append: "For a run-journal row the Mission is the one whose directory the client passes as `start_path`; the producer reads `missionid` from the row's `payload.mission_id` when non-empty, else from that directory's `meta.json`." (clarification; FR-009 is satisfiable without it.)
12. **data-model section 3, rows "Run journal"**, emission point: "persisted by `engine._append_event`; reaches the seam through the `RuntimeEventEmitter` chain (`RuntimeMomentProducer._publish_journalled`), never by an import of `specify_cli` into `runtime`. `DecisionAuthorityDenied` is carried by a new Protocol method `emit_decision_authority_denied`; `SignificanceEvaluated` by the existing `emit_significance_evaluated`."

## 19 ADR plan (plan section 13, item 3)

ADR 2026-10-13-2 D-1, D-2, D-3 need amending. Amend it in place (an "Amendment" section, as ADR 2026-10-06-1 did) for the three departures (CloudEvents rows supersede "flat rows" for new rows; publish log, fault log and clock are an added read path; the local-tier list gains three files): one authority per decision. Add one new ADR `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md` for what no ADR owns (Java strangler producer, native platform wheels, pure fallback wheel, unsigned binaries, released-artifact schema delivery) and update `docs/adr/4.x/index.md`. AC-033 checks the amendment names D-1, D-2, D-3.

## 20 Campsite text (plan section 11)

**Campsite-clean (Standing Order 2).** (1) WP01's first commit, distinct and docs-only: the stale "PyPI Release" paragraph of `AGENTS.md` (says `release.yml` is absent) and the matching line of `scripts/release/README.md`. (2) WP06's first commit folds the three `try/except` ladders of the `fire_*` functions in `status/adapters.py` behind one private helper (the one touched surface where the edit adds branching), characterised by `tests/status/test_saas_fanout_timeout.py` and `test_drain_fanout.py`. (3) WP06's edits to `status/emit.py` (1570 lines), `lifecycle_events.py` and `engine.py` are call sites and keyword additions with no new branch, so no extraction is justified (one line per surface in the WP prompt). The orchestrator dispatches a campsite scout on `status/emit.py` and `events/runtime_moments.py` before WP06; findings fold or defer under `RECONCILE_CHANGE_SCOPE_TENSIONS`. Sonar was not measured (no access): unmeasured, not clean.

## 21 Lock fact detail (plan section 8)

- **Lock fact = the content of a Python-owned lock sidecar, never its presence.** `kernel.locks` truncates but never unlinks a sidecar on release, so every advanced run directory keeps an empty `state.json.lock`. A run is locked iff its sidecar holds a non-empty parsable `LockRecord` younger than the stale threshold (60 s, `kernel.locks.STALE_AFTER_S_DEFAULT`); an empty sidecar is no lock; an unreadable one (a Windows mandatory lock refuses the read) keeps the run. Java reads bytes and never takes or tests an OS lock on a Python-owned file (different advisory-lock families). `clock.lock` is never a lock fact. Unfinished step: a run journal without `MissionRunCompleted`. AC-013 fixtures: sidecar released by the real Python primitive (eligible), live record (kept), stale record (eligible), unreadable (kept).

## 22 Doctor rendering detail (plan section 8)

- **Doctor pass-through rendering.** `doctor events` prints binary presence, fault count in the window (fault-log day files whose name date lies within the raw `max_age_days` of today; absent or non-numeric: every file present; no Python default), last class, notices apart, and, optionally, a `migrate backfill-identity` note (never a fault); `doctor events-retention` renders the reply lists (repo-relative). `--json` uses `cli/json_contract`. Both live in `_event_producer_doctor.py`; Python renders and counts only.

## 23 Retain versus produce detail (plan section 8)

- **Retain versus produce.** For each candidate publish-log file `Retain` takes `clock.lock` (bounded by `deadline_ms`), re-checks eligibility and the file's length and newest row under it, then deletes; a lock timeout or delete failure (a Windows sharing violation) is kept-with-reason (`busy`, `delete-failed`). Two-process test: `produce` racing `retain --apply` loses no row reported produced. Fault-log and run-journal candidates have writers that touch only the current day or a live run, which eligibility excludes.

## 24 Run-journal row route and Python-side schema source (plan sections 4.2, 11; fresh round 2)

**Row, not envelope (PLAN-FRESH2-001).** In the checkout `JournalRecord` (`events/runtime_moments.py`) holds `line` and `timestamp` only; `latest_matching_record` parses the full journal dict and discards it; `_publish_journalled` builds a bridge-style `envelope` with `moment_event_id` (UUID5 over `_EVENT_ID_NAMESPACE`) and `_with_mission_identity`. Minimal edit: `JournalRecord` gains `row: dict[str, Any] = field(default_factory=dict, compare=False)` (so `JournalRecord(line=4, timestamp=STAMP)` and the `==` assertion of `test_runtime_moments_unit.py` still hold), `latest_matching_record` fills it from the parsed dict, `_publish_journalled` passes `row=record.row` next to the unchanged `envelope=`. Residuals, named with the reason (the Zeitgeist handler, `zeitgeist_bridge._broadcast_lifecycle_envelope`, reads `kwargs["envelope"]` until #6164 deletes it, and Team Kitty upserts on that UUID5): `moment_event_id`, `_EVENT_ID_NAMESPACE`, the `uuid` import, `_with_mission_identity`, the envelope dict. Nothing new is minted. The producer id is `row["event_id"]` only; Java never reads `seam_args["envelope"]`. WP06 rows have no `event_id` yet (WP07 stamps), so the row reaches Java without one and is refused per FR-007's rules (a fault row, no Python id); `_publish_journalled` must not raise or invent one for a legacy journal.

**Tests (red first, WP06).** Blast radius: `tests/specify_cli/events/test_runtime_moments_unit.py` (the only test of `moment_event_id` and `latest_matching_record`; keeps its envelope assertions, gains the new ones), `tests/specify_cli/events/test_runtime_moments.py` (published calls also carry `row`). New assertions, each red on the current code: (1) every publish call has a `row` equal to the `json.loads` of its journal line; (2) `set(row)` is within `{event_id,row_version,event_type,timestamp,payload}` (an envelope passed as `row` has `aggregate_id` and fails); (3) with a journal line stamped with a ULID `event_id`, `row["event_id"]` is that ULID and differs from `envelope["event_id"]`; (4) a hand-built `JournalRecord(line, timestamp)` yields an empty `row` and `_publish_journalled` neither raises nor adds an id. WP07 adds the end-to-end case: a stamped journal line through the real handler into a stubbed-binary-free Java acceptance run yields a publish-log `id` equal to the stamped ULID. Sweep for other Python ids or envelopes reaching the client: `status/emit.py` and `retrospective/lifecycle_events.py` pass persisted rows whose ids are `_generate_ulid()`; `status/lifecycle_events.py::_generate_event_id` falls back to a 32-hex uuid4 and `moment_event_id` is a UUID5, both refused by Java as UUID forms (`rejected`), which is the specified behaviour for legacy rows. Gate (e) does not catch a change of meaning inside a changed function, so tests (1) to (3) are the guard.

**Journal-row mission identity (PLAN-FRESH3-002).** Checked in the checkout: `RuntimeMomentProducer.for_mission` takes `_resolve_mission_id(feature_dir)` from `resolve_mission_identity(feature_dir)`, which reads `<feature_dir>/meta.json`; `_repo_root()` returns `_feature_dir` and `_publish` passes it as `repo_root` to `_publish_journalled`, and `find_run_journal` walks up from it, so the run directory `.kittify/runtime/runs/<run_id>/` is not where identity comes from. The envelope payload alone carries the stamped id (via `_with_mission_identity`) and Java must not read it. Rule: client `start_path` = that mission directory; Java order: non-empty `row.payload.mission_id`, then `meta.json` in `start_path`, else the project-scoped `source` with no `missionid` and no fault (OD-MISSIONID (b)). Tests: WP06 (request carries the feature directory for a row with empty `mission_id`, no envelope member in `row`), WP04 (Java resolution both ways and a slug-only payload still gets the project-scoped `source`, never a slug as `missionid`), WP10 (end to end publish-log `missionid`). `SignificanceEvaluated` and `DecisionAuthorityDenied` rows take the same route. Spec touch-up 13 (R-18) states the rule for FR-009.

**Schema source for WP07 (PLAN-FRESH2-002).** Decision: WP07 depends on WP11 (WP11: pin task, schema adapter, floor bump with `uv.lock`; it needs the events release, WP04 and WP03; round 3 moved the floor bump there from WP08). The Python AC-019 gate and the marker-clearing check read the released schemas and registry from the installed `spec_kitty_events` package (resource lookup in the venv), so nothing is committed (`test_no_committed_events_schema.py`) and no fetch path exists beside the pin. Floor: the schema set resolved above zero and the cleared-marker count above zero; the test fails, not skips, when the package lacks them (AC-019). Sweep of the WP table for the same class (a WP reads an artifact whose provider it does not depend on): WP11 (pin task, gated by the release, noted); WP04 and WP02 (read no schema); WP05, WP06 (stubbed client, no events package use); WP07 (fixed here); WP09 (workflow text only, depends WP08); WP10 (all). No further gap.
