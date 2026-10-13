# Data Model: event-producer-reroute-01M4VDY9

Entities and invariants for [`spec.md`](spec.md). Wire formats: plan. **INTERIM** marks unconfirmed ADR 0001 choices. Envelope schema, registry and upcaster belong to events#92.

## 1 Envelope attributes (CloudEvents 1.0 structured JSON)

Names match `^[a-z0-9]{1,20}$`; CloudEvents attribute types only; absent is omitted, never `null`.

| Attribute | Kind | Type | Req | Source on the producer |
|---|---|---|---|---|
| `specversion` | core | String | yes | constant `1.0` |
| `id` | core | String | yes | authoritative row `event_id` (section 2) |
| `source` | core | URI-reference | yes | `/spec-kitty/missions/<missionid>` or `/spec-kitty/projects/<project_uuid>` (INTERIM) |
| `type` | core | String | yes | `ai.spec-kitty.<name>.v<N>` (section 3) |
| `subject` | optional | String | per type | work package id; omitted for Mission-level events |
| `time` | optional | Timestamp | yes here | authoritative row's own timestamp, RFC 3339 |
| `datacontenttype` | optional | String | yes here | constant `application/json` |
| `dataschema` | optional | URI | yes here | base URI (events#92) + type suffix |
| `schemaversion` | extension | String | yes here | constant from events#92 |
| `missionid` | extension | String | Mission-scoped | row (an empty string counts as absent), else `meta.json` (never a slug); neither: the row is refused (FR-009, OD-MISSIONID) |
| `missionslug` | extension | String | when known | row `mission_slug` |
| `actor` | extension (INTERIM) | String | lane, annotation, retrospective rows, when derivable | row `actor` per the actor rule (section 3) |
| `executionmode` | extension (INTERIM) | String | lane rows | row `execution_mode` |
| `lamportclock` | extension | Integer | yes | per-node counter (section 7), 0..2^31-1 |
| `nodeid` | extension | String | yes | `identity/project.py::generate_node_id()` (12 hex chars), derived per FR-011 |

`data` members stay snake_case (INTERIM); a structured value never becomes a context attribute.

## 2 Rules for `id`, `time`, `source`, dedupe

- **`id`**: the ledger row's `event_id`, never replaced, accepted only as a 26-character ULID (written upper-case). Any UUID form (hyphenated, or the 32-hex fallback of `lifecycle_events._generate_event_id`) is `rejected`. UUID normalization (`normalize_event_id`) is reader-only: the comparator applies it to legacy rows.
- **`time`**: the row's own instant (`at` on lane, annotation, decision and retrospective rows; `timestamp` on lifecycle and run-journal rows), RFC 3339, never synthesized; unparseable or absent is `rejected`.
- **`source`**: Mission-scoped rows `/spec-kitty/missions/<missionid>`; `ProjectInitialized` `/spec-kitty/projects/<project_uuid>` (INTERIM). `missionid` is a ULID; when neither the row nor `meta.json` yields one, the row is `rejected` (FR-009, OD-MISSIONID).
- **Dedupe key** `(source, i)` with `i` the normalized id (INTERIM; section 4). A second append for an existing key is a no-op. A row with no `source` (upcast diary or `3.0.0` rows) has the empty string as `source`; not an error row.
- **Join invariant**: each publish-log line matches at most one ledger row by normalized `event_id`, and each in-scope ledger row has at most one line.

## 3 Type table (all names INTERIM; registry authority is events#92)

"Verbatim": row members moved unchanged (lists: carryover Y). "NEW": a seam call FR-003 adds.

| Legacy row | Type | Scope / `subject` | `data` members (row members moved verbatim unless noted) | Emission point (writer: Python) / seam | Status |
|---|---|---|---|---|---|
| Lane transition (`StatusEvent`) | `lane.transitioned.v1` | Mission / `wp_id` | `from_lane`, `to_lane`, `force`, `reason`, `reason_source` (optional), `review_ref`, `evidence`, `review_result`, `policy_metadata`, `actorbinding`. Lifted to attributes: `event_id`, `at`, `mission_slug`, `mission_id`, `wp_id`, `actor`, `execution_mode` | `status/emit.py::emit_status_transition`, `emit_status_transition_batch`, coordination shell; seam `fire_saas_fanout` (`_saas_fan_out`, `coordination/outbound.py`) | in |
| Inner-state annotation (`InnerStateChanged`) | `wp.innerstate.changed.v1` | Mission / `wp_id` | `delta`, `actorbinding`; lifted `event_id`, `at`, `wp_id`, `actor`; `kind` (constant `annotation`) dropped, the type carries it; `missionid`/`missionslug` from `meta.json` | `emit_inner_state_changed` (+ transactional sibling); seam NEW | in |
| `ProjectInitialized` | `project.initialized.v1` | Project / none | row members verbatim | `emit_project_initialized`; lifecycle slot | in |
| `MissionCreated` | `mission.created.v1` | Mission / none | row members verbatim | `emit_mission_created_local`; lifecycle slot | in |
| `Specify/Plan/TasksStarted` | `mission.<phase>.started.v1` (3) | Mission / none | row members verbatim; local-only `artifact_path` kept | `emit_artifact_phase_local`; lifecycle slot | in |
| `Specify/Plan/TasksCompleted` | `mission.<phase>.completed.v1` (3) | Mission / none | row members verbatim | same | in |
| `WPCreated` | `wp.created.v1` | Mission / `wp_id` | row members verbatim | `emit_wp_created_local`; lifecycle slot | in |
| `ReviewerSelfApproval` | `wp.reviewer.selfapproved.v1` | Mission / `wp_id` | row members verbatim; schema authored in the events PR | `emit_reviewer_self_approval`; lifecycle slot | in |
| `MissionReopened` | `mission.reopened.v1` | Mission / none | row members verbatim | `emit_mission_reopened`; lifecycle slot | in |
| `FollowUpRecorded` | `mission.followup.recorded.v1` | Mission / none | row members verbatim | `emit_follow_up_recorded`; lifecycle slot | in |
| `DecisionPointOpened` | `decision.opened.v1` | Mission / none | row members verbatim | `emit_decision_opened`; lifecycle slot | in |
| `DecisionPointResolved` (outcome in `terminal_outcome`: resolved, deferred, canceled) | `decision.resolved.v1` | Mission / none | row members verbatim | `emit_decision_resolved`; as above | in |
| Run journal: `MissionRunStarted`, `NextStepIssued`, `NextStepAutoCompleted`, `DecisionInputRequested`, `DecisionInputAnswered`, `MissionRunCompleted` | `run.started.v1`, `run.step.issued.v1`, `run.step.autocompleted.v1`, `run.decision.requested.v1`, `run.decision.answered.v1`, `run.completed.v1` | Mission (identity from journal context) / none | payload models of `runtime/next/_internal_runtime/events.py` | `engine._append_event`; lifecycle slot; FR-035 | in |
| Run journal: `SignificanceEvaluated`, `DecisionAuthorityDenied` | `run.significance.evaluated.v1`, `run.decision.denied.v1` | as above | payload verbatim | seam NEW | in |
| Retrospective rows: `RetrospectiveCaptured`, `RetrospectiveCaptureFailed`, `RetrospectiveSkipped` | `retrospective.captured.v1`, `retrospective.capturefailed.v1`, `retrospective.skipped.v1` | Mission / none | row members verbatim except the lifted ones and `type`; the row's `lamport` stays in `data` (a legacy per-Mission counter, not `lamportclock`); `actorbinding` per the actor rule; lifted `event_id`, `at`, `actor`, `mission_id`, `mission_slug` (`execution_mode` stays in `data`) | `retrospective/lifecycle_events.py::emit_captured`, `emit_capture_failed`, `emit_skipped` (via `append_raw_rows_atomic`); seam NEW | in |
| Glossary rows (`src/glossary/events.py`) | n/a | n/a | n/a | n/a | **deferred** (#1200 residual) |
| `DecisionPointWidened`, `DecisionPointDeferred`, `DecisionPointCanceled` | n/a | n/a | n/a | registered kinds, no emitter in `src/` (outcomes travel as `DecisionPointResolved`) | **deferred** |

**Actor rule.** Persisted `actor` is a string (lifecycle rows), a structured `{role, profile, tool, model}` object (lane, annotation) or a retrospective `{kind, id, display}` object. Only the lane, annotation and retrospective types lift it. Attribute `actor`: a string verbatim; a structured object its `actor_identity_str` projection (`tool`, else `role`); a retrospective object its `id`; omitted when empty. `data.actorbinding` exists only for an object actor and equals the row's `actor` unchanged (the one rename). Other types keep their own actor members (`actor`; `actor_id`, `actor_type` on decision rows) in `data` verbatim and set no `actor` attribute.

**Lifecycle rule.** The twelve `LIFECYCLE_EVENT_TYPES` rows are the envelope of `status/lifecycle_events.py::_build_envelope` (`event_id`, `event_type`, `aggregate_id`, `aggregate_type`, `schema_version`, `timestamp`, `payload`, `project_uuid`, `project_slug`). For those types `data` is `payload` verbatim (`actor` stays in `data`); `event_id` is `id`, `timestamp` is `time`, `event_type` picks the type, `aggregate_id` is `subject` for work package types, `project_uuid` forms the project `source`; the rest is dropped. Decision rows persist `{event_id, at, event_type, payload}`: `id` is `event_id`, `time` is `at`, `data` is `payload`, `source` from `payload.mission_id`, no `subject`. Run-journal rows persist `{event_type, timestamp, payload, event_id (FR-035)}`: `id` is that ULID, never the uuid5 moment id of the hosted envelope; `time` is `timestamp`; mission identity from the journal context.

The seam hands the producer only in-scope rows. The census gate (AC-004) derives its universe from the persisted row kinds: a kind in neither this table nor the deferred rows fails it. Each row's lifted-member list is part of its registry entry (FR-014).

## 4 The total order

Let `clk(r)` be the integer `lamportclock` if present, `t(r)` the `time` as a UTC instant, `i(r)` the normalized `id` (`normalize_event_id` rules), `s(r)` the `source` (the empty string when the row has none, section 2).

```
key(r) = ( h(r), c(r), t(r), i(r), s(r) )
  h(r) = 1 if r has a clock else 0      c(r) = clk(r) if r has a clock else 0
```

Ascending, lexicographic: `h` (all rows without a `lamportclock` member before all rows with one), `c`, `t` as an instant, `i` and `s` by ASCII code point (not UTF-16). `s` is a final tie-break, so the order is total across sources; `nodeid` is not in the key (#6193).

A **clockless row** has no `lamportclock` member at all; a present value outside 0..2^31-1 is not clockless but an error row (below).

**Domain.** The order applies to upcast-form rows (diary, `3.0.0`, CloudEvents); sources with no id (the legacy run journal) are outside it and read by their own reader. **Error rows** (unparseable `time`, a clock outside 0..2^31-1, an id that does not normalize, a missing id) are set aside first, with named reason and the line position of the file as read: never dropped, never silently sorted, never a survivor. Dedupe then runs on the valid rows only, on `(s(r), i(r))`, so two spellings of one id (case, hyphenation) in one source are one row. Of each group the survivor is the row whose serialization (members sorted, compact, UTF-8) has the smallest bytes, so it never depends on input position; every other row of the group counts as collapsed, and a group whose rows differ in any member other than `id` is also reported as a conflict by its `(s, i)` key (a report on the key, not another bucket). After dedupe `(s, i)` is unique, so no two rows have equal keys. Permutation equality compares the ordered rows, the collapsed count, the conflict keys and the error rows as a multiset (position excluded). Count: ordered + errors + collapsed = rows read.

Invariants (checked by a read-only script over all permutations of every fixture): total, transitive, input-order independent. A naive comparator (both clocked: `(clock, time, id)`, else `(time, id)`) is not transitive (example 4). Carryover W.

### Worked examples (the nine fixtures of AC-008 and AC-009; example 4 is the naive counter-example)

UTC unless shown. Ids `…A`, `…B`, `…C` are ULIDs with the prefix `01JAAAA0000000000000000AB`.

1. **Two clockless.** A `10:00, id …B`; B `10:00, id …A`: `id` decides, B before A.
2. **Two clocked.** A `clock 5, 09:00`; B `clock 4, 11:00`: B before A (clock beats time); equal clock and time: `id` decides.
3. **Mixed.** A clockless `23:00`; B `clock 1, 08:00`: A before B (`h` 0 < 1).
4. **Naive counter-example.** A `clock 5, 10:00`; B clockless `10:30`; C `clock 3, 11:00`. Naive: C<A (clock), A<B (time), B<C (time): a cycle. Total order: **B, C, A** for every permutation.
5. **Offsets and ids.** Clockless A `2026-10-13T12:00:00+02:00` (= 10:00:00Z), B `2026-10-13T10:00:00.5Z`: A before B (text order says B). At one instant, C `01jaaaa…abc` and D `01JAAAA…ABD` normalize to `…ABC` < `…ABD`: C before D. A legacy UUID `0190a1b2-…` sorts before ULID `01JAAAA…ABC` by code point.
6. **Error row and duplicate.** Clockless A `10:00`, B `time: "yesterday"`, C `11:00`, D byte-identical to C: ordered `[A, C]`, errors `{B: unparseable time}`, collapsed count 1; 4 read = 2 + 1 + 1.
7. **Source tie-break.** Three rows `clock 7`, same time, same id, sources A `/spec-kitty/projects/P1`, B `/spec-kitty/missions/M1`, C none (empty): order **C, B, A** for every permutation (empty < `/spec-kitty/m…` < `/spec-kitty/p…`). Without `s` the permutations differ.
8. **Two spellings of one id.** Same source, clock 3, same time: A id `01jaaaa0000000000000000abc`, B id `01JAAAA0000000000000000ABC` (identical payload), C `…ABD`: survivor B (`…ABC` sorts before `…abc`), ordered `[B, C]`, collapsed 1; dedupe on raw ids keeps both.
9. **Conflict, survivor, every error class.** Same source; every row except M has no `lamportclock` member (clockless, `h` 0); M carries `lamportclock` 2^31, so it is not clockless but out of range, an error row. G and H share id `…ABA` and differ in a payload member (`x`, `y`); X has id `…ABA` and an empty `time`; K has no id; M id `…ABB`; N id `nope`; Z `11:00`, id `…ABC`. Ordered `[G, Z]` (G is valid and its bytes sort first; X cannot displace it), errors `{X: unparseable time, K: missing id, M: clock out of range, N: non-normalizing id}`, collapsed 1, conflict `(empty source, …ABA)`; 7 read = 2 + 4 + 1.

## 5 Example lines

Carryover X.

## 6 Producer protocol entities (fields only)

**ProduceRequest** (client to producer; assembled by the seam, passed through uninterpreted): `operation` (`produce` or `retain`); for `produce`, `row` (the persisted authoritative row, post-sanitization, FR-004); `slot` (`status` or `lifecycle`; the resolved-binding slot is not registered, FR-003); `seam_args` (the existing keyword arguments, as given); `start_path` (the call site's path, E14; the producer resolves the canonical root from it, never from the process working directory); `deadline_ms` (the client's effective timeout, FR-025; the producer exits by it on its own).

**Retain operation** (FR-030; the second operation of the same request, a Java operation; the Python side passes arguments and renders the reply): the request carries `start_path`, `dry_run` (true by default) and the policy keys of FR-028; it never carries the open-Mission, lock or unfinished-step facts. The producer derives them itself: it reads the committed ledgers read-only and minimally (the lane of each work package) and the lock and run-journal facts; this is not the #5529 read model, and #6193 absorbs it. Its reply lists the files eligible (dry run) or deleted, repo-relative, each with the reason kept or eligible, or a named policy error (AC-031).

**ProduceReply** (producer to client; exactly one): `outcome` (`produced` \| `skipped_duplicate` \| `refused`); `event_id` (normalized; present when the row carries a normalizable id, else omitted); `type` (when produced or skipped); `reason_class` (when refused: `rejected` \| `clock-overflow`); `notice` (optional: `torn-line` with the line position; recorded by the client as a notice row, not a fault); `detail` (one line, repo-relative paths only, NFR-006).

**ProducerFault** (recorded, never raised into the command): `class` (`binary-missing` \| `crash` \| `timeout` \| `malformed-reply` \| `clock-overflow` \| `rejected` \| `busy`; no `disabled`, OD-OPTOUT); `event_type` and `event_id`: the reply's values when one was read; for a fault the client raises alone (`binary-missing`, `crash`, `timeout`, `busy`, `malformed-reply`) the row's own `event_type` and `event_id` members, copied as the seam handed them (no interpretation, no normalization), each omitted when the row has none; `message` (carries the FR-023 sentence); `at` (client clock, never in an envelope).

Mapping: an unreadable, truncated, non-JSON or schema-violating reply `malformed-reply`; non-zero exit, signal or empty reply `crash`; no executable `binary-missing`; the deadline `timeout`; a handler already in flight `busy`.

## 7 Local-tier entities

**PublishLogFile.** Gitignored; a new local-tier file class (spec section 9). Unit (per-Mission or per-run file) is a plan decision; it carries an age (newest row `time` or file mtime) and a run or Mission association (FR-028). Complete lines ending LF; single writer: the producer. A final line without LF is preserved and flagged via `notice`; the next row starts on a new line (FR-021), so the fragment is the one non-JSON line, skipped by clock recovery and dedupe. Location: an already-gitignored path (candidate `.kittify/events/`) or the canonical ignore mechanism (AC-023).

**LamportState.** Producer-owned local state under the git common directory (FR-019, OD-CLOCK): per-`nodeid` counter, integer 0..2^31-1, shared by every worktree of one repository; kill, loss and bound: FR-012.

**FaultLogRow.** Append-only, gitignored, retention applies, written only by the client (FR-023): two row kinds, a ProducerFault (its fields plus a relative root marker) or a `torn-line` notice (path, line position, `at`). `doctor` derives the fault count (fault rows, not notice rows, in the files within the retention window) and last class from the first kind, and counts notices apart.

**RetentionPolicy.** `.kittify/config.yaml` keys `ledger.retention.keep_runs` (positive integer, default 20) and `ledger.retention.max_age_days` (positive integer, default 30). Invalid values are a named command error (fail closed, AC-031). Eligible: FR-029, facts derived by the Java retain operation (section 6). Local tier only (publish log, fault log, run journals, `.kittify/events/`); committed files are never candidates. The unit is a whole file (FR-028): retention never rewrites or trims a file with another writer.

## 8 Exemption ledger (entity `Exemption`)

`clear` = the marker is gone and the persisted lines its writer wrote validate in the **legacy-row form**: the single `data` schema plus the lifted-member list of section 3 (FR-014), never a second authored schema. `residual` = stays, with a reason and an issue reference to be filed (reasons: carryover M).

- **Clear:** markers 1, 2 (`decisions/emit.py`), 5, 6 (`status/lifecycle_events.py`), all #1198; 8 (`engine._append_event`, #1248, FR-035). Five fixtures for AC-019.
- **Plan decides:** 3 (`events/decision_log.py`), 4 (`migration/mission_state.py`), 7 (`build_saas_lifecycle_queue_event`).
- **Residual:** 9 to 16 (`glossary/events.py`, #1200). The producer lint gains a persisted-line mode; its baseline is not widened.

## 9 LaneVocabulary

`Lane` (StrEnum, `status/models.py`): 11 members including the non-persisted `uninitialized`; the canonical authority. `CANONICAL_LANES` (`status_lanes.py`): 9. `spec_kitty_events.Lane`: 10, aligned in the events PR. Census items: `_VALID_LANES` and `_LANE_ORDER` (`migration/rebuild_state.py`), `_LEGACY_NOT_DONE_LANES` (`acceptance/__init__.py`, includes legacy `doing`), `_PRE_APPROVED_LANE_VALUES` (`consolidation/done_bookkeeping.py`). Six sets in all (the AC-020 floor).

The schema's persisted lane enumeration = genesis, planned, claimed, in_progress, for_review, in_review, approved, done, blocked, canceled. The alias `doing` (`LANE_ALIASES`) is resolved to `in_progress` in `data`. `reason_source`: `operator` (`OPERATOR_REASON_SOURCE`) or `synthetic` (move-task hop), optional, absent for non-cancel moves.

## 10 PlatformWheel matrix entity

Per wheel: `platform_tag`; `os_arch` (FR-041, OD-MATRIX); `binary_path`; `binary_checksum` (AC-032); `toolchain_pin`; `smoke_result` (G-3); `signed` (false in this mission, OD-SIGN). The pure wheel (OD-FALLBACK) and sdist hold no binary. Invariants: one binary per platform wheel; `packs/internal/` in no wheel; the expected set is pinned (G-12).
