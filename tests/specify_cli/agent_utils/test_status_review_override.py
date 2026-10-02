"""WP01 (review-cycle-read-authority): status-board verdict honours the
approval override.

FR-001/FR-002/FR-003/FR-006. The stale-verdict warning on the status board
(``show_kanban_status``) must respect a recorded event-log approval override
exactly the way the merge gate already does via
``status.wp_review.resolve_materialized_review``.

**Load-bearing fixture rule (T001/reviewer guidance)**: every override
scenario here drives the override THROUGH A REAL EVENT LOG — a genuine
``InnerStateChanged`` annotation persisted via the same production seam
(``wp_state.annotate`` + ``store.append_annotations_atomic_verified``, the
primitives ``status.emit.emit_inner_state_changed`` itself is built on).
No test constructs ``snapshot.work_packages[wp_id]["review"]`` by hand: a
fixture built that way would pass against the broken (annotation-blind
``read_events`` + ``reduce(events)``) implementation and prove nothing
(data-model.md's "Retrieval hazard").
"""

from __future__ import annotations

import json
import secrets
import textwrap
from pathlib import Path
from typing import Any

import pytest

from specify_cli.agent_utils.status import show_kanban_status
from specify_cli.review.artifacts import ReviewCycleArtifact
from specify_cli.status.models import ReviewOverride, WPInnerStateDelta
from specify_cli.status.store import append_annotations_atomic_verified
from specify_cli.status.wp_state import annotate

pytestmark = pytest.mark.fast

_COMPLETE_OVERRIDE_FIELDS: dict[str, str] = {
    "at": "2026-07-21T09:00:00+00:00",
    "actor": "arbiter-override",
    "wp_id": "WP01",
    "reason": "approved after fix verified",
}


# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------


def _create_wp_file(tasks_dir: Path, wp_id: str, title: str) -> None:
    content = textwrap.dedent(f"""\
        ---
        work_package_id: {wp_id}
        title: '{title}'
        ---
        # {wp_id}: {title}
    """)
    (tasks_dir / f"{wp_id}-stub.md").write_text(content, encoding="utf-8")


def _create_meta_json(feature_dir: Path, mission_slug: str) -> None:
    meta = {
        "mission_slug": mission_slug,
        "mission_number": "999",
        "mission_type": "software-dev",
        "phase": 2,
    }
    (feature_dir / "meta.json").write_text(json.dumps(meta), encoding="utf-8")


def _create_events_jsonl(feature_dir: Path, events: list[dict[str, Any]]) -> None:
    lines = [json.dumps(e) for e in events]
    (feature_dir / "status.events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


_CROCKFORD32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # matches status.models.ULID_PATTERN exactly


def _new_event_id() -> str:
    """A syntactically-valid ULID (26-char Crockford32, no I/L/O/U).

    ``InnerStateChanged.from_dict`` validates ``event_id`` against
    ``status.models.ULID_PATTERN``, unlike the looser ``StatusEvent``
    event_id — a real (if not chronologically monotonic) ULID shape is
    required here.
    """
    return "".join(secrets.choice(_CROCKFORD32) for _ in range(26))


def _create_project(tmp_path: Path, mission_slug: str) -> tuple[Path, Path, Path]:
    """Create a minimal spec-kitty project layout. Returns (repo_root, feature_dir, tasks_dir)."""
    kittify = tmp_path / ".kittify"
    kittify.mkdir()
    (kittify / "config.yaml").write_text("version: 1\n", encoding="utf-8")

    feature_dir = tmp_path / "kitty-specs" / mission_slug
    feature_dir.mkdir(parents=True)
    tasks_dir = feature_dir / "tasks"
    tasks_dir.mkdir()
    _create_meta_json(feature_dir, mission_slug)
    return tmp_path, feature_dir, tasks_dir


def _patch_roots(monkeypatch: pytest.MonkeyPatch, repo_root: Path) -> None:
    monkeypatch.chdir(repo_root)
    monkeypatch.setattr("specify_cli.agent_utils.status.locate_project_root", lambda cwd: repo_root)
    monkeypatch.setattr("specify_cli.agent_utils.status.get_main_repo_root", lambda r: repo_root)


def _write_rejected_artifact(
    wp_dir: Path, *, wp_id: str, mission_slug: str, cycle_number: int = 1
) -> Path:
    wp_dir.mkdir(parents=True, exist_ok=True)
    artifact = ReviewCycleArtifact(
        cycle_number=cycle_number,
        wp_id=wp_id,
        mission_slug=mission_slug,
        reviewer_agent="reviewer-renata",
        verdict="rejected",
        reviewed_at="2026-07-20T10:00:00+00:00",
        body="# Review\n\nVerdict: rejected — changes needed.\n",
    )
    path = wp_dir / f"review-cycle-{cycle_number}.md"
    artifact.write(path)
    return path


def _append_review_override(
    feature_dir: Path,
    *,
    wp_id: str,
    override: ReviewOverride,
    event_id: str,
    at: str = "2026-07-21T09:05:00+00:00",
) -> None:
    """Persist a REAL ``InnerStateChanged`` annotation via the production
    write seam. This is the load-bearing part of the fixture rule: the
    override must reach the verdict decision through the event log, never
    through a hand-built snapshot dict.
    """
    event = annotate(
        wp_id,
        WPInnerStateDelta(review=override),
        actor="reviewer-arbiter",
        at=at,
        event_id=event_id,
    )
    append_annotations_atomic_verified(feature_dir, [event])


def _done_transition_event(mission_slug: str, wp_id: str, event_id: str) -> dict[str, Any]:
    return {
        "event_id": event_id, "at": "2026-07-20T00:00:00+00:00",
        "feature_slug": mission_slug, "wp_id": wp_id,
        "from_lane": "approved", "to_lane": "done",
        "actor": "test", "force": True, "reason": None,
        "evidence": None, "review_ref": None, "execution_mode": "worktree",
    }


def _approved_transition_event(mission_slug: str, wp_id: str, event_id: str) -> dict[str, Any]:
    return {
        "event_id": event_id, "at": "2026-07-20T00:00:00+00:00",
        "feature_slug": mission_slug, "wp_id": wp_id,
        "from_lane": "for_review", "to_lane": "approved",
        "actor": "test", "force": True, "reason": None,
        "evidence": None, "review_ref": None, "execution_mode": "worktree",
    }


# ---------------------------------------------------------------------------
# T001 / T003 — the load-bearing positive case: a complete override
# suppresses the stale-rejection warning
# ---------------------------------------------------------------------------


def test_stale_verdict_suppressed_by_complete_override(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A rejected review-cycle record with a COMPLETE approval override
    recorded in the event log must NOT be reported stale.

    Before the WP01 fix (``read_events`` + ``reduce(events)`` — annotation-
    blind), this assertion fails: the override never reaches the verdict
    decision and the warning fires regardless."""
    mission_slug = "test-override-suppresses-stale"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)

    _create_wp_file(tasks_dir, "WP01", "Done Work")
    wp_dir = tasks_dir / "WP01-stub"
    _write_rejected_artifact(wp_dir, wp_id="WP01", mission_slug=mission_slug)

    _create_events_jsonl(feature_dir, [_done_transition_event(mission_slug, "WP01", "01AAA001")])
    _append_review_override(
        feature_dir,
        wp_id="WP01",
        override=ReviewOverride(**_COMPLETE_OVERRIDE_FIELDS),
        event_id=_new_event_id(),
    )

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    assert result.get("stale_verdicts", []) == []


# ---------------------------------------------------------------------------
# T005 — no false negative: the warning must still fire
# ---------------------------------------------------------------------------


def test_stale_verdict_still_fires_without_override(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """T005 case 1: a rejection with no override recorded at all must still
    warn — removing the false positive must not silence a real rejection."""
    mission_slug = "test-no-override-still-stale"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)

    _create_wp_file(tasks_dir, "WP01", "Done Work")
    wp_dir = tasks_dir / "WP01-stub"
    _write_rejected_artifact(wp_dir, wp_id="WP01", mission_slug=mission_slug)

    _create_events_jsonl(feature_dir, [_done_transition_event(mission_slug, "WP01", "01AAA001")])
    # Deliberately: no override annotation appended.

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    stale = result.get("stale_verdicts", [])
    assert len(stale) == 1  # golden-count: cardinality-is-contract
    assert stale[0]["wp_id"] == "WP01"


@pytest.mark.parametrize("missing_field", ["at", "actor", "wp_id", "reason"])
def test_stale_verdict_still_fires_for_incomplete_override(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, missing_field: str
) -> None:
    """T005 case 2: an override missing any one of the four required fields
    is inert everywhere (``ReviewOverride.complete`` is the only
    completeness authority) — the warning must still fire. Parameterised
    across all four omissions."""
    mission_slug = f"test-incomplete-override-{missing_field}"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)

    _create_wp_file(tasks_dir, "WP01", "Done Work")
    wp_dir = tasks_dir / "WP01-stub"
    _write_rejected_artifact(wp_dir, wp_id="WP01", mission_slug=mission_slug)

    _create_events_jsonl(feature_dir, [_done_transition_event(mission_slug, "WP01", "01AAA001")])

    fields = dict(_COMPLETE_OVERRIDE_FIELDS)
    fields[missing_field] = ""
    _append_review_override(
        feature_dir,
        wp_id="WP01",
        override=ReviewOverride(**fields),
        event_id=_new_event_id(),
    )

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    stale = result.get("stale_verdicts", [])
    assert len(stale) == 1  # golden-count: cardinality-is-contract
    assert stale[0]["wp_id"] == "WP01"


# ---------------------------------------------------------------------------
# T004 — tolerant degradation: absent/malformed inputs never raise
# ---------------------------------------------------------------------------


def test_show_kanban_status_survives_missing_event_log(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """T004 case 1: no status.events.jsonl at all -> the board still
    renders; no exception reaches the caller."""
    mission_slug = "test-missing-event-log"
    repo_root, _feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)
    _create_wp_file(tasks_dir, "WP01", "Some Work")
    # Deliberately: no status.events.jsonl written at all.

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)  # must not raise

    assert isinstance(result, dict)
    assert "error" not in result
    assert result.get("stale_verdicts", []) == []


def test_show_kanban_status_survives_malformed_event_log(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """T004 case 2: a corrupted status.events.jsonl must not raise out of
    show_kanban_status. The existing outer try/except is kept as the
    defensive posture (T002): the function returns a structured error dict
    instead of propagating an exception to the caller."""
    mission_slug = "test-malformed-event-log"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)
    _create_wp_file(tasks_dir, "WP01", "Some Work")
    (feature_dir / "status.events.jsonl").write_text("{not valid json\n", encoding="utf-8")

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)  # must not raise

    assert isinstance(result, dict)
    assert "error" in result


def test_show_kanban_status_survives_unparseable_review_frontmatter(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """T004 case 3: a present review-cycle-N.md with unparseable frontmatter
    degrades to "no verdict" for that WP -- the board still fully renders
    the WP as approved, with no stale warning and no exception."""
    mission_slug = "test-unparseable-frontmatter"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)
    _create_wp_file(tasks_dir, "WP01", "Approved Work")
    wp_dir = tasks_dir / "WP01-stub"
    wp_dir.mkdir()
    (wp_dir / "review-cycle-1.md").write_text(
        "# No frontmatter delimiters here at all\n", encoding="utf-8"
    )

    _create_events_jsonl(
        feature_dir, [_approved_transition_event(mission_slug, "WP01", "01AAA001")]
    )

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    assert result["by_lane"]["approved"] == 1
    assert result.get("stale_verdicts", []) == []


def test_show_kanban_status_survives_missing_wp_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """T004 case 4: an approved/done WP whose tasks/<slug>/ directory was
    never created (no review-cycle files exist anywhere) -> no verdict, no
    raise, no stale warning."""
    mission_slug = "test-missing-wp-directory"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)
    _create_wp_file(tasks_dir, "WP01", "Approved Work")
    # Deliberately: tasks_dir / "WP01-stub" is never created.

    _create_events_jsonl(
        feature_dir, [_approved_transition_event(mission_slug, "WP01", "01AAA001")]
    )

    _patch_roots(monkeypatch, repo_root)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    assert result["by_lane"]["approved"] == 1
    assert result.get("stale_verdicts", []) == []


# ---------------------------------------------------------------------------
# DoD — exactly one reduce() call per invocation (NFR-002)
# ---------------------------------------------------------------------------


def test_reduce_called_exactly_once_for_multi_wp_mission(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A spy on ``specify_cli.status.reducer.reduce`` asserts exactly ONE
    call for a mission with several work packages. Counting reads or
    checking for an absent function name is NOT sufficient: a per-WP
    ``resolve_event_stream_review``/``resolve_snapshot_review`` call would
    pass those checks while still re-reducing the stream once per work
    package, breaching NFR-002."""
    mission_slug = "test-reduce-once"
    repo_root, feature_dir, tasks_dir = _create_project(tmp_path, mission_slug)

    events = []
    for i, wp_id in enumerate(("WP01", "WP02", "WP03", "WP04"), start=1):
        _create_wp_file(tasks_dir, wp_id, f"Work {i}")
        wp_dir = tasks_dir / f"{wp_id}-stub"
        _write_rejected_artifact(wp_dir, wp_id=wp_id, mission_slug=mission_slug)
        events.append(_done_transition_event(mission_slug, wp_id, f"01AAA{i:03d}"))
    _create_events_jsonl(feature_dir, events)

    _patch_roots(monkeypatch, repo_root)

    # Patch target must be ``specify_cli.status.reduce``, NOT
    # ``specify_cli.status.reducer.reduce``. The code under test resolves
    # ``reduce`` via ``from specify_cli.status import ... reduce`` (the module
    # boundary requires going through the package facade), so the name is
    # looked up on the PACKAGE at call time. Patching the reducer submodule
    # attribute would not intercept it, and this spy would silently count
    # nothing while still asserting ``== 1`` against an un-incremented list.
    import specify_cli.status as status_reducer

    real_reduce = status_reducer.reduce
    calls: list[int] = []

    def _spy(*args: object, **kwargs: object) -> object:
        calls.append(1)
        return real_reduce(*args, **kwargs)

    monkeypatch.setattr(status_reducer, "reduce", _spy)

    result = show_kanban_status(mission_slug)

    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    assert len(result.get("stale_verdicts", [])) == 4
    assert len(calls) == 1, f"reduce() must be called exactly once, was called {len(calls)} times"
