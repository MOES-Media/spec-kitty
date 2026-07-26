"""WP03 (T011/T012/T014/T015/T016) — the transition guard receives the
*effective* verdict, not the raw one.

Mission ``review-cycle-read-authority-01KYB6Z0``. The defect
(research.md Decisions 4/5/7): ``tasks_move_task.py`` called
``_get_latest_review_cycle_verdict`` positionally, so ``override`` defaulted
to ``None`` and the event-sourced :class:`~specify_cli.status.models.ReviewOverride`
never reached it. An operator who already supplied
``--skip-review-artifact-check --note <reason>`` to reach ``approved`` was
refused *again* on ``approved -> done``, forced to re-assert an override that
was already durably recorded and that the merge gate already honours.

T011 is the red-first regression test: it asserts the DESIRED end state
(transition permitted, no override flags re-supplied) against a fixture whose
override is driven through a REAL event log
(``append_annotations_atomic_verified`` + ``InnerStateChanged`` — never a
hand-built snapshot mapping, mirroring ``test_tasks_status_review_override.py``'s
T006 fixture rule). Before the fix (``tasks_move_task.py`` wired to
``read_event_stream_transactional`` + ``resolve_event_stream_review``), this
test fails RED — the guard refuses. It was committed in that failing state
before the fix landed (verifiable in history: the fix commit follows this
test's commit). T014 is the same test, now green.

T015 proves the transition guard's other refusal arms are untouched: no
override, an incomplete override (parameterised over each of
``at``/``actor``/``wp_id``/``reason`` missing), and an unparseable verdict all
still refuse with the EXACT existing message text — not merely "refused",
since a projection bug can flip one refusal into another and a lazy
assertion would still pass.

T016 proves override-evidence durability (INV-6): a first override still
persists its evidence exactly as today, that evidence survives a later
``approved -> done`` move that no longer re-triggers the persist arm (because
the verdict now reads ``approved`` rather than ``rejected``), and no
duplicate evidence is written.

``tasks_transition_core.py`` is untouched by this fix (C-002) — its arms
behave differently only because they are handed the corrected verdict.
"""

from __future__ import annotations

import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from click.testing import Result
from typer.testing import CliRunner
from ulid import ULID

from specify_cli.cli.commands.agent import tasks as tasks_module
from specify_cli.cli.commands.agent.tasks import app as tasks_app
from specify_cli.review.artifacts import ReviewCycleArtifact
from specify_cli.status.models import (
    InnerStateChanged,
    Lane,
    ReviewOverride,
    StatusEvent,
    WPInnerStateDelta,
)
from specify_cli.status.reducer import reduce as reduce_snapshot
from specify_cli.status.store import (
    append_annotations_atomic_verified,
    append_event,
    read_event_stream,
)
from tests.lane_test_utils import write_single_lane_manifest

pytestmark = [pytest.mark.integration, pytest.mark.git_repo]

_MISSION_SLUG = "wp03-guard-fixture"
_WP_ID = "WP01"
_COMPLETE_OVERRIDE: dict[str, str] = {
    "at": "2026-07-20T12:00:00+00:00",
    "actor": "reviewer-renata",
    "wp_id": _WP_ID,
    "reason": "approved after fix",
}

# The rejected-review-artifact refusal message (tasks_transition_core.py:379-383).
_REJECTED_MESSAGE = (
    f"{_WP_ID} has a rejected review artifact (review-cycle-1.md). "
    "Re-run with --skip-review-artifact-check --note <reason> "
    "to record an arbiter override."
)
# The unparseable-verdict refusal message (tasks_transition_core.py:373-376).
_UNPARSEABLE_MESSAGE_FRAGMENT = "has no parseable review verdict."


# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)


def _write_wp_file(tasks_dir: Path, wp_id: str) -> Path:
    tasks_dir.mkdir(parents=True, exist_ok=True)
    wp_file = tasks_dir / f"{wp_id}.md"
    wp_file.write_text(
        "---\n"
        f"work_package_id: {wp_id}\n"
        f"title: {wp_id} guard fixture\n"
        "execution_mode: code_change\n"
        "agent: reviewer-renata\n"
        "subtasks: []\n"
        "tracker_refs: []\n"
        "dependencies: []\n"
        "owned_files:\n  - src/**\n"
        "authoritative_surface: src/\n"
        "---\n\n# Body\n\n## Activity Log\n",
        encoding="utf-8",
    )
    return wp_file


def _write_rejected_artifact(tasks_dir: Path, wp_id: str) -> Path:
    artifact_dir = tasks_dir / wp_id
    path = artifact_dir / "review-cycle-1.md"
    ReviewCycleArtifact(
        cycle_number=1,
        wp_id=wp_id,
        mission_slug=_MISSION_SLUG,
        reviewer_agent="reviewer-renata",
        verdict="rejected",
        reviewed_at="2026-07-19T11:00:00+00:00",
        body="# Review\n\nVerdict: rejected — changes needed.\n",
    ).write(path)
    return path


def _write_unparseable_artifact(tasks_dir: Path, wp_id: str) -> Path:
    """A schema-valid-path but genuinely unparseable artifact (no frontmatter)."""
    artifact_dir = tasks_dir / wp_id
    artifact_dir.mkdir(parents=True, exist_ok=True)
    path = artifact_dir / "review-cycle-1.md"
    path.write_text("Not a frontmatter document at all.\n", encoding="utf-8")
    return path


def _approve(feature_dir: Path, wp_id: str) -> None:
    """Put *wp_id* in the terminal ``approved`` lane via a real transition event."""
    append_event(
        feature_dir,
        StatusEvent(
            event_id=str(ULID()),
            mission_slug=_MISSION_SLUG,
            wp_id=wp_id,
            from_lane=Lane.FOR_REVIEW,
            to_lane=Lane.APPROVED,
            at="2026-07-19T12:00:00+00:00",
            actor="operator",
            force=False,
            execution_mode="worktree",
        ),
    )


def _record_override(
    feature_dir: Path,
    wp_id: str,
    *,
    fields: dict[str, str] | None = None,
) -> None:
    """Append a REAL ``InnerStateChanged`` annotation carrying a ``ReviewOverride``.

    Mirrors production's ``_persist_review_artifact_override`` write shape —
    never a hand-built snapshot ``state["review"]`` mapping (the fixture rule:
    a hand-built mapping would pass against the unfixed
    ``read_events``-partitions-annotations-out code path and prove nothing).
    """
    values = dict(_COMPLETE_OVERRIDE) if fields is None else dict(fields)
    append_annotations_atomic_verified(
        feature_dir,
        [
            InnerStateChanged(
                event_id=str(ULID()),
                wp_id=wp_id,
                at="2026-07-20T12:00:01+00:00",
                actor="operator",
                delta=WPInnerStateDelta(review=ReviewOverride(**values)),
            )
        ],
    )


def _build_mission(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    unparseable: bool = False,
) -> tuple[Path, Path]:
    """A real git repo with WP01 approved and a rejected (or unparseable) verdict."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-b", "main")
    _git(repo, "config", "user.email", "wp03@example.invalid")
    _git(repo, "config", "user.name", "WP03 Guard Fixture")
    _git(repo, "config", "commit.gpgsign", "false")
    (repo / ".kittify").mkdir()
    (repo / ".kittify" / "config.yaml").write_text("auto_commit: false\n", encoding="utf-8")

    feature_dir = repo / "kitty-specs" / _MISSION_SLUG
    tasks_dir = feature_dir / "tasks"
    tasks_dir.mkdir(parents=True)
    write_single_lane_manifest(feature_dir, wp_ids=(_WP_ID,))

    (feature_dir / "tasks.md").write_text(
        f"# Tasks\n\n## {_WP_ID} - Guard Fixture\n\n(no subtasks)\n", encoding="utf-8"
    )
    _write_wp_file(tasks_dir, _WP_ID)
    if unparseable:
        _write_unparseable_artifact(tasks_dir, _WP_ID)
    else:
        _write_rejected_artifact(tasks_dir, _WP_ID)

    _git(repo, "add", ".")
    _git(repo, "commit", "-m", "seed WP03 guard fixture")

    _approve(feature_dir, _WP_ID)

    monkeypatch.chdir(repo)
    monkeypatch.setattr(tasks_module, "locate_project_root", lambda: repo)
    monkeypatch.setattr(
        tasks_module, "_validate_ready_for_review", lambda *_a, **_k: (True, [])
    )
    monkeypatch.setattr(tasks_module, "get_mission_type", lambda *_a, **_k: "software-dev")
    return repo, feature_dir


def _move_to_done(*, note: str | None = None, skip: bool = False) -> Result:
    args = [
        "move-task",
        _WP_ID,
        "--to",
        "done",
        "--mission",
        _MISSION_SLUG,
        "--no-auto-commit",
        "--done-override-reason",
        "ancestry bypass — guard-under-test is the rejected-verdict arm",
        "--json",
    ]
    if skip:
        args.append("--skip-review-artifact-check")
    if note is not None:
        args += ["--note", note]
    return CliRunner().invoke(tasks_app, args)


def _annotation_count(feature_dir: Path) -> int:
    return len(read_event_stream(feature_dir).annotations)


def _review_override_annotation_count(feature_dir: Path, wp_id: str) -> int:
    """Count annotations that actually carry a ``review`` (override-evidence)
    delta for *wp_id* — never conflate with the unrelated
    ``--done-override-reason`` activity-note annotation every ``--to done``
    move persists regardless of the review-override guard (a raw
    ``_annotation_count`` diff would wrongly flag that as a duplicate)."""
    return sum(
        1
        for a in read_event_stream(feature_dir).annotations
        if a.wp_id == wp_id and a.delta.review is not None
    )


def _review_slot(feature_dir: Path, wp_id: str) -> dict[str, object] | None:
    stream = read_event_stream(feature_dir)
    snapshot = reduce_snapshot(stream.transitions, stream.annotations)
    wp_state = snapshot.work_packages.get(wp_id) or {}
    review = wp_state.get("review")
    return dict(review) if isinstance(review, dict) else None


# ---------------------------------------------------------------------------
# T011 / T014 — the core defect: a complete event-sourced override must not
# force re-assertion on approved -> done.
# ---------------------------------------------------------------------------


def test_complete_override_permits_transition_without_reasserting_flags(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Red before the T012/T013 wiring landed; green afterward (T011 -> T014).

    WP01 is ``approved`` with a rejected ``review-cycle-1.md`` and a complete,
    event-sourced override already on record. ``move-task --to done`` with
    NO override flags must succeed — the operator already recorded the
    override; it must not need to be re-asserted.
    """
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch)
    _record_override(feature_dir, _WP_ID)

    result = _move_to_done()

    assert result.exit_code == 0, result.output
    stream = read_event_stream(feature_dir)
    latest = [e for e in stream.transitions if e.wp_id == _WP_ID][-1]
    assert str(latest.to_lane) == str(Lane.DONE)


# ---------------------------------------------------------------------------
# T015 — all three refusal arms stay intact, asserting the SPECIFIC message.
# ---------------------------------------------------------------------------


def test_no_override_still_refuses_with_rejected_artifact_message(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Negative control: a genuine, unresolved rejection still refuses (FR-003)."""
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch)
    # No override recorded anywhere.

    result = _move_to_done()

    assert result.exit_code != 0
    assert _REJECTED_MESSAGE in result.output, result.output


@pytest.mark.parametrize("missing_field", ["at", "actor", "wp_id", "reason"])
def test_incomplete_override_still_refuses(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, missing_field: str
) -> None:
    """An override missing any one of at/actor/wp_id/reason is NOT complete
    (C-004 — ``ReviewOverride.complete`` is the only completeness authority)
    and must still refuse with the unchanged rejected-artifact message."""
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch)
    fields = dict(_COMPLETE_OVERRIDE)
    fields[missing_field] = ""
    _record_override(feature_dir, _WP_ID, fields=fields)

    result = _move_to_done()

    assert result.exit_code != 0
    assert _REJECTED_MESSAGE in result.output, result.output


def test_unparseable_verdict_refusal_is_unchanged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A genuinely unparseable artifact still hits the "no parseable review
    verdict" refusal, override or not — the projection must degrade to
    ``None`` for this case, never silently to ``"approved"``."""
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch, unparseable=True)
    _record_override(feature_dir, _WP_ID)

    result = _move_to_done()

    assert result.exit_code != 0
    assert _UNPARSEABLE_MESSAGE_FRAGMENT in result.output, result.output


# ---------------------------------------------------------------------------
# T016 — override-evidence durability (INV-6).
# ---------------------------------------------------------------------------


def test_first_override_still_persists_evidence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A FIRST arbiter override (rejection, no prior override, flags supplied)
    still records evidence exactly as today — the override-aware projection
    only changes behaviour for an ALREADY-overridden WP."""
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch)
    before = _annotation_count(feature_dir)

    result = _move_to_done(skip=True, note="arbiter override: acceptable risk")

    assert result.exit_code == 0, result.output
    after = _annotation_count(feature_dir)
    assert after > before, "no override evidence was persisted on the first override"
    review = _review_slot(feature_dir, _WP_ID)
    assert review is not None
    assert review.get("reason") == "arbiter override: acceptable risk"


def test_prior_override_evidence_survives_second_move_without_duplication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Evidence from a first override is durable across a later
    ``approved -> done`` move that no longer re-triggers the persist arm
    (because the verdict now reads ``approved``), and no duplicate evidence
    is written.

    Sequence: WP01 starts ``approved`` with a rejected verdict. First move
    to ``done`` supplies the override flags (first-override persist fires).
    Roll back to ``approved`` and move to ``done`` again with NO flags — the
    fix means this now succeeds without re-firing the persist arm.
    """
    _repo, feature_dir = _build_mission(tmp_path, monkeypatch)

    first = _move_to_done(skip=True, note="arbiter override: acceptable risk")
    assert first.exit_code == 0, first.output

    review_annotations_after_first = _review_override_annotation_count(feature_dir, _WP_ID)
    assert review_annotations_after_first == 1
    review_after_first = _review_slot(feature_dir, _WP_ID)
    assert review_after_first is not None

    # Roll the WP back to approved via a real transition event (mirrors an
    # operator re-opening approval state without touching the override slot)
    # and move to done again with NO override flags. ``reduce()`` sorts
    # transitions by ``(at, event_id)`` ascending (status/reducer.py:315), so
    # this MUST carry a later timestamp than the first move's real
    # ``datetime.now(UTC)``-stamped event or the fold leaves the WP in
    # ``done`` regardless of append order.
    rollback_at = (datetime.now(UTC) + timedelta(seconds=1)).isoformat()
    append_event(
        feature_dir,
        StatusEvent(
            event_id=str(ULID()),
            mission_slug=_MISSION_SLUG,
            wp_id=_WP_ID,
            from_lane=Lane.DONE,
            to_lane=Lane.APPROVED,
            at=rollback_at,
            actor="operator",
            force=True,
            execution_mode="worktree",
        ),
    )

    second = _move_to_done()
    assert second.exit_code == 0, second.output

    review_annotations_after_second = _review_override_annotation_count(feature_dir, _WP_ID)
    assert review_annotations_after_second == review_annotations_after_first, (
        "a second approved -> done move re-fired the override-evidence persist "
        "arm and wrote a duplicate review-override annotation"
    )
    review_after_second = _review_slot(feature_dir, _WP_ID)
    assert review_after_second == review_after_first, (
        "prior override evidence changed across the second move"
    )
