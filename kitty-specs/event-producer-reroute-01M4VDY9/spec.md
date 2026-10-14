# Mission Specification: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 (P1, 4.0.0 milestone) | **Branch**: `feat/event-producer-reroute-6185` | **Created**: 2026-10-13 | **Status**: Draft (spec phase)
**Companion**: [`data-model.md`](data-model.md) (entities, type table, total order). Requirements and acceptance only; HOW belongs to the plan, which reads [`plan-carryover.md`](plan-carryover.md).

**Assumptions in force** (all open decisions are resolved: section 10): (1) a Java producer behind a thin Python client; (2) Python writers stay authoritative, the producer appends to a gitignored publish log (D-SINK); (3) either landing order with #6164 works (D-6164); (4) INTERIM: unconfirmed ADR 0001 choice.

---

## 1 Purpose and scope

**Purpose.** The only code that builds a `spec_kitty_events` `Event` is `status/zeitgeist_bridge.py`, on the hosted drain path (off by default, slated for removal, #6164), so lifecycle and status events reach no integration on a default install. This mission gives them a local, on-by-default publication path separate from `hosted.drain`, with a versioned per-row-kind contract in `spec-kitty-events`.

**In scope.** (1) A Java producer, shipped as a native binary in platform-tagged wheels, that turns each persisted ledger row into one CloudEvents 1.0 structured-JSON row in a gitignored local publish log. (2) A thin Python client on the post-persistence fan-out seam (`status/adapters.py`), firing irrespective of drain. (3) One `spec-kitty-events` PR (separate repo, released first): the FR-014 list. (4) The defined total order for logs mixing clocked and clockless rows (data-model section 4). (5) Fault handling that never fails a lifecycle command and is never silent. (6) Retention for the gitignored local logs, a Java operation (FR-030); committed ledgers are never pruned. (7) Clearing the #1198 and #1248 `canonical-producer-exempt` markers; lane-list deduplication; carrying `reason_source`; the one flagged Python modification, run-journal field stamping (FR-035). (8) Platform wheels and their release gates. (9) An ADR amendment (section 9).

**Out of scope** (full wording: carryover Q). Committed-ledger CloudEvents and retiring the Python writers (#6193, #6195, #6196); `status/reducer.py`, the #5529 read model, `status/event_log_merge.py`; any Java read of the committed ledgers beyond the minimal read-only one retention needs for "Mission open" (FR-039, not the #5529 read model, absorbed by #6193); envelope schema, validator, upcaster (events#92); glossary rows (#1200); backfill; retiring `3.0.0`; renaming the `saas_fanout` slots; hosted behaviour.

---

## 2 Context and evidence (verified against this checkout)

Table: carryover E. Load-bearing: **E2** three slots; all `fire_*` return unless the drain posture is enabled; handlers run bounded, a busy one is skipped; `RuntimeMomentProducer._publish` has its own drain read. **E14** the `repo_root` keyword of `fire_*` is a starting path, not a root. **E15** `status/emit.py::_load_mission_id` returns None for older Missions.

---

## 3 Decisions encoded

**Operator decisions (binding; never re-opened here).**

1. Taken at the maintainer's request. 2. Strangler: Java producer, thin Python client only (FR-001, FR-026, FR-027, FR-039); the ONE flagged exception is the run-journal field stamping of FR-035. 3. CloudEvents 1.0 structured JSON; legacy rows stay readable, never rewritten (FR-006, FR-036, FR-037). 4. Ordering option C; ULID-only new ids; prefix `ai.spec-kitty` (FR-007 to FR-013). 5. Platform wheels (FR-041 to FR-044). 6. Degrade with a recorded warning, never silent (FR-022 to FR-025). 7. One schema set, one writer per type (FR-014, FR-015, FR-034). 8. Public hygiene (NFR-006).

**INTERIM** (ADR 0001 "further choices"; the events PR confirms each): snake_case `data` members; `actor` and `executionmode` attributes; `.vN` versioning; `(source, id)` dedupe key; type names; lifted members; `source` form; `dataschema` base URI.

**Repo-evidence decisions (to confirm).**

| Id | Decision | Evidence | Confirmation |
|---|---|---|---|
| D-SINK | Producer on the post-persistence seam; Python writers authoritative. | E2 | ruled: OD-SINK |
| D-REDUCER | `status/reducer.py` untouched; the order ships as comparator spec and fixtures. Java reads the committed ledgers only for retention's "Mission open" facts (FR-039): minimal, read-only, not the #5529 read model, absorbed by #6193. | E9 | OD-RETAIN-FACTS |
| D-6164 | The seam fires the producer irrespective of drain; either landing order works. | E2 | AC-003 |


**The total order**: data-model section 4 (sink-agnostic).

---

## 4 Functional requirements

Rationale: carryover R.

**A. Seam and coverage**
- **FR-001** The CLI registers a thin client on the existing fan-out seam that hands every in-scope persisted row to the Java producer on a default install, with no setting required. One spawn per row (OD-SPAWN, measured by AC-036); each row yields at most one publish-log row.
- **FR-002** The seam fires the producer irrespective of `hosted.drain`: the drain gate moves to the hosted (Zeitgeist) handlers (none after #6164); `RuntimeMomentProducer._publish` drops its own drain read; the client registers outside the `moment_handlers_disabled_reason` branch of the `status/adapters.py` import tail and ignores the hosted kill switches; the ADR's confirmation pin and the reset helpers (`reset_handlers`) move with the gate and re-register the client.
- **FR-003** Every in-scope persisted row (data-model section 3) reaches the seam exactly once. E4 gaps close: every persisted `InnerStateChanged` row, plain or resolved-binding, standalone or bundled by `emit_status_transition` or `emit_status_transition_batch`, gets its own NEW seam call (today none reaches a registered slot); persist-only lifecycle callers and the retrospective writers get one where their row is persisted or at their existing fan-out call. The client registers on the `status` and `lifecycle` slots only. The census gate (AC-004) fails when a persisted row kind has no seam call.
- **FR-004** At each seam call the persisted row (as written to the authoritative ledger, after sanitization) is handed to the client as one opaque mapping, additive to the existing keyword arguments (the producer reads the row, never the derived hosted envelope the decision and run-journal calls keep for hosted handlers), with a starting path as the call site gives it (E14); the lifecycle fire forwards its `repo_root` to handlers as the status fire does (`fire_lifecycle_saas_fanout` does not today; a seam change this mission makes, AC-001). The PRODUCER resolves the canonical repository root from it (`core.paths.resolve_canonical_root`), so a lane worktree, a coordination worktree and the primary checkout reach one publish log; the client repeats it only to place the fault log (FR-026). An unresolvable start is fault class `rejected` (`root-unresolvable`), never a silent return.
- **FR-005** The producer emits nothing beyond the persisted row: it adds no member the authoritative row lacks, other than the envelope attributes of data-model section 1 and the one stated rename (`actor` to `data.actorbinding` for a structured actor, data-model section 3).

**B. Envelope and ids**
- **FR-006** Each published row is one CloudEvents 1.0 structured-JSON object per line, `datacontenttype` `application/json`, `type` prefixed `ai.spec-kitty`, `dataschema` naming the committed `data` schema of its type.
- **FR-007** `id` equals the authoritative row's `event_id`; the producer never mints a different id for a ledger row. It accepts only a 26-character ULID (written upper-case). Any UUID form, hyphenated or 32-hex, is refused with fault class `rejected`. UUID normalization belongs to readers and the order key for legacy rows.
- **FR-008** `time` is the authoritative row's own timestamp (`at` or `timestamp`) as an RFC 3339 instant. The producer never synthesizes a time; a row without a parseable time is refused (`rejected`).
- **FR-009** `source` is `/spec-kitty/missions/<missionid>` for Mission-scoped rows and `/spec-kitty/projects/<project_uuid>` for project-scoped rows (INTERIM). `missionid` comes from the row, else from the Mission's `meta.json` read by the producer. If neither yields it (E15) the row is published under the project source `/spec-kitty/projects/<project_uuid>` with `subject` = the aggregate and no `missionid` attribute (the ADR 0001 fallback, OD-MISSIONID); this is no fault, no warning and no refusal. A slug is never presented as a mission id. For a run-journal row the Mission is the one whose directory the client passes as `start_path`; the producer reads `missionid` from the row's `payload.mission_id` when non-empty, else from that directory's `meta.json`. `doctor` may add an optional note that `migrate backfill-identity` repairs such a Mission; it is never counted as a fault.
- **FR-010** Context attribute names match `^[a-z0-9]{1,20}$`; values use only CloudEvents attribute types; absent values are omitted, never `null` (conformance test).

**C. Ordering and clock**
- **FR-011** The producer stamps `lamportclock` (Integer) and `nodeid` (String) on every row. `nodeid` equals `identity/project.py::generate_node_id()` for that machine and user (E10): the same inputs and hash, verified by a golden test on each OS (AC-039); the Java producer derives it itself from those inputs, the request does not carry it (OD-NODEID).
- **FR-012** The clock is a strictly increasing counter in a state file the producer owns, anchored at the git common directory so every worktree of one repository shares it under one `nodeid` (OD-CLOCK), so no two events of one repository (its linked worktrees included) share a `(nodeid, lamportclock)` pair; separate clones on one machine share a `nodeid`, an accepted residual (OD-CLOCK). Stamp and append are one exclusive operation, state first, then the line; a kill leaves no advance, or an advance and no line (a gap), never a reused value. After state loss the first stamp exceeds the highest clock readable in the publish log; the state is never pruned while its log exists. The clock never exceeds 2^31-1: on overflow the event fails (`clock-overflow`), no wrap. Merging other nodes' clocks is #6193's.
- **FR-013** The spec's total order (section 3) is delivered as a reference comparator specification and conformance fixtures in the events PR, including the non-transitive counter-example, the dedupe-then-order rule and the error rows. No CLI reducer changes here.

**D. Schemas and types**
- **FR-014** The events PR carries exactly: one versioned `data` JSON Schema per emitted type with its lifted-member list, the registry entries, the `dataschema` URIs, the producer conformance fixtures, the total-order comparator specification and fixtures (FR-013), the `spec_kitty_events.Lane` alignment (data-model section 9) and, conditionally, the minimal envelope subset (FR-035). The envelope schema, validator and upcaster are #92's.
- **FR-015** Every row the producer appends validates against its type's `data` schema and the envelope attribute rules before it is written; a row that fails is refused (`rejected`), not written.
- **FR-016** Re-firing the seam for an already published ledger row (same `(source, id)`) leaves one publish-log row.
- **FR-017** The type table (data-model section 3) states per type the legacy source, writer, emission point, scope, `subject`, lifted members and status. Deferred: glossary rows (#1200) and the three registered decision kinds with no emitter; in scope: every other persisted kind, run-journal rows included (#1248; OD-JOURNAL).
- **FR-018** `lane.transitioned.v1` `data` carries the members of data-model section 3 including `reason_source` (optional; `operator` or `synthetic`) and `actorbinding` (only for a structured actor); `actor`, `executionmode`, `missionid`, `missionslug` and `subject` are context attributes. `doing` is resolved to `in_progress`; `reason_source` is never dropped.

**E. The publish log**
- **FR-019** The publish log is gitignored, local tier, one UTF-8 JSON object per line (a flagged torn fragment, FR-021, is the one exception), LF only, no BOM, no embedded raw newline; its location is a plan decision under the local tier and inside retention. A gate asserts `git check-ignore` for the publish log and fault log; for the clock state, that its path lies under `git rev-parse --git-common-dir`.
- **FR-020** The producer is the single writer of the publish log (C-004). No Python code opens it for write (AC-030); no file shared with a Python writer is introduced.
- **FR-021** A crash mid-append never lets a later append join a torn line to the next row: a torn final line (no trailing LF) is detected by the producer before the next append, left in place, the next row starting on a new line, and reported in the reply; the client records it as a `torn-line` notice (FR-023) with the repo-relative log path and line position. Readers are out of scope (FR-036); clock recovery (FR-012) and dedupe skip the fragment.

**F. Faults**
- **FR-022** A missing binary, crash, timeout, malformed reply, clock overflow, refused request, or busy-skip never fails the lifecycle command, never changes its exit code, never changes any `--json` body and never changes an orchestrator-api envelope.
- **FR-023** Each fault records a warning naming event type and event id when available (the reply's, else the row's own members copied uninterpreted; omitted when the row has none), reason class (`binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`; there is no `disabled` class and no producer opt-out setting, `binary-missing` being the degraded state, OD-OPTOUT) and "event was NOT produced" (`crash`, `timeout`: "event may not have been produced"). Carriers: a stderr line in EVERY mode (no `--json` body changes) and an append-only gitignored fault log (retention applies); if the log cannot be written, stderr still carries the warning. The client is the fault log's single writer; the producer's notices travel in its reply. A `torn-line` notice is a second fault-log row kind, not a reason class: one stderr line and one row, not a fault for FR-024. One warning per event, not per retry. The fault log sits beside the publish log under the local tier (FR-019); with no root the warning is stderr only. The client is the outermost catch; the seam's busy-skip and `TimeoutError` paths call its fault recorder. The `retain` operation's failures are `policy_error` or kept-with-reason replies, not event faults.
- **FR-024** A degraded state stays visible: `doctor` reports the fault count (fault rows, not notice rows, in the retention window; success does not reset it, only retention reduces it) and the last fault by class; notices are counted apart.
- **FR-025** The call is bounded: a finite, configurable client timeout, resolved per call and kept strictly below the EFFECTIVE seam bound (`status/adapters.py::_saas_fanout_timeout_s()`, which reads the `SPEC_KITTY_SAAS_FANOUT_TIMEOUT` override; the client lowers its own timeout to stay under it), and still applying when that bound is disabled (`<= 0`, handler inline). The child also receives `deadline_ms` and exits by itself at that deadline, so no child outlives an abandoned seam call. On timeout the client terminates the child it spawned (by its own handle, never by name pattern) and records `timeout`. One breaker per process: after the first timeout or crash the remaining rows of the command record one fault each without spawning. The default: NFR-002.

**G. The thin client**
- **FR-026** The client may only: locate the platform binary; spawn it; pass the seam's arguments and the FR-004 row, serialized without interpretation; pass `doctor`'s retention arguments to the Java retention operation and render its reply; resolve the effective seam bound and enforce its own timeout below it; terminate its own child by handle; hold the per-process breaker; read one reply; map any failure to a fault class; copy the row's `event_type` and `event_id` into a client-only fault as opaque values; write the FR-023 stderr line; append fault-log rows (fault and notice), resolving the canonical root for that location only; count its own fault-log rows and report the last class for `doctor`; register itself on the seam. The client may read the wall clock only to stamp its own fault-log row's `at` and to name the UTC day file, in `producer_faults.py` alone; never for an event. The fault-count window is the fault-log day files whose name date lies within the raw `max_age_days` of today; with that value absent or non-numeric, every file present. It must not build or validate an envelope or attribute, mint ids, timestamps or clocks, choose type names or `dataschema` URIs, know the schema, order, dedupe, normalize ids or read the publish log.
- **FR-027** An architecture gate enforces FR-026 (floor: the client module is found and its call sites counted; a self-mutation test injects a forbidden capability). It also fails if the Python bridge's envelope code (`_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`) is carried into the client. The allowed Python surface of this mission is the thin client, the seam wiring of FR-002 to FR-004 and the FR-035 stamping, the last the one flagged exception; the gate names that surface and fails on other new Python logic. The allowed Python surface is the file, symbol and new-import list of plan section 4.2; the gate fails on any other new module or function in the status and emission area, and on a new top-level name or import in an existing file of that list beyond that file's row; the list is its data.

**H. Retention**
- **FR-028** Retention deletes whole local-tier files only; it never rewrites or trims a file, so the single writers of the publish log (FR-020) and fault log (FR-023) stay the only writers, and the fault-count window is a file age. The plan places the publish log and fault log in per-run or per-period files for that. Policy keys `ledger.retention.keep_runs` and `ledger.retention.max_age_days` in `.kittify/config.yaml` (defaults 20 and 30, OD-RETENTION). Python forwards the raw `ledger.retention.*` values from `.kittify/config.yaml` unread; Java applies the defaults 20 and 30 and validates them, returning the named policy error that `doctor` renders. "Run" is defined for each local-tier file the plan places.
- **FR-029** An item is eligible only when outside both limits (not among the newest `keep_runs` AND older than `max_age_days`), its Mission is not open (any work package in a lane other than done or canceled), it holds no lock and no step is unfinished (a run journal without `MissionRunCompleted`, or a live lock record: a Python-owned lock sidecar holding a non-empty parsable lock record younger than the stale threshold; an empty or truncated sidecar is not a lock; the sidecar's presence alone is not a lock; `clock.lock` is never a lock fact); the Java retention operation derives those facts itself from the committed ledgers and the local tier (FR-039, OD-RETAIN-FACTS); the request never carries them.
- **FR-030** Cleanup is an explicit, dry-run-first command in the `doctor` family; never implicit. It is a Java operation (a second `ProduceRequest` operation, data-model section 6); the Python `doctor` side is a thin pass-through (FR-026) (OD-RETAIN). A dry run lists what a real run would delete and deletes nothing. Retain takes `clock.lock` around each candidate delete, re-checks eligibility and length under it, and maps a lock timeout or delete failure to kept-with-reason.
- **FR-031** Cleanup never opens a git-tracked path for write (AC-014). Committed ledgers are never pruned.

**I. Exemptions, lanes, journal**
- **FR-032** After this mission no `canonical-producer-exempt` marker in `src/` cites #1198 or #1248 except named residuals with an issue reference (data-model section 8). A marker on an authoritative Python writer is cleared only when a producer gate validates the PERSISTED lines that writer wrote against the legacy-row form of the published schema: generated from the single `data` schema plus the lifted-member list (FR-014), never a second authored schema. The gate also validates publish-log lines.
- **FR-033** The eight #1200 markers in `glossary/events.py` stay a named residual in the exemption ledger, neither widened nor cleared.
- **FR-034** Lane membership has one canonical definition, consumed by the `lane.transitioned` data schema. The persisted lane vocabulary excludes `uninitialized`. A census of every full lane-literal list (E6) is the first plan task, and a gate fails when a new full lane list appears.
- **FR-035** The existing Python run-journal writer gains a ULID `event_id` and a row version at write time (additive; rows without them stay legacy; OD-JOURNAL). FLAGGED: this is the ONE narrow modification of existing Python in this mission: field stamping only, no new logic. If #92's envelope schema has not landed when needed, the events PR carries a minimal envelope subset, strictly a subset of ADR 0001's design, which #92 absorbs.

**J. Compatibility**
- **FR-036** The Python readers are untouched. `3.0.0` and diary rows are read by the events upcaster when it exists; no legacy row is rewritten.
- **FR-037** Until the events upcaster is released CLI readers are unchanged and the producer writes only new rows; the upcaster binds the read side of mixed views, not the producer.
- **FR-038** `kitty-specs/**` files already on `main` are never modified by the producer, and the producer never writes under `kitty-specs/`. A Mission open at upgrade keeps working; its publish log starts at the first later event.

**K. Delivery**
- **FR-039** The producer's Java source lives in a Gradle build in this repository, as a standalone module with a framework-free domain, no dependency on unlanded #5529 code, and a layout #6192 / #6193 can absorb into `event-log` (OD-JAVA). The build consumes the RELEASED `spec-kitty-events` artifact at build time, pinned and checksummed; no events-repo schema source, copy or generated binding source is committed here, and releasing the events PR is a prerequisite (OD-SCHEMA-DELIVERY). The retention operation reads the committed ledgers read-only and minimally, only for "Mission open" (the lane of each work package) and the lock and run-journal facts; this is not the #5529 read model, and #6193 absorbs it (OD-RETAIN-FACTS).
- **FR-040** Release order: the events PR is released first; then the CLI raises its floor in `pyproject.toml` and extends `tests/contract/spec_kitty_events_consumer/`; no path, git or editable override is merged.
- **FR-041** Release builds produce platform-tagged wheels each bundling one native producer binary, for at least linux x86_64, macOS arm64 and Windows x86_64 (the whole matrix: extra arches and libc variants are out of scope, OD-MATRIX).
- **FR-042** The native binary is built in CI from a pinned toolchain; its checksum is recorded at build and re-verified against the member inside each published wheel; its provenance is attached to the release. No bit-reproducibility claim is made.
- **FR-043** When no platform wheel matches (including the pure `py3-none-any` fallback wheel that ships beside the platform wheels, OD-FALLBACK) or the sdist is installed, the CLI installs and runs and reports `binary-missing`; never an install failure (C-007).
- **FR-044** `packs/internal/` still does not ship, and the wheel's `packs` include stays narrowed to `packs/built-in/`.
- **FR-045** An ADR amendment (or new ADR) records that new publish-log rows are CloudEvents (superseding D-1's "flat rows" for them) and that the publish log, fault log and clock state are a new read path (extending D-2) and new local-tier files (extending D-3's closed list) in ADR 2026-10-13-2.
- **FR-046** The new terms of section 13 are added to the canonical glossary and the Contextive glossaries regenerated (`scripts/generate_contextive_glossaries.py`), enforced by a targeted test added by this mission (no CI job runs the generator's `check` today).

---

## 5 Non-functional requirements and constraints

- **NFR-001** No network. Neither producer nor client makes a network call or needs a credential.
- **NFR-002** Latency. AC-036 fixes a per-command overhead ceiling through `tests/_perf_helpers.py`; the plan sets the numbers. Plan contingency, not an open decision: if one spawn per row fails AC-036, the client buffers rows and flushes once at exit (a lost flush records one fault per row), after amending FR-003, FR-026 and AC-004.
- **NFR-003** Determinism. The same ledger row, clock value and node id produce byte-identical publish-log lines (golden tests from the real serializer, per #4182).
- **NFR-004** Cross-platform: Linux, macOS, Windows 10+; LF-only log; `kernel` path helpers; no shell quoting in the spawn.
- **NFR-005** Rigour. Java domain code (ids, clock, mapping, order, retention eligibility over the facts supplied) carries tiered rigour above glue; the plan sets coverage floors.
- **NFR-006** Public hygiene. No absolute home paths, usernames or credentials in any shipped file, fixture or fault record, nor in any member the producer itself authors; fault records hold repo-relative paths only. Ledger members moved verbatim (FR-005) keep what the operator wrote; the publish log is local and gitignored.
- **NFR-007** Exactly-once is not claimed: at most one row per `(source, id)`, never silent when not produced.
- **C-001** No hand edit of spec-kitty state files. **C-002** orchestrator-api envelopes and contract version unchanged (`orchestrator_api/envelope.py`, #5231). **C-003** Drain gate unchanged in meaning. **C-004** One writer per type and per file: Python writes the ledger row, the producer the publish-log row (cutover: #6193/#6195). **C-005** Terminology: Mission, work package. **C-006** No version numbers assigned in scope; "4.0.0 milestone" names the issue milestone only. **C-007** Building from the sdist or an editable checkout never requires the native binary (any build hook is conditional). **C-008** The events PR follows spec-kitty-events#92 for the `dataschema` base URI, the `lamportclock` type, lifted-member lists and minimal-envelope timing, and for type names except the operator-ruled prefix `ai.spec-kitty` and the INTERIM names of data-model section 3, which that PR is asked to adopt; the `source` tie-break added to the total-order key goes to the events PR to confirm (OD-EVENTS).

---

## 6 Acceptance criteria

Brackets name the requirements verified (each FR, NFR, C appears bar C-001, C-006). Tests are committed RED first. **Floor rule:** every census or enumeration gate (AC-004, 008, 009, 012, 014, 015, 019, 020, 024, 029, 030, 037, 039) asserts a floor and has a self-mutation case that must fail it; a gate that scans nothing fails.

| AC | Observable | Falsifier |
|---|---|---|
| AC-001 [FR-001, FR-004, NFR-001] | Default install, drain off, network denied: a lane transition appends one publish-log line whose `id` is the ledger row's normalized `event_id`; fired per slot from a lane worktree and a Mission subdirectory, one publish log receives it (the lifecycle slot included). | Registration reverted; a second log; a silent return on an unresolved start; a lifecycle fire that drops the start path. |
| AC-002 [FR-006, FR-010, FR-015, FR-017, FR-018] | Per in-scope type, the line validates against its `data` schema and the envelope rules. | Schema drift; an underscored or over-long attribute name; a `null` attribute. |
| AC-003 [FR-002, C-003] | Both orders of #6164: (a) Zeitgeist handlers registered, drain off: a test adapter and the producer fire, no network; (b) without `zeitgeist_bridge.py` and `hosted_posture` the producer fires; (c) a run-journal row via `RuntimeMomentProducer` yields a line; (d) each hosted kill switch set: the producer fires; (e) reset helpers re-register the client; (f) the confirmation pin test covers every Zeitgeist handler. | A drain read; a dangling bridge import; (c) to (e) fail when their FR-002 part is reverted. |
| AC-004 [FR-003, FR-004, FR-017] | Writers derive from the persisted row kinds (`AUTHORITATIVE_NON_LANE_EVENT_TYPES`, retrospective, lane and annotation rows, the run-journal kinds of `runtime/next/_internal_runtime/events.py`), no hand list; a registered kind with no emitter in `src/` is a named census exemption that fails when an emitter appears; floor = the in-scope writers of section 3, the annotation written standalone and bundled by each of the two transition writers. Per writer one forced row yields one seam call carrying the row; one annotation row yields one client invocation. | An injected writer persisting with no seam call; a persisted kind in no list; a truncated set. |
| AC-005 [FR-007 to FR-009, FR-015, FR-022, FR-023, NFR-007] | One test per class (`binary-missing`, `crash`, `timeout`, `malformed-reply`, `clock-overflow`, `busy`; `rejected` for a 32-hex id, a hyphenated UUID id, a bad time, an unresolvable root and a schema-invalid row; an enum-equality test asserts `disabled` is not a class): exit code and `--json` body byte-identical to the no-fault run; ledger row present; one stderr warning in human and `--json` mode (class, FR-023 sentence, type and id as available: for the client-only classes the row's own members, verbatim); one fault-log row (none for an unresolvable root); fault log unwritable: stderr still appears, command succeeds. A Mission whose row and `meta.json` yield no `mission_id` is not a fault: its publish-log row has `source` `/spec-kitty/projects/<project_uuid>`, `subject` = the aggregate and no `missionid` member; no stderr warning, no fault-log row, fault count unchanged, exit code and body unchanged (mutation: refusing it, faulting it or emitting an empty or slug `missionid` fails). | A raise, changed exit code or body, class-less warning; a vacuous test; a Mission without `mission_id` refused, faulted, warned about or given a `missionid`. |
| AC-006 [FR-025] | A stub outliving the bound is terminated by handle; the command returns within the fan-out bound; no child remains. A batch of N rows with a hanging stub spawns once, then records N faults. With the seam bound disabled, or lowered below the client default by its environment override, the client still ends the stub first. A seam busy-skip and `TimeoutError` each yield a fault row. | An orphan; N spawns; a seam drop with no fault row. |
| AC-007 [FR-024] | After N faults `doctor` reports the fault-log rows in the window and the last class (notice rows not counted); none in the window reports zero; a success does not reset the count. | A silent `doctor`; a reset by success. |
| AC-008 [FR-013, FR-040] | Permutation test over the nine data-model section 4 fixtures (example 4 is the naive counter-example; floor: all nine) against a test-local reference comparator that implements section 4: one result for every permutation: the ordered rows, the collapsed count and the conflict keys, with error rows compared as a multiset (line position excluded); error rows set aside first, dedupe on normalized ids over valid rows, survivor by the smallest row bytes, comparison by code point. Mutations that must fail: the naive comparator (example 4), one without `source` (example 7), raw-id dedupe (example 8), one that lets an invalid duplicate survive or reports no conflict (example 9). Once the events PR is released, its comparator and fixtures run in the consumer tests with the same results. | A naive comparator; one without `source`; raw-id dedupe; a survivor chosen before validation; a released comparator that disagrees. |
| AC-009 [FR-013] | Error rows (unparseable `time`, non-normalizing or missing `id`, clock out of range) are listed with line position, absent from the ordered list; ordered + errors + collapsed = rows read, every non-survivor duplicate (a conflicting one included) counted as collapsed and the conflict also reported by key; legacy run-journal rows stay outside the order. Floor: example 9 (every error class and a conflicting duplicate); mutation: a comparator that drops an error row must fail. | A dropped or silently sorted row; a wrong count. |
| AC-010 [FR-012] | A primary checkout plus two worktrees, N concurrent processes: no `(nodeid, lamportclock)` pair repeats and the clock strictly increases per node. Log deleted, state kept: next stamp higher. State deleted, log kept: next stamp above the log's highest clock. Kill injected before the state write, between state write and append, and after the append, then rerun: the next stamp exceeds every clock in the log and the state (a gap is permitted). A killed producer yields a `crash` fault (AC-005). At 2^31-1: `clock-overflow`, no wrap. | A repeated pair, decrease or wrap. |
| AC-011 [FR-016, NFR-007] | Firing the seam twice for one ledger row leaves one line. | Two lines. |
| AC-012 [FR-026, FR-027] | The client gate passes the real client and FAILS a mutated copy adding an id mint, timestamp, schema import, log read or the bridge's envelope code; floor: client module found, call sites above zero; the client's other FR-026 capabilities (stderr line, bound resolution, child termination, breaker, fault-log append and read, opaque identifier copy, root resolution, retention pass-through) pass; new Python logic outside the allowed surface of FR-027 fails; the timestamp mutation is applied to the client module, and a second mutation adds a clock read to `producer_faults.py` outside its one clock function. | A pass on the mutated copy; zero modules. |
| AC-013 [FR-028 to FR-030] | Open and closed Missions, locked and unlocked items, unfinished steps: the Java operation derives open Missions, locks and unfinished runs from fixture ledgers it opens read-only; a dry run lists exactly the eligible items and deletes nothing; a real run deletes only those, whole files only (no candidate file opened for write); a two-process test races produce against retain; at least the newest `keep_runs` and any item within `max_age_days` are kept. | Deleting an open Mission's or locked file or a young item; any delete in dry run. |
| AC-014 [FR-031] | With every tracked ledger offered as a candidate (floor: at least one tracked ledger offered), zero writes to tracked paths; self-mutation adds a tracked path to the eligible set. | A fixture with no tracked ledger. |
| AC-015 [FR-036, FR-037] | Reading every archived Mission's `status.events.jsonl` with the CLI readers (and the upcaster once released) leaves each file byte-identical; floor: at least one read; mutation: a reader that rewrites one byte must fail. | A changed byte; zero files. |
| AC-016 [FR-038] | The producer on a scratch repo changes no `kitty-specs/**` file on `main` and creates none. | Any diff. |
| AC-017 [FR-038] | A Mission open before upgrade keeps its ledger byte-unchanged; its publish log starts at the first post-upgrade event. | A backfilled row or changed byte. |
| AC-018 [C-002] | orchestrator-api contract tests pass unchanged; no affected `--json` body changes (snapshot). | Any envelope or body diff. |
| AC-019 [FR-032, FR-033] | No marker in `src/` cites #1198 or #1248 except named residuals; #1200 stays x8. The gate validates (i) publish-log lines, at least one per in-scope type from the AC-002 fixtures, and (ii) one row per cleared marker written by the real writer, in legacy-row form (floor: schemas resolved, cleared-marker count; mutation: a schema-invalid line must fail). | A left marker; an empty schema set; a schema-invalid persisted line that passes. |
| AC-020 [FR-034] | The lane list consumed by `lane.transitioned` is defined once; the gate first finds the census lists of data-model section 9 (floor), then fails on a newly added full list (mutation: an injected list must fail); `uninitialized` is rejected; `doing` appears as `in_progress`. | A second definition; an accepted `uninitialized`; a scan finding nothing. |
| AC-021 [FR-018] | `reason_source` round-trips: operator-note cancel `operator`; bare forced cancel `synthetic`; absent stays absent. | A dropped or defaulted value. |
| AC-022 [FR-035] | Run-journal rows carry a ULID `event_id` and version; a journal without them is still read. | A rejected old journal; an id-less new row. |
| AC-023 [FR-019] | After fresh `init` and after upgrade, `git check-ignore` succeeds for the publish log and fault log, and the clock state lies under the git common directory. | An unignored or tracked path; clock state inside the working tree. |
| AC-024 [FR-041] | Release verification enumerates the built wheels and fails unless the three minimum platform tags and the pure wheel are present; mutation: one tag removed must fail. Per platform wheel: one executable binary at the known path, correct tag; a by-path clean-venv install passes the smoke test on its OS. | Missing or extra binary; wrong tag; smoke failure; a removed matrix entry. |
| AC-025 [FR-043] | Clean venv with no binary (sdist, pure wheel or no matching wheel): a lifecycle command succeeds and warns `binary-missing`. | An install failure; a silent run. |
| AC-026 [FR-021] | Truncate the publish log mid-line, append: the torn line stays, flagged; the new line is intact; the fault log holds a `torn-line` notice naming the repo-relative path and line position, and one stderr line is written. | A joined line; an unflagged torn line. |
| AC-027 [FR-014, FR-039, FR-040, C-008] | CLI consumer tests pin the new schema surface and the floor; removing a schema in the events repo breaks them; no events-repo schema source is committed here. | A test that does not break; a committed schema source. |
| AC-028 [FR-039, NFR-005, C-005] | `ruff check`, `ruff format --check`, mypy on the client; Java build, unit tests, ArchUnit (framework-free domain); terminology guard. | Any red gate. |
| AC-029 [FR-005] | Per type, the key set of `data` is a subset of the persisted row's keys (for lifecycle rows, of its `payload`) plus the lifted-member rules of data-model section 3 (`actorbinding` only for a structured row `actor`, equal to it). Floor: every type of section 3 resolved; mutation: an injected member must fail. | A member injected from `meta.json` or the environment. |
| AC-030 [FR-020, C-004] | No Python module opens the publish log path for write (floor: path constant and scanned modules found). | An injected Python `open` for write. |
| AC-031 [FR-028] | Invalid or non-numeric `keep_runs` or `max_age_days`: a named error, nothing deleted, also on a dry run. | A silent default; any delete. |
| AC-032 [FR-042] | The checksum recorded at binary build, recomputed from the member inside each published wheel, matches; provenance attached. | A binary swapped after build. |
| AC-033 [FR-044, FR-045, FR-046] | No wheel or sdist contains `packs/internal/`; the ADR amendment exists and names D-1, D-2 and D-3; a targeted test added by this mission (no CI job runs the generator's `check` today) passes with the section 13 terms. | A member under it; a missing file or decision; a removed term. |
| AC-034 [NFR-003, NFR-004, FR-019] | The same row, clock and node id give byte-identical lines on all three OSes (golden file from the real serializer); no BOM, CR or embedded raw newline. | A differing byte. |
| AC-035 [NFR-006] | A scan of fixtures, fault records and the members the producer authors finds no absolute home path or username. | A planted path. |
| AC-036 [NFR-002] | Per-command overhead (cold and warm spawn, per OS) over the largest multi-row command (`emit_status_transition_batch`) stays under a ceiling in `tests/_perf_helpers.py`. | A planted slow stub. |
| AC-037 [G-1, G-2, G-12] | Release steps, scripts and packaging fixtures iterate the expected wheel set (floor: non-empty); the dist set equals the pinned list (platform wheels, one sdist, the pure wheel); mutation: one wheel removed must fail. | A fixture picking fewer wheels; one wheel removed. |
| AC-038 [C-007, G-7] | A wheel built from the extracted sdist with no binary succeeds; an editable install works without it; the CLI reports `binary-missing`. | A build or install needing the binary. |
| AC-039 [FR-011] | The producer's `nodeid` equals `generate_node_id()` on each OS for the live hostname and user and for fixed injected `hostname:username` strings (floor: the live case plus at least two injected strings); mutation: a changed hash input must fail. | A differing value on any OS. |
| AC-040 [G-5, G-13] | The release SBOM names a known Java dependency and the embedded toolchain; Java dependency verification fails on a changed checksum. | An SBOM unchanged by the binary. |

---

## 7 Dependencies and hand-offs

Hand-offs (detail: carryover AB): the events PR carries the FR-014 list and events#92 absorbs the rest (envelope schema, validator, upcaster, STRICT-type registry, reducer sort-key switch); #6193 owns the event-log write side beyond this first producer; #6194 the Java lane state machine; #6195 and #6196 CloudEvents in committed ledgers, retiring the Python writers, the CLI reducer switch and `status/event_log_merge.py`; #6164 the removal of hosted/sync/drain (the seam works in either order); #6192 the `domain-model` / `event-log` extraction.

**Merge order with #6164:** whichever lands second rebases (`status/adapters.py`). Cutover: C-004. Tooling: carryover T.

---

## 8 Release and gates

Machinery: carryover G, Z.

**Per-wheel rule.** Every release step, script and test that globs or asserts one wheel becomes per-wheel over the expected set (machinery: carryover G). The binary sits at one known in-package path under an existing known prefix, or the prefix tuple of `test_wheel_contains_only_known_packages` is amended.

| Gate | Requirement and the machinery it extends |
|---|---|
| G-1 | Per platform wheel: one binary, executable, platform tag. |
| G-2 | Installability from each built wheel. |
| G-3 | Install each built wheel by path into a fresh venv on each OS and run the smoke test (one valid row appended): nightly, on PRs touching the Java tree, packaging config or client, and in `release.yml` before publish. New jobs. |
| G-4 | `twine check` on every wheel. |
| G-5 | The release SBOM lists the Java dependencies and the toolchain embedded in each binary. |
| G-6 | `uv lock --check`. |
| G-7 | Sdist and editable builds succeed without the binary; install runs and reports `binary-missing` (FR-043, C-007). |
| G-8 | Provenance and pinned toolchain; checksum continuity from build to each published wheel (FR-042). |
| G-9 | Red-CI-no-release (Standing Order 9): a per-OS build stage feeds one assembling job; `publish-pypi` needs the set-checked artifact. |
| G-10 | `packs/internal/` still excluded (FR-044). |
| G-11 | Producer gates (client-no-logic with the FR-027 Python surface, no `disabled` class in the fault enum, persisted-line schema, lane list, retention-no-tracked-write, seam census, single writer, no committed schema source), each under the floor rule. |
| G-12 | The dist set equals the pinned expected list (AC-037): a missing platform cannot degrade silently to the sdist. |
| G-13 | Java dependency checksum verification, reusing the pattern of `contracts/gradle/verification-metadata.xml`. |

**Omitted (out of scope):** signing and notarization (OD-SIGN): the macOS and Windows binaries ship unsigned, so Gatekeeper or SmartScreen may warn or block a first run until a later mission adds signing; extra arches and libc variants (OD-MATRIX).

---

## 9 Governance drift (flagged; nothing resolved here)

Detail: carryover D. (1) Charter "Python 3.11+" versus the Java producer: Python only for the shim (and the flagged FR-035 stamping). (2) Charter forbids vendoring events source (met by FR-039's build-time consumption, OD-SCHEMA-DELIVERY). (3) ADR 2026-10-13-2 is departed from at D-1, D-2, D-3 (FR-045). (4) to (7) flagged, not amended.

---

## 10 Resolved decisions

All former open decisions are ruled by the operator; each now lives in the requirement named.

- **OD-SINK**: (a) publish log now; Python ledger writers authoritative; committed-ledger writing at #6193/#6195 (FR-001, FR-020, C-004, D-SINK).
- **OD-JAVA**: (a) standalone Gradle module, structured for #6192/#6193 to absorb (FR-039).
- **OD-JOURNAL**: (a) the Python run-journal writer stamps a ULID `event_id` and row version; the one flagged Python exception (FR-035, FR-027).
- **OD-SCHEMA-DELIVERY**: (a) build-time consumption of the released events artifact, pinned and checksummed (FR-039, FR-040, AC-027).
- **OD-FALLBACK**: (a) a pure `py3-none-any` fallback wheel (FR-043, AC-024, AC-037).
- **OD-MISSIONID**: (b) the ADR 0001 fallback: project-scoped `source`, `subject` = the aggregate, no `missionid`, no fault; an optional `doctor` note may name `migrate backfill-identity` (FR-009, AC-005).
- **OD-CLOCK**: (a) anchored at the git common directory (FR-012, FR-019, AC-023).
- **OD-NODEID**: (a) derived in Java from the same inputs and hash (FR-011, AC-039).
- **OD-SPAWN**: (a) one spawn per row, measured (FR-001, AC-036); batching is a plan contingency (NFR-002).
- **OD-RETENTION**: 20 runs and 30 days confirmed (FR-028).
- **OD-RETAIN**: a Java retention operation; `doctor` is a thin pass-through (FR-030, FR-026).
- **OD-RETAIN-FACTS**: the producer derives "Mission open" and unfinished steps itself, read-only and minimal (FR-029, FR-039, D-REDUCER, data-model section 6).
- **OD-SIGN**: no signing or notarization in this mission (section 8, Omitted).
- **OD-MATRIX**: the FR-041 floor only (FR-041, section 8).
- **OD-OPTOUT**: (a) no `disabled` class; a gate asserts it (FR-022, FR-023, G-11, AC-005).
- **OD-EVENTS**: a constraint (C-008).

---

## 11 Risks

Carryover K.

---

## 12 Reflexivity and mid-flight

This mission's own events come from the Python writers; its gates never depend on the producer. Mid-flight Missions: FR-038, AC-017.

## 13 Glossary additions (Contextive regeneration required)

New terms (none defined in `docs/context/`): **Producer**, **Publish seam**, **Publish log**, **Ledger**, **Fan-out** (not "sync"), **Clockless row**, **Clocked row**, **Fault log**. Definitions: carryover L; then `python scripts/generate_contextive_glossaries.py generate` and `check`.
