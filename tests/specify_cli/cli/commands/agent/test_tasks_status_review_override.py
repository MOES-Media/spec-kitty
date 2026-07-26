"""WP02 (T006) — tasks-status verdict honours the approval override.

Mission ``review-cycle-read-authority-01KYB6Z0``. The bug (research.md
Decision 3 — the same "annotation-blind read" trap WP01 hits on the
status-board surface): ``tasks_status_cmd.py`` built its per-WP snapshot with
``read_events`` + ``reduce(events)``. ``read_events`` deliberately partitions
``InnerStateChanged`` annotations OUT (``status/store.py:717``), so
``state["review"]`` — populated only inside the reducer's dedicated
annotation-fold pass — is ALWAYS absent from that snapshot. A recorded
approval override could therefore never clear a stale-verdict warning on the
tasks-status surface, no matter how complete or well-formed.

T006 pins the defect against a REAL event log: a lane-transition event plus
an ``InnerStateChanged`` annotation carrying a complete ``ReviewOverride``.
A hand-built ``state["review"]`` mapping would pass against the broken code
and prove nothing (it never exercises the annotation-partitioning read path),
so every override in this file is driven through
``append_annotations_atomic_verified`` — the same durable, verified append
primitive production code uses.

This test was committed RED (against the unfixed ``read_events`` +
``reduce(events)`` construction) before T007/T008's fix landed
(ADR ``2026-07-17-1``); it stays green as the regression guard afterward.
Remaining WP02 coverage (T009 degrade cases, T010 parity matrix, the
NFR-002 reduce-count spy) is added alongside the fix in the follow-up commit.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from ulid import ULID

from specify_cli.cli.commands.agent.tasks_parsing_validation import (
    _get_latest_review_cycle_verdict,
)
from specify_cli.cli.commands.agent.tasks_status_cmd import (
    _StatusState,
    _st_apply_review_flags,
    _st_load_work_packages,
)
from specify_cli.review.artifacts import ReviewCycleArtifact
from specify_cli.status.models import (
    InnerStateChanged,
    Lane,
    ReviewOverride,
    StatusEvent,
    WPInnerStateDelta,
)
from specify_cli.status.store import append_annotations_atomic_verified, append_event

pytestmark = pytest.mark.fast

_MISSION_SLUG = "wp02-fixture-mission"
_COMPLETE_OVERRIDE: dict[str, str] = {
    "at": "2026-07-20T12:00:00+00:00",
    "actor": "reviewer-renata",
    "wp_id": "WP01",
    "reason": "approved after fix",
}


# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------


def _write_wp_file(tasks_dir: Path, wp_id: str) -> None:
    tasks_dir.mkdir(parents=True, exist_ok=True)
    (tasks_dir / f"{wp_id}.md").write_text(
        "---\n"
        f"work_package_id: {wp_id}\n"
        f"title: {wp_id} example\n"
        "phase: Phase 1\n"
        "agent: claude\n"
        "history: []\n"
        "---\n\n# Body\n",
        encoding="utf-8",
    )


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


def _write_out_of_schema_verdict_artifact(tasks_dir: Path, wp_id: str, verdict: str) -> Path:
    """Write a canonical-field-name artifact whose *verdict* is outside
    ``REVIEW_ARTIFACT_VERDICTS`` (``{"approved", "rejected"}``).

    ``ReviewCycleArtifact.from_dict`` rejects this with ``ValueError`` even
    though every field name is correct — mirrors the real, already-committed
    ``auth-tranche-2-5-cli-contract-consumption-01KQEJZK`` WP05 cycle-2
    artifact (``verdict: approved_after_orchestrator_fix``).
    """
    artifact_dir = tasks_dir / wp_id
    artifact_dir.mkdir(parents=True, exist_ok=True)
    path = artifact_dir / "review-cycle-1.md"
    path.write_text(
        "---\n"
        "affected_files: []\n"
        "cycle_number: 1\n"
        f"mission_slug: {_MISSION_SLUG}\n"
        "reproduction_command:\n"
        "reviewed_at: '2026-07-19T11:00:00Z'\n"
        "reviewer_agent: reviewer-renata\n"
        f"verdict: {verdict}\n"
        f"wp_id: {wp_id}\n"
        "---\n\nReview body.\n",
        encoding="utf-8",
    )
    return path


def _write_legacy_field_name_artifact(tasks_dir: Path, wp_id: str, verdict: str) -> Path:
    """Write an artifact using PRE-schema frontmatter field names.

    ``work_package_id``/``review_cycle``/``reviewed_commit`` instead of
    ``wp_id``/``cycle_number``/``reviewed_at`` — ``ReviewCycleArtifact.from_dict``
    raises ``ValueError`` regardless of verdict, since the required fields are
    absent by name. Mirrors the real, already-committed
    ``execution-context-unification-01KTPKST`` WP08 cycle-1 artifact.
    """
    artifact_dir = tasks_dir / wp_id
    artifact_dir.mkdir(parents=True, exist_ok=True)
    path = artifact_dir / "review-cycle-1.md"
    path.write_text(
        "---\n"
        f"work_package_id: {wp_id}\n"
        "review_cycle: 1\n"
        f"verdict: {verdict}\n"
        "reviewer: reviewer-renata\n"
        "reviewed_commit: abc123\n"
        f"mission: {_MISSION_SLUG}\n"
        "---\n\nReview body.\n",
        encoding="utf-8",
    )
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

    This is the event-log write the approval-override flow performs (mirrors
    production's ``_persist_review_artifact_override``) — never a hand-built
    snapshot ``state["review"]`` mapping. Uses the durability-verified append
    primitive so the annotation is guaranteed readable back through
    ``read_event_stream`` before the fixture proceeds.
    """
    values = dict(_COMPLETE_OVERRIDE) if fields is None else dict(fields)
    values["wp_id"] = wp_id
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


def _run_status_state(tmp_path: Path, feature_dir: Path) -> _StatusState:
    st = _StatusState(mission=None, json_output=True, stale_threshold=60)
    st.main_repo_root = tmp_path
    st.feature_dir = feature_dir
    st.tasks_dir = feature_dir / "tasks"
    _st_load_work_packages(st)
    _st_apply_review_flags(st)
    return st


# ---------------------------------------------------------------------------
# T006 — red-first: prove the defect on THIS surface (kept green post-fix)
# ---------------------------------------------------------------------------


def test_stale_verdict_honours_complete_event_sourced_override(tmp_path: Path) -> None:
    """A complete, event-sourced override clears the tasks-status stale-verdict warning.

    Before T007/T008, ``tasks_status_cmd`` read the event log through
    ``read_events`` + ``reduce(events)`` — annotation-blind — so this override
    was invisible and the assertion below failed (RED): the stale warning
    fired regardless of the override. This is the surface-level regression
    guard for that fix.
    """
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")
    _write_rejected_artifact(feature_dir / "tasks", "WP01")
    _approve(feature_dir, "WP01")
    _record_override(feature_dir, "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert "WP01" not in stale_wp_ids, (
        "A complete event-sourced override must clear the stale-verdict "
        "warning on the tasks-status surface"
    )
    row = next(w for w in st.work_packages if w["id"] == "WP01")
    assert not row.get("_stale_verdict")


def test_stale_verdict_still_fires_without_any_override(tmp_path: Path) -> None:
    """Negative control: a genuine, unresolved rejection still warns (no regression)."""
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")
    _write_rejected_artifact(feature_dir / "tasks", "WP01")
    _approve(feature_dir, "WP01")
    # No override recorded anywhere.

    st = _run_status_state(tmp_path, feature_dir)

    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert "WP01" in stale_wp_ids
    row = next(w for w in st.work_packages if w["id"] == "WP01")
    assert row.get("_stale_verdict") is True


# ---------------------------------------------------------------------------
# T009 — degrade cases: never raise, always render
# ---------------------------------------------------------------------------


def test_absent_event_log_degrades(tmp_path: Path) -> None:
    """No ``status.events.jsonl`` at all — the status command still renders."""
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    assert st.events == []
    assert st.snapshot is None
    assert len(st.work_packages) == 1  # golden-count: cardinality-is-contract


def test_unparseable_event_log_degrades(tmp_path: Path) -> None:
    """A corrupt events file degrades ``st.events``/``st.snapshot``, never raises.

    Isolates T007's specific read (``tasks_status_cmd.py``'s ``st.events``/
    ``st.snapshot`` construction, wrapped in the pre-existing bare except at
    what was ``:279-283``) from the unrelated, pre-existing per-WP runtime-row
    reader (``_st_runtime_row`` -> ``reconstruct_wp_view`` ->
    ``read_event_stream``), which performs its own, separate, UN-caught read
    of the same file and is out of this WP's scope (not touched by T007/T008;
    ``tasks_status_cmd.py``'s outer ``_do_status`` try/except is what protects
    the operator from that path, not this one). No WP frontmatter file is
    written here so that unrelated per-WP path is never reached, isolating the
    read this WP actually changed.
    """
    import typer

    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    feature_dir.mkdir(parents=True, exist_ok=True)
    (feature_dir / "status.events.jsonl").write_text("not-json\n", encoding="utf-8")

    st = _StatusState(mission=None, json_output=True, stale_threshold=60)
    st.main_repo_root = tmp_path
    st.feature_dir = feature_dir
    st.tasks_dir = feature_dir / "tasks"
    st.tasks_dir.mkdir(parents=True, exist_ok=True)

    # No WP*.md files exist, so _st_load_work_packages exits via typer.Exit(0)
    # ("No work packages found") AFTER the try/except under test has already
    # run — the degrade is asserted on the mutated state object regardless.
    with pytest.raises(typer.Exit):
        _st_load_work_packages(st)

    assert st.events == []
    assert st.snapshot is None


def test_unparseable_review_artifact_frontmatter_degrades(tmp_path: Path) -> None:
    """A malformed review-cycle artifact degrades the stale check, never raises."""
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")
    artifact_dir = feature_dir / "tasks" / "WP01"
    artifact_dir.mkdir(parents=True, exist_ok=True)
    (artifact_dir / "review-cycle-1.md").write_text("no frontmatter here\n", encoding="utf-8")
    _approve(feature_dir, "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    assert st.stale_verdicts == []
    row = next(w for w in st.work_packages if w["id"] == "WP01")
    assert not row.get("_stale_verdict")


def test_missing_work_package_directory_degrades(tmp_path: Path) -> None:
    """No ``tasks/<wp>/`` review-artifact directory at all — degrades, no raise."""
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")
    _approve(feature_dir, "WP01")
    # Deliberately no tasks/WP01/ directory — no review-cycle artifacts exist.

    st = _run_status_state(tmp_path, feature_dir)

    assert st.stale_verdicts == []
    row = next(w for w in st.work_packages if w["id"] == "WP01")
    assert not row.get("_stale_verdict")


# ---------------------------------------------------------------------------
# T010 — parity with the canonical read
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("override_fields", "expect_stale"),
    [
        pytest.param(dict(_COMPLETE_OVERRIDE), False, id="overridden_rejection"),
        pytest.param(None, True, id="unoverridden_rejection"),
        pytest.param({**_COMPLETE_OVERRIDE, "at": ""}, True, id="incomplete_override_missing_at"),
        pytest.param(
            {**_COMPLETE_OVERRIDE, "actor": ""}, True, id="incomplete_override_missing_actor"
        ),
        pytest.param(
            {**_COMPLETE_OVERRIDE, "reason": ""}, True, id="incomplete_override_missing_reason"
        ),
    ],
)
def test_stale_verdict_matches_canonical_read(
    tmp_path: Path, override_fields: dict[str, str] | None, expect_stale: bool
) -> None:
    """The tasks-status surface must not disagree with the canonical read (INV-2).

    Each case asserts the tasks-status surface's stale/not-stale determination
    against ``latest_review_artifact_verdict`` — the shared canonical
    authority both this surface and the status board (WP01) must converge on.
    """
    from specify_cli.review.artifacts import latest_review_artifact_verdict
    from specify_cli.status import resolve_materialized_review

    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    tasks_dir = feature_dir / "tasks"
    _write_wp_file(tasks_dir, "WP01")
    _write_rejected_artifact(tasks_dir, "WP01")
    _approve(feature_dir, "WP01")
    if override_fields is not None:
        _record_override(feature_dir, "WP01", fields=override_fields)

    st = _run_status_state(tmp_path, feature_dir)

    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert ("WP01" in stale_wp_ids) is expect_stale

    canonical_override = None
    if st.snapshot is not None:
        canonical_override = resolve_materialized_review(st.snapshot, "WP01")
    canonical = latest_review_artifact_verdict(
        tasks_dir / "WP01", snapshot_override=canonical_override
    )
    assert canonical is not None
    canonical_is_stale = canonical.verdict == "rejected" and not canonical.has_override
    assert canonical_is_stale == expect_stale, (
        "tasks-status and the canonical read must agree on staleness (INV-2)"
    )


def test_no_records_at_all_is_not_stale(tmp_path: Path) -> None:
    """No review-cycle record at all is a NOT-an-error, not-stale state (data-model.md)."""
    from specify_cli.review.artifacts import latest_review_artifact_verdict

    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    _write_wp_file(feature_dir / "tasks", "WP01")
    _approve(feature_dir, "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    assert st.stale_verdicts == []
    canonical = latest_review_artifact_verdict(feature_dir / "tasks" / "WP01")
    assert canonical is None


# ---------------------------------------------------------------------------
# NFR-002 — reduce exactly once per invocation, never per work package
# ---------------------------------------------------------------------------


def test_reduce_called_exactly_once_for_multi_wp_mission(tmp_path: Path) -> None:
    """A spy on ``reduce`` proves a single reduction regardless of WP count.

    Absence of ``resolve_event_stream_review``/``resolve_snapshot_review`` by
    name is NOT sufficient evidence a fix respects NFR-002 — either still
    re-reduces the whole stream on every call. ``_st_load_work_packages``
    resolves ``reduce`` via a fresh ``from specify_cli.status import reduce``
    on every invocation (the package-level re-export bound once at import
    time in ``status/__init__.py``, from ``status.reducer.reduce``), so that
    is the seam this spies on — patching ``status.reducer.reduce`` directly
    would NOT intercept this call path, since the package-level name is
    already bound to the original function object.
    """
    from unittest.mock import patch

    import specify_cli.status as status_module

    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    tasks_dir = feature_dir / "tasks"
    wp_ids = ["WP01", "WP02", "WP03", "WP04"]
    for wp_id in wp_ids:
        _write_wp_file(tasks_dir, wp_id)
        _write_rejected_artifact(tasks_dir, wp_id)
        _approve(feature_dir, wp_id)
        _record_override(feature_dir, wp_id)

    with patch("specify_cli.status.reduce", wraps=status_module.reduce) as reduce_spy:
        st = _run_status_state(tmp_path, feature_dir)

    assert reduce_spy.call_count == 1, (
        f"Expected exactly ONE reduce() call for a {len(wp_ids)}-WP mission, "
        f"got {reduce_spy.call_count}"
    )
    # And the fix actually worked for every WP (not just cheaply avoided the spy).
    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert stale_wp_ids == set(), "every WP has a complete override and must not be stale"


# ---------------------------------------------------------------------------
# Fix-cycle-1 regression (review rejection item #1) — schema-failure fallback
#
# The first WP02 pass made ``_get_latest_review_cycle_verdict`` delegate
# ENTIRELY to the canonical, schema-validating ``latest_review_artifact_verdict``,
# whose ``except ValueError`` branch collapsed straight to ``(None, path)``.
# That is reachable against real, already-committed artifacts in this repo:
# ``auth-tranche-2-5-cli-contract-consumption-01KQEJZK`` WP05 cycle-2 carries
# ``verdict: approved_after_orchestrator_fix`` (a ``_VALID_VERDICTS`` member
# outside the canonical ``{"approved", "rejected"}`` pair), and
# ``execution-context-unification-01KTPKST`` WP08 cycle-1 uses the pre-schema
# frontmatter field names. Both flipped from "silently approvable" to "hard-
# refused, requires manual artifact repair" under the unfixed code. These
# tests pin the raw-frontmatter fallback that restores pre-WP02 parsing
# while keeping override-awareness (the fallback restores legacy PARSING,
# not legacy override-BLINDNESS).
# ---------------------------------------------------------------------------


def test_out_of_schema_verdict_falls_back_to_raw_parse(tmp_path: Path) -> None:
    """Canonical field names, verdict outside {"approved", "rejected"} → still parses.

    Real-shaped regression for the ``auth-tranche-2-5-...`` WP05 case: the
    schema-validating read raises ``ValueError`` on the verdict enum alone;
    the fallback must still surface the raw verdict, not collapse to None.
    """
    tasks_dir = tmp_path / "tasks"
    path = _write_out_of_schema_verdict_artifact(
        tasks_dir, "WP01", "approved_after_orchestrator_fix"
    )
    verdict, artifact = _get_latest_review_cycle_verdict(tasks_dir / "WP01")
    assert verdict == "approved_after_orchestrator_fix"
    assert artifact == path


def test_legacy_field_name_schema_falls_back_to_raw_parse(tmp_path: Path) -> None:
    """Pre-schema field names (``work_package_id``/``review_cycle``/
    ``reviewed_commit``) → still parses the verdict.

    Real-shaped regression for the ``execution-context-unification-...``
    WP08 case: the schema-validating read raises ``ValueError`` because the
    required field names are absent, regardless of verdict value.
    """
    tasks_dir = tmp_path / "tasks"
    path = _write_legacy_field_name_artifact(tasks_dir, "WP01", "changes_requested")
    verdict, artifact = _get_latest_review_cycle_verdict(tasks_dir / "WP01")
    assert verdict == "changes_requested"
    assert artifact == path


def test_out_of_schema_verdict_with_complete_override_resolves_approved(
    tmp_path: Path,
) -> None:
    """Override-awareness still wins for the raw-parse fallback (canonical field names).

    A ``rejected`` verdict recovered only via the raw-parse fallback must
    still fold to the effective ``"approved"`` verdict when a complete,
    event-sourced override exists (driven through a real event log via
    ``append_annotations_atomic_verified``, never a hand-built snapshot).
    """
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    tasks_dir = feature_dir / "tasks"
    _write_wp_file(tasks_dir, "WP01")
    _write_out_of_schema_verdict_artifact(tasks_dir, "WP01", "rejected")
    _approve(feature_dir, "WP01")
    _record_override(feature_dir, "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert "WP01" not in stale_wp_ids, (
        "an event-sourced override must resolve the raw-parse-fallback "
        "verdict to approved, not merely restore it as a stale rejection"
    )


def test_legacy_field_name_schema_with_complete_override_resolves_approved(
    tmp_path: Path,
) -> None:
    """Override-awareness still wins for the raw-parse fallback (legacy field names)."""
    feature_dir = tmp_path / "kitty-specs" / _MISSION_SLUG
    tasks_dir = feature_dir / "tasks"
    _write_wp_file(tasks_dir, "WP01")
    _write_legacy_field_name_artifact(tasks_dir, "WP01", "rejected")
    _approve(feature_dir, "WP01")
    _record_override(feature_dir, "WP01")

    st = _run_status_state(tmp_path, feature_dir)

    stale_wp_ids = {w["wp_id"] for w in st.stale_verdicts}
    assert "WP01" not in stale_wp_ids, (
        "an event-sourced override must resolve the raw-parse-fallback "
        "verdict to approved, not merely restore it as a stale rejection"
    )


def test_schema_and_raw_parse_both_fail_still_degrades_to_none(tmp_path: Path) -> None:
    """A schema-rejected artifact that ALSO has no extractable ``verdict``
    scalar still degrades to ``(None, path)`` — never raises.

    Distinct from the "no frontmatter delimiters at all" case: this artifact
    HAS well-formed frontmatter delimiters, so the raw-parse fallback's
    ``split_frontmatter`` succeeds, but there is no ``verdict:`` line for
    ``extract_scalar`` to find — both the schema layer and the raw-parse
    layer must independently fail-open for the ``(None, path)`` contract to
    hold.
    """
    tasks_dir = tmp_path / "tasks"
    wp_dir = tasks_dir / "WP01"
    wp_dir.mkdir(parents=True, exist_ok=True)
    artifact = wp_dir / "review-cycle-1.md"
    artifact.write_text(
        "---\nnot: frontmatter-that-has-no-verdict-key\n---\n", encoding="utf-8"
    )
    verdict, path = _get_latest_review_cycle_verdict(wp_dir)
    assert verdict is None
    assert path == artifact
