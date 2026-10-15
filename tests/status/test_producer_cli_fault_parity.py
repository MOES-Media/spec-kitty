"""AC-005 / T036: a producer fault never changes a lifecycle command's stdout or exit code.

One real command (``agent status emit``) runs once with a healthy producer and once per fault
class. After the two per-run variables (the event id and the repository path) are normalised,
stdout and the exit code equal the healthy run's; stderr carries exactly one warning line for a
fault and none for the healthy run; the ledger row is present in every run.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path
from unittest.mock import patch

import pytest
from typer.testing import CliRunner

from specify_cli.cli.commands.agent.status import app
from specify_cli.status import adapters, producer_client
from specify_cli.status.producer_client import ProducerClient
from tests.status.conftest import ProducerStub
from tests.status.test_agent_status_emit_aggregate_wiring import _emit_args, _make_repo_with_mission

pytestmark = [pytest.mark.fast, pytest.mark.real_event_producer]

_SLUG = "017-test-feature"
_BUSY_REPLY = json.dumps({"v": 1, "op": "produce", "outcome": "refused", "reason_class": "busy"})
_OVERFLOW_REPLY = json.dumps({"v": 1, "op": "produce", "outcome": "refused", "reason_class": "clock-overflow"})

#: fault name -> how the stub misbehaves (None: the binary is missing).
_FAULTS: dict[str, dict[str, object] | None] = {
    "binary-missing": None,
    "crash": {"reply": "", "exit_code": 1},
    "timeout": {"reply": "", "exit_code": 75},
    "malformed-reply": {"reply": "not json", "exit_code": 0},
    "rejected": {"golden": "produce-refused-no-time"},
    "busy": {"reply": _BUSY_REPLY, "exit_code": 0},
    "clock-overflow": {"reply": _OVERFLOW_REPLY, "exit_code": 0},
    # The seam's own bounds, not the producer's replies: a handler still in flight is skipped
    # (busy), and one outliving the fan-out bound is abandoned (timeout).
    "seam-busy": {"seam": "busy"},
    "seam-timeout": {"seam": "timeout"},
}
#: fault name -> the class token the one warning line must carry (default: the name itself).
_EXPECTED_CLASS = {"rejected": "rejected", "seam-busy": "busy", "seam-timeout": "timeout"}


@pytest.fixture(autouse=True)
def _fresh_client(monkeypatch: pytest.MonkeyPatch) -> None:
    ProducerClient.install()
    # An abandoned (timed-out) handler keeps its in-flight key until it ends; a private set per test
    # keeps that from reading as "busy" in the next one.
    monkeypatch.setattr(adapters, "_inflight_fanout_handlers", set())


def _run(root: Path, extra: list[str]) -> tuple[int, str, str, str]:
    """Run the emit command on a fresh repository; return exit code, normalised stdout, stderr, event id."""
    repo = _make_repo_with_mission(root, _SLUG)
    args = [a for a in _emit_args(_SLUG) if a != "--json"] if "--human" in extra else _emit_args(_SLUG)
    with (
        patch("specify_cli.cli.commands.agent.status.locate_project_root", return_value=repo),
        patch("specify_cli.cli.commands.agent.status._find_mission_slug", return_value=_SLUG),
    ):
        result = CliRunner().invoke(app, args)
    ledger = (repo / "kitty-specs" / _SLUG / "status.events.jsonl").read_text(encoding="utf-8").splitlines()
    row = json.loads(ledger[-1])
    assert row["to_lane"] == "claimed", "the ledger row must be present whatever the producer does"
    event_id = row["event_id"]
    stdout = result.stdout.replace(str(repo), "<repo>").replace(event_id, "<event-id>").replace(event_id[:12], "<event-id>")
    return result.exit_code, stdout, result.stderr, event_id


def _arm(stub: ProducerStub, monkeypatch: pytest.MonkeyPatch, fault: str | None) -> None:
    if fault is None:
        stub.replay("produce-valid")
        return
    behaviour = _FAULTS[fault]
    if behaviour is None:
        monkeypatch.setattr("specify_cli.status.producer_client.locate_binary", lambda: None)
    elif "seam" in behaviour:
        if behaviour["seam"] == "busy":
            stub.replay("produce-valid")
            key = adapters._handler_key(producer_client.produce_status_handler)
            adapters._inflight_fanout_handlers.add(key)
        else:
            stub.script(reply="", exit_code=0, hang=5.0)
            monkeypatch.setenv("SPEC_KITTY_FANOUT_TIMEOUT", "0.3")
    elif "golden" in behaviour:
        stub.replay(str(behaviour["golden"]))
    else:
        stub.script(reply=str(behaviour["reply"]), exit_code=int(str(behaviour["exit_code"])))


@pytest.mark.parametrize("mode", ["json", "human"])
@pytest.mark.parametrize("fault", sorted(_FAULTS))
def test_a_producer_fault_leaves_stdout_and_the_exit_code_unchanged(
    fault: str, mode: str, producer_stub: ProducerStub, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    extra = ["--human"] if mode == "human" else []
    _arm(producer_stub, monkeypatch, None)
    healthy_code, healthy_out, healthy_err, _ = _run(tmp_path / "healthy", extra)
    assert healthy_code == 0 and healthy_err == ""
    ProducerClient.install()

    _arm(producer_stub, monkeypatch, fault)
    code, out, err, _ = _run(tmp_path / "faulted", extra)
    if fault == "seam-timeout":
        _LAST_TIMEOUT_RUN.update(root=str(tmp_path / "faulted"), rows=_fault_row_count(tmp_path / "faulted"))

    assert code == healthy_code
    assert out == healthy_out, "a producer fault must not change a single byte of stdout"
    warning_lines = [line for line in err.splitlines() if line.strip()]
    assert len(warning_lines) == 1, warning_lines
    assert "produced" in warning_lines[0], warning_lines[0]
    assert f"producer fault {_EXPECTED_CLASS.get(fault, fault)}" in warning_lines[0], warning_lines[0]
    if mode == "json":
        json.loads(out)  # still one valid JSON document


#: The last seam-timeout run: its repository root and fault-row count when the test ended.
_LAST_TIMEOUT_RUN: dict[str, object] = {}


def _fault_row_count(root: Path) -> int:
    return sum(len(path.read_text(encoding="utf-8").splitlines()) for path in root.rglob("faults/*.jsonl"))


def _orphaned_handlers() -> list[threading.Thread]:
    return [thread for thread in threading.enumerate() if thread.is_alive() and thread.name.endswith("-handler")]


def test_zz_the_seam_timeout_case_leaves_nothing_behind(capfd: pytest.CaptureFixture[str]) -> None:
    """Runs after the matrix: an abandoned worker must not write into whichever test runs next.

    The seam-timeout case abandons its worker at the seam bound. If that worker outlives the case
    it later writes a second warning line and a second fault row into a later test (cycle-3 P2).
    """
    if not _LAST_TIMEOUT_RUN:
        pytest.skip("the seam-timeout case did not run in this session")
    assert _orphaned_handlers() == [], "a worker abandoned by the seam-timeout case is still running"
    assert capfd.readouterr().err == ""
    assert _fault_row_count(Path(str(_LAST_TIMEOUT_RUN["root"]))) == _LAST_TIMEOUT_RUN["rows"]
