# Mission Specification: Reroute mission lifecycle events to produce spec-kitty-events (local publish seam)

**Mission**: `event-producer-reroute-01M4VDY9` | **Issue**: spec-kitty#6185 (P1, 4.0.0 milestone) | **Branch**: `feat/event-producer-reroute-6185` | **Created**: 2026-10-13 | **Status**: Draft (spec phase)
**Companion**: [`data-model.md`](data-model.md) (entities, type table, total-order key, trace). Requirements and acceptance only; HOW belongs to the plan, which reads [`plan-carryover.md`](plan-carryover.md).

**Assumptions in force** (open ones: section 10): (1) a Java producer behind a thin Python client; (2) Python writers stay authoritative for the committed ledgers, the producer appends to a gitignored publish log (D-SINK, OD-SINK); (3) either landing order with #6164 works (D-6164); (4) INTERIM marks an adopted-but-unconfirmed ADR 0001 choice.

---

## 1 Purpose and scope

**Purpose.** The only code that builds a `spec_kitty_events` `Event` is `status/zeitgeist_bridge.py`, on the hosted drain path (off by default, slated for removal, #6164), so Mission lifecycle and status events reach no integration on a default install. This mission gives them a local, on-by-default publication path separate from `hosted.drain`, with a versioned per-row-kind contract in `spec-kitty-events` (ADR 2026-10-13-2).

**In scope.**
1. A Java producer, shipped as a native binary in platform-tagged wheels, that turns each persisted ledger row into one CloudEvents 1.0 structured-JSON row appended to a gitignored local publish log.
2. A thin Python client registered on the existing post-persistence fan-out seam (`status/adapters.py`), firing irrespective of drain.
3. One `spec-kitty-events` PR (separate repo, released first): the FR-014 list.
4. The defined total order for logs mixing clocked and clockless rows (section 3, data-model).
5. Fault handling that never fails a lifecycle command and is never silent.
6. Retention for the gitignored local logs; committed ledgers are never pruned.
7. Clearing the stale #1198 and #1248 `canonical-producer-exempt` markers; removing lane-list duplication; carrying `reason_source`.
8. Platform wheels and the release gates they need.
9. An ADR amendment recording the CloudEvents ruling, the added read path and the added local-tier files (section 9).

**Out of scope** (original wording: `plan-carryover.md` section Q). CloudEvents in committed ledgers, retiring the Python writers, lock interop, committed-log appends (#6193, #6195, #6196); any change to `status/reducer.py`, the Java read model or `status/event_log_merge.py`; the envelope schema, validator, upcaster, STRICT types, bindings, events-charter amendment (events#92); glossary rows (#1200); backfill; retiring `3.0.0` (ADR 3b); renaming the `saas_fanout` slots; hosted behaviour.

---

## 2 Context and evidence (verified against this checkout)

Full table: `plan-carryover.md` section E (E1 to E15, cited by id). Load-bearing: **E2** the seam has three slots, all `fire_*` return unless the drain posture is enabled, handlers run bounded (`_DEFAULT_SAAS_FANOUT_TIMEOUT_S`), a busy handler is skipped, failures are logged; `RuntimeMomentProducer._publish` has its own drain read. **E14** the `repo_root` keyword of `fire_*` is a starting path, not a root. **E15** `status/emit.py::_load_mission_id` returns None for older Missions.

---

## 3 Decisions encoded

**Operator decisions (binding; never re-opened here).**

1. Taken at the maintainer's request. 2. Strangler: Java producer, thin Python client only, as #6117 (FR-001, FR-026, FR-027, FR-039). 3. CloudEvents 1.0 structured JSON now (ADR 0001 q3a, option A); 3b not ruled: legacy rows stay readable, upcast on read, never rewritten (FR-006, FR-036, FR-037). 4. Ordering option C: `lamportclock` + `nodeid`, ordered Lamport, `time`, `id`, `(time, id)` fallback; ULID-only new ids; prefix `ai.spec-kitty` (FR-007 to FR-013). 5. Platform wheels bundling the native binary (FR-041 to FR-044). 6. Fault mode: degrade with a recorded warning, never silent (FR-022 to FR-025). 7. One schema set, one writer per type; row schemas ARE the `data` schemas (FR-014, FR-015, FR-034, section 7). 8. Public hygiene (NFR-006).

**INTERIM** (ADR 0001 "further choices"; the events PR confirms each; none is hardened silently): snake_case `data` members; `actor` and `executionmode` as attributes; `.vN` versioning; `(source, id)` dedupe key; type names; the lifted-member set; the relative `source` form; the `dataschema` base URI.

**Repo-evidence decisions (to confirm).**

| Id | Decision | Evidence | Confirmation |
|---|---|---|---|
| D-SINK | The producer is an adapter on the post-persistence seam; Python writers stay authoritative; the producer appends to a gitignored publish log. | E2; ADR 2026-10-13-2 D-2. | OD-SINK |
| D-REDUCER | `status/reducer.py` is untouched; the order is delivered as comparator spec plus fixtures in the events PR; the sort-key change is handed off (section 7). | E9; ADR 2026-10-11-1. | none |
| D-6164 | The seam fires the producer irrespective of drain, so both landing orders satisfy the requirements. | E2 | AC-003 |

**The total order (requirement; formal in data-model section 4).** Key `(has_clock, lamportclock, time, id, source)` over normalized ids, applied to upcast-form rows after dedupe on `(source, normalized id)`; `source` is a final tie-break, a refinement of the ruled key. Error rows are surfaced by name, never dropped. Sink-agnostic (OD-SINK).

---

## 4 Functional requirements

Each line is testable; rationale per FR: `plan-carryover.md` section R.

**A. Seam and coverage**
- **FR-001** The CLI registers a thin client on the existing fan-out seam that hands every in-scope persisted row to the Java producer on a default install, with no setting required. Spawn granularity is OD-SPAWN; each row yields at most one publish-log row.
- **FR-002** The seam fires the producer irrespective of `hosted.drain`: the drain gate moves to the hosted (Zeitgeist) handlers (none after #6164); `RuntimeMomentProducer._publish` drops its own drain read; the client registers outside the `moment_handlers_disabled_reason` branch of the `status/adapters.py` import tail and ignores the hosted kill switches; the ADR's confirmation pin and the reset helpers (`reset_handlers`) move with the gate and re-register the client. Neither reads the drain posture.
- **FR-003** Every in-scope persisted row (data-model section 3) reaches the seam exactly once. E4 gaps close: a plain `InnerStateChanged` annotation gets its own seam call; persist-only lifecycle callers and the retrospective writers get one where their row is persisted or at their existing fan-out call. The client registers on the `status` and `lifecycle` slots only: the resolved-binding slot projects rows that already reach the client. The census gate (AC-004) fails when a persisted ledger row kind has no seam call.
- **FR-004** At each seam call the persisted row (as written to the authoritative ledger, after sanitization) is handed to the client as one opaque mapping, additive to the existing keyword arguments, with a starting path as the call site gives it (E14). The PRODUCER resolves the canonical repository root from it (`core.paths.resolve_canonical_root`), so a lane worktree, a coordination worktree and the primary checkout reach one publish log; the client repeats that only to place the fault log (FR-026). An unresolvable start is fault class `rejected` (`root-unresolvable`), never a silent return; with no root the warning is stderr only.
- **FR-005** Nothing the producer emits exceeds what the persisted row holds. It adds no member the authoritative row lacks, other than the envelope attributes of data-model section 1 and the one stated rename (`actor` to `data.actorbinding` for a structured actor, data-model section 3).

**B. Envelope and ids**
- **FR-006** Each published row is one CloudEvents 1.0 structured-JSON object per line, `datacontenttype` `application/json`, `type` prefixed `ai.spec-kitty`, `dataschema` naming the committed `data` schema of its type.
- **FR-007** `id` equals the authoritative row's `event_id`; the producer never mints a different id for a ledger row. It accepts only a 26-character ULID (written upper-case). Any UUID form, hyphenated or 32-hex, is refused with fault class `rejected`. UUID normalization belongs to readers and the order key for legacy rows.
- **FR-008** `time` is the authoritative row's own timestamp (`at` or `timestamp`) as an RFC 3339 instant. The producer never synthesizes a time; a row without a parseable time is refused (`rejected`).
- **FR-009** `source` is `/spec-kitty/missions/<missionid>` for Mission-scoped rows and `/spec-kitty/projects/<project_uuid>` for project-scoped rows (INTERIM). `missionid` comes from the row, else from the Mission's `meta.json` read by the producer. If neither yields it (E15) OD-MISSIONID decides: (a) the row is refused as `rejected` with FR-023's per-event warning, or (b) it is published with the project source and no `missionid`, and no fault. The command succeeds either way and the event is never silently lost. A slug is never presented as a mission id.
- **FR-010** Context attribute names match `^[a-z0-9]{1,20}$`; values use only CloudEvents attribute types; absent values are omitted, never `null` (conformance test).

**C. Ordering and clock**
- **FR-011** The producer stamps `lamportclock` (Integer) and `nodeid` (String) on every row. `nodeid` equals `identity/project.py::generate_node_id()` for that machine and user (E10): the same inputs and hash, verified by a golden test on each OS (AC-039); how the producer obtains the inputs is OD-NODEID.
- **FR-012** The clock is a strictly increasing counter in a gitignored state file the producer owns, shared by every worktree of one repository under one `nodeid` (OD-CLOCK), so no two events share a `(nodeid, lamportclock)` pair. Stamp and append are one exclusive operation; a kill leaves no advance and no line, or a gap, never a reused value. After state loss the first stamp exceeds the highest clock readable in the publish log; the state is never pruned while its log exists. The clock never exceeds 2^31-1: on overflow the event fails (`clock-overflow`), no wrap. Merging other nodes' clocks is #6193's.
- **FR-013** The spec's total order (section 3) is delivered as a reference comparator specification and conformance fixtures in the events PR, including the non-transitive counter-example, the dedupe-then-order rule and the error rows. No CLI reducer changes here.

**D. Schemas and types**
- **FR-014** The events PR carries exactly: one versioned `data` JSON Schema per emitted type with its lifted-member list, the registry entries, the `dataschema` URIs, the producer conformance fixtures, the total-order comparator specification and fixtures (FR-013), the `spec_kitty_events.Lane` alignment (data-model section 9) and, conditionally, the minimal envelope subset (FR-035). The envelope schema, validator and upcaster are #92's. Section 7 lists each item for #92 to acknowledge.
- **FR-015** Every row the producer appends validates against its type's `data` schema and the envelope attribute rules before it is written; a row that fails is refused (`rejected`), not written.
- **FR-016** Re-firing the seam for a ledger row already published (same `(source, id)`) leaves one row in the publish log.
- **FR-017** The type table (data-model section 3) states per type the legacy source, writer, emission point, scope, `subject`, lifted members and status. In scope: lane transition, annotation, the `status/lifecycle_events.py` lifecycle rows, decision opened and resolved, the retrospective rows and the run-journal rows (named by #1248; OD-JOURNAL). Deferred: glossary rows (#1200) and `DecisionPointWidened` (no emitter in `src/`).
- **FR-018** `lane.transitioned.v1` `data` carries the members of data-model section 3 including `reason_source` (optional; `operator` or `synthetic`) and `actorbinding` (only for a structured actor); `actor`, `executionmode`, `missionid`, `missionslug` and `subject` are context attributes. `doing` is resolved to `in_progress`; `reason_source` is never dropped.

**E. The publish log**
- **FR-019** The publish log is gitignored, local tier, one UTF-8 JSON object per line, LF only, no BOM, no embedded raw newline; its location is a plan decision under the local tier and inside retention. A gate asserts `git check-ignore` for the publish log and fault log; for the clock state, `git check-ignore` (a working-tree path) or, under the git common directory (OD-CLOCK a), that it lies outside the working tree and `git add` cannot reach it.
- **FR-020** The producer is the single writer of the publish log (C-004). No Python code opens it for write (gate AC-030); no file shared with a Python writer is introduced.
- **FR-021** A crash mid-append never lets a later append join a torn line to the next row: a torn final line (no trailing LF) is detected by the producer before the next append, left in place and reported in the reply; the client records it in the fault log with the repo-relative log path and line position. Readers are not changed here (FR-036).

**F. Faults**
- **FR-022** A missing binary, crash, timeout, malformed reply, clock overflow, refused request, busy-skip or (only under OD-OPTOUT b) explicit disablement never fails the lifecycle command, never changes its exit code, never changes any `--json` body and never changes an orchestrator-api envelope.
- **FR-023** Each fault records a warning naming event type, event id (if known), reason class (`binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`, plus `disabled` only under OD-OPTOUT b) and "event was NOT produced" (`crash`, `timeout`: "event may not have been produced"). Carriers: a stderr line in EVERY mode (no `--json` body changes) and an append-only gitignored fault log (retention applies); if the log cannot be written, stderr still carries the warning and the command succeeds. The client is the fault log's single writer; the producer's notices travel in its reply. One warning per event, not per retry. The fault log sits under the local tier at the canonical root, beside the publish log (location: plan decision, FR-019); with no root the warning is stderr only. The client is the outermost catch; the seam's busy-skip and `TimeoutError` paths for the producer handler call the client's fault recorder.
- **FR-024** A degraded repeated state stays visible: `doctor` reports the fault count (fault-log rows in the retention window; success does not reset it, only retention reduces it) and the last fault by class.
- **FR-025** The call is bounded: a finite, configurable client timeout strictly below the seam bound `_DEFAULT_SAAS_FANOUT_TIMEOUT_S`, and still applying when the seam bound is disabled (`<= 0`). On timeout the client terminates the child it spawned (by its own handle, never by name pattern) and records `timeout`. One breaker per process: after the first timeout or crash the remaining rows of the command record one fault each without spawning. The default is a plan decision under NFR-002.

**G. The thin client**
- **FR-026** The client may only: locate the platform binary; spawn it; pass the seam's arguments and the FR-004 row, serialized without interpretation; enforce the timeout; read one reply; map any failure to the fault record; append a fault-log row, resolving the canonical root for that location only; register itself on the seam. It must not build or validate an envelope or attribute, mint ids, timestamps or clocks, choose type names or `dataschema` URIs, know the schema, order, dedupe, normalize ids or read the log.
- **FR-027** An architecture gate enforces FR-026 (floor: the client module is found and its call sites counted; a self-mutation test injects a forbidden capability). It also fails if the Python bridge's envelope code (`_broadcast_status_transition`, `_broadcast_lifecycle_envelope`, `_envelope_bookkeeping`) is carried into the client.

**H. Retention**
- **FR-028** Policy keys `ledger.retention.keep_runs` and `ledger.retention.max_age_days` in `.kittify/config.yaml` (proposed 20 and 30, OD-RETENTION). "Run" is defined for each local-tier file the plan places, so N-runs/X-days is well-defined for the publish log.
- **FR-029** An item is eligible only when outside both limits (not among the newest `keep_runs` AND older than `max_age_days`), its Mission is not open (any work package in a lane other than done or canceled), it holds no lock and no step is unfinished (a run journal without `MissionRunCompleted`, or a lock file present).
- **FR-030** Cleanup is an explicit, dry-run-first command in the `doctor` family; it never runs implicitly. A dry run lists what a real run would delete and deletes nothing.
- **FR-031** Cleanup never opens a git-tracked path for write, proven by a non-vacuous gate (AC-014). Committed ledgers are never pruned.

**I. Exemptions, lanes, journal**
- **FR-032** After this mission no `canonical-producer-exempt` marker in `src/` cites #1198 or #1248 except named residuals with an issue reference (data-model section 8). A marker on an authoritative Python writer is cleared only when a producer gate validates the PERSISTED lines that writer wrote against the legacy-row form of the published schema: generated from the single `data` schema plus the lifted-member list (FR-014), never a second authored schema. The gate also validates publish-log lines.
- **FR-033** The eight #1200 markers in `glossary/events.py` stay a named residual in the exemption ledger, neither widened nor cleared.
- **FR-034** Lane membership has one canonical definition, consumed by the `lane.transitioned` data schema. The persisted lane vocabulary excludes `uninitialized`. A census of every full lane-literal list (E6) is the first plan task, and a gate fails when a new full lane list appears.
- **FR-035** The run-journal writer gains a ULID `event_id` and a row version at write time (additive; rows without them stay legacy). If #92's envelope schema has not landed when needed, the events PR carries a minimal envelope subset, strictly a subset of ADR 0001's design, which #92 absorbs.

**J. Compatibility**
- **FR-036** The Python readers are untouched. `3.0.0` and diary rows are read by the events upcaster when it exists; no legacy row is rewritten.
- **FR-037** Until the events upcaster is released CLI readers are unchanged and the producer writes only new rows; the upcaster binds the read side of mixed views, not the producer.
- **FR-038** `kitty-specs/**` files already on `main` are never modified by the producer, and the producer never writes under `kitty-specs/`. A mission open at upgrade keeps working; its publish log starts at the first event after upgrade.

**K. Delivery**
- **FR-039** The producer's Java source lives in a Gradle build in this repository, with a framework-free domain, no dependency on unlanded #5529 code, and a layout #6192 / #6193 can absorb into `event-log` (OD-JAVA). No events-repo schema source, copy or generated binding source is committed here (OD-SCHEMA-DELIVERY).
- **FR-040** Release order: the events PR is released first; then the CLI raises its floor in `pyproject.toml` and extends `tests/contract/spec_kitty_events_consumer/`; no path, git or editable override is merged.
- **FR-041** Release builds produce platform-tagged wheels each bundling exactly one native producer binary, for at least linux x86_64, macOS arm64 and Windows x86_64 (others: OD-MATRIX).
- **FR-042** The native binary is built in CI from a pinned toolchain; its checksum is recorded at build and re-verified against the member inside each published wheel; its provenance is attached to the release. No bit-reproducibility claim is made.
- **FR-043** When no platform wheel matches (including a pure fallback wheel, OD-FALLBACK) or the sdist is installed, the CLI installs and runs and the producer reports `binary-missing`; never an install failure (C-007).
- **FR-044** `packs/internal/` still does not ship, and the wheel's `packs` include stays narrowed to `packs/built-in/`.
- **FR-045** An ADR amendment (or new ADR) records that new publish-log rows are CloudEvents (superseding D-1's "flat rows" for them) and that the publish log, fault log and clock state are a new read path (extending D-2) and new local-tier files (extending D-3's closed list) in ADR 2026-10-13-2.
- **FR-046** The new terms of section 13 are added to the canonical glossary and the Contextive glossaries regenerated (`scripts/generate_contextive_glossaries.py`, CI-enforced).

---

## 5 Non-functional requirements and constraints

- **NFR-001** No network. The producer and client make no network call and need no credential.
- **NFR-002** Latency. The charter requires CLI operations under 2 s. AC-036 fixes a per-command overhead ceiling through `tests/_perf_helpers.py` (relative method); the plan sets the numbers. If per-row spawn cannot meet it, OD-SPAWN (b).
- **NFR-003** Determinism. The same ledger row, clock value and node id produce byte-identical publish-log lines (golden tests from the real serializer, per #4182).
- **NFR-004** Cross-platform: Linux, macOS, Windows 10+; LF-only log; `kernel` path helpers; no shell quoting in the spawn.
- **NFR-005** Rigour. Java domain code (ids, clock, mapping, order, retention eligibility) carries tiered rigour above glue; the plan sets coverage floors.
- **NFR-006** Public hygiene. No absolute home paths, usernames or credentials in any shipped file, fixture, log row or fault record; fault records hold repo-relative paths only.
- **NFR-007** Exactly-once is not claimed: the guarantee is at most one row per `(source, id)` and never silent when not produced.
- **C-001** No hand edit of spec-kitty state files. **C-002** orchestrator-api envelopes and contract version unchanged (`orchestrator_api/envelope.py`, #5231). **C-003** Drain gate unchanged in meaning. **C-004** One writer per type and per file: Python writes the ledger row, the producer the publish-log row; at #6193/#6195 Java writes both and the publish log becomes the ledger or is retired (decided there). **C-005** Terminology: Mission, work package. **C-006** No version numbers assigned in scope; "4.0.0 milestone" names the issue milestone only. **C-007** Building from the sdist or an editable checkout never requires the native binary (any build hook is conditional).

---

## 6 Acceptance criteria

Each states the observable and the falsifier; brackets name the requirements verified (every FR, NFR and C appears, bar C-001, C-006). Tests are committed RED first. **Floor rule:** every census or enumeration gate (AC-004, 008, 009, 012, 014, 015, 019, 020, 024, 029, 030, 037, 039) asserts a floor (what it must find before judging) and has a self-mutation case, named in its Falsifier column, that must fail it; a gate that scans nothing fails.

| AC | Observable | Falsifier |
|---|---|---|
| AC-001 [FR-001, FR-004, NFR-001] | Default install, drain off, networking denied: a lane transition appends one publish-log line whose `id` is the ledger row's normalized `event_id`; fired per slot from a lane worktree and a Mission subdirectory, one publish log receives it. | Registration reverted; a second log; a silent return on an unresolved start. |
| AC-002 [FR-006, FR-010, FR-015, FR-017, FR-018] | Per in-scope type, the line validates against its `data` schema and the envelope rules. | Schema drift; an underscored or over-long attribute name; a `null` attribute. |
| AC-003 [FR-002, C-003] | Both orders of #6164: (a) Zeitgeist handlers registered, drain off: a test adapter and the producer fire, no network call; (b) without `zeitgeist_bridge.py` and `hosted_posture` the producer fires; (c) a run-journal row via `RuntimeMomentProducer` yields a line; (d) each hosted kill switch set: the producer fires; (e) reset helpers re-register the client; the pin test covers every Zeitgeist handler. | A drain read; a dangling bridge import; (c) to (e) fail when their FR-002 part is reverted. |
| AC-004 [FR-003, FR-004, FR-017] | Writers derive from data-model section 3 and the persisted row kinds: the ledger's non-lane set (`AUTHORITATIVE_NON_LANE_EVENT_TYPES`), retrospective, lane and annotation rows and the run-journal kinds of `runtime/next/_internal_runtime/events.py`; no hand list; floor = the in-scope writers of section 3. Per writer one forced row yields one seam call carrying the row; one annotation row yields one client invocation. | An injected writer persisting with no seam call; a persisted kind in no list; a truncated set. |
| AC-005 [FR-007 to FR-009, FR-015, FR-022, FR-023, NFR-007] | One test per class (`binary-missing`, `crash`, `timeout`, `malformed-reply`, `clock-overflow`, `busy`; `rejected` for a 32-hex id, a hyphenated UUID id, a bad time, an unresolvable root, a schema-invalid row and, only under OD-MISSIONID (a), a Mission without `mission_id` (under (b): published, project source, no fault); `disabled` only under OD-OPTOUT b, else an enum-equality test): exit code and `--json` body byte-identical to the no-fault run; ledger row present; one stderr warning in human and `--json` mode (type, id, class, FR-023 sentence); one fault-log row (none for an unresolvable root); fault log unwritable: stderr still appears, command succeeds. | A raise, changed exit code or body, class-less warning; a vacuous test. |
| AC-006 [FR-025] | A stub outliving the bound is terminated by handle; the command returns within the fan-out bound; no child remains. A batch of N rows with a hanging stub spawns once, then records N faults. With the seam bound disabled the client timeout still ends the stub. A seam busy-skip and `TimeoutError` each yield a fault row. | An orphan; N spawns; a seam drop with no fault row. |
| AC-007 [FR-024] | After N faults `doctor` reports the fault-log rows in the window and the last class; none in the window reports zero; a success does not reset the count. | A silent `doctor`; a reset by success. |
| AC-008 [FR-013] | Permutation test over the eight data-model section 4 fixtures (example 4 is the naive counter-example; floor: all eight) against a test-local reference comparator that implements section 4 (the events PR delivers its specification; once released at the floor, its comparator is cross-checked on the same fixtures): one result for every permutation (dedupe on normalized ids, comparison by code point). Mutations that must fail: the naive comparator (example 4), a comparator without `source` (example 7), dedupe on raw ids (example 8). | A naive comparator; one without `source`; raw-id dedupe. |
| AC-009 [FR-013] | Error rows (unparseable `time`, non-normalizing or missing `id`, clock out of range) are listed with line position, absent from the ordered list; ordered + errors + collapsed = rows read, with a duplicate `(source, normalized id)` of differing payload (named conflict) in the fixture; legacy run-journal rows stay outside the order. Floor: one fixture per error class; mutation: a comparator that drops an error row must fail. | A dropped or silently sorted row; a wrong count. |
| AC-010 [FR-012] | A primary checkout plus two worktrees, N concurrent processes: no `(nodeid, lamportclock)` pair repeats and the clock strictly increases per node. Log deleted, state kept: next stamp higher. State deleted, log kept: next stamp above the log's highest clock. Kill (injected between stamp and append), rerun: the next stamp is above every clock in the log and equals none ever written to a line (a gap is permitted, no fault is required, FR-012). At 2^31-1: `clock-overflow`, no wrap. | A repeated pair, decrease or wrap. |
| AC-011 [FR-016, NFR-007] | Firing the seam twice for one ledger row leaves one line. | Two lines. |
| AC-012 [FR-026, FR-027] | The client gate passes the real client and FAILS a mutated copy adding an id mint, timestamp, schema import, log read or the bridge's envelope code; floor: client module found, call sites above zero; the client's own fault-log append and root resolution (FR-026) pass. | A pass on the mutated copy; zero modules. |
| AC-013 [FR-028 to FR-030] | Open and closed Missions, locked and unlocked items, unfinished steps: a dry run lists exactly the eligible items and deletes nothing; a real run deletes only those; at least the newest `keep_runs` and any item within `max_age_days` are kept. | Deleting an open Mission's or locked file or a young item; any delete in dry run. |
| AC-014 [FR-031] | With every tracked ledger offered as a candidate (floor: at least one tracked ledger offered), zero writes to tracked paths; self-mutation adds a tracked path to the eligible set. | A fixture with no tracked ledger. |
| AC-015 [FR-036, FR-037] | Reading every archived Mission's `status.events.jsonl` with the CLI readers (and the upcaster once released) leaves each file byte-identical; floor: at least one read; mutation: a reader that rewrites one byte must fail. | A changed byte; zero files. |
| AC-016 [FR-038] | The producer on a scratch repo changes no `kitty-specs/**` file on `main` and creates none. | Any diff. |
| AC-017 [FR-038] | A Mission open before upgrade keeps its ledger byte-unchanged; its publish log starts at the first post-upgrade event. | A backfilled row or changed byte. |
| AC-018 [C-002] | orchestrator-api contract tests pass unchanged; no affected `--json` body changes (snapshot). | Any envelope or body diff. |
| AC-019 [FR-032, FR-033] | No marker in `src/` cites #1198 or #1248 except named residuals; #1200 stays x8. The gate validates (i) publish-log lines, at least one per in-scope type from the AC-002 fixtures, and (ii) one row per cleared marker written by the real writer, in legacy-row form (floor: schemas resolved, cleared-marker count; mutation: a schema-invalid line must fail). | A left marker; an empty schema set; a schema-invalid persisted line that passes. |
| AC-020 [FR-034] | The lane list consumed by `lane.transitioned` is defined once; the gate first finds the census lists of data-model section 9 (floor), then fails on a newly added full list (mutation: an injected list must fail); `uninitialized` is rejected; `doing` appears as `in_progress`. | A second definition; an accepted `uninitialized`; a scan finding nothing. |
| AC-021 [FR-018] | `reason_source` round-trips: operator-note cancel `operator`; bare forced cancel `synthetic`; absent stays absent. | A dropped or defaulted value. |
| AC-022 [FR-035] | Run-journal rows carry a ULID `event_id` and version; a journal without them is still read. | A rejected old journal; an id-less new row. |
| AC-023 [FR-019] | After fresh `init` and after upgrade, `git check-ignore` succeeds for the publish log and fault log, and the clock state passes the FR-019 test for its placement. | An unignored or tracked path; clock state reachable by `git add`. |
| AC-024 [FR-041] | Release verification enumerates the built wheels and fails unless the three minimum platform tags (and the pure wheel iff OD-FALLBACK a) are present; mutation: one required tag removed must fail. Per platform wheel: binary at the single known path, executable, correct tag, exactly one; a by-path clean-venv install passes the smoke test on its OS. | Missing or extra binary; wrong tag; smoke failure; a removed matrix entry. |
| AC-025 [FR-043] | Clean venv with no binary (sdist, pure wheel or no matching wheel): a lifecycle command succeeds and warns `binary-missing`. | An install failure; a silent run. |
| AC-026 [FR-021] | Truncate the publish log mid-line, append: the torn line stays, flagged; the new line is intact; the fault log holds a torn-line row naming the repo-relative path and line position. | A joined line; an unflagged torn line. |
| AC-027 [FR-014, FR-039, FR-040] | CLI consumer tests pin the new schema surface and the floor; removing a schema in the events repo breaks them; no events-repo schema source is committed here. | A test that does not break; a committed schema source. |
| AC-028 [FR-039, NFR-005, C-005] | `ruff check`, `ruff format --check`, mypy on the client; Java build, unit tests, ArchUnit (framework-free domain); terminology guard. | Any red gate. |
| AC-029 [FR-005] | Per type, the key set of `data` is a subset of the persisted row's keys (for lifecycle rows, of its `payload`) plus the lifted-member rules of data-model section 3 (`actorbinding` only for a structured row `actor`, equal to it). Floor: every type of section 3 resolved; mutation: an injected member must fail. | A member injected from `meta.json` or the environment. |
| AC-030 [FR-020, C-004] | No Python module opens the publish log path for write (floor: path constant and scanned modules found). | An injected Python `open` for write. |
| AC-031 [FR-028] | Invalid or non-numeric `keep_runs` or `max_age_days`: a named error, nothing deleted, also on a dry run. | A silent default; any delete. |
| AC-032 [FR-042] | The checksum recorded at binary build, recomputed from the member inside each published wheel, matches; provenance attached. | A binary swapped after build. |
| AC-033 [FR-044, FR-045, FR-046] | No wheel or sdist contains `packs/internal/`; the ADR amendment exists and names D-1, D-2 and D-3; the Contextive check passes with the section 13 terms. | A member under it; a missing file or decision; a removed term. |
| AC-034 [NFR-003, NFR-004, FR-019] | The same row, clock and node id give byte-identical lines on all three OSes (golden file from the real serializer); no BOM, CR or embedded raw newline. | A differing byte. |
| AC-035 [NFR-006] | A scan of fixtures, produced rows and fault records finds no absolute home path or username. | A planted path. |
| AC-036 [NFR-002] | Per-command overhead (cold and warm spawn, per OS) summed over the largest in-scope multi-row command (`emit_status_transition_batch`) stays under a ceiling held in `tests/_perf_helpers.py`. | A planted slow stub. |
| AC-037 [G-1, G-2, G-12] | Release steps, scripts and packaging fixtures iterate the expected wheel set (floor: the pinned list is non-empty); the dist set equals the pinned list (platform wheels, one sdist, the pure wheel iff OD-FALLBACK a); mutation: one wheel removed must fail. | A fixture picking fewer wheels; one wheel removed. |
| AC-038 [C-007, G-7] | A wheel built from the extracted sdist with no binary succeeds; an editable install works without it; the CLI reports `binary-missing`. | A build or install needing the binary. |
| AC-039 [FR-011] | The producer's `nodeid` equals `generate_node_id()` on each OS for the live hostname and user and for fixed injected `hostname:username` strings (floor: the live case plus at least two injected strings); mutation: a changed hash input must fail. | A differing value on any OS. |
| AC-040 [G-5, G-13] | The release SBOM names a known Java dependency and the embedded toolchain; Java dependency verification fails on a changed checksum. | An SBOM unchanged by the binary. |

---

## 7 Dependencies and hand-offs

| Item | This mission delivers | Leaves to | Owner issue |
|---|---|---|---|
| Envelope schema + validator, upcaster, legacy fixtures, STRICT-type registry, bindings, major-bump artifacts, events-charter amendment (flag only, section 9) | the FR-014 list: `data` schemas with lifted-member lists, registry entries, `dataschema` URIs, producer fixtures, total-order comparator spec + fixtures, `spec_kitty_events.Lane` alignment; fallback minimal envelope subset (#92 to acknowledge each) | all the rest; the reducer sort-key switch (Phase 2) | events#92 |
| Event-log write side | first Java producer: ULID id, clock stamping, local publish log, retention, wheels | lock-interop spike, byte-identical append proof, committed-log appends, commit step, temporary Python bridge | #6193 |
| Command-side write core; lane cut-over; merge driver; reducer sort key | only the defined order and fixtures | Java lane state machine (#6194); CloudEvents into committed ledgers, retiring Python writers, CLI reducer switch, schema bump, CloudEvents-aware dedupe and fork detection in `status/event_log_merge.py` (#6195, #6196) | #6194, #6195, #6196 |
| Removal of hosted/sync/drain, `zeitgeist_bridge.py` | the seam works either order | the removal | #6164 |
| `domain-model` / `event-log` modules | liftable producer layout | extraction | #6192 |

**Merge order with #6164:** whichever lands second rebases; the only shared file is `status/adapters.py`. Writers at cutover: C-004. Tooling constraints the plan must respect: `plan-carryover.md` section T.

---

## 8 Release and gates

Gates; the machinery each extends and the reasons: `plan-carryover.md` sections G and Z.

**Per-wheel rule.** Every release step, script and test that globs or asserts one wheel becomes per-wheel over the expected set (machinery: carryover G). The binary sits at one known in-package path under an existing known prefix, or the prefix tuple of `test_wheel_contains_only_known_packages` is amended deliberately.

| Gate | Requirement and the machinery it extends |
|---|---|
| G-1 | Per platform wheel: binary present, executable, platform tag, no extra binary. |
| G-2 | Exact installability from each built wheel. |
| G-3 | Install the built wheel by path into a fresh venv on each OS and run the smoke test (one valid row appended): nightly, on PRs touching the producer's Java tree, packaging config or client (a named router group), and in `release.yml` before publish. New jobs, not `ci-windows.yml`. |
| G-4 | `twine check` on every wheel. |
| G-5 | The release SBOM lists the Java dependencies and the toolchain embedded in each binary. |
| G-6 | `uv lock --check`, the only dependency gate in CI today. |
| G-7 | Sdist and editable builds succeed without the binary; install runs and reports `binary-missing` (FR-043, C-007). |
| G-8 | Provenance and pinned toolchain; checksum continuity from build to each published wheel (FR-042). |
| G-9 | Red-CI-no-release (Standing Order 9): `build-release` becomes a per-OS build stage feeding one assembling job; `publish-pypi` needs the assembled, set-checked artifact, so a failed or cancelled leg blocks publish. |
| G-10 | `packs/internal/` still excluded (FR-044). |
| G-11 | Producer gates (client-no-logic, persisted-line schema, lane list, retention-no-tracked-write, seam census, single writer, no committed schema source), each under the floor rule, in `tests/architectural/`. |
| G-12 | The dist set equals the pinned expected list (platform tags, one sdist, the pure wheel iff OD-FALLBACK a): a missing platform cannot degrade silently to the sdist. |
| G-13 | Java dependency checksum verification, reusing the pattern of `contracts/gradle/verification-metadata.xml`. |

**Omitted deliberately:** signing and notarization is an open risk, not a gate (OD-SIGN); extra arches and libc variants are plan decisions.

---

## 9 Governance drift (flagged; nothing resolved here)

Detail: carryover D.
1. Charter "Python 3.11+ for all CLI and library code" versus the Java producer: Java gates have no charter row; Python only for the shim.
2. Charter forbids vendoring events source: Java binding generation crosses that line (OD-SCHEMA-DELIVERY).
3. ADR 2026-10-13-2 is departed from at D-1 (flat rows, superseded for the new rows), D-2 (publish and fault logs: an added read path) and D-3 (its closed local-tier file list gains the publish log, fault log and clock state); the amendment is FR-045.
4. to 7. Events-repo charter wording, Python-only coverage, a stale CLAUDE.md `release.yml` line, no charter row for platform wheels: flagged, not amended (carryover D).

---

## 10 Open decisions

An option marked **amends** contradicts a named requirement and is legal only with that spec amendment, made before the plan relies on it; every unmarked option satisfies every requirement (original wording of the first three: `plan-carryover.md` section Q).

| Id | Question | Options | Recommendation | Blocks |
|---|---|---|---|---|
| OD-SINK | Sink: the gitignored publish log (Python ledger writers authoritative), or the committed ledgers (Java the only writer)? | (a) publish log now; committed-ledger writing at #6193/#6195; (b) write the committed ledgers here (**amends** section 1 out-of-scope, C-004, FR-020). | (a): a missing binary cannot lose authoritative state; the total-order requirement is sink-agnostic. | plan |
| OD-JAVA | Standalone Gradle module now, or wait for #6192's `event-log`? | (a) standalone, structured for absorption; (b) wait (**amends** FR-001, FR-039). | (a). | plan |
| OD-JOURNAL | The run journal has no `event_id`. | (a) Python journal writer adds a ULID `event_id` and row version; (b) defer run-journal rows, #1248 an explicit residual (**amends** FR-017, FR-032, FR-035, AC-003 (c), AC-022). | (a). | plan |
| OD-SCHEMA-DELIVERY | How does the Java build obtain the events-repo `data` schemas without vendoring? | (a) consume the released events artifact at build time (pinned, checksummed, nothing committed); (b) wait for events#92's Java binding; (c) copy here (**amends** FR-039, AC-027, and needs a charter amendment). | (a). | plan |
| OD-FALLBACK | Does a pure `py3-none-any` fallback wheel ship beside the platform wheels? | (a) yes; (b) no, other platforms build from the sdist. | (a): pip and uv fall back with no build step. | plan |
| OD-MISSIONID | A Mission whose `meta.json` has no `mission_id` (E15). | (a) refuse as `rejected` with the per-event warning of FR-023, `doctor` names the repair (`migrate backfill-identity`); (b) ADR 0001 fallback: project source, subject = the aggregate, no `missionid`, no fault. | (b): the publication is not lost. | plan |
| OD-CLOCK | Where does clock state live so worktrees share one counter (FR-012)? | (a) anchored at the git common directory; (b) `nodeid` extended by a checkout discriminator (**amends** FR-011, FR-012, AC-039). | (a); separate clones on one machine stay a residual. | plan |
| OD-NODEID | How does Java obtain the `generate_node_id()` value? | (a) derives it from the same inputs and hash; (b) the request carries it (**amends** FR-026). | (a), with AC-039. | no |
| OD-SPAWN | Spawn granularity against NFR-002. | (a) one spawn per row; (b) one per command batch. | (a), measured; (b) if AC-036 fails. | plan |
| OD-RETENTION | Defaults 20 runs / 30 days. | confirm or change. | confirm. | no |
| OD-SIGN | macOS and Windows signing policy, owners. | not invented here. | operator states. | release |
| OD-MATRIX | macOS x86_64, linux aarch64, libc variants. | include or exclude. | plan, above the FR-041 minimum. | no |
| OD-OPTOUT | Does `disabled` exist (a producer setting independent of the hosted kill switches)? | (a) none; `binary-missing` is the degraded state; (b) a producer setting (adds `disabled`; FR-022, FR-023 and AC-005 already carry it conditionally). | (a); the ADR rejects a publication toggle. Under (a) `disabled` is not in the enum (a gate asserts it). | no |
| OD-EVENTS | Dataschema base URI, lamportclock type, type names, lifted-member lists, minimal-envelope timing. | per #92. | follow #92. | events PR |


---

## 11 Risks

Elaboration: carryover K. Critical path: events PR released, floor bump, CLI producer (#6193). Main risks: spawn cost (NFR-002); unsigned binaries (OD-SIGN); INTERIM choices turn breaking once emitted; seam coverage drift (AC-004); clock ordering (data-model section 4); a missing platform leg is permanent once published (G-12); #6164 conflicts on `status/adapters.py`.

---

## 12 Reflexivity and mid-flight

This mission's own events are written by the Python writers; it never depends on the producer to pass its own gates. Mid-flight Missions: FR-038, AC-017.

## 13 Glossary additions (Contextive regeneration required, CI-enforced)

New terms (none defined in `docs/context/`): **Producer**, **Publish seam**, **Publish log**, **Ledger**, **Fan-out** (not "sync"), **Clockless row**, **Clocked row**, **Fault log**. Definitions: carryover L; then `python scripts/generate_contextive_glossaries.py generate` and `check`.
