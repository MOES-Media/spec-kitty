"""WP04 (IC-05) — the snapshot-taking ``wp_review`` entry point.

``resolve_materialized_review`` is the single construction body every
``wp_review`` entry point delegates to after this mission's WP04: a
``review`` slot on a WP's reduced snapshot state becomes a typed
``ReviewOverride`` (or ``None``) in exactly one place. These tests pin:

- T017: the completeness matrix (complete / a required field entirely
  missing / absent slot / malformed slot), determinism (NFR-003), and that
  ``resolve_event_stream_review``/``resolve_snapshot_review`` are thin
  wrappers delegating to the one construction body rather than independent
  implementations.
- T019/T020: the ``post_merge`` consolidation (``_snapshot_review_override``
  retired in favour of this seam) still resolves a non-terminal lane and a
  missing artifact to "no finding", and still calls ``materialize`` exactly
  once per invocation regardless of work-package count (NFR-002 — no per-WP
  re-reduction was introduced).

No test mocks the reducer to force a pass for the integration-level cases —
review overrides not exercised here (complete / no-override / incomplete)
are already pinned end-to-end, against real event logs, by
``tests/regression/test_2684_review_override_recognition.py``, which this WP
must leave unmodified.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import Mock

import pytest

import specify_cli.status.wp_review as wp_review
from specify_cli.post_merge import review_artifact_consistency as gate
from specify_cli.review.artifacts import ReviewCycleArtifact
from specify_cli.status import materialize
from specify_cli.status.models import EventStream, Lane, ReviewOverride, StatusSnapshot
from specify_cli.status.wp_review import (
    resolve_event_stream_review,
    resolve_materialized_review,
    resolve_snapshot_review,
)
from tests.reliability.fixtures import (
    WorkPackageSpec,
    append_status_event,
    create_mission_fixture,
    write_work_package,
)

pytestmark = pytest.mark.fast

_REQUIRED_FIELDS = ("at", "actor", "wp_id", "reason")

_COMPLETE_REVIEW: dict[str, str] = {
    "at": "2026-07-20T12:00:00+00:00",
    "actor": "reviewer-renata",
    "wp_id": "WP01",
    "reason": "approved after fix",
}


def _snapshot(work_packages: dict[str, dict[str, Any]]) -> StatusSnapshot:
    """Build a ``StatusSnapshot`` directly — no reduction, no filesystem I/O."""
    return StatusSnapshot(
        mission_slug="test-mission",
        materialized_at="2026-07-25T00:00:00+00:00",
        event_count=0,
        last_event_id=None,
        work_packages=work_packages,
        summary={},
    )


def _write_rejected_artifact(artifact_dir: Path, *, wp_id: str = "WP01") -> Path:
    artifact_dir.mkdir(parents=True, exist_ok=True)
    artifact = ReviewCycleArtifact(
        cycle_number=1,
        wp_id=wp_id,
        mission_slug="release-320-workflow-reliability-01KQKV85",
        reviewer_agent="reviewer-renata",
        verdict="rejected",
        reviewed_at="2026-07-19T11:00:00+00:00",
        body="# Review\n\nVerdict: rejected — changes needed.\n",
    )
    path = artifact_dir / "review-cycle-1.md"
    artifact.write(path)
    return path


# ---------------------------------------------------------------------------
# T017 — complete override returned
# ---------------------------------------------------------------------------


def test_complete_override_returned() -> None:
    snapshot = _snapshot({"WP01": {"lane": "approved", "review": dict(_COMPLETE_REVIEW)}})

    override = resolve_materialized_review(snapshot, "WP01")

    assert override == ReviewOverride(**_COMPLETE_REVIEW)
    assert override is not None
    assert override.complete


# ---------------------------------------------------------------------------
# T017 — a required field entirely missing -> None, parameterised over all four
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("missing_field", _REQUIRED_FIELDS)
def test_review_slot_missing_required_field_returns_none(missing_field: str) -> None:
    review = {k: v for k, v in _COMPLETE_REVIEW.items() if k != missing_field}
    snapshot = _snapshot({"WP01": {"lane": "approved", "review": review}})

    assert resolve_materialized_review(snapshot, "WP01") is None


# ---------------------------------------------------------------------------
# T017 — absent slot -> None
# ---------------------------------------------------------------------------


def test_absent_review_slot_returns_none() -> None:
    snapshot = _snapshot({"WP01": {"lane": "approved"}})

    assert resolve_materialized_review(snapshot, "WP01") is None


def test_absent_work_package_returns_none() -> None:
    snapshot = _snapshot({})

    assert resolve_materialized_review(snapshot, "WP01") is None


# ---------------------------------------------------------------------------
# T017 — malformed slot -> None, no raise
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "malformed_review",
    ["not-a-mapping", 123, ["at", "actor", "wp_id", "reason"], None],
    ids=["string", "int", "list", "none"],
)
def test_malformed_review_slot_returns_none_without_raising(
    malformed_review: Any,
) -> None:
    snapshot = _snapshot({"WP01": {"lane": "approved", "review": malformed_review}})

    assert resolve_materialized_review(snapshot, "WP01") is None


# ---------------------------------------------------------------------------
# T017 — determinism (NFR-003)
# ---------------------------------------------------------------------------


def test_repeated_resolution_over_unchanged_snapshot_is_identical() -> None:
    snapshot = _snapshot({"WP01": {"lane": "approved", "review": dict(_COMPLETE_REVIEW)}})

    first = resolve_materialized_review(snapshot, "WP01")
    second = resolve_materialized_review(snapshot, "WP01")

    assert first == second


def test_resolution_does_not_depend_on_work_package_insertion_order() -> None:
    """Dict-insertion order of unrelated WPs must not change the answer."""
    forward = _snapshot(
        {
            "WP01": {"lane": "approved", "review": dict(_COMPLETE_REVIEW)},
            "WP02": {"lane": "for_review"},
            "WP03": {"lane": "done"},
        }
    )
    reversed_order = _snapshot(
        {
            "WP03": {"lane": "done"},
            "WP02": {"lane": "for_review"},
            "WP01": {"lane": "approved", "review": dict(_COMPLETE_REVIEW)},
        }
    )

    assert resolve_materialized_review(forward, "WP01") == resolve_materialized_review(
        reversed_order, "WP01"
    )


# ---------------------------------------------------------------------------
# T017/T020 — no reduction, no filesystem access
# ---------------------------------------------------------------------------


def test_resolve_materialized_review_performs_no_reduction_or_io(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    reduce_spy = Mock(side_effect=AssertionError("reduce must not be called"))
    read_spy = Mock(side_effect=AssertionError("read_event_stream must not be called"))
    monkeypatch.setattr(wp_review, "reduce", reduce_spy)
    monkeypatch.setattr(wp_review, "read_event_stream", read_spy)

    snapshot = _snapshot({"WP01": {"lane": "approved", "review": dict(_COMPLETE_REVIEW)}})
    override = resolve_materialized_review(snapshot, "WP01")

    assert override is not None
    reduce_spy.assert_not_called()
    read_spy.assert_not_called()


# ---------------------------------------------------------------------------
# T017 — exactly one construction body; the other two entry points delegate
# ---------------------------------------------------------------------------


def test_event_stream_and_feature_dir_entry_points_delegate_to_one_body(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``resolve_event_stream_review`` and ``resolve_snapshot_review`` are thin
    wrappers around ``resolve_materialized_review`` — not independent
    re-implementations. Patching the shared body changes both wrappers'
    output, proving the delegation rather than merely matching it."""
    calls: list[str] = []
    real = wp_review.resolve_materialized_review

    def _tracking(snapshot: StatusSnapshot, wp_id: str) -> ReviewOverride | None:
        calls.append(wp_id)
        return real(snapshot, wp_id)

    monkeypatch.setattr(wp_review, "resolve_materialized_review", _tracking)

    empty_stream = EventStream(transitions=[], annotations=[])
    resolve_event_stream_review(empty_stream, "WP-STREAM")
    resolve_snapshot_review(tmp_path, "WP-DIR")  # no events file -> empty stream

    assert calls == ["WP-STREAM", "WP-DIR"]


# ---------------------------------------------------------------------------
# T019 — post_merge consolidation stays behaviour-preserving
# ---------------------------------------------------------------------------


def test_non_terminal_lane_with_rejected_artifact_yields_no_finding(
    tmp_path: Path,
) -> None:
    mission = create_mission_fixture(tmp_path)
    write_work_package(mission, WorkPackageSpec())
    append_status_event(
        mission,
        from_lane=Lane.PLANNED,
        to_lane=Lane.IN_PROGRESS,
        event_id="01KQKV85INPROGRESS0000001",
    )
    artifact_dir = mission.tasks_dir / "WP01-regression-harness"
    _write_rejected_artifact(artifact_dir)

    findings = gate.find_rejected_review_artifact_conflicts(mission.mission_dir)

    assert findings == []


def test_terminal_lane_without_any_review_artifact_yields_no_finding(
    tmp_path: Path,
) -> None:
    mission = create_mission_fixture(tmp_path)
    write_work_package(mission, WorkPackageSpec())
    append_status_event(
        mission,
        from_lane=Lane.FOR_REVIEW,
        to_lane=Lane.APPROVED,
        event_id="01KQKV85APPROVED00000004",
    )
    # No review-cycle-*.md written anywhere under tasks_dir.

    findings = gate.find_rejected_review_artifact_conflicts(mission.mission_dir)

    assert findings == []


# ---------------------------------------------------------------------------
# T020 — no per-WP re-reduction: ``materialize`` called exactly once
# ---------------------------------------------------------------------------


def test_materialize_called_once_for_multi_work_package_mission(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    mission = create_mission_fixture(tmp_path)
    calls: list[Path] = []

    def _tracking(feature_dir: Path) -> Any:
        calls.append(feature_dir)
        return materialize(feature_dir)

    monkeypatch.setattr(gate, "materialize", _tracking)

    for index, wp_id in enumerate(("WP01", "WP02", "WP03"), start=1):
        spec = WorkPackageSpec(work_package_id=wp_id, title=f"Regression Harness {index}")
        write_work_package(mission, spec)
        append_status_event(
            mission,
            work_package_id=wp_id,
            from_lane=Lane.FOR_REVIEW,
            to_lane=Lane.APPROVED,
            event_id=f"01KQKV85MULTIWP0000000{index}",
        )
        artifact_dir = mission.tasks_dir / spec.file_stem
        _write_rejected_artifact(artifact_dir, wp_id=wp_id)

    findings = gate.find_rejected_review_artifact_conflicts(mission.mission_dir)

    assert len(findings) == 3
    assert len(calls) == 1, "materialize must be called exactly once, independent of WP count"
