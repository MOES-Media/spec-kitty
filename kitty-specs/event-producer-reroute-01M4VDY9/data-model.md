# Data Model: event-producer-reroute-01M4VDY9

Entities and invariants for [`spec.md`](spec.md). Wire formats are a plan decision; this file fixes the fields. **INTERIM** marks adopted ADR 0001 choices awaiting confirmation. Envelope schema, registry and upcaster belong to spec-kitty-events#92; a difference between this file and #92 is a defect to reconcile, never a parallel set.

## 1 Envelope attributes (CloudEvents 1.0 structured JSON)

Names match `^[a-z0-9]{1,20}$` (all below conform). Values are CloudEvents attribute types only; an absent value is omitted, never `null`.

| Attribute | Kind | Type | Req | Source on the producer |
|---|---|---|---|---|
| `specversion` | core | String | yes | constant `1.0` |
| `id` | core | String | yes | authoritative row `event_id` (section 2) |
| `source` | core | URI-reference | yes | `/spec-kitty/missions/<missionid>` or `/spec-kitty/projects/<project_uuid>` (INTERIM) |
| `type` | core | String | yes | `ai.spec-kitty.<name>.v<N>` (section 3) |
| `subject` | optional | String | per type | work package id; omitted for Mission-level events |
| `time` | optional | Timestamp | yes here | authoritative row's own timestamp, RFC 3339 |
| `datacontenttype` | optional | String | yes here | constant `application/json` |
| `dataschema` | optional | URI | yes here | base URI (placeholder, events#92) + type suffix |
| `schemaversion` | extension | String | yes here | constant from events#92 |
| `missionid` | extension | String | Mission-scoped | row, else `meta.json` (never a slug); see OD-MISSIONID |
| `missionslug` | extension | String | when known | row `mission_slug` |
| `actor` | extension (INTERIM) | String | when present | row actor (`actor_identity_str` projection; full object in `data.actorbinding`) |
| `executionmode` | extension (INTERIM) | String | lane rows | row `execution_mode` |
| `lamportclock` | extension | Integer | yes | per-node counter (section 7), 0..2^31-1 |
| `nodeid` | extension | String | yes | `identity/project.py::generate_node_id()` (12 hex chars), derived per FR-011 |

`data` members stay snake_case (INTERIM); the name rule does not apply inside `data`. A structured value never becomes a context attribute.

## 2 Rules for `id`, `time`, `source`, dedupe

- **`id`**: the ledger row's `event_id`, never replaced, accepted only as a 26-character ULID (written upper-case). Any UUID form (hyphenated, or the 32-hex fallback of `lifecycle_events._generate_event_id`) is `rejected`. UUID normalization (`normalize_event_id` of the events package) is reader-only: the comparator and order key apply it to legacy rows.
- **`time`**: the row's own instant (`at` on lane, annotation, decision and retrospective rows; `timestamp` on lifecycle and run-journal rows), RFC 3339, written in UTC, never synthesized; unparseable or absent is `rejected`.
- **`source`**: Mission-scoped rows `/spec-kitty/missions/<missionid>`; `ProjectInitialized` `/spec-kitty/projects/<project_uuid>` (INTERIM). `missionid` is a ULID; when neither the row nor `meta.json` yields one, OD-MISSIONID decides.
- **Dedupe key** `(source, id)` (INTERIM). Within one Mission's log every row has one `source`, so it equals `id`-only dedupe there. A second append for an existing key is a no-op.
- **Join invariant**: each publish-log line matches at most one ledger row by normalized `event_id`, and each in-scope ledger row has at most one line (faults produce none).

## 3 Type table (all names INTERIM; registry authority is events#92)

"Seam" is the existing call that reaches the producer; "NEW" marks a gap FR-003 closes.

| Legacy row | Type | Scope / `subject` | `data` members (row members moved verbatim unless noted) | Emission point (writer: Python) / seam | Status |
|---|---|---|---|---|---|
| Lane transition (`StatusEvent`) | `lane.transitioned.v1` | Mission / `wp_id` | `from_lane`, `to_lane`, `force`, `reason`, `reason_source` (optional), `review_ref`, `evidence`, `review_result`, `policy_metadata`, `actorbinding`. Lifted to attributes: `event_id`, `at`, `mission_slug`, `mission_id`, `wp_id`, `actor`, `execution_mode` | `status/emit.py::emit_status_transition`, `emit_status_transition_batch`, coordination shell; seam `fire_saas_fanout` (`_saas_fan_out`, `coordination/outbound.py`) | in |
| Inner-state annotation (`InnerStateChanged`) | `wp.innerstate.changed.v1` | Mission / `wp_id` | `delta`, `actorbinding`; lifted `event_id`, `at`, `wp_id`, `actor`; `missionid`/`missionslug` from `meta.json` | `emit_inner_state_changed` (+ transactional sibling); seam NEW | in |
| `ProjectInitialized` | `project.initialized.v1` | Project / none | row members verbatim (Y) | `emit_project_initialized` to `.kittify/canonical-events.jsonl`; lifecycle slot | in |
| `MissionCreated` | `mission.created.v1` | Mission / none | row members verbatim (Y) | `emit_mission_created_local`; caller fans out via `fanout_lifecycle_event_hosted` (`core/mission_creation_commit.py`) | in |
| `Specify/Plan/TasksStarted` | `mission.<phase>.started.v1` (3) | Mission / none | row members verbatim (Y); local-only `artifact_path` kept | `emit_artifact_phase_local` / `emit_artifact_phase`; persist-only path is a census item | in |
| `Specify/Plan/TasksCompleted` | `mission.<phase>.completed.v1` (3) | Mission / none | row members verbatim (Y) | same | in |
| `WPCreated` | `wp.created.v1` | Mission / `wp_id` | row members verbatim (Y) | `emit_wp_created_local`; lifecycle slot | in |
| `ReviewerSelfApproval` | `wp.reviewer.selfapproved.v1` | Mission / `wp_id` | row members verbatim (Y); schema authored in the events PR | `emit_reviewer_self_approval`; lifecycle slot | in |
| `MissionReopened` | `mission.reopened.v1` | Mission / none | row members verbatim (Y) | `emit_mission_reopened`; lifecycle slot | in |
| `FollowUpRecorded` | `mission.followup.recorded.v1` | Mission / none | row members verbatim (Y) | `emit_follow_up_recorded`; lifecycle slot | in |
| `DecisionPointOpened` | `decision.opened.v1` | Mission / none | row members verbatim (Y) | `decisions/emit.py::emit_decision_opened`; `_queue_decision_fanout` (lifecycle slot) | in |
| `DecisionPointResolved` (resolved, deferred, canceled) | `decision.resolved.v1` | Mission / none | row members verbatim (Y) | `emit_decision_resolved`; as above | in |
| Run journal: `MissionRunStarted`, `NextStepIssued`, `NextStepAutoCompleted`, `DecisionInputRequested`, `DecisionInputAnswered`, `MissionRunCompleted` | `run.started.v1`, `run.step.issued.v1`, `run.step.autocompleted.v1`, `run.decision.requested.v1`, `run.decision.answered.v1`, `run.completed.v1` | Mission (identity from journal context) / none | payload models of `runtime/next/_internal_runtime/events.py` | `engine._append_event`; `RuntimeMomentProducer` (lifecycle slot). Journal row needs a ULID `event_id` and version (FR-035) | in |
| Run journal: `SignificanceEvaluated`, `DecisionAuthorityDenied` | `run.significance.evaluated.v1`, `run.decision.denied.v1` | as above | payload verbatim | seam NEW (the runtime producer deliberately publishes neither) | in |
| Retrospective rows: `RetrospectiveCaptured`, `RetrospectiveCaptureFailed`, `RetrospectiveSkipped` | `retrospective.captured.v1`, `retrospective.capturefailed.v1`, `retrospective.skipped.v1` | Mission / none | row members moved verbatim except lifted and the `type` member; the row's own `lamport` stays in `data` (a legacy per-Mission counter, not `lamportclock`); lifted `event_id`, `at`, `actor`, `mission_id`, `mission_slug`, `execution_mode` | `retrospective/lifecycle_events.py::emit_captured`, `emit_capture_failed`, `emit_skipped` (via `append_raw_rows_atomic`); seam NEW | in |
| Glossary rows (`src/glossary/events.py`) | n/a | n/a | n/a | n/a | **deferred** (#1200 residual) |
| `DecisionPointWidened` | n/a | n/a | n/a | no emitter found in `src/` | **deferred** |

The seam hands the producer only in-scope rows. The census gate (AC-004) derives its universe from the ledger's non-lane row set, so a persisted kind in neither this table nor the two deferred rows fails the gate instead of being ignored. The lifted-member list of each row is part of its registry entry (FR-014).

## 4 The total order (formal)

Let `clk(r)` be the integer `lamportclock` if present, `t(r)` the `time` as a UTC instant, `i(r)` the normalized `id` (`normalize_event_id` rules), `s(r)` the `source`.

```
key(r) = ( h(r), c(r), t(r), i(r), s(r) )
  h(r) = 1 if r has a clock else 0      c(r) = clk(r) if r has a clock else 0
```

Ascending, lexicographic: `h` (all clockless rows before all clocked), `c`, `t` as an instant, `i` and `s` by ASCII code point (not UTF-16). `s` is a final tie-break only, a refinement of the operator-ruled key so the order is total across sources; `nodeid` is not in the key (#6193).

**Domain.** The order applies to upcast-form rows (diary, `3.0.0`, CloudEvents) after dedupe; sources with no id (the legacy run journal) are outside it and read by their own reader. Dedupe runs first: of duplicate `(source, id)` rows the smallest key is kept, a differing payload is reported as a conflict (named reason), the rest count as collapsed. **Error rows** (unparseable `time`, a clock outside 0..2^31-1, an id that does not normalize, a missing id) go to a separate list with line position and named reason: never dropped, never silently sorted. Count: ordered + errors + collapsed = rows read.

Invariants: total, transitive, antisymmetric, input-order independent (a lexicographic product of total orders). Known consequences: a clockless row appended after clocked rows sorts first (only pre-cutover writers write clockless rows; #6195 stops old CLIs appending); a checkout with neither clock state nor publish log restarts the counter, so its new rows sort before older consumed rows. A naive comparator (both clocked: `(clock, time, id)`, else `(time, id)`) is not transitive (example 4).

### Worked examples (fixtures of AC-008 and AC-009; checked over all permutations with a reference comparator)

UTC unless shown. Ids `…A`, `…B`, `…C` are full ULIDs sharing the prefix `01JAAAA0000000000000000AB`, differing in the last character.

1. **Two clockless.** A `10:00, id …B`; B `10:00, id …A`: `id` decides, B before A.
2. **Two clocked.** A `clock 5, 09:00`; B `clock 4, 11:00`: B before A (clock beats time); equal clock and time: `id` decides.
3. **Mixed.** A clockless `23:00`; B `clock 1, 08:00`: A before B (`h` 0 < 1).
4. **Naive counter-example.** A `clock 5, 10:00`; B clockless `10:30`; C `clock 3, 11:00`. Naive: C<A (clock), A<B (time), B<C (time): a cycle. Total order: **B, C, A** for every permutation.
5. **Offsets and ids.** Clockless A `2026-10-13T12:00:00+02:00` (= 10:00:00Z), B `2026-10-13T10:00:00.5Z`: A before B (text order says B). At one instant, C `01jaaaa0000000000000000abc` and D `01JAAAA0000000000000000ABD` normalize to `…ABC` < `…ABD`: C before D. A legacy U `0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b` (or its 32-hex spelling) against ULID `01JAAAA0000000000000000ABC`: `0190…` < `01JA…` by code point, U first (deterministic, not meaningful).
6. **Error row and duplicate.** Clockless A `10:00`, B `time: "yesterday"`, C `11:00`, D a duplicate of C: ordered `[A, C]`, errors `[B at line 2: unparseable time]`, collapsed `[D]`; 4 read = 2 + 1 + 1.

## 5 Example lines

`plan-carryover.md` section X (real bytes come from the serializer and golden tests, never hand-written JSON).

## 6 Producer protocol entities (fields; wire format is a plan decision)

**ProduceRequest** (client to producer; assembled by the seam, passed through uninterpreted):

| Field | Type | Constraint |
|---|---|---|
| `row` | opaque mapping | the persisted authoritative row, post-sanitization (FR-004); a list under OD-SPAWN (b) |
| `slot` | enum | `status` \| `lifecycle`: the seam slot that fired (the resolved-binding slot is not registered, FR-003) |
| `seam_args` | opaque mapping | the existing keyword arguments (`wp_id`, `actor`, `mission_slug`, `mission_id`, `metadata`, ...), as given |
| `start_path` | path | the call site's path (E14); the producer resolves the canonical root from it, never from the process working directory |
| `deadline_ms` | integer | the client's timeout |

**ProduceReply** (producer to client; exactly one): `outcome` (`produced` \| `skipped_duplicate` \| `refused`); `event_id` (normalized, always present); `type` (when produced or skipped); `reason_class` (when refused: `rejected` \| `clock-overflow`); `detail` (one line, repo-relative paths only, NFR-006).

**ProducerFault** (recorded, never raised into the command): `class` (`binary-missing` \| `crash` \| `timeout` \| `malformed-reply` \| `clock-overflow` \| `rejected` \| `busy`, plus `disabled` only under OD-OPTOUT b); `event_type`; `event_id` (if known); `message` (carries the FR-023 sentence); `at` (client clock, never in an envelope).

Mapping: an unreadable, truncated, non-JSON or schema-violating reply is `malformed-reply`; a non-zero exit, signal or empty reply `crash`; no executable `binary-missing`; the client's deadline `timeout`; a call skipped because the handler is in flight `busy`.

## 7 Local-tier entities

**PublishLogFile.** Gitignored; a new local-tier file class (spec section 9). Unit (per-Mission or per-run file) is a plan decision; it carries an age (newest row `time` or file mtime) and a run or Mission association, so N-runs/X-days is defined ("run" = that unit). Complete lines ending LF; single writer: the producer. A final line without LF is preserved, flagged in the fault log, never joined to the next row. Location: an already-gitignored path (candidate `.kittify/events/`) or the canonical ignore mechanism (AC-023).

**LamportState.** Gitignored state owned by the producer: per-`nodeid` counter, integer 0..2^31-1, shared by every worktree of one repository (OD-CLOCK); outcomes of a kill, state loss and the bound are FR-012's. Counter only; merging other nodes' clocks is #6193's.

**FaultLogRow.** Append-only, gitignored, retention applies: ProducerFault fields plus a relative root marker. `doctor` derives its count (rows in the retention window) and last class from these rows.

**RetentionPolicy.** `.kittify/config.yaml` keys `ledger.retention.keep_runs` (positive integer, proposed 20) and `ledger.retention.max_age_days` (positive integer, proposed 30); operator to confirm. Invalid or non-numeric values are a named command error (fail closed), never a silent default (AC-031). Eligible: FR-029. Local tier only (publish log, fault log, run journals, `.kittify/events/`); the committed tier is never a candidate.

## 8 Exemption ledger (entity `Exemption`)

`clear` = the marker is gone and the persisted lines its writer wrote validate in the **legacy-row form**: generated from the single `data` schema plus the lifted-member list of section 3, both from the events PR (FR-014), never a second authored schema. `residual` = stays, with a reason and an issue reference to be filed. Reasons: `plan-carryover.md` section M.

- **Clear:** markers 1 and 2 (`decisions/emit.py`, #1198); 5 and 6 (`status/lifecycle_events.py`, before `_build_envelope` and its dict literal, #1198); 8 (`engine._append_event`, #1248, FR-035). Five fixtures for AC-019.
- **Plan decides:** 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`, likely residual-by-design), 7 (`build_saas_lifecycle_queue_event`; clear if #6164 removes the hosted path), all #1198.
- **Residual:** 9 to 16 (`glossary/events.py`, 8 markers, #1200; not widened).

Test fixture markers are fixtures (the plan reviews them). `scripts/lint_canonical_producers.py` gains a persisted-line mode; its baseline `scripts/canonical_producer_lint_baseline.txt` is not widened.

## 9 LaneVocabulary

`Lane` (StrEnum, `status/models.py`): 11 members including the non-persisted `uninitialized`; the canonical authority. `CANONICAL_LANES` (`status_lanes.py`): 9, derived or removed. `spec_kitty_events.Lane`: 10 (genesis plus the nine), aligned in the events PR. Census items (literals to derive or document): `_VALID_LANES` and `_LANE_ORDER` (`migration/rebuild_state.py`), `_LEGACY_NOT_DONE_LANES` (`acceptance/__init__.py`, includes legacy `doing`), `_PRE_APPROVED_LANE_VALUES` (`consolidation/done_bookkeeping.py`). Six sets in all (the AC-020 floor).

The schema's persisted lane enumeration = genesis, planned, claimed, in_progress, for_review, in_review, approved, done, blocked, canceled. The alias `doing` (`LANE_ALIASES`) is resolved to `in_progress` in `data`. `reason_source`: `operator` (`OPERATOR_REASON_SOURCE`) or `synthetic` (move-task hop), optional, absent for non-cancel moves.

## 10 PlatformWheel matrix entity

Per wheel: `platform_tag`; `os_arch` (minimum linux x86_64, macOS arm64, Windows x86_64; others OD-MATRIX); `binary_path` (one executable at one known in-package path); `binary_checksum` (recorded at build, equal to the recomputed wheel member, AC-032); `toolchain_pin`; `smoke_result` (G-3); `signed` (OD-SIGN). `fallback`: the pure `py3-none-any` wheel (OD-FALLBACK) and the sdist contain no binary, install, and the producer reports `binary-missing`.

Invariants: exactly one binary per platform wheel; the CLI's pure-Python behaviour is unchanged when the binary is absent; `packs/internal/` is in no wheel; the expected set is pinned (G-12).

## 11 Requirement-to-entity trace

FR-001 to FR-005: ProduceRequest (6), type table (3). FR-006, FR-010: envelope (1). FR-007 to FR-009, FR-016: rules (2). FR-011, FR-012: LamportState (7), `lamportclock`/`nodeid` (1). FR-013: total order (4). FR-014, FR-015, FR-017, FR-018, FR-034: type table (3), LaneVocabulary (9). FR-019 to FR-021: PublishLogFile (7). FR-022 to FR-025: ProducerFault (6), FaultLogRow (7). FR-026, FR-027: ProduceRequest, ProduceReply (6). FR-028 to FR-031: RetentionPolicy (7). FR-032, FR-033: Exemption ledger (8). FR-035: run-journal rows (3). FR-036 to FR-038: rules (2), section 4. FR-041 to FR-044: PlatformWheel (10). FR-039, FR-040, FR-045, FR-046: spec sections 7, 9, 13.
