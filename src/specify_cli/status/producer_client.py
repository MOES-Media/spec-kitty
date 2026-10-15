"""Thin client for the native event producer (FR-001, FR-025, FR-026).

The producer is a separate native binary (Java). This module only locates it,
hands it the seam's persisted row over a one-object JSON request on stdin,
reads one reply and maps any failure to a fault class. It builds no envelope
and holds no schema, id, clock, order, dedupe or type knowledge: every one of
those lives in the producer. The seam handlers never raise, so a missing
binary, a crash, a timeout, a malformed reply or a busy producer degrades to a
recorded warning (see ``producer_faults``) and never changes a lifecycle
command's exit code or ``--json`` body.

This is the single in-CLI entry point to the producer (ADR 2026-10-14-1: each
event type has one writer).
"""

from __future__ import annotations

import json
import subprocess
import sys
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from specify_cli.status.producer_faults import (
    FaultClass,
    record_fault,
    record_notice,
)

__all__ = [
    "ProducerClient",
    "effective_client_timeout",
    "locate_binary",
    "produce_lifecycle_handler",
    "produce_status_handler",
]

_BINARY_NAME = "spec-kitty-event-producer"
_PROTOCOL_VERSION = 1
_MAX_REPLY_BYTES = 1024 * 1024
_DEFAULT_TIMEOUT_S = 3.0
_EXIT_DEADLINE = 75
_ROOT_UNRESOLVABLE = "root-unresolvable"
_PRODUCE_OUTCOMES = frozenset({"produced", "skipped_duplicate", "refused"})
_REFUSAL_CLASSES = {
    "rejected": FaultClass.REJECTED,
    "clock-overflow": FaultClass.CLOCK_OVERFLOW,
    "busy": FaultClass.BUSY,
}


def locate_binary() -> Path | None:
    """The platform binary at its package-relative place, or ``None`` when this install has none.

    There is no environment override and no producer setting (OD-OPTOUT);
    tests patch this function.
    """
    name = f"{_BINARY_NAME}.exe" if sys.platform == "win32" else _BINARY_NAME
    candidate = Path(__file__).resolve().parent.parent / "_native" / name
    return candidate if candidate.is_file() else None


def effective_client_timeout(bound: float) -> float:
    """The client's own timeout, strictly below the effective seam bound (FR-025).

    A non-positive bound means the seam runs handlers inline, so the client
    uses its 3.0 s default; otherwise ``min(3.0, max(bound / 2, bound - 1.0))``,
    which is positive and strictly below any positive bound.
    """
    if bound <= 0:
        return _DEFAULT_TIMEOUT_S
    return min(_DEFAULT_TIMEOUT_S, max(bound / 2, bound - 1.0))


class ProducerClient:
    """One process-wide client: spawns the producer once per row, holds the per-process breaker."""

    _current: ProducerClient | None = None

    def __init__(self, *, bound_resolver: Callable[[], float] | None = None) -> None:
        self._bound_resolver = bound_resolver
        self._tripped: FaultClass | None = None

    @classmethod
    def install(cls, *, bound_resolver: Callable[[], float] | None = None) -> ProducerClient:
        """Make a fresh client (a closed breaker) the process-wide one and return it."""
        cls._current = cls(bound_resolver=bound_resolver)
        return cls._current

    @classmethod
    def current(cls) -> ProducerClient:
        """The process-wide client, created without a bound resolver when none was installed."""
        if cls._current is None:
            cls._current = cls()
        return cls._current

    @staticmethod
    def start_path_for(kwargs: Mapping[str, Any]) -> Path | str:
        """The starting path the call site gives: its ``repo_root``, else its log path's directory, else ``.``."""
        repo_root, log_path = kwargs.get("repo_root"), kwargs.get("log_path")
        if repo_root is not None:
            return repo_root  # type: ignore[no-any-return]
        return Path(log_path).parent if log_path is not None else "."

    @classmethod
    def handle(cls, slot: str, kwargs: Mapping[str, Any]) -> None:
        """Hand the seam call's row to the process-wide client; never raise."""
        try:
            row = kwargs.get("row")
            if not isinstance(row, Mapping):
                return
            start_path = cls.start_path_for(kwargs)
            seam_args = {key: value for key, value in kwargs.items() if key != "row"}
            cls.current().produce(row=row, slot=slot, seam_args=seam_args, start_path=start_path)
        except Exception:  # noqa: BLE001 - the outermost catch: a handler never raises into the seam
            return

    def _timeout(self) -> float:
        try:
            bound = self._bound_resolver() if self._bound_resolver is not None else 0.0
        except Exception:  # noqa: BLE001 - a broken resolver must not break the seam
            bound = 0.0
        return effective_client_timeout(bound)

    def produce(
        self,
        *,
        row: Mapping[str, Any],
        slot: str,
        seam_args: Mapping[str, Any],
        start_path: Path | str,
    ) -> None:
        """Hand one persisted row to the producer; record a fault or notice, never raise."""
        try:
            self._produce(row=row, slot=slot, seam_args=seam_args, start_path=start_path)
        except Exception:  # noqa: BLE001 - the outermost catch of the client
            record_fault(FaultClass.CRASH, row=row, detail="client error", start_path=start_path)

    def _produce(self, *, row: Mapping[str, Any], slot: str, seam_args: Mapping[str, Any], start_path: Path | str) -> None:
        if self._tripped is not None:
            record_fault(self._tripped, row=row, start_path=start_path)
            return
        timeout = self._timeout()
        request = {
            "v": _PROTOCOL_VERSION,
            "op": "produce",
            "deadline_ms": max(1, int(timeout * 1000)),
            "start_path": str(start_path),
            "slot": slot,
            "seam_args": dict(seam_args),
            "row": dict(row),
        }
        fault, reply = self._run(request, timeout)
        if fault is None and reply is not None:
            fault = self._classify(reply, row=row, start_path=start_path)
            if fault is None:
                return
            record_fault(fault, row=row, **self._reply_identifiers(reply), detail=self._detail(reply), start_path=start_path)
            return
        if fault in (FaultClass.TIMEOUT, FaultClass.CRASH):
            self._tripped = fault
        record_fault(fault or FaultClass.CRASH, row=row, start_path=start_path)

    def retain(self, *, start_path: Path | str, dry_run: bool, policy: Mapping[str, Any]) -> dict[str, Any]:
        """Run the producer's ``retain`` operation and return its reply, or an error reply.

        A failure here is an error to render, never an event fault: it writes
        no fault-log row (FR-023).
        """
        timeout = self._timeout()
        request = {
            "v": _PROTOCOL_VERSION,
            "op": "retain",
            "deadline_ms": max(1, int(timeout * 1000)),
            "start_path": str(start_path),
            "dry_run": dry_run,
            "policy": dict(policy),
        }
        try:
            fault, reply = self._run(request, timeout)
        except Exception:  # noqa: BLE001 - see produce
            fault, reply = FaultClass.CRASH, None
        if fault is not None or reply is None:
            return {"outcome": "error", "reason": (fault or FaultClass.CRASH).value}
        if reply.get("op") != "retain":
            reason = reply.get("reason")
            return {"outcome": "error", "reason": reason if isinstance(reason, str) else FaultClass.MALFORMED_REPLY.value}
        return reply

    def _run(self, request: Mapping[str, Any], timeout: float) -> tuple[FaultClass | None, dict[str, Any] | None]:
        """Spawn the producer (argv list, no shell), send one request, read one reply."""
        binary = locate_binary()
        if binary is None:
            return FaultClass.BINARY_MISSING, None
        payload = json.dumps(request, default=str).encode("utf-8")
        try:
            child = subprocess.Popen(  # noqa: S603 - argv list, no shell, a package-relative binary
                [str(binary)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
        except OSError:
            return FaultClass.BINARY_MISSING, None
        try:
            stdout, _ = child.communicate(payload, timeout=timeout)
        except subprocess.TimeoutExpired:
            child.kill()
            child.communicate()
            return FaultClass.TIMEOUT, None
        except OSError:
            child.kill()
            child.wait()
            return FaultClass.CRASH, None
        if child.returncode == _EXIT_DEADLINE:
            return FaultClass.TIMEOUT, None
        if child.returncode != 0 or not stdout:
            return FaultClass.CRASH, None
        reply = self._read_reply(stdout)
        return (None, reply) if reply is not None else (FaultClass.MALFORMED_REPLY, None)

    @staticmethod
    def _read_reply(stdout: bytes) -> dict[str, Any] | None:
        """Decode the one reply object (the one reply-decode site); ``None`` when it is not a v1 object."""
        if len(stdout) > _MAX_REPLY_BYTES:
            return None
        try:
            reply = json.loads(stdout.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return None
        if not isinstance(reply, dict) or reply.get("v") != _PROTOCOL_VERSION:
            return None
        return reply

    @staticmethod
    def _reply_identifiers(reply: Mapping[str, Any]) -> dict[str, Any]:
        event_type, event_id = reply.get("type"), reply.get("event_id")
        return {
            "reply_event_type": event_type if isinstance(event_type, str) else None,
            "reply_event_id": event_id if isinstance(event_id, str) else None,
        }

    @staticmethod
    def _detail(reply: Mapping[str, Any]) -> str | None:
        detail = reply.get("detail")
        return detail if isinstance(detail, str) else None

    def _classify(self, reply: Mapping[str, Any], *, row: Mapping[str, Any], start_path: Path | str) -> FaultClass | None:
        """Map a decoded produce reply to a fault class, or ``None`` when it is not a fault.

        A torn-line notice is recorded here (one row, not a fault). An
        unresolvable root is a refusal that is warned about but is not a
        fault-log row, so it is recorded here and reported as no fault.
        """
        outcome = reply.get("outcome")
        if reply.get("op") != "produce" or outcome not in _PRODUCE_OUTCOMES:
            return FaultClass.MALFORMED_REPLY
        notice = reply.get("notice")
        if isinstance(notice, dict) and notice.get("kind") == "torn-line":
            path, line = notice.get("path"), notice.get("line")
            if isinstance(path, str) and isinstance(line, int) and not isinstance(line, bool):
                record_notice(path=path, line=line, start_path=start_path)
        if outcome != "refused":
            return None
        fault = _REFUSAL_CLASSES.get(str(reply.get("reason_class")))
        if fault is None:
            return FaultClass.MALFORMED_REPLY
        if fault is FaultClass.REJECTED and reply.get("reason") == _ROOT_UNRESOLVABLE:
            record_fault(fault, row=row, **self._reply_identifiers(reply), detail=self._detail(reply), start_path=start_path, log=False)
            return None
        return fault


def produce_status_handler(**kwargs: Any) -> None:
    """The ``status`` slot handler: one persisted status or annotation row to the producer."""
    ProducerClient.handle("status", kwargs)


def produce_lifecycle_handler(**kwargs: Any) -> None:
    """The ``lifecycle`` slot handler: one persisted lifecycle, decision, retrospective or journal row."""
    ProducerClient.handle("lifecycle", kwargs)
