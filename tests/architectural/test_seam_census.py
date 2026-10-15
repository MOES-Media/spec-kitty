"""Every persisted row kind reaches the fan-out seam exactly once (FR-003, AC-004).

The census derives its universe from the persisted row kinds themselves, with no hand list of
writers: the lifecycle registry (``LIFECYCLE_EVENT_TYPES``), the Decision Moment registry, the
retrospective event classes, the lane and annotation rows, and the run-journal kinds of the
``RuntimeEventEmitter`` Protocol. One forced row per writer must yield exactly one seam call whose
``row`` equals the ledger line the writer persisted; a kind in no driver, a writer that persists
with no seam call, or a truncated driver set fails the census.

Named exemptions, each failing when its premise stops holding: the three registered Decision
Moment kinds with no emitter in ``src/``, and the run-journal ``DecisionTimeoutExpired`` (out of
scope, plan 4.2).
"""

from __future__ import annotations

import ast
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from specify_cli.status import adapters
from specify_cli.status.lifecycle_events import (
    _DECISION_POINT_EVENT_TYPES,
    LIFECYCLE_EVENT_TYPES,
    MISSION_CREATED,
    MISSION_REOPENED,
    FOLLOW_UP_RECORDED,
    PLAN_COMPLETED,
    PLAN_STARTED,
    PROJECT_INITIALIZED,
    REVIEWER_SELF_APPROVAL,
    SPECIFY_COMPLETED,
    SPECIFY_STARTED,
    TASKS_COMPLETED,
    TASKS_STARTED,
    WP_CREATED,
    emit_artifact_phase,
    emit_follow_up_recorded,
    emit_mission_created_local,
    emit_mission_reopened,
    emit_project_initialized,
    emit_reviewer_self_approval,
    emit_wp_created_local,
)
from specify_cli.status.models import TransitionRequest, WPInnerStateDelta

pytestmark = [pytest.mark.architectural, pytest.mark.real_event_producer]

_SRC = Path(__file__).resolve().parents[2] / "src"
_SLUG = "census-mission-01KCENSUS"
_MISSION_ID = "01KCENSUS0000000000000MSN1"
_RUN_ID = "0123456789abcdef0123456789abcdef"
_JOURNAL_KEYS = {"event_id", "row_version", "event_type", "timestamp", "payload"}

#: The Decision Moment kinds that are registered but have no emitter anywhere in ``src/``. The
#: entry FAILS when an emitter appears (``test_the_named_exemption_fails_when_an_emitter_appears``).
_DECISION_KINDS_WITHOUT_EMITTER = frozenset({"DecisionPointDeferred", "DecisionPointCanceled", "DecisionPointWidened"})
#: Run-journal kinds the seam does not carry (plan 4.2: ``DecisionTimeoutExpired`` stays out of scope).
_JOURNAL_EMITTERS_OUT_OF_SCOPE = frozenset({"emit_decision_timeout_expired"})


# --- the world a driver writes into ------------------------------------------------------------


class World:
    """A temporary project, a recording seam and the helpers a driver needs."""

    def __init__(self, root: Path) -> None:
        self.root = root
        (root / ".git").mkdir(parents=True, exist_ok=True)
        (root / ".kittify").mkdir(exist_ok=True)
        self.feature_dir = root / "kitty-specs" / _SLUG
        self.feature_dir.mkdir(parents=True, exist_ok=True)
        (self.feature_dir / "meta.json").write_text(json.dumps({"mission_id": _MISSION_ID, "mission_slug": _SLUG, "mid8": _MISSION_ID[:8]}), encoding="utf-8")
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def record_seam(self) -> None:
        adapters.reset_handlers()
        adapters.register_status_fanout_handler(lambda **kwargs: self.calls.append(("status", kwargs)))
        adapters.register_lifecycle_fanout_handler(lambda **kwargs: self.calls.append(("lifecycle", kwargs)))

    def ledger(self, path: Path) -> list[dict[str, Any]]:
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    @property
    def status_log(self) -> Path:
        return self.feature_dir / "status.events.jsonl"

    @property
    def project_log(self) -> Path:
        return self.root / ".kittify" / "canonical-events.jsonl"

    @property
    def journal(self) -> Path:
        return self.root / ".kittify" / "runtime" / "runs" / _RUN_ID / "run.events.jsonl"


Driver = Callable[[World, pytest.MonkeyPatch], Path]
"""Forces the writer once and returns the ledger file the rows land in."""


def _lifecycle(call: Callable[[World], Any]) -> Driver:
    def driver(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
        monkeypatch.setattr("specify_cli.status.lifecycle.is_mission_completed", lambda _fd: True)
        call(world)
        return world.status_log

    return driver


def _phase(event_type: str) -> Driver:
    return _lifecycle(
        lambda w: emit_artifact_phase(
            w.feature_dir, event_type=event_type, mission_slug=_SLUG, artifact_path="spec.md", summary="census", wp_count=1, repo_root=w.root
        )
    )


def _project_initialized(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
    emit_project_initialized(world.root, project_uuid="11111111-1111-4111-8111-111111111111", project_slug="census")
    return world.project_log


def _seed(world: World) -> None:
    from tests.status.conftest import seed_wp_to_planned

    seed_wp_to_planned(world.feature_dir, "WP01", slug=_SLUG)


def _request(world: World, to_lane: str = "claimed", **extra: Any) -> TransitionRequest:
    return TransitionRequest(feature_dir=world.feature_dir, mission_slug=_SLUG, wp_id="WP01", to_lane=to_lane, actor="census", repo_root=world.root, **extra)


def _lane_transition(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
    from specify_cli.status.emit import emit_status_transition

    _seed(world)
    world.calls.clear()
    emit_status_transition(_request(world))
    return world.status_log


def _lane_with_bundled_annotation(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
    from specify_cli.status.emit import emit_status_transition

    _seed(world)
    world.calls.clear()
    emit_status_transition(_request(world, annotation_delta=WPInnerStateDelta(note="bundled")))
    return world.status_log


def _batch(world: World, monkeypatch: pytest.MonkeyPatch, *, annotate: bool = False) -> Path:
    from specify_cli.status.emit import emit_status_transition_batch

    _seed(world)
    world.calls.clear()
    claim = _request(world)
    start = _request(world, "in_progress", workspace_context="worktree:/nonexistent/wp01")
    if annotate:
        start.annotation_delta = WPInnerStateDelta(note="batch binding")
    emit_status_transition_batch([claim, start])
    return world.status_log


def _standalone_annotation(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
    from specify_cli.status.emit import emit_inner_state_changed

    _seed(world)
    world.calls.clear()
    emit_inner_state_changed(world.feature_dir, "WP01", WPInnerStateDelta(note="standalone"), actor="census", mission_slug=_SLUG, repo_root=world.root)
    return world.status_log


def _decision(opened: bool) -> Driver:
    def driver(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
        from kernel.clock import UTC, datetime
        from specify_cli.decisions.emit import emit_decision_opened, emit_decision_resolved
        from specify_cli.decisions.models import DecisionStatus
        from tests.specify_cli.decisions.test_emit import _make_entry

        decision_id = "01KCENSUSDECISION00000000A"
        if opened:
            emit_decision_opened(world.root, _SLUG, decision_id=decision_id, entry=_make_entry(decision_id), actor="census")
        else:
            entry = _make_entry(
                decision_id, status=DecisionStatus.RESOLVED, final_answer="oauth2", resolved_at=datetime(2026, 4, 23, 10, 6, 0, tzinfo=UTC), resolved_by="census"
            )
            emit_decision_resolved(world.root, _SLUG, decision_id=decision_id, entry=entry, actor="census")
        return world.status_log

    return driver


def _retrospective(which: str) -> Driver:
    def driver(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
        from specify_cli.retrospective import lifecycle_events as retro
        from tests.retrospective.test_events import MISSION_ID, MISSION_SLUG, make_gen_record

        actor = retro.Actor(kind="runtime", id="census")
        feature_dir = world.root / "kitty-specs" / MISSION_SLUG
        feature_dir.mkdir(parents=True, exist_ok=True)
        if which == "captured":
            retro.emit_captured(make_gen_record(), world.root, provenance_kind="explicit_create", actor=actor)
        elif which == "failed":
            retro.emit_capture_failed(
                MISSION_ID,
                MISSION_SLUG,
                world.root,
                failure_category="other",
                failure_message="census",
                remediation_hint=None,
                policy_source={},
                attempted_provenance_kind="explicit_create",
                missing_artifacts=None,
                actor=actor,
            )
        else:
            retro.emit_skipped(MISSION_ID, MISSION_SLUG, world.root, skip_reason="census", skip_reason_source="cli_flag", policy_source={}, actor=actor)
        return feature_dir / "status.events.jsonl"

    return driver


def _journal(emitter_method: str) -> Driver:
    """The run-journal route: the engine appends the record, then calls the emitter."""

    def driver(world: World, monkeypatch: pytest.MonkeyPatch) -> Path:
        from types import SimpleNamespace

        from spec_kitty_events.mission_next import (
            DecisionInputAnsweredPayload,
            DecisionInputRequestedPayload,
            MissionRunCompletedPayload,
            MissionRunStartedPayload,
            NextStepAutoCompletedPayload,
            NextStepIssuedPayload,
            RuntimeActorIdentity,
        )
        from specify_cli.events.runtime_moments import RuntimeMomentProducer

        actor = RuntimeActorIdentity(actor_id="census", actor_type="llm")
        payloads: dict[str, tuple[str, Any]] = {
            "emit_mission_run_started": ("MissionRunStarted", MissionRunStartedPayload(run_id=_RUN_ID, mission_type="software-dev", actor=actor)),
            "emit_next_step_issued": ("NextStepIssued", NextStepIssuedPayload(run_id=_RUN_ID, step_id="s", agent_id="a", actor=actor)),
            "emit_next_step_auto_completed": (
                "NextStepAutoCompleted",
                NextStepAutoCompletedPayload(run_id=_RUN_ID, step_id="s", agent_id="a", result="success", actor=actor),
            ),
            "emit_decision_input_requested": (
                "DecisionInputRequested",
                DecisionInputRequestedPayload(run_id=_RUN_ID, decision_id="input:x", step_id="s", question="q?", options=("a",), input_key="x", actor=actor),
            ),
            "emit_decision_input_answered": ("DecisionInputAnswered", DecisionInputAnsweredPayload(run_id=_RUN_ID, decision_id="input:x", answer="a", actor=actor)),
            "emit_mission_run_completed": ("MissionRunCompleted", MissionRunCompletedPayload(run_id=_RUN_ID, mission_type="software-dev", actor=actor)),
            "emit_significance_evaluated": (
                "SignificanceEvaluated",
                SimpleNamespace(model_dump=lambda mode: {"run_id": _RUN_ID, "decision_id": "audit:g", "step_id": "s"}),
            ),
            "emit_decision_authority_denied": (
                "DecisionAuthorityDenied",
                {"run_id": _RUN_ID, "decision_id": "audit:g", "reason": "denied", "rationale_linkage": None},
            ),
        }
        kind, payload = payloads[emitter_method]
        dumped = payload if isinstance(payload, dict) else payload.model_dump(mode="json")
        world.journal.parent.mkdir(parents=True, exist_ok=True)
        with world.journal.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"event_type": kind, "timestamp": "2026-10-15T00:00:00+00:00", "payload": dumped}, sort_keys=True) + "\n")
        producer = RuntimeMomentProducer.for_mission(feature_dir=world.feature_dir, mission_slug=_SLUG, mission_type="software-dev")
        getattr(producer, emitter_method)(payload)
        return world.journal

    return driver


# --- the writers, keyed by what they persist ---------------------------------------------------

_LIFECYCLE_WRITERS: dict[str, Driver] = {
    PROJECT_INITIALIZED: _project_initialized,
    MISSION_CREATED: _lifecycle(
        lambda w: emit_mission_created_local(
            w.feature_dir, mission_slug=_SLUG, mission_id=_MISSION_ID, mission_number=None, mission_type="software-dev", target_branch="main", repo_root=w.root
        )
    ),
    SPECIFY_STARTED: _phase(SPECIFY_STARTED),
    SPECIFY_COMPLETED: _phase(SPECIFY_COMPLETED),
    PLAN_STARTED: _phase(PLAN_STARTED),
    PLAN_COMPLETED: _phase(PLAN_COMPLETED),
    TASKS_STARTED: _phase(TASKS_STARTED),
    TASKS_COMPLETED: _phase(TASKS_COMPLETED),
    WP_CREATED: _lifecycle(lambda w: emit_wp_created_local(w.feature_dir, mission_slug=_SLUG, wp_id="WP01", wp_title="t", repo_root=w.root)),
    REVIEWER_SELF_APPROVAL: _lifecycle(
        lambda w: emit_reviewer_self_approval(w.feature_dir, mission_slug=_SLUG, wp_id="WP01", implementing_actor="a", intended_reviewer="r", failure_reason="none")
    ),
    MISSION_REOPENED: _lifecycle(lambda w: emit_mission_reopened(w.feature_dir, mission_id=_MISSION_ID, mission_slug=_SLUG, reason="census", reopened_by="census")),
    FOLLOW_UP_RECORDED: _lifecycle(
        lambda w: emit_follow_up_recorded(
            w.feature_dir, mission_id=_MISSION_ID, mission_slug=_SLUG, follow_up_type="commit", commit_sha="a" * 40, recorded_by="census"
        )
    ),
}

_DECISION_WRITERS: dict[str, Driver] = {"DecisionPointOpened": _decision(True), "DecisionPointResolved": _decision(False)}

_RETROSPECTIVE_WRITERS: dict[str, Driver] = {
    "RetrospectiveCaptured": _retrospective("captured"),
    "RetrospectiveCaptureFailed": _retrospective("failed"),
    "RetrospectiveSkipped": _retrospective("skipped"),
}

_STATUS_WRITERS: dict[str, Driver] = {
    "StatusEvent": _lane_transition,
    "StatusEvent (batch)": _batch,
    "InnerStateChanged (standalone)": _standalone_annotation,
    "InnerStateChanged (bundled by emit_status_transition)": _lane_with_bundled_annotation,
    "InnerStateChanged (bundled by emit_status_transition_batch)": lambda w, m: _batch(w, m, annotate=True),
}


def _journal_emitter_methods() -> set[str]:
    from runtime.next._internal_runtime.events import RuntimeEventEmitter

    return {name for name in RuntimeEventEmitter.__dict__ if name.startswith("emit_")} - _JOURNAL_EMITTERS_OUT_OF_SCOPE


_JOURNAL_WRITERS: dict[str, Driver] = {name: _journal(name) for name in sorted(_journal_emitter_methods())}


def all_writers() -> dict[str, Driver]:
    return {**_LIFECYCLE_WRITERS, **_DECISION_WRITERS, **_RETROSPECTIVE_WRITERS, **_STATUS_WRITERS, **_JOURNAL_WRITERS}


# --- the universe, derived ---------------------------------------------------------------------


def derived_universe() -> set[str]:
    """Every persisted row kind that must have a writer driven here, derived from the code's own registries."""
    retro_module = ast.parse((_SRC / "specify_cli/retrospective/lifecycle_events.py").read_text(encoding="utf-8"))
    retrospective = {node.name for node in retro_module.body if isinstance(node, ast.ClassDef) and node.name.startswith("Retrospective")}
    return (
        set(LIFECYCLE_EVENT_TYPES)
        | (set(_DECISION_POINT_EVENT_TYPES) - _DECISION_KINDS_WITHOUT_EMITTER)
        | retrospective
        | {"StatusEvent", "InnerStateChanged"}
        | {f"journal:{name}" for name in _journal_emitter_methods()}
    )


def _kind_of(writer_name: str) -> str:
    base = writer_name.split(" (")[0]
    return f"journal:{base}" if base.startswith("emit_") else base


def check_writers(writers: dict[str, Driver], universe: set[str], tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """The census proper: coverage of the universe, then one seam call per persisted row, equal to the ledger line."""
    problems: list[str] = []
    covered = {_kind_of(name) for name in writers}
    problems += [f"no writer is driven for the persisted kind {kind}" for kind in sorted(universe - covered)]
    for index, (name, driver) in enumerate(sorted(writers.items())):
        world = World(tmp_path / f"w{index}")
        world.record_seam()
        ledger = driver(world, monkeypatch)
        # Seed rows are written straight to the ledger before the writer runs; they are not the writer's rows.
        new_rows = [row for row in world.ledger(ledger) if not str(row.get("event_id", "")).startswith("01SEED")]
        seen = [call_kwargs["row"] for _slot, call_kwargs in world.calls if isinstance(call_kwargs.get("row"), dict)]
        if len(seen) != len(new_rows):
            problems.append(f"{name}: {len(new_rows)} row(s) persisted but {len(seen)} seam call(s)")
            continue
        for row in seen:
            if row not in new_rows:
                problems.append(f"{name}: a seam call carried a row that is not a persisted ledger line: {sorted(row)}")
            if name in _JOURNAL_WRITERS and not set(row) <= _JOURNAL_KEYS:
                problems.append(f"{name}: journal row keys {sorted(set(row) - _JOURNAL_KEYS)} are outside the persisted keys")
    return problems


# --- the census --------------------------------------------------------------------------------


def test_every_persisted_row_kind_reaches_the_seam_once_and_equals_its_ledger_line(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert check_writers(all_writers(), derived_universe(), tmp_path, monkeypatch) == []


def test_floor_the_universe_has_every_in_scope_writer_of_data_model_section_3() -> None:
    universe = derived_universe()
    assert len(LIFECYCLE_EVENT_TYPES) == 12
    assert {"DecisionPointOpened", "DecisionPointResolved"} <= universe
    assert {"RetrospectiveCaptured", "RetrospectiveCaptureFailed", "RetrospectiveSkipped"} <= universe
    assert {"StatusEvent", "InnerStateChanged"} <= universe
    assert len({kind for kind in universe if kind.startswith("journal:")}) == 8
    assert len(universe) == 12 + 2 + 3 + 2 + 8
    annotation_writers = [name for name in _STATUS_WRITERS if name.startswith("InnerStateChanged")]
    assert len(annotation_writers) == 3, "standalone, bundled by emit_status_transition and bundled by emit_status_transition_batch"


def test_one_annotation_row_invokes_the_registered_client_exactly_once(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A client-level assertion, not only a seam-level one."""
    from specify_cli.status.producer_client import ProducerClient

    invoked: list[dict[str, Any]] = []
    monkeypatch.setattr(ProducerClient, "produce", lambda self, **kwargs: invoked.append(kwargs))
    world = World(tmp_path / "client")
    adapters.reset_handlers()
    adapters.ensure_producer_client()
    _seed(world)
    _standalone_annotation_without_recording(world)
    assert len(invoked) == 1
    assert invoked[0]["slot"] == "status" and invoked[0]["row"]["kind"] == "annotation"


def _standalone_annotation_without_recording(world: World) -> None:
    from specify_cli.status.emit import emit_inner_state_changed

    emit_inner_state_changed(world.feature_dir, "WP01", WPInnerStateDelta(note="client"), actor="census", mission_slug=_SLUG, repo_root=world.root)


def test_the_named_exemption_fails_when_an_emitter_appears() -> None:
    """The three registered Decision Moment kinds have no emitter in ``src/``; one that appears must be driven instead."""
    occurrences: dict[str, list[str]] = {kind: [] for kind in _DECISION_KINDS_WITHOUT_EMITTER}
    for file in _SRC.rglob("*.py"):
        text = file.read_text(encoding="utf-8")
        for kind in occurrences:
            if kind in text:
                occurrences[kind].append(file.relative_to(_SRC).as_posix())
    registry = "specify_cli/status/lifecycle_events.py"
    assert all(files == [registry] for files in occurrences.values()), f"an emitter appeared for an exempted kind: {occurrences}"


# --- self-mutations: each makes the census fail ------------------------------------------------


def test_mutation_a_writer_that_persists_with_no_seam_call_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def silent_writer(world: World, _monkeypatch: pytest.MonkeyPatch) -> Path:
        with world.status_log.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"event_id": "01JSILENT00000000000000001", "event_type": "Silent"}) + "\n")
        return world.status_log

    writers = {**all_writers(), "silent": silent_writer}
    problems = check_writers(writers, derived_universe() | {"silent"}, tmp_path, monkeypatch)
    assert any("silent: 1 row(s) persisted but 0 seam call(s)" in problem for problem in problems), problems


def test_mutation_a_persisted_kind_in_no_driver_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    problems = check_writers(all_writers(), derived_universe() | {"BrandNewKind"}, tmp_path, monkeypatch)
    assert any("no writer is driven for the persisted kind BrandNewKind" in problem for problem in problems), problems


def test_mutation_a_truncated_driver_set_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    writers = {name: driver for name, driver in all_writers().items() if name != MISSION_CREATED}
    problems = check_writers(writers, derived_universe(), tmp_path, monkeypatch)
    assert any(f"persisted kind {MISSION_CREATED}" in problem for problem in problems), problems


def test_mutation_a_seam_call_carrying_a_row_that_is_not_the_ledger_line_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def lying_writer(world: World, _monkeypatch: pytest.MonkeyPatch) -> Path:
        with world.status_log.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"event_id": "01JLYING000000000000000001", "event_type": "Lying", "x": 1}) + "\n")
        adapters.fire_lifecycle_fanout(row={"event_id": "01JLYING000000000000000001", "event_type": "Lying", "x": 2}, repo_root=world.root)
        return world.status_log

    problems = check_writers({"lying": lying_writer}, set(), tmp_path, monkeypatch)
    assert any("not a persisted ledger line" in problem for problem in problems), problems
