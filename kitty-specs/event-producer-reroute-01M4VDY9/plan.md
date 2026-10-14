# Implementation Plan: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 | **Branch**: `feat/event-producer-reroute-6185` (target `main`) | **Date**: 2026-10-14
**Inputs**: [`spec.md`](spec.md), [`data-model.md`](data-model.md), [`plan-carryover.md`](plan-carryover.md), `reviews/spec-amend-verify.yaml` (F-1 to F-6 in sections 4, 8, 15), `reviews/plan.confirmed.yaml` (round 1, absorbed throughout), the operator rulings of 2026-10-13. Evidence, gate-audit tables, alternatives and the unverified list: [`research.md`](research.md) (R-n is research section n; P-n is row n of its section 1).
**Charter**: read; it wins over `AGENTS.md`/`CLAUDE.md` (`CLAUDE.md` is a symlink to `AGENTS.md` here). Drift is flagged in section 13, not resolved.

The spec has no open decision. The one fork is in section 16 and blocks nothing.

## 1 Module layout

**Location and build.** `services/event-producer/` at the repository root (a sibling of `contracts/` and `acceptance-jvm/`; no dependency on either or on unlanded #5529 code). Kotlin DSL (the brief's choice; P8), Gradle 8.14.5 installed by `contracts/tools/install_tools.py --pins services/event-producer/tools/pins.json --only gradle` (reused unchanged). Java 25 by toolchain (`languageVersion = 25`, as `acceptance-jvm`, ADR 2026-10-01-2); CI uses the SHA-pinned `actions/setup-java` form of `contracts.yml`; native builds use the GraalVM JDK 25 archive pinned in `pins.json`. A committed, Gradle-generated `gradle/verification-metadata.xml` (`sha256`) covers every dependency and plugin, with a tamper check as in `contracts/` (a planted checksum change fails the build).

**Subprojects and packages** (root package `ai.speckitty.producer`):

| Subproject | Contents | May depend on |
|---|---|---|
| `:domain` | `ids` (ULID parse and normalise, UUID refusal), `clock` (value type, bound 2^31-1, overflow), `order` (total-order comparator, dedupe, error rows), `envelope` (attribute names, type registry model, lifted-member mapping driven by data), `retention` (eligibility over supplied facts, policy defaults and validation), `fault` (reason vocabulary), `protocol` (request and reply value types) | `java.base` only |
| `:application` | use cases `Produce`, `Retain`; ports `RowSchemaValidator`, `PublishLog`, `ClockStore`, `RepoLocator`, `NodeIdentity`, `LedgerFacts`, `Clock` | `:domain` |
| `:adapter` | JSON (Jackson, only here), schema validation, file system, `FileChannel` lock, git common-dir resolution by reading `.git`/`commondir`, hostname through the Foreign Function and Memory API, committed-ledger reader and lock-record reader for retention (reads sidecar bytes, takes no OS lock on a Python-owned file) | `:application` |
| `:launcher` | `main`, stdin/stdout protocol, exit codes, deadline watchdog, native-image configuration | all |

**Rules enforced by ArchUnit (a test in `:domain`/`:application`):** `:domain` imports only `java.base`; `:application` imports no `:adapter` or Jackson; no class in `:domain` reads a clock, environment variable or file. Every rule carries a floor (scanned count above zero) and a self-mutation case.

**Absorption.** #6192 lifts `:domain` into `domain-model` and the `ClockStore`, `PublishLog`, `Produce` parts into `event-log` by moving packages. #6193 replaces `LedgerFacts` (the only code that opens a committed ledger) and swaps the publish-log writer behind the same port.

## 2 Native image build per platform

**Toolchain pin.** GraalVM Community JDK 25, one release chosen in WP01 from the stable `jdk-25.0.x` tags; archive URL and sha256 in `tools/pins.json`; the build fails when `native-image --version` differs. Three legs only (FR-041, OD-MATRIX), no musl wheel unless WP01's fallback applies (R-2).

| Leg | Runner label | Binary | Wheel tag |
|---|---|---|---|
| Linux x86_64 | `ubuntu-24.04`, build in a pinned `manylinux_2_28` container | `spec-kitty-event-producer` | `py3-none-manylinux_2_28_x86_64` (pinned in `dist-set.json`, created by WP01) |
| macOS arm64 | `macos-14` | same | `py3-none-macosx_11_0_arm64` (pinned) |
| Windows x86_64 | `windows-2022` | `spec-kitty-event-producer.exe` | `py3-none-win_amd64` |

**Tag authority.** `scripts/release/dist-set.json` is the single authority for the wheel tags (the floors); WP01 creates it with the three platform entries (`{"v":1,"platform_wheels":[{"leg","tag"}x3]}`) and its workflows and stub-wheel test read the tag from it (`jq`/`json`, no new script); WP08 extends it additively (`sdist`, `pure_wheels`), never renaming a WP01 key. The hook is given the tag from it, never from the binary. The measured maximum `GLIBC_` symbol and macOS `minos` are assertions: the leg fails when the measurement exceeds the pinned floor (a lower measurement is fine). If WP01 falls back to musl, the pinned tag changes with it.

**CI shape.** Reusable workflow `.github/workflows/producer-native.yml` (`workflow_call`, required input `ref`, matrix over the three legs, `fail-fast: false`) checks out `ref`, builds and uploads `producer-<leg>` (binary plus `producer-manifest.json`). `release.yml` calls it in a new job `build-producer` passing the release ref; `event-producer.yml` passes `github.sha`. `release.yml` repeats the `RELEASE_REF` expression in the job-level `with:` (`env` is unavailable there; a test pins equal texts; R-12). The assembling job is the existing `build-release` (name pinned, P4).

**Provenance (FR-042, AC-032).** Per-leg manifest (binary sha256, toolchain sha256, source commit, measured floor); `build-release` fails unless every `source commit` equals `git rev-parse HEAD` and recomputes each sha256 before building a wheel; the hook writes `specify_cli/_native/PRODUCER.sha256`; `check_dist_set.py --verify-binaries` re-hashes each member; `publish-pypi` attaches an attestation. No reproducibility claim.

**Unsigned (OD-SIGN).** The macOS binary is never stripped (ad-hoc signature); the smoke job runs `codesign --verify` (R-2).

**WP01 exit criteria (feasibility gate, first work package).** On all three runners a trivial producer (stdin JSON in, JSON out, Jackson, one schema validation, `FileChannel` lock, `gethostname` downcall) builds, starts under 100 ms warm, and a stub wheel built with WP01's own hook installs and runs by path on its OS. If a leg fails, the fallbacks (musl static on Linux; hand-written reachability metadata) are tried in WP01; if a leg still fails the mission stops and the operator is told (FR-041 floor).

## 3 Wheel build machinery

Current state (P2, P3, P10, P11): `hatchling==1.31.0` backend; one pure wheel plus sdist from `python -m build` in `release.yml` job `build-release`; every wheel-handling step assumes one wheel.

### 3.1 Build backend change (stays hatch)

`pyproject.toml` gains only `[tool.hatch.build.targets.wheel.hooks.custom]` with `path = "scripts/release/hatch_build_producer.py"` and `enable-by-default = false`. The hook runs only when `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`; it then requires `SPEC_KITTY_PRODUCER_BINARY` and `SPEC_KITTY_PRODUCER_PLATFORM_TAG` (the latter read from `dist-set.json` by the caller) and sets `pure_python = False`, an explicit `tag = py3-none-<platform>`, and a `force_include` of the binary to `specify_cli/_native/<name>` (plus `PRODUCER.sha256`). On non-Windows tags it force-includes a copy chmod-ed `0755` (artifact transfer drops mode bits, R-3). Unset, nothing changes: `uv sync`, editable installs, `uv build --wheel`, the shared test build, the sdist and the sdist-to-wheel step produce today's output (C-007, AC-038); the sdist `include` list is not widened. `.gitignore` gains `src/specify_cli/_native/` (hatch honours `.gitignore`; otherwise a developer's copied binary would ship in the pure wheel and sdist).

### 3.2 Files that change, and owners

WP01 (needs a working hook and the tags to prove the stub wheel): `scripts/release/hatch_build_producer.py`, the `pyproject.toml` hook table, `.gitignore`, `dist-set.json` (three platform tags) with a test that it holds exactly the three legs the workflows build, and the minimal stub-wheel packaging test. WP08 (the rest): `check_dist_set.py` and the extension of `dist-set.json` (one sdist, `py3-none-any`; AC-037, G-12), `check_exact_install.py` (`--wheel-tag`; untagged multi-wheel still refuses), `tests/_support/shared_build_artifacts.py` (added platform-wheel fixture), the other packaging tests, `scripts/release/README.md`. The events floor bump is WP11's. WP09: `release.yml`, `RELEASE_CHECKLIST.md`, `CONTRIBUTING.md`. WP01 owns `producer-native.yml`.

### 3.3 `release.yml` change (WP09; step list: R-13)

New job `build-producer` (reusable workflow, release `ref`); `build-release` keeps its name, gains `needs: build-producer` and the checks, platform-wheel builds, `check_dist_set.py`, `check_exact_install.py --wheel-tag`, looped wheel-content verification and producer SBOM; new job `smoke-wheels` (three OS legs, matching wheel by path; the pure wheel on one leg); `publish-pypi` needs both, re-runs `check_dist_set.py` and attests. Standing Order 9: every new stage is a `needs` edge, no `continue-on-error`.

### 3.4 Gates touched

R-3, R-7, R-13. New assertions: `test_release_ci_ownership.py` (`build-producer` with its `ref` input, `smoke-wheels`, the `needs` edges); packaging tests (binary only in platform wheels, with exec bit; hook-off wheel and sdist binary-free **with a stub planted untracked in `_native/`**, present only with the hook; `git check-ignore` pins the entry).

## 4 Thin Python client contract

### 4.1 What Python may do, each with its file

| Capability (FR-026) | File | Symbol(s) |
|---|---|---|
| locate the platform binary (package-relative `_native/`; no env override) | `src/specify_cli/status/producer_client.py` | `locate_binary` |
| spawn (argv list, no shell), pass request, read one reply (cap 1 MiB; the one reply decode site) | same | `ProducerClient.produce`, `ProducerClient.retain`, `_read_reply` |
| keep the client timeout strictly below the effective seam bound (also when disabled); the bound resolver is injected by `adapters.py::ensure_producer_client(bound_resolver=_saas_fanout_timeout_s)`, so the client imports nothing from `adapters` | same | `effective_client_timeout(bound)` (rule: section 5) |
| terminate its own child by handle | same | inside `ProducerClient._run` |
| per-process breaker | same | `ProducerClient` state |
| map any failure to a fault class | same | `FaultClass` mapping in `_classify` |
| the seam handlers (never raise) | `status/producer_client.py` | `produce_status_handler`, `produce_lifecycle_handler` |
| register on the seam; inject the bound resolver | `status/adapters.py` (not the client module) | `ensure_producer_client` |
| fault recording: stderr line in every mode, fault-log append (single writer), notice rows, count and last class for `doctor`; root via `resolve_canonical_root` for the location only. Holds the only clock read (`_now_utc`: the row's `at` and the UTC day file name) and the second decode site (`_read_fault_rows`) | `src/specify_cli/status/producer_faults.py` | `FaultClass`, `record_fault`, `record_notice`, `write_stderr_warning`, `fault_summary`, `_now_utc`, `_read_fault_rows` |
| doctor pass-through: raw `ledger.retention.*` values forwarded, reply rendered | `src/specify_cli/cli/commands/_event_producer_doctor.py` (self-registering sibling, no edit to `doctor.py`) | `register`, `events_health`, `events_retention` |
| OD-JOURNAL stamping | `src/runtime/next/_internal_runtime/engine.py` | `_append_event` only |
| seam wiring (FR-002 to FR-004) | files in 4.2 | named symbols |

 The client holds no schema, id, event clock, order, dedupe or type knowledge. Its handlers never raise (the outermost catch maps every failure to a fault). Fault `event_type`/`event_id` are opaque copies of the row's. No environment override of the binary and no producer setting (OD-OPTOUT); tests patch `locate_binary`.

### 4.2 Seam wiring and the allowed-surface list (resolves F-1)

FR-027's gate takes this table as its data. It is the complete list of Python changes this mission may make; the gate fails on any other new module or function in the status and emission area. A file's new top-level functions, classes, methods and imports (any depth, as `(module, name)`) must lie within its row; the per-file lists are R-16 and are identical to the rows here. A file named in several rows is unioned (allowed names and imports are the union of its rows; a row naming several files applies to each).

| File | Allowed symbols (new or changed) | New imports | Nature |
|---|---|---|---|
| `status/adapters.py` | `fire_saas_fanout`, `fire_lifecycle_saas_fanout`, `fire_resolved_binding_fanout` (drain gate removed; `row` passed through; busy and timeout branches call `record_fault` **only for a client handler's `_handler_key`**), new `_dispatch_handlers` (campsite fold of the three `try/except` ladders, WP06's first commit), `ensure_zeitgeist_moment_handlers` (logic unchanged), `reset_handlers` (docstring), new `ensure_producer_client` (registers outside the `moment_handlers_disabled_reason` branch, injects `bound_resolver=_saas_fanout_timeout_s`) | `producer_client`, `producer_faults.record_fault` | wiring |
| `status/zeitgeist_bridge.py` | the handlers or `_broadcast_*` entry gain the drain check the `fire_*` functions drop (until #6164 deletes the file) | `specify_cli.core.hosted_posture` | gate move |
| `status/emit.py` | `_saas_fan_out` (`row=` serialised row), `emit_inner_state_changed` and its two bundling sites in `emit_status_transition` and `emit_status_transition_batch` (seam call per persisted annotation row) | none | wiring |
| `coordination/outbound.py`, `coordination/status_transition.py` | `_send_to_saas`; the `_emit._saas_fan_out` call | none | wiring |
| `status/lifecycle_events.py` | `fanout_lifecycle_event_hosted` (row; forward `repo_root`), persist-only callers' fan-out | none | wiring |
| `decisions/emit.py` | `_queue_decision_fanout` (row) | none | wiring |
| `retrospective/lifecycle_events.py` | `emit_captured`, `emit_capture_failed`, `emit_skipped` (seam call) | one function-local name from the `specify_cli.status` facade (the fan-out entry) | wiring |
| `events/runtime_moments.py` | `JournalRecord` (new field `row`: the raw parsed journal line, `compare=False`), `latest_matching_record` (fills it), `_publish` (drain read dropped; accepts a payload model or dict), `_publish_journalled` (adds `row=record.row`; its `envelope=` stays for the Zeitgeist handler only), `emit_significance_evaluated` (filled; today a no-op), new method `emit_decision_authority_denied` | `dataclasses.field` (`hosted_posture` import deleted) | wiring |
| `runtime/next/_internal_runtime/events.py` | `RuntimeEventEmitter` Protocol and `NullEmitter`: new method `emit_decision_authority_denied(payload: dict)` | none | journal route |
| `events/decision_log.py` | `DecisionGitLog.emit_decision_authority_denied` (delegates to `_inner`) | none | journal route |
| `runtime/next/runtime_bridge_retrospective.py` | `_BufferingRuntimeEmitter.emit_decision_authority_denied` (records, as its siblings) | none | journal route |
| `runtime/next/_internal_runtime/engine.py` | `_append_event` (adds `event_id` ULID and `row_version` 1; keys exactly `event_id,row_version,event_type,timestamp,payload`; no new function), `_authorize_audit_answer` (new `emitter` parameter, one `emit_decision_authority_denied` call after the denial append; `_apply_decision_answer` passes it), marker 8 comment removal | `ulid` (as `decisions/emit.py` imports it) | FR-035 stamping, journal route |
| `status/__init__.py` | `__all__` and imports for the client's public names | the client names | facade |
| `status/models.py`, `status_lanes.py` | none expected (`Lane` and the import-light `CANONICAL_LANES` literal stay) | none | census only |
| `migration/rebuild_state.py` | `_VALID_LANES` derivation | `specify_cli.status_lanes.CANONICAL_LANES` | constants (FR-034) |
| `acceptance/__init__.py` | `_LEGACY_NOT_DONE_LANES` derivation, `doing` alias constant | `Lane` added to its existing `specify_cli.status` import | constants |
| `consolidation/done_bookkeeping.py` | `_PRE_APPROVED_LANE_VALUES` selected by name | `Lane` at runtime (today under `TYPE_CHECKING`); a cycle makes it a reviewed exception | constants |
| `status/lifecycle_events.py`, `decisions/emit.py`, `events/decision_log.py`, `migration/mission_state.py` | `canonical-producer-exempt` comment removal for markers 1, 2, 5, 6 and, by the 4.4 rule, 3, 4, 7 | none | markers |
| `state/contract.py` | three `StateSurface` entries (WP06) | none | declaration |
| `scripts/lint_canonical_producers.py`, `tests/status/conftest.py`, `_completion_manifest.json`, `docs/api/cli-commands.md` | persisted-line mode (baseline not widened); fixture also restores the client; regenerated | outside gate (e) | tooling, test, generated |

**Journal route (one per kind, FR-035/FR-003).** The runtime layer cannot import `specify_cli` without growing the ledgered edge cap of `tests/architectural/test_layer_rules.py` (`_RUNTIME_ALLOWED_SPECIFY_CLI`, `_baselines.yaml`), and `engine.py` imports none today, so every run-journal kind reaches the seam through the existing `RuntimeEventEmitter` chain (`engine` -> `DecisionGitLog` -> `_BufferingRuntimeEmitter` -> `RuntimeMomentProducer._publish_journalled`, which already holds the `specify_cli.status` import). The six moment kinds use their existing methods; `SignificanceEvaluated` uses `emit_significance_evaluated`, which `_record_significance` already calls (so `engine.py` gains no seam call there); `DecisionAuthorityDenied` gets the one new Protocol method, called from `_authorize_audit_answer`. `engine.py` never calls the seam, so nothing double-fires. `DecisionTimeoutExpired` stays out of scope. Blast radius: R-16. **Mission identity (one rule).** The persisted journal payload leaves `mission_id` empty (the engine takes it from nowhere; `_with_mission_identity` stamps only the Zeitgeist envelope). For a journal row the client passes `start_path` = the `repo_root` argument `_publish` already hands `_publish_journalled`, which is `RuntimeMomentProducer._feature_dir`, the mission directory holding `meta.json`; no new symbol. Java reads `missionid` from `row.payload.mission_id` when non-empty, else from `meta.json` in `start_path` when that directory holds one, else publishes under the project-scoped `source` with `subject` = the aggregate, no `missionid` and no fault (FR-009, OD-MISSIONID (b); R-24). Java never reads `seam_args`. **The client gets the raw persisted journal line as `row`, never an envelope.** `_publish_journalled` still builds `envelope` (UUID5 `moment_event_id`) for the Zeitgeist handler until #6164; those names are residuals deleted with it. The producer's `id` is `row["event_id"]` alone; rows lack it until WP07 and are then refused, never given a Python id. WP06's census uses a stubbed client and asserts calls, `row` keys within the five persisted keys and `start_path` equal to the feature directory; its journal-identity test drives the real `RuntimeMomentProducer` route with a row whose payload `mission_id` is empty into a recording stub binary and asserts that request; WP04 has the Java test (empty payload `mission_id` plus `meta.json` in `start_path` gives that `missionid`; no `meta.json` gives the project-scoped `source`, no `missionid`, no fault); WP10 runs both ends together against the real binary. Tests, residuals, sweep: R-24.

**Gate** `tests/architectural/test_producer_client_thinness.py` (new; full rules R-16): (a) the three new modules: closed import allowlist, no write-mode `open()` on the publish-log constant (also AC-030), `json.loads` only in `_read_reply` and `_read_fault_rows`, a wall-clock read only in `_now_utc`, the exact top-level symbol set equals the table; (b) (WP07, with the stamping) `_append_event`: key set equals the five keys; an AST walk of its body finds no `uuid4`, `hashlib`, `secrets` or `random` reference (`uuid4` and `hashlib` are already imported by `engine.py`, so the import rule alone cannot see them); and a test on the real `_append_event` output asserts `event_id` is a 26-character Crockford ULID that `ulid` parses (AC-022); (c) the emission-area module census equals a pinned list; (d) the client imports none of `_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`; (e) **every existing Python file of the table (except the last row)**: its top-level function, class and method set and its import set lie within the base commit's plus that file's row, so a helper such as `_mint_id`, or an import of `uuid`, `hashlib`, `datetime`, `secrets` or `random` not in the row, added to `emit.py`, `lifecycle_events.py` or `engine.py` fails (`engine.py` may add `ulid` and nothing else; its pre-existing `uuid4` and `hashlib` imports are covered by (b)). "Within" lets a row's edit land in a later WP than the gate. Floor: every module and table file found. Self-mutation (AC-012, R-16): client, `producer_faults.py`, `emit.py`, an absent import (`secrets`) in `engine.py` and `rebuild_state.py` copies, and an `engine.py` copy whose `_append_event` stamps `str(uuid4())` (fails (b)). The pinned lists in (c) and (e) are priced debt, deleted when #6193 retires the Python writers. **Build-glue pin (F1).** The gate also pins the packaging files: `scripts/release/hatch_build_producer.py`, `check_dist_set.py`, the `check_exact_install.py --wheel-tag` edit and the `gradle_pin_check.py` third-`Subproject` entry are the only new or edited Python for packaging and release, none holds runtime logic (no producer, envelope, id, clock or schema code).

### 4.3 Fault, retention, and ledger rules (F-2, F-6)

Python forwards `ledger.retention.keep_runs` and `max_age_days` exactly as read from `.kittify/config.yaml` (or omits them); Java applies the defaults 20 and 30 and validates (AC-031 fixtures live in Java tests, plus one Python test that the raw string `"abc"` reaches Java unchanged). Retain fails closed on an unreadable ledger (section 8).

### 4.4 Lane-list consolidation and marker clearing

First task of the Python WP is the census (FR-034): the sets of data-model section 9 plus any further hit; read `tests/specify_cli/migration/test_rebuild_state.py` and the acceptance tests first. None of the four lists is derived wholesale; dispositions, reasons and tests per list: R-14. `Lane` stays the authority; each change follows a red-first characterisation test (value and order equal to the pre-change literal); `tests/architectural/test_single_lane_list.py` finds the census lists (floor six, exceptions listed) and fails on a new full literal list (mutation case). Imports each file may add: the 4.2 rows. Marker disposition per data-model section 8: clear 1, 2, 5, 6, 8; markers 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`), 7 (`status/lifecycle_events.py`) by the rule "clear only if the persisted line validates in legacy-row form, else residual with an issue reference"; 9 to 16 untouched.

## 5 Request/response protocol

Transport: the client runs `[<binary>]` with no arguments, writes one UTF-8 JSON object to stdin, closes it, and reads one JSON object from stdout. One spawn per row (OD-SPAWN); batching is only the NFR-002 contingency. Shapes: R-4.

- **Versioning.** `"v": 1` in both directions; an unknown `v` is exit 64 (client `crash`). New optional members may be added within `v` 1; removing or retyping one needs `v` 2.
- **Operations.** `produce` (`row`, `slot`, `seam_args`, `start_path`, `deadline_ms`) and `retain` (`start_path`, `dry_run` default true, raw `policy`, `deadline_ms`). No `nodeid`, facts or schema in a request.
- **Reply.** `produce`: `outcome` (`produced|skipped_duplicate|refused`), `event_id`, `type`, `reason_class` and a closed `reason` when refused, optional `notice` (`torn-line`), `detail`. `retain`: `eligible`, `kept` (each with reason), `deleted`, `policy_error`. Never the row, an absolute path or a stack trace (NFR-006).
- **Exit codes.** 0 a reply was written (refusals are replies); 64 unreadable request or unsupported `v`; 75 deadline reached (watchdog); anything else, a signal, or empty stdout is `crash`.
- **Timeouts.** `effective_client_timeout(bound)` = 3.0 s when `bound <= 0` (seam runs handlers inline), else `min(3.0, max(bound / 2, bound - 1.0))`, so it is positive and strictly below any positive bound; `deadline_ms` is that value in whole milliseconds, at least 1. AC-006 adds the boundary cases 0.5, 1.0, 1.5, 0 and negative, and a slow Zeitgeist handler case (no producer fault row). Provisional until AC-036; the breaker bounds a batch to one timeout.
- **Fault classes** `binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`; an enum-equality test asserts `disabled` is absent. The mapping table is R-4. stderr of the child is discarded.

## 6 The spec-kitty-events PR

**Separate PR, in the events repo, sequenced first and released** (FR-040); the orchestrator opens it. Content (FR-014, exactly): per-kind `data` JSON Schemas for the types of data-model section 3 (v1) with lifted-member lists; registry entries (type, scope, `subject` rule, lifted members, schema file) in one machine-readable file the Java build reads, so Java has no per-type code; `dataschema` URIs; producer conformance fixtures (valid, invalid, legacy-row-form variants generated from the single schema plus the lifted list, FR-032); the comparator specification and the nine fixtures of data-model section 4; `spec_kitty_events.Lane` alignment (data-model section 9); conditionally the minimal envelope subset (FR-035). Confirmations asked: the INTERIM choices, the `source` tie-break, `schemaversion`, the `dataschema` base URI, and that #92 adopts the ruled prefix and interim type names (F-3).

**Dependency, gating, pin.**
- The Java build fetches the released wheel `spec_kitty_events-<V_E>-py3-none-any.whl` (version assigned by the events repo; none here, C-006) by a plain Gradle task: URL and sha256 in `services/event-producer/gradle/events-pin.properties`, sha256 checked before unzip, schemas and registry extracted into `build/` and embedded as native-image resources. Nothing from the events repo is committed (`tests/architectural/test_no_committed_events_schema.py`, with floor and planted-schema mutation; AC-027). The wheel because PyPI is the only release channel (P15).
- WP11 raises the `spec-kitty-events` floor to `V_E` in `pyproject.toml` (`uv.lock` follows); `tests/contract/spec_kitty_events_consumer/` pins the surface. WP11 precedes WP07, whose Python AC-019 gate reads the schemas from that installed package (R-24).
- **While unreleased.** Java contract tests run against a pinned released version only; `-PeventsWheel=<path>` exists for development and is refused when `CI` is set. Only WP11 reads released artifacts; WP07, WP09 and WP10 follow it, so WP07, WP09, WP10, WP11 are not approved, and the mission PR not marked ready, before the pin names the released wheel. WP01 to WP06 and WP08 read no schema and proceed now (R-15).
- **Reconciliation.** One schema set, one writer per type: events#92 keeps envelope schema, validator, upcaster, STRICT registry and the sort-key switch; this PR carries only the FR-014 list. #6193 reuses this registry and domain. Rebase on #92 if it lands first and drop the minimal envelope subset.
- **Events-repo charter** (Python library, Pydantic; #6117 decision 11) needs an amendment allowing JSON Schemas and fixtures as the contract for non-Python producers; owned by the events PR or #92 (section 13).

## 7 Clock state file

Detail: R-5.

- **Path and format.** `<git-common-dir>/spec-kitty/event-producer/clock.json` (`{"v":1,"nodeid":"<12 hex>","clock":<int>}`, integer 0..2^31-1) and sibling `clock.lock`; the common dir is resolved by reading `.git` and `commondir`, never by spawning git. Worktrees share it; separate clones share only the `nodeid` (accepted residual).
- **Locking.** One exclusive `FileChannel.lock()` on `clock.lock`, held across read, stamp, dedupe, state write and append (POSIX `fcntl` advisory byte-range lock, one descriptor per process; Windows `LockFileEx`). Wait bounded by `deadline_ms`.
- **State first.** Temp-file, `fsync`, atomic move, then append and `fsync`: a kill leaves no advance, or an advance and no line (a gap); never a reused value (AC-010 injects the three kill points through a test-only `FaultInjector` port).
- **State loss.** Next stamp = max(state, highest `lamportclock` readable across publish-log files) + 1. Overflow above 2^31-1 is refused as `clock-overflow`; no wrap.
- **nodeid.** SHA-256 of `hostname:username`, first 12 hex; hostname by an FFM `gethostname` downcall (`ws2_32` on Windows); username from `LOGNAME`, `USER`, `LNAME`, `USERNAME`, then `user.name`. Golden test per OS (AC-039; detail R-5).

## 8 Retention in Java

Detail and file table: R-6.

- **Operation shape.** `Retain` lists candidate local-tier files, derives facts, applies `RetentionPolicy` (defaults 20 and 30; a named `policy_error`, also on a dry run, AC-031) and returns eligible/kept/deleted. `dry_run` is true unless the client sends false, which `doctor` does only with `--apply`.
- **Whole-file deletion only.** The producer opens no candidate file for write in `retain`; it calls delete on whole files, each under `clock.lock` (below). Candidates come only from the local-tier directories, never from `kitty-specs/`; AC-014 offers every tracked ledger as a candidate and asserts zero writes and zero deletes on tracked paths.
- **Placement and "run"** (R-6). Publish log: `.kittify/events/publish/missions/<missionid>.jsonl` and `project-<project_uuid>.jsonl` (run = one Mission file); fault log (client-written): `.kittify/events/faults/<UTC date>.jsonl` (run = one UTC day); run journals: the existing per-run file.
- **Mission open.** Derived in Java from the committed ledger(s): open iff any work package's latest row (greatest `at`) has `to_lane` other than `done`/`canceled`; the canonical copy and a coordination-worktree copy are both read, open if either says so. **An unreadable, unparseable or absent ledger keeps the Mission's files ("facts unavailable"); AC-013 (F-6).**
- **Lock fact = the content of a Python-owned lock sidecar, never its presence** (non-empty parsable `LockRecord` younger than 60 s, `kernel.locks.STALE_AFTER_S_DEFAULT`; empty sidecar no lock; unreadable keeps the run; Java never takes an OS lock on a Python-owned file; `clock.lock` is never a lock fact). Fixtures and reasons: R-21.
- **Retain versus produce.** `Retain` takes `clock.lock` (bounded by `deadline_ms`) per candidate file, re-checks eligibility, length and newest row under it, then deletes; lock timeout or delete failure is kept-with-reason (`busy`, `delete-failed`). Two-process test: `produce` racing `retain --apply` loses no reported row (detail: R-23).
- **Dry run first.** `spec-kitty doctor events-retention` (dry run), `--apply` to delete; never implicit.
- **Doctor pass-through rendering.** `doctor events` and `doctor events-retention` live in `_event_producer_doctor.py`; Python renders and counts only (R-22).

## 9 Test strategy and gates

**Method (Standing Order 4).** Every behaviour has a test committed red first, as its own commit before the implementation, verified red on the WP's base and green on its tip, and revert-checked (revert the implementation hunk, the test must fail). Floor and self-mutation cases on every census gate (spec floor rule). Golden files come from the real serializer (#4182). Each WP declares its targeted test surface; no whole-directory run.

**Python gates (shim).** New architectural gates: `test_producer_client_thinness.py` (section 4), `test_single_lane_list.py`, `test_no_committed_events_schema.py`, `test_publish_log_single_writer.py` (AC-030), `test_seam_census.py` (AC-004: one forced row per writer yields one seam call), `test_producer_fault_enum.py`, `test_exemption_ledger.py` (AC-019). Behavioural tests live in `tests/status/` and `tests/specify_cli/status/` (R-17). Existing gates re-run: R-7. Coverage: diff-cover >= 90% (CI), new Python modules 95%.

**Gradle gates (Java).** `gradle -p services/event-producer test`: unit tests per subproject; ArchUnit (section 1); golden `nodeid` plus a per-OS CI step against `identity/project.py::generate_node_id` (AC-039); order fixtures (AC-008/009); clock concurrency and kill tests (AC-010); torn line (AC-026); determinism and byte rules (AC-034); contract tests against the pinned events wheel (AC-002/008/027, WP11); **the FR-005 guard, AC-029 (WP11): a `:domain` test over every type of data-model section 3, driven by the registry, asserting the `data` key set is a subset of the persisted row's keys (for lifecycle rows, its `payload`) plus the lifted-member rules; floor: every section 3 type resolved; mutation: an injected member (a `meta.json` or environment value) fails**; retention (AC-013/014/031, the two-process race of section 8); dependency verification (G-13, with a tamper test); JaCoCo floors: `:domain` 95% line and 90% branch, `:application` 90%, `:adapter` 80%, `:launcher` excluded (a plan choice, section 13).

**Packaging and release gates.** Section 3.4, plus dist-set equality with mutation, `smoke-wheels`, SBOM (AC-040), checksum continuity (AC-032), `twine check`, `uv lock --check`.

**Pin tooling (one authority, WP01).** `contracts/tools/gradle_pin_check.py` is the single pin-versus-build authority (P21); `services/event-producer` becomes its third `Subproject` (planted-mismatch cases and a compared-values floor in `tests/contract/test_gradle_pin_check.py`). `verify_pins.py` runs with `--pins services/event-producer/tools/pins.json --workflow` for both new workflows, enforcing action SHA pins; `tests/contract/test_verify_pins.py` is re-run. Detail: R-7.

**CI home.** New `.github/workflows/event-producer.yml` on `pull_request` and `push` to `main` (path filters: `services/event-producer/**`, `scripts/release/**`, `pyproject.toml`, `src/specify_cli/status/producer_*.py`, `src/specify_cli/_native/**`, itself, `producer-native.yml`); jobs `verify-pins`, `gradle-test` (ubuntu, JDK 25, JaCoCo, ArchUnit, dependency verification, tamper check), `native-smoke` (three legs through `producer-native.yml`, then wheel by path and smoke script per OS), terminal `event-producer-gate` (the `contracts-gate` pattern); every root job carries the fork guard first. WP01 registers both new workflows in `scripts/ci/fleet_verdict.py::PR_WORKFLOWS`, `ci-fleet-verdict.yml`, `tests/ci/test_fleet_verdict.py` and the `NON_CHANGE_TRIGGERED_WORKFLOWS` ledger (P6; the closed inventory test is red on any unregistered PR workflow). Nightly: `ci-nightly.yml` calls the same smoke path (WP09; AC-036 numbers recorded there).

**Performance (AC-036/NFR-002).** `tests/_perf_helpers.py` gains relative producer limits (`test_perf_limit_authority.py` rejects absolute ones): the largest multi-row command (`emit_status_transition_batch`) with a real producer over the same with a no-op handler, cold and warm, per OS; numbers from WP01 and WP10 measurements; on failure the NFR-002 contingency applies after amending FR-003, FR-026 and AC-004.

**CI gates not included, with reasons:** R-8. **Gate-implication audit:** R-7 (hardest-biting: `test_status_module_boundary.py`, `test_runtime_emitter_seam.py`, `test_hosted_drain_gate.py`, `test_completion_manifest_freshness.py`, `test_release_ci_ownership.py`, `test_fleet_verdict.py`). Also ruff check and format, `TID251`, `uv lock --check`, `spec-kitty regen --check`, docs lint, the terminology guard. **Contextive** (FR-046): no CI job runs `check` (P17), so a targeted test does (15.5).

## 10 Baseline method

Before the first change the orchestrator captures pre-existing red from a detached scratch worktree of the base commit: each gate file of R-7 and the owning fast tiers, one invocation per file, into `research/baseline-red.txt` (base sha, command, counts, failing node ids). A red node id with the same failure text is pre-existing; any other is introduced and is the WP's to fix. **#3283** (venv-lock bootstrap cascade) defines the environment rule: a failure of that shape is re-synced (`uv sync --frozen --all-extras`) and rerun once alone before it is recorded. **#3284** (sanitation mission base failures) is tracked: a red node attributable to it is recorded "known red, tracked: #3284". Any other distinct pre-existing red needs an issue before work continues past it (charter rule); the operator opens it. Known-P0 reproductions are not run. Detail: R-9.

## 11 Work packages and PR shape

**One PR** (draft, `main`), compact history, `spk-land-pr` pass before it sits ready. **No slices**: the Java tree, wheel machinery, client and release workflow are only useful together, and every WP leaves its lane green on its own gates. The events-repo PR goes first; no split is planned.

**Ownership rule.** A WP that adds a file or workflow owns every gate edit it forces, in the same WP, and no gate lands before the edit it checks; a chokepoint owner is only for files several WPs edit, which then take their edits one WP at a time in dependency order (audit: R-15). Seam call sites, the emitter route and the census are WP06's; stamping, gate (b), lanes and markers are WP07's.

**Campsite-clean (Standing Order 2).** (1) WP01's first commit, docs-only: the stale "PyPI Release" paragraph of `AGENTS.md` and the matching line of `scripts/release/README.md`. (2) WP06's first commit folds the three `try/except` ladders of the `fire_*` functions behind `_dispatch_handlers`, characterised by `tests/status/test_saas_fanout_timeout.py` and `test_drain_fanout.py`. (3) WP06's edits to `status/emit.py`, `lifecycle_events.py` and `engine.py` are call sites and keyword additions: no extraction. A campsite scout runs on `status/emit.py` and `events/runtime_moments.py` before WP06. Full text: R-20.

| WP | Scope | Write-scope notes | Depends on |
|---|---|---|---|
| WP01 | Spike: native image on three OS; `producer-native.yml` and `event-producer.yml`; **fleet-verdict registration and duplicate-suite ledger rows for both**; pins, third `gradle_pin_check` Subproject, verification metadata; **hatch hook, `pyproject.toml` hook table, `.gitignore`, `dist-set.json` (three platform tags), minimal stub-wheel packaging test**; campsite (1) | **risky first**; owns the Java skeleton, `fleet_verdict.py`, `ci-fleet-verdict.yml`, `test_fleet_verdict.py`, the hook; first editor of `pyproject.toml` | none |
| WP02 | `:domain`: ids, clock value, order (tests use data-model section 4 examples as test-local inputs), envelope model, retention eligibility, ArchUnit. Reads no schema | `:domain` | WP01 |
| WP03 | `:adapter` files: lock, clock store, publish log (torn line, dedupe, determinism), repo locator, nodeid FFM and golden | `:adapter` | WP02 |
| WP04 | `:application` `Produce` (takes the `RowSchemaValidator` port; tests use a fake), launcher protocol, faults vocabulary, journal-row mission identity (section 4.2) | `:application`, `:launcher` | WP03 |
| WP05 | `Retain` (clock.lock, lock-record reader), committed-ledger reader, policy | `:adapter` ledger package | WP03 |
| WP06 | Campsite (2), Python client, all seam call sites of 4.2 (status, decisions, retrospective, `runtime_moments`, the emitter route in `events.py`, `decision_log.py`, `runtime_bridge_retrospective.py`, `engine.py` denial call), census, thinness gate except (b) (its floor counts all three new modules), fault log, doctor sibling with completion manifest and CLI reference, `state/contract.py` entries | **sole owner of `status/adapters.py`**, `state/contract.py`, `tests/status/conftest.py`; first editor of `engine.py` | WP04, WP05 (protocol, `retain`); none blocking on #6164 (hunk handling: section 12) |
| WP07 | `_append_event` stamping with gate (b) and the ULID-form test, lane consolidation, marker clearing (AC-019 validates persisted lines against the released schemas), glossary and Contextive, ADRs | second editor of `engine.py` (after WP06); owns lint script, `_baselines.yaml`, `dead_symbol_allowlist.yaml`; reads schemas from the installed `spec_kitty_events` (R-24) | WP06, WP11 |
| WP08 | Packaging remainder: `check_dist_set.py`, `dist-set.json` extension, `check_exact_install.py`, fixtures, packaging tests. No `pyproject.toml` edit | ungated by the events release | WP01 |
| WP09 | `release.yml`, `ci-nightly.yml` call, SBOM, attestation, release docs | **sole owner of `release.yml`** | WP08, WP11 |
| WP10 | Integration: default-install end to end, AC-036 measurement, AC-025, mid-flight Mission (AC-017), reflexivity check, final gate pass | read-mostly | all |
| WP11 | Events-release consumer (gated): pin fetch task and `events-pin.properties`, schema-backed adapter for the validator port, AC-029 test (a `:domain` test file), the released comparator and nine fixtures run (AC-008), contract tests against the pinned wheel (AC-002/008/027), the `spec-kitty-events` floor bump with `uv.lock` | `:adapter` schema package, one `:domain` test file, `pyproject.toml` (last editor, after WP01) | WP04, events release |

Risky and unknown work first: WP01 proves the native image and the hook on all three OS. Graph (acyclic): WP01; WP02<-WP01; WP03<-WP02; WP04, WP05<-WP03; WP06<-WP04, WP05; WP08<-WP01; WP11<-WP04 (and the release); WP07<-WP06, WP11; WP09<-WP08, WP11; WP10<-all. Each WP is green alone on its own tests and gates.

**Tracer files** are seeded beside this plan.

## 12 Interaction with #6164

Not landed; the seam fires the client irrespective of drain, so either order works. `status/adapters.py` hunks in WP06 commit order (R-11): (0) campsite fold, (A) drain gates and `hosted_posture` import deleted, (B) `row` and fault-recorder calls in the busy and timeout branches, (C) client registration outside the `moment_handlers_disabled_reason` branch, (D) docstring and fixture. If #6164 lands first, A and the `zeitgeist_bridge` import block conflict as "deleted by them": take theirs and re-add C; if this mission lands first, #6164 removes what A left. The drain gate moves into the Zeitgeist handlers while they exist, so `hosted.drain` keeps its meaning (C-003); the pin test is re-pointed at every Zeitgeist handler (AC-003 f); AC-003 skips (a) when `zeitgeist_bridge.py` is gone and runs (b) always.

## 13 Governance drift to flag

1. **Charter has no Java gate row** (it says Python 3.11+ for all CLI and library code); the Java gates are the plan's choice.
2. **Python outside the thin client (F1, operator-ruled bounded allowance).** The only new or edited Python allowed for packaging and release is `scripts/release/hatch_build_producer.py` (platform tag, exec bit), `check_dist_set.py`, the named release-script edits (`check_exact_install.py --wheel-tag`) and the third `Subproject` entry of `contracts/tools/gradle_pin_check.py`. None holds runtime logic. The thinness gate pins the runtime shim (`producer_client.py`, `producer_faults.py`, the doctor sibling) as well as these files; the Java-only rule otherwise stands.
3. **ADR 2026-10-13-2 D-1, D-2, D-3 need amending** in place (as ADR 2026-10-06-1 did), plus one new ADR `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md` and an `index.md` update (detail: R-19; AC-033 checks the amendment names D-1, D-2, D-3).
4. **Events-repo charter** needs an amendment (section 6).
5. **No-vendoring rule** is met (pinned released wheel, nothing committed, gated).
6. **`AGENTS.md`:** "PyPI Release" says `release.yml` is absent (campsite 1); the charter has no row for platform wheels or native binaries.
7. **Contextive regeneration** is not CI-enforced (15.5).
8. **Reflexivity and mid-flight.** This mission's own events come from the Python writers; no gate depends on the producer. A Mission open at upgrade keeps its ledger byte-unchanged and gets a publish log from its first later event (AC-017); one without `mission_id` publishes under the project-scoped `source` with no `missionid` and no fault (OD-MISSIONID (b); `doctor` may add an optional `migrate backfill-identity` note). No migration step.

**Glossary additions** (spec section 13, carryover L) go to `docs/context/system-events.md`: Producer, Publish seam, Publish log, Ledger, Fan-out (not "sync"), Clockless row, Clocked row, Fault log; `generate` and `check` run after.

## 14 Traceability

Every FR, NFR, C and G group maps to a section and its ACs in R-17.

## 15 Spec touch-ups proposed to the orchestrator

Thirteen, not edited here; exact wording in R-18.

## 16 Operator forks

**F1. Python build glue under a Java-only rule.** Platform wheels need a hatchling build hook (Python by the backend's design) and a release set-check script comparing `dist/` with the pinned list (AC-037); pin checking extends the existing `contracts/tools/gradle_pin_check.py`. Options: (a) allow these bounded files plus edits to existing `scripts/release/*.py`, `gradle_pin_check.py` and `tests/`, no product logic; (b) implement the set-check as a Gradle/Kotlin task (the hook stays Python, unavoidable); (c) change the backend away from hatch (rejected: far larger). Recommendation: (a), recorded in the new ADR and the FR-027 surface, because (b) adds a JVM dependency to the Python release path for no safety gain. **Ruled (operator, 2026-10-14): (a), bounded** to the files of section 13 item 2; see the thinness gate.

No other fork: the Linux wheel floor, JaCoCo thresholds, log placement, CI shape, fault-count window and timeout rule are plan decisions with a stated reason.

## 17 Claims not verified

Full list: R-12. Material: the GraalVM build, flags and Linux container route (WP01 proves them); Windows/Java hostname agreement (AC-039); events-repo file names; SHA-pinned action versions (WP09); the `env` context in a job-level `with:` of a reusable-workflow call (WP09 confirms); hatchling is absent from this venv, so hook semantics were read from cached source, not executed.
