"""`doctor events` and `doctor events-retention`: a thin pass-through to the producer client (FR-024, FR-030, AC-013)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import patch

import pytest
from typer.testing import CliRunner

from specify_cli.cli.commands import _event_producer_doctor as doctor_module
from specify_cli.cli.commands.doctor import app
from specify_cli.status import ProducerClient, record_fault
from specify_cli.status.producer_faults import FaultClass, fault_summary, record_notice
from tests.status.conftest import ProducerStub, producer_stub  # noqa: F401 -- the stub fixture is shared

pytestmark = [pytest.mark.fast, pytest.mark.real_event_producer]

runner = CliRunner()


@pytest.fixture
def project(tmp_path: Path) -> Path:
    root = tmp_path / "project"
    (root / ".git").mkdir(parents=True)
    (root / ".kittify").mkdir()
    return root


@pytest.fixture(autouse=True)
def _in_project(project: Path):  # noqa: ANN202
    with patch.object(doctor_module, "locate_project_root", return_value=project):
        ProducerClient.install()
        yield


def _config(project: Path, text: str) -> None:
    (project / ".kittify" / "config.yaml").write_text(text, encoding="utf-8")


def _fault_files(project: Path) -> list[Path]:
    directory = project / ".kittify" / "events" / "faults"
    return sorted(directory.glob("*.jsonl")) if directory.exists() else []


def _run(*args: str):  # noqa: ANN202
    return runner.invoke(app, list(args))


# --- doctor events ------------------------------------------------------------------------


def test_health_reports_a_missing_binary_as_degraded(project: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(doctor_module, "locate_binary", lambda: None)
    result = _run("events")
    assert result.exit_code == 0
    assert "NOT produced" in result.stdout
    assert "none recorded" in result.stdout


def test_health_counts_faults_and_reports_the_last_class_with_notices_apart(project: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(doctor_module, "locate_binary", lambda: Path("present"))
    record_fault(FaultClass.CRASH, row={}, start_path=project)
    record_fault(FaultClass.BUSY, row={}, start_path=project)
    record_notice(path="p", line=3, start_path=project)
    result = _run("events")
    assert "binary: present" in result.stdout
    assert "retention window: 2 (last: busy)" in result.stdout
    assert "notices (counted apart): 1" in result.stdout


def test_health_shows_an_unreadable_log_as_a_visible_warning_never_zero(project: Path) -> None:
    directory = project / ".kittify" / "events" / "faults"
    directory.mkdir(parents=True)
    (directory / "2026-10-15.jsonl").write_text("{broken\n", encoding="utf-8")
    result = _run("events")
    assert "unreadable" in result.stdout and "unknown, not zero" in result.stdout
    assert "0" not in result.stdout.split("unknown, not zero")[-1], "no zero count is shown after the warning"


def test_health_window_uses_the_raw_max_age_days_from_the_config(project: Path) -> None:
    directory = project / ".kittify" / "events" / "faults"
    directory.mkdir(parents=True)
    (directory / "2000-01-01.jsonl").write_text(json.dumps({"kind": "fault", "class": "crash"}) + "\n", encoding="utf-8")
    _config(project, "ledger:\n  retention:\n    max_age_days: 30\n")
    assert json.loads(_run("events", "--json").stdout)["faults"] == 0
    _config(project, "ledger:\n  retention:\n    max_age_days: abc\n")
    assert json.loads(_run("events", "--json").stdout)["faults"] == 1


def test_health_json_carries_the_binary_presence_and_the_summary(project: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(doctor_module, "locate_binary", lambda: None)
    payload = json.loads(_run("events", "--json").stdout)
    assert payload == {"binary_present": False, "status": "none_recorded", "faults": 0, "notices": 0, "last_class": None}


# --- doctor events-retention -------------------------------------------------------------


def _reply(**members: Any) -> str:
    return json.dumps({"v": 1, "op": "retain", "dry_run": True, "eligible": [], "kept": [], "deleted": [], **members}) + "\n"


def test_the_default_run_is_a_dry_run_and_apply_sends_dry_run_false(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply())
    assert _run("events-retention").exit_code == 0
    assert _run("events-retention", "--apply").exit_code == 0
    first, second = producer_stub.requests
    assert first["op"] == "retain" and first["dry_run"] is True
    assert second["dry_run"] is False


def test_the_raw_policy_values_reach_the_producer_unchanged(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply())
    _config(project, "ledger:\n  retention:\n    keep_runs: abc\n    max_age_days: 7.5\n")
    _run("events-retention")
    assert producer_stub.requests[0]["policy"] == {"keep_runs": "abc", "max_age_days": 7.5}


def test_absent_policy_keys_are_omitted_so_the_producer_applies_its_defaults(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply())
    _run("events-retention")
    assert producer_stub.requests[0]["policy"] == {}


def test_the_reply_lists_are_rendered(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(
        reply=_reply(
            eligible=[{"path": ".kittify/events/publish/missions/OLD.jsonl", "reason": "closed-mission-outside-limits"}],
            kept=[{"path": ".kittify/events/publish/missions/NEW.jsonl", "reason": "mission-open"}],
        )
    )
    result = _run("events-retention")
    assert "OLD.jsonl" in result.stdout and "nothing deleted" in result.stdout
    assert "kept .kittify/events/publish/missions/NEW.jsonl: mission-open" in result.stdout


def test_apply_renders_the_deleted_files(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply(dry_run=False, deleted=[".kittify/events/publish/missions/OLD.jsonl"]))
    result = _run("events-retention", "--apply")
    assert "1 file(s) deleted" in result.stdout and "OLD.jsonl" in result.stdout


def test_a_policy_error_is_an_error_and_not_a_fault(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply(policy_error={"key": "keep_runs", "code": "not-a-positive-integer", "message": "keep_runs must be a positive integer"}))
    result = _run("events-retention")
    assert result.exit_code == 1
    assert "keep_runs" in result.stdout
    assert _fault_files(project) == []


@pytest.mark.parametrize("stub", [{"reply": "", "exit_code": 70}, {"reply": "", "exit_code": 75}, {"reply": "not json\n"}])
def test_a_crash_or_timeout_of_retain_renders_an_error_and_writes_no_fault_row(producer_stub: ProducerStub, project: Path, stub: dict[str, Any]) -> None:  # noqa: F811
    producer_stub.script(**stub)
    result = _run("events-retention")
    assert result.exit_code == 1 and "retention did not run" in result.stdout
    assert _fault_files(project) == []
    assert fault_summary(project)["status"] == "none_recorded"


def test_an_unresolvable_root_reply_is_an_error(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=json.dumps({"v": 1, "op": "retain", "outcome": "refused", "reason": "root-unresolvable"}) + "\n")
    result = _run("events-retention")
    assert result.exit_code == 1 and "root-unresolvable" in result.stdout


def test_an_old_producer_without_retain_is_an_error_naming_unsupported_operation(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply='{"v":1,"outcome":"refused","reason":"unsupported-operation"}\n')
    result = _run("events-retention")
    assert result.exit_code == 1 and "unsupported-operation" in result.stdout


def test_json_mode_passes_the_reply_through_and_exits_nonzero_on_failure(producer_stub: ProducerStub, project: Path) -> None:  # noqa: F811
    producer_stub.script(reply=_reply(eligible=[{"path": "x", "reason": "r"}]))
    ok = _run("events-retention", "--json")
    assert ok.exit_code == 0 and json.loads(ok.stdout)["eligible"] == [{"path": "x", "reason": "r"}]
    producer_stub.script(reply="", exit_code=70)
    bad = _run("events-retention", "--json")
    assert bad.exit_code == 1 and json.loads(bad.stdout)["outcome"] == "error"
