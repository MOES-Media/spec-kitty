---
work_package_id: WP03
title: 'Java ports and :adapter file-system side: lock, clock store, publish log, repo locator, node identity'
dependencies:
- WP02
requirement_refs:
- FR-004
- FR-011
- FR-012
- FR-019
- FR-020
- FR-021
- NFR-003
- NFR-004
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T079
- T017
- T018
- T019
- T020
- T021
- T022
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/adapter/
create_intent:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/port/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/json/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/fs/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/repo/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/identity/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/clock/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/publishlog/**
- services/event-producer/adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-adapter-fs/**
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/application/src/main/java/ai/speckitty/producer/application/port/**
- services/event-producer/adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-adapter-fs/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/json/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/fs/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/repo/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/identity/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/clock/**
- services/event-producer/adapter/src/main/java/ai/speckitty/producer/adapter/publishlog/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/json/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/fs/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/repo/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/identity/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/clock/**
- services/event-producer/adapter/src/test/java/ai/speckitty/producer/adapter/publishlog/**
- services/event-producer/adapter/src/test/resources/publishlog/**
- services/event-producer/adapter/src/test/resources/identity/**
role: implementer
tags: []
tracker_refs: []
---

# WP03: Java ports and :adapter file-system side: lock, clock store, publish log, repo locator, node identity

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Define the `:application` port interfaces (interface-only), then implement the file-system side of `:adapter` against them: JSON, lock, clock store, publish-log writer, repo locator, node identity.

## Context

Plan sections 1 (`:adapter`), 7 (clock state file), 8 (placement) and data-model section 7; research R-5, R-6. WP03 owns the `adapter/json` package that later WPs (WP05, WP11) consume. Dependencies are already settled in WP01's verification metadata; add none. Unaffected by the events release.

**Ports are authored here (tasks-overview R4).** `:adapter` depends on `:application` and implements its ports, so the interface-only port package `application/port/**` is owned by this WP (not WP04) and lands first. WP04's `Produce` consumes the ports. A port signature that WP04 finds wrong is changed by WP04 as a **recorded second-editor edit** of `application/port/**` with the reason in the WP04 notes; it is never forked into a second interface.

**Check open PRs** on the owned files before the first commit (none known to overlap).

## Subtask T079: Ports as interfaces (first task, red-free)

**Steps**: in `ai.speckitty.producer.application.port` write the interfaces only, no behaviour and no framework import: `RowSchemaValidator`, `PublishLog`, `ClockStore`, `RepoLocator`, `NodeIdentity`, `Clock`, shaped by plan sections 1, 5, 7 and data-model sections 1, 2, 6, 7. Each adapter class of T017 to T021 declares `implements` on its port. `:application` ArchUnit rules (no `:adapter`, no Jackson) are WP04's `ArchitectureTest`; this task adds no test. `LedgerFacts` and the retain ports (including local-tier listing and deletion) are **not** authored here: they live in `application.retain`, owned by WP05 (T029). **Validation**: `gradle -p services/event-producer :application:compileJava :adapter:compileJava`.

## Subtask T017: `json` and `fs` helpers

**Steps**: Jackson configured once (stable member order, LF, no BOM, no pretty printing); atomic temp-file + fsync + move helper; `FileChannel` exclusive lock helper with a bounded wait taken from `deadline_ms`. **Validation**: determinism test (same input, identical bytes).

## Subtask T018: `repo` locator

**Steps**: red tests, then resolve the git common dir by reading `.git` (file or directory) and `commondir`, never spawning git; primary checkout plus two worktrees fixtures. **Canonical-root derivation (FR-004):** the publish-log location (`.kittify/events/` under the canonical repository root, parity with `core.paths.resolve_canonical_root`) is derived from a start path; red tests with parity fixtures show that a primary checkout, a linked worktree, a coordination worktree and a Mission **subdirectory** start path all give the **same** publish-log path; a start path with no repository above it is `root-unresolvable` (surfaced by WP04 T025 as `rejected`). **Covers** FR-004 (Java side), FR-012 (anchor), AC-005 (root-unresolvable half).

## Subtask T019: `clock` store

**Steps**: `<git-common-dir>/spec-kitty/event-producer/clock.json` (`{"v":1,"nodeid","clock"}`) and `clock.lock`; one exclusive lock held across read, stamp, dedupe, state write, append. State first: temp, fsync, atomic move, then append and fsync. State loss: next stamp = max(state, highest `lamportclock` readable across publish-log files) + 1. A test-only `FaultInjector` port injects the three kill points (AC-010). **Common-dir anchor (AC-023):** a test with a primary checkout and a linked worktree asserts both resolve the same `<git-common-dir>/spec-kitty/event-producer/clock.json`, and that the path lies outside every working tree (floor: two checkouts; mutation: anchoring the state at the worktree root must fail). **Covers** FR-012, FR-019, AC-010, AC-023.

## Subtask T020: `publishlog` writer

**Steps**: red tests; per-Mission file `.kittify/events/publish/missions/<missionid>.jsonl` and `project-<project_uuid>.jsonl`; one UTF-8 JSON object per line, LF only, no BOM, no raw embedded newline; **torn-line handling** (a final line without trailing LF is detected before the next append, left in place, flagged, next row starts on a new line; AC-026); `(source, id)` dedupe (second append is a no-op, AC-011 at adapter level); the producer is the single writer. **Covers** FR-019, FR-020, FR-021, AC-026, AC-034, NFR-003.

## Subtask T021: `identity` node id

**Steps**: SHA-256 of `hostname:username`, first 12 hex; hostname through an FFM `gethostname` downcall (`ws2_32` on Windows); username from `LOGNAME`, `USER`, `LNAME`, `USERNAME`, then `user.name`. 
**Golden fixtures (synthetic strings only, the repository is public):** `generate_node_id()` takes no arguments, so expected values are produced by running the real Python function with its two inputs patched, once, and committing the output as `adapter/src/test/resources/identity/nodeid-golden.json` (hostnames and usernames such as `host-a` and `user-a`, never a real host or user):
```
uv run --frozen python -c "import getpass, json, socket; from unittest import mock; from specify_cli.identity.project import generate_node_id; rows = []
for host, user in (('host-a', 'user-a'), ('host-b', 'user-b')):
    with mock.patch.object(socket, 'gethostname', return_value=host), mock.patch.object(getpass, 'getuser', return_value=user):
        rows.append({'hostname': host, 'username': user, 'nodeid': generate_node_id()})
print(json.dumps(rows))"
```
Add a third case with `USER` unset and `user.name` set (Java's chain differs from `getpass`'s password-database fallback, so this case documents the intended Java behaviour with a synthetic name). **Live comparison on every OS:** the Java test compares `NodeIdentity` with the environment value `PRODUCER_LIVE_PY_NODE_ID` that each leg of the `gradle-test` matrix exports (WP01 T007, a step already in `event-producer.yml`; this WP edits no workflow). A missing `PRODUCER_LIVE_PY_NODE_ID` **fails** the test only when `REQUIRE_LIVE_NODE_ID=1`, which only the `gradle-test` legs export (WP01 T007) next to the node id; in any other run (a local `gradle check`, or any CI lane that is not `gradle-test`) it skips with a message. `CI` is not consulted, so no other lane can go red for lack of a Python-side value. **Floor and mutation (AC-039):** the test counts its cases and fails below three (the live case plus the two synthetic golden strings); a mutation that changes a hash input (hostname and username swapped, or a different truncation length) must fail the golden comparison. **Covers** FR-011, AC-039.

## Subtask T022: Concurrency and byte tests

**Steps**: a primary checkout plus two worktrees, N concurrent processes: no repeated `(nodeid, lamportclock)`, strictly increasing per node; log deleted with state kept raises the next stamp; state deleted recovers from the log. Byte-identical golden line file (from the real serializer) for AC-034; scan that no absolute home path or username is written (NFR-006). **AC to leg:** AC-010, AC-034 and AC-039 are asserted on all three legs of the `gradle-test` matrix (WP01 T007); a leg that is absent turns the terminal gate red, it is never skipped.

**Native-image metadata:** every class this WP adds that Jackson, the FFM downcall or the lock code reaches by reflection is registered under `adapter/src/main/resources/META-INF/native-image/ai.speckitty/producer-adapter-fs/` (this WP's own directory). **The metadata is unverified until WP04.** In WP03 the only launcher code is WP01's self-contained spike (WP01-owned, deleted by WP04) and WP03 owns no launcher file, `tools/` file or smoke action, so the native binary built by `native-smoke` cannot reach the `json`, `fs`, `clock`, `publishlog` or `identity` classes; `native-image` only checks reachable code. Author the metadata carefully from the JVM tests (a JVM run with the native-image agent, `-agentlib:native-image-agent`, against the adapter tests is the evidence to use) and hand it to WP04, whose `Wiring` makes these classes reachable and whose `native-smoke` expectation (T023/T028) is the first real exercise. A metadata error found there is fixed in `producer-adapter-fs/**` as a recorded fix-only edit of WP04 (WP04 'Recorded out-of-map edits').

## Gates

`gradle -p services/event-producer :adapter:check` and full `check` (JaCoCo `:adapter` 80% is enforced by `check`, not `test`). No Python. Red-first, revert-check on lock ordering and state-first order.

## Definition of Done

T079 and T017 to T022 done; kill-point tests green; golden and live nodeid green on all three OS in CI; adapter-fs native-image metadata written from agent-recorded runs and handed to WP04 for its first native exercise.

## Risks

Windows `LockFileEx` semantics; FFM availability in the native image (proved in WP01); separate clones share only `nodeid` (accepted residual).

## Reviewer Guidance

Check the lock is held across the whole stamp-to-append span and that no code path rewrites an existing publish-log line.

## Tests, markers, jobs

All Java: run by the `gradle-test` three-OS matrix (`check`). No Python test files.

## Recorded out-of-map edits

None. (This WP edits no build file and no workflow.)

## Implementation command

`spec-kitty agent action implement WP03 --agent claude --mission event-producer-reroute-01M4VDY9`
