# Implementation Plan: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 | **Branch**: `feat/event-producer-reroute-6185` (target `main`) | **Date**: 2026-10-14
**Inputs**: [`spec.md`](spec.md), [`data-model.md`](data-model.md), [`plan-carryover.md`](plan-carryover.md), `reviews/spec-amend-verify.yaml` (F-1 to F-6 in sections 4, 8, 15), `reviews/plan.confirmed.yaml` (round 1, absorbed throughout), the operator rulings of 2026-10-13. Evidence, gate-audit tables, alternatives and the unverified list: [`research.md`](research.md) (R-n is research section n; P-n is row n of its section 1).
**Charter**: read; it wins over `AGENTS.md`/`CLAUDE.md` (`CLAUDE.md` is a symlink to `AGENTS.md` here). Drift is flagged in section 13, not resolved.

The spec has no open decision. The one fork found is in section 16 and blocks nothing.

## 1 Module layout

**Location and build.** `services/event-producer/` at the repository root (a sibling of `contracts/` and `acceptance-jvm/`; no dependency on either or on unlanded #5529 code). Kotlin DSL (the brief's choice; the two existing builds are Groovy and #6192 can absorb either, P8), Gradle 8.14.5 installed by `contracts/tools/install_tools.py --pins services/event-producer/tools/pins.json --only gradle` (reused unchanged). Java 25 by toolchain (`languageVersion = 25`, as `acceptance-jvm`, ADR 2026-10-01-2); CI uses the SHA-pinned `actions/setup-java` form of `contracts.yml`; native builds use the GraalVM JDK 25 archive pinned in `pins.json`. A committed, Gradle-generated `gradle/verification-metadata.xml` (`sha256`) covers every dependency and plugin, with the tamper check of `contracts/gradle/verification-metadata.xml` (a planted checksum change fails the build).

**Subprojects and packages** (root package `ai.speckitty.producer`):

| Subproject | Contents | May depend on |
|---|---|---|
| `:domain` | `ids` (ULID parse and normalise, UUID refusal), `clock` (value type, bound 2^31-1, overflow), `order` (total-order comparator, dedupe, error rows), `envelope` (attribute names, type registry model, lifted-member mapping driven by data), `retention` (eligibility over supplied facts, policy defaults and validation), `fault` (reason vocabulary), `protocol` (request and reply value types) | `java.base` only |
| `:application` | use cases `Produce`, `Retain`; ports `RowSchemaValidator`, `PublishLog`, `ClockStore`, `RepoLocator`, `NodeIdentity`, `LedgerFacts`, `Clock` | `:domain` |
| `:adapter` | JSON (Jackson, only here), schema validation, file system, `FileChannel` lock, git common-dir resolution by reading `.git`/`commondir`, hostname through the Foreign Function and Memory API, committed-ledger reader and lock-record reader for retention (reads sidecar bytes, takes no OS lock on a Python-owned file) | `:application` |
| `:launcher` | `main`, stdin/stdout protocol, exit codes, deadline watchdog, native-image configuration | all |

**Rules enforced by ArchUnit (a test in `:domain`/`:application`):** `:domain` imports only `java.base`; `:application` imports no `:adapter` or Jackson; no class in `:domain` reads a clock, environment variable or file. Every rule carries a floor (the scanned class count must be above zero) and a self-mutation case (the spec's floor rule).

**Absorption.** #6192 lifts `:domain` into `domain-model` and the `ClockStore`, `PublishLog`, `Produce` parts into `event-log` by moving packages. #6193 replaces `LedgerFacts` (a read-only reader of `wp_id`, `to_lane`, `at`, `event_id`; the only code that opens a committed ledger) with its read model and swaps the publish-log writer behind the same port.

## 2 Native image build per platform

**Toolchain pin.** GraalVM Community JDK 25, one release chosen in WP01 from the stable `jdk-25.0.x` tags; archive URL and sha256 in `tools/pins.json`; the build fails when `native-image --version` differs. Three legs only (FR-041, OD-MATRIX), no musl wheel unless WP01's fallback applies (R-2).

| Leg | Runner label | Binary | Wheel tag |
|---|---|---|---|
| Linux x86_64 | `ubuntu-24.04`, build in a pinned `manylinux_2_28` container | `spec-kitty-event-producer` | `py3-none-manylinux_2_28_x86_64` (pinned in `dist-set.json`) |
| macOS arm64 | `macos-14` | same | `py3-none-macosx_11_0_arm64` (pinned) |
| Windows x86_64 | `windows-2022` | `spec-kitty-event-producer.exe` | `py3-none-win_amd64` |

**Tag authority.** `scripts/release/dist-set.json` is the single authority for the wheel tags (the floors). The hook is given the tag from it, never from the binary. The measured maximum `GLIBC_` symbol and macOS `minos` are assertions: the leg fails when the measurement exceeds the pinned floor (a lower measurement is fine). If WP01 falls back to musl, the pinned tag changes with it.

**CI shape.** Reusable workflow `.github/workflows/producer-native.yml` (`workflow_call`, required input `ref`, matrix over the three legs, `fail-fast: false`) checks out `ref`, builds and uploads `producer-<leg>` (binary plus `producer-manifest.json`). `release.yml` calls it in a new job `build-producer` passing the release ref; `event-producer.yml` passes `github.sha`. The workflow-level `env` context is not available in a job-level `with:`, so `release.yml` repeats the `RELEASE_REF` expression from the `github` and `inputs` contexts (a test pins that the two texts are equal; R-12). The assembling job is the existing `build-release` (name pinned, P4).

**Provenance (FR-042, AC-032).** Per-leg manifest (binary sha256, toolchain sha256, source commit, measured floor); `build-release` fails unless every `source commit` equals `git rev-parse HEAD` of its checkout and recomputes each sha256 before building a wheel; the hook writes it as `specify_cli/_native/PRODUCER.sha256`; `check_dist_set.py --verify-binaries` re-hashes each published member; `publish-pypi` attaches an attestation. No reproducibility claim.

**Unsigned (OD-SIGN).** pip sets neither quarantine nor mark-of-the-web; macOS arm64 needs the linker's ad-hoc signature, so the binary is never stripped and the smoke job runs `codesign --verify`. Release notes say "unsigned in this mission" (R-2).

**WP01 exit criteria (feasibility gate, first work package).** On all three runners a trivial producer (stdin JSON in, JSON out, Jackson, one schema validation, `FileChannel` lock, `gethostname` downcall) builds, starts under 100 ms warm, and a stub wheel built with WP01's own hook installs and runs by path on its OS. If a leg fails, the fallbacks (musl static on Linux; hand-written reachability metadata) are tried in WP01; if a leg still fails the mission stops and the operator is told (FR-041 floor).

## 3 Wheel build machinery

Current state (P2, P3, P10, P11): `hatchling==1.31.0` backend; one pure wheel plus sdist from `python -m build` in `release.yml` job `build-release`; every wheel-handling step assumes one wheel.

### 3.1 Build backend change (stays hatch)

`pyproject.toml` gains only `[tool.hatch.build.targets.wheel.hooks.custom]` with `path = "scripts/release/hatch_build_producer.py"` and `enable-by-default = false`. The hook runs only when `HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`; it then requires `SPEC_KITTY_PRODUCER_BINARY` and `SPEC_KITTY_PRODUCER_PLATFORM_TAG` (the latter read from `dist-set.json` by the caller) and sets `pure_python = False`, an explicit `tag = py3-none-<platform>`, and a `force_include` of the binary to `specify_cli/_native/<name>` (plus `PRODUCER.sha256`). On non-Windows tags it force-includes a copy chmod-ed `0755` (artifact upload and download drop mode bits and hatch takes the member mode from the file stat, R-3). Unset, nothing changes: `uv sync`, editable installs, `uv build --wheel`, the shared test build, the sdist and the sdist-to-wheel step produce today's output (C-007, AC-038); the sdist `include` list is not widened. `.gitignore` gains `src/specify_cli/_native/` (no entry exists; hatch honours `.gitignore`, so otherwise a developer's copied binary would ship in the pure wheel and the sdist).

### 3.2 Files that change, and owners

WP01 (needs a working hook to prove the stub wheel): `scripts/release/hatch_build_producer.py`, the `pyproject.toml` hook table, `.gitignore`, and the minimal stub-wheel packaging test. WP08 (the rest): `check_dist_set.py` and `dist-set.json` (one sdist, `py3-none-any`, the three platform tags; AC-037, G-12), `check_exact_install.py` (`--wheel-tag`; untagged multi-wheel still refuses), `tests/_support/shared_build_artifacts.py` (added platform-wheel fixture), the other packaging tests, the `spec-kitty-events` floor bump with `uv.lock`, `scripts/release/README.md`. WP09: `release.yml`, `RELEASE_CHECKLIST.md`, `CONTRIBUTING.md`. WP01 owns `producer-native.yml`. Detail: R-3.

### 3.3 `release.yml` change (WP09; step list: R-13)

New job `build-producer` (reusable workflow, release `ref`); `build-release` keeps its name, gains `needs: build-producer` and, after "Build distributions", the download, sha256 and source-commit checks, the platform-wheel builds, `check_dist_set.py` (set equality, `--verify-binaries` incl. exec mode bits), `check_exact_install.py --wheel-tag`, a looped "Verify wheel contents", the producer SBOM; new job `smoke-wheels` (three OS legs: install the matching wheel by path, run the smoke script; the pure wheel on one leg); `publish-pypi` needs both, re-runs `check_dist_set.py` first and attests. Standing Order 9: every new stage is a `needs` edge, no `continue-on-error`.

### 3.4 Gates touched

R-3, R-7, R-13. New assertions: `test_release_ci_ownership.py` (`build-producer` with its `ref` input, `smoke-wheels`, the `needs` edges); packaging tests (binary only in platform wheels, with exec bit; hook-off wheel and sdist binary-free **with a stub planted untracked in `_native/`**, present only with the hook; `git check-ignore` pins the entry).

## 4 Thin Python client contract

### 4.1 What Python may do, each with its file

| Capability (FR-026) | File | Symbol(s) |
|---|---|---|
| locate the platform binary (package-relative `_native/`; no env override) | `src/specify_cli/status/producer_client.py` | `locate_binary` |
| spawn (argv list, no shell), pass request, read one reply (cap 1 MiB; the one reply decode site) | same | `ProducerClient.produce`, `ProducerClient.retain`, `_read_reply` |
| keep the client timeout strictly below the effective seam bound (also when disabled); the bound resolver is injected by `adapters.py` as `ensure_producer_client(bound_resolver=_saas_fanout_timeout_s)`, so the client imports nothing from `adapters` | same | `effective_client_timeout(bound)` (rule: section 5) |
| terminate its own child by handle | same | inside `ProducerClient._run` |
| per-process breaker | same | `ProducerClient` state |
| map any failure to a fault class | same | `FaultClass` mapping in `_classify` |
| register on the seam | same + `status/adapters.py` | `ensure_producer_client`, handlers `produce_status_handler`, `produce_lifecycle_handler` |
| fault recording: stderr line in every mode, fault-log append (single writer), notice rows, count and last class for `doctor`; root via `resolve_canonical_root` for the location only. Holds the only clock read (`_now_utc`: the row's `at` and the UTC day file name) and the second decode site (`_read_fault_rows`) | `src/specify_cli/status/producer_faults.py` | `FaultClass`, `record_fault`, `record_notice`, `write_stderr_warning`, `fault_summary`, `_now_utc`, `_read_fault_rows` |
| doctor pass-through: raw `ledger.retention.*` values forwarded, reply rendered | `src/specify_cli/cli/commands/_event_producer_doctor.py` (self-registering sibling, no edit to `doctor.py`) | `register`, `events_health`, `events_retention` |
| OD-JOURNAL stamping | `src/runtime/next/_internal_runtime/engine.py` | `_append_event` only |
| seam wiring (FR-002 to FR-004) | files in 4.2 | named symbols |

The client holds no schema, id, event clock, order, dedupe or type knowledge. Its handlers never raise (the outermost catch maps every failure to a fault), so the seam's generic `except Exception` branch is unchanged. Fault `event_type`/`event_id` are opaque copies of the row's. No environment override of the binary and no producer setting (OD-OPTOUT); tests patch `locate_binary`.

### 4.2 Seam wiring and the allowed-surface list (resolves F-1)

FR-027's gate takes this table as its data. It is the complete list of Python changes this mission may make; the gate fails on any other new module or function in the status and emission area. A file's new top-level functions, classes, methods and imports (any depth, as `(module, name)`) must lie within its row; the per-file lists are R-16 and are identical to the rows here. A file named in several rows (`status/lifecycle_events.py`, `decisions/emit.py`, `events/decision_log.py`, `migration/mission_state.py`) is unioned: its allowed names and imports are the union of all its rows, a duplicate key is not an error, and a row naming several files applies to each.

| File | Allowed symbols (new or changed) | New imports | Nature |
|---|---|---|---|
| `status/adapters.py` | `fire_saas_fanout`, `fire_lifecycle_saas_fanout`, `fire_resolved_binding_fanout` (drain gate removed; `row` passed through; busy and timeout branches call `record_fault` **only for a client handler's `_handler_key`**), new `_dispatch_handlers` (campsite fold of the three `try/except` ladders, WP06's first commit, texts byte-identical), `ensure_zeitgeist_moment_handlers` (logic unchanged), `reset_handlers` (docstring), new `ensure_producer_client` (registers outside the `moment_handlers_disabled_reason` branch, injects `bound_resolver=_saas_fanout_timeout_s`) | `producer_client`, `producer_faults.record_fault` | wiring |
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

**Journal route (one per kind, FR-035/FR-003).** The runtime layer cannot import `specify_cli` without growing the ledgered edge cap of `tests/architectural/test_layer_rules.py` (`_RUNTIME_ALLOWED_SPECIFY_CLI`, `_baselines.yaml`), and `engine.py` imports none today, so every run-journal kind reaches the seam through the existing `RuntimeEventEmitter` chain (`engine` -> `DecisionGitLog` -> `_BufferingRuntimeEmitter` -> `RuntimeMomentProducer._publish_journalled`, which already holds the `specify_cli.status` import). The six moment kinds use their existing methods; `SignificanceEvaluated` uses `emit_significance_evaluated`, which `_record_significance` already calls (so `engine.py` gains no seam call there); `DecisionAuthorityDenied` gets the one new Protocol method, called from `_authorize_audit_answer`. `engine.py` never calls the seam, so nothing double-fires. `DecisionTimeoutExpired` stays out of scope. Blast radius and test fakes: R-16. **The client gets the raw persisted journal line as `row`, never an envelope.** `_publish_journalled` still builds `envelope` (UUID5 `moment_event_id`) for the Zeitgeist handler until #6164; those names are residuals deleted with it. The producer's `id` is `row["event_id"]` alone; rows lack it until WP07 and are then refused, never given a Python id. WP06's census uses a stubbed client and asserts calls and `row` keys within the five persisted keys. Tests, residuals, sweep: R-24.

**Gate** `tests/architectural/test_producer_client_thinness.py` (new; full rules R-16): (a) the three new modules: closed import allowlist, no write-mode `open()` on the publish-log constant (also AC-030), `json.loads` only in `_read_reply` and `_read_fault_rows`, a wall-clock read only in `_now_utc`, the exact top-level symbol set equals the table; (b) `_append_event` key set equals the five keys (WP07, with the stamping); (c) the emission-area module census equals a pinned list; (d) the client imports none of `_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`; (e) **every existing Python file of the table (except the last row)**: its top-level function, class and method set and its import set lie within the base commit's plus that file's row, so a helper such as `_mint_id`, or an import of `uuid`, `hashlib`, `datetime`, `secrets` or `random` not in the row, added to `emit.py`, `lifecycle_events.py` or `engine.py` fails (`engine.py` may add `ulid` and nothing else). "Within" lets a row's edit land in a later WP than the gate. Floor: every module and table file found, call sites above zero. Self-mutation (AC-012, cases in R-16): client, `producer_faults.py`, `emit.py`, and an out-of-row import in `engine.py` and `rebuild_state.py` copies. The pinned lists in (c) and (e) are priced debt, deleted when #6193 retires the Python writers.

### 4.3 Fault, retention, and ledger rules (F-2, F-6)

Python forwards `ledger.retention.keep_runs` and `max_age_days` exactly as read from `.kittify/config.yaml` (or omits them); Java applies the defaults 20 and 30 and validates (AC-031 fixtures live in Java tests, plus one Python test that the raw string `"abc"` reaches Java unchanged). Retain fails closed on an unreadable ledger (section 8).

### 4.4 Lane-list consolidation and marker clearing

First task of the Python WP is the census (FR-034): the sets of data-model section 9 plus any further hit; read `tests/specify_cli/migration/test_rebuild_state.py` and the acceptance tests first. The four lists are not copies of the vocabulary, so none is derived wholesale (R-14): `_VALID_LANES` (eight lanes, no `in_review`) becomes `CANONICAL_LANES` minus `in_review` stated explicitly; `_LANE_ORDER` (a five-step migration history path) and `CANONICAL_LANES` (the import-light literal; `status_lanes.py` must not import `models`) stay literal and are registered as reviewed exceptions with a reason; `_LEGACY_NOT_DONE_LANES` keeps its order with `doing` as an explicit alias constant; `_PRE_APPROVED_LANE_VALUES` is selected by name from `Lane`. The imports each file may add are the 4.2 rows. Each change follows a red-first characterisation test (value and order equal to the pre-change literal; every member a `Lane` value). `Lane` stays the authority; `tests/architectural/test_single_lane_list.py` finds the census lists (floor six, exceptions listed) and fails on a new full literal list (mutation case). Marker disposition per data-model section 8: clear 1, 2, 5, 6, 8; markers 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`), 7 (`status/lifecycle_events.py`) decided in the WP by the rule "clear only if the persisted line validates in legacy-row form, else residual with an issue reference"; 9 to 16 untouched.

## 5 Request/response protocol

Transport: the client runs `[<binary>]` with no arguments, writes one UTF-8 JSON object to stdin, closes it, and reads one JSON object from stdout. One spawn per row (OD-SPAWN); batching is only the NFR-002 contingency. Shapes: R-4.

- **Versioning.** `"v": 1` in both directions; an unknown `v` is exit 64 (client `crash`). New optional members may be added within `v` 1; removing or retyping one needs `v` 2.
- **Operations.** `produce` (`row`, `slot`, `seam_args`, `start_path`, `deadline_ms`) and `retain` (`start_path`, `dry_run` default true, raw `policy`, `deadline_ms`). No `nodeid`, facts or schema in a request.
- **Reply.** `produce`: `outcome` (`produced|skipped_duplicate|refused`), `event_id`, `type`, `reason_class` and a closed `reason` when refused, optional `notice` (`torn-line`), `detail`. `retain`: `eligible`, `kept` (each with reason), `deleted`, `policy_error`. Never the row, an absolute path or a stack trace (NFR-006).
- **Exit codes.** 0 a reply was written (refusals are replies); 64 unreadable request or unsupported `v`; 75 deadline reached (watchdog); anything else, a signal, or empty stdout is `crash`.
- **Timeouts.** `effective_client_timeout(bound)` = 3.0 s when `bound <= 0` (seam runs handlers inline), else `min(3.0, max(bound / 2, bound - 1.0))`, so it is positive and strictly below any positive bound (0.5 gives 0.25 s, 1.0 gives 0.5 s, 1.5 gives 0.75 s, 10 gives 3.0 s); `deadline_ms` is that value in whole milliseconds, at least 1. AC-006 adds the boundary cases 0.5, 1.0, 1.5, 0 and negative, and a slow Zeitgeist handler case (no producer fault row). Provisional until AC-036 (section 9); the breaker bounds a batch to one timeout.
- **Fault classes** `binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`; an enum-equality test asserts `disabled` is absent. The mapping table is R-4. stderr of the child is discarded.

## 6 The spec-kitty-events PR

**Separate PR, in the events repo, sequenced first and released** (FR-040); the orchestrator opens it. Content (FR-014, exactly): per-kind `data` JSON Schemas for the types of data-model section 3 (v1) with lifted-member lists; registry entries (type, scope, `subject` rule, lifted members, schema file) in one machine-readable file the Java build reads, so Java has no per-type code; `dataschema` URIs; producer conformance fixtures (valid, invalid, legacy-row-form variants generated from the single schema plus the lifted list, FR-032); the comparator specification and the nine fixtures of data-model section 4; `spec_kitty_events.Lane` alignment (data-model section 9); conditionally the minimal envelope subset (FR-035). Confirmations asked: the INTERIM choices, the `source` tie-break, `schemaversion`, the `dataschema` base URI, and that #92 adopts the ruled prefix and interim type names (F-3).

**Dependency, gating, pin.**
- The Java build fetches the released wheel `spec_kitty_events-<V_E>-py3-none-any.whl` (version assigned by the events repo; none here, C-006) by a plain Gradle task: URL and sha256 in `services/event-producer/gradle/events-pin.properties`, sha256 checked before unzip, schemas and registry extracted into `build/` and embedded as native-image resources. Nothing from the events repo is committed (`tests/architectural/test_no_committed_events_schema.py`; floor: the pin file found; mutation: a planted `*.schema.json` with an events `$id` fails; AC-027). The wheel because PyPI is the only release channel (P15).
- WP08 raises the `spec-kitty-events` floor to `V_E` in `pyproject.toml` (`uv.lock` follows); `tests/contract/spec_kitty_events_consumer/` pins the surface. WP08 precedes WP07, whose Python AC-019 gate reads the schemas from that installed package (R-24).
- **While unreleased.** Java contract tests run against a pinned released version only; `-PeventsWheel=<path>` exists for development and is refused when `CI` is set. No WP that reads the schemas is approved, and the mission PR is not marked ready, before the pin names the released wheel. Work that does not read schemas proceeds.
- **Reconciliation.** One schema set, one writer per type: events#92 keeps envelope schema, validator, upcaster, STRICT registry and the sort-key switch; this PR carries only the FR-014 list. #6193 reuses this registry and domain. Rebase on #92 if it lands first and drop the minimal envelope subset.
- **Events-repo charter** (Python library, Pydantic; #6117 decision 11) needs an amendment allowing JSON Schemas and fixtures as the contract for non-Python producers; owned by the events PR or #92 (section 13).

## 7 Clock state file

Detail: R-5.

- **Path and format.** `<git-common-dir>/spec-kitty/event-producer/clock.json` (`{"v":1,"nodeid":"<12 hex>","clock":<int>}`, integer 0..2^31-1) and sibling `clock.lock`; the common dir is resolved by reading `.git` and `commondir`, never by spawning git. Worktrees share it; separate clones share only the `nodeid` (accepted residual).
- **Locking.** One exclusive `FileChannel.lock()` on `clock.lock`, held across read, stamp, dedupe, state write and append (POSIX `fcntl` advisory byte-range lock, one descriptor per process; Windows `LockFileEx`). Wait bounded by `deadline_ms`. Local file systems only.
- **State first.** Temp-file, `fsync`, atomic move, then append and `fsync`: a kill leaves no advance, or an advance and no line (a gap); never a reused value (AC-010 injects the three kill points through a `FaultInjector` port whose only non-no-op adapter lives in the test source set).
- **State loss.** Next stamp = max(state, highest `lamportclock` readable across publish-log files) + 1. Overflow above 2^31-1 is refused as `clock-overflow`; no wrap.
- **nodeid.** SHA-256 of `hostname:username`, first 12 hex; hostname by an FFM downcall to the C library `gethostname` Python calls (`ws2_32` on Windows); username from `LOGNAME`, `USER`, `LNAME`, `USERNAME`, then `user.name`. Golden test per OS (AC-039): live case plus two injected strings, one non-ASCII.

## 8 Retention in Java

Detail and file table: R-6.

- **Operation shape.** `Retain` lists candidate local-tier files, derives facts, applies `RetentionPolicy` (defaults 20 and 30; a named `policy_error`, also on a dry run, AC-031) and returns eligible/kept/deleted. `dry_run` is true unless the client sends false, which `doctor` does only with `--apply`.
- **Whole-file deletion only.** The producer opens no candidate file for write in `retain`; it calls delete on whole files, each under `clock.lock` (below). Candidates come only from the local-tier directories, never from `kitty-specs/`; AC-014 offers every tracked ledger as a candidate and asserts zero writes and zero deletes on tracked paths.
- **Placement and "run"** (R-6). Publish log: `.kittify/events/publish/missions/<missionid>.jsonl` and `project-<project_uuid>.jsonl` (run = one Mission file); fault log (client-written): `.kittify/events/faults/<UTC date>.jsonl` (run = one UTC day); run journals: the existing per-run file.
- **Mission open.** Derived in Java from the committed ledger(s): open iff any work package's latest row (greatest `at`) has `to_lane` other than `done`/`canceled`; the canonical copy and a coordination-worktree copy are both read, open if either says so. **An unreadable, unparseable or absent ledger keeps the Mission's files ("facts unavailable"); AC-013 (F-6).**
- **Lock fact = the content of a Python-owned lock sidecar, never its presence** (non-empty parsable `LockRecord` younger than 60 s, `kernel.locks.STALE_AFTER_S_DEFAULT`; empty sidecar no lock; unreadable keeps the run; Java never takes an OS lock on a Python-owned file; `clock.lock` is never a lock fact; unfinished step: a journal without `MissionRunCompleted`). Fixtures and reasons: R-21.
- **Retain versus produce.** `Retain` takes `clock.lock` (bounded by `deadline_ms`) per candidate file, re-checks eligibility, length and newest row under it, then deletes; lock timeout or delete failure is kept-with-reason (`busy`, `delete-failed`). Two-process test: `produce` racing `retain --apply` loses no reported row (detail: R-23).
- **Dry run first.** `spec-kitty doctor events-retention` (dry run), `--apply` to delete; never implicit.
- **Doctor pass-through rendering.** `doctor events` and `doctor events-retention` live in `_event_producer_doctor.py`; Python renders and counts only (fields, window rule, hint: R-22).

## 9 Test strategy and gates

**Method (Standing Order 4).** Every behaviour has a test committed red first, as its own commit before the implementation, verified red on the WP's base and green on its tip, and revert-checked (revert the implementation hunk, the test must fail). Floor and self-mutation cases on every census gate (spec floor rule). Golden files come from the real serializer (#4182). Each WP declares its targeted test surface; no whole-directory or `make test-full` run in mission work.

**Python gates (shim).** New architectural gates: `test_producer_client_thinness.py` (section 4), `test_single_lane_list.py`, `test_no_committed_events_schema.py`, `test_publish_log_single_writer.py` (AC-030), `test_seam_census.py` (AC-004: one forced row per writer yields one seam call), `test_producer_fault_enum.py`, `test_exemption_ledger.py` (AC-019). Behavioural tests in `tests/status/` and `tests/specify_cli/status/` for AC-001 to AC-007, AC-011, AC-018, AC-021 to AC-023, AC-025, AC-026, AC-033, AC-035. Existing gates re-run: R-7. Coverage: diff-cover >= 90% (CI), new Python modules 95%.

**Gradle gates (Java).** `gradle -p services/event-producer test`: unit tests per subproject; ArchUnit (section 1); golden `nodeid` plus a per-OS CI step against `identity/project.py::generate_node_id` (AC-039); order fixtures (AC-008/009); clock concurrency and kill tests (AC-010); torn line (AC-026); determinism and byte rules (AC-034); contract tests against the pinned events wheel (AC-002/008/027); **the FR-005 guard, AC-029: a `:domain` test over every type of data-model section 3, driven by the registry, asserting the `data` key set is a subset of the persisted row's keys (for lifecycle rows, its `payload`) plus the lifted-member rules; floor: every section 3 type resolved; mutation: an injected member (a `meta.json` or environment value) fails**; retention (AC-013/014/031, the two-process race of section 8); dependency verification (G-13, with a tamper test); JaCoCo floors: `:domain` 95% line and 90% branch, `:application` 90% line, `:adapter` 80% line, `:launcher` excluded (the plan's choice; the charter has no Java row, section 13).

**Packaging and release gates.** Section 3.4, plus dist-set equality with mutation, `smoke-wheels`, SBOM (AC-040), checksum continuity (AC-032), `twine check`, `uv lock --check`.

**Pin tooling (one authority, WP01).** `contracts/tools/gradle_pin_check.py` is the single pin-versus-build authority (P21); `services/event-producer` becomes its third `Subproject` (Gradle version in the wrapper properties and workflow `gradle-version` inputs; the toolchain major in `build.gradle.kts` as one new check in the same file), with planted-mismatch cases and a compared-values floor in `tests/contract/test_gradle_pin_check.py`. `verify_pins.py` runs with `--pins services/event-producer/tools/pins.json --workflow` for both new workflows (its default scope is `contracts*.yml`), enforcing action SHA pins; `tests/contract/test_verify_pins.py` is re-run. No Gradle-task re-implementation.

**CI home.** New `.github/workflows/event-producer.yml` on `pull_request` and `push` to `main` with path filters `services/event-producer/**`, `scripts/release/**`, `pyproject.toml`, `src/specify_cli/status/producer_*.py`, `src/specify_cli/_native/**`, itself and `producer-native.yml`; jobs: `verify-pins` (installer checksum plus the two tools above), `gradle-test` (ubuntu, JDK 25, JaCoCo, ArchUnit, dependency verification, tamper check), `native-smoke` (three legs through `producer-native.yml`, then wheel by path and smoke script on each OS), terminal `event-producer-gate` (the `contracts-gate` pattern). Every root job carries the fork guard first. WP01 registers both new workflows in `scripts/ci/fleet_verdict.py::PR_WORKFLOWS`, `ci-fleet-verdict.yml`, `tests/ci/test_fleet_verdict.py` and the `NON_CHANGE_TRIGGERED_WORKFLOWS` ledger (P6), because the closed inventory test is red on any unregistered PR workflow. Nightly: `ci-nightly.yml` calls the same smoke path (WP09; AC-036 numbers recorded there).

**Performance (AC-036/NFR-002).** `tests/_perf_helpers.py` gains relative producer limits (`test_perf_limit_authority.py` rejects absolute ones): the largest multi-row command (`emit_status_transition_batch`) with a real producer over the same with a no-op handler, cold and warm, per OS; numbers from WP01 and WP10 measurements; on failure the NFR-002 contingency applies after amending FR-003, FR-026 and AC-004.

**CI gates not included, with reasons:** R-8. **Gate-implication audit:** R-7; hardest-biting: `test_status_module_boundary.py` (SR-2: client names via the `status/__init__.py` facade), `test_runtime_emitter_seam.py`, `test_hosted_drain_gate.py`, `test_completion_manifest_freshness.py`, `test_release_ci_ownership.py`, `test_fleet_verdict.py`, `tests/status/conftest.py`. Also ruff check and format (`--force-exclude` per file), `TID251`, `uv lock --check`, `spec-kitty regen --check`, docs lint, the terminology guard. **Contextive** (FR-046): terms to `docs/context/system-events.md`, then `generate_contextive_glossaries.py generate` and `check`; no CI job runs `check` (P17), so a targeted test does (15.5).

## 10 Baseline method

Before the first change the orchestrator captures pre-existing red from a detached scratch worktree of the base commit: each gate file of R-7 (with `tests/contract/test_gradle_pin_check.py` and `test_verify_pins.py`) and the owning fast tiers, one invocation per file, into `research/baseline-red.txt` (base sha, command, counts, failing node ids). A red node id with the same failure text is pre-existing; any other is introduced and is the WP's to fix. **#3283** (venv-lock bootstrap cascade; `.github/actions/warmup/action.yml`) defines the environment rule: a failure of that shape (shared test-venv lock timeout, or `.venv/bin/python` missing with exit 127) is re-synced (`uv sync --frozen --all-extras`, the stale-venv rule of `AGENTS.md`) and rerun once alone before it is recorded. **#3284** (the sanitation mission's base failures, `docs/reports/test-sanitation/assertive-test-suite-sanitation-01KZME3P/issue-matrix.md`) is a tracked issue: a red node attributable to it is recorded "known red, tracked: #3284". Any other distinct pre-existing red needs an issue before work continues past it (charter rule); the operator opens it (the orchestrator cannot write to GitHub). Known-P0 reproductions are not run. Detail: R-9.

## 11 Work packages and PR shape

**One PR** (draft, `main`), compact history, `spk-land-pr` pass before it sits ready. **No slices**: the Java tree, wheel machinery, client and release workflow are only useful together, and every WP leaves its lane green on its own gates. The events-repo PR goes first; no split is planned.

**Ownership rule.** A WP that adds a file or workflow owns every gate edit it forces, in the same WP, and no gate lands before the edit it checks; a chokepoint owner is only for files several WPs edit, which then take their edits one WP at a time in dependency order (audit: R-15). Seam call sites, the emitter route and the census are WP06's; stamping, gate (b), lanes and markers are WP07's.

**Campsite-clean (Standing Order 2).** (1) WP01's first commit, docs-only: the stale "PyPI Release" paragraph of `AGENTS.md` and the matching line of `scripts/release/README.md`. (2) WP06's first commit folds the three `try/except` ladders of the `fire_*` functions behind `_dispatch_handlers`, characterised by `tests/status/test_saas_fanout_timeout.py` and `test_drain_fanout.py`. (3) WP06's edits to `status/emit.py` (1570 lines), `lifecycle_events.py` and `engine.py` are call sites and keyword additions with no new branch: no extraction. The orchestrator dispatches a campsite scout on `status/emit.py` and `events/runtime_moments.py` before WP06 (findings fold or defer under `RECONCILE_CHANGE_SCOPE_TENSIONS`). Sonar was not measured. Full text: R-20.

| WP | Scope | Write-scope notes | Depends on |
|---|---|---|---|
| WP01 | Spike: native image on three OS; `producer-native.yml` and `event-producer.yml`; **fleet-verdict registration and duplicate-suite ledger rows for both**; pins, third `gradle_pin_check` Subproject, verification metadata; **hatch hook, `pyproject.toml` hook table, `.gitignore`, minimal stub-wheel packaging test**; campsite (1) | **risky first**; owns the Java skeleton, `fleet_verdict.py`, `ci-fleet-verdict.yml`, `test_fleet_verdict.py`, the hook; first editor of `pyproject.toml` | none |
| WP02 | `:domain`: ids, clock value, order, envelope model, retention eligibility, ArchUnit; the events-pin fetch task and `events-pin.properties`, the AC-029 registry-driven test and the nine comparator fixtures (these last items wait for the released wheel; the rest starts now) | `:domain`, the pin task | WP01; events release for the pin items |
| WP03 | `:adapter` files: lock, clock store, publish log (torn line, dedupe, determinism), repo locator, nodeid FFM and golden | `:adapter` | WP02 |
| WP04 | `:application` `Produce`, launcher protocol, schema validation against the pinned events wheel, faults vocabulary | needs the events release for the contract tests | WP03, events release |
| WP05 | `Retain` (clock.lock, lock-record reader), committed-ledger reader, policy | `:adapter` ledger package | WP03 |
| WP06 | Campsite (2), Python client, all seam call sites of 4.2 (status, decisions, retrospective, `runtime_moments`, the emitter route in `events.py`, `decision_log.py`, `runtime_bridge_retrospective.py`, `engine.py` denial call), census, thinness gate except (b) (its floor counts all three new modules), fault log, doctor sibling with completion manifest and CLI reference, `state/contract.py` entries | **sole owner of `status/adapters.py`**, `state/contract.py`, `tests/status/conftest.py`; first editor of `engine.py` | WP04, WP05 (protocol, `retain`); none blocking on #6164 (hunk handling: section 12) |
| WP07 | `_append_event` stamping with gate (b), lane consolidation, marker clearing (AC-019 validates persisted lines against the released schemas), glossary and Contextive, ADRs | second editor of `engine.py` (after WP06); owns lint script, `_baselines.yaml`, `dead_symbol_allowlist.yaml`; reads schemas from the installed `spec_kitty_events` (R-24) | WP06, WP08 (floor bump; WP08 runs earlier) |
| WP08 | Packaging remainder: `check_dist_set.py`, `dist-set.json`, `check_exact_install.py`, fixtures, packaging tests, events floor bump | second and last editor of `pyproject.toml` (after WP01); ordered before WP07 | WP01; events release (floor bump) |
| WP09 | `release.yml`, `ci-nightly.yml` call, SBOM, attestation, release docs | **sole owner of `release.yml`** | WP08 |
| WP10 | Integration: default-install end to end, AC-036 measurement, AC-025, mid-flight Mission (AC-017), reflexivity check, final gate pass | read-mostly | all |

Risky and unknown work first: WP01 proves the native image and the hook on all three OS.

**Tracer files** are seeded beside this plan (Standing Order 3).

## 12 Interaction with #6164

Not landed; the seam fires the client irrespective of drain, so either order works. `status/adapters.py` hunks, in WP06 commit order (R-11): (0) the campsite fold, (A) drain gates and the `hosted_posture` import deleted, (B) `row` and fault-recorder calls in the busy and timeout branches, (C) client registration outside the `moment_handlers_disabled_reason` branch, (D) docstring and fixture. If #6164 lands first, A and the `zeitgeist_bridge` import block conflict as "deleted by them": take theirs and re-add C under their import tail; if this mission lands first, #6164 removes what A left. The drain gate moves into the Zeitgeist handlers (`status/zeitgeist_bridge.py`) while it exists, so `hosted.drain` keeps its meaning (C-003); the pin test is re-pointed at every Zeitgeist handler (AC-003 f). AC-003 skips (a) when `zeitgeist_bridge.py` is gone and runs (b) always.

## 13 Governance drift to flag

Flagged, not resolved.

1. **Charter has no Java gate row** (it says Python 3.11+ for all CLI and library code); the Java gates are the plan's choice.
2. **Python outside the thin client:** the hatch hook and the release set-check script (fork F1).
3. **ADR 2026-10-13-2 D-1, D-2, D-3 need amending** in place (as ADR 2026-10-06-1 did), plus one new ADR `docs/adr/4.x/2026-10-14-1-event-producer-java-native-binary-behind-thin-python-client.md` and an `index.md` update (detail: R-19; AC-033 checks the amendment names D-1, D-2, D-3).
4. **Events-repo charter** needs an amendment (section 6).
5. **No-vendoring rule** is met (pinned released wheel, nothing committed, gated).
6. **`AGENTS.md`:** "PyPI Release" says `release.yml` is absent (campsite 1); the charter has no row for platform wheels or native binaries.
7. **Contextive regeneration** is not CI-enforced (15.5).
8. **Reflexivity and mid-flight.** This mission's own events come from the Python writers; no gate depends on the producer. A Mission open at upgrade keeps its ledger byte-unchanged and gets a publish log from its first later event (AC-017); one without `mission_id` is refused per event with the repair named. No migration step.

**Glossary additions** (spec section 13, carryover L) go to `docs/context/system-events.md`: Producer, Publish seam, Publish log, Ledger, Fan-out (not "sync"), Clockless row, Clocked row, Fault log; `generate` and `check` run after.

## 14 Traceability

Every FR, NFR, C and G group maps to a section and its ACs in R-17.

## 15 Spec touch-ups proposed to the orchestrator

Twelve, not edited here; exact wording in R-18.

## 16 Operator forks

**F1. Python build glue under a Java-only rule.** Platform wheels need a hatchling build hook (Python by the backend's design) and a release set-check script comparing `dist/` with the pinned list (AC-037); pin checking extends the existing `contracts/tools/gradle_pin_check.py`. Options: (a) allow these bounded files plus edits to existing `scripts/release/*.py`, `contracts/tools/gradle_pin_check.py` and `tests/`, no product logic; (b) implement the set-check as a Gradle/Kotlin task (the hook stays Python, unavoidable); (c) change the backend away from hatch (rejected: far larger). Recommendation: (a), recorded in the new ADR and the FR-027 surface, because (b) adds a JVM dependency to the Python release path for no safety gain.

No other fork: the Linux wheel floor, JaCoCo thresholds, log placement, CI shape, fault-count window and timeout rule are plan decisions with a stated reason.

## 17 Claims not verified

Full list: R-12. Material: the GraalVM build, flags and Linux container route (WP01 proves them); Windows/Java hostname agreement (AC-039); events-repo file names; SHA-pinned action versions (WP09); the `env` context in a job-level `with:` of a reusable-workflow call (WP09 confirms); `spec-kitty plan --json` was not run; hatchling is absent from this venv, so hook semantics were read from cached 1.31.0 source, not executed.
