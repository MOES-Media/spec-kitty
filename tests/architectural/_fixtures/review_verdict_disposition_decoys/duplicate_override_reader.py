"""Decoy fixture for ``test_review_verdict_disposition.py`` (T023.1 / acceptance Q2).

Not real production code — never imported, never shipped under ``src/``.
Reconstructs the *shape* of the retired ``post_merge`` duplicate
(``_snapshot_review_override``, WP04/Decision 6): it constructs a
``ReviewOverride`` from a ``review`` slot directly, with **no** file glob at
all. This is precisely the third blind spot research.md records — a Pass-1
(glob-only) enumeration is structurally unable to see it, because it
contains no glob call whatsoever. Only Pass 2 (override-construction search)
finds it.

This file is never parsed as real code and never imports ``ReviewOverride``
at runtime — the gate inspects it with ``ast.parse`` only, so the reference
to ``ReviewOverride.from_dict`` below needs to be syntactically valid Python,
not an importable one.
"""

from __future__ import annotations

from typing import Any


class ReviewOverride:  # decoy stand-in so this file parses standalone
    @staticmethod
    def from_dict(data: dict[str, Any]) -> ReviewOverride:
        raise NotImplementedError


def rogue_snapshot_override(state: dict[str, Any] | None) -> ReviewOverride | None:
    """Re-implement the override read independently of the canonical seam.

    No glob anywhere in this function — a Pass-1-only gate is structurally
    blind to it. It duplicates ``status.wp_review._review_override_from_state``
    instead of calling into it, which is exactly the drift class WP04 retired
    from ``post_merge/review_artifact_consistency.py``.
    """
    if state is None:
        return None
    review_raw = state.get("review")
    if not isinstance(review_raw, dict):
        return None
    return ReviewOverride.from_dict(review_raw)
