# Mission Specification: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 (P1, 4.0.0 milestone) | **Branch**: `feat/event-producer-reroute-6185` | **Created**: 2026-10-13 | **Status**: Draft (spec phase)
**Companion**: [`data-model.md`](data-model.md) holds the entities, the type table, the total-order key and the trace table. Requirements and acceptance only; HOW belongs to the plan. Detail handed to the plan: [`plan-carryover.md`](plan-carryover.md).

**Assumptions in force** (open ones: section 10):
(1) The producer is Java, behind a thin Python client (operator decision 2). (2) The existing Python writers stay the authoritative writers of the committed ledgers; the producer appends CloudEvents rows to a gitignored local publish log (D-SINK, flagged OD-SINK). (3) The mission works in either landing order with #6164 (D-6164). (4) INTERIM marks an adopted-but-unconfirmed ADR 0001 choice.

---

## 1 Purpose and scope

**Purpose.** Today the only code that builds a `spec_kitty_events` `Event` is `status/zeitgeist_bridge.py`, on the hosted drain path (off by default, slated for removal, #6164), so Mission lifecycle and status events reach no integration on a default install. This mission gives them a local, on-by-default publication path separate from `hosted.drain`, with a versioned per-row-kind contract in `spec-kitty-events`, so an integration reads CloudEvents 1.0 rows without depending on CLI internals (ADR 2026-10-13-2, D-1 and D-2).

**In scope.**
1. A Java producer, shipped as a native binary in platform-tagged wheels, that turns each persisted ledger row into one CloudEvents 1.0 structured-JSON row appended to a gitignored local publish log.
2. A thin Python client registered on the existing post-persistence fan-out seam (`status/adapters.py`), firing irrespective of drain.
3. Per-row-kind `data` JSON Schemas, type-registry entries, `dataschema` URIs, producer conformance fixtures and total-order fixtures, delivered as one `spec-kitty-events` PR (separate repo, released first).
4. The defined total order for logs mixing clocked and clockless rows (section 3, data-model).
5. Fault handling that never fails a lifecycle command and is never silent.
6. Retention for the gitignored local logs; committed ledgers are never pruned.
7. Clearing the stale `canonical-producer-exempt` markers that cite #1198 and #1248; removing the lane-list duplication; carrying `reason_source`.
8. Platform wheels and the release gates they need.
9. An ADR amendment recording the CloudEvents ruling for the new rows (section 9).

**Out of scope / non-goals.**
- Writing CloudEvents into the committed `status.events.jsonl` or any committed ledger, and retiring the Python writers: #6193, #6195, #6196.
- Appends to committed logs, the lock-interop spike, the byte-identical-append proof: #6193.
- Any change to `status/reducer.py` or the Java read model (D-REDUCER; hand-offs in section 7), or to `status/event_log_merge.py`.
- The envelope schema and validator, the legacy upcaster and fixtures, the 26 STRICT types registry, bindings generation, major-bump artifacts, the events-charter amendment: spec-kitty-events#92.
- Glossary rows (`src/glossary/events.py`, #1200): a named residual (FR-033).
- Backfilling history into the publish log; retiring the `3.0.0` envelope (ADR question 3b, not ruled); renaming the `saas_fanout` slots.
- Any hosted behaviour. `hosted.drain` keeps its meaning and stays off by default.

---

## 2 Context and evidence (verified against this checkout)

Short facts; the full table with file locations and detail is `plan-carryover.md` section E (same E-ids).

- **E1** `zeitgeist_bridge.py` is the only builder of the events `Event`.
- **E2** The seam (`status/adapters.py`) has three slots (`saas_fanout`, lifecycle, resolved-binding); all three `fire_*` return unless `hosted_posture.drain_posture(...).enabled`. Handlers run in a bounded daemon thread (`_DEFAULT_SAAS_FANOUT_TIMEOUT_S`, 10 s); failures are logged, never raised.
- **E3** `_saas_fan_out` does not pass the ledger `event_id` (only as `metadata.causation_id`), `reason_source` or `review_result`.
- **E4** Seam gaps: `emit_inner_state_changed` has no fan-out of its own; `persist_lifecycle_event_local` only persists; `RuntimeMomentProducer` publishes six run moments, not `SignificanceEvaluated`; `DecisionAuthorityDenied` has no emitter.
- **E5** The run-journal row `{event_type, timestamp, payload}` has no `event_id` or version.
- **E6** `Lane` 11 members, `CANONICAL_LANES` 9, `spec_kitty_events.Lane` 10, plus lookalike literals (data-model section 9).
- **E7** `StatusEvent` carries `reason_source` (`operator` or `synthetic`); the events `diary.StatusEvent` drops it.
- **E8** `canonical-producer-exempt` markers in `src/`: #1198 x7, #1248 x1, #1200 x8. The lint inspects source shapes, not persisted lines. #1198 and #1248 are closed upstream.
- **E9** The reducer sorts `(at, event_id)`; the merge driver keys on string `event_id`.
- **E10** Node identity: first 12 hex chars of SHA-256(hostname:username). Persisted rows pass `sanitize_event_for_log`.
- **E11** One pure wheel today (`release.yml`, hatchling); packaging tests and `ci-windows.yml` exist.
- **E12** Only `acceptance-jvm/` and `contracts/` are Gradle builds on `main`; the #5529 stack is open; #6192 extracts `domain-model` and `event-log`.
- **E13** `.kittify/events/`, `.kittify/runtime/`, `.kittify/derived/` are gitignored; no `ledger.retention` exists; CLI floor `spec-kitty-events>=10.4.4,<11`.

---

## 3 Decisions encoded

**Operator decisions (binding; never re-opened here).**

| Dec | Decision | Encoded in |
|---|---|---|
| 1 | Taken at the maintainer's request. | Header |
| 2 | Strangler shape: Java producer; thin Python client only; same pattern as epic #6117. | FR-001, FR-026, FR-027, FR-039 |
| 3 | CloudEvents 1.0 structured JSON now (ADR 0001 q3a, option A, for this producer). 3b not ruled: legacy rows stay readable, upcast on read, never rewritten. | FR-006, FR-036, FR-037 |
| 4 | Ordering = option C: `lamportclock` + `nodeid`, ordered Lamport, then `time`, then `id`, with `(time, id)` fallback; ULID-only ids for new events; type prefix `ai.spec-kitty`. | FR-007 to FR-013 |
| 5 | Platform wheels bundling the native binary (ruff/uv model). | FR-041 to FR-044 |
| 6 | Fault mode: degrade with a recorded warning; never silent. | FR-022 to FR-025 |
| 7 | One schema set, one writer per event type; row schemas ARE the `data` schemas. | FR-014, FR-015, FR-034, section 7 |
| 8 | Public hygiene. | NFR-006 |

**INTERIM (adopted from ADR 0001 "further choices"; each needs confirmation on the events PR and none may be hardened silently):** snake_case `data` members; `actor` and `executionmode` as extension attributes; `.vN` type versioning; `(source, id)` dedupe key; type name `lane.transitioned` (other names in data-model are proposals under the same flag); the lifted-member set; the relative `source` form; the `dataschema` base URI (placeholder).

**Decisions made from repo evidence (flagged for confirmation).**

| Id | Decision | Evidence | Confirmation |
|---|---|---|---|
| D-SINK | The producer is an adapter on the post-persistence seam; Python writers stay authoritative for committed ledgers; the producer appends to a gitignored publish log. | E2; ADR 2026-10-13-2 D-2. | OD-SINK |
| D-REDUCER | This mission does not touch `status/reducer.py`. The total order is defined here and delivered as reference comparator plus fixtures in the events PR. Sort-key change is HANDED OFF (section 7). | E9; archive-freeze ADR 2026-10-11-1. | none needed |
| D-6164 | Requirements are written as "the seam fires the producer irrespective of drain" so both landing orders satisfy them. | E2 | AC-003 |

**The total order (requirement; formal in data-model section 4).** Rows compare by the lexicographic key `(has_clock, lamportclock, time, id)`, with `has_clock = 0` and `lamportclock = 0` for a clockless row: all clockless rows sort before all clocked rows, then by `(lamportclock, time, id)`. `time` compares as a UTC instant (RFC 3339, mixed offsets and fractions); `id` as a normalized string. A row with unparseable `time` is an error row: surfaced by name with its line position, never dropped, excluded from the ordered result. Known consequence and guard: data-model section 4. The requirement is sink-agnostic: it binds every reader that combines upcast legacy rows with CloudEvents rows (OD-SINK).

---

## 4 Functional requirements

Each line is the testable statement. The rationale of each FR is in `plan-carryover.md` section R (same id).

**A. Seam and coverage**
- **FR-001** The CLI registers a thin client on the existing fan-out seam that runs the Java producer once per in-scope persisted row, on a default install, with no setting required.
- **FR-002** The seam fires the producer irrespective of `hosted.drain`. While the three `fire_*` functions still gate on `drain_posture()`, the gate moves to the hosted (Zeitgeist) handlers; once #6164 has landed there is no gate and no Zeitgeist handler. The producer and its client never read the drain posture. The drain gate keeps its meaning and stays off by default.
- **FR-003** Every in-scope persisted row (data-model section 3) reaches the seam exactly once per persisted row. Gaps found in E4 are closed: a plain `InnerStateChanged` annotation gets a seam call; persist-only lifecycle callers get one at the point their row is persisted or at their existing separate fan-out call, whichever the census (plan task) shows is complete. A census gate fails when an in-scope writer persists a row with no seam call.
- **FR-004** At each seam call the persisted row (as written to the authoritative ledger, after sanitization) is handed to the client as one opaque mapping, additive to the existing keyword arguments, together with the mission directory or repo root the call already carries.
- **FR-005** Nothing the producer emits exceeds what the persisted row holds. It adds no member the authoritative row lacks, other than the envelope attributes of data-model section 1.

**B. Envelope and ids**
- **FR-006** Each published row is one CloudEvents 1.0 structured-JSON object per line, `datacontenttype` `application/json`, `type` prefixed `ai.spec-kitty`, `dataschema` naming the committed `data` schema of its type.
- **FR-007** `id` equals the authoritative row's `event_id`, normalized (ULID upper-case; UUID lower-case hyphenated). The producer never mints a different id for a ledger row. If the ledger id is not a ULID (the stripped-env fallback in `lifecycle_events._generate_event_id` is 32-hex UUID), the producer refuses that event with fault class `rejected`.
- **FR-008** `time` is the authoritative row's own timestamp (`at` or `timestamp`) as an RFC 3339 instant. The producer never synthesizes a time; a row without a parseable time is refused (`rejected`).
- **FR-009** `source` is `/spec-kitty/missions/<missionid>` for Mission-scoped rows and `/spec-kitty/projects/<project_uuid>` for project-scoped rows (INTERIM). `missionid` comes from the row, else from the Mission's `meta.json` read by the producer; if neither yields it, the event is refused (`rejected`). A slug is never presented as a mission id.
- **FR-010** Context attribute names match `^[a-z0-9]{1,20}$`; values use only CloudEvents attribute types; absent values are omitted, never `null`. A conformance test over every emitted attribute enforces it.

**C. Ordering and clock**
- **FR-011** The producer stamps `lamportclock` (Integer) and `nodeid` (String) on every row. `nodeid` reuses the repo-standard machine identity (E10).
- **FR-012** Within one node the clock is a strictly increasing counter in a gitignored local state file the producer owns. Concurrent CLI processes never stamp the same `(nodeid, lamportclock)` for different events: stamp and append are one exclusive operation. The clock never exceeds 2^31-1; on overflow the producer fails that event (`clock-overflow`) and never wraps (an Integer-versus-string type change, if any, is a plan/events#92 decision; the bound holds either way). Receive-rule merging across nodes belongs to #6193.
- **FR-013** The spec's total order (section 3) is delivered as a reference comparator specification and conformance fixtures in the events PR, including the non-transitive counter-example. No CLI reducer changes here.

**D. Schemas and types**
- **FR-014** The events PR carries one versioned `data` JSON Schema per emitted type, the type-registry entries, the `dataschema` URIs and the producer conformance fixtures. It adds nothing else; the envelope schema, validator and upcaster are #92's (fallback: FR-035).
- **FR-015** Every row the producer appends validates against its type's `data` schema and the envelope attribute rules before it is written; a row that fails is refused (`rejected`), not written.
- **FR-016** Re-firing the seam for a ledger row already published (same `(source, id)`) leaves one row in the publish log.
- **FR-017** The type table (data-model section 3) states per type the legacy source, writer, emission point, aggregate scope, `subject`, lifted members and scope status. In scope: lane transition, inner-state annotation, the `status/lifecycle_events.py` lifecycle rows, decision opened and resolved (including deferred and canceled outcomes) and the run-journal rows. Deferred: glossary rows (#1200); `DecisionPointWidened` (no emitter in `src/`). Run-journal rows are in scope because #1248 is named; deferring them makes #1248 an explicit residual (OD-JOURNAL).
- **FR-018** `lane.transitioned.v1` `data` carries `from_lane`, `to_lane`, `force`, `reason`, `reason_source` (optional; enumerated `operator`, `synthetic`), `review_ref`, `evidence`, `review_result`, `policy_metadata` and `actorbinding`, with `actor`, `executionmode`, `missionid`, `missionslug` and `subject` as context attributes. The `doing` alias is resolved to `in_progress` in `data`; `reason_source` is never dropped.

**E. The publish log**
- **FR-019** The publish log is gitignored, local tier, one UTF-8 JSON object per line, LF only, no BOM, no embedded raw newline; its location is a plan decision under the local tier and inside retention. A gate asserts `git check-ignore` for the log, the fault log and the clock state.
- **FR-020** The producer is the single writer of the publish log. No Python code opens it for write; no file shared with a Python writer is introduced.
- **FR-021** A crash mid-append never leaves a torn line that a later append joins with the next row: a torn final line (no trailing LF) is detected before the next append and left in place, flagged in the fault log. Readers surface a malformed line by name, never skip it silently.

**F. Faults**
- **FR-022** A missing binary, crash, timeout, malformed reply, clock overflow, refused request or explicit disablement never fails the lifecycle command, never changes its exit code, never changes any `--json` body and never changes an orchestrator-api envelope.
- **FR-023** Each fault records a warning naming event type, event id (if known), reason class (`binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | disabled`) and the sentence that the event was NOT produced. Carriers: a stderr line in human mode, plus an append-only gitignored fault log (retention applies). One warning per event, not per retry.
- **FR-024** A degraded repeated state stays visible: `doctor` reports the fault count and the last fault by class.
- **FR-025** The call is bounded: a finite, configurable timeout that never lets the producer hold the command beyond the fan-out handler bound `_DEFAULT_SAAS_FANOUT_TIMEOUT_S`. On timeout the client terminates the child it spawned (by its own handle, never by name pattern) and records `timeout`. The numeric default is a plan decision constrained by NFR-002.

**G. The thin client**
- **FR-026** The client may only: locate the platform binary; spawn it; pass the seam's existing arguments and the FR-004 row, serialized without interpretation, on stdin or argv; enforce the timeout; read one reply; map any failure to the fault record; register itself on the seam. It must not build or validate an envelope or attribute, mint ids, timestamps or clocks, choose type names or `dataschema` URIs, know the schema, order, dedupe, normalize ids or read the log.
- **FR-027** An architecture gate enforces FR-026 with a concrete floor (the client module is found and its call sites counted) and a self-mutation test that fails when a forbidden capability is injected into the client. The gate also fails if the Python bridge's envelope code (`_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`) is carried into the client.

**H. Retention**
- **FR-028** Policy keys `ledger.retention.keep_runs` and `ledger.retention.max_age_days` in `.kittify/config.yaml` (proposed defaults 20 and 30; operator to confirm, OD-RETENTION). "Run" is defined for each local-tier file the plan places (per-Mission file or per-run) so that N-runs/X-days is well-defined for the publish log.
- **FR-029** An item is eligible only when outside both limits, its Mission is not open, it holds no lock and no step is unfinished.
- **FR-030** Cleanup is an explicit, dry-run-first command in the `doctor` family; it never runs implicitly. A dry run lists what a real run would delete and deletes nothing.
- **FR-031** Cleanup never opens a git-tracked path for write. A non-vacuous gate proves it (concrete floor of tracked ledgers present; self-mutation adds a tracked path to the candidate set and must fail). Committed ledgers are never pruned.

**I. Exemptions, lanes, journal**
- **FR-032** After this mission no `canonical-producer-exempt` marker in `src/` cites #1198 or #1248, and a producer gate validates PERSISTED publish-log lines (not only payloads) against the row schemas. A marker on an authoritative Python writer is cleared when its persisted legacy row is validated by the published per-kind schema's legacy-row form; the plan decides per marker (data-model section 8). A marker that cannot be cleared without changing a legacy writer is a stated residual with an issue reference, never a silent keep.
- **FR-033** The eight `#1200` markers in `glossary/events.py` stay as a named residual, recorded in the exemption ledger; this mission neither widens nor clears them.
- **FR-034** Lane membership has one canonical definition, consumed by the `lane.transitioned` data schema. The persisted lane vocabulary excludes `uninitialized`. A census of every full lane-literal list (E6) is the first plan task, and a gate fails when a new full lane list appears.
- **FR-035** The run-journal writer gains a ULID `event_id` and a row version at write time (additive; rows without them stay legacy). If the events envelope schema from #92 has not landed when needed, the events PR carries the minimal envelope subset as a strict subset of ADR 0001 and its design (never a differently shaped parallel set), and #92 absorbs it.

**J. Compatibility**
- **FR-036** The Python readers are untouched. `3.0.0` `Event` rows and diary rows are read by the events upcaster when it exists; this mission never rewrites a legacy row.
- **FR-037** Until the events upcaster is released, CLI-side readers are unchanged and the producer writes only new rows; the upcaster dependency binds the read side of mixed views, not the producer.
- **FR-038** `kitty-specs/**` files already on `main` are never modified by the producer, and the producer never writes under `kitty-specs/`. A mission open at upgrade keeps working; its publish log starts at the first event after upgrade.

**K. Delivery**
- **FR-039** The producer's Java source lives in a Gradle build in this repository, with a framework-free domain, no dependency on unlanded #5529 code, and a layout #6192 / #6193 can absorb into `event-log` (OD-JAVA).
- **FR-040** Release order: the events PR is released first; then the CLI raises its floor in `pyproject.toml` and extends `tests/contract/spec_kitty_events_consumer/`; no path, git or editable override is merged.
- **FR-041** Release builds produce platform-tagged wheels each bundling exactly one native producer binary, for at least linux x86_64, macOS arm64 and Windows x86_64 (macOS x86_64 and linux aarch64 are plan decisions).
- **FR-042** The native binary is built in CI from a pinned toolchain; its checksum is recorded; its provenance is attached to the release.
- **FR-043** When no platform wheel matches, or the sdist is installed, the CLI installs and runs and the producer reports fault class `binary-missing`; it is never an install failure of the CLI.
- **FR-044** `packs/internal/` still does not ship, and the wheel's `packs` include stays narrowed to `packs/built-in/`.
- **FR-045** An ADR amendment (or new ADR) is delivered recording that new publish-log rows are CloudEvents, superseding for those rows ADR 2026-10-13-2 D-1's "flat rows" choice.
- **FR-046** The new terms of section 13 are added to the canonical glossary and the Contextive glossaries regenerated (`scripts/generate_contextive_glossaries.py`, CI-enforced).

---

## 5 Non-functional requirements and constraints

- **NFR-001** No network. The producer and client make no network call and need no credential. Test: run with networking denied.
- **NFR-002** Latency. The charter requires CLI operations under 2 s and the 10 s fan-out bound exceeds it, so the producer's timeout default is set well below it. The plan MEASURES per-event spawn cost on all three OSes and sets the budget from it; no figure is assumed here.
- **NFR-003** Determinism. The same ledger row, clock value and node id produce byte-identical publish-log lines (serializer pinned by golden tests produced by the real serializer, per #4182).
- **NFR-004** Cross-platform: Linux, macOS, Windows 10+; LF-only log; path rules per `kernel` helpers; no shell quoting dependence in the spawn.
- **NFR-005** Rigour. Java domain code (ids, clock, mapping, order, retention eligibility) carries tiered rigour above glue; the plan sets Java coverage floors (section 9).
- **NFR-006** Public hygiene. No absolute home paths, usernames or credentials in any shipped file, fixture, log row or fault record; fault records hold repo-relative paths only.
- **NFR-007** Exactly-once is not claimed: the guarantee is "at most one row per `(source, id)`" and "never silent when not produced".
- **C-001** No hand edit of spec-kitty state files. **C-002** orchestrator-api envelopes and contract version unchanged (`orchestrator_api/envelope.py`, #5231). **C-003** Drain gate unchanged in meaning. **C-004** One writer per type. **C-005** Terminology: Mission, work package. **C-006** No version numbers assigned in scope; "4.0.0 milestone" names the issue milestone only.

---

## 6 Acceptance criteria

Each states the observable and the falsifier. Each test is committed RED before its implementation (ATDD-first).

| AC | Observable | Falsifier |
|---|---|---|
| AC-001 | Default install, drain off: a lane transition appends exactly one publish-log line whose `id` equals the ledger row's normalized `event_id`. | Reverting the client registration leaves no line: red. |
| AC-002 | One test per in-scope type (FR-017): the line validates against its `data` schema and the envelope rules (FR-010, FR-015). | Schema drift, an underscored or over-long attribute name, or a `null` attribute fails the validator. |
| AC-003 | Drain independence, both orders of #6164: (a) Zeitgeist handlers registered, drain off: a test adapter and the producer both fire, the Zeitgeist handler makes no network call; (b) on a scratch copy without `zeitgeist_bridge.py` and `hosted_posture`, the producer still fires. | Any drain read by client or producer, or firing only when drain is on, fails (a); a dangling bridge import fails (b). |
| AC-004 | Seam census: per in-scope writer, one forced row yields one seam call carrying the persisted row (FR-003, FR-004). | An annotation, persist-only lifecycle or `DecisionAuthorityDenied`/`SignificanceEvaluated` writer with no call fails. |
| AC-005 | Fault matrix, one test per class (`binary-missing`, `crash`, `timeout`, `malformed-reply`, `clock-overflow`, `rejected` for non-ULID id / bad time / no `missionid` / schema-invalid row, `disabled`): command exit code and `--json` body byte-identical to the no-fault run; ledger row present; one stderr warning naming type, id, class and "NOT produced"; one fault-log row. | A raise, changed exit code or body, missing or class-less warning fails. A test passing with the warning removed is vacuous. |
| AC-006 | Timeout: a stub that outlives the bound is terminated by handle; the command returns within the fan-out bound; no child remains. | An orphan, or a command waiting on the stub, fails. |
| AC-007 | After N faults `doctor` reports count and last class; a clean run reports zero. | A silent `doctor` fails. |
| AC-008 | Total-order permutation test over the data-model section 4 fixtures (six examples, the counter-example): every input permutation gives the same result; the naive comparator, run in the same test, does not. | A naive-comparator implementation fails the permutation assertion. |
| AC-009 | Error rows: an unparseable-`time` row is in the error list with line position and absent from the ordered list; ordered + errors = rows read. | A dropped or silently sorted row fails the count. |
| AC-010 | Clock: N concurrent processes on a scratch repo repeat no `(nodeid, lamportclock)` and the clock strictly increases per node; a state at 2^31-1 gives `clock-overflow`, no wrap. | A duplicate, decrease or wrap fails. |
| AC-011 | Firing the seam twice for one ledger row leaves one line. | Two lines fail. |
| AC-012 | The client gate (FR-027) passes the real client and FAILS a mutated copy that adds an id mint, a timestamp, a schema import, a log read or the bridge's envelope code; it also asserts a floor (client module found, call sites above zero). | A pass on the mutated copy, or with zero client modules, fails the gate's own test. |
| AC-013 | Retention: on a fixture of open/closed Missions, locked/unlocked items, unfinished steps, a dry run lists exactly the eligible items and deletes nothing; a real run deletes only those; exactly `keep_runs` newest are kept; an item younger than `max_age_days` is kept; both limits must be exceeded. | Deleting an open Mission's or locked file, or any delete in dry run, fails. |
| AC-014 | Cleanup never opens a tracked path for write: with every tracked ledger offered as a candidate, zero writes to tracked paths; self-mutation puts a tracked path in the eligible set and the gate must fail. | A fixture with no tracked ledger (vacuous) fails the floor. |
| AC-015 | Reading every archived Mission's `status.events.jsonl` with the CLI readers (and the upcaster once released) leaves each file byte-identical (hash before/after). | Any changed byte fails. |
| AC-016 | Running the producer on a scratch repo changes no `kitty-specs/**` file on `main` and creates none. | Any diff there fails. |
| AC-017 | Mid-flight: a Mission open before upgrade keeps its ledger byte-unchanged; its publish log starts at the first post-upgrade event. | A backfilled row or changed ledger byte fails. |
| AC-018 | orchestrator-api contract tests pass unchanged; no `--json` body of an affected command changes (snapshot). | Any envelope or body diff fails. |
| AC-019 | `src/` has no marker citing #1198 or #1248; #1200 stays x8; the persisted-line producer gate fails when a persisted line is made schema-invalid. | A left marker, or a gate that passes a corrupted line, fails. |
| AC-020 | The lane list consumed by `lane.transitioned` is defined once; the lane-literal gate fails on a newly added full list (self-mutation); `uninitialized` is rejected; `doing` appears as `in_progress`. | A second definition or an accepted `uninitialized` fails. |
| AC-021 | `reason_source` round-trips: operator-note cancel gives `operator`; bare forced cancel `synthetic`; absent stays absent. | A dropped or defaulted value fails. |
| AC-022 | Run-journal rows carry a ULID `event_id` and version; a journal without them is still read. | A rejected old journal or an id-less new row fails. |
| AC-023 | `git check-ignore` succeeds for the publish log, fault log and clock state after fresh `init` and after upgrade of an existing project. | An unignored or tracked path fails. |
| AC-024 | Per platform wheel: binary present, executable, correct tag, exactly one binary; a clean-venv install runs the producer smoke test on its OS in CI. | Missing or extra binary, wrong tag, or smoke failure fails. |
| AC-025 | Clean venv with no binary (sdist or no matching wheel): a lifecycle command succeeds and warns `binary-missing`. | An install failure or a silent run fails. |
| AC-026 | Torn line: truncate the publish log mid-line, append: the torn line stays and is flagged, the new line is intact, readers name the malformed line. | A joined line or silent skip fails. |
| AC-027 | The CLI consumer tests pin the new schema surface and the floor; removing a schema in the events repo breaks them. | A consumer test that does not break fails. |
| AC-028 | `ruff check`, `ruff format --check`, mypy on the client; Java build, unit tests and ArchUnit (framework-free domain); terminology guard. | Any red gate. |

---

## 7 Dependencies and hand-offs

| Item | This mission delivers | Leaves to | Repo | Owner issue |
|---|---|---|---|---|
| CloudEvents envelope schema + validator, upcaster, legacy fixtures, STRICT-type registry, bindings, major-bump artifacts | per-kind `data` schemas for emitted types, registry entries, `dataschema` URIs, producer + total-order fixtures; fallback minimal envelope subset | all the rest; reducer sort-key change (Phase 2) | spec-kitty-events | events#92 |
| Events charter amendment (#6117 decision 11) | flag only (section 9) | amendment | spec-kitty-events | #6117 / events#92 |
| Event-log write side | first Java producer with ULID id, clock stamping, local publish log, retention, wheels | lock-interop spike; byte-identical append proof; appends to committed logs; commit step; temporary Python bridge | spec-kitty | #6193 |
| Command-side write core | nothing | lane state machine and transition rules in Java | spec-kitty | #6194 |
| Lane cut-over / other status writes | nothing | writing CloudEvents into committed ledgers; retiring Python writers; switching the CLI reducer; project schema bump | spec-kitty | #6195, #6196 |
| Merge driver `status/event_log_merge.py` | nothing | CloudEvents-aware dedupe `(source, id)`, fork detection on upcast form (precondition of any writer emitting CloudEvents into a COMMITTED log) | spec-kitty | #6195 |
| Reducer sort key (`status/reducer.py`, events package reducer, Java read model) | the defined order + fixtures | the switch | spec-kitty, spec-kitty-events | events#92 Phase 2; #6195 |
| Removal of hosted/sync/drain, `zeitgeist_bridge.py` | seam works either order | the removal | spec-kitty | #6164 |
| `domain-model` / `event-log` modules | liftable producer layout | extraction | spec-kitty | #6192 |

**Merge-order rule with #6164:** whichever lands second rebases; the only shared file is `status/adapters.py`. Before #6164 the producer registers beside the Zeitgeist handler in the existing slots.

**Tooling defects** (SK-350, SK-311, SK-331, SK-319, SK-339, SK-328, SK-320): consequences for the binary lookup, per-checkout publish log and ignore handling are in `plan-carryover.md` section T.

---

## 8 Release and gates

Named gates the wheel change needs, with the existing machinery each extends. 
| Gate | Extends |
|---|---|
| G-1 wheel-content per platform wheel: binary present, executable bit, platform tag, no extra binaries | `release.yml` "Verify wheel contents"; `test_packaging_safety.py` |
| G-2 exact installability from each built wheel | `release.yml` |
| G-3 native smoke test on each OS (producer appends one valid row) | `ci-windows.yml` + linux and macOS lanes |
| G-4 `twine check` on every wheel | `release.yml` |
| G-5 SBOM covers the binary and its Java/native-image dependency licenses | CycloneDX step |
| G-6 pip-audit and `uv.lock` freshness (Java dependency audit is a plan item) | existing dependency gates |
| G-7 sdist builds without the binary or fails loudly; install runs, reports `binary-missing` (FR-043) | `test_package_bundling.py` |
| G-8 binary reproducibility and provenance: pinned toolchain, recorded checksum (FR-042) | `release.yml` |
| G-9 red-CI-no-release for every RC and the GA; the new matrix is inside the aggregate gate | Standing Order 9 |
| G-10 `packs/internal/` still excluded (FR-044) | `test_packaging_safety.py` |
| G-11 producer gates (client-no-logic, persisted-line schema, lane list, retention-no-tracked-write, seam census), each non-vacuous | `tests/architectural/` named files, targeted |

Why each gate: `plan-carryover.md` section G.

**Omitted deliberately:** signing and notarization is an open risk, not a gate (OD-SIGN, no policy invented). Linux libc variants and extra arches are plan decisions. Release channel and RC handling are unchanged.

---

## 9 Governance drift (flagged; nothing resolved here)

1. Charter "Python 3.11+ for all CLI and library code" and Python quality gates versus the Java producer: Java build, test, lint, ArchUnit and coverage floors have no charter row; Python only for the shim.
2. Charter forbids vendoring events source: Java binding generation sits across that boundary.
3. ADR 2026-10-13-2 D-1 chose flat ledger rows with the 3.0.0 envelope as the off-machine contract; the CloudEvents ruling supersedes it for the new rows. An ADR amendment is a deliverable (FR-045).
4. The events-repo charter says "Python library" and "Pydantic" (#6117 decision 11): flagged, not amended.
5. Charter "90%+ coverage" and diff-cover >= 90% are Python measurements; Java coverage tooling is unstated.
6. CLAUDE.md says `release.yml` is not present; it is (`.github/workflows/release.yml`). The charter wins.
7. Charter "PyPI distribution via automated release workflow" has no row for platform-tagged wheels or native binaries.

## 10 Open decisions

| Id | Question | Options | Recommendation | Blocks |
|---|---|---|---|---|
| OD-SINK | Is the sink the gitignored publish log (Python ledger writers authoritative), or the committed ledgers (Java the only writer)? | (a) publish log now; committed-ledger writing at #6193/#6195; (b) write the committed ledgers here. | (a): a missing binary cannot lose authoritative state, and lock interop, merge driver and reducer stay out. After cutover the fault rule for authoritative rows is a different, fail-closed one. The total-order requirement is sink-agnostic. | plan |
| OD-JAVA | Standalone Gradle module now, or wait for #6192's `event-log`? | (a) standalone, structured for absorption; (b) wait for #6192. | (a); FR-039 holds under both. | plan |
| OD-JOURNAL | The run journal has no `event_id`; FR-007 needs one. | (a) Python journal writer adds a ULID `event_id` and row version (ADR D-1); (b) defer run-journal rows, leaving #1248 an explicit residual. | (a). | plan |
| OD-RETENTION | Defaults 20 runs / 30 days are the ADR's proposal. | confirm or change. | confirm. | no |
| OD-SIGN | macOS signing and notarization, Windows signing: policy, certificates, owners. | not invented here. | operator to state. | release, not plan |
| OD-MATRIX | macOS x86_64 and linux aarch64 wheels; linux libc variants. | include or exclude. | plan decides with a stated minimum (FR-041). | no |
| OD-OPTOUT | Does the `disabled` class exist (an environment opt-out of the local producer)? | (a) none; `binary-missing` is the supported degraded state; (b) an environment kill switch. | (a); ADR rejects a publication toggle. The class stays in the enum, unused, and the gate asserts the enum matches. | no |
| OD-EVENTS | Dataschema base URI, lamportclock type, final type names, minimal-envelope fallback timing: owned by events#92 and its PR review. | per #92. | follow #92. | events PR |

Plan-blocking: OD-SINK, OD-JAVA, OD-JOURNAL.

---

## 11 Risks

Elaboration: `plan-carryover.md` section K.
- Critical path: events PR released, floor bump, CLI producer; #6193 is blocked by this mission, #6194 by #6193.
- Native build weight and unmeasured spawn cost against the 2 s budget (NFR-002).
- Unsigned macOS and Windows binaries (OD-SIGN).
- Windows byte rules (#4182) on the publish log.
- INTERIM choices become breaking once emitted; the events PR confirms or changes them before release.
- Seam coverage drift: guarded by the census gate (AC-004).
- Clock coupling: a clockless row appended after clocked rows sorts first; the guard depends on #6195.
- #6164 ordering: a rebase conflict on `status/adapters.py` is expected.
- Disk: the publish log duplicates ledger content; retention bounds it.

---

## 12 Reflexivity and mid-flight

This mission's own events are written by the Python writers; the producer need not publish them. A Mission open when the producer ships keeps its ledger unchanged and its publish log starts at the first post-upgrade event (no backfill). The mission must not depend on the producer to pass its own status gates.

## 13 Glossary additions (Contextive regeneration required, CI-enforced)

New terms, none defined in `docs/context/`: **Producer**, **Publish seam**, **Publish log**, **Ledger**, **Fan-out** (not "sync"), **Clockless row**, **Clocked row**, **Fault log**. Definitions: `plan-carryover.md` section L. They go to `docs/context/` (the plan names the file), then `python scripts/generate_contextive_glossaries.py generate` and `check`.
