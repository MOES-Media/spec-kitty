# Contracts — none for this mission

This mission adds **no** API, endpoint, event, webhook, or externally visible payload. It corrects
an internal read: three sites derived a review verdict from the latest review-cycle record without
consulting the event-sourced approval override, and now route through the canonical override-aware
read instead.

The directory exists because the software-dev mission type requires it as a path convention. It is
deliberately empty of contract documents rather than populated with fabricated ones — inventing a
schema to satisfy a directory check would be worse than an honest placeholder.

## What plays the role a contract would

The behavioural surface this mission had to hold stable is captured elsewhere:

- **`../data-model.md`** — the resolution rule and the effective-verdict projection, plus the six
  invariants (INV-1 … INV-6) that constrain it.
- **`../spec.md`** — the In-Scope Rule and constraints C-001 … C-006, including the two that pin
  behaviour: C-001 (no artifact changes partition) and C-002 (correct the guard's input, never its
  rules).
- **`tests/architectural/test_review_verdict_disposition.py`** — the executable form: a two-pass
  gate that fails when a new verdict-deriving reader appears without a recorded disposition.

The last of these is the closest thing this mission has to a contract, because it is the artifact
that outlives it.
