---
title: 'ADR: the event producer is a Java native binary behind a thin Python client'
description: 'The local event producer is Java, shipped as a native binary in platform wheels; the CLI keeps only a thin client on the fan-out seam, with recorded gate choices and debt.'
status: Proposed
date: '2026-10-15'
---

# The event producer is a Java native binary behind a thin Python client

**Status:** Proposed (2026-10-15). It becomes Accepted when the operator merges the pull request of mission `event-producer-reroute`.

**Date:** 2026-10-15

**Deciders:** Stijn Dejongh (operator).

**Technical Story:**
- spec-kitty#6185, taken by the maintainer's request. It follows #6164 (removal of the hosted drain surfaces) and extends [ADR 2026-10-13-2](2026-10-13-2-the-event-ledger-is-the-publication-seam-default-on-with-local-retention.md), which carries an amendment dated 2026-10-15 for D-1, D-2 and D-3.
- It keeps the single-writer rule of the upstream ADR `2026-10-14-1-event-native-core-one-canonical-event-writer` (on `main`): each event type has one writer, and the thin client is the single in-CLI entry point to the producer.
- It follows the strangler pattern of #6117: new logic lands in Java, Python keeps only thin pass-through shims.

**Reader:** a maintainer changing the producer, its Python client, the platform wheels, or the gates that bound them.

---

## Context and Problem Statement

[ADR 2026-10-13-2](2026-10-13-2-the-event-ledger-is-the-publication-seam-default-on-with-local-retention.md) makes the ledger the publication seam and says publication is on by default. Something has to turn each persisted ledger row into a published row, never fail a lifecycle command, and never be silent about a failure. The forces:

- The project's direction is that new spec-kitty logic is written in Java, with a thin Python client as the single exception while the Python writers are retired (#6117, #6193).
- Users must need no setup: no JVM, no extra install step.
- The committed ledgers stay authoritative and are written by the existing Python writers until #6193 and #6195 move them.
- A producer fault must degrade to a recorded warning, not an error.

## Decision

1. **The producer is Java, built to a native image.** One request on stdin, one reply on stdout. It writes CloudEvents 1.0 structured-JSON rows to a gitignored local publish log, stamps a Lamport clock under a lock anchored at the git common directory, and owns local-tier retention. It consumes the released `spec-kitty-events` artifact at build time, pinned and checksummed, and vendors nothing.
2. **The CLI holds a thin client.** The client locates the binary, hands it the persisted row, reads the reply and records faults. It builds no envelope and holds no schema, id, clock, order, dedupe or type knowledge. It is registered on the fan-out seam, so it fires whether or not a hosted drain exists.
3. **Platform wheels carry the binary** (linux, macOS, Windows). A pure fallback wheel carries none and degrades with a `binary-missing` warning.
4. **Faults degrade with a warning.** A missing binary, crash, timeout or malformed reply never changes a lifecycle command's exit code or `--json` body, and always leaves a fault-log row and a stderr line.
5. **One narrow edit of existing Python:** the run-journal writer stamps a ULID `event_id` and a `row_version` on each new row (two keys and one import, no new function). Rows written before the stamp stay readable and are never rewritten.

## Deliberate choices recorded here

These are decisions, not oversights, so a later reader does not "fix" them.

**Java gates and JaCoCo floors.** The charter has no Java gate row, so the Java side is gated by its own build (`./gradlew check` on three operating systems): JaCoCo line floors per subproject (`:domain` 95 percent line and 90 percent branch, `:application` 90 percent line, `:adapter` 80 percent line, `:launcher` none), module dependency rules, ArchUnit tests, a pinned toolchain, verified dependency metadata, and planted-defect checks that go red for the right reason. The `:adapter` floor of 80 percent is below the charter's 90 percent new-code figure. That figure is written for pytest line coverage. `:adapter` carries the lock, the Foreign Function and Memory API and the per-OS paths, and those are proven by the three-operating-system CI legs rather than by line coverage alone. The charter itself is not amended in this mission; a charter row for the Java gates is proposed as a separate change.

**Bounded build glue.** The only new or edited Python outside the runtime client is packaging and release glue with no runtime logic: the hatch build hook (platform tag, executable bit), `check_dist_set.py`, the named release-script edits and the third entry of `gradle_pin_check.py`. It is pinned by `tests/architectural/test_build_glue_thin.py`. `scripts/lint_canonical_producers.py` is bounded gate tooling in the same category.

**The thinness gate's pinned lists are priced debt.** `tests/architectural/test_producer_client_thinness.py` pins the emission-area module census and the symbol and import sets of the existing files it allows the producer work to touch, in `producer_client_thinness_base.json`. They are fixed pins that fail on growth, not mutable allowlists. Owner: the #6193 mission (the event-log write side that retires the Python writers). Drain: they are deleted when #6193 or #6195 lands.

## Consequences

### Positive

- No JVM or setup step for users; the Python client is small and gated to stay so.
- One definition of every row shape and every id and clock rule, in Java, tested against the released `spec-kitty-events` fixtures.
- A producer failure can never break `spec-kitty next`, a lane move or a decision.

### Negative

- The release machinery grows: platform-tagged wheels, a native build per operating system, and a fallback wheel.
- Editable and source installs have no bundled binary, so each lifecycle row prints the not-produced warning until a binary is available.
- Until #6193 lands there are two languages in the event path, bounded by the gates above.

### Confirmation

- The thinness gate (rules (a) to (e)) passes and each of its self-mutations goes red.
- Run-journal rows carry a Crockford ULID `event_id` and `row_version` 1, and a pre-stamp journal is read unchanged.
- A lifecycle command under every fault class exits as it would without the producer.

## More Information

- [ADR 2026-10-13-2](2026-10-13-2-the-event-ledger-is-the-publication-seam-default-on-with-local-retention.md), amended 2026-10-15.
- `services/event-producer/README.md` for the build and gates.
- `spec-kitty-events` producer contract (registry, per-kind `data` schemas, conformance fixtures, total-order cases), release 10.5.0.
