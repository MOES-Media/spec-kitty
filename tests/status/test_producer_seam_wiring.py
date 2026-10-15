"""The producer client is wired onto the fan-out seam by default (FR-001 to FR-004, AC-001, AC-003)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from specify_cli.status import adapters
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
