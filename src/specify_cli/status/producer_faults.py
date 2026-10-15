"""Fault recording for the event-producer client (FR-022 to FR-024).

A producer that cannot be reached, crashes, times out, answers nonsense or is
busy never fails the lifecycle command. This module is where that degraded
state stays visible, never silent: one stderr line per fault in every output
mode (so no ``--json`` body changes) and an append-only, gitignored fault log
under ``.kittify/events/faults/<UTC date>.jsonl``. The client is the fault
log's only writer; the producer's own notices travel in its reply and are
recorded here as a second row kind (``torn-line``) that is not a fault.

Thin by design (FR-026): no event schema, id, order or type knowledge lives
here. ``event_type`` and ``event_id`` of a client-only fault are opaque copies
of the row's own members. The one wall-clock read is ``_now_utc``, which only
stamps the fault row's ``at`` and names the UTC day file, never an event.
"""

from __future__ import annotations

import json
import sys
from collections.abc import Mapping
from enum import StrEnum
from pathlib import Path
from typing import Any

from kernel.clock import date, datetime, now_utc, timedelta
from specify_cli.core.paths import WorkspaceRootNotFound, resolve_canonical_root

__all__ = ["FaultClass", "fault_summary", "record_fault", "record_notice", "write_stderr_warning"]


class FaultClass(StrEnum):
    """The closed vocabulary of producer fault classes (data-model section 6).

    There is no ``disabled`` class: the producer has no opt-out (OD-OPTOUT);
    ``binary-missing`` is the degraded state.
    """

    BINARY_MISSING = "binary-missing"
    CRASH = "crash"
    TIMEOUT = "timeout"
    MALFORMED_REPLY = "malformed-reply"
    CLOCK_OVERFLOW = "clock-overflow"
    REJECTED = "rejected"
    BUSY = "busy"


_MAY_NOT_HAVE_BEEN = (FaultClass.CRASH, FaultClass.TIMEOUT)


def _now_utc() -> datetime:
    """The only wall-clock read of the client: a fault row's ``at`` and the UTC day file name."""
    return now_utc()


def write_stderr_warning(message: str) -> None:
    """Write one warning line to stderr; a closed or broken stream is ignored."""
    try:
        sys.stderr.write(message + "\n")
        sys.stderr.flush()
    except (OSError, ValueError):
        pass


def _opaque(row: Mapping[str, Any] | None, key: str) -> str | None:
    """Copy one member of the row as the seam handed it, or ``None`` when it has none."""
    if row is None:
        return None
    value = row.get(key)
    return value if isinstance(value, str) and value else None


def _fault_dir(start_path: Path | str | None) -> tuple[Path, Path] | None:
    """``(repo root, fault-log directory)`` for a start path, or ``None`` when no spec-kitty root resolves.

    A git root without a ``.kittify`` directory is not a spec-kitty project, so the log never
    creates one there (an ambient ``.git`` such as a shared temp directory must not grow state).
    """
    try:
        root = resolve_canonical_root(Path(start_path) if start_path is not None else None)
    except (WorkspaceRootNotFound, OSError, ValueError):
        return None
    if not (root / ".kittify").is_dir():
        return None
    return root, root / ".kittify" / "events" / "faults"


def _append_row(start_path: Path | str | None, row: dict[str, Any], now: datetime) -> None:
    """Append one fault-log row to today's day file; any failure leaves stderr as the only carrier."""
    located = _fault_dir(start_path)
    if located is None:
        return
    _root, directory = located
    try:
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / f"{now.date().isoformat()}.jsonl").open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(row, sort_keys=True) + "\n")
    except OSError:
        return


def _describe(event_type: str | None, event_id: str | None) -> str:
    parts = [f"{name}={value}" for name, value in (("event_type", event_type), ("event_id", event_id)) if value]
    return f" ({', '.join(parts)})" if parts else ""


def record_fault(
    fault_class: FaultClass,
    *,
    row: Mapping[str, Any] | None = None,
    reply_event_type: str | None = None,
    reply_event_id: str | None = None,
    detail: str | None = None,
    start_path: Path | str | None = None,
    log: bool = True,
) -> None:
    """Record one producer fault: a stderr line always, a fault-log row when ``log`` and a root resolve.

    The identifiers are the reply's when one was read, else the row's own
    members copied verbatim. Never raises: a fault that cannot be recorded is
    still on stderr.
    """
    try:
        event_type = reply_event_type or _opaque(row, "event_type")
        event_id = reply_event_id or _opaque(row, "event_id")
        outcome = "event may not have been produced" if fault_class in _MAY_NOT_HAVE_BEEN else "event was NOT produced"
        write_stderr_warning(f"spec-kitty: {outcome}{_describe(event_type, event_id)}: producer fault {fault_class.value}")
        if not log:
            return
        entry: dict[str, Any] = {"kind": "fault", "class": fault_class.value, "at": _now_utc().isoformat(), "root": "."}
        if event_type:
            entry["event_type"] = event_type
        if event_id:
            entry["event_id"] = event_id
        if detail:
            entry["message"] = detail
        _append_row(start_path, entry, _now_utc())
    except Exception:  # noqa: BLE001 - the outermost catch: a fault recorder never fails the command
        return


def record_notice(*, path: str, line: int, start_path: Path | str | None = None) -> None:
    """Record a producer ``torn-line`` notice: one stderr line and one notice row, never a fault (FR-024)."""
    try:
        write_stderr_warning(f"spec-kitty: publish log has a torn line at {path}:{line}; it was left in place")
        entry = {"kind": "notice", "notice": "torn-line", "path": path, "line": line, "at": _now_utc().isoformat(), "root": "."}
        _append_row(start_path, entry, _now_utc())
    except Exception:  # noqa: BLE001 - see record_fault
        return


def _read_fault_rows(path: Path) -> list[dict[str, Any]]:
    """Every row of one fault-log day file (the second reply-decode site); raises on anything unreadable."""
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            parsed = json.loads(line)
            if not isinstance(parsed, dict):
                raise ValueError("a fault-log row is not an object")
            rows.append(parsed)
    return rows


def _window_days(max_age_days: Any) -> int | None:
    """The raw ``max_age_days`` as a whole number of days, or ``None`` (every file) when absent or non-numeric."""
    if isinstance(max_age_days, bool):
        return None
    if isinstance(max_age_days, int):
        return max_age_days if max_age_days >= 0 else None
    if isinstance(max_age_days, str):
        text = max_age_days.strip()
        return int(text) if text.isascii() and text.isdigit() else None
    return None


def fault_summary(start_path: Path | str | None = None, *, max_age_days: Any = None) -> dict[str, Any]:
    """Count the fault-log rows inside the retention window, for ``doctor``.

    The window is the day files whose name date lies within ``max_age_days``
    of today (a file exactly that old is inside); an absent or non-numeric
    value means every file present. Fault rows and notice rows are counted
    apart, and a later success never resets the count. The ``status`` is
    explicit: ``none_recorded`` (no log), ``counts`` or ``unreadable``;
    an unreadable log is never reported as zero.
    """
    located = _fault_dir(start_path)
    if located is None or not located[1].exists():
        return {"status": "none_recorded", "faults": 0, "notices": 0, "last_class": None}
    days = _window_days(max_age_days)
    today = _now_utc().date()
    faults = notices = 0
    last_class: str | None = None
    try:
        for path in sorted(located[1].iterdir()):
            if path.suffix != ".jsonl":
                continue
            try:
                stamp = date.fromisoformat(path.stem)
            except ValueError:
                continue
            if days is not None and stamp < today - timedelta(days=days):
                continue
            for entry in _read_fault_rows(path):
                if entry.get("kind") == "fault":
                    faults += 1
                    last_class = str(entry.get("class")) if entry.get("class") is not None else last_class
                elif entry.get("kind") == "notice":
                    notices += 1
    except (OSError, ValueError):
        return {"status": "unreadable", "faults": 0, "notices": 0, "last_class": None}
    return {"status": "counts", "faults": faults, "notices": notices, "last_class": last_class}
