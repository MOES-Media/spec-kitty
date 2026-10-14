"""The thin event-producer client against stub binaries that replay WP04's golden fixtures."""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import pytest

from specify_cli.status import producer_client, producer_faults
from specify_cli.status.producer_client import (
    ProducerClient,
    effective_client_timeout,
    locate_binary,
    produce_lifecycle_handler,
    produce_status_handler,
)
from specify_cli.status.producer_faults import fault_summary
from tests.status.conftest import GOLDEN_DIR, ProducerStub, golden

pytestmark = pytest.mark.fast

_ROW: dict[str, Any] = {"event_id": "01J0000000000000000000F001", "wp_id": "WP01", "from_lane": "planned", "to_lane": "claimed"}


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / ".git").mkdir(parents=True)
    return root


@pytest.fixture(autouse=True)
def _fresh_client() -> None:
    ProducerClient.install()


def _fault_rows(repo: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    directory = repo / ".kittify" / "events" / "faults"
    for path in sorted(directory.glob("*.jsonl")) if directory.exists() else []:
        rows.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    return rows


def _produce(repo: Path, row: dict[str, Any] | None = None, **extra: Any) -> None:
    produce_status_handler(row=row or _ROW, repo_root=repo, wp_id="WP01", **extra)


def test_the_fixtures_this_suite_replays_exist() -> None:
    names = {path.stem for path in GOLDEN_DIR.glob("*.reply")}
    assert {"produce-valid", "produce-refused-no-time", "malformed", "version-2", "deadline"} <= names


# --- timeout rule (AC-006 boundaries) -----------------------------------------------------


@pytest.mark.parametrize(
    ("bound", "expected"),
    [(0.5, 0.25), (1.0, 0.5), (1.5, 0.75), (2.0, 1.0), (4.0, 3.0), (10.0, 3.0), (0, 3.0), (-1, 3.0)],
)
def test_the_client_timeout_stays_strictly_below_the_seam_bound(bound: float, expected: float) -> None:
    assert effective_client_timeout(bound) == pytest.approx(expected)
    if bound > 0:
        assert effective_client_timeout(bound) < bound


# --- the request ---------------------------------------------------------------------------


def test_a_produce_request_carries_the_row_slot_start_path_and_a_deadline(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("produce-valid")
    _produce(repo)

    (request,) = producer_stub.requests
    assert request["v"] == 1 and request["op"] == "produce" and request["slot"] == "status"
    assert request["row"] == _ROW
    assert request["start_path"] == str(repo)
    assert request["deadline_ms"] >= 1
    assert "row" not in request["seam_args"] and request["seam_args"]["wp_id"] == "WP01"


def test_the_lifecycle_handler_uses_the_lifecycle_slot_and_the_log_path_parent_as_start(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("produce-valid")
    log = repo / "kitty-specs" / "m" / "status.events.jsonl"
    produce_lifecycle_handler(row={"event_id": "x"}, envelope={"event_id": "x"}, log_path=log)
    (request,) = producer_stub.requests
    assert request["slot"] == "lifecycle" and request["start_path"] == str(log.parent)


def test_a_call_without_a_row_spawns_nothing(producer_stub: ProducerStub, repo: Path) -> None:
    produce_status_handler(wp_id="WP01", repo_root=repo)
    assert producer_stub.requests == []


# --- success and silent outcomes ----------------------------------------------------------


def test_a_produced_reply_is_silent(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    producer_stub.replay("produce-valid")
    _produce(repo)
    assert capsys.readouterr().err == ""
    assert _fault_rows(repo) == []


def test_a_duplicate_reply_is_silent(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    producer_stub.script(reply='{"v":1,"op":"produce","outcome":"skipped_duplicate","event_id":"x","type":"t"}\n')
    _produce(repo)
    assert capsys.readouterr().err == "" and _fault_rows(repo) == []


def test_a_project_scoped_produced_reply_is_not_a_fault(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    """OD-MISSIONID (b): a row with no mission_id is produced under the project source; no warning, no fault row."""
    row = {k: v for k, v in _ROW.items() if k != "mission_id"}
    producer_stub.replay("produce-valid")
    _produce(repo, row)
    assert capsys.readouterr().err == ""
    assert _fault_rows(repo) == []
    assert fault_summary(repo)["status"] == "none_recorded"


# --- fault classes (AC-005) ---------------------------------------------------------------


def test_a_missing_binary_is_binary_missing(monkeypatch: pytest.MonkeyPatch, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(producer_client, "locate_binary", lambda: None)
    _produce(repo)
    assert "event was NOT produced" in capsys.readouterr().err
    assert [row["class"] for row in _fault_rows(repo)] == ["binary-missing"]


def test_an_unspawnable_binary_is_binary_missing(monkeypatch: pytest.MonkeyPatch, repo: Path, tmp_path: Path) -> None:
    not_executable = tmp_path / "plain.txt"
    not_executable.write_text("x", encoding="utf-8")
    not_executable.chmod(0o644)
    monkeypatch.setattr(producer_client, "locate_binary", lambda: not_executable)
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["binary-missing"]


@pytest.mark.parametrize("golden_name", ["malformed", "version-2"])
def test_the_launcher_exit_codes_with_no_reply_are_a_crash(producer_stub: ProducerStub, repo: Path, golden_name: str) -> None:
    producer_stub.replay(golden_name)  # exit 64, no reply
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["crash"]


def test_exit_70_is_an_internal_failure_and_a_crash(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply="", exit_code=70)
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["crash"]


def test_the_producers_own_deadline_exit_is_a_timeout(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("deadline")  # exit 75
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["timeout"]


@pytest.mark.parametrize(
    "reply",
    [
        "this is not json\n",
        "[1]\n",
        '{"v":2,"op":"produce","outcome":"produced"}\n',
        '{"v":1,"op":"produce","outcome":"maybe"}\n',
        '{"v":1,"op":"retain","outcome":"produced"}\n',
        '{"v":1,"op":"produce","outcome":"refused","reason_class":"mystery","reason":"x"}\n',
        '{"v":1,"outcome":"refused","reason":"unsupported-operation"}\n',
    ],
)
def test_a_reply_outside_the_contract_is_malformed(producer_stub: ProducerStub, repo: Path, reply: str) -> None:
    producer_stub.script(reply=reply)
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["malformed-reply"]


def test_an_oversized_reply_is_malformed(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply='{"v":1,"op":"produce","outcome":"produced","detail":"' + "x" * (1024 * 1024) + '"}\n')
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == ["malformed-reply"]


def test_a_rejected_refusal_is_a_fault_with_the_replys_identifiers(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    producer_stub.replay("produce-refused-no-time")
    _produce(repo)
    rows = _fault_rows(repo)
    assert [row["class"] for row in rows] == ["rejected"]
    assert rows[0]["event_id"] == "01J0000000000000000000F001"
    assert "rejected" in capsys.readouterr().err


@pytest.mark.parametrize(("reason_class", "fault"), [("clock-overflow", "clock-overflow"), ("busy", "busy")])
def test_the_other_refusal_classes_map_one_to_one(producer_stub: ProducerStub, repo: Path, reason_class: str, fault: str) -> None:
    producer_stub.script(reply=json.dumps({"v": 1, "op": "produce", "outcome": "refused", "reason_class": reason_class, "reason": reason_class}) + "\n")
    _produce(repo)
    assert [row["class"] for row in _fault_rows(repo)] == [fault]


def test_an_unresolvable_root_warns_but_writes_no_fault_row(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    producer_stub.script(reply='{"v":1,"op":"produce","outcome":"refused","reason_class":"rejected","reason":"root-unresolvable"}\n')
    _produce(repo)
    assert "event was NOT produced" in capsys.readouterr().err
    assert _fault_rows(repo) == []
    assert fault_summary(repo)["status"] == "none_recorded"


def test_a_torn_line_notice_is_recorded_once_and_is_not_a_fault(producer_stub: ProducerStub, repo: Path, capsys: pytest.CaptureFixture[str]) -> None:
    producer_stub.script(
        reply='{"v":1,"op":"produce","outcome":"produced","notice":{"kind":"torn-line","path":".kittify/events/publish/missions/M.jsonl","line":42}}\n'
    )
    _produce(repo)
    assert len(capsys.readouterr().err.strip().splitlines()) == 1
    rows = _fault_rows(repo)
    assert [(row["kind"], row["line"]) for row in rows] == [("notice", 42)]
    summary = fault_summary(repo)
    assert (summary["faults"], summary["notices"]) == (0, 1)


def test_a_fault_after_a_success_is_still_counted(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply="", exit_code=70)
    _produce(repo)
    producer_stub.replay("produce-valid")
    ProducerClient.install()  # a new command: the breaker is per process
    _produce(repo)
    assert fault_summary(repo)["faults"] == 1


# --- bounded call, breaker, termination (AC-006) -----------------------------------------


def test_a_hanging_producer_is_terminated_by_handle_and_recorded_as_timeout(producer_stub: ProducerStub, repo: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    producer_stub.script(reply="", hang=30)
    ProducerClient.install(bound_resolver=lambda: 1.0)  # effective client timeout 0.5 s
    started = time.monotonic()
    _produce(repo)
    assert time.monotonic() - started < 10
    assert [row["class"] for row in _fault_rows(repo)] == ["timeout"]
    assert producer_stub.survivors() == []


def test_a_batch_against_a_hanging_producer_spawns_once_and_records_every_row(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply="", hang=30)
    ProducerClient.install(bound_resolver=lambda: 1.0)
    rows = 4
    for index in range(rows):
        _produce(repo, {**_ROW, "event_id": f"01J000000000000000000000{index:02d}"})
    assert len(producer_stub.requests) == 1
    assert [row["class"] for row in _fault_rows(repo)] == ["timeout"] * rows
    assert producer_stub.survivors() == []


def test_a_crash_also_opens_the_breaker(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply="", exit_code=70)
    for _ in range(3):
        _produce(repo)
    assert len(producer_stub.requests) == 1
    assert [row["class"] for row in _fault_rows(repo)] == ["crash"] * 3


def test_a_refusal_does_not_open_the_breaker(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("produce-refused-no-time")
    for _ in range(3):
        _produce(repo)
    assert len(producer_stub.requests) == 3


def test_a_disabled_seam_bound_still_ends_the_producer_first(producer_stub: ProducerStub, repo: Path) -> None:
    """With the seam bound disabled (<= 0) the client's own default timeout applies and the stub is terminated."""
    producer_stub.script(reply="", hang=30)
    ProducerClient.install(bound_resolver=lambda: 0.0)
    started = time.monotonic()
    _produce(repo)
    assert 2.5 < time.monotonic() - started < 12
    assert [row["class"] for row in _fault_rows(repo)] == ["timeout"]
    assert producer_stub.survivors() == []


def test_a_broken_bound_resolver_never_breaks_the_seam(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("produce-valid")

    def _boom() -> float:
        raise RuntimeError("resolver failed")

    ProducerClient.install(bound_resolver=_boom)
    _produce(repo)
    assert len(producer_stub.requests) == 1


# --- never raises -------------------------------------------------------------------------


def test_the_handlers_never_raise_even_when_the_client_itself_breaks(monkeypatch: pytest.MonkeyPatch, repo: Path) -> None:
    def _boom(*_a: object, **_k: object) -> None:
        raise RuntimeError("client bug")

    monkeypatch.setattr(ProducerClient, "_produce", _boom)
    _produce(repo)  # must not raise
    assert [row["class"] for row in _fault_rows(repo)] == ["crash"]


def test_the_handlers_never_raise_on_an_unserialisable_seam_argument(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.replay("produce-valid")
    _produce(repo, metadata=object())
    assert len(producer_stub.requests) == 1


# --- retain (doctor) ----------------------------------------------------------------------


def test_retain_returns_the_reply_and_forwards_the_raw_policy(producer_stub: ProducerStub, repo: Path) -> None:
    reply = {"v": 1, "op": "retain", "dry_run": True, "eligible": [], "kept": [], "deleted": []}
    producer_stub.script(reply=json.dumps(reply) + "\n")
    result = ProducerClient.current().retain(start_path=repo, dry_run=True, policy={"keep_runs": "abc", "max_age_days": None})
    assert result == reply
    (request,) = producer_stub.requests
    assert request["op"] == "retain" and request["dry_run"] is True
    assert request["policy"] == {"keep_runs": "abc", "max_age_days": None}


@pytest.mark.parametrize("stub", [{"reply": "", "exit_code": 70}, {"reply": "", "exit_code": 75}, {"reply": "nope\n"}])
def test_a_failed_retain_is_an_error_reply_and_no_fault_row(producer_stub: ProducerStub, repo: Path, stub: dict[str, Any]) -> None:
    producer_stub.script(**stub)
    result = ProducerClient.current().retain(start_path=repo, dry_run=True, policy={})
    assert result["outcome"] == "error"
    assert _fault_rows(repo) == []
    assert fault_summary(repo)["status"] == "none_recorded"


def test_retain_against_a_missing_binary_is_an_error_not_a_fault(monkeypatch: pytest.MonkeyPatch, repo: Path) -> None:
    monkeypatch.setattr(producer_client, "locate_binary", lambda: None)
    assert ProducerClient.current().retain(start_path=repo, dry_run=True, policy={})["outcome"] == "error"
    assert _fault_rows(repo) == []


def test_retain_unsupported_operation_is_an_error_naming_the_reason(producer_stub: ProducerStub, repo: Path) -> None:
    producer_stub.script(reply='{"v":1,"outcome":"refused","reason":"unsupported-operation"}\n')
    result = ProducerClient.current().retain(start_path=repo, dry_run=True, policy={})
    assert result == {"outcome": "error", "reason": "unsupported-operation"}


def test_retain_root_unresolvable_reply_is_passed_through(producer_stub: ProducerStub, repo: Path) -> None:
    reply = {"v": 1, "op": "retain", "outcome": "refused", "reason": "root-unresolvable"}
    producer_stub.script(reply=json.dumps(reply) + "\n")
    assert ProducerClient.current().retain(start_path=repo, dry_run=False, policy={}) == reply


def test_the_golden_reply_is_what_the_stub_replays() -> None:
    assert golden("produce-valid")[0].startswith('{"event_id"')


def test_locate_binary_finds_nothing_in_a_source_checkout_and_the_name_is_package_relative() -> None:
    located = locate_binary()
    assert located is None or located.parent.name == "_native"
    assert producer_faults.FaultClass.BINARY_MISSING.value == "binary-missing"
