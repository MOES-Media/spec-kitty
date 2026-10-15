"""AC-005 / T036: a producer fault never changes a lifecycle command's stdout or exit code.

One real command (``agent status emit``) runs once with a healthy producer and once per fault
class. After the two per-run variables (the event id and the repository path) are normalised,
stdout and the exit code equal the healthy run's; stderr carries exactly one warning line for a
fault and none for the healthy run; the ledger row is present in every run.
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest
from typer.testing import CliRunner

from specify_cli.cli.commands.agent.status import app
from specify_cli.status.producer_client import ProducerClient
from tests.status.conftest import ProducerStub
from tests.status.test_agent_status_emit_aggregate_wiring import _emit_args, _make_repo_with_mission

pytestmark = [pytest.mark.fast, pytest.mark.real_event_producer]

_SLUG = "017-test-feature"
_BUSY_REPLY = json.dumps({"v": 1, "op": "produce", "outcome": "refused", "reason_class": "busy"})

#: fault name -> how the stub misbehaves (None: the binary is missing).
_FAULTS: dict[str, dict[str, object] | None] = {
    "binary-missing": None,
    "crash": {"reply": "", "exit_code": 1},
    "timeout": {"reply": "", "exit_code": 75},
    "malformed-reply": {"reply": "not json", "exit_code": 0},
    "rejected": {"golden": "produce-refused-no-time"},
    "busy": {"reply": _BUSY_REPLY, "exit_code": 0},
}


@pytest.fixture(autouse=True)
def _fresh_client() -> None:
    ProducerClient.install()


def _run(root: Path, extra: list[str]) -> tuple[int, str, str, str]:
    """Run the emit command on a fresh repository; return exit code, normalised stdout, stderr, event id."""
    repo = _make_repo_with_mission(root, _SLUG)
    args = [a for a in _emit_args(_SLUG) if a != "--json"] if "--human" in extra else _emit_args(_SLUG)
    with patch("specify_cli.cli.commands.agent.status.locate_project_root", return_value=repo), patch("specify_cli.cli.commands.agent.status._find_mission_slug", return_value=_SLUG):
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

    assert code == healthy_code
    assert out == healthy_out, "a producer fault must not change a single byte of stdout"
    warning_lines = [line for line in err.splitlines() if line.strip()]
    assert len(warning_lines) == 1, warning_lines
    assert "produced" in warning_lines[0], warning_lines[0]
    if mode == "json":
        json.loads(out)  # still one valid JSON document
