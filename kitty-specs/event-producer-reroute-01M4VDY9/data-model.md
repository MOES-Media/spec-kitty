# Data Model: event-producer-reroute-01M4VDY9

Entities, constraints and invariants for [`spec.md`](spec.md). Wire formats are a plan decision; this file fixes the fields. **INTERIM** marks adopted ADR 0001 choices awaiting confirmation. Authority for envelope schema, registry and upcaster is spec-kitty-events#92; a difference between this file and #92 is a defect to reconcile, never a parallel set.

## 1 Envelope attributes (CloudEvents 1.0 structured JSON)

Name rule: lowercase letters and digits, at most 20 characters (`^[a-z0-9]{1,20}$`). Values are CloudEvents attribute types only; an absent value is omitted, never `null`.

| Attribute | Kind | Type | Req | Source on the producer | Name check |
|---|---|---|---|---|---|
| `specversion` | core | String | yes | constant `1.0` | 11 ok |
| `id` | core | String | yes | authoritative row `event_id`, normalized (section 2) | 2 ok |
| `source` | core | URI-reference | yes | `/spec-kitty/missions/<missionid>` or `/spec-kitty/projects/<project_uuid>` (INTERIM, relative form) | 6 ok |
| `type` | core | String | yes | `ai.spec-kitty.<name>.v<N>` (section 3) | 4 ok |
| `subject` | optional | String | per type | work package id (for example `WP01`); omitted for Mission-level events | 7 ok |
| `time` | optional | Timestamp | yes here | authoritative row's own timestamp, RFC 3339 | 4 ok |
| `datacontenttype` | optional | String | yes here | constant `application/json` | 15 ok |
| `dataschema` | optional | URI | yes here | base URI (placeholder, events#92) + type suffix | 10 ok |
| `schemaversion` | extension | String | yes here | the envelope schema version constant defined by events#92 | 13 ok |
| `missionid` | extension | String | Mission-scoped | row, else `meta.json` (never a slug) | 9 ok |
| `missionslug` | extension | String | when known | row `mission_slug` | 11 ok |
| `actor` | extension (INTERIM) | String | when present | row actor; for a structured actor the `actor_identity_str` projection, full object in `data.actorbinding` | 5 ok |
| `executionmode` | extension (INTERIM) | String | lane rows | row `execution_mode` | 13 ok |
| `lamportclock` | extension | Integer | yes | per-node counter (section 7), range 0..2^31-1 | 12 ok |
| `nodeid` | extension | String | yes | repo-standard machine identity (`_generate_node_id` form: 12 hex chars) | 6 ok |

Extension attributes are defined by our own schema. `data` members stay snake_case (INTERIM); the name rule does not apply inside `data`. A structured value never becomes a context attribute.

## 2 Rules for `id`, `time`, `source`, dedupe

- **`id`**: ULID, 26 characters, upper-case, for every new event. The producer uses the ledger row's `event_id` after normalization (ULID upper-cased; UUID lower-cased and hyphenated). It never mints a replacement. A non-ULID ledger id (the 32-hex fallback in `lifecycle_events._generate_event_id`) is refused: fault class `rejected`, event not produced.
- **`time`**: the row's own instant (`at` on lane, annotation, decision rows; `timestamp` on lifecycle and run-journal rows) parsed as RFC 3339, written in UTC. Never synthesized. Unparseable or absent: `rejected`.
- **`source`**: Mission-scoped rows `/spec-kitty/missions/<missionid>`; `ProjectInitialized` `/spec-kitty/projects/<project_uuid>` (INTERIM). `missionid` is a ULID; when neither the row nor `meta.json` yields one, `rejected`.
- **Dedupe key** `(source, id)` (INTERIM). Within one Mission's log every row has the same `source`, so it equals `id`-only dedupe there. A second append for an existing key is a no-op.
- **Join invariant**: for every publish-log line there is at most one ledger row with the same normalized `event_id`, and every in-scope ledger row has at most one line (at most, not exactly: faults produce none, and the fault log says so).

## 3 Type table (all names INTERIM; registry authority is events#92)

Data members are the persisted row's members moved verbatim except where noted. "Seam" is the existing call that reaches the producer; "NEW" marks a gap FR-003 closes.

| Legacy row | Type | Scope / `subject` | `data` members (row members moved verbatim unless noted) | Emission point (writer: Python) / seam | Status |
|---|---|---|---|---|---|
| Lane transition (`StatusEvent`) | `lane.transitioned.v1` | Mission / `wp_id` | `from_lane`, `to_lane`, `force`, `reason`, `reason_source` (optional), `review_ref`, `evidence`, `review_result`, `policy_metadata`, `actorbinding`. Lifted to attributes: `event_id`, `at`, `mission_slug`, `mission_id`, `wp_id`, `actor`, `execution_mode` | `status/emit.py::emit_status_transition`, `emit_status_transition_batch`, coordination shell; seam `fire_saas_fanout` (`_saas_fan_out`, `coordination/outbound.py`) | in |
| Inner-state annotation (`InnerStateChanged`) | `wp.innerstate.changed.v1` | Mission / `wp_id` | `delta`, `actorbinding`; lifted `event_id`, `at`, `wp_id`, `actor`; `missionid`/`missionslug` from `meta.json` | `emit_inner_state_changed` (+ transactional sibling); seam NEW | in |
| `ProjectInitialized` | `project.initialized.v1` | Project / none | `project_slug`, `runtime_version`, `initialized_at` (`actor` as attribute) | `emit_project_initialized` to `.kittify/canonical-events.jsonl`; lifecycle slot | in |
| `MissionCreated` | `mission.created.v1` | Mission / none | `mission_id`, `mission_slug`, `mission_number`, `mission_type`, `target_branch`, `wp_count`, `actor`, `friendly_name`, `purpose_tldr`, `purpose_context`, `created_at` | `emit_mission_created_local`; caller fans out via `fanout_lifecycle_event_hosted` (`core/mission_creation_commit.py`) | in |
| `Specify/Plan/TasksStarted` | `mission.<phase>.started.v1` (3) | Mission / none | `mission_slug`, `mission_number`, `actor`, `at`; local-only `artifact_path` kept (the hosted projection strips it, the local log keeps it) | `emit_artifact_phase_local` / `emit_artifact_phase`; persist-only path is a census item | in |
| `Specify/Plan/TasksCompleted` | `mission.<phase>.completed.v1` (3) | Mission / none | Started members plus `artifact_path`, `summary`; `wp_count` on tasks | same | in |
| `WPCreated` | `wp.created.v1` | Mission / `wp_id` | `mission_slug`, `mission_number`, `wp_id`, `wp_title`, `wp_path`, `depends_on`, `actor`, `created_at` | `emit_wp_created_local`; lifecycle slot | in |
| `ReviewerSelfApproval` | `wp.reviewer.selfapproved.v1` | Mission / `wp_id` | `mission_slug`, `wp_id`, `implementing_actor`, `intended_reviewer`, `failure_reason`, `fallback_approved`, `timestamp` (schema authored in the events PR) | `emit_reviewer_self_approval`; lifecycle slot | in |
| `MissionReopened` | `mission.reopened.v1` | Mission / none | `mission_id`, `mission_slug`, `reason`, `reopened_by`, `reopened_at`, `cleared_merge` | `emit_mission_reopened`; lifecycle slot | in |
| `FollowUpRecorded` | `mission.followup.recorded.v1` | Mission / none | `mission_id`, `mission_slug`, `follow_up_type`, `commit_sha`, `pr_number`, `recorded_by`, `recorded_at` | `emit_follow_up_recorded`; lifecycle slot | in |
| `DecisionPointOpened` | `decision.opened.v1` | Mission / none | `origin_surface`, `decision_point_id`, `mission_id`, `run_id`, `mission_slug`, `mission_type`, `phase`, `origin_flow`, `question`, `options`, `input_key`, `step_id`, `actor_id`, `actor_type`, `state_entered_at`, `recorded_at` | `decisions/emit.py::emit_decision_opened`; `_queue_decision_fanout` (lifecycle slot) | in |
| `DecisionPointResolved` (resolved, deferred, canceled) | `decision.resolved.v1` | Mission / none | `origin_surface`, `decision_point_id`, `mission_id`, `run_id`, `mission_slug`, `mission_type`, `terminal_outcome`, `final_answer`, `other_answer`, `rationale`, `summary`, `actual_participants`, `resolved_by`, `closed_locally_while_widened`, `closure_message`, `state_entered_at`, `recorded_at` | `emit_decision_resolved`; as above | in |
| Run journal: `MissionRunStarted`, `NextStepIssued`, `NextStepAutoCompleted`, `DecisionInputRequested`, `DecisionInputAnswered`, `MissionRunCompleted` | `run.started.v1`, `run.step.issued.v1`, `run.step.autocompleted.v1`, `run.decision.requested.v1`, `run.decision.answered.v1`, `run.completed.v1` | Mission (identity from journal context) / none | payload models of `runtime/next/_internal_runtime/events.py` | `engine._append_event`; `RuntimeMomentProducer` (lifecycle slot). Journal row needs a ULID `event_id` and version (FR-035) | in |
| Run journal: `SignificanceEvaluated`, `DecisionAuthorityDenied` | `run.significance.evaluated.v1`, `run.decision.denied.v1` | as above | payload verbatim | seam NEW (the runtime producer deliberately publishes neither) | in |
| Glossary rows (`src/glossary/events.py`) | n/a | n/a | n/a | n/a | **deferred** (#1200 residual) |
| `DecisionPointWidened` | n/a | n/a | n/a | no emitter found in `src/` | **deferred** |

Rows of types not in this table are never published and never an error: the seam only hands the producer in-scope rows (FR-017).

## 4 The total order (formal)

Let a row `r` have `clk(r)` the integer `lamportclock` if present else absent, `t(r)` its `time` as a UTC instant, `i(r)` its normalized `id`.

```
key(r) = ( h(r), c(r), t(r), i(r) )
  h(r) = 1 if r has a clock else 0
  c(r) = clk(r) if r has a clock else 0
```

Order ascending, lexicographic: `h` (all clockless rows before all clocked), then `c`, then `t` as an instant (not text), then `i` as a normalized string. `nodeid` is not in the key (it is carried for cross-node merging, #6193).

Invariants: **total**, **transitive**, **antisymmetric** (a lexicographic product of total orders) and **input-order independent**. Equal keys are the same event under the dedupe key and collapse to one. **Error rows** (unparseable `time`, or a clock outside 0..2^31-1, or a missing `id`) are not placed in the order: they are returned in a separate error list with their line position and a named reason, never dropped, never silently sorted. Count invariant: `ordered + errors = rows read` (duplicates counted as collapsed, listed).

Known consequence: a clockless row appended after clocked rows sorts first. Guard: only pre-cutover Python writers and old CLIs write clockless rows; the schema bump that stops old CLIs appending is #6195's.

**Why a naive comparator fails.** Naive: "if both rows have a clock compare `(clock, time, id)`, else `(time, id)`" is not transitive (example 4).

### Worked examples (the six fixtures of AC-008 and AC-009)

UTC unless shown.

1. **Two clockless.** A `10:00, id 01B`; B `10:00, id 01A`: `id` decides, B before A.
2. **Two clocked.** A `clock 5, 09:00`; B `clock 4, 11:00`: B before A (clock beats time). Equal clock and time: `id` decides.
3. **Mixed.** A clockless `23:00`; B `clock 1, 08:00`: A before B (`h` 0 < 1).
4. **Naive counter-example.** A `clock 5, 10:00`; B clockless `10:30`; C `clock 3, 11:00`. Naive: C<A (clock), A<B (time), B<C (time): a cycle, result depends on input order. Total order: **B, C, A** for every permutation.
5. **Offsets and id case.** Clockless A `2026-10-13T12:00:00+02:00` (= 10:00:00Z), B `2026-10-13T10:00:00.5Z`: A before B (text order would say B first). Ids `01abc` and `01ABD` compare after upper-casing: `01ABC` < `01ABD`.
6. **Error row.** Clockless A `10:00`, B `time: "yesterday"`, C `11:00`: ordered `[A, C]`, errors `[B at line 2: unparseable time]`; 3 read = 2 + 1.

## 5 Example lines

One example line per kind (lane transition; run-journal row before and after FR-035) is in `plan-carryover.md` section X. Real bytes come from the serializer and golden tests, never hand-written JSON.

## 6 Producer protocol entities (fields; wire format is a plan decision)

**ProduceRequest** (client to producer; assembled by the seam, passed through without interpretation):

| Field | Type | Constraint |
|---|---|---|
| `row` | opaque mapping | the persisted authoritative row, post-sanitization (FR-004) |
| `slot` | enum | `status` \| `lifecycle` \| `resolved_binding`: the seam slot that fired |
| `seam_args` | opaque mapping | the existing keyword arguments (`wp_id`, `actor`, `mission_slug`, `mission_id`, `metadata`, ...), as given |
| `repo_root` | path | the emitting checkout as the seam carries it (never the process working directory) |
| `mission_dir` | path, optional | as the seam carries it, for `meta.json` lookup |
| `deadline_ms` | integer | the client's timeout, informational |

**ProduceReply** (producer to client; exactly one):

| Field | Type | Constraint |
|---|---|---|
| `outcome` | enum | `produced` \| `skipped_duplicate` \| `refused` |
| `event_id` | string | normalized id, present for all three |
| `type` | string | when `produced` or `skipped_duplicate` |
| `reason_class` | enum | when `refused`: `rejected` \| `clock-overflow` |
| `detail` | string | one line, repo-relative paths only (NFR-006) |

**ProducerFault** (recorded, never raised into the command):

| Field | Type | Constraint |
|---|---|---|
| `class` | enum | `binary-missing` \| `crash` \| `timeout` \| `malformed-reply` \| `clock-overflow` \| `rejected` \| `disabled` |
| `event_type` | string | legacy or published type name |
| `event_id` | string or absent | if known |
| `message` | string | contains the sentence "event was NOT produced" |
| `at` | timestamp | client clock; never enters an envelope |

Mapping: an unreadable, truncated, non-JSON or schema-violating reply is `malformed-reply`; a non-zero exit, signal or empty reply is `crash`; no executable for the platform is `binary-missing`; the client's own deadline is `timeout`.

## 7 Local-tier entities

**PublishLogFile.** Gitignored. Unit (per-Mission or per-run file) is a plan decision; it must carry an age (newest row `time` or file mtime) and a run or Mission association so N-runs/X-days is defined. Complete lines ending LF; single writer: the producer. Torn-line rule: a final line without LF is preserved, flagged in the fault log, never joined to the next row. Location: under an already-gitignored path (candidate `.kittify/events/`) or added through the canonical ignore mechanism; AC-023 checks.

**LamportState.** Gitignored state file owned by the producer: per-`nodeid` counter, integer 0..2^31-1. Strictly increasing per node; stamp and append are one exclusive operation across processes; at the bound the next stamp fails (`clock-overflow`), never wraps. Counter only; merging with other nodes' clocks is #6193's.

**FaultLogRow.** Append-only, gitignored, retention applies. ProducerFault fields plus a relative `repo_root` marker and `count_since_clean`. Used by `doctor` (FR-024): fault count, last class, last time.

**RetentionPolicy.** Keys in `.kittify/config.yaml`: `ledger.retention.keep_runs` (positive integer, proposed 20) and `ledger.retention.max_age_days` (positive integer, proposed 30); operator to confirm. Invalid or non-numeric values are a named command error (fail closed), never a silent default. Eligible: outside both limits (not among the newest `keep_runs` and older than `max_age_days`), Mission not open, no lock, no unfinished step. Scope: local tier only (publish log, fault log, run journals, `.kittify/events/`; clock state is never pruned while the log exists). The committed tier is never a candidate.

## 8 Exemption ledger (entity `Exemption`)

Disposition: `clear` = the marker is gone and its writer's persisted line is validated by the published per-kind schema (legacy-row form); `residual` = stays, with a reason and an issue reference to be filed. Per-marker reasons: `plan-carryover.md` section M.

| Marker | File / symbol | Issue | Disposition |
|---|---|---|---|
| 1 | `decisions/emit.py` (`emit_decision_opened`) | #1198 | clear |
| 2 | `decisions/emit.py` (`emit_decision_resolved`) | #1198 | clear |
| 3 | `events/decision_log.py` (git-log envelope) | #1198 | plan decides (clear if covered by the decision schema, else residual with issue) |
| 4 | `migration/mission_state.py` (historical replay builder) | #1198 | plan decides (likely residual-by-design, new issue) |
| 5 | `status/lifecycle_events.py` (function-level, before `_build_envelope`) | #1198 | clear |
| 6 | `status/lifecycle_events.py` (dict literal in `_build_envelope`) | #1198 | clear |
| 7 | `status/lifecycle_events.py` (`build_saas_lifecycle_queue_event`) | #1198 | plan decides (clear if #6164 removes the hosted path, else residual) |
| 8 | `runtime/next/_internal_runtime/engine.py::_append_event` | #1248 | clear (FR-035) |
| 9 to 16 | `glossary/events.py` (8 markers) | #1200 | **residual** (named, not widened) |

Test fixture markers (sanitizer tests cite #1198; lint tests cite #1248) are fixtures, not producers; the plan reviews them. The producer lint (`scripts/lint_canonical_producers.py`) gains a persisted-line mode; its baseline `scripts/canonical_producer_lint_baseline.txt` (37 entries, path and code only) is not widened.

## 9 LaneVocabulary

| Set | Location | Members | Role after this mission |
|---|---|---|---|
| `Lane` (StrEnum) | `status/models.py` | 11: genesis, uninitialized, planned, claimed, in_progress, for_review, in_review, approved, done, blocked, canceled | Canonical authority (source of the schema's lane list). |
| `CANONICAL_LANES` | `status_lanes.py` | 9 (no genesis, no uninitialized) | Derived from the canonical definition, or removed. |
| `spec_kitty_events.Lane` | events package | 10: genesis plus the nine | Aligned with the schema's enumeration in the events PR. |
| `_VALID_LANES`, `_LANE_ORDER` | `migration/rebuild_state.py` | literals (8 and 5) | Census item; plan derives or documents. |
| `_LEGACY_NOT_DONE_LANES` | `acceptance/__init__.py` | includes legacy `doing` | Census item. |
| `_PRE_APPROVED_LANE_VALUES` | `consolidation/done_bookkeeping.py` | 4 | Census item. |

The schema's persisted lane enumeration = genesis, planned, claimed, in_progress, for_review, in_review, approved, done, blocked, canceled. `uninitialized` is excluded (never persisted). The alias `doing` (`LANE_ALIASES`) is resolved to `in_progress` in `data`; the persisted alias form is never emitted. `reason_source` enumeration: `operator`, `synthetic` (`OPERATOR_REASON_SOURCE` and the synthetic default from the move-task hop), optional, absent for non-cancel moves.

## 10 PlatformWheel matrix entity

| Field | Constraint |
|---|---|
| `platform_tag` | wheel platform tag; one wheel per row of the matrix |
| `os_arch` | minimum: linux x86_64, macOS arm64, Windows x86_64; macOS x86_64 and linux aarch64 are plan decisions (OD-MATRIX) |
| `binary_path` | one executable file inside the wheel, executable bit set, name fixed by the plan |
| `binary_checksum` | recorded at build; matches the provenance record (G-8) |
| `toolchain_pin` | pinned native-image toolchain version, recorded |
| `smoke_result` | per-OS CI result (G-3) |
| `signed` | open: OD-SIGN (no policy invented) |
| `sdist` | contains no binary; install runs; producer reports `binary-missing` |

Invariants: exactly one binary per platform wheel; the pure-Python behaviour of the CLI is unchanged when the binary is absent; `packs/internal/` is never in any wheel.

## 11 Requirement-to-entity trace

| Requirement | Entities / sections |
|---|---|
| FR-001, FR-002, FR-003, FR-004 | ProduceRequest (6); type table "Seam" column (3) |
| FR-005 | ProduceRequest `row`; envelope attributes (1) |
| FR-006, FR-010 | Envelope attributes (1) |
| FR-007, FR-008, FR-009, FR-016 | Rules (2) |
| FR-011, FR-012 | LamportState (7); `lamportclock`, `nodeid` (1) |
| FR-013 | Total order and worked examples (4) |
| FR-014, FR-015, FR-017, FR-018 | Type table (3); LaneVocabulary (9) |
| FR-019, FR-020, FR-021 | PublishLogFile (7) |
| FR-022, FR-023, FR-024, FR-025 | ProducerFault (6); FaultLogRow (7) |
| FR-026, FR-027 | ProduceRequest / ProduceReply (6) |
| FR-028 to FR-031 | RetentionPolicy (7) |
| FR-032, FR-033 | Exemption ledger (8) |
| FR-034 | LaneVocabulary (9) |
| FR-035 | Run-journal rows (3, 5) |
| FR-036 to FR-038 | Rules (2); section 4 invariants |
| FR-039, FR-040 | (dependencies; spec section 7) |
| FR-041 to FR-044 | PlatformWheel (10) |
| FR-045, FR-046 | (documents; spec sections 9 and 13) |
