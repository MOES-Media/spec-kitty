"""Run-journal rows carry a ULID ``event_id`` and a row version (FR-035, AC-022; OD-JOURNAL).

The real ``_append_event`` is called, never a stand-in. A journal written before the
stamp (no ``event_id``, no ``row_version``) must still be read.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
import ulid

from runtime.next._internal_runtime.engine import _append_event
from specify_cli.events.runtime_moments import latest_matching_record

pytestmark = [pytest.mark.fast]

_CROCKFORD_ULID = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")
_STAMPED_KEYS = {"event_id", "row_version", "event_type", "timestamp", "payload"}


def _rows(journal: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines()]


def test_a_new_row_has_exactly_the_five_keys(tmp_path: Path) -> None:
    _append_event(tmp_path, "NextStepIssued", {"run_id": "r", "step_id": "s"})

    (row,) = _rows(tmp_path / "run.events.jsonl")
    assert set(row) == _STAMPED_KEYS


def test_the_event_id_is_a_crockford_ulid_that_the_ulid_package_parses(tmp_path: Path) -> None:
    _append_event(tmp_path, "NextStepIssued", {"run_id": "r"})

    (row,) = _rows(tmp_path / "run.events.jsonl")
    event_id = row["event_id"]
    assert isinstance(event_id, str)
    assert _CROCKFORD_ULID.fullmatch(event_id)
    assert str(ulid.ULID.from_str(event_id)) == event_id


def test_the_row_version_is_one(tmp_path: Path) -> None:
    _append_event(tmp_path, "NextStepIssued", {"run_id": "r"})

    (row,) = _rows(tmp_path / "run.events.jsonl")
    assert row["row_version"] == 1
    assert type(row["row_version"]) is int


def test_every_row_gets_its_own_id(tmp_path: Path) -> None:
    for _ in range(5):
        _append_event(tmp_path, "NextStepIssued", {"run_id": "r"})

    ids = [row["event_id"] for row in _rows(tmp_path / "run.events.jsonl")]
    assert len(ids) == 5
    assert len(set(ids)) == 5


def test_a_stamped_row_is_still_found_by_its_type_and_payload(tmp_path: Path) -> None:
    payload = {"run_id": "r", "step_id": "s"}
    _append_event(tmp_path, "NextStepIssued", payload)

    record = latest_matching_record(tmp_path / "run.events.jsonl", "NextStepIssued", payload)

    assert record is not None
    assert record.line == 1
    assert record.row["event_id"]


def test_a_legacy_journal_without_the_stamp_is_still_read_and_not_rewritten(tmp_path: Path) -> None:
    journal = tmp_path / "run.events.jsonl"
    legacy = {"event_type": "NextStepIssued", "timestamp": "2026-04-11T12:00:00+00:00", "payload": {"run_id": "r"}}
    legacy_bytes = (json.dumps(legacy, sort_keys=True) + "\n").encode("utf-8")
    journal.write_bytes(legacy_bytes)

    record = latest_matching_record(journal, "NextStepIssued", {"run_id": "r"})

    assert record is not None
    assert record.row == legacy
    assert journal.read_bytes() == legacy_bytes


def test_a_new_row_appended_after_legacy_rows_leaves_them_byte_identical(tmp_path: Path) -> None:
    journal = tmp_path / "run.events.jsonl"
    legacy = {"event_type": "NextStepIssued", "timestamp": "2026-04-11T12:00:00+00:00", "payload": {"run_id": "r"}}
    legacy_bytes = (json.dumps(legacy, sort_keys=True) + "\n").encode("utf-8")
    journal.write_bytes(legacy_bytes)

    _append_event(tmp_path, "NextStepIssued", {"run_id": "r2"})

    assert journal.read_bytes().startswith(legacy_bytes)
    assert set(_rows(journal)[1]) == _STAMPED_KEYS
