# Detail handed to the plan

Mission `event-producer-reroute-01M4VDY9`. This file holds explanatory and implementation-level detail moved out of [`spec.md`](spec.md) and [`data-model.md`](data-model.md) so those stay within the review budget. Nothing here changes a requirement: every FR, NFR, C, AC and OD id, with its testable content and falsifier, stays in the spec. Each section below is referenced from the spec or data model by letter. The plan reads this file as input.

Sections: R rationale per FR; E evidence detail; T tooling defects; G gate rationale; K risk elaboration; M exemption marker reasons; X example lines; L glossary definitions; D governance drift detail; W total-order and worked-example narration; Q open-decision and scope prose; S further moved wording.

## R Rationale per FR

| FR | Rationale |
|---|---|
| FR-001 | ADR 2026-10-13-2 D-2: local adapters fire by default. |
| FR-002 | Publication must not depend on the frozen hosted relay. |
| FR-003 | A producer that misses rows is a silent-success hole. |
| FR-004 | E3: the existing arguments omit `event_id` (except as `causation_id`), `reason_source` and `review_result`; without the row the producer cannot honour FR-005 or FR-016. This is a seam-argument addition, not client logic (FR-026). |
| FR-005 | E10 (the sanitizer): a second path must not widen disclosure. |
| FR-006 | Dec-3, Dec-4. |
| FR-007 | Join key between publish log and ledger; Dec-4 Q2. |
| FR-008 | Deterministic, nothing invented (ADR design section 9). |
| FR-009 | Design sections 3 and 6. |
| FR-010 | CloudEvents naming rule; design section 2. |
| FR-011 | Dec-4 Q1. |
| FR-012 | Lamport soundness; CloudEvents Integer range. |
| FR-013 | Precondition of Dec-4 Q1. |
| FR-014 | Dec-7: one schema set. |
| FR-015 | Persisted-line validation, not payload-only. |
| FR-016 | Lifecycle dedupe, retries and re-entrant fan-out must not double-publish. INTERIM key. |
| FR-017 | Bounded to "mission lifecycle and status events"; the run journal is in scope only because #1248 is named. If a later decision defers it, #1248 becomes an explicit residual and this contradicts "clear #1248". |
| FR-018 | Closes the `reason_source` gap (E7); design section 3. |
| FR-019 | Windows byte rules (#4182); local tier of ADR D-3. |
| FR-020 | Dec-7; keeps lock interop out of scope. |
| FR-021 | Silent-success rule. |
| FR-022 | Dec-6; the #2535 machinery-fault ruling. |
| FR-023 | Never silent (D-WARN). |
| FR-024 | A once-per-event line scrolls away; a count does not. |
| FR-025 | A hang must not stall a command. |
| FR-026 | Dec-2. |
| FR-027 | Standing Order 5. |
| FR-028 | ADR D-3. |
| FR-029 | ADR D-3 eligibility. |
| FR-030 | ADR D-3. |
| FR-031 | C4; ADR D-3 committed tier. |
| FR-032 | ADR D-1 follow-up. |
| FR-033 | Scope honesty. |
| FR-034 | "3x lane-list duplication"; charter single canonical authority. |
| FR-035 | E5, ADR D-1; Dec-7. See OD-JOURNAL. |
| FR-036 | Dec-3. |
| FR-037 | Decouples release order. |
| FR-038 | Archive-freeze ADR 2026-10-11-1; reflexivity. |
| FR-039 | E12; #6193 takes over this producer. |
| FR-040 | Charter, Development Workflow Requirements. |
| FR-041 | Dec-5. |
| FR-042 | Reproducibility. |
| FR-043 | Dec-6. |
| FR-044 | Charter pack tiers; `test_packaging_safety.py`. |
| FR-045 | Section 9. |
| FR-046 | Terminology canon. |

## E Evidence detail (spec section 2, original table)

| # | Fact | Where |
|---|---|---|
| E1 | `zeitgeist_bridge.py` is the only builder of the events `Event` (`_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`). | `status/zeitgeist_bridge.py` |
| E2 | The seam has three slots (`saas_fanout`, lifecycle, resolved-binding). All three `fire_*` functions return unless `hosted_posture.drain_posture(...).enabled`. Default wiring is `ensure_zeitgeist_moment_handlers()`. Handlers run in a bounded daemon thread (`_DEFAULT_SAAS_FANOUT_TIMEOUT_S`, 10 s); failures are logged, never raised. | `status/adapters.py` |
| E3 | The lane fan-out (`_saas_fan_out`) passes slug, ids, lanes, actor, `WPStatusChangeMetadata` and `repo_root`. The ledger `event_id` rides only as `metadata.causation_id`; `reason_source` and `review_result` are not passed. Coordination writes reach the slot via `coordination/outbound.py`. | `status/emit.py`, `status/wp_status_metadata.py` |
| E4 | `emit_inner_state_changed` has no fan-out of its own; only a resolved-binding annotation fans out. Lifecycle: `append_lifecycle_event` persists then fans out, `persist_lifecycle_event_local` only persists (callers such as `core/mission_creation_commit.py` fan out separately). Run-journal moments go through `RuntimeMomentProducer`, which publishes six moments and deliberately not `SignificanceEvaluated`; `DecisionAuthorityDenied` has no emitter. | `status/emit.py`, `status/lifecycle_events.py`, `events/runtime_moments.py` |
| E5 | The run-journal row `{event_type, timestamp, payload}` has no `event_id` or version. | `runtime/next/_internal_runtime/engine.py::_append_event` |
| E6 | `Lane` has 11 members (incl. non-persisted `uninitialized`); `CANONICAL_LANES` 9; `spec_kitty_events.Lane` 10 (incl. `genesis`). Lookalikes: `_VALID_LANES`, `_LANE_ORDER` (`migration/rebuild_state.py`), `_LEGACY_NOT_DONE_LANES` (`acceptance/__init__.py`), `_PRE_APPROVED_LANE_VALUES` (`consolidation/done_bookkeeping.py`). | `status/models.py`, `status_lanes.py` |
| E7 | `StatusEvent` carries `reason_source` (`operator` via `OPERATOR_REASON_SOURCE`, or `synthetic`); the reducer reads it; the events `diary.StatusEvent` drops it. | `status/models.py` |
| E8 | `canonical-producer-exempt` markers in `src/`: #1198 x7 (`decisions/emit.py` 2, `events/decision_log.py` 1, `migration/mission_state.py` 1, `status/lifecycle_events.py` 3); #1248 x1 (`engine.py`); #1200 x8 (`glossary/events.py`). `scripts/lint_canonical_producers.py` (baseline `scripts/canonical_producer_lint_baseline.txt`) inspects source shapes, not persisted lines. #1198 and #1248 are closed upstream. | files named |
| E9 | The reducer sorts `(at, event_id)`; the merge driver keys on string `event_id` and rejects rows without it. | `status/reducer.py`, `status/event_log_merge.py` |
| E10 | Node identity today: first 12 hex chars of SHA-256(hostname:username). Persisted rows pass `sanitize_event_for_log`. | `status/migrate_lifecycle_envelope.py`, `events/sanitizer.py` |
| E11 | One pure wheel via `python -m build` (hatchling) in `.github/workflows/release.yml`, then wheel-content check, exact-installability, CycloneDX SBOM, `twine check`, `publish-pypi`; `tests/cross_cutting/packaging/` guards it; `ci-windows.yml` exists. | files named |
| E12 | The only Gradle builds on `main` are `acceptance-jvm/` and `contracts/`; the #5529 stack is open PRs; #6192 extracts `domain-model` and `event-log` from it. | repository root |
| E13 | `.kittify/events/`, `.kittify/runtime/`, `.kittify/derived/` are gitignored; no `ledger.retention` exists in code. The CLI floor is `spec-kitty-events>=10.4.4,<11`; `tests/contract/spec_kitty_events_consumer/` pins the consumed surface. | `.gitignore`, `pyproject.toml` |

---



## T Tooling defects (spec section 7)

Tooling defects (accounted for by number, no detail): SK-350 and SK-311 (a `lib64` symlink in the tool venv breaks startup, so the binary lookup and the merge driver must not assume a plain `lib`); SK-331 and SK-319 (primary and coordination partitions hold different event streams, so the publish log is per resolved checkout and never assumed to be unique per Mission); SK-339 (`GitignoreManager.ensure_entries` is not byte-preserving, so prefer paths already ignored, FR-019); SK-328 and SK-320 (CLI-written event lines left uncommitted: the publish log is never a committed file, so these cannot apply to it).



## G Gate rationale (spec section 8)

| Gate | Extends | Why |
|---|---|---|
| G-1 wheel-content, per platform wheel: binary present, executable bit, platform tag, no extra binaries | `release.yml` "Verify wheel contents"; `test_packaging_safety.py` | Wrong or missing binary ships a dead producer. |
| G-2 exact installability from each built wheel | `release.yml` | Existing exact-installability step, per wheel. |
| G-3 native smoke test on each OS (producer appends one valid row) | `ci-windows.yml` + linux and macOS lanes | A built binary is not a working binary. |
| G-4 `twine check` on every wheel | `release.yml` | Metadata validity per wheel. |
| G-5 SBOM covers the binary and its Java/native-image dependency licenses | CycloneDX step | Today's SBOM sees only Python. |
| G-6 pip-audit and `uv.lock` freshness | existing dependency gates | Unchanged for Python; Java dependency audit is a plan item. |
| G-7 sdist behaviour: sdist builds without the binary, or fails loudly; install runs and reports `binary-missing` | `test_package_bundling.py` | FR-043. |
| G-8 binary reproducibility and provenance: pinned toolchain, recorded checksum | `release.yml` | FR-042. |
| G-9 red-CI-no-release for every RC and the GA | Standing Order 9 | Unchanged; the new matrix must be inside the aggregate gate, not beside it. |
| G-10 `packs/internal/` still excluded | `test_packaging_safety.py` | FR-044. |
| G-11 producer gates (client-no-logic, persisted-line schema, lane list, retention-no-tracked-write, seam census) | `tests/architectural/` named files, targeted | Standing Order 5; each non-vacuous. |


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

---



## M Exemption marker reasons (data-model section 8)

### 8 Exemption ledger (entity `Exemption`)

Counts verified by grep of `src/`. Disposition: `clear` = the marker is gone and its writer's persisted line is validated by the published per-kind schema (legacy-row form); `residual` = stays, with a reason and an issue reference to be filed.

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
2. **Charter forbids vendoring events source and treats `spec-kitty-events` as an external package:** Java binding generation sits across that boundary.
3. **ADR 2026-10-13-2 D-1** chose "versioned ledger-row schema, flat rows", said "the canonical 3.0.0 Event envelope stays the wire contract for anything that leaves the machine" and "the CLI then writes from the package's models" (Python), and rejected canonical envelopes in the ledger. The operator's CloudEvents ruling supersedes this for the new rows: an ADR amendment is a deliverable (FR-045).
4. **The events-repo charter** says "Python library" and "Pydantic" (#6117 decision 11): flagged, not amended.
5. **Charter "90%+ coverage for new code" and diff-cover >= 90%** are Python measurements; Java coverage tooling is unstated.
6. **CLAUDE.md versus the checkout:** the "PyPI Release" section says `release.yml` is not present; it is present at `.github/workflows/release.yml`. The charter wins; the line in CLAUDE.md is stale.
7. **Charter "PyPI distribution via automated release workflow"** has no row for platform-tagged wheels or native binaries.
8. No other CLAUDE.md-versus-charter disagreement found.

---



## W Total-order and worked-example narration (data-model section 4)

**Why a naive comparator fails.** Naive: "if both rows have a clock, compare `(clock, time, id)`; otherwise compare `(time, id)`." It is not transitive.

### Worked examples

Times are UTC unless shown. Row ids are abbreviated.

1. **Two clockless rows.** A `time 10:00, id 01B`; B `time 10:00, id 01A`. Key tie on `h`,`c`,`t`; `i` decides: B before A.
2. **Two clocked rows.** A `clock 5, 09:00`; B `clock 4, 11:00`. Clock decides: B before A, although B is later in time. Equal clock 5 and 09:00: `id` decides.
3. **Mixed.** A clockless `23:00`; B clocked `clock 1, 08:00`. A before B (`h` 0 < 1) although A is later in time.
4. **The naive-comparator counter-example.** A `clock 5, 10:00`; B clockless `10:30`; C `clock 3, 11:00`. Naive: A vs C both clocked, C(3) < A(5); A vs B uses time, A(10:00) < B(10:30); B vs C uses time, B(10:30) < C(11:00). So C < A < B < C: a cycle, and the sort result depends on input order. Total order: B (h 0) first; then C (clock 3); then A (clock 5): **B, C, A** for every input permutation.
5. **Mixed offsets and id case.** A `time 2026-10-13T12:00:00+02:00`, B `2026-10-13T10:00:00.5Z`, both clockless. Instants: A = 10:00:00Z, B = 10:00:00.5Z: A before B (text order would say the opposite: `"10..."` < `"12..."` places B first). With identical instants, ids `01abc` (a lower-cased ULID) and `01ABD` compare after upper-casing: `01ABC` < `01ABD`.
6. **Error row.** Rows A (`10:00`), B (`time: "yesterday"`), C (`11:00`), all clockless. Result: ordered `[A, C]`, errors `[B at line 2: unparseable time]`; 3 rows read = 2 ordered + 1 error.



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

### Section 12 original wording

### 12 Reflexivity and mid-flight

This mission's own events (its lane transitions and lifecycle rows) are written by the Python writers today; the producer is not required to publish them until it ships. A Mission open when the producer ships keeps working: its ledger is unaffected, its publish log starts at the first event after upgrade, and no backfill is done (non-goal). The mission itself must not depend on the producer to pass its own status gates.


## S Further moved wording

### Spec section 3 total-order paragraph, original

**The total order (requirement; formal in data-model section 4).** For rows `r` compare the lexicographic key `(has_clock, lamportclock, time, id)`: `has_clock = 0` and `lamportclock = 0` for a row without a clock. All clockless rows therefore sort before all clocked rows; clockless rows by `(time, id)`; clocked rows by `(lamportclock, time, id)`. It is lexicographic, so transitive and independent of input order. `time` compares as a UTC instant after RFC 3339 parsing (mixed offsets and fractional digits). `id` compares as a string after normalization (ULID upper-case; UUID lower-case hyphenated). A row whose `time` cannot be parsed is an error row: surfaced by name with its line position, never dropped, and excluded from the ordered result. Known consequence: a clockless row appended after clocked rows sorts first. Guard: only pre-cutover Python writers and old CLIs write clockless rows; the project schema bump that stops old CLIs appending is #6195's. The requirement is sink-agnostic: it binds every reader that combines upcast legacy rows with CloudEvents rows (see OD-SINK).


### Data-model section 7, original

### 7 Local-tier entities

**PublishLogFile.** Gitignored. Unit (per-Mission file or per-run file) is a plan decision; it must carry an age (newest row `time` or file mtime, plan decides) and a run or Mission association so N-runs/X-days is defined. Rows: complete lines ending LF. Single writer: the producer. Torn-line rule: a final line without LF is preserved, flagged in the fault log, never joined to the next row. Location: under a path already gitignored (candidate: beneath `.kittify/events/`, plan confirms) or added through the canonical ignore mechanism; AC-023 checks.

**LamportState.** Gitignored state file owned by the producer: per-`nodeid` counter value, integer 0..2^31-1. Invariants: strictly increasing per node; stamp and append are one exclusive operation across processes; on reaching the bound the next stamp fails (`clock-overflow`), never wraps. Within a node it is a counter only; merging with observed clocks of other nodes is #6193's.

**FaultLogRow.** Append-only, gitignored, retention applies. Fields: ProducerFault fields plus `repo_root` marker (relative) and `count_since_clean`. Used by `doctor` (FR-024): fault count, last class, last time.

**RetentionPolicy.** Keys in `.kittify/config.yaml`: `ledger.retention.keep_runs` (positive integer, proposed default 20, operator to confirm), `ledger.retention.max_age_days` (positive integer, proposed default 30, operator to confirm). Invalid or non-numeric values are a named error from the command (fail closed), never a silent default. Eligible item: outside both limits (not among the newest `keep_runs`, and older than `max_age_days`), Mission not open, holds no lock, no unfinished step. Scope: local tier only (publish log, fault log, clock state is never pruned while the log exists, run journals and `.kittify/events/` per the ADR). Committed tier is never in the candidate set.


### Data-model section 6, original

### 6 Producer protocol entities (fields; wire format is a plan decision)

**ProduceRequest** (client to producer; assembled by the seam, passed through by the client without interpretation):

| Field | Type | Constraint |
|---|---|---|
| `row` | opaque mapping | the persisted authoritative row, post-sanitization (FR-004) |
| `slot` | enum | `status` \| `lifecycle` \| `resolved_binding`: the seam slot that fired (as given) |
| `seam_args` | opaque mapping | the existing keyword arguments (`wp_id`, `actor`, `mission_slug`, `mission_id`, `metadata`, ...), as given |
| `repo_root` | path | the emitting checkout, as the seam carries it (never the process working directory); relative to nothing, resolved by the producer |
| `mission_dir` | path, optional | as the seam carries it, for `meta.json` lookup |
| `deadline_ms` | integer | the client's timeout, informational |

**ProduceReply** (producer to client; exactly one):

| Field | Type | Constraint |
|---|---|---|
| `outcome` | enum | `produced` \| `skipped_duplicate` \| `refused` |
| `event_id` | string | normalized id, present for all three |
| `type` | string | when `produced` or `skipped_duplicate` |
| `reason_class` | enum | when `refused`: `rejected` \| `clock-overflow` (producer-detected classes) |
| `detail` | string | one line, repo-relative paths only (NFR-006) |

**ProducerFault** (client-side or producer-side; recorded, never raised into the command):

| Field | Type | Constraint |
|---|---|---|
| `class` | enum | `binary-missing` \| `crash` \| `timeout` \| `malformed-reply` \| `clock-overflow` \| `rejected` \| `disabled` |
| `event_type` | string | legacy or published type name |
| `event_id` | string or absent | if known |
| `message` | string | contains the sentence "event was NOT produced" |
| `at` | timestamp | client clock; this is not an event time and never enters an envelope |

Mapping: an unreadable, truncated, non-JSON or schema-violating reply is `malformed-reply`; a non-zero exit, signal or empty reply is `crash`; no executable found for the platform is `binary-missing`; the client's own deadline is `timeout`.


