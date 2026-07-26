"""Decoy fixture for ``test_review_verdict_disposition.py`` (T023.1).

Not real production code — never imported, never shipped under ``src/``.
Simulates a newly-added, override-blind verdict reader that globs
``review-cycle-*.md`` directly (the Pass-1 shape) without being registered in
the gate's disposition table. Used to prove the gate goes red for an
unclassified site instead of passing unnoticed.
"""

from __future__ import annotations

from pathlib import Path


def rogue_latest_verdict(sub_artifact_dir: Path) -> str | None:
    """Select the latest review-cycle record and return its verdict.

    Deliberately override-blind (no ``ReviewOverride`` consultation) — this is
    the exact defect class the mission exists to kill, reintroduced as a
    decoy so the gate's non-vacuity can be demonstrated against it.
    """
    candidates = sorted(sub_artifact_dir.glob("review-cycle-*.md"))
    if not candidates:
        return None
    return candidates[-1].read_text(encoding="utf-8")
