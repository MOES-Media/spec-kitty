# Detail handed to the plan

Mission `event-producer-reroute-01M4VDY9`. This file holds explanatory and implementation-level detail moved out of [`spec.md`](spec.md) and [`data-model.md`](data-model.md) so those stay within the review budget. Nothing here changes a requirement: every FR, NFR, C, AC and OD id, with its testable content and falsifier, stays in the spec. Each section below is referenced from the spec or data model by letter. The plan reads this file as input.

Sections: R rationale per FR; E evidence detail; T tooling defects; G gate rationale; K risk elaboration; M exemption marker reasons; X example lines; L glossary definitions; D governance drift detail; W total-order and worked-example narration; Q open-decision and scope prose; S further moved wording; Y type-table member lists; Z round-2 moved wording.

## R Rationale per FR

| FR | Rationale |
|---|---|
| FR-001 | ADR 2026-10-13-2 D-2: local adapters fire by default. |
| FR-002 | Publication must not depend on the frozen hosted relay. Three further silencers exist besides the `fire_*` drain gate: `RuntimeMomentProducer._publish` (its own `drain_posture` read, so run-journal rows would never be produced on a default install), the import-tail registration gated by `moment_handlers_disabled_reason` (`SPEC_KITTY_NO_MOMENT_HANDLERS`, `SPEC_KITTY_SYNC_DISABLE`, the deprecated alias), which would make a hosted kill switch silently remove the local producer (a not-produced event with no warning), and the status test fixtures that restore only the Zeitgeist wiring via `ensure_zeitgeist_moment_handlers`. ADR 2026-10-13-2 D-2 names the confirmation pin (every Zeitgeist handler checks drain first), which moves with the gate. |
| FR-003 | A producer that misses rows is a silent-success hole. The resolved-binding call (`emit.py::_resolved_binding_fan_out`) passes `causation_id`, not a row, and is a hosted projection of an annotation row that is itself persisted and of data already inside the lane row's actor binding; registering the client there would deliver the same annotation twice. The retrospective rows are written by a fourth writer (`append_raw_rows_atomic`) and are authoritative non-lane rows. |
| FR-004 | E3: the existing arguments omit `event_id` (except as `causation_id`), `reason_source` and `review_result`; without the row the producer cannot honour FR-005 or FR-016. A seam-argument addition, not client logic (FR-026). E14: the `repo_root` keyword is a starting path (`fanout_lifecycle_event_hosted` passes the log's directory, `RuntimeMomentProducer` the feature directory), and `hosted_posture._resolve_repo_root` exists because callers hand it subdirectories and returns None on failure, which the old code treated as drain-off. A producer taking the value at face value would place the publish log, clock state and fault log under a Mission directory or a lane worktree. |
| FR-005 | E10 (the sanitizer): a second path must not widen disclosure. |
| FR-006 | Dec-3, Dec-4. |
| FR-007 | Join key between publish log and ledger; Dec-4 Q2 (ULID only for new events). Producer: ULID only, any UUID form refused. Readers keep accepting UUID in legacy rows; `normalize_event_id` (events package) is the reader and order-key rule. A stripped-env install therefore produces no lifecycle events, with a recorded warning (AC-005). |
| FR-008 | Deterministic, nothing invented (ADR design section 9). |
| FR-009 | Design sections 3 and 6. E15: Missions older than `mission_id` exist in the code; ADR 0001's own rule for a non-ULID mission segment is the project-source fallback (OD-MISSIONID option b); this spec does not diverge from it silently. |
| FR-010 | CloudEvents naming rule; design section 2. |
| FR-011 | Dec-4 Q1. The canonical definition is `identity/project.py::generate_node_id` (`socket.gethostname`, `getpass.getuser`, SHA-256, 12 hex); `migrate_lifecycle_envelope._generate_node_id` is a private copy kept for CORE import rules. Java and Python can disagree on hostname resolution on macOS and Windows, hence the per-OS golden test (AC-039) and OD-NODEID. |
| FR-012 | Lamport soundness; CloudEvents Integer range. The nodeid is per machine and user, not per checkout, while the state file and publish log are per checkout, so two worktrees would mint the same pair without a shared counter (OD-CLOCK). Timeout or crash after the append can leave the row written while the client records not produced, and a later re-fire is deduplicated (FR-016): hence the "may not have been produced" wording (FR-023). A fresh clone has no state and no log; nothing in the ledger carries a clock, so the restart cannot be repaired locally. |
| FR-013 | Precondition of Dec-4 Q1. |
| FR-014 | Dec-7: one schema set. The closed list resolves an inconsistency with events#92, whose body lists envelope, registry, schemas, upcaster and compatibility entries but not an ordering comparator or a `Lane` change; the hand-off row asks #92 to acknowledge them. |
| FR-015 | Persisted-line validation, not payload-only. |
| FR-016 | Lifecycle dedupe, retries and re-entrant fan-out must not double-publish. INTERIM key. |
| FR-017 | Bounded to "mission lifecycle and status events"; the run journal is in scope only because #1248 is named. If a later decision defers it, #1248 becomes an explicit residual and this contradicts "clear #1248". |
| FR-018 | Closes the `reason_source` gap (E7); design section 3. |
| FR-019 | Windows byte rules (#4182); local tier of ADR D-3. |
| FR-020 | Dec-7; keeps lock interop out of scope. C-004 is read per file: the authoritative ledger row and the publish-log row each have one writer; the epic's migration rule (#6195, #6196: Python writers deleted in the same release) is met at cutover. |
| FR-021 | Silent-success rule. |
| FR-022 | Dec-6; the #2535 machinery-fault ruling. |
| FR-023 | Never silent (D-WARN). The bound lives in the seam, per handler call, not in the client: `_run_fanout_handler_bounded` raises `_FanoutHandlerBusyError` when the same handler is still in flight and `TimeoutError` after the bound, and `fire_saas_fanout` / `fire_lifecycle_saas_fanout` only log those outcomes; these paths produce no row and no warning unless they call the client's recorder. stderr carries the warning in every mode because stderr does not alter a `--json` body; the fault log alone would leave a `--json` run or an unwritable log with no record. |
| FR-024 | A once-per-event line scrolls away; a count does not. The count is derived from fault-log rows in the retention window, so a success cannot reset it and retention pruning is the only reduction. |
| FR-025 | A hang must not stall a command. `SPEC_KITTY_SAAS_FANOUT_TIMEOUT <= 0` removes the seam bound (inline mode); the client timeout still applies. `emit_status_transition_batch` fans out once per event, so N events could pay N client timeouts; the per-process breaker bounds it. |
| FR-026 | Dec-2. |
| FR-027 | Standing Order 5. |
| FR-028 | ADR D-3. |
| FR-029 | ADR D-3 eligibility. Open Mission and unfinished step are defined in terms of lane state and run-journal or lock state so AC-013 can name fixtures. |
| FR-030 | ADR D-3. |
| FR-031 | C4; ADR D-3 committed tier. |
| FR-032 | ADR D-1 follow-up. The lint inspects source shapes, not persisted lines. The legacy persisted rows keep the lifted members inline, so validating them needs a second shape: the legacy-row form, generated from the single `data` schema plus the lifted-member list (one source in the events PR), never authored separately. |
| FR-033 | Scope honesty. |
| FR-034 | "3x lane-list duplication"; charter single canonical authority. |
| FR-035 | E5, ADR D-1; Dec-7. See OD-JOURNAL. |
| FR-036 | Dec-3. |
| FR-037 | Decouples release order. |
| FR-038 | Archive-freeze ADR 2026-10-11-1; reflexivity. |
| FR-039 | E12; #6193 takes over this producer. Charter: external contract packages are consumed, "do not vendor their source"; the delivery mechanism is OD-SCHEMA-DELIVERY. |
| FR-040 | Charter, Development Workflow Requirements. |
| FR-041 | Dec-5. |
| FR-042 | Provenance and a pinned toolchain. A checksum computed in the build that produced the wheel cannot detect a swap between the per-OS build job and the assembling job; the recorded value is therefore re-verified against the wheel member (AC-032). Native-image output is not bit-reproducible by default, so no reproducibility claim is made. |
| FR-043 | Dec-6. A graceful fallback converts a release defect into a silent one if a platform wheel is missing, hence G-12. Whether a pure fallback wheel ships is OD-FALLBACK; making the wheel platform-specific needs a hatchling build hook that must stay conditional (an unconditional one breaks `uv sync`, editable installs and the sdist-to-wheel step). |
| FR-044 | Charter pack tiers; `test_packaging_safety.py`. |
| FR-045 | Section 9. Items: D-1 (flat rows), D-2 (a subscriber reads the ledger and the named paths, never being called), D-3 (the closed list of local-tier files). |
| FR-046 | Terminology canon. |

## E Evidence detail (spec section 2, original table)

| # | Fact | Where |
|---|---|---|
| E1 | `zeitgeist_bridge.py` is the only builder of the events `Event` (`_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`). | `status/zeitgeist_bridge.py` |
| E2 | The seam has three slots (`saas_fanout`, lifecycle, resolved-binding). All three `fire_*` functions return unless `hosted_posture.drain_posture(...).enabled`. Default wiring is `ensure_zeitgeist_moment_handlers()`, registered at import only when `moment_handlers_disabled_reason()` is None. Handlers run in a bounded daemon thread (`_run_fanout_handler_bounded`, `_DEFAULT_SAAS_FANOUT_TIMEOUT_S`, 10 s): a still-running handler is skipped (`_FanoutHandlerBusyError`), a bound <= 0 runs inline, failures are logged, never raised. `RuntimeMomentProducer._publish` reads the drain posture itself. | `status/adapters.py`, `events/runtime_moments.py`, `core/env.py` |
| E3 | The lane fan-out (`_saas_fan_out`) passes slug, ids, lanes, actor, `WPStatusChangeMetadata` and `repo_root`. The ledger `event_id` rides only as `metadata.causation_id`; `reason_source` and `review_result` are not passed. Coordination writes reach the slot via `coordination/outbound.py`. | `status/emit.py`, `status/wp_status_metadata.py` |
| E4 | `emit_inner_state_changed` has no fan-out of its own; only a resolved-binding annotation fans out. Lifecycle: `append_lifecycle_event` persists then fans out, `persist_lifecycle_event_local` only persists (callers such as `core/mission_creation_commit.py` fan out separately). Run-journal moments go through `RuntimeMomentProducer`, which publishes six moments and deliberately not `SignificanceEvaluated`; `DecisionAuthorityDenied` has no emitter. The retrospective rows (`RetrospectiveCaptured`, `RetrospectiveCaptureFailed`, `RetrospectiveSkipped`) are appended to `status.events.jsonl` through `append_raw_rows_atomic` with no seam call. | `status/emit.py`, `status/lifecycle_events.py`, `events/runtime_moments.py`, `retrospective/lifecycle_events.py` |
| E5 | The run-journal row `{event_type, timestamp, payload}` has no `event_id` or version. | `runtime/next/_internal_runtime/engine.py::_append_event` |
| E6 | `Lane` has 11 members (incl. non-persisted `uninitialized`); `CANONICAL_LANES` 9; `spec_kitty_events.Lane` 10 (incl. `genesis`). Lookalikes: `_VALID_LANES`, `_LANE_ORDER` (`migration/rebuild_state.py`), `_LEGACY_NOT_DONE_LANES` (`acceptance/__init__.py`), `_PRE_APPROVED_LANE_VALUES` (`consolidation/done_bookkeeping.py`). | `status/models.py`, `status_lanes.py` |
| E7 | `StatusEvent` carries `reason_source` (`operator` via `OPERATOR_REASON_SOURCE`, or `synthetic`); the reducer reads it; the events `diary.StatusEvent` drops it. | `status/models.py` |
| E8 | `canonical-producer-exempt` markers in `src/`: #1198 x7 (`decisions/emit.py` 2, `events/decision_log.py` 1, `migration/mission_state.py` 1, `status/lifecycle_events.py` 3); #1248 x1 (`engine.py`); #1200 x8 (`glossary/events.py`). `scripts/lint_canonical_producers.py` (baseline `scripts/canonical_producer_lint_baseline.txt`) inspects source shapes, not persisted lines. #1198 and #1248 are closed upstream. | files named |
| E9 | The reducer sorts `(at, event_id)`; the merge driver keys on string `event_id` and rejects rows without it. | `status/reducer.py`, `status/event_log_merge.py` |
| E10 | Node identity: `identity/project.py::generate_node_id` (first 12 hex chars of SHA-256(hostname:username)); `status/migrate_lifecycle_envelope.py::_generate_node_id` is a private copy. Persisted rows pass `sanitize_event_for_log`. | `identity/project.py`, `events/sanitizer.py` |
| E11 | One pure wheel via `python -m build` (hatchling) in `.github/workflows/release.yml`, then wheel-content check (`ls dist/*.whl` into one variable), exact-installability (`scripts/release/check_exact_install.py::locate_wheel` refuses more than one wheel), CycloneDX SBOM (`cyclonedx-py environment`), `twine check`, `publish-pypi` (one `release-distributions` artifact); the shared packaging fixture picks the last wheel and `test_wheel_contains_only_known_packages` allows a closed prefix list; `ci-windows.yml` is Windows-only and path-filtered; `release.yml` has no build matrix; `nightly-gate` precedes `build-release`. | files named |
| E12 | The only Gradle builds on `main` are `acceptance-jvm/` and `contracts/`; the #5529 stack is open PRs; #6192 extracts `domain-model` and `event-log` from it. | repository root |
| E14 | The `repo_root` keyword of the `fire_*` functions is a starting path: `fanout_lifecycle_event_hosted` passes `log_path.parent`, `RuntimeMomentProducer._repo_root` returns the feature directory; `hosted_posture._resolve_repo_root` walks up via `resolve_canonical_root` and returns None on failure. | `status/lifecycle_events.py`, `events/runtime_moments.py`, `core/hosted_posture.py` |
| E15 | `status/emit.py::_load_mission_id` returns None for Missions older than `mission_id`. | `status/emit.py` |
| E13 | `.kittify/events/`, `.kittify/runtime/`, `.kittify/derived/` are gitignored; no `ledger.retention` exists in code. The CLI floor is `spec-kitty-events>=10.4.4,<11`; `tests/contract/spec_kitty_events_consumer/` pins the consumed surface. | `.gitignore`, `pyproject.toml` |

---



## T Tooling defects (spec section 7)

Tooling constraints, stated self-contained (no external ledger ids): a `lib64` symlink in the tool venv can break startup, so the binary lookup and the merge driver must not assume a plain `lib`; primary and coordination partitions hold different event streams, so the publish log is per resolved checkout and never assumed unique per Mission; `GitignoreManager.ensure_entries` is not byte-preserving, so prefer paths already ignored (FR-019); CLI-written event lines can be left uncommitted, and the publish log is never a committed file, so that cannot apply to it.



## G Gate rationale (spec section 8)

| Gate | Why |
|---|---|
| G-1 wheel-content per platform wheel | A wrong or missing binary ships a dead producer; the existing known-prefix test rejects any wheel member outside a closed list, so the binary's location is named or the list amended. |
| G-2 exact installability per wheel | The existing step asserts exactly one wheel and goes red with several, or silently tests one. |
| G-3 by-path install and smoke on each OS | A built binary is not a working binary. Release proof today is a green nightly for the SHA plus tag-time checks; a native build first attempted at tag time is discovered after the nightly gate passed. `ci-windows.yml` is Windows-only, path-filtered and installs editable, so it cannot prove a built wheel; the path router's unmatched loop covers `src/**` only, so a Java-tree PR needs a named group. |
| G-4 `twine check` per wheel | Metadata validity per wheel. |
| G-5 SBOM lists Java dependencies and embedded toolchain | `cyclonedx-py environment` enumerates the Python environment only. |
| G-6 `uv lock --check` | The only dependency gate that runs in CI; `pip-audit` and `bandit` are declared in the lint group but invoked nowhere. |
| G-7 sdist and editable builds without the binary | FR-043, C-007. |
| G-8 provenance, pinned toolchain, checksum continuity | FR-042; the existing release checksums only the final `dist/`. |
| G-9 red-CI-no-release | Standing Order 9; `release.yml` has no matrix today and the aggregate gate in `ci-aggregate.yml` is the PR gate, so the structural change (per-OS build stage, assembling job, publish needs the checked artifact) is stated. |
| G-10 `packs/internal/` excluded | FR-044. |
| G-11 producer gates | Standing Order 5; each non-vacuous (floor rule). |
| G-12 wheel set equals the pinned list | PyPI files are immutable: a missing platform leg would give those users the sdist and a permanent `binary-missing` warning. |
| G-13 Java dependency verification | No Java audit exists; the contracts build already pins checksums in `contracts/gradle/verification-metadata.xml`. |

## K Risk elaboration (spec section 11)



- **Critical path to the milestone:** events PR released, then floor bump, then CLI producer; the producer chain is blocked by events#92 only for the envelope schema (fallback FR-035). #6193 is blocked by this mission, #6194 by #6193: slippage here moves the epic.
- **Native build:** the native-image toolchain, JSON reflection configuration (GraalVM) and per-OS build runners are new CI weight; an unmeasured spawn cost can break the 2 s budget (NFR-002).
- **Signing:** unsigned macOS and Windows binaries may be blocked by the OS (OD-SIGN).
- **Windows byte rules:** #4182 applies to the publish log: LF only, tested.
- **Interim hardening:** a flagged INTERIM choice (type names, member naming, `dataschema` base) becomes a breaking change once emitted; the events PR must confirm or change them before release.
- **Seam coverage drift:** a new writer added later bypasses the seam; the census gate (AC-004) is the guard.
- **Clock coupling:** a clockless row appended after clocked rows sorts first (section 3); the guard depends on #6195's schema bump.
- **#6164 ordering:** a rebase conflict on `status/adapters.py` is expected, not exceptional.
- **Disk:** the publish log duplicates ledger content locally; retention bounds it.
- **Clock regression:** a checkout with neither clock state nor publish log restarts the counter; the new rows then sort before older consumed rows. Only the clock fixes the order, so the state is kept while the log exists (FR-012).
- **Release shape:** a missing platform leg is permanent once published (G-12); the sdist fallback turns it into a silent warning.
- **Spawn cost:** one process per row, inside the command; unsigned Windows binaries add a first-run scan (NFR-002, OD-SPAWN).

---



## M Exemption marker reasons (data-model section 8)

### 8 Exemption ledger (entity `Exemption`)

Counts verified by grep of `src/`. Disposition: `clear` = the marker is gone and the persisted lines its writer wrote are validated in the legacy-row form (generated from the single `data` schema plus the lifted-member list); AC-019 uses one fixture row per cleared marker, written by the real writer; `residual` = stays, with a reason and an issue reference to be filed.

| Marker | File / symbol | Issue | Disposition | Reason |
|---|---|---|---|---|
| 1 | `decisions/emit.py` (`emit_decision_opened`) | #1198 | clear | Decision row validated against `decision.opened.v1` schema; marker removed. |
| 2 | `decisions/emit.py` (`emit_decision_resolved`) | #1198 | clear | Likewise `decision.resolved.v1`. |
| 3 | `events/decision_log.py` (git-log envelope) | #1198 | plan decides | Clear if its persisted line is covered by the decision schema; else residual with issue. |
| 4 | `migration/mission_state.py` (historical migration-replay builder) | #1198 | plan decides | Historical rows are legacy by definition; likely residual-by-design with a new issue reference. |
| 5 | `status/lifecycle_events.py` (function-level, before `_build_envelope`) | #1198 | clear | Lifecycle rows validated against the lifecycle type schemas. |
| 6 | `status/lifecycle_events.py` (dict literal in `_build_envelope`) | #1198 | clear | Same function; one marker and its inline echo. |
| 7 | `status/lifecycle_events.py` (`build_saas_lifecycle_queue_event`, lifecycle-to-SaaS wire envelope) | #1198 | plan decides | Hosted wire projection; clear if the hosted path is removed by #6164, else residual. |
| 8 | `runtime/next/_internal_runtime/engine.py::_append_event` | #1248 | clear | Journal row gains `event_id` and version (FR-035) and is validated against the run types. |
| 9 to 16 | `glossary/events.py` (8 markers) | #1200 | **residual** (named, not widened) | Out of the operator's named scope. |

The tests carry further fixture markers (sanitizer tests cite #1198; lint tests cite #1248 as intentional fixtures); they are test fixtures, not producers, and are reviewed in the plan. The producer lint (`scripts/lint_canonical_producers.py`) gains a persisted-line mode; its baseline file `scripts/canonical_producer_lint_baseline.txt` (37 entries at the time of writing, path and code only) is not widened.



## X Example lines (data-model section 5)

 (one per kind, at most three)

Lane transition (one log line; real bytes come from the serializer and golden tests, not from hand-written JSON):

```json
{"specversion":"1.0","id":"01JAAAA0000000000000000002","source":"/spec-kitty/missions/01JAAAA00000000000000MSN01","type":"ai.spec-kitty.lane.transitioned.v1","subject":"WP01","time":"2026-08-26T10:05:00+00:00","datacontenttype":"application/json","dataschema":"<dataschema-base>/lane.transitioned.v1","schemaversion":"<envelope-schema-version>","missionid":"01JAAAA00000000000000MSN01","missionslug":"041-reducer-unification","actor":"claude","executionmode":"worktree","lamportclock":17,"nodeid":"3fa1c07b9d2e","data":{"from_lane":"planned","to_lane":"claimed","force":false,"reason":null,"review_ref":null,"evidence":null,"policy_metadata":{"agent":"claude"}}}
```

Run-journal row, before and after FR-035:

```json
{"event_type":"MissionRunStarted","timestamp":"2026-10-13T09:00:00+00:00","payload":{"run_id":"..."}}
{"event_id":"01JAAAA0000000000000000009","row_version":"<journal-row-version>","event_type":"MissionRunStarted","timestamp":"2026-10-13T09:00:00+00:00","payload":{"run_id":"..."}}
```



## L Glossary definitions (spec section 13)


None of these terms is defined in `docs/context/`: **Producer** (the component that turns a persisted ledger row into a CloudEvents row), **Publish seam** (the post-persistence fan-out seam as the local publication point), **Publish log** (the gitignored local log of produced rows), **Ledger** (the committed event logs that are the authority for Mission state), **Fan-out** (the in-process call from a writer to registered adapters; not "sync"), **Clockless row** and **Clocked row**, **Fault log**. Definitions go to `docs/context/` (the plan names the file), then `python scripts/generate_contextive_glossaries.py generate` and `check`.


## D Governance drift detail (spec section 9)


1. **Charter "Python 3.11+ is required for all CLI and library code"** and quality gates pytest, mypy strict, ruff versus the operator's Java producer: Java build, test and lint gates, ArchUnit and coverage floors have no charter row. Python is allowed only for the shim.
2. **Charter forbids vendoring events source and treats `spec-kitty-events` as an external package:** Java binding generation sits across that boundary; the delivery mechanism is OD-SCHEMA-DELIVERY (plan-blocking), and events#92 gates a Java binding on a charter amendment not owned here.
3. **ADR 2026-10-13-2 D-1** chose "versioned ledger-row schema, flat rows", said "the canonical 3.0.0 Event envelope stays the wire contract for anything that leaves the machine" and "the CLI then writes from the package's models" (Python), and rejected canonical envelopes in the ledger. The operator's CloudEvents ruling supersedes this for the new rows. Two further departures: D-2 says a subscriber learns of events by reading the ledger and names the supported read paths (events tail, the Read API); the publish log and fault log are an added read path. D-3 lists the local-tier files under retention as a closed list; the publish log, fault log and clock state extend it. An ADR amendment covering D-1, D-2 and D-3 is a deliverable (FR-045).
4. **The events-repo charter** says "Python library" and "Pydantic" (#6117 decision 11): flagged, not amended.
5. **Charter "90%+ coverage for new code" and diff-cover >= 90%** are Python measurements; Java coverage tooling is unstated.
6. **CLAUDE.md versus the checkout:** the "PyPI Release" section says `release.yml` is not present; it is present at `.github/workflows/release.yml`. The charter wins; the line in CLAUDE.md is stale.
7. **Charter "PyPI distribution via automated release workflow"** has no row for platform-tagged wheels or native binaries.
8. No other CLAUDE.md-versus-charter disagreement found.

---



## W Total-order narration (data-model section 4)

The formal key and the eight worked examples are in data-model section 4; they were checked over all permutations (and the invariants over all triples) with a read-only script using the events package's `normalize_event_id`. Notes:
- Known consequences: a clockless row appended after clocked rows sorts first (only pre-cutover writers write clockless rows; #6195 stops old CLIs appending); a checkout with neither clock state nor publish log restarts the counter, so its new rows sort before older consumed rows.
- Source for rows with none: the empty string (sorts first). The upcaster is #92's; if it supplies a source, the comparator uses it. Not an error row, so a legacy log never loses rows for want of a source.
- Dedupe uses the same normalized-id domain as the key; otherwise two spellings of one id in one source would be distinct rows with equal keys (example 8), breaking totality.
- Examples 7 and 8 exist so a comparator that omits `source`, or dedupes on raw ids, fails the permutation test.
- Example 4: naive means "if both rows have a clock compare `(clock, time, id)`, else `(time, id)`": C<A (clock), A<B (time), B<C (time).
- Example 5: the shipped normalizer raises for any id that is not a 26-character Crockford ULID or a 36 or 32 character UUID, so ids such as `01abc` are error rows, not ordered rows; the fixture uses full ULIDs. Comparison is by ASCII code point (Java `compareTo` is UTF-16, Python compares code points); a ULID against a hyphenated UUID is deterministic and arbitrary, which is acceptable when stated.
- Dedupe precedes ordering because equal keys do not imply equal `(source, id)`; the shipped merge driver (`status/event_log_merge.py`) fails loudly on conflicting payloads for one id, and the conflict report mirrors that.
- The legacy run journal has no `event_id` (E5); feeding it to the comparator would make every pre-upgrade row an error row, so it is outside the order and read by its own reader.

## Q Open-decision, scope and reflexivity prose (spec sections 1, 10, 12)

### Section 1 out-of-scope, original wording

**Out of scope / non-goals.**
- Writing CloudEvents into the committed `status.events.jsonl` or any committed ledger, and retiring the Python writers: #6193, #6195, #6196 (one writer per type after cutover).
- Appends to committed logs, the Python/Java lock-interop spike and the byte-identical-append proof: #6193.
- Any change to `status/reducer.py` or the Java read model (D-REDUCER; hand-offs in section 7).
- Any change to `status/event_log_merge.py` (blocks cutover, not this mission).
- The CloudEvents envelope schema and validator, the legacy upcaster and its fixture corpus, the full registry of the 26 STRICT types, bindings generation, major-bump artifacts, the events-charter amendment: spec-kitty-events#92.
- Glossary rows (`src/glossary/events.py`, #1200): a named residual (FR-033).
- Backfilling history into the publish log: a non-goal; the upcaster and the read side cover history.
- Retiring the `3.0.0` envelope (ADR question 3b): not ruled; readers keep accepting legacy rows.
- Renaming the `saas_fanout` seam slots (ADR 2026-10-13-2 follow-up debt).
- Any hosted behaviour. `hosted.drain` keeps its meaning and stays off by default.


### Section 10 OD-SINK, OD-JAVA, OD-JOURNAL, original wording

| OD-SINK | Is the producer's sink the gitignored publish log (Python ledger writers authoritative), or the committed ledgers (Java the only writer)? Dec-3 / Q1 speak of "logs mixing clocked and clockless rows", which also describes the committed ledger after cutover. | (a) publish log now; committed-ledger writing at #6193/#6195; (b) write the committed ledgers here. | (a). It makes the fault rule safe (a missing binary cannot lose authoritative state) and keeps lock interop, the merge driver and the reducer out of this mission. After cutover the fault rule for authoritative rows is a different, fail-closed rule (the lifecycle command should fail rather than lose a ledger row). The total-order requirement is sink-agnostic either way. | plan |
| OD-JAVA | Standalone Gradle module now, or wait for #6192's `event-log`? | (a) standalone module, structured for absorption; (b) wait for #6192. | (a); waiting puts the 4.0.0 milestone on the open #5529 stack. FR-039 holds under both. A java-jenny profile exists in `packs/built-in/agent_profiles/`. | plan |
| OD-JOURNAL | The run journal has no `event_id`; FR-007 needs one. | (a) Python journal writer adds a ULID `event_id` and row version (ADR D-1); (b) defer run-journal rows, leaving #1248 an explicit residual. | (a). | plan |

### Added open decisions (round 1), detail

- **OD-SCHEMA-DELIVERY:** the Java build must validate against the events-repo `data` schemas (FR-015) while the charter forbids vendoring and events#92 gates a Java binding on a charter amendment. (a) a build-time fetch of the released events artifact, pinned by coordinate and checksum, commits nothing; (b) waits on #92; (c) copies and needs the amendment. All three satisfy FR-015; only (a) and (b) satisfy FR-039's no-committed-source rule today.
- **OD-FALLBACK:** with a pure fallback wheel, pip and uv fall back with no build step and tests that assume a pure wheel stay valid; without it every platform outside the matrix (linux aarch64, musl, macOS x86_64 per OD-MATRIX) builds the sdist on the user's machine. A conditional hatchling hook (or build-data override) must not run for `uv sync`, editable installs or the sdist-to-wheel step.
- **OD-MISSIONID:** option (a) turns every event of an old Mission into a fault (one warning per event, FR-023, which wins over any per-Mission wording) and `doctor` names the repair; option (b) follows ADR 0001's aggregate-split rule (a non-ULID mission segment falls under the project source with subject the full aggregate and no `missionid`).
- **OD-CLOCK:** (a) one counter anchored at the git common directory is shared by the primary checkout and each worktree; separate clones of one repository under one machine identity remain a residual. (b) a checkout discriminator makes the clock domain per checkout and relaxes FR-011 to its machine part.
- **OD-NODEID:** (a) needs a per-OS golden test because Java and Python can disagree on hostname resolution; (b) makes the request carry the value, a stated exception to FR-026 for one pass-through field.
- **OD-SPAWN:** the repository's perf method is relative (ratio to a fixed workload) with limits only in `tests/_perf_helpers.py` (`tests/architectural/test_perf_limit_authority.py`); an absolute 2 s assertion would be rejected. `emit_status_transition_batch` is the largest in-scope multi-row command.
- **OD-OPTOUT (revised):** under (a) the `disabled` class is absent from the enum and FR-022, FR-023 and ProducerFault; under (b) a producer setting independent of `SPEC_KITTY_NO_MOMENT_HANDLERS`, `SPEC_KITTY_SYNC_DISABLE` and the deprecated alias makes it reachable.

### Section 12 original wording

### 12 Reflexivity and mid-flight

This mission's own events (its lane transitions and lifecycle rows) are written by the Python writers today; the producer is not required to publish them until it ships. A Mission open when the producer ships keeps working: its ledger is unaffected, its publish log starts at the first event after upgrade, and no backfill is done (non-goal). The mission itself must not depend on the producer to pass its own status gates.


## S Further moved wording

### Superseded wording

The earlier wording of the total-order paragraph, data-model sections 6 and 7 (a `repo_root` request field, `count_since_clean`, a key without `source`) is superseded by the round-1 revisions and not kept.

## Y Type-table member lists (data-model section 3)

Emission-point detail moved from the type table: `emit_project_initialized` writes `.kittify/canonical-events.jsonl`; `MissionCreated` is fanned out by the caller through `fanout_lifecycle_event_hosted` (`core/mission_creation_commit.py`); the persist-only path of `emit_artifact_phase` is a census item; decision rows also reach the seam through `_queue_decision_fanout`; the run journal also reaches it through `RuntimeMomentProducer`, and its rows need a ULID `event_id` and version (FR-035).


The `data` members of each row, as originally listed (row members moved verbatim except the lifted ones named in the type table; the events PR owns the final schema and lifted-member list, FR-014):

| Legacy row | Type | Members |
|---|---|---|
| `ProjectInitialized` | `project.initialized.v1` | `project_slug`, `runtime_version`, `initialized_at` (`actor` as attribute) |
| `MissionCreated` | `mission.created.v1` | `mission_id`, `mission_slug`, `mission_number`, `mission_type`, `target_branch`, `wp_count`, `actor`, `friendly_name`, `purpose_tldr`, `purpose_context`, `created_at` |
| `Specify/Plan/TasksStarted` | `mission.<phase>.started.v1` (3) | `mission_slug`, `mission_number`, `actor`, `at`; local-only `artifact_path` kept (the hosted projection strips it, the local log keeps it) |
| `Specify/Plan/TasksCompleted` | `mission.<phase>.completed.v1` (3) | Started members plus `artifact_path`, `summary`; `wp_count` on tasks |
| `WPCreated` | `wp.created.v1` | `mission_slug`, `mission_number`, `wp_id`, `wp_title`, `wp_path`, `depends_on`, `actor`, `created_at` |
| `ReviewerSelfApproval` | `wp.reviewer.selfapproved.v1` | `mission_slug`, `wp_id`, `implementing_actor`, `intended_reviewer`, `failure_reason`, `fallback_approved`, `timestamp` (schema authored in the events PR) |
| `MissionReopened` | `mission.reopened.v1` | `mission_id`, `mission_slug`, `reason`, `reopened_by`, `reopened_at`, `cleared_merge` |
| `FollowUpRecorded` | `mission.followup.recorded.v1` | `mission_id`, `mission_slug`, `follow_up_type`, `commit_sha`, `pr_number`, `recorded_by`, `recorded_at` |
| `DecisionPointOpened` | `decision.opened.v1` | `origin_surface`, `decision_point_id`, `mission_id`, `run_id`, `mission_slug`, `mission_type`, `phase`, `origin_flow`, `question`, `options`, `input_key`, `step_id`, `actor_id`, `actor_type`, `state_entered_at`, `recorded_at` |
| `DecisionPointResolved` (resolved, deferred, canceled) | `decision.resolved.v1` | `origin_surface`, `decision_point_id`, `mission_id`, `run_id`, `mission_slug`, `mission_type`, `terminal_outcome`, `final_answer`, `other_answer`, `rationale`, `summary`, `actual_participants`, `resolved_by`, `closed_locally_while_widened`, `closure_message`, `state_entered_at`, `recorded_at` |


## Z Wording moved from spec and data-model (spec phase, round 2)

Original fuller wording of the passages shortened to keep the spec and data model within their size budget; no requirement was dropped.

- spec section 8, per-wheel machinery (original): "**Per-wheel rule.** Every release step, script and test that globs or asserts one wheel becomes per-wheel over the expected set (the `ls dist/*.whl` step, `check_exact_install.py::locate_wheel`, `published_build_artifacts` in `tests/_support/shared_build_artifacts.py`, the `test_check_exact_install.py` fixtures). The binary sits at one known in-package path under an existing known prefix, or the prefix tuple of `test_wheel_contains_only_known_packages` is amended deliberately."
- spec section 11 (original): "Elaboration: `plan-carryover.md` section K. Critical path: events PR released, floor bump, CLI producer (#6193 blocked by this mission). Main risks: native build weight and spawn cost (NFR-002); unsigned binaries (OD-SIGN); INTERIM choices turn breaking once emitted; seam coverage drift (AC-004); clock ordering consequences (data-model section 4); a missing platform leg is permanent once published (G-12); #6164 conflicts on `status/adapters.py`."
- spec section 7 trailer (original): "**Writers at cutover (C-004):** Python writes the ledger rows, Java the publish-log rows; at #6193/#6195 Java writes both and the publish log becomes the ledger or is retired. **Merge order with #6164:** whichever lands second rebases; the only shared file is `status/adapters.py`. Tooling constraints the plan must respect (venv layout, per-checkout event streams, non-byte-preserving ignore edits, uncommitted CLI-written lines): `plan-carryover.md` section T."
- FR-002 tail (original): "the ADR's confirmation pin and the reset helpers (`reset_handlers`) move with the gate and re-register the client. Neither producer nor client reads the drain posture; `hosted.drain` is unchanged."
- data-model example 5 (original): "5. **Offsets and ids.** Clockless A `2026-10-13T12:00:00+02:00` (= 10:00:00Z), B `2026-10-13T10:00:00.5Z`: A before B (text order says B). At one instant, C `01jaaaa0000000000000000abc` and D `01JAAAA0000000000000000ABD` normalize to `…ABC` < `…ABD`: C before D. A legacy U `0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b` (or its 32-hex spelling) against ULID `01JAAAA0000000000000000ABC`: U first by code point (deterministic, not meaningful)."
- data-model section 10 (original): "Per wheel: `platform_tag`; `os_arch` (minimum linux x86_64, macOS arm64, Windows x86_64; others OD-MATRIX); `binary_path` (one executable at one known in-package path); `binary_checksum` (recorded at build, equal to the recomputed wheel member, AC-032); `toolchain_pin`; `smoke_result` (G-3); `signed` (OD-SIGN). `fallback`: the pure `py3-none-any` wheel (OD-FALLBACK) and the sdist hold no binary and the producer reports `binary-missing`. Invariants: one binary per platform wheel; `packs/internal/` in no wheel; the expected set is pinned (G-12)."
- data-model section 6 mapping (original): "Mapping: an unreadable, truncated, non-JSON or schema-violating reply is `malformed-reply`; a non-zero exit, signal or empty reply `crash`; no executable `binary-missing`; the deadline `timeout`; a call skipped because the handler is in flight `busy`."
- data-model PublishLogFile (original): "Gitignored; a new local-tier file class (spec section 9). Unit (per-Mission or per-run file) is a plan decision; it carries an age (newest row `time` or file mtime) and a run or Mission association, so N-runs/X-days is defined. Complete lines ending LF; single writer: the producer. A final line without LF is preserved, flagged in the fault log (via the reply `notice`), never joined to the next row. Location: an already-gitignored path (candidate `.kittify/events/`) or the canonical ignore mechanism (AC-023)."
- Per-wheel machinery named in spec section 8: the `ls dist/*.whl` step, `check_exact_install.py::locate_wheel`, `published_build_artifacts` in `tests/_support/shared_build_artifacts.py`, the `test_check_exact_install.py` fixtures.
- spec section 3 operator decisions (original list form): "1. Taken at the maintainer's request (header).
2. Strangler shape: Java producer, thin Python client only, as epic #6117 (FR-001, FR-026, FR-027, FR-039).
3. CloudEvents 1.0 structured JSON now (ADR 0001 q3a, option A); 3b not ruled: legacy rows stay readable, upcast on read, never rewritten (FR-006, FR-036, FR-037).
4. Ordering option C: `lamportclock` + `nodeid`, ordered Lamport, `time`, `id`, `(time, id)` fallback; ULID-only new ids; prefix `ai.spec-kitty` (FR-007 to FR-013).
5. Platform wheels bundling the native binary, the ruff/uv model (FR-041 to FR-044).
6. Fault mode: degrade with a recorded warning, never silent (FR-022 to FR-025).
7. One schema set, one writer per event type; row schemas ARE the `data` schemas (FR-014, FR-015, FR-034, section 7).
8. Public hygiene (NFR-006)."
- spec header assumptions (original): "**Assumptions in force** (open ones: section 10): (1) a Java producer behind a thin Python client (operator decision 2); (2) Python writers stay authoritative for the committed ledgers, the producer appends CloudEvents rows to a gitignored publish log (D-SINK, OD-SINK); (3) either landing order with #6164 works (D-6164); (4) INTERIM marks an adopted-but-unconfirmed ADR 0001 choice."
- spec section 1 purpose tail (original): "so Mission lifecycle and status events reach no integration on a default install. This mission gives them a local, on-by-default publication path separate from `hosted.drain`, with a versioned per-row-kind contract in `spec-kitty-events` (ADR 2026-10-13-2, D-1, D-2)."
- data-model section 8 tail (original): "Fixture markers in tests are reviewed in the plan. The producer lint gains a persisted-line mode; its baseline is not widened."

- Spec section 8 gate table, original machinery column wording (each gate extends the machinery named):

| G-1 | Per platform wheel: binary present, executable, platform tag, no extra binary (`release.yml` "Verify wheel contents"; `test_packaging_safety.py`). |
| G-2 | Exact installability from each built wheel (`release.yml`; `check_exact_install.py`). |
| G-3 | Install the built wheel by path into a fresh venv on each OS and run the smoke test (one valid row appended): in the nightly, on PRs touching the producer's Java tree, packaging config or client (a named `ci-router.yml` group and `gate_selection.py` entry), and in `release.yml` before publish. New jobs, not `ci-windows.yml`. |
| G-4 | `twine check` on every wheel (`release.yml`). |
| G-5 | The release SBOM lists the Java dependencies and the toolchain embedded in each binary (`cyclonedx-py environment` sees only Python). |
| G-6 | `uv lock --check`, the only dependency gate in CI today (`ci-router.yml`). |
| G-7 | Sdist and editable builds succeed without the binary; install runs and reports `binary-missing` (FR-043, C-007; `test_package_bundling.py`). |
| G-8 | Provenance and pinned toolchain; checksum continuity from build to each published wheel (FR-042; `release.yml` "Record artifact checksums"). |
| G-9 | Red-CI-no-release (Standing Order 9): `build-release` becomes a per-OS build stage feeding one assembling job; `publish-pypi` needs the assembled, set-checked artifact, so a failed or cancelled leg blocks publish. |
| G-10 | `packs/internal/` still excluded (FR-044; `test_packaging_safety.py`). |
| G-11 | Producer gates (client-no-logic, persisted-line schema, lane list, retention-no-tracked-write, seam census, single writer, no committed schema source), each under the floor rule, in `tests/architectural/` (targeted files). |
| G-12 | The dist set equals the pinned expected list (platform tags, one sdist, the pure wheel iff OD-FALLBACK a): a missing platform cannot degrade silently to the sdist (new assembling step). |
| G-13 | Java dependency checksum verification, reusing the pattern of `contracts/gradle/verification-metadata.xml` (new build step). |

- AC-005 (original): "One test per class (`binary-missing`, `crash`, `timeout`, `malformed-reply`, `clock-overflow`, `busy`; `rejected` for a 32-hex id, a hyphenated UUID id, a bad time, an unresolvable root, a schema-invalid row and, under OD-MISSIONID (a) only, a Mission without `mission_id` (under (b): a published row with project source, no fault); `disabled` only under OD-OPTOUT b, else an enum-equality test): exit code and `--json` body byte-identical to the no-fault run; ledger row present; in human and `--json` mode one stderr warning (type, id, class, FR-023 sentence); one fault-log row (none for an unresolvable root: stderr only); fault log unwritable: stderr still appears, command succeeds."
- FR-023 head (original): "Each fault records a warning naming event type, event id (if known), reason class (`binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`, plus `disabled` only under OD-OPTOUT b) and a sentence: "event was NOT produced", except `crash` and `timeout` (the append may have finished): "event may not have been produced"."
- Out of scope (original): "**Out of scope** (original wording: `plan-carryover.md` section Q). CloudEvents in committed ledgers, retiring the Python writers, lock interop, committed-log appends (#6193, #6195, #6196); any change to `status/reducer.py`, the Java read model or `status/event_log_merge.py`; the envelope schema, validator, upcaster, STRICT types, bindings, events-charter amendment (events#92); glossary rows (#1200); backfill; retiring the `3.0.0` envelope (ADR 3b); renaming the `saas_fanout` slots; any hosted behaviour."
- E2 (original): "Full table: `plan-carryover.md` section E (E1 to E15, cited by id). Load-bearing: **E2** the seam has three slots, all `fire_*` return unless the drain posture is enabled, handlers run bounded (`_DEFAULT_SAAS_FANOUT_TIMEOUT_S`), a busy handler is skipped, failures are logged not raised; `RuntimeMomentProducer._publish` has its own drain read."

- Data-model section 6 ProduceRequest, original table form:

**ProduceRequest** (client to producer; assembled by the seam, passed through uninterpreted):

| Field | Type | Constraint |
|---|---|---|
| `row` | opaque mapping | the persisted authoritative row, post-sanitization (FR-004); a list under OD-SPAWN (b) |
| `slot` | enum | `status` \| `lifecycle`: the seam slot that fired (the resolved-binding slot is not registered, FR-003) |
| `seam_args` | opaque mapping | the existing keyword arguments (`wp_id`, `actor`, `mission_slug`, `mission_id`, `metadata`, ...), as given |
| `start_path` | path | the call site's path (E14); the producer resolves the canonical root from it, never from the process working directory |
| `deadline_ms` | integer | the client's timeout |

- FR-019 gate (original): "A gate asserts `git check-ignore` for the publish log and the fault log, and for the clock state either `git check-ignore` (a working-tree path) or, when it sits under the git common directory (OD-CLOCK a), that it lies outside the working tree and is not reachable by `git add`."
- FR-012 tail (original): "Stamp and append are one exclusive operation; a kill leaves no advance and no line, or a gap with or without a line, never a reused value. After state loss the first stamp exceeds the highest clock readable in the publish log; the state is never pruned while its log exists. The clock never exceeds 2^31-1; on overflow the producer fails that event (`clock-overflow`) and never wraps. Merging other nodes' clocks is #6193's."
- FR-029 (original): "An item is eligible only when outside both limits (not among the newest `keep_runs` AND older than `max_age_days`), its Mission is not open (open: any work package in a lane other than done or canceled), it holds no lock and no step is unfinished (a run journal without a `MissionRunCompleted` row, or a lock file present)."
- FR-036 (original): "The Python readers are untouched. `3.0.0` `Event` rows and diary rows are read by the events upcaster when it exists; this mission never rewrites a legacy row."
- FR-010 (original): "Context attribute names match `^[a-z0-9]{1,20}$`; values use only CloudEvents attribute types; absent values are omitted, never `null`. A conformance test enforces it."
- FR-041 (original): "Release builds produce platform-tagged wheels each bundling exactly one native producer binary, for at least linux x86_64, macOS arm64 and Windows x86_64 (macOS x86_64 and linux aarch64 are plan decisions)."
- dm time (original): "`time`**: the row's own instant (`at` on lane, annotation, decision and retrospective rows; `timestamp` on lifecycle and run-journal rows), RFC 3339, written in UTC, never synthesized; unparseable or absent is `rejected`."
- section 9 (original): "1. Charter "Python 3.11+ for all CLI and library code" versus the Java producer: Java build, test, lint, ArchUnit and coverage floors have no charter row; Python only for the shim.
2. Charter forbids vendoring events source: Java binding generation crosses that line (OD-SCHEMA-DELIVERY).
3. ADR 2026-10-13-2 is departed from in three places: D-1 (flat rows, superseded by CloudEvents for the new rows), D-2 (the publish and fault logs are an added read path beside the ledger) and D-3 (its closed local-tier file list gains the publish log, fault log and clock state). The amendment is a deliverable (FR-045).
4. to 7. Events-repo charter wording, Python-only coverage measurements, a stale CLAUDE.md `release.yml` line, no charter row for platform wheels: flagged, not amended (carryover D)."
- D-SINK (original): "The producer is an adapter on the post-persistence seam; Python writers stay authoritative for committed ledgers; the producer appends to a gitignored publish log."
- D-6164 (original): "Requirements say "the seam fires the producer irrespective of drain", so both landing orders satisfy them."
- section 12 (original): "This mission's own events are written by the Python writers; it must not depend on the producer to pass its own status gates. Mid-flight Missions: FR-038, AC-017."
- lifecycle rule (original): "A lifecycle row is the envelope of `status/lifecycle_events.py::_build_envelope` (`event_id`, `event_type`, `aggregate_id`, `aggregate_type`, `schema_version`, `timestamp`, `payload`, `project_uuid`, `project_slug`). For those types `data` is the row's `payload` verbatim; `event_id` becomes `id`, `timestamp` becomes `time`, `event_type` selects the type, `aggregate_id` becomes `subject` for work package types (else dropped), `aggregate_type` and `schema_version` are dropped, `project_uuid` forms the project `source`, and `project_slug` is dropped. Members of `payload` such as `actor` stay in `data`."
- FR-004 (original): "The PRODUCER resolves the canonical repository root from it (`core.paths.resolve_canonical_root`), so a lane worktree, a coordination worktree and the primary checkout reach one publish log. The client repeats that resolution only to place the fault log (FR-026). An unresolvable start is fault class `rejected` (`root-unresolvable`), never a silent return; with no root there is no fault-log location, so the warning is stderr only."
- C-004 (original): "**C-003** Drain gate unchanged in meaning. **C-004** One writer per type and per file: one writer of the authoritative ledger row and one of the publish-log row; at #6193/#6195 Java writes both and the publish log becomes the ledger or is retired (decided there); no type has two writers of one file."
- OD-MISSIONID b (original): "(b) ADR 0001 fallback: project source, subject = the aggregate, no `missionid`, no fault (data-model section 1: `missionid` omitted)."
- FR-018 (original): "The `doing` alias is resolved to `in_progress`; `reason_source` is never dropped."
- G-11 (original): "Producer gates (client-no-logic, persisted-line schema, lane list, retention-no-tracked-write, seam census, single writer, no committed schema source), each under the floor rule, in `tests/architectural/` (targeted files)."
- AC intro (original): "Tests are committed RED first (ATDD). "
- FR-024 (original): "A degraded repeated state stays visible: `doctor` reports the fault count (fault-log rows in the retention window; a successful event does not reset it; only retention reduces it) and the last fault by class."
- s3 heading (original): "**Decisions from repo evidence (flagged for confirmation).**"
- s9 (original): "Detail: `plan-carryover.md` section D.
"
- s11 (original): "Elaboration: `plan-carryover.md` section K. "
- s4 (original): "Each line is testable; the rationale of each FR is in `plan-carryover.md` section R."
- dm intro (original): "Entities and invariants for [`spec.md`](spec.md). Wire formats are a plan decision. **INTERIM** marks adopted ADR 0001 choices awaiting confirmation. Envelope schema, registry and upcaster belong to spec-kitty-events#92; a difference is a defect to reconcile, never a parallel set."
- dm invariants (original): "(checked by script over all permutations of every fixture below, and over all triples for transitivity)"
- dm inj (original): "the key is a lexicographic product of total orders and is injective after dedupe"
- dm retention (original): "Invalid or non-numeric values are a named command error (fail closed, AC-031). Eligible: FR-029. Local tier only (publish log, fault log, run journals, `.kittify/events/`); committed files are never candidates."
- dm lamport (original): "Counter only; merging other nodes' clocks is #6193's."
- dm 8 (original): "Per-marker reasons: `plan-carryover.md` section M."
- dm 5 (original): "`plan-carryover.md` section X (real bytes come from the serializer and golden tests)."
- s13 (original): "Definitions: `plan-carryover.md` section L; then `python scripts/generate_contextive_glossaries.py generate` and `check`."
- s8 (original): "(machinery list: `plan-carryover.md` section G)"
