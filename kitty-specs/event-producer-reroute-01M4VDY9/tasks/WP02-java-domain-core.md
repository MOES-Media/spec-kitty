---
work_package_id: WP02
title: 'Java :domain core: ids, clock value, total order, envelope model, retention eligibility, ArchUnit'
dependencies:
- WP01
requirement_refs:
- FR-007
- FR-008
- FR-009
- FR-010
- FR-012
- FR-013
- FR-016
- FR-029
- NFR-003
- NFR-005
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T011
- T012
- T013
- T014
- T015
- T016
history: []
agent_profile: java-jenny
authoritative_surface: services/event-producer/domain/
create_intent:
- services/event-producer/domain/src/main/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/ids/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/clock/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/order/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/envelope/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/retention/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/fault/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/protocol/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/arch/**
- services/event-producer/domain/src/testFixtures/**
execution_mode: code_change
model: ''
owned_files:
- services/event-producer/domain/src/main/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/ids/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/clock/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/order/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/envelope/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/retention/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/fault/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/protocol/**
- services/event-producer/domain/src/test/java/ai/speckitty/producer/domain/arch/**
- services/event-producer/domain/src/testFixtures/**
role: implementer
tags: []
tracker_refs: []
---

# WP02: Java :domain core: ids, clock value, total order, envelope model, retention eligibility, ArchUnit

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `java-jenny`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Implement the framework-free `:domain` subproject: ids, clock value, total-order comparator with dedupe and error rows, the envelope model driven by data, retention eligibility and policy, the fault vocabulary and protocol value types, with ArchUnit rules.

## Context

Plan section 1 (`:domain` row and ArchUnit rules), 7 (clock bound), 8 (eligibility), 9 (JaCoCo floors: `:domain` 95% line, 90% branch). Data-model sections 1 to 4, 6, 7. **Reads no schema**: order tests use the nine data-model section 4 examples as test-local inputs (the released comparator fixtures run in WP11). `:domain` imports only `java.base`: no Jackson, no clock/env/file reads. WP02 is unaffected by the external events release.

## Preconditions

WP01 verdict PASS (read `research/wp01-feasibility.md`). If it is HALT, stop.

## Subtask T011: `ids`

**Steps**: tests first (red): ULID parse and normalise (26 chars, Crockford, upper-case written); refuse every UUID form (hyphenated, 32-hex) with reason `rejected`; round-trip. Then `ai.speckitty.producer.domain.ids`. **Covers** FR-007.

## Subtask T012: `clock` value type

**Steps**: red tests for integer 0..2^31-1, successor, overflow above 2^31-1 refused as `clock-overflow` (no wrap). The value type rejects every non-integer JSON form (see T013 for the error-row cases `null`, string, boolean, fractional, negative). **Covers** FR-012 (value side).

## Subtask T013: `order` (comparator, dedupe, error rows)

**Steps**: red tests from the nine data-model section 4 fixtures as test-local inputs (floor: all nine; example 4 is the naive counter-example). Comparator: Lamport, then `time`, then `id`, `(time, id)` fallback for clockless rows, the `source` tie-break as the spec states. Dedupe-then-order on `(source, id)`; error rows (unparseable `time`, non-normalising or missing `id`, clock out of range) listed with line position, absent from the ordered list; ordered + errors + collapsed = rows read (AC-009). Permutation test (AC-008 against a test-local reference).
**Mutants (each asserted red, so the tests cannot pass against a comparator that ignores the rule):** (1) the naive comparator (example 4); (2) a comparator without the `source` tie-break (example 7); (3) dedupe on the raw `id` instead of `(source, id)` (example 8); (4) an invalid duplicate survivor, or no conflict reported (example 9); (5) a comparator that **drops an error row** (AC-009). The "all nine" floor is asserted, and error rows are compared as a **multiset** (position, reason), so a dropped or duplicated error row fails. **One mutant table, shared by construction:** the five mutant comparators, the nine test-local fixtures' loader and the permutation reference live in `domain/src/testFixtures/java/ai/speckitty/producer/domain/order/**` (WP02-owned; the `java-test-fixtures` plugin and the `:adapter` `testFixtures(project(":domain"))` dependency are pre-wired by WP01 T003 step 9, so this WP edits no build file). WP02's own tests consume them as `testFixtures` of `:domain`; WP11 T075 consumes the same classes from the `:adapter` test classpath and holds no copy. Test-fixture code is `java.base` plus JUnit only, so `:domain` main stays `java.base`-only.
**Non-integer `lamportclock` (spec touch-up 4):** error rows with the named reason, never clockless, for each of `null`, `"5"`, `true`, `1.5` and `-1`; the member **absent** is clockless (ordered by `(time, id)`); `2^31` stays the out-of-range case of fixture 9. A reader that coerces `"5"` or `true` to an integer, or treats `null` as absent, fails these cases.
**Covers** FR-013 (Java comparator), FR-016 (dedupe key), AC-008, AC-009.

## Subtask T014: `envelope` model

**Steps**: red tests; attribute-name rule `^[a-z0-9]{1,20}$`; allowed attribute value types; absent values omitted, never `null`; `time` as RFC 3339 instant from the row's own timestamp (never synthesised; unparseable is `rejected`); `source` rule (`/spec-kitty/missions/<missionid>` or `/spec-kitty/projects/<project_uuid>`); registry model and lifted-member mapping **driven by data** (no per-type code). **Covers** FR-008, FR-009, FR-010.

## Subtask T015: `retention` eligibility, policy; `fault`, `protocol`

**Steps**: red tests: eligible only when outside both limits (not among the newest `keep_runs` AND older than `max_age_days`), Mission not open, no live lock, no unfinished step, over supplied facts only (pure functions); policy defaults 20 and 30; invalid or non-numeric values give a named `policy_error` (AC-031 fixtures live here and in WP05); `fault` reason vocabulary (**owned here**; WP04 only uses it) (`binary-missing | crash | timeout | malformed-reply | clock-overflow | rejected | busy`, no `disabled`); `protocol` request/reply value types for `produce` and `retain` (plan section 5, shapes R-4). **Covers** FR-029, FR-028 (policy), NFR-003.

## Subtask T016: ArchUnit and coverage

**Steps**: ArchUnit test in `:domain`: only `java.base`; no class reads a clock, environment variable or file. Each rule carries a floor (scanned count above zero) and a self-mutation case. The JaCoCo floors (95% line, 90% branch) are already wired into `check` by WP01 (T003); this WP only has to meet them, with no build-file edit. **Covers** NFR-005.

## Gates

`gradle -p services/event-producer :domain:check` (runs the JaCoCo verification) and `gradle -p services/event-producer check`; `test` alone does not enforce the floors. No Python in this WP. Red-first per behaviour (own commit before implementation, red on base, green on tip, revert-checked).

## Definition of Done

T011 to T016 done; floors met (`check` green); ArchUnit green with self-mutation cases; no dependency outside `java.base`.

## Risks

Over-modelling the registry (keep it data-driven); sort-key `source` tie-break is provisional until the events PR confirms.

## Reviewer Guidance

Verify nine fixtures are all exercised, UUID refusal is complete, and nothing in `:domain` touches a clock or file.

## Recorded out-of-map edits

None. Greenfield trees under `services/event-producer/domain/` are listed in `create_intent`.

## Implementation command

`spec-kitty agent action implement WP02 --agent claude --mission event-producer-reroute-01M4VDY9`
