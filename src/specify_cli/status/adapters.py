"""Fan-out adapter registry for status events.

Provides a decoupled callback boundary so that status/emit.py does not
need to depend on any transport. Handlers are registered into the slots
below by whichever subsystem owns a transport. The default wiring (this
module's import tail) installs two occupants:

* the event-producer client (:func:`ensure_producer_client`) into the status
  and lifecycle slots, always and ahead of every other handler: producing an
  event never depends on whether a hosted drain is enabled or on a hosted kill
  switch (ADR 2026-10-13-2). It is the single in-CLI entry point to the
  producer (upstream ADR ``2026-10-14-1-event-native-core-one-canonical-event-writer``:
  each event type has one writer);
* the Zeitgeist moment handler (#8), the hosted occupant; that tail honours
  the moment-handler gate
  (:func:`specify_cli.core.env.moment_handlers_disabled_reason` —
  ``SPEC_KITTY_NO_MOMENT_HANDLERS``, the ``SPEC_KITTY_SYNC_DISABLE`` kill
  switch, or the deprecated ``SPEC_KITTY_SYNC_MINIMAL_IMPORT`` alias, #3980).
  The drain gate lives inside the Zeitgeist handlers themselves, so it
  silences only them.

All fire_* functions are non-raising: exceptions from individual
handlers are caught and logged, never re-raised to the caller. An empty
registry is a no-op.
"""

from __future__ import annotations

import logging
import math
import os
import threading
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from specify_cli.core.env import moment_handlers_disabled_reason

# Module-level imports so registration below is a plain function call, never
# an import side effect hidden in another package's init. The producer client
# is the default occupant of the status and lifecycle slots; the E3 Zeitgeist
# moment handler (#8) is the hosted one.
from specify_cli.events import sanitize_event_for_log

from .producer_client import ProducerClient, produce_lifecycle_handler, produce_status_handler
from .producer_faults import FaultClass, record_fault
from .zeitgeist_bridge import (
    lifecycle_moment_handler,
    resolved_binding_moment_handler,
    saas_moment_handler,
)

logger = logging.getLogger(__name__)

# Wall-time bound (seconds) for a single fan-out handler. The fan-out contract
# is "a transport failure never blocks canonical persistence" -- a guarantee
# that must cover a *hanging* handler (e.g. a transport polling something that
# has stopped answering), not just a raising one. Handlers run in a
# short-lived daemon thread joined with this timeout; on timeout the caller
# (canonical persistence) proceeds and the worker is left to unwind on its own.
# Generous enough for a legitimate enqueue; a non-positive value runs handlers
# inline (opt-out).
_DEFAULT_FANOUT_TIMEOUT_S = 10.0
_FANOUT_TIMEOUT_ENV = "SPEC_KITTY_FANOUT_TIMEOUT"
# The former, transport-named spelling of the override. Operators and CI jobs
# already set it, so it keeps working; the neutral name wins when both are set.
_LEGACY_FANOUT_TIMEOUT_ENV = "SPEC_KITTY_SAAS_FANOUT_TIMEOUT"


def _fanout_timeout_s() -> float:
    """Resolve the per-handler fan-out timeout from the environment.

    ``SPEC_KITTY_FANOUT_TIMEOUT`` is read first; the legacy
    ``SPEC_KITTY_SAAS_FANOUT_TIMEOUT`` is the alias when the neutral name is
    unset or blank. Non-finite values (``nan``/``inf``) are rejected:
    ``Thread.join(nan)`` raises immediately (defeating the bound) and
    ``join(inf)`` overflows platform time -- both would silently disable the
    safety mechanism, so they fall back to the default.
    """
    raw = os.environ.get(_FANOUT_TIMEOUT_ENV)
    if raw is None or not raw.strip():
        raw = os.environ.get(_LEGACY_FANOUT_TIMEOUT_ENV)
    if raw is None or not raw.strip():
        return _DEFAULT_FANOUT_TIMEOUT_S
    try:
        value = float(raw)
    except ValueError:
        return _DEFAULT_FANOUT_TIMEOUT_S
    if math.isnan(value) or math.isinf(value):
        return _DEFAULT_FANOUT_TIMEOUT_S
    return value


class _FanoutHandlerBusyError(RuntimeError):
    """A prior invocation of this handler is still running (orphaned past its timeout)."""


# Handler-identity keys whose bounded worker has not yet returned. A handler
# that hangs past its timeout stays here until its orphaned worker finally
# unwinds, so a later fan-out for the same handler is skipped rather than
# overlapped — this caps orphan threads at one per handler and prevents two
# live invocations from concurrently touching the handler's non-atomic writers. Only relevant in a long-lived
# process (a daemon); one-shot CLI processes exit before it matters.
_inflight_fanout_handlers: set[str] = set()
_inflight_lock = threading.Lock()


def _run_fanout_handler_bounded(handler: Callable[..., None], kwargs: dict[str, Any], *, label: str) -> None:
    """Run one fan-out handler with a wall-time bound.

    Raises ``TimeoutError`` if the handler outlives the configured timeout, or
    ``_FanoutHandlerBusyError`` if a prior invocation of the same handler is
    still in flight, so the caller can log and move on; re-raises any handler
    exception so the existing per-handler ``except`` keeps catching it. A
    non-positive timeout runs the handler inline (no thread), preserving legacy
    behaviour.
    """
    timeout_s = _fanout_timeout_s()
    if timeout_s <= 0:
        handler(**kwargs)
        return

    key = _handler_key(handler)
    with _inflight_lock:
        if key in _inflight_fanout_handlers:
            raise _FanoutHandlerBusyError(f"{label} handler still in flight")
        _inflight_fanout_handlers.add(key)

    error: list[Exception] = []

    def _target() -> None:
        try:
            handler(**kwargs)
        except Exception as exc:  # mirror the caller's per-handler catch
            error.append(exc)
        finally:
            with _inflight_lock:
                _inflight_fanout_handlers.discard(key)

    worker = threading.Thread(target=_target, name=f"{label}-handler", daemon=True)
    worker.start()
    worker.join(timeout_s)
    if worker.is_alive():
        # Leave `key` in-flight: the orphaned worker clears it on unwind, which
        # is what suppresses an overlapping invocation until then.
        raise TimeoutError(f"{label} handler exceeded {timeout_s:.1f}s")
    if error:
        raise error[0]


#: One log line of a dispatch outcome: a ``%``-format message and its arguments.
_LogLine = tuple[str, tuple[Any, ...]]


def _dispatch_handlers(
    handlers: list[Callable[..., None]],
    kwargs: dict[str, Any],
    *,
    label: str,
    busy: _LogLine,
    timed_out: _LogLine,
    failed: _LogLine,
    failed_quietly: bool = False,
) -> None:
    """Run every handler of one slot, bounded, in registration order; never raise.

    The one place the three ``fire_*`` functions share: a handler still in
    flight is skipped (``busy``), one that outlives the bound is abandoned
    (``timed_out``) and one that raises is logged (``failed``, with the
    traceback). Each outcome carries the slot's own message and arguments.

    A busy-skip or a timeout of the producer client's own handler is a
    producer fault (FR-023): it is recorded beside the log line. A slow hosted
    handler records none, so no "event was NOT produced" warning is printed
    for it.
    """
    client_keys = {_handler_key(produce_status_handler), _handler_key(produce_lifecycle_handler)}
    row = kwargs.get("row")
    if isinstance(row, Mapping):
        # The persisted row as written to the ledger: the same sanitisation the
        # ledger writers apply, so the row a handler sees equals the ledger line (FR-004).
        # A Mapping that cannot be snapshotted is a producer fault, never an escape.
        try:
            kwargs["row"] = sanitize_event_for_log(dict(row))
        except Exception:
            logger.warning(failed[0], *failed[1], exc_info=True)
            record_fault(FaultClass.CRASH, row=None, start_path=ProducerClient.start_path_for(kwargs))
            return
    for handler in handlers:
        is_client = _handler_key(handler) in client_keys
        try:
            _run_fanout_handler_bounded(handler, kwargs, label=label)
        except _FanoutHandlerBusyError:
            logger.warning(busy[0], *busy[1])
            if is_client:
                record_fault(FaultClass.BUSY, row=kwargs.get("row"), start_path=ProducerClient.start_path_for(kwargs))
        except TimeoutError:
            logger.warning(timed_out[0], *timed_out[1])
            if is_client:
                record_fault(FaultClass.TIMEOUT, row=kwargs.get("row"), start_path=ProducerClient.start_path_for(kwargs))
        except Exception:
            (logger.debug if failed_quietly else logger.warning)(failed[0], *failed[1], exc_info=True)


# Callback signature: keyword-only, mirrors emit_wp_status_changed
StatusFanOutHandler = Callable[..., None]

LifecycleFanOutHandler = Callable[..., None]

# Callback signature: keyword-only, carries the resolved binding + WP identity
# for the first-class ``WPResolvedBindingChanged`` bridge (FR-015 / IC-09).
ResolvedBindingFanOutHandler = Callable[..., None]

_status_handlers: list[StatusFanOutHandler] = []
_lifecycle_handlers: list[LifecycleFanOutHandler] = []
_resolved_binding_handlers: list[ResolvedBindingFanOutHandler] = []


def _handler_key(cb: Callable[..., Any]) -> str:
    """Return a stable identity key for a registered handler.

    Uses ``__module__`` + ``__qualname__`` (falling back to ``__name__``)
    so that the same logical handler is treated as identical across
    module reloads that produce fresh function objects.
    """
    module = getattr(cb, "__module__", None)
    qualname = getattr(cb, "__qualname__", None)
    name = qualname if isinstance(qualname, str) else getattr(cb, "__name__", None)
    if isinstance(module, str) and isinstance(name, str):
        return f"{module}.{name}"
    if isinstance(name, str):
        return name
    return repr(cb)


def register_status_fanout_handler(cb: StatusFanOutHandler) -> None:
    """Register a status fan-out callback (idempotent by qualified name).

    Re-registration of a handler with the same ``__qualname__`` replaces
    the existing entry rather than appending, so re-registering does not
    stack duplicate fan-out invocations.
    """
    key = _handler_key(cb)
    for idx, existing in enumerate(_status_handlers):
        if _handler_key(existing) == key:
            _status_handlers[idx] = cb
            return
    _status_handlers.append(cb)


def register_lifecycle_fanout_handler(cb: LifecycleFanOutHandler) -> None:
    """Register a lifecycle fan-out callback (idempotent by qualified name)."""
    key = _handler_key(cb)
    for idx, existing in enumerate(_lifecycle_handlers):
        if _handler_key(existing) == key:
            _lifecycle_handlers[idx] = cb
            return
    _lifecycle_handlers.append(cb)


def register_resolved_binding_fanout_handler(cb: ResolvedBindingFanOutHandler) -> None:
    """Register a ``WPResolvedBindingChanged`` fan-out callback (idempotent by name).

    Mirrors :func:`register_status_fanout_handler`. The Zeitgeist moment handler
    builds the concrete ``spec_kitty_events.WPResolvedBindingChanged`` payload
    once the events package ships it; until then the status layer's version
    gate skips the fan-out (see ``emit._resolved_binding_fan_out``).
    """
    key = _handler_key(cb)
    for idx, existing in enumerate(_resolved_binding_handlers):
        if _handler_key(existing) == key:
            _resolved_binding_handlers[idx] = cb
            return
    _resolved_binding_handlers.append(cb)


def ensure_zeitgeist_moment_handlers() -> None:
    """Register the Zeitgeist moment handlers into all three fan-out slots.

    Idempotent: the ``register_*`` functions de-duplicate by qualified name,
    so calling this repeatedly is safe. This is the E3 (#8) default wiring of
    these slots — the sole default occupant since the sync package was
    deleted (#5/#114) — and, per the egress-consent precedent, it lives where
    the answer is needed rather than in some other package's init. The
    import tail calls it once; tests that wipe the registry via
    :func:`reset_handlers` call it again to restore production wiring.
    """
    register_status_fanout_handler(saas_moment_handler)
    register_lifecycle_fanout_handler(lifecycle_moment_handler)
    register_resolved_binding_fanout_handler(resolved_binding_moment_handler)


def ensure_producer_client(bound_resolver: Callable[[], float] = _fanout_timeout_s) -> None:
    """Register the event-producer client into the status and lifecycle slots.

    The client is registered outside the ``moment_handlers_disabled_reason``
    branch of the import tail and ignores the hosted kill switches: the
    producer has no opt-out (OD-OPTOUT). ``bound_resolver`` is how the client
    learns the effective seam bound without importing a private name from this
    module; a fresh client (a closed breaker) is installed on each call. The
    resolved-binding slot stays unregistered by the client (FR-003): the
    annotation row it carries reaches the producer through the status slot.
    """
    ProducerClient.install(bound_resolver=bound_resolver)
    register_status_fanout_handler(produce_status_handler)
    register_lifecycle_fanout_handler(produce_lifecycle_handler)


def ensure_runtime_moment_producer() -> None:
    """Register the E3 runtime-moment producer at the runtime emitter seam (#3929).

    Called by the runtime bridge immediately before it obtains the seam, never
    from this module's import tail: importing the runtime seam while the status
    package is still importing re-enters ``runtime.next`` mid-import. The
    registry is idempotent by qualified name, and the seam itself applies the
    moment-handler gate at call time (S2). The producer publishes through the
    lifecycle slot above.
    """
    from runtime.next._internal_runtime.events import register_runtime_emitter_factory  # noqa: PLC0415
    from specify_cli.events.runtime_moments import RuntimeMomentProducer  # noqa: PLC0415

    register_runtime_emitter_factory(RuntimeMomentProducer.for_mission)


def reset_handlers() -> None:
    """Clear all registered handlers (test-only utility).

    Clears the producer client and the Zeitgeist moment handlers too; a test
    that wipes the registry and wants the production wiring back calls
    :func:`ensure_producer_client` and
    :func:`ensure_zeitgeist_moment_handlers` (the status conftest does exactly
    that around every test, so one test's wipe cannot silence another's
    fan-out).
    """
    _status_handlers.clear()
    _lifecycle_handlers.clear()
    _resolved_binding_handlers.clear()


def _fanout_force(kwargs: dict[str, Any]) -> Any:
    """Resolve the force flag for the #1141 breadcrumb.

    Duck-typed and best-effort: lifecycle / other-event callers pass ``force``
    as a top-level kwarg, while WPStatusChanged producers bundle the optional
    tail (including ``force``) into a ``metadata`` params object. Read whichever
    is present so the diagnostic breadcrumb keeps surfacing the flag; never
    raise.
    """
    if "force" in kwargs:
        return kwargs["force"]
    return getattr(kwargs.get("metadata"), "force", None)


def fire_status_fanout(*, repo_root: Path | None = None, **kwargs: Any) -> None:
    """Call all registered status fan-out handlers with **kwargs.

    Guarantees:
    - Handlers called in registration order.
    - Exceptions are caught per handler, logged at WARNING level, and
      never propagate to the caller.
    - If no handlers are registered, this is a no-op.

    Logs an INFO-level entry breadcrumb (issue #1141) so silent fan-out
    failures on review-rejection / backward-rewind transitions are
    correlatable with the originating canonical event in operator logs.
    The breadcrumb identifies the WP, lane delta, and force flag; the
    full kwargs are NOT logged (PII / payload-size reasons).

    ``repo_root`` (#5181): the repository that OWNS this transition -- the
    checkout ``status/emit.py`` and ``coordination/outbound.py`` were given,
    never the process's own CWD. It is re-joined into the kwargs handed to
    handlers: the producer client takes it as its starting path, and the
    Zeitgeist handler reads it to resolve relay credentials and to scope its
    own drain gate. The call fires the producer whatever the drain posture is.
    """
    kwargs["repo_root"] = repo_root
    # Diagnostic breadcrumb (issue #1141). Cheap dict.get() calls avoid
    # raising if the caller drops a key; the breadcrumb is best-effort and
    # never blocks fan-out. Log even with zero handlers; a missing handler
    # registration is one of the states this breadcrumb is meant to reveal.
    logger.info(
        "fire_status_fanout: wp_id=%s from=%s to=%s force=%s handlers=%d",
        kwargs.get("wp_id"),
        kwargs.get("from_lane"),
        kwargs.get("to_lane"),
        _fanout_force(kwargs),
        len(_status_handlers),
    )
    wp_args = (kwargs.get("wp_id"), kwargs.get("from_lane"), kwargs.get("to_lane"))
    forced_args = (*wp_args, _fanout_force(kwargs))
    _dispatch_handlers(
        _status_handlers,
        kwargs,
        label="Status fan-out",
        busy=(
            "Status fan-out handler still in flight from a prior transition; skipped to avoid overlap (wp_id=%s from=%s to=%s)",
            wp_args,
        ),
        timed_out=(
            "Status fan-out handler timed out; canonical status log unaffected (wp_id=%s from=%s to=%s force=%s)",
            forced_args,
        ),
        failed=(
            "Status fan-out handler failed; canonical status log unaffected (wp_id=%s from=%s to=%s force=%s)",
            forced_args,
        ),
    )


def fire_resolved_binding_fanout(*, repo_root: Path | None = None, **kwargs: Any) -> None:
    """Call all registered ``WPResolvedBindingChanged`` fan-out handlers with **kwargs.

    Same non-raising / bounded contract as :func:`fire_status_fanout`: exceptions
    from a handler are caught and logged, never re-raised, so a resolved-binding
    fan-out can never block canonical local persistence. An empty registry is a
    no-op. The status layer only reaches here once its version gate confirms the
    installed ``spec_kitty_events`` supports the event (FR-015 / IC-09).

    ``repo_root`` (#5181): see :func:`fire_status_fanout`; it is absorbed here
    and not forwarded (FR-004 names the status and lifecycle fires only).
    """
    del repo_root  # not forwarded to handlers (FR-004 names the status and lifecycle fires only)
    logger.info(
        "fire_resolved_binding_fanout: wp_id=%s mission_slug=%s handlers=%d",
        kwargs.get("wp_id"),
        kwargs.get("mission_slug"),
        len(_resolved_binding_handlers),
    )
    wp_arg = (kwargs.get("wp_id"),)
    _dispatch_handlers(
        _resolved_binding_handlers,
        kwargs,
        label="Resolved-binding fan-out",
        busy=("Resolved-binding fan-out handler still in flight from a prior emit; skipped to avoid overlap (wp_id=%s)", wp_arg),
        timed_out=("Resolved-binding fan-out handler timed out; canonical status log unaffected (wp_id=%s)", wp_arg),
        failed=("Resolved-binding fan-out handler failed; canonical status log unaffected (wp_id=%s)", wp_arg),
    )


def fire_lifecycle_fanout(*, repo_root: Path | None = None, **kwargs: Any) -> None:
    """Call all registered lifecycle fan-out handlers with **kwargs.

    ``repo_root`` (#5181): see :func:`fire_status_fanout`; it is forwarded to
    handlers as the status fire forwards it (FR-004), so the producer client
    starts its root resolution from the emitting repository. Callers resolve it
    themselves (e.g. ``lifecycle_events.repo_root_for_lifecycle_log(log_path)``)
    since this module must not reach back into ``lifecycle_events``/``decisions``
    at import time.
    """
    kwargs["repo_root"] = repo_root
    _dispatch_handlers(
        _lifecycle_handlers,
        kwargs,
        label="Lifecycle fan-out",
        busy=("Lifecycle fan-out handler still in flight from a prior transition; skipped to avoid overlap", ()),
        timed_out=("Lifecycle fan-out handler timed out; canonical lifecycle log unaffected", ()),
        failed=("Lifecycle fan-out handler failed; canonical lifecycle log unaffected", ()),
        failed_quietly=True,
    )


# Default wiring. The producer client is registered first and unconditionally:
# the hosted kill switches below gate the hosted handler only, never the
# producer (FR-002, OD-OPTOUT). The Zeitgeist moment handler follows under its
# own gate: SPEC_KITTY_NO_MOMENT_HANDLERS means "register no hosted transport
# at import time" -- the kill switch SPEC_KITTY_SYNC_DISABLE folds in (a
# disarmed process registers no hosted transport), and the former
# SPEC_KITTY_SYNC_MINIMAL_IMPORT name survives as a deprecated alias that warns
# once when honored. A caller needing an empty registry (tests) calls
# reset_handlers() and restores with ensure_producer_client() and
# ensure_zeitgeist_moment_handlers().
ensure_producer_client()
if moment_handlers_disabled_reason() is None:
    ensure_zeitgeist_moment_handlers()
