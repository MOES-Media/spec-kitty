"""Canonical shared reader for the reduced-snapshot ``review`` slot.

Mission ``runtime-state-corpus-cutover`` — IC-04 / WP05 (D-14 campsite seam).
Extended by mission ``review-cycle-read-authority`` — WP04 (IC-05).

The merge gate (``merge/done_bookkeeping``), the CLI review-context
resolution (``cli/commands/agent/workflow_cores``), and the post-merge
review-artifact consistency gate (``post_merge/review_artifact_consistency``)
all used to interpret review-related state independently — either
*frontmatter* with different fallbacks, or a private re-implementation of the
snapshot read. This module is the single canonical interpretation of the
snapshot ``review`` slot (a :class:`ReviewOverride`), so every consumer reads
it through one seam.

The slot is populated as an off-axis ``InnerStateChanged`` delta
(``WPInnerStateDelta.review``) and stored by the reducer as a plain dict
(``ReviewOverride.to_dict()``).

Three entry points share **one** construction body
(:func:`_review_override_from_state`), differing only in what they are handed:

- :func:`resolve_materialized_review` — an already-materialized
  :class:`~specify_cli.status.models.StatusSnapshot` (e.g. from
  ``status.reducer.materialize``). No reduction, no filesystem access — O(1)
  per work package, so this is the only shape safe inside a per-WP loop
  (NFR-002).
- :func:`resolve_event_stream_review` — an already-read
  :class:`~specify_cli.status.models.EventStream`. Reduces once per call.
- :func:`resolve_snapshot_review` — a ``feature_dir``. Reads and reduces once
  per call; the most expensive shape.
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from specify_cli.status.models import EventStream, ReviewOverride, StatusSnapshot
from specify_cli.status.reducer import reduce
from specify_cli.status.store import read_event_stream


def _review_override_from_state(
    state: Mapping[str, Any] | None,
) -> ReviewOverride | None:
    """The single construction body: a WP's ``review`` slot -> typed override.

    Returns ``None`` — never raises — for an absent WP entry, an absent
    ``review`` slot, or a malformed one (wrong shape, or missing a required
    field). An *incomplete* override (some but not all of ``at``/``actor``/
    ``wp_id``/``reason`` populated) is still constructed and returned;
    :attr:`ReviewOverride.complete` is the caller's completeness gate.
    """
    if state is None:
        return None
    review_raw = state.get("review")
    if not isinstance(review_raw, Mapping):
        return None
    try:
        return ReviewOverride.from_dict(review_raw)
    except (KeyError, TypeError, ValueError):
        return None


def resolve_materialized_review(
    snapshot: StatusSnapshot,
    wp_id: str,
) -> ReviewOverride | None:
    """Return the review override from an already-materialized snapshot.

    Performs **no** reduction and **no** filesystem access — this indexes a
    :class:`StatusSnapshot` the caller already produced (e.g. via
    ``status.reducer.materialize``). This is the only entry point safe to call
    inside a per-work-package loop: reduce the mission's event stream exactly
    once, then look up each work package's ``review`` slot from that single
    snapshot (NFR-002).
    """
    return _review_override_from_state(snapshot.work_packages.get(wp_id))


def resolve_event_stream_review(
    event_stream: EventStream,
    wp_id: str,
) -> ReviewOverride | None:
    """Return the review override from an already-resolved event stream."""
    snapshot = reduce(event_stream.transitions, event_stream.annotations)
    return resolve_materialized_review(snapshot, wp_id)


def resolve_snapshot_review(feature_dir: Path, wp_id: str) -> ReviewOverride | None:
    """Return the reduced-snapshot ``review`` override for *wp_id*, or ``None``.

    ``read_event_stream`` → ``reduce`` → :func:`resolve_materialized_review`.
    Returns ``None`` when the WP has no snapshot entry or carries no
    ``review`` slot — never raises for the absent case.
    """
    return resolve_event_stream_review(read_event_stream(feature_dir), wp_id)
