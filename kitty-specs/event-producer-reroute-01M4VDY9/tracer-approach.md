# Tracer: approach

Seeded at plan time; append during implementation, assess at close.

- Order: baseline capture, docs-only campsite commit, WP01 native-image spike on three OS first (the only step that can change the wheel floor), then Java domain and adapters, protocol, retention, the Python client, packaging, release workflow, integration.
- The Java side is built and gated before the Python client is wired, so the client is written against a real protocol, not a guess.
- Every behaviour: test committed red first, verified red on the base, green on the tip, revert-checked.
- Python is limited to the allowed-surface table of `plan.md` section 4.2; anything else is a finding.
- Schema-reading work waits for the released events artifact; everything else proceeds against a stub.
- #6164 is handled by keeping the `status/adapters.py` edits in four separable commits (plan section 12).

## Assessment at close

(to fill)
