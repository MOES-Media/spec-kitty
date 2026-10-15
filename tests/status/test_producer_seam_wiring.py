"""The producer client is wired onto the fan-out seam by default (FR-001 to FR-004, AC-001, AC-003)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any

import pytest

from specify_cli.core.env import MOMENT_HANDLER_DISABLE_ENV_VARS
from specify_cli.status import adapters, producer_client, producer_faults, zeitgeist_bridge
from specify_cli.status.producer_client import ProducerClient
from specify_cli.status.emit import emit_status_transition
from specify_cli.status.models import TransitionRequest
from tests.status.conftest import ProducerStub
from tests.status.conftest import seed_wp_to_planned as _seed_planned

pytestmark = pytest.mark.fast


@pytest.fixture
def repo(tmp_path: Path) -> Path:
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
