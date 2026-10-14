---
work_package_id: WP05
title: 'Java retention: Retain use case, committed-ledger and lock-record readers, launcher retain operation'
dependencies:
- WP04
requirement_refs:
- FR-028
- FR-029
- FR-030
- FR-031
- NFR-006
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T029
- T030
- T031
- T032
- T033
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/application/
create_intent:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/retain/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/retain/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/ledger/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/ledger/**
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/retain/**
- services/event-producer/launcher/src/main/resources/META-INF/services/ai.speckitty.producer.launcher.OperationHandler
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/retain/**
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/retain/**
- services/event-producer/application/src/test/java/ai/speckitty/producer/application/retain/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/ledger/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/ledger/**
- services/event-producer/launcher/src/main/java/ai/speckitty/producer/launcher/retain/**
- services/event-producer/launcher/src/main/resources/META-INF/services/ai.speckitty.producer.launcher.OperationHandler
- services/event-producer/launcher/src/main/resources/META-INF/native-image/ai.speckitty/producer-retain/**
- services/event-producer/adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-ledger/**
- services/event-producer/launcher/src/test/java/ai/speckitty/producer/launcher/retain/**
- services/event-producer/adapter/src/test/resources/ledger/**
- services/event-producer/launcher/src/test/resources/retain/**
role: implementer
tags: []
tracker_refs: []
---

# WP05: Java retention: Retain use case, committed-ledger and lock-record readers, launcher retain operation

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Implement `Retain` in Java: candidate listing, facts, policy, whole-file deletion under `clock.lock`, the committed-ledger reader and lock-record reader, and the launcher `retain` operation.

## Context

Plan section 8 (all of it), 4.3; research R-6, R-21, R-23. **Refinement of the plan (defect found, see tasks-overview.md R1):** WP05 depends on WP04 (not only WP03; WP03 is implied transitively and no longer listed), because it needs WP04's `OperationHandler` interface and the ports at compile time. The registration mechanism is `ServiceLoader` with one WP05-owned provider-configuration file, so WP05 edits **no** WP04 file. The Python `doctor` is a pass-through (WP06). Unaffected by the events release.

## Subtask T029: Ports and facts

**Steps**: `application.retain`: `Retain` use case, ports `LedgerFacts` (the only code that opens a committed ledger; #6193 replaces it) and `LocalTier` (list the candidate files of the local-tier directories and delete one whole file; implemented in `adapter/ledger/localtier/` within the owned `adapter/ledger/**` glob; **it survives the #6193 replacement**, which removes only the committed-ledger reader classes behind `LedgerFacts`, not `LocalTier`). Both live in `application.retain`, not in WP03's `application.port`. Candidates come only from local-tier directories (`.kittify/events/publish/...`, `.kittify/events/faults/<UTC date>.jsonl`, run journals), never `kitty-specs/`.

## Subtask T030: Committed-ledger reader ("Mission open")

**Steps**: red tests, then read-only: open iff any work package's latest row (greatest `at`) has `to_lane` other than `done`/`canceled`; read both the canonical and the coordination-worktree copy, open if either says so; **an unreadable, unparseable or absent ledger keeps the Mission's files ("facts unavailable")** (AC-013, F-6). **Covers** FR-029.

## Subtask T031: Lock-record reader

**Steps**: lock fact = the content of a Python-owned lock sidecar, never its presence: non-empty parsable `LockRecord` younger than 60 s; empty sidecar means no lock; unreadable keeps the run; Java never takes an OS lock on a Python-owned file; `clock.lock` is never a lock fact. Fixtures per R-21.

## Subtask T032: `Retain` operation

**Steps**: `dry_run` true unless the request says false; defaults 20/30 with a named `policy_error` on invalid values **also on a dry run** (AC-031); per candidate take `clock.lock` (bounded by `deadline_ms`), re-check eligibility, length and newest row under it, then delete the whole file; lock timeout is kept-with-reason `busy`, delete failure `delete-failed`; the producer opens no candidate for write. Two-process test: `produce` racing `retain --apply` loses no reported row. Never open a git-tracked path for write: offer every tracked ledger as a candidate and assert zero writes and zero deletes (AC-014, with a self-mutation adding a tracked path). **Covers** FR-028, FR-030, FR-031, AC-013, AC-014, AC-031.

## Subtask T033: Launcher registration

**Steps**: `launcher/.../retain/**` implements `OperationHandler` for `retain` and registers it by the WP05-owned file `META-INF/services/ai.speckitty.producer.launcher.OperationHandler` (WP04's dispatcher loads it with `ServiceLoader`; red test first: before this file `retain` replies `refused`, after it replies per the policy). **Native-smoke expectation:** commit `launcher/src/test/resources/retain/smoke-request-retain.json`, a dry-run `retain` request that the shared smoke action (WP01 T008) runs against the native binary on each leg, so the `native-smoke` leg proves the provider file and the ledger and retain metadata survive the native build; reply `eligible`, `kept` (with reasons), `deleted`, `policy_error`; no absolute path in any reply (NFR-006). The two-process produce/retain race test (T032) lives in `launcher/src/test/java/.../launcher/retain/**`, which this WP owns. **Native-image metadata:** this WP's own directories `producer-retain` (launcher) and `producer-ledger` (adapter).

## Gates

`gradle -p services/event-producer check` (JaCoCo floors are enforced by `check`). No Python. Red-first, revert-checked.

## Definition of Done

T029 to T033 done; fail-closed on unreadable ledger proven; zero tracked writes.

## Risks

Deleting under a lock that Python also reads (the Python fault-log reader is in WP06): whole-file deletion only, never trimming.

## Reviewer Guidance

Check fail-closed paths, that eligibility is re-checked under the lock, and that no write-mode open of a candidate exists.

## Tests, markers, jobs

All Java: `gradle-test` three-OS matrix (`check`).

## Recorded out-of-map edits

None.

## Implementation command

`spec-kitty agent action implement WP05 --agent claude --mission event-producer-reroute-01M4VDY9`
