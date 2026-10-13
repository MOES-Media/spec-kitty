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

## 2 Native image per platform (detail of plan section 2)

**Toolchain.** GraalVM Community JDK 25 (P16): the same Java line as `acceptance-jvm`, so one JDK major serves the repo. The exact release is chosen in WP01 from the `jdk-25.0.x` tags (the stable line; innovation drops move monthly) and recorded with the archive URL and sha256 in `services/event-producer/tools/pins.json`; the build fails when the running `native-image --version` differs from the pin. The same file pins Gradle 8.14.5 (the repo's current pin) for `contracts/tools/install_tools.py`, which is reused unchanged.

**Build flags (starting point, WP01 confirms).** `-O2`, `--no-fallback`, `-H:+ReportExceptionStackTraces`, `-H:IncludeResources` for the embedded schemas and registry, `--enable-native-access=ALL-UNNAMED` plus the Foreign Function and Memory API support flag for the `gethostname` downcall, `-march=x86-64-v2` on Linux (a portable CPU baseline: the default `native` would pin the CI runner's CPU), `-march=armv8-a` on macOS arm64. Reachability metadata for Jackson and the schema validator comes from the GraalVM reachability-metadata repository where available, else a committed `reflect-config` produced by the tracing agent during the contract tests.

**Per-OS recipe.**

| OS | Runner label | Specifics |
|---|---|---|
| Linux x86_64 | `ubuntu-24.04` | Build inside a pinned `manylinux_2_28` x86_64 container (image digest recorded in `pins.json`) so the glibc floor is 2.28; the wheel tag is derived from the measured maximum `GLIBC_` symbol of the built binary, never assumed. Fallback if GraalVM cannot run in that image: static musl build (`--static --libc=musl`), tag `manylinux_2_17_x86_64.musllinux_1_1_x86_64`. WP01 chooses; the choice is a recorded decision, not an operator fork. |
| macOS arm64 | `macos-14` (pinned, not `macos-latest`) | `MACOSX_DEPLOYMENT_TARGET=11.0`; tag from `otool -l` `minos` of the built binary. arm64 executables require a code signature to run; the linker emits an ad-hoc one, so the binary must not be stripped or modified afterwards, and the smoke job runs `codesign --verify`. |
| Windows x86_64 | `windows-2022` | MSVC environment set up by a SHA-pinned action step before `native-image`; binary `spec-kitty-event-producer.exe`; tag `win_amd64`. |

**One job per OS, one assembling job.** Reusable workflow `.github/workflows/producer-native.yml` (`workflow_call`) holds the per-OS job (matrix over the three legs, `fail-fast: false`). Each leg: checkout, pinned JDK, Gradle install, `gradle :launcher:nativeCompile` under dependency verification, `producer-manifest.json` (binary sha256, toolchain version and archive sha256, source commit, OS, measured platform floor, native-image argument digest), upload. Callers: `release.yml` (job `build-producer`), `event-producer.yml` (PR and nightly), nothing else. The assembling job is the existing `build-release`, which keeps its name (P4).

**Provenance and checksum continuity (FR-042, AC-032).** (1) Each leg records the binary sha256 in its manifest and in the uploaded artifact. (2) `build-release` downloads the three artifacts and recomputes every sha256 before any wheel is built (a mismatch fails the job). (3) The hook copies the binary into the wheel and writes the same sha256 to `specify_cli/_native/PRODUCER.sha256`. (4) `scripts/release/check_dist_set.py --verify-binaries` opens every platform wheel, hashes the member and compares with the manifest (AC-032). (5) `publish-pypi` attaches a build-provenance attestation for the binaries and wheels (`actions/attest-build-provenance`, SHA-pinned at WP09; needs `attestations: write` and `id-token: write`, which the job already uses for trusted publishing). (6) The manifests ship as release assets. No bit-for-bit reproducibility is claimed.

**Unsigned binaries.** pip does not set the macOS quarantine attribute and does not add the Windows mark-of-the-web, so a pip-installed binary is not blocked by Gatekeeper or SmartScreen in the ordinary path; a user who copies the binary out of a browser download would be. Defender may scan the first run, which the first-run row of AC-036 measures. The release notes state "unsigned in this mission" (F-5 trim).

## 3 Wheel machinery, file by file (plan section 3)

| File | Change | Gate(s) it touches |
|---|---|---|
| `pyproject.toml` | add `[tool.hatch.build.targets.wheel.hooks.custom]` with `path = "scripts/release/hatch_build_producer.py"`, `enable-by-default = false`; no change to `packages`, `artifacts`, `force-include`, `exclude`, `[build-system]`. The sdist `include` list is NOT widened: platform wheels are built from the checkout, never from the sdist. | `test_pyproject_shape.py`, `test_packaging_safety.py`, `uv lock --check` (no dependency change, so the lock is unchanged), `release-readiness.yml` (path filter includes `pyproject.toml`) |
| `scripts/release/hatch_build_producer.py` (new) | `BuildHookInterface` subclass. `initialize(version, build_data)`: read `SPEC_KITTY_PRODUCER_BINARY`, `SPEC_KITTY_PRODUCER_PLATFORM_TAG` (both required when the hook is active, else raise); set `build_data["pure_python"] = False`, `build_data["tag"] = "py3-none-<tag>"`, `build_data["force_include"][<binary>] = "specify_cli/_native/<name>"`, plus the `PRODUCER.sha256` file. No other logic. Python build glue (plan section 13, fork F1). | `ruff` (the `scripts/` tree is linted), TID251 |
| `scripts/release/check_dist_set.py` (new) and `scripts/release/dist-set.json` (new) | `dist-set.json` is the pinned expected list: `sdist` x1, `py3-none-any` x1, `py3-none-manylinux_2_28_x86_64` (or the WP01 tag), `py3-none-macosx_11_0_arm64`, `py3-none-win_amd64`. The script lists `dist/`, fails on any missing or extra file, optionally verifies binary checksums (`--verify-binaries`). Mutation: remove one wheel -> red. | AC-037, AC-024, AC-032, G-12 |
| `scripts/release/check_exact_install.py` | `locate_wheel(dist_dir, package_name, *, wheel_tag=None)`: with a tag select that wheel, without one keep today's "exactly one" rule; new `--wheel-tag` option. | `tests/release/test_check_exact_install.py` (extend: tagged selection; untagged multi-wheel still refuses) |
| `.github/workflows/release.yml` | see plan 3.3. | `test_release_ci_ownership.py`, `test_fork_guard.py` (exempt), `test_no_duplicate_suite_execution.py` (exempt) |
| `.github/workflows/producer-native.yml` (new, `workflow_call`) | per-OS native build. | `test_no_duplicate_suite_execution.py` ledger row; stock-runner text check (no `blacksmith`) |
| `tests/_support/shared_build_artifacts.py` | add a fixture variant `platform_wheel_artifacts` that builds a platform wheel with the hook enabled and a stub binary; `published_build_artifacts` untouched (hook-off build stays the single pure wheel). | `tests/_support/test_shared_build_artifacts.py` |
| `tests/cross_cutting/packaging/test_packaging_safety.py`, `test_package_bundling.py` | add: platform wheel has exactly one binary at `specify_cli/_native/`, executable bit preserved, `packs/internal/` absent, sdist and hook-off wheel contain no binary and build with no env (AC-038). | G-1, G-7, G-10 |
| `RELEASE_CHECKLIST.md`, `scripts/release/README.md`, `CONTRIBUTING.md` "What the Release Workflow Does" | describe the new stages and the set check. | `tests/release/test_release_ci_ownership.py` (asserts the checklist names `release.yml`) |

**Behaviour of the other build paths.** `uv sync` and editable installs never enable the hook (default off), so `src/specify_cli/_native/` is absent and the client reports `binary-missing` unless a developer copies a locally built binary there (the directory is gitignored). `python -m build` without the two env vars yields the sdist and the pure wheel as today.

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
{"v":1,"op":"produce","outcome":"refused","reason_class":"rejected","reason":"mission-id-missing","event_id":"01J...","detail":"..."}
{"v":1,"op":"produce","outcome":"produced","notice":{"kind":"torn-line","path":".kittify/events/publish/missions/<id>.jsonl","line":42}}
{"v":1,"op":"retain","dry_run":true,"eligible":[{"path":"...","reason":"closed-mission-outside-limits"}],"kept":[{"path":"...","reason":"mission-open"}],"deleted":[],"policy_error":null}
```

Rules: the reply never carries the row, an absolute path, or a stack trace; `reason` is a closed vocabulary (`root-unresolvable`, `mission-id-missing`, `id-not-ulid`, `time-unparseable`, `schema-invalid`, `type-unknown`, `clock-overflow`, `nodeid-unavailable`, ); `detail` is one repo-relative line. Exit codes: 0 a reply was written (including `refused`); 64 request unreadable or `v` unsupported (no reply is written, so the client records `crash`); any other non-zero, a signal, or empty stdout is `crash`. stderr is discarded by the client (it may carry JVM or native-image diagnostics and absolute paths, NFR-006). Client reads at most 1 MiB of stdout. The `deadline_ms` budget is enforced by the producer through a watchdog thread that exits with code 75 after cleanup, so no child outlives an abandoned seam call.

**Fault mapping table (client).**

| Observation | Class |
|---|---|
| no executable at the package-relative path | `binary-missing` |
| spawn error (permission, exec format) | `binary-missing` |
| non-zero exit, signal, empty stdout | `crash` |
| client timeout elapsed (child terminated by handle) | `timeout` |
| stdout not one JSON object, wrong `v`/`op`, missing `outcome`, unknown `reason_class` | `malformed-reply` |
| reply `refused` with `reason_class` `clock-overflow` or `rejected` | that class |
| seam raised `_FanoutHandlerBusyError` | `busy` |
| seam raised `TimeoutError` (the handler outlived the seam bound) | `timeout` |

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
| `state/contract.py` (three new surfaces) | `tests/architectural/test_lifted_root_gitignore_contract.py`, `tests/specify_cli/test_state_contract.py`, `tests/state/` | state-surface registry vs gitignore and roots | register publish log, fault log, clock state (a root outside the work tree needs a new `StateRoot` member or a `GIT_COMMON_DIR` note; decided in WP07) |
| path literals | `tests/architectural/test_no_dead_src_path_literals.py`, `tests/audit/test_no_legacy_path_literals.py` | new `.kittify/events/...` literals in `src/` | literals live in one constants module |
| exempt markers | `tests/architectural/test_lifted_lint.py`, `scripts/lint_canonical_producers.py`, `scripts/canonical_producer_lint_baseline.txt` | marker semantics, baseline | markers cleared per data-model section 8; baseline not widened; persisted-line mode added to the lint |
| lane lists | `tests/architectural/` grep found no test naming the census lists; wider grep in `tests/` for `CANONICAL_LANES` etc. is done in WP07 before editing | none known | first task of WP07 is the census (FR-034) |
| `pyproject.toml` | `tests/architectural/test_pyproject_shape.py`, `test_shared_package_boundary.py`, `test_layer_rules.py`, `tests/cross_cutting/packaging/*`, `tests/release/test_validate_release.py`, `test_pinning_inventory_fresh.py` (`pinning_rule_inventory.json`) | wheel `packages` inventory, dependency ranges, release metadata, pin inventory | hook table only; floor bump of `spec-kitty-events` also touches `uv.lock`, `test_events_dependency_floor_rejects_pre_v10_contract`, the pin inventory, `.kittify/release/shared-package-compatibility.json` and `check-spec-kitty-events-alignment.yml` |
| `release.yml` and release scripts | `tests/release/test_release_ci_ownership.py`, `test_check_exact_install.py`, `test_release_prep.py`, `tests/ci/test_fork_guard.py`, `tests/architectural/test_no_duplicate_suite_execution.py`, `tests/architectural/_workflow_jobs.py` consumers | P4 to P7 | keep job and step names; add ledger rows |
| new `event-producer.yml` | `tests/ci/test_fleet_verdict.py`, `scripts/ci/fleet_verdict.py`, `ci-fleet-verdict.yml`, `tests/ci/test_fork_guard.py`, `tests/ci/test_contracts_workflows.py` (shape reference only) | P5, P6 | register the workflow; guard its root jobs |
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
3. A shared test-venv lock timeout (#3283) is an environment failure: rerun the file once alone; a second identical failure is recorded as red.
4. After each WP, a red node id is **pre-existing** when it is in the baseline list with the same failure text, **introduced** otherwise. Only introduced red is the WP's to fix. Each distinct pre-existing red is reported to the operator for the issue the charter requires (the agent cannot write to GitHub).
5. Known-P0 reproductions (`p0_repro`) are not run locally (AGENTS.md).

## 10 Alternatives considered

| Topic | Chosen | Rejected | Why |
|---|---|---|---|
| Wheel tags | explicit `py3-none-<platform>` from the hook | `infer_tag` | inferred tag carries a CPython ABI; one wheel must serve every Python >= 3.11 |
| Where wheels are built | assembling job builds all platform wheels from the downloaded binaries | each OS builds its wheel | one place for set check, checksum check and `twine check`; hatch is pure Python |
| Hook enablement | env var, default off | always-on hook with a no-op branch | an unconditional hook breaks `uv sync`, editable and sdist-to-wheel builds (C-007) |
| Events artifact | pinned wheel fetched by a plain Gradle task with sha256 check | git submodule, copied schemas, Maven artifact | charter no-vendoring rule; no Maven artifact exists |
| Client placement | `status/` beside `zeitgeist_bridge.py` (routed by the existing `status` group) | new top-level `specify_cli/event_producer/` | a new subpackage falls into the router's catch-all and needs a new group |
| Dev override of the binary | none in production code; tests patch `locate_binary`; developers copy a built binary into the gitignored `_native/` | env var naming the binary | an env-selected executable on every command is an avoidable hazard, and a bad value would be a de-facto opt-out (OD-OPTOUT) |
| CI home of Java gates | new `event-producer.yml` (the `contracts.yml` pattern) | add jobs to the router | the router is src-glob driven and heavily pinned |
| ADR | amendment of 2026-10-13-2 for D-1 to D-3, new ADR for the delivery decision | one new ADR superseding | one authority per decision |

## 11 #6164 interaction, hunk by hunk (plan section 12)

Hunks this mission makes in `status/adapters.py`, each in its own commit: (A) three `fire_*` drain gates removed and the `hosted_posture` import dropped; (B) `row` and fault-recorder calls in the busy and timeout branches; (C) `ensure_producer_client()` plus the import tail registering it outside the `moment_handlers_disabled_reason` branch; (D) `reset_handlers` docstring and the test fixture call. Conflicts if #6164 lands first: (A) and the `zeitgeist_bridge` import block (#6164 deletes both; take theirs, our commit becomes empty), the import tail (take theirs, re-add (C) beneath it). Conflicts if this mission lands first: #6164 deletes the Zeitgeist registration lines we left; no change of ours remains in them. The other silencers named in FR-002 sit outside `adapters.py`: `RuntimeMomentProducer._publish` (`events/runtime_moments.py`) and the status test fixtures; #6164 is expected to touch the first only if it removes `hosted_posture`, in which case our deletion is a no-op.

## 12 Claims not verified

- GraalVM: the exact `jdk-25.0.x` build, its sha256, flag spelling for the FFM support and `-march` values, and whether GraalVM runs in the pinned `manylinux_2_28` image are WP01 outputs, not facts.
- Action versions for the MSVC environment step and the attestation step; WP09 pins them by SHA.
- Whether `native-image` honours `MACOSX_DEPLOYMENT_TARGET`; the measured `minos` is the source of truth.
- #3283 and #3284 are named by the design pipeline; neither id appears in this repository.
- That the Windows `gethostname` of Python and the FFM downcall agree on every configuration: the per-OS golden test (AC-039) is the check.
- The final names of the events repo's schema files and registry file: that repo decides.
- Whether `tests/architectural/test_pyproject_shape.py` objects to a `hooks` table: to be run in WP08 (its visible assertions concern `packages` and dependencies).
- That `spec-kitty plan --help` scaffolds nothing needed: plan.md was written directly; the command was not run.
