"""Fault recording for the event-producer client (FR-022 to FR-024, AC-005, AC-007, AC-026)."""

from __future__ import annotations

import json
import os
import stat
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

import pytest

from specify_cli.core.paths import WorkspaceRootNotFound
from specify_cli.status import producer_faults
from specify_cli.status.producer_faults import (
    FaultClass,
    fault_summary,
    record_fault,
    record_notice,
)

pytestmark = pytest.mark.fast

_NOW = datetime(2026, 10, 15, 12, 0, tzinfo=UTC)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / ".git").mkdir(parents=True)
    (root / ".kittify").mkdir()
    return root


@pytest.fixture
def at(monkeypatch: pytest.MonkeyPatch) -> Callable[[str], None]:
    """Inject the one wall-clock read of the client."""

    def _set(day: str) -> None:
        stamp = datetime.fromisoformat(day + "T12:00:00+00:00")
        monkeypatch.setattr(producer_faults, "_now_utc", lambda: stamp)

    _set("2026-10-15")
    return _set


def _day_file(repo: Path, day: str) -> Path:
    return repo / ".kittify" / "events" / "faults" / f"{day}.jsonl"


def _write_rows(repo: Path, day: str, rows: list[dict[str, object]]) -> None:
    path = _day_file(repo, day)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def test_the_fault_vocabulary_is_closed_and_has_no_disabled_class() -> None:
    assert {member.value for member in FaultClass} == {
        "binary-missing",
        "crash",
        "timeout",
        "malformed-reply",
        "clock-overflow",
        "rejected",
        "busy",
    }
    assert "disabled" not in {member.value for member in FaultClass}


def test_a_fault_prints_one_stderr_line_and_appends_one_row(repo: Path, at: Callable[[str], None], capsys: pytest.CaptureFixture[str]) -> None:
    record_fault(FaultClass.REJECTED, row={"event_type": "WPStatusChanged", "event_id": "01J0000000000000000000F001"}, start_path=repo)

    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) == 1
    assert "event was NOT produced" in err[0]
    assert "rejected" in err[0] and "WPStatusChanged" in err[0] and "01J0000000000000000000F001" in err[0]
    rows = [json.loads(line) for line in _day_file(repo, "2026-10-15").read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 1
    assert rows[0]["kind"] == "fault" and rows[0]["class"] == "rejected"
    assert rows[0]["event_type"] == "WPStatusChanged" and rows[0]["event_id"] == "01J0000000000000000000F001"
    assert rows[0]["at"].startswith("2026-10-15T12:00:00")


@pytest.mark.parametrize("fault", [FaultClass.CRASH, FaultClass.TIMEOUT])
def test_crash_and_timeout_say_the_event_may_not_have_been_produced(
    fault: FaultClass, repo: Path, at: Callable[[str], None], capsys: pytest.CaptureFixture[str]
) -> None:
    record_fault(fault, row={}, start_path=repo)
    assert "may not have been produced" in capsys.readouterr().err


def test_identifiers_are_omitted_when_the_row_has_none(repo: Path, at: Callable[[str], None], capsys: pytest.CaptureFixture[str]) -> None:
    record_fault(FaultClass.BINARY_MISSING, row={"wp_id": "WP01"}, start_path=repo)
    assert "event_id" not in capsys.readouterr().err
    row = json.loads(_day_file(repo, "2026-10-15").read_text(encoding="utf-8"))
    assert "event_type" not in row and "event_id" not in row


def test_the_reply_identifiers_win_over_the_rows_own(repo: Path, at: Callable[[str], None]) -> None:
    record_fault(FaultClass.REJECTED, row={"event_id": "row-id"}, reply_event_id="reply-id", reply_event_type="reply.type", start_path=repo)
    row = json.loads(_day_file(repo, "2026-10-15").read_text(encoding="utf-8"))
    assert (row["event_id"], row["event_type"]) == ("reply-id", "reply.type")


def test_a_row_never_carries_an_absolute_path(repo: Path, at: Callable[[str], None]) -> None:
    record_fault(FaultClass.CRASH, row={"event_id": "x"}, start_path=repo)
    assert str(repo) not in _day_file(repo, "2026-10-15").read_text(encoding="utf-8")


def test_an_unwritable_fault_log_still_warns_and_never_raises(repo: Path, at: Callable[[str], None], capsys: pytest.CaptureFixture[str]) -> None:
    _day_file(repo, "2026-10-15").mkdir(parents=True)  # a directory where the day file belongs
    record_fault(FaultClass.TIMEOUT, row={"event_id": "x"}, start_path=repo)
    assert "may not have been produced" in capsys.readouterr().err


def test_with_no_root_the_warning_is_stderr_only(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    def _no_root(_start: object = None) -> Path:
        raise WorkspaceRootNotFound("none")

    monkeypatch.setattr(producer_faults, "resolve_canonical_root", _no_root)
    record_fault(FaultClass.BINARY_MISSING, row={}, start_path=tmp_path)
    assert "event was NOT produced" in capsys.readouterr().err
    assert not (tmp_path / ".kittify").exists()
    assert fault_summary(tmp_path)["status"] == "none_recorded"


def test_a_git_root_that_is_not_a_spec_kitty_project_gets_no_fault_log(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    bare = tmp_path / "ambient"
    (bare / ".git").mkdir(parents=True)

    record_fault(FaultClass.BINARY_MISSING, row={}, start_path=bare)

    assert "event was NOT produced" in capsys.readouterr().err
    assert not (bare / ".kittify").exists()
    assert fault_summary(bare)["status"] == "none_recorded"


def test_a_notice_is_one_stderr_line_and_one_notice_row_and_not_a_fault(repo: Path, at: Callable[[str], None], capsys: pytest.CaptureFixture[str]) -> None:
    record_notice(path=".kittify/events/publish/missions/M.jsonl", line=42, start_path=repo)

    assert len(capsys.readouterr().err.strip().splitlines()) == 1
    rows = [json.loads(line) for line in _day_file(repo, "2026-10-15").read_text(encoding="utf-8").splitlines()]
    assert rows == [{"kind": "notice", "notice": "torn-line", "path": ".kittify/events/publish/missions/M.jsonl", "line": 42, "at": rows[0]["at"], "root": "."}]
    summary = fault_summary(repo)
    assert (summary["faults"], summary["notices"]) == (0, 1)


def test_the_summary_says_none_recorded_for_a_missing_log(repo: Path, at: Callable[[str], None]) -> None:
    assert fault_summary(repo) == {"status": "none_recorded", "faults": 0, "notices": 0, "last_class": None}


def test_the_summary_counts_faults_not_notices_and_reports_the_last_class(repo: Path, at: Callable[[str], None]) -> None:
    record_fault(FaultClass.CRASH, row={}, start_path=repo)
    record_notice(path="p", line=1, start_path=repo)
    record_fault(FaultClass.BUSY, row={}, start_path=repo)
    assert fault_summary(repo) == {"status": "counts", "faults": 2, "notices": 1, "last_class": "busy"}


def test_a_later_success_does_not_reset_the_count(repo: Path, at: Callable[[str], None]) -> None:
    record_fault(FaultClass.CRASH, row={}, start_path=repo)
    before = fault_summary(repo)
    # A produced row writes nothing to the fault log: there is no reset operation at all.
    assert fault_summary(repo) == before == {"status": "counts", "faults": 1, "notices": 0, "last_class": "crash"}


def test_day_files_that_hold_only_notices_do_not_count_as_faults(repo: Path, at: Callable[[str], None]) -> None:
    _write_rows(repo, "2026-10-14", [{"kind": "notice", "notice": "torn-line"}])
    summary = fault_summary(repo)
    assert (summary["status"], summary["faults"], summary["notices"], summary["last_class"]) == ("counts", 0, 1, None)


@pytest.mark.parametrize(
    ("max_age_days", "expected_days"),
    [
        (30, {"2026-10-15", "2026-09-15"}),  # a file exactly max_age_days old is inside
        ("30", {"2026-10-15", "2026-09-15"}),
        (29, {"2026-10-15"}),  # one day older is outside
        (0, {"2026-10-15"}),
        (None, {"2026-10-15", "2026-09-15", "2026-01-01"}),  # absent: every file
        ("abc", {"2026-10-15", "2026-09-15", "2026-01-01"}),  # non-numeric: every file
        (True, {"2026-10-15", "2026-09-15", "2026-01-01"}),
        (-3, {"2026-10-15", "2026-09-15", "2026-01-01"}),
    ],
)
def test_the_window_is_the_day_files_within_the_raw_max_age(repo: Path, at: Callable[[str], None], max_age_days: object, expected_days: set[str]) -> None:
    for day in ("2026-10-15", "2026-09-15", "2026-01-01"):
        _write_rows(repo, day, [{"kind": "fault", "class": "crash"}])
    assert fault_summary(repo, max_age_days=max_age_days)["faults"] == len(expected_days)


def test_a_corrupt_line_is_unreadable_never_zero(repo: Path, at: Callable[[str], None]) -> None:
    path = _day_file(repo, "2026-10-15")
    path.parent.mkdir(parents=True)
    path.write_text('{"kind": "fault"\n', encoding="utf-8")
    assert fault_summary(repo)["status"] == "unreadable"


def test_a_directory_in_place_of_the_file_is_unreadable(repo: Path, at: Callable[[str], None]) -> None:
    _day_file(repo, "2026-10-15").mkdir(parents=True)
    assert fault_summary(repo)["status"] == "unreadable"


@pytest.mark.skipif(os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0), reason="needs POSIX permissions and a non-root user")
def test_a_permission_error_is_unreadable(repo: Path, at: Callable[[str], None]) -> None:
    _write_rows(repo, "2026-10-15", [{"kind": "fault", "class": "crash"}])
    path = _day_file(repo, "2026-10-15")
    path.chmod(0)
    try:
        assert fault_summary(repo)["status"] == "unreadable"
    finally:
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)


def test_a_non_object_row_is_unreadable(repo: Path, at: Callable[[str], None]) -> None:
    path = _day_file(repo, "2026-10-15")
    path.parent.mkdir(parents=True)
    path.write_text("[1]\n", encoding="utf-8")
    assert fault_summary(repo)["status"] == "unreadable"
