"""The producer client is wired onto the fan-out seam by default (FR-001 to FR-004, AC-001, AC-003)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from collections.abc import Iterator, Mapping
from pathlib import Path
from types import MappingProxyType
from typing import Any

import pytest

from specify_cli.core.env import MOMENT_HANDLER_DISABLE_ENV_VARS
from specify_cli.events import sanitize_event_for_log
from specify_cli.status import adapters, producer_client, producer_faults, zeitgeist_bridge
from specify_cli.status.producer_client import ProducerClient
from specify_cli.status.emit import emit_status_transition
from specify_cli.status.models import TransitionRequest
from tests.status.conftest import ProducerStub
from tests.status.conftest import seed_wp_to_planned as _seed_planned

pytestmark = [pytest.mark.fast, pytest.mark.real_event_producer]


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    (tmp_path / ".kittify").mkdir(exist_ok=True)
    return tmp_path


@pytest.fixture
def feature_dir(repo: Path) -> Path:
    fd = repo / "kitty-specs" / "test-feature"
    fd.mkdir(parents=True)
    return fd


def test_one_lane_transition_gives_exactly_one_produce_request_with_the_start_path_and_the_row(
    drain_off: None, producer_stub: ProducerStub, repo: Path, feature_dir: Path
) -> None:
    """AC-001 (lane ATDD): default wiring, drain off, a recording stub binary, one transition, one request."""
    producer_stub.replay("produce-valid")
    _seed_planned(feature_dir, "WP01")

    event = emit_status_transition(
        TransitionRequest(feature_dir=feature_dir, mission_slug="test-feature", wp_id="WP01", to_lane="claimed", actor="test-actor", repo_root=repo)
    )

    assert len(producer_stub.requests) == 1, "the seam must hand the persisted row to the producer exactly once"
    (request,) = producer_stub.requests
    assert request["op"] == "produce" and request["slot"] == "status"
    assert request["start_path"] == str(repo)
    persisted = json.loads((feature_dir / "status.events.jsonl").read_text(encoding="utf-8").splitlines()[-1])
    assert request["row"] == persisted
    assert request["row"]["event_id"] == event.event_id


def test_the_lifecycle_fire_forwards_its_repo_root(drain_off: None, producer_stub: ProducerStub, repo: Path) -> None:
    """FR-004: ``fire_lifecycle_fanout`` takes ``repo_root`` as a keyword; the handler must receive it as the start path."""
    producer_stub.replay("produce-valid")
    adapters.fire_lifecycle_fanout(row={"event_id": "01J0000000000000000000L001"}, envelope={}, log_path=repo / "x" / "log.jsonl", repo_root=repo)
    assert len(producer_stub.requests) == 1, "the lifecycle fire must reach the producer"
    (request,) = producer_stub.requests
    assert request["slot"] == "lifecycle" and request["start_path"] == str(repo)


def test_the_retrospective_lifecycle_fire_forwards_its_repo_root(drain_off: None, producer_stub: ProducerStub, repo: Path) -> None:
    """#5181: the retrospective row reaches the producer with the acting repo as its start path, never the process CWD."""
    from specify_cli.retrospective.lifecycle_events import Actor, emit_skipped

    producer_stub.replay("produce-valid")
    (repo / "kitty-specs" / "retro-feature").mkdir(parents=True)
    emit_skipped(
        "01J0000000000000000000M001",
        "retro-feature",
        repo,
        skip_reason="operator bypass",
        skip_reason_source="cli_flag",
        policy_source={},
        actor=Actor(kind="human", id="tester", display="Tester"),
        event_log_dir=repo / "kitty-specs" / "retro-feature",
    )
    (request,) = producer_stub.requests
    assert request["slot"] == "lifecycle" and request["start_path"] == str(repo)


def _pii_row() -> dict[str, Any]:
    return {
        "event_id": "01J0000000000000000000P001",
        "event_type": "MissionCreated",
        "machine_name": "pii-machine-9f3a",
        "hostname": "pii-host-9f3a",
        "workspace_path": "/pii/workspace-9f3a",
        "payload": {"developer_name": "pii-developer-9f3a", "kept": "visible"},
        "session_started_at": "2026-01-01T00:00:00+00:00",
        "session_ended_at": "2026-01-01T00:01:00+00:00",
    }


def test_the_seam_hands_the_producer_the_sanitised_row_not_the_raw_one(drain_off: None, producer_stub: ProducerStub, repo: Path) -> None:
    """FR-004: the producer's row is the ledger line, after sanitisation; PII members never reach it."""
    producer_stub.replay("produce-valid")
    raw = _pii_row()
    adapters.fire_lifecycle_fanout(row=raw, envelope={}, log_path=repo / "x" / "log.jsonl", repo_root=repo)
    (request,) = producer_stub.requests
    assert request["row"] == sanitize_event_for_log(raw)
    sent = json.dumps(request["row"])
    for leaked in ("pii-machine-9f3a", "pii-host-9f3a", "/pii/workspace-9f3a", "pii-developer-9f3a", "session_started_at", "session_ended_at"):
        assert leaked not in sent, leaked
    assert request["row"]["session_duration_s"] == 60 and request["row"]["payload"]["kept"] == "visible"


def test_the_seam_sanitises_any_mapping_row_not_only_a_dict(drain_off: None, producer_stub: ProducerStub, repo: Path) -> None:
    """The client accepts any Mapping, so a non-dict row must not skip sanitisation."""
    producer_stub.replay("produce-valid")
    raw = _pii_row()
    adapters.fire_lifecycle_fanout(row=MappingProxyType(raw), envelope={}, log_path=repo / "x" / "log.jsonl", repo_root=repo)
    (request,) = producer_stub.requests
    assert request["row"] == sanitize_event_for_log(raw)
    assert "pii-machine-9f3a" not in json.dumps(request["row"])


class _RaisingRow(Mapping[str, Any]):
    """A Mapping whose iteration raises: snapshotting it must record a fault, never escape the seam."""

    def __getitem__(self, key: str) -> Any:
        raise KeyError(key)

    def __iter__(self) -> Iterator[str]:
        raise RuntimeError("iteration blew up")

    def __len__(self) -> int:
        return 1


def test_a_mapping_row_that_raises_on_snapshot_records_a_fault_and_never_escapes(
    drain_off: None, producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    producer_stub.replay("produce-valid")
    adapters.fire_lifecycle_fanout(row=_RaisingRow(), envelope={}, log_path=repo / "x" / "log.jsonl", repo_root=repo)
    assert _fault_classes(repo) == ["crash"]
    assert "producer fault crash" in capsys.readouterr().err


def test_a_fault_on_a_pii_row_carries_no_pii_in_the_fault_log_or_stderr(
    drain_off: None, producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    producer_stub.script(reply="", exit_code=1)
    adapters.fire_lifecycle_fanout(row=_pii_row(), envelope={}, log_path=repo / "x" / "log.jsonl", repo_root=repo)
    assert _fault_classes(repo) == ["crash"]
    logged = "".join(path.read_text(encoding="utf-8") for path in (repo / ".kittify" / "events" / "faults").glob("*.jsonl"))
    assert "pii-" not in logged + capsys.readouterr().err


# ---------------------------------------------------------------------------
# Seam busy-skip and TimeoutError each yield a fault row (AC-005, AC-006)
# ---------------------------------------------------------------------------


def _fault_classes(repo: Path) -> list[str]:
    directory = repo / ".kittify" / "events" / "faults"
    rows: list[dict[str, Any]] = []
    for path in sorted(directory.glob("*.jsonl")) if directory.exists() else []:
        rows.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    return [row["class"] for row in rows if row.get("kind") == "fault"]


def test_a_seam_busy_skip_of_the_client_records_a_busy_fault(producer_stub: ProducerStub, repo: Path) -> None:
    key = adapters._handler_key(producer_client.produce_status_handler)
    adapters._inflight_fanout_handlers.add(key)
    try:
        adapters.fire_status_fanout(row={"event_id": "01J0000000000000000000B001"}, repo_root=repo, wp_id="WP01")
    finally:
        adapters._inflight_fanout_handlers.discard(key)
    assert _fault_classes(repo) == ["busy"]
    assert producer_stub.requests == []


def test_a_seam_timeout_of_the_client_records_a_timeout_fault(monkeypatch: pytest.MonkeyPatch, repo: Path) -> None:
    release = threading.Event()

    def _hang(self: ProducerClient, **_kwargs: object) -> None:
        release.wait(timeout=10)

    monkeypatch.setattr(ProducerClient, "produce", _hang)
    monkeypatch.setenv("SPEC_KITTY_FANOUT_TIMEOUT", "0.3")
    try:
        adapters.fire_status_fanout(row={"event_id": "01J0000000000000000000T001"}, repo_root=repo, wp_id="WP01")
    finally:
        release.set()
    assert _fault_classes(repo) == ["timeout"]


def test_a_slow_hosted_handler_records_no_producer_fault(monkeypatch: pytest.MonkeyPatch, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    release = threading.Event()

    def slow_hosted(**_kwargs: object) -> None:
        release.wait(timeout=10)

    adapters.reset_handlers()
    adapters.register_status_fanout_handler(slow_hosted)
    monkeypatch.setenv("SPEC_KITTY_FANOUT_TIMEOUT", "0.3")
    try:
        adapters.fire_status_fanout(row={"event_id": "x"}, repo_root=repo, wp_id="WP01")
    finally:
        release.set()
    assert _fault_classes(repo) == []
    assert "NOT produced" not in capsys.readouterr().err


# ---------------------------------------------------------------------------
# AC-003 sub-observables (T080)
# ---------------------------------------------------------------------------

# ac003_c (a run-journal row through RuntimeMomentProducer reaches the producer with its row) is
# pinned in tests/architectural/test_seam_census.py; dropping row= from the journal fire turns it red.


def _client_keys() -> list[str]:
    return [h.__qualname__ for h in adapters._status_handlers if h.__module__.endswith("producer_client")]


def test_ac003_b_the_producer_fires_without_the_hosted_modules(producer_stub: ProducerStub, repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """(b) With no hosted handler registered and every hosted entry point booby-trapped, the producer still fires."""
    producer_stub.replay("produce-valid")
    adapters.reset_handlers()
    adapters.ensure_producer_client()

    def _boom(*_a: object, **_k: object) -> None:
        raise AssertionError("the producer path touched a hosted module")

    from specify_cli.core import hosted_posture

    monkeypatch.setattr(hosted_posture, "drain_posture", _boom)
    monkeypatch.setattr(zeitgeist_bridge, "_broadcast_status_transition", _boom)
    monkeypatch.setattr(zeitgeist_bridge, "_broadcast_lifecycle_envelope", _boom)

    adapters.fire_status_fanout(row={"event_id": "01J0000000000000000000H001"}, repo_root=repo, wp_id="WP01", from_lane="planned", to_lane="claimed")
    adapters.fire_lifecycle_fanout(row={"event_id": "01J0000000000000000000H002"}, envelope={}, repo_root=repo)

    assert [r["slot"] for r in producer_stub.requests] == ["status", "lifecycle"]


def test_ac003_d_there_is_at_least_one_hosted_kill_switch_to_enumerate() -> None:
    assert len(MOMENT_HANDLER_DISABLE_ENV_VARS) >= 3


@pytest.mark.parametrize("switch", MOMENT_HANDLER_DISABLE_ENV_VARS)
def test_ac003_d_each_hosted_kill_switch_leaves_the_producer_firing(switch: str, producer_stub: ProducerStub, repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    producer_stub.replay("produce-valid")
    monkeypatch.setenv(switch, "1")
    adapters.fire_status_fanout(row={"event_id": "01J0000000000000000000K001"}, repo_root=repo, wp_id="WP01", from_lane="planned", to_lane="claimed")
    assert len(producer_stub.requests) == 1


@pytest.mark.parametrize("switch", MOMENT_HANDLER_DISABLE_ENV_VARS)
def test_ac003_d_a_kill_switch_at_import_registers_the_client_and_no_hosted_handler(switch: str) -> None:
    program = (
        "import json; from specify_cli.status import adapters; "
        "print(json.dumps([[h.__qualname__ for h in adapters._status_handlers], [h.__qualname__ for h in adapters._lifecycle_handlers]]))"
    )
    done = subprocess.run(  # noqa: S603 - fixed argv, this interpreter
        [sys.executable, "-c", program],
        capture_output=True,
        text=True,
        check=True,
        env={**os.environ, switch: "1"},
    )
    status, lifecycle = json.loads(done.stdout.strip().splitlines()[-1])
    assert status == ["produce_status_handler"] and lifecycle == ["produce_lifecycle_handler"]


def test_ac003_e_reset_handlers_clears_and_ensure_restores_the_client() -> None:
    adapters.reset_handlers()
    assert _client_keys() == []
    adapters.ensure_producer_client()
    assert _client_keys() == ["produce_status_handler"]
    assert [h.__qualname__ for h in adapters._lifecycle_handlers] == ["produce_lifecycle_handler"]
    assert adapters._resolved_binding_handlers == [] or all(not h.__module__.endswith("producer_client") for h in adapters._resolved_binding_handlers)


def test_the_client_is_registered_first_so_a_hosted_handler_cannot_delay_it() -> None:
    adapters.reset_handlers()
    adapters.ensure_producer_client()
    adapters.ensure_zeitgeist_moment_handlers()
    assert [h.__qualname__ for h in adapters._status_handlers][0] == "produce_status_handler"


def test_the_resolved_binding_slot_is_never_registered_by_the_client() -> None:
    adapters.ensure_producer_client()
    assert not any(h.__module__.endswith("producer_client") for h in adapters._resolved_binding_handlers)


def test_the_hosted_modules_do_not_import_the_client_and_the_client_names_no_envelope_code() -> None:
    assert producer_faults.FaultClass.BUSY.value == "busy"
    source = Path(producer_client.__file__).read_text(encoding="utf-8")
    for forbidden in ("_broadcast_status_transition", "_broadcast_lifecycle_envelope", "_envelope_bookkeeping"):
        assert forbidden not in source


# ---------------------------------------------------------------------------
# Seam contract (decision 16b): a handler registered on the seam is fired by the real emitters
# ---------------------------------------------------------------------------


class TestSeamContract:
    """Any transport owner can register on the slots, and the real emit paths fire it.

    The seam is the single in-CLI publish surface (ADR 2026-10-14-1); the producer client is
    one registrant, not a special case. A stub registrant stands for the next one.
    """

    @pytest.fixture(autouse=True)
    def _only_the_stub(self) -> None:
        adapters.reset_handlers()
        yield
        adapters.reset_handlers()

    def test_a_status_slot_registrant_is_fired_once_per_lane_transition_with_the_persisted_row(self, repo: Path, feature_dir: Path) -> None:
        calls: list[dict[str, Any]] = []
        adapters.register_status_fanout_handler(lambda **kwargs: calls.append(kwargs))
        _seed_planned(feature_dir, "WP01")

        event = emit_status_transition(
            TransitionRequest(feature_dir=feature_dir, mission_slug="test-feature", wp_id="WP01", to_lane="claimed", actor="a", repo_root=repo)
        )

        assert len(calls) == 1
        assert calls[0]["row"]["event_id"] == event.event_id and calls[0]["repo_root"] == repo

    def test_a_lifecycle_slot_registrant_is_fired_once_per_lifecycle_row(self, repo: Path, feature_dir: Path) -> None:
        from specify_cli.status.lifecycle_events import WP_CREATED, append_lifecycle_event, mission_event_log_path

        calls: list[dict[str, Any]] = []
        adapters.register_lifecycle_fanout_handler(lambda **kwargs: calls.append(kwargs))

        envelope = append_lifecycle_event(
            mission_event_log_path(feature_dir),
            WP_CREATED,
            {"mission_slug": "test-feature", "wp_id": "WP01"},
            aggregate_id="WP01",
            aggregate_type="WorkPackage",
            mission_slug="test-feature",
        )

        assert envelope is not None and len(calls) == 1
        assert calls[0]["row"]["event_id"] == envelope["event_id"]

    def test_a_raising_registrant_never_reaches_the_emitter(self, repo: Path, feature_dir: Path) -> None:
        def boom(**_kwargs: object) -> None:
            raise RuntimeError("transport down")

        adapters.register_status_fanout_handler(boom)
        _seed_planned(feature_dir, "WP01")

        event = emit_status_transition(
            TransitionRequest(feature_dir=feature_dir, mission_slug="test-feature", wp_id="WP01", to_lane="claimed", actor="a", repo_root=repo)
        )

        assert event.to_lane == "claimed"
