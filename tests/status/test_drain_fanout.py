"""The drain gate lives in the Zeitgeist handlers, not in the fan-out seam.

``fire_status_fanout``, ``fire_resolved_binding_fanout`` and
``fire_lifecycle_fanout`` fire every registered handler whatever the drain
posture is (the event producer must run with drain off, FR-002). The hosted
(Zeitgeist) handlers consult the emitting repository's own drain posture and do
nothing, broadcast nothing, when it is off. The runtime-moment producer no
longer reads the posture before it hands a journal row to the seam, and the
bridge's outcome logging still logs a ``drain_disabled`` outcome at debug,
never WARNING.

``TestRepoScopedGateReadsTheEmittingRepo`` (issue #5181) pins the
repository-scoped half of the gate: the drain posture a Zeitgeist handler
consults must be the EMITTING repo's own ``.kittify/config.yaml``, never the
process's current working directory. Those tests carry ``real_drain_posture``
(no mocked ``drain_posture``) and construct two real on-disk repos with
divergent ``hosted.drain`` values.
"""

from __future__ import annotations

import logging
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock

import pytest

from specify_cli.core.env import MOMENT_HANDLER_DISABLE_ENV_VARS
from specify_cli.events.runtime_moments import RuntimeMomentProducer
from specify_cli.status import adapters, zeitgeist_bridge

pytestmark = pytest.mark.fast


@pytest.fixture(autouse=True)
def _clean_registry() -> Any:
    adapters.reset_handlers()
    yield
    adapters.reset_handlers()


# ---------------------------------------------------------------------------
# The seam fires its handlers whatever the drain posture is
# ---------------------------------------------------------------------------


class TestTheSeamIgnoresDrain:
    def test_the_status_fire_runs_every_handler_under_drain_off(self, drain_off: None) -> None:
        spy = Mock(name="status-handler")
        adapters.register_status_fanout_handler(spy)

        adapters.fire_status_fanout(wp_id="WP01", from_lane="planned", to_lane="claimed")

        spy.assert_called_once()

    def test_the_resolved_binding_fire_runs_every_handler_under_drain_off(self, drain_off: None) -> None:
        spy = Mock(name="resolved-binding-handler")
        adapters.register_resolved_binding_fanout_handler(spy)

        adapters.fire_resolved_binding_fanout(wp_id="WP01", mission_slug="demo")

        spy.assert_called_once()

    def test_the_lifecycle_fire_runs_every_handler_under_drain_off(self, drain_off: None) -> None:
        spy = Mock(name="lifecycle-handler")
        adapters.register_lifecycle_fanout_handler(spy)

        adapters.fire_lifecycle_fanout(event_type="MissionRunStarted")

        spy.assert_called_once()

    def test_every_fire_still_runs_its_handler_under_drain_on(self) -> None:
        """Baseline regression guard (root fixture: drain on)."""
        spies = {name: Mock(name=name) for name in ("status", "lifecycle", "resolved")}
        adapters.register_status_fanout_handler(spies["status"])
        adapters.register_lifecycle_fanout_handler(spies["lifecycle"])
        adapters.register_resolved_binding_fanout_handler(spies["resolved"])

        adapters.fire_status_fanout(wp_id="WP01")
        adapters.fire_lifecycle_fanout(event_type="x")
        adapters.fire_resolved_binding_fanout(wp_id="WP01")

        for spy in spies.values():
            spy.assert_called_once()


# ---------------------------------------------------------------------------
# AC-003 (f): the drain gate pins every Zeitgeist handler
# ---------------------------------------------------------------------------

_ZEITGEIST_HANDLERS = (
    ("saas_moment_handler", "_broadcast_status_transition", {"to_lane": "claimed"}),
    ("lifecycle_moment_handler", "_broadcast_lifecycle_envelope", {"envelope": {"event_type": "MissionCreated"}}),
)


class TestTheZeitgeistHandlersCarryTheDrainGate:
    @pytest.mark.parametrize(("handler_name", "broadcast_name", "kwargs"), _ZEITGEIST_HANDLERS)
    def test_a_handler_broadcasts_nothing_under_drain_off(
        self, handler_name: str, broadcast_name: str, kwargs: dict[str, Any], drain_off: None, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        broadcast = Mock(name=broadcast_name)
        monkeypatch.setattr(zeitgeist_bridge, broadcast_name, broadcast)

        getattr(zeitgeist_bridge, handler_name)(**kwargs)

        broadcast.assert_not_called()

    @pytest.mark.parametrize(("handler_name", "broadcast_name", "kwargs"), _ZEITGEIST_HANDLERS)
    def test_a_handler_broadcasts_under_drain_on(self, handler_name: str, broadcast_name: str, kwargs: dict[str, Any], monkeypatch: pytest.MonkeyPatch) -> None:
        broadcast = Mock(name=broadcast_name)
        monkeypatch.setattr(zeitgeist_bridge, broadcast_name, broadcast)

        getattr(zeitgeist_bridge, handler_name)(**kwargs)

        broadcast.assert_called_once()

    def test_the_resolved_binding_handler_is_gated_too(self, drain_off: None, caplog: pytest.LogCaptureFixture) -> None:
        caplog.set_level(logging.DEBUG, logger="specify_cli.status.zeitgeist_bridge")
        zeitgeist_bridge.resolved_binding_moment_handler(wp_id="WP01")
        assert not any("nothing broadcast" in record.message for record in caplog.records)

    def test_a_status_call_carrying_only_a_row_is_no_hosted_moment(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """An annotation row rides the status slot with no ``to_lane``: the hosted handler has nothing to broadcast."""
        broadcast = Mock(name="_broadcast_status_transition")
        monkeypatch.setattr(zeitgeist_bridge, "_broadcast_status_transition", broadcast)
        zeitgeist_bridge.saas_moment_handler(row={"event_id": "x"}, wp_id="WP01")
        broadcast.assert_not_called()


# ---------------------------------------------------------------------------
# Repo-scoped gate (#5181): the EMITTING repo's posture, never the CWD's.
# ---------------------------------------------------------------------------


def _repo_with_drain(tmp_path: Path, name: str, *, drain: bool | None) -> Path:
    """A tmp-path repo with a ``.kittify/config.yaml`` (present iff *drain* is not ``None``)."""
    root = tmp_path / name
    kittify = root / ".kittify"
    kittify.mkdir(parents=True)
    # A ``.git`` marker makes the repo its own canonical root: the status conftest
    # git-inits ``tmp_path`` (#142), so without it root resolution walks up out of the repo.
    (root / ".git").mkdir()
    if drain is not None:
        (kittify / "config.yaml").write_text(f"hosted:\n  drain: {'true' if drain else 'false'}\n", encoding="utf-8")
    return root


@pytest.fixture(autouse=True)
def _clear_moment_handler_env_for_real_gate(monkeypatch: pytest.MonkeyPatch) -> None:
    """Hermetic against an ambient env narrower for the ``real_drain_posture`` tests below.

    Mirrors ``tests/specify_cli/core/test_hosted_posture.py``'s module-local
    fixture of the same purpose: only a test's own explicit ``setenv`` should
    narrow drain here, never whatever the orchestrator's own process
    environment happens to carry.
    """
    for name in MOMENT_HANDLER_DISABLE_ENV_VARS:
        monkeypatch.delenv(name, raising=False)


@pytest.mark.real_drain_posture
class TestRepoScopedGateReadsTheEmittingRepo:
    """#5181: each Zeitgeist handler must read the emitting repo's own posture.

    Two real repos on disk, drain configured oppositely, with the process CWD
    pointed at the OTHER repo from the one passed as ``repo_root`` -- the bug
    this closes read ``drain_posture()`` with no ``project_root``, which falls
    back to ``locate_project_root()`` i.e. the CWD, so a transition for repo A
    was gated on repo B's config whenever CWD was repo B.
    """

    def test_the_status_handler_broadcasts_for_the_emitting_repo_even_when_cwd_is_a_drain_off_repo(
        self, tmp_path: Path, canonical_home: None, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        repo_a = _repo_with_drain(tmp_path, "repo_a", drain=True)
        repo_b = _repo_with_drain(tmp_path, "repo_b", drain=False)
        ((tmp_path / "home") / "config.toml").write_text("[hosted]\ndrain = true\n", encoding="utf-8")
        monkeypatch.chdir(repo_b)
        broadcast = Mock(name="_broadcast_status_transition")
        monkeypatch.setattr(zeitgeist_bridge, "_broadcast_status_transition", broadcast)

        adapters.register_status_fanout_handler(zeitgeist_bridge.saas_moment_handler)
        adapters.fire_status_fanout(wp_id="WP01", from_lane="planned", to_lane="claimed", repo_root=repo_a)

        broadcast.assert_called_once()

    def test_the_status_handler_stays_silent_for_the_emitting_repo_even_when_cwd_is_a_drain_on_repo(
        self, tmp_path: Path, canonical_home: None, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        repo_a = _repo_with_drain(tmp_path, "repo_a", drain=False)
        repo_b = _repo_with_drain(tmp_path, "repo_b", drain=True)
        ((tmp_path / "home") / "config.toml").write_text("[hosted]\ndrain = true\n", encoding="utf-8")
        monkeypatch.chdir(repo_b)
        broadcast = Mock(name="_broadcast_status_transition")
        monkeypatch.setattr(zeitgeist_bridge, "_broadcast_status_transition", broadcast)

        adapters.register_status_fanout_handler(zeitgeist_bridge.saas_moment_handler)
        adapters.fire_status_fanout(wp_id="WP01", from_lane="planned", to_lane="claimed", repo_root=repo_a)

        broadcast.assert_not_called()

    def test_the_lifecycle_handler_reads_the_emitting_repo(self, tmp_path: Path, canonical_home: None, monkeypatch: pytest.MonkeyPatch) -> None:
        """The lifecycle fire forwards ``repo_root`` (FR-004), so the handler's gate reads the emitting repo."""
        repo_a = _repo_with_drain(tmp_path, "repo_a", drain=True)
        repo_b = _repo_with_drain(tmp_path, "repo_b", drain=False)
        ((tmp_path / "home") / "config.toml").write_text("[hosted]\ndrain = true\n", encoding="utf-8")
        monkeypatch.chdir(repo_b)
        broadcast = Mock(name="_broadcast_lifecycle_envelope")
        monkeypatch.setattr(zeitgeist_bridge, "_broadcast_lifecycle_envelope", broadcast)

        adapters.register_lifecycle_fanout_handler(zeitgeist_bridge.lifecycle_moment_handler)
        adapters.fire_lifecycle_fanout(envelope={"event_type": "MissionCreated"}, repo_root=repo_a)

        broadcast.assert_called_once()


# ---------------------------------------------------------------------------
# Runtime-moment producer: reads no drain posture
# ---------------------------------------------------------------------------


class TestRuntimeMomentProducerIgnoresDrain:
    def test_publish_looks_up_the_run_journal_under_drain_off(self, tmp_path: Path, drain_off: None, monkeypatch: pytest.MonkeyPatch) -> None:
        """FR-002: ``_publish`` no longer reads the drain posture; the producer must see journal rows with drain off."""
        from specify_cli.events import runtime_moments as rm

        find_run_journal = Mock(name="find_run_journal", return_value=None)
        monkeypatch.setattr(rm, "find_run_journal", find_run_journal)

        producer = RuntimeMomentProducer(feature_dir=tmp_path, mission_slug="demo")
        payload = SimpleNamespace(model_dump=lambda mode: {"run_id": "r1"})
        producer._publish("MissionRunStarted", payload)  # type: ignore[arg-type]

        find_run_journal.assert_called_once()


# ---------------------------------------------------------------------------
# zeitgeist_bridge._log_offer_outcome: drain_disabled logs at debug, not WARNING
# ---------------------------------------------------------------------------


class TestLogOfferOutcomeDrainDisabled:
    def test_drain_disabled_outcome_logs_debug_never_warning(self, caplog: pytest.LogCaptureFixture) -> None:
        """A ``DRAIN_DISABLED`` offer result is a silent debug skip."""
        from specify_cli.zeitgeist_client.transport import OfferOutcome, OfferResult

        caplog.set_level(logging.DEBUG, logger="specify_cli.status.zeitgeist_bridge")
        result = OfferResult(outcome=OfferOutcome.DRAIN_DISABLED, request_id="req-1", elapsed_s=0.0)

        zeitgeist_bridge._log_offer_outcome("test", result)

        assert not any(record.levelno >= logging.WARNING for record in caplog.records)
        assert any(record.levelno == logging.DEBUG and "drain off" in record.message for record in caplog.records)
