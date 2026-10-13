# Implementation Plan: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 | **Branch**: `feat/event-producer-reroute-6185` (target `main`) | **Date**: 2026-10-14
**Inputs**: [`spec.md`](spec.md), [`data-model.md`](data-model.md), [`plan-carryover.md`](plan-carryover.md), `reviews/spec-amend-verify.yaml` (F-1 to F-6 absorbed in sections 4, 8, 15), the operator rulings of 2026-10-13. Evidence, gate-audit tables, alternatives and the unverified list: [`research.md`](research.md) (cited as R-n).
**Charter**: read; it wins over `AGENTS.md`/`CLAUDE.md` (`CLAUDE.md` is a symlink to `AGENTS.md` here). Drift is flagged in section 13, not resolved.

The spec has no open decision. Real forks found while planning are in section 16; none blocks the rest.

## 1 Module layout

**Location.** `services/event-producer/` at the repository root (a sibling of `contracts/` and `acceptance-jvm/`, both standalone Gradle builds with no committed wrapper jar). No dependency on `acceptance-jvm`, `contracts`, or any unlanded #5529 code.

**Build.** Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts` per subproject), Gradle 8.14.5 installed by `contracts/tools/install_tools.py --pins services/event-producer/tools/pins.json --only gradle` (reused unchanged: sha256 verified before unpack). The two existing builds are Groovy; Kotlin DSL is the brief's choice and Gradle reads both, so #6192 can absorb either (R-8). Java 25 through a toolchain block, the line `acceptance-jvm` already uses (ADR 2026-10-01-2). A committed `gradle/verification-metadata.xml` (Gradle-generated, never hand-edited, `sha256`) covers every dependency and plugin; the `contracts/gradle/verification-metadata.xml` pattern, with the same tamper check (a planted checksum change must fail the build).

**Subprojects and packages** (root package `ai.speckitty.producer`):

| Subproject | Contents | May depend on |
|---|---|---|
| `:domain` | `ids` (ULID parse and normalise, UUID refusal), `clock` (value type, bound 2^31-1, overflow), `order` (total-order comparator, dedupe, error rows), `envelope` (attribute names, type registry model, lifted-member mapping driven by data), `retention` (eligibility over supplied facts, policy defaults and validation), `fault` (reason vocabulary), `protocol` (request and reply value types) | `java.base` only |
| `:application` | use cases `Produce`, `Retain`; ports `RowSchemaValidator`, `PublishLog`, `ClockStore`, `RepoLocator`, `NodeIdentity`, `LedgerFacts`, `Clock` | `:domain` |
| `:adapter` | JSON (Jackson, only here), schema validation, file system, `FileChannel` lock, git common-dir resolution by reading `.git`/`commondir`, hostname through the Foreign Function and Memory API, committed-ledger reader for retention | `:application` |
| `:launcher` | `main`, stdin/stdout protocol, exit codes, deadline watchdog, native-image configuration | all |

**Rules enforced by ArchUnit (a test in `:domain`/`:application`):** `:domain` imports only `java.base`; `:application` imports no `:adapter` or Jackson; no class in `:domain` reads a clock, environment variable or file. Every rule carries a floor (the scanned class count must be above zero) and a self-mutation case (the spec's floor rule).

**Absorption.** #6192 lifts `:domain` into `domain-model` and the `ClockStore`, `PublishLog`, `Produce` parts into `event-log` by moving packages, not rewriting them. #6193 replaces `LedgerFacts` (the minimal committed-ledger reader) with its read model and swaps the publish-log writer for the committed-ledger writer behind the same port. The reader is read-only, parses only `wp_id`, `to_lane`, `at` and `event_id`, and is the only code that opens a committed ledger.

**JDK pin.** Gradle toolchain `languageVersion = 25`; CI uses the SHA-pinned `actions/setup-java` form from `contracts.yml` with `java-version: '25'`; native builds use the GraalVM Community JDK 25 archive pinned in `pins.json` (section 2).

## 2 Native image build per platform

**Toolchain pin.** GraalVM Community JDK 25, one release chosen in WP01 from the stable `jdk-25.0.x` tags; archive URL and sha256 in `tools/pins.json`; the build fails when `native-image --version` differs. Floor of three legs only (FR-041, OD-MATRIX): no macOS x86_64, no Linux aarch64, no musl wheel unless WP01's fallback applies (R-2).

| Leg | Runner label | Binary | Wheel tag |
|---|---|---|---|
| Linux x86_64 | `ubuntu-24.04`, build in a pinned `manylinux_2_28` container | `spec-kitty-event-producer` | `py3-none-manylinux_2_28_x86_64`, measured from the binary's glibc symbols |
| macOS arm64 | `macos-14` | same | `py3-none-macosx_11_0_arm64`, measured `minos` |
| Windows x86_64 | `windows-2022` | `spec-kitty-event-producer.exe` | `py3-none-win_amd64` |

**CI shape.** Reusable workflow `.github/workflows/producer-native.yml` (`workflow_call`, matrix over the three legs, `fail-fast: false`) builds and uploads `producer-<leg>` (binary plus `producer-manifest.json`). `release.yml` calls it in a new job `build-producer`; `event-producer.yml` calls it for the smoke path. The assembling job is the existing `build-release` (it keeps its name because `tests/release/test_release_ci_ownership.py` pins it, R-4).

**Provenance and checksums (FR-042, AC-032).** Per-leg manifest (binary sha256, toolchain archive sha256, source commit, measured platform floor); the assembling job recomputes the sha256 before building any wheel; the hook writes the same value into the wheel as `specify_cli/_native/PRODUCER.sha256`; `check_dist_set.py --verify-binaries` re-hashes the member of every published wheel; `publish-pypi` attaches a build-provenance attestation. No reproducibility claim.

**Unsigned consequence (OD-SIGN).** pip sets neither the macOS quarantine attribute nor the Windows mark-of-the-web, so installed binaries run unprompted in the ordinary path; a browser-downloaded copy may be blocked. macOS arm64 needs an (ad-hoc) signature to execute at all: the linker supplies it, the build must not strip or modify the binary, and the smoke job runs `codesign --verify`. Release notes say "unsigned in this mission".

**WP01 exit criteria (feasibility gate, first work package).** On all three runners a trivial producer (stdin JSON in, JSON out, Jackson, one schema validation, `FileChannel` lock, `gethostname` downcall) builds, starts under 100 ms warm, and a stub wheel built with the hook installs and runs by path on its OS. If the Windows or Linux leg fails, the fallback options (musl static on Linux; reachability-metadata hand configuration) are tried in WP01; if a leg still fails the mission stops and the operator is told (it would alter the floor of FR-041).

## 3 Wheel build machinery

Current state (R-2, R-3, R-10, R-11): `hatchling==1.31.0` backend; one pure wheel plus sdist from `python -m build` in `release.yml` job `build-release`; every wheel-handling step assumes one wheel.

### 3.1 Build backend change (stays hatch)

`pyproject.toml` gains only `[tool.hatch.build.targets.wheel.hooks.custom]` with `path = "scripts/release/hatch_build_producer.py"` and `enable-by-default = false`. The hook runs only when `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`; it then requires `SPEC_KITTY_PRODUCER_BINARY` and `SPEC_KITTY_PRODUCER_PLATFORM_TAG` and sets `pure_python = False`, an explicit `tag = py3-none-<platform>`, and a `force_include` of the binary to `specify_cli/_native/<name>` (plus `PRODUCER.sha256`). Unset, nothing changes: `uv sync`, editable installs, `uv build --wheel` (`ci-quality.yml` `build-wheel`), the shared test build (`--no-isolation`), the sdist, and the sdist-to-wheel step all produce today's output (C-007, AC-038). The sdist `include` list is not widened: platform wheels are built from the checkout.

### 3.2 Files that change

`pyproject.toml` (hook table; at the floor bump also the events range and `uv.lock`); new `scripts/release/hatch_build_producer.py`; new `scripts/release/check_dist_set.py` and `scripts/release/dist-set.json` (the pinned expected list: one sdist, `py3-none-any`, the three platform tags; AC-037, G-12); `scripts/release/check_exact_install.py` (`locate_wheel(..., wheel_tag=None)` and `--wheel-tag`; untagged multi-wheel still refuses); `.github/workflows/release.yml`; new `.github/workflows/producer-native.yml`; `tests/_support/shared_build_artifacts.py` (an added fixture building a platform wheel with a stub binary; the existing one-pure-wheel path untouched); packaging tests; `RELEASE_CHECKLIST.md`, `scripts/release/README.md`, `CONTRIBUTING.md` release section. File-by-file detail: R-3.

### 3.3 `release.yml` change

1. New job `build-producer` (`uses` the reusable workflow, `needs: [nightly-gate]`, same `if:` as `build-release` for the waiver case).
2. `build-release` keeps its name and every existing step; adds `needs: [nightly-gate, build-producer]`; new steps after "Build distributions": download the three producer artifacts; recompute sha256; build each platform wheel with `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true` and the two env vars (`python -m build --wheel --outdir dist`); `check_dist_set.py` (set equals `dist-set.json`); `check_exact_install.py --wheel-tag py3-none-any` and for the Linux tag; "Verify wheel contents" becomes a loop over `dist/*.whl` keeping the substrings the test pins; `twine check dist/*` already globs; `sha256sum dist/*` already globs; SBOM adds `sbom-producer.cdx.json` from the Gradle CycloneDX plugin plus the toolchain component (G-5); upload adds the manifests and the producer SBOM.
3. New job `smoke-wheels` (matrix of the three OS labels, `needs: build-release`): download `release-distributions`, create a fresh venv, `pip install` the matching platform wheel by path, run the smoke script (one valid row appended, `binary-missing` absent), and install the pure wheel on one leg to prove the fallback (G-2, G-3).
4. `publish-pypi` becomes `needs: [build-release, smoke-wheels]` with the same `!cancelled() && results == success` condition extended to both; a failed or cancelled leg anywhere blocks publish (G-9, Standing Order 9). It re-runs `check_dist_set.py` on the downloaded artifact as its first step (belt and braces for G-12) and adds the attestation step.
5. `verify-pypi-installability` gains the platform wheel tag for its OS only on Linux (the PyPI exact-install check stays one job).

### 3.4 Gates touched (named)

Release verification and packaging: `release.yml` steps above; `tests/release/test_release_ci_ownership.py` (job and step names kept; new assertions for `build-producer`, `smoke-wheels`, the `needs` edges, and that `publish-pypi` needs the set-checked artifact); `tests/release/test_check_exact_install.py`; `tests/cross_cutting/packaging/test_packaging_safety.py` and `test_package_bundling.py` (binary only in platform wheels, `packs/internal/` absent, sdist and pure wheel binary-free); `tests/architectural/test_pyproject_shape.py` (run, expected untouched); `tests/ci/test_fork_guard.py` (release exempt); `tests/architectural/test_no_duplicate_suite_execution.py` (ledger row for the `workflow_call` workflow); `ci-quality.yml` `build-wheel` and `clean-install-verification` (unchanged behaviour, run as regression); `release-readiness.yml` (runs by path). **Standing Order 9**: the new stages are all `needs` edges of `publish-pypi`; no `continue-on-error` anywhere in them. **AC-037/AC-024**: the pinned list in `dist-set.json` plus the iteration in every release step, with the one-removed-wheel mutation test. **G-1 to G-13** map: G-1 hook test and packaging test; G-2 `check_exact_install --wheel-tag` per wheel; G-3 `smoke-wheels`, `event-producer.yml` (PRs touching the Java tree, packaging config, the client) and nightly; G-4 `twine check`; G-5 producer SBOM; G-6 `uv lock --check`; G-7 sdist/editable test; G-8 manifests, attestation, `--verify-binaries`; G-9 `needs` edges; G-10 `test_packaging_safety.py`; G-11 section 9; G-12 `check_dist_set.py`; G-13 Gradle dependency verification plus tamper check.

## 4 Thin Python client contract

### 4.1 What Python may do, each with its file

| Capability (FR-026) | File | Symbol(s) |
|---|---|---|
| locate the platform binary (package-relative `_native/`; no env override) | `src/specify_cli/status/producer_client.py` | `locate_binary` |
| spawn (argv list, no shell), pass request, read one reply (cap 1 MiB) | same | `ProducerClient.produce`, `ProducerClient.retain` |
| resolve the effective seam bound and keep the client timeout strictly below it (also when the bound is disabled) | same | `effective_client_timeout` (reads `status.adapters._saas_fanout_timeout_s`) |
| terminate its own child by handle | same | inside `ProducerClient._run` |
| per-process breaker | same | `ProducerClient` state |
| map any failure to a fault class | same | `FaultClass` mapping in `_classify` |
| register on the seam | same + `status/adapters.py` | `ensure_producer_client`, handlers `produce_status_handler`, `produce_lifecycle_handler` |
| fault recording: stderr line in every mode, fault-log append (single writer), notice rows, count and last class for `doctor`; canonical root via `resolve_canonical_root` for the location only | `src/specify_cli/status/producer_faults.py` | `FaultClass`, `record_fault`, `record_notice`, `write_stderr_warning`, `fault_summary` |
| doctor pass-through: raw `ledger.retention.*` values forwarded, reply rendered | `src/specify_cli/cli/commands/_event_producer_doctor.py` (self-registering sibling, no edit to `doctor.py`) | `register`, `events_health`, `events_retention` |
| OD-JOURNAL stamping | `src/runtime/next/_internal_runtime/engine.py` | `_append_event` only |
| seam wiring (FR-002 to FR-004) | files in 4.2 | named symbols |

The client holds no schema, id, clock, order, dedupe or type knowledge. Fault `event_type`/`event_id` are copied from the row as opaque values. There is no environment override of the binary and no producer setting (OD-OPTOUT); tests patch `locate_binary`.

### 4.2 Seam wiring and the allowed-surface list (resolves F-1)

FR-027's gate takes this table as its data. It is the complete list of Python changes this mission may make; the gate fails on any other new module or function in the status and emission area.

| File | Allowed symbols (new or changed) | Nature |
|---|---|---|
| `status/adapters.py` | `fire_saas_fanout`, `fire_lifecycle_saas_fanout`, `fire_resolved_binding_fanout` (drain gate removed; `row` passed through; busy and timeout branches call `record_fault`), `ensure_zeitgeist_moment_handlers` (unchanged logic), `reset_handlers` (docstring), new `ensure_producer_client`, import tail (registers the client outside the `moment_handlers_disabled_reason` branch) | wiring |
| `status/zeitgeist_bridge.py` | the three handlers or `_broadcast_*` entry gain the drain check the `fire_*` functions drop (until #6164 deletes the file) | gate move |
| `status/emit.py` | `_saas_fan_out` (pass `row=event` serialised row), `emit_inner_state_changed` and its two bundling sites in `emit_status_transition` and `emit_status_transition_batch` (new seam call per persisted annotation row) | wiring |
| `coordination/outbound.py` | `_send_to_saas`; `coordination/status_transition.py` call of `_emit._saas_fan_out` | wiring |
| `status/lifecycle_events.py` | `fanout_lifecycle_event_hosted` (row, forward `repo_root`), persist-only callers' fan-out | wiring |
| `decisions/emit.py` | `_queue_decision_fanout` (row) | wiring |
| `retrospective/lifecycle_events.py` | `emit_captured`, `emit_capture_failed`, `emit_skipped` (new seam call) | wiring |
| `events/runtime_moments.py` | `RuntimeMomentProducer._publish` (drain read dropped), `_publish_journalled` (`row=record`), emitter method for `SignificanceEvaluated` | wiring |
| `runtime/next/_internal_runtime/engine.py` | `_append_event` (adds `event_id` ULID and `row_version` 1; keys exactly `event_id,row_version,event_type,timestamp,payload`), `_record_significance` (seam call) | the flagged stamping (FR-035) |
| `status/__init__.py` | `__all__` and imports for the client's public names | facade |
| `status/models.py`, `status_lanes.py`, `migration/rebuild_state.py`, `acceptance/__init__.py`, `consolidation/done_bookkeeping.py` | literal lane tuples replaced by values derived from `Lane`/`CANONICAL_LANES` (FR-034) | constants only |
| `status/lifecycle_events.py`, `decisions/emit.py`, `runtime/.../engine.py` | clearing of `canonical-producer-exempt` markers (comment removal) | markers |
| `state/contract.py` | three `StateSurface` entries | declaration |
| `scripts/lint_canonical_producers.py` | persisted-line mode; baseline not widened | existing script |
| `tests/status/conftest.py` and listed tests | fixture also restores the client | test wiring |
| `src/specify_cli/_completion_manifest.json`, `docs/api/cli-commands.md` | regenerated | generated |

**Gate** `tests/architectural/test_producer_client_thinness.py` (new): (a) AST rules on the three new modules: closed import allowlist (no `uuid`, `ulid`, `hashlib`, `jsonschema`, `spec_kitty_events`, `secrets`, `random`; no `datetime` outside `producer_faults.py`); no `open()`/`Path.open()` in write mode on a path containing the publish-log constant (also AC-030); no `json.loads` outside the reply reader; the exact top-level symbol set per module equals the table; (b) `_append_event` key set equals the five keys; (c) the emission-area module census (`status/`, `events/`, `retrospective/`, `decisions/`, plus the three new files) equals the pinned list, so a new module there fails; (d) no import of `_broadcast_status_transition`, `_broadcast_lifecycle_envelope` or `_envelope_bookkeeping` by the client. Floor: the three modules found and call sites above zero. Self-mutation: a copy gaining an id mint, a timestamp, a schema import, a publish-log read, the bridge's envelope code, or an extra module fails (AC-012). The pinned module list in (c) is priced debt: it is deleted when #6193 retires the Python writers.

### 4.3 Fault, retention, and ledger rules (F-2, F-6)

Python forwards `ledger.retention.keep_runs` and `max_age_days` exactly as read from `.kittify/config.yaml` (or omits them); Java applies the defaults 20 and 30 and validates (AC-031 fixtures live in Java tests, plus one Python test that the raw string `"abc"` reaches Java unchanged). Retain fails closed on an unreadable ledger (section 8).

### 4.4 Lane-list consolidation and marker clearing

First task of the Python WP is the census (FR-034): grep the six sets of data-model section 9, plus any further hit, before editing. `Lane` (`status/models.py`) stays the authority; the other tuples derive from it; a new gate `tests/architectural/test_single_lane_list.py` finds the census lists (floor six) and fails on a new full literal list (mutation case). Marker disposition per data-model section 8: clear 1, 2, 5, 6, 8; markers 3, 4, 7 decided in the WP by the rule "clear only if the persisted line validates in legacy-row form, else residual with an issue reference"; 9 to 16 untouched.

## 5 Request/response protocol

Transport: the client runs `[<binary>]` with no arguments, writes one UTF-8 JSON object to stdin, closes it, and reads one JSON object from stdout. One spawn per row (OD-SPAWN); batching is only the NFR-002 contingency. Shapes: R-4.

- **Versioning.** `"v": 1` in both directions; an unknown `v` is exit 64 (client `crash`). New optional members may be added within `v` 1; removing or retyping one needs `v` 2.
- **Operations.** `produce` (fields: `row`, `slot` `status|lifecycle`, `seam_args`, `start_path`, `deadline_ms`) and `retain` (`start_path`, `dry_run` default true, `policy` raw values, `deadline_ms`). No `nodeid`, no facts, no schema in any request.
- **Reply.** `produce`: `outcome` `produced|skipped_duplicate|refused`, `event_id` (normalised, when available), `type`, `reason_class` (`rejected|clock-overflow`) and a closed `reason` when refused, optional `notice` (`torn-line` with repo-relative path and line), `detail` (one line). `retain`: `eligible`, `kept` (each with reason), `deleted`, `policy_error`. A reply never contains the row, an absolute path, or a stack trace (NFR-006).
- **Exit codes.** 0 a reply was written (refusals are replies); 64 unreadable request or unsupported `v`; 75 deadline reached (watchdog); anything else, a signal, or empty stdout is `crash`.
- **Timeouts.** Client timeout default 3.0 s, always below the effective seam bound (default 10 s) and capped at `bound - 1.0` s when the bound is smaller; `deadline_ms` = that value. The numbers are provisional until AC-036 measures them (section 9); the breaker bounds a batch to one timeout.
- **Fault classes** `binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`; an enum-equality test asserts `disabled` is absent. The mapping table is R-4. stderr of the child is discarded.

## 6 The spec-kitty-events PR

**Separate PR, in the events repo, sequenced first and released** (FR-040). This plan cannot write to that repo; the orchestrator opens it. Content (FR-014, exactly): per-kind `data` JSON Schemas for the types of data-model section 3 (v1) with lifted-member lists; registry entries (type, scope, `subject` rule, lifted members, schema file) in one machine-readable registry file the Java build reads, so Java contains no per-type code; `dataschema` URIs; producer conformance fixtures (valid and invalid per type, legacy-row-form variants generated from the single schema plus the lifted list, FR-032); the total-order comparator specification and the nine fixtures of data-model section 4 with expected results; `spec_kitty_events.Lane` alignment (data-model section 9); conditionally the minimal envelope subset (FR-035). Confirmations asked of that PR: the INTERIM choices of the spec, the `source` tie-break, `schemaversion`, the `dataschema` base URI, and that #92 adopts the operator-ruled prefix and interim type names (F-3).

**Dependency, gating, pin.**
- The Java build fetches the released wheel `spec_kitty_events-<V_E>-py3-none-any.whl` (the version the events repo assigns; this plan assigns none, C-006) with a plain Gradle task: URL and sha256 in `services/event-producer/gradle/events-pin.properties`, sha256 checked before unzip, schemas and registry extracted into `build/` and embedded in the native image as resources. Nothing from the events repo is committed (`tests/architectural/test_no_committed_events_schema.py`, floor: the pin file found; mutation: a planted `*.schema.json` carrying an events `$id` fails; AC-027). The wheel is used because PyPI is the only release channel (R-15).
- `pyproject.toml` raises the `spec-kitty-events` floor to `V_E` (`uv.lock` follows) and `tests/contract/spec_kitty_events_consumer/` pins the new surface; the same release feeds both consumers.
- **While unreleased.** Java contract tests run against a pinned released version only. A local override (`-PeventsWheel=<path>`) exists for development and is refused when `CI` is set. No work package that reads the schemas is approved, and the mission PR is not marked ready, before the pin names the released wheel. Work that does not read schemas (WP01 spike, clock and lock, protocol, retention, wheel machinery with a stub binary) proceeds.
- **Reconciliation.** One schema set, one writer per type: events#92 keeps envelope schema, validator, upcaster, STRICT registry and the reducer sort-key switch; this PR carries only the FR-014 list and asks #92 to acknowledge each. #6193 reuses this producer's registry and domain (section 1). The registry file is the join with #92's registry: the PR is rebased on #92 if #92 lands first, and the minimal envelope subset is dropped.
- **Events-repo charter amendment.** The events repo charter says Python library and Pydantic (#6117 decision 11); it needs an amendment allowing JSON Schemas and fixtures as the contract consumed by non-Python producers. Owned by the events PR or #92; flagged in section 13.

## 7 Clock state file

Detail: R-5.

- **Path.** `<git-common-dir>/spec-kitty/event-producer/clock.json` and sibling `clock.lock`; the common dir is resolved by reading `.git` and `commondir` files, never by spawning git. Primary checkout and linked worktrees share it; separate clones share only the `nodeid` (accepted residual).
- **Format.** `{"v":1,"nodeid":"<12 hex>","clock":<int>}`, integer 0..2^31-1.
- **Locking.** One `FileChannel.lock()` exclusive lock on `clock.lock`, held across read, stamp, dedupe, state write and append. Linux and macOS: `fcntl` advisory byte-range lock (opened once per process; closing any descriptor of the file releases it); Windows: `LockFileEx` mandatory lock. One row per process, so intra-JVM overlap cannot occur. Wait bounded by `deadline_ms`. Local file systems only.
- **Stamp and append as one operation, state first.** state temp-file, `fsync`, atomic move, then append and `fsync`. A kill leaves no advance, or an advance and no line (a gap); never a reused value (AC-010 injects the three kill points through a `FaultInjector` port whose only non-no-op adapter lives in the test source set).
- **State loss.** Next stamp = max(state, highest `lamportclock` readable across publish-log files) + 1, torn fragments skipped. State is never a retention candidate (tested).
- **Overflow.** Above 2^31-1 the row is refused as `clock-overflow`; no wrap.
- **nodeid.** SHA-256 of `hostname:username` (UTF-8), first 12 hex; hostname through an FFM downcall to the same C library `gethostname` Python calls (`ws2_32` on Windows); username from `LOGNAME`, `USER`, `LNAME`, `USERNAME` in that order, then the JVM's `user.name`. Golden test per OS (AC-039): the live case plus at least two injected strings, one non-ASCII.

## 8 Retention in Java

Detail and file table: R-6.

- **Operation shape.** `retain` request (section 5) handled by `Retain`: lists candidate files in the local tier, derives facts, applies `RetentionPolicy` (defaults 20 and 30, validation with a named `policy_error`, also on a dry run, AC-031), returns eligible/kept/deleted. `dry_run` is true unless the client sends false; the doctor command sends false only with `--apply`.
- **Whole-file deletion only.** The producer opens no file for write in `retain`; it calls delete on whole files. Candidates come only from the local-tier directories, never from `kitty-specs/`; AC-014 offers every tracked ledger as a candidate and asserts zero writes and zero deletes on tracked paths.
- **Placement and "run".** Publish log: `.kittify/events/publish/missions/<missionid>.jsonl` and `project-<project_uuid>.jsonl`; run = one Mission (one file). Fault log (client-written): `.kittify/events/faults/<UTC date>.jsonl`; run = one UTC day. Run journals: existing per-run file; run = one run. Each file's age is its newest row time (fault log: its date).
- **Mission open.** Derived in Java from the committed ledger(s): open iff any work package's latest row (greatest `at`) has `to_lane` other than `done`/`canceled`; both the canonical checkout copy and a coordination worktree copy are read, open if either says so. **Unreadable, unparseable, or absent ledger: the Mission's files are kept ("facts unavailable"); tested under AC-013 (F-6).** Lock facts: a lock file present; unfinished step: a run journal without `MissionRunCompleted`.
- **Dry run first.** `spec-kitty doctor events-retention` (dry run), `--apply` to delete; never implicit.
- **Doctor pass-through rendering.** `doctor events` prints binary presence, fault count in the window, last class, notices apart, and the `migrate backfill-identity` hint when `mission-id-missing` faults exist; `doctor events-retention` renders the reply lists (repo-relative). `--json` uses `cli/json_contract`. Both are commands of `_event_producer_doctor.py`; the Python side renders and counts, nothing more.

## 9 Test strategy and gates

**Method (Standing Order 4).** Every behaviour has a test committed red first, as its own commit before the implementation, verified red on the WP's base and green on its tip, and revert-checked (revert the implementation hunk, the test must fail). Floor and self-mutation cases on every census gate (spec floor rule). Golden files come from the real serializer (#4182). Each WP declares its targeted test surface; no whole-directory or `make test-full` run in mission work.

**Python gates (shim).** New: `tests/architectural/test_producer_client_thinness.py` (section 4), `test_single_lane_list.py`, `test_no_committed_events_schema.py`, `test_publish_log_single_writer.py` (AC-030), `test_seam_census.py` (AC-004: universe derived from `AUTHORITATIVE_NON_LANE_EVENT_TYPES`, retrospective, lane, annotation and run-journal kinds; one forced row per writer yields one seam call), `test_producer_fault_enum.py` (no `disabled`), `test_exemption_ledger.py` (AC-019, persisted-line validation against the released schemas). Behavioural tests in `tests/status/` and `tests/specify_cli/status/` for AC-001 to AC-007, AC-011, AC-018, AC-021 to AC-023, AC-025, AC-026, AC-033, AC-035. Existing gates re-run: the files of R-7. Coverage: diff-cover >= 90% on changed Python lines (CI), new Python modules at 95% by tests of every branch.

**Gradle gates (Java).** `gradle -p services/event-producer test`: unit tests per subproject; ArchUnit (section 1); the golden `nodeid` test against fixed strings plus a CI step on each OS comparing the live value with `identity/project.py::generate_node_id` output (AC-039; the Python side is one line in the smoke script); order fixtures (AC-008/009, reference comparator, mutations); clock concurrency and kill tests (AC-010); torn line (AC-026); determinism and byte rules (AC-034); contract tests against the pinned released events wheel (schema validity of every in-scope type, comparator results equal, AC-002/008/027); retention (AC-013/014/031); dependency verification (G-13, with a tamper test); JaCoCo floors: `:domain` 95% line and 90% branch, `:application` 90% line, `:adapter` 80% line, `:launcher` excluded (tiered rigour, NFR-005); the choice is the plan's, the charter has no Java row (section 13).

**Packaging and release gates.** Section 3.4: hook off/on tests, platform-wheel content, dist-set equality with mutation, sdist/editable without binary, `check_exact_install` per wheel, `smoke-wheels`, SBOM check (AC-040), checksum continuity (AC-032), `packs/internal/` absent (AC-033), `twine check`, `uv lock --check`.

**CI home.** New `.github/workflows/event-producer.yml` on `pull_request` and `push` to `main` with path filters `services/event-producer/**`, `scripts/release/**`, `pyproject.toml`, `src/specify_cli/status/producer_*.py`, `src/specify_cli/_native/**`, itself and `producer-native.yml`; jobs: `verify-pins` (installer checksum, pin file vs build declarations as a Gradle task), `gradle-test` (ubuntu, JDK 25, JaCoCo, ArchUnit, dependency verification, tamper check), `native-smoke` (three legs through `producer-native.yml`, then wheel by path and smoke script on each OS), terminal `event-producer-gate` listing the others (the `contracts-gate` pattern). Every root job carries the fork guard first (`tests/ci/test_fork_guard.py`). Registered in `scripts/ci/fleet_verdict.py::PR_WORKFLOWS`, `ci-fleet-verdict.yml` and `tests/ci/test_fleet_verdict.py` (R-6). Nightly: `ci-nightly.yml` calls the same smoke path (AC-036 numbers recorded there).

**Performance (AC-036/NFR-002).** `tests/_perf_helpers.py` gains the producer limits (relative method; the repo rejects absolute assertions, `test_perf_limit_authority.py`): ratio of the largest multi-row command (`emit_status_transition_batch`) with a real producer to the same command with a no-op handler, cold and warm, per OS; the 2 s charter budget is the ceiling to reason from. Numbers are set from WP01 and WP10 measurements; if one spawn per row fails, the contingency of NFR-002 (buffer and flush once at exit) applies after amending FR-003, FR-026 and AC-004.

**Enforced CI gates not included, with reasons** (R-8 for the verified list): mypy (not run in CI; run locally on the new modules as the charter asks); Bandit and pip-audit (invoked nowhere in CI; Java dependency verification is the new supply-chain gate); SonarCloud (Python-only `sonar.sources`; reported, never blocking); `ci-windows.yml` (cannot test a built wheel; `smoke-wheels` and `native-smoke` do); `contracts.yml` (no `contracts/**` change); commitlint and markdownlint (advisory in CI; run locally); architectural fast/heavy, module matrix, nightly (CI owns the full runs; locally only the files of R-7).

**Gate-implication audit conclusions** (table: R-7). The files that must be read or re-pinned are listed in R-7; the ones that bite hardest are `test_status_module_boundary.py` (SR-2: client names go through the `status/__init__.py` facade), `test_runtime_emitter_seam.py`, `test_hosted_drain_gate.py`, `test_completion_manifest_freshness.py`, `test_release_ci_ownership.py`, `test_fleet_verdict.py`, and `tests/status/conftest.py` with its registry tests. Other gates: ruff check and format are hard-enforced (`--force-exclude` on per-file runs); `TID251`; `uv lock --check`; `spec-kitty regen --check`; docs lint for the ADR and glossary prose; the terminology guard. **Contextive** (FR-046): terms go to `docs/context/system-events.md`; `generate_contextive_glossaries.py generate` then `check`; because no CI job runs `check` (R-17), a targeted test runs it (spec touch-up 15.5).

## 10 Baseline method

Before the first change the orchestrator captures pre-existing red from a detached scratch worktree of the base commit: each gate file of R-7 and the owning fast tiers, one invocation per file, results in `research/baseline-red.txt` (base sha, command, counts, failing node ids). A red node id in that list with the same failure text is pre-existing; any other is introduced and is the WP's to fix; a lock timeout of the shared test venv is rerun once alone before it is recorded. Each distinct pre-existing red is reported for the issue the charter's reporting rule requires. Known-P0 reproductions are not run. Detail: R-9.

## 11 Work packages and PR shape

**One PR** (draft, `main`), compact history, `spk-land-pr` pass before it sits ready. **No slices**: the Java tree, wheel machinery, client and release workflow are only useful together, and every WP leaves its lane green on its own gates. The events-repo PR is a separate repo and goes first. If #6164 or the events release forces a long wait, the only legitimate split is the Python-only seam census and client (WP06, WP07) behind a stub producer, which would still need the whole contract; not planned.

**Campsite-clean (Standing Order 2).** A distinct, behaviour-preserving first commit: fix the stale "PyPI Release" paragraph of `AGENTS.md` (it says `release.yml` is absent; it exists) and the matching line in `scripts/release/README.md` if stale. Domain-matched, docs only. No refactor of `release.yml`'s long "Verify wheel contents" step because `test_release_ci_ownership.py` pins its text. No Sonar findings were measured for the touched Python files (no Sonar access): none claimed.

| WP | Scope | Write-scope notes | Depends on |
|---|---|---|---|
| WP01 | Spike: native image on three OS, stub wheel with hook, `producer-native.yml`, `event-producer.yml` skeleton, pins, verification metadata | **risky first**; owns `services/event-producer/` skeleton | none |
| WP02 | `:domain`: ids, clock value, order and fixtures, envelope model, retention eligibility, ArchUnit | `:domain` only | WP01 |
| WP03 | `:adapter` files: lock, clock store, publish log (torn line, dedupe, determinism), repo locator, nodeid FFM and golden | `:adapter` | WP02 |
| WP04 | `:application` `Produce`, launcher protocol, schema validation against the pinned events wheel, faults vocabulary | needs the events release for the contract tests | WP03, events release |
| WP05 | `Retain`, committed-ledger reader, policy | `:adapter` ledger package | WP03 |
| WP06 | Python client, seam wiring, census, thinness gate, fault log | **sole owner of `status/adapters.py`** | WP04 (protocol), #6164 handled per section 12 |
| WP07 | Journal stamping, lane consolidation, marker clearing, state contract, doctor sibling, completion manifest and CLI docs, glossary and Contextive, ADRs | owns `engine.py`, `state/contract.py`, lint script | WP06 |
| WP08 | Packaging: hook, `scripts/release/*`, packaging fixtures and tests | **sole owner of `pyproject.toml`** (also the events floor bump) | WP01 |
| WP09 | `release.yml`, `ci-nightly.yml` call, fleet verdict registration, SBOM, attestation, release docs | **sole owner of `release.yml`** | WP08 |
| WP10 | Integration: default-install end to end, AC-036 measurement, AC-025, mid-flight Mission (AC-017), reflexivity check, final gate pass | read-mostly | all |

Shared-gate chokepoints (`tests/architectural/_baselines.yaml`, `dead_symbol_allowlist.yaml`, `tests/ci/test_fleet_verdict.py`, `tests/status/conftest.py`): one owning WP each (WP07, WP07, WP09, WP06); other WPs report needs to the owner. Risky and unknown work first: WP01 (native image), then WP08's stub-binary wheel build is exercised in WP01 already on all three OS.

**Tracer files** are seeded beside this plan (Standing Order 3).

## 12 Interaction with #6164

Not landed. The seam fires the client irrespective of drain, so either order is correct. In `status/adapters.py` the mission makes four separable edits (R-11): (A) delete the drain gates and the `hosted_posture` import, (B) `row` and fault-recorder calls in the busy and timeout branches, (C) client registration outside the `moment_handlers_disabled_reason` branch, (D) docstring and fixture. Order of commits in WP06: A first (so a conflict is isolated), then B, C, D. If #6164 lands first: A and the `zeitgeist_bridge` import block conflict as "deleted by them"; take #6164's version, A becomes empty, C is re-added under #6164's import tail. If this mission lands first: #6164 removes the Zeitgeist registration and the lines A left; nothing of ours remains in them. The drain gate moves into the three Zeitgeist handlers (`status/zeitgeist_bridge.py`) while that file exists, so `hosted.drain` keeps its meaning (C-003); the confirmation pin test is re-pointed at every Zeitgeist handler (AC-003 f). The other silencers of FR-002: `RuntimeMomentProducer._publish` drain read (removed by this mission; if #6164 deleted `hosted_posture` first the hunk is a no-op), the `moment_handlers_disabled_reason` branch (client registered outside it), and the status test fixtures (they restore the client as well). AC-003 is written as a parametrised test that skips (a) when `zeitgeist_bridge.py` is gone and runs (b) always.

## 13 Governance drift to flag

Flagged, not resolved here.

1. **Charter has no Java gate row and says Python 3.11+ for all CLI and library code.** Java build, ArchUnit, JaCoCo, dependency verification and native-image gates in this plan are the plan's choice; the charter needs a row. Coverage "90%+ for new code" and diff-cover are Python measurements.
2. **Python outside the thin client.** The rule "Java only, thin shims excepted" meets two unavoidable Python artefacts: the hatch build hook and the release set-check script. See fork F1.
3. **ADR 2026-10-13-2 D-1, D-2, D-3 need amending.** Choice: **amend that ADR in place** (new "Amendment" section of the Accepted ADR, the practice of ADR 2026-10-06-1) for the three departures (CloudEvents rows supersede "flat rows" for new rows; the publish log, fault log and clock are an added read path; the closed local-tier list gains three files), because D-1 to D-3 are that ADR's decisions and one authority per decision is the charter's rule; **and add one new ADR** `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md` for decisions no ADR owns (Java strangler producer, native platform wheels, pure fallback wheel, unsigned binaries, released-artifact schema delivery). `docs/adr/4.x/index.md` updated. AC-033 checks the amendment names D-1, D-2, D-3.
4. **Events-repo charter** (Python library, Pydantic) needs an amendment (section 6).
5. **No-vendoring rule.** Satisfied: the events artifact is a released, pinned, sha256-checked wheel consumed at build time; no source, schema or binding is committed; a gate enforces it. The pin file is a lockfile-style record, as the charter allows ("exact artifact resolution in lockfiles and release records").
6. **`AGENTS.md`/`CLAUDE.md` versus the checkout:** "PyPI Release" says `release.yml` is absent; fixed in the campsite commit. Charter "PyPI distribution via automated release workflow" has no row for platform wheels or native binaries.
7. **Spec says Contextive regeneration is CI-enforced; no workflow runs it.** Handled by a targeted test (15.5).
8. **Reflexivity and mid-flight.** This mission's own events come from the Python writers and no gate of this mission depends on the producer. A Mission open at upgrade keeps its ledger byte-unchanged and gets a publish log from its first later event (AC-017); a Mission without `mission_id` is refused per event with the repair named. Missions in flight when this lands need no migration step; the new `StateSurface` entries and the existing `.kittify/events/` ignore entry cover them.

**Glossary additions** (spec section 13; definitions in carryover L) go to `docs/context/system-events.md`: Producer, Publish seam, Publish log, Ledger, Fan-out (not "sync"), Clockless row, Clocked row, Fault log; `generate` and `check` run after.

## 14 Traceability

| Requirement group | Plan section |
|---|---|
| FR-001 to FR-005 (seam, coverage, row) | 4.2, 5, 12; ACs 001 to 004 in 9 |
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
| G-1 to G-13 | 3.4 |

## 15 Spec touch-ups proposed to the orchestrator

(Not edited here.)
1. **FR-027**, add: "The allowed Python surface is the file-and-symbol list of plan section 4.2; the gate fails on any other new module or function in the status and emission area, and the list is its data."
2. **FR-028 / data-model section 6** (F-2), add: "Python forwards the raw `ledger.retention.*` values from `.kittify/config.yaml` unread; Java applies the defaults 20 and 30 and validates them, returning the named policy error that `doctor` renders."
3. **C-008** (F-3), replace with: "The events PR follows spec-kitty-events#92 for the `dataschema` base URI, the `lamportclock` type, lifted-member lists and minimal-envelope timing, and for type names except the operator-ruled prefix `ai.spec-kitty` and the INTERIM names of data-model section 3, which that PR is asked to adopt; the `source` tie-break added to the total-order key goes to that PR to confirm (OD-EVENTS)."
4. **data-model section 4 clockless definition** (F-4), replace the sentence with: "A clockless row has no `lamportclock` member at all; a present value that is not an integer in 0..2^31-1 (out of range, `null`, a string, a boolean) is not clockless but an error row." and in the error-row list: "a `lamportclock` that is not an integer in 0..2^31-1".
5. **FR-046 / AC-033**: replace "CI-enforced" by "enforced by a targeted test added by this mission (no CI job runs the generator's `check` today)".
6. **data-model section 6 Retain** (F-6), add: "An unreadable, unparseable or absent committed ledger makes that Mission's items kept ('facts unavailable'); AC-013 covers it."
7. **FR-023**, state that the `retain` operation's failures are `policy_error` or kept-with-reason replies, not event faults.

## 16 Operator forks

**F1. Python build glue under a Java-only rule.** Platform wheels need a hatchling build hook (Python by the backend's design) and a release set-check script that compares `dist/` with the pinned list (AC-037). Options: (a) allow these two bounded files plus edits to existing `scripts/release/*.py` and `tests/`, no product logic; (b) implement the set-check as a Gradle/Kotlin task in the producer build (the hook stays Python, unavoidable); (c) change the backend away from hatch (rejected: far larger). Recommendation: (a), recorded in the new ADR and listed in the FR-027 surface, because (b) adds a JVM dependency to the Python release path for no safety gain.

No other fork was found: the Linux wheel floor, JaCoCo thresholds, the file placement of the logs, and the CI workflow shape are plan decisions with a stated reason.

## 17 Claims not verified

Full list: R-12. Material: the chosen GraalVM build, flags and the Linux container route (WP01 proves them); Windows/Java hostname agreement (AC-039); events-repo file names; SHA-pinned action versions (WP09); #3283 and #3284 are not in this repository; `spec-kitty plan --json` was not run (files written directly); hatchling is absent from this checkout's venv, so hook semantics were read from the cached 1.31.0 source, not executed.
