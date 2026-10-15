"""A degraded producer never fails a lifecycle command and is never silent, end to end (mission event-producer-reroute, WP10 T065, AC-005, NFR-007).

The same real command runs once against the REAL native producer (the healthy baseline) and once per fault: a missing binary, a
crashing producer, a slow producer past the client's timeout and one that answers nonsense. Each fault run must exit with the
baseline's code, leave the ``--json`` body (and the human output) byte-identical once the per-run values are normalised, leave the
ledger row in place, write exactly one stderr warning naming the class and one fault-log row, and still succeed when the fault log
cannot be written. Crash, slow and malformed producers are ``python <script>`` children substituted for the binary by the harness's
launcher, so the same cases run on every OS.

Collected by directory in lanes that build no binary: skipped there unless ``REQUIRE_NATIVE_PRODUCER=1`` (only ``native-e2e``).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from tests.integration._native_binary import (
    Project,
    emit_args,
    make_project,
    require_native_binary,
    run_cli,
)

pytestmark = [pytest.mark.integration, pytest.mark.git_repo, pytest.mark.real_event_producer]

#: fault name -> (stub script body or None for a missing binary, the class the warning must name)
_FAULTS: dict[str, tuple[str | None, str]] = {
    "binary-missing": (None, "binary-missing"),
    "crash": ("import sys\nsys.stdin.read()\nsys.exit(1)\n", "crash"),
    "slow": ("import sys, time\nsys.stdin.read()\ntime.sleep(60)\n", "timeout"),
    "malformed": ("import sys\nsys.stdin.read()\nsys.stdout.write('this is not json')\n", "malformed-reply"),
}
_TIMEOUT_ENV = {"SPEC_KITTY_FANOUT_TIMEOUT": "4"}


@pytest.fixture(scope="module", autouse=True)
def binary() -> Path:
    """Autouse: every test of this module needs the real binary, so without it the whole module skips (fails under the flag)."""
    return require_native_binary()


def _run(binary: Path | None, project: Project, *, human: bool, stub: Path | None = None) -> tuple[int, str, str]:
    args = emit_args(project.slug, "WP01", "claimed")
    if human:
        args = [a for a in args if a != "--json"]
    result = run_cli(binary, project.root, *args, stub=stub, env=_TIMEOUT_ENV)
    return result.returncode, _normalised(result.stdout, project), result.stderr


def _normalised(stdout: str, project: Project) -> str:
    """The stdout with the per-run values (the new event's id, the repository path) replaced."""
    row = project.ledger()[-1]
    return stdout.replace(str(project.root), "<repo>").replace(row["event_id"], "<event-id>").replace(row["event_id"][:12], "<event-id>")


def _warning_lines(stderr: str) -> list[str]:
    return [line for line in stderr.splitlines() if line.strip()]


@pytest.mark.parametrize("mode", ["json", "human"])
@pytest.mark.parametrize("fault", sorted(_FAULTS))
def test_a_producer_fault_leaves_the_exit_code_and_the_output_unchanged_and_warns_once(binary: Path, tmp_path: Path, fault: str, mode: str) -> None:
    human = mode == "human"
    healthy = make_project(tmp_path / "healthy")
    healthy_code, healthy_out, healthy_err = _run(binary, healthy, human=human)
    assert healthy_code == 0 and healthy_err == "" and len(healthy.publish_lines()) == 1, "premise: the real producer works"

    body, expected_class = _FAULTS[fault]
    faulted = make_project(tmp_path / "faulted")
    stub: Path | None = None
    if body is not None:
        stub = tmp_path / f"{fault}-stub.py"
        stub.write_text(body, encoding="utf-8")
    code, out, err = _run(Path(sys.executable) if stub else None, faulted, human=human, stub=stub)

    assert code == healthy_code == 0
    assert out == healthy_out, "a producer fault must not change a byte of stdout"
    if not human:
        json.loads(out.replace("<repo>", "x"))  # still one JSON document
    assert faulted.ledger()[-1]["to_lane"] == "claimed", "the ledger row is present whatever the producer does"
    warnings = _warning_lines(err)
    assert len(warnings) == 1, warnings
    assert f"producer fault {expected_class}" in warnings[0] and "produced" in warnings[0], warnings[0]
    rows = faulted.fault_rows()
    assert [row.get("class") for row in rows] == [expected_class], rows
    assert faulted.publish_lines() == [] or fault == "slow", "a faulted run publishes nothing (a slow producer may still finish)"


def test_an_unwritable_fault_log_still_warns_and_the_command_succeeds(binary: Path, tmp_path: Path) -> None:
    project = make_project(tmp_path / "project")
    faults = project.root / ".kittify" / "events" / "faults"
    faults.parent.mkdir(parents=True, exist_ok=True)
    faults.write_text("a file where the fault log directory should be\n", encoding="utf-8")
    stub = tmp_path / "crash-stub.py"
    stub.write_text(_FAULTS["crash"][0] or "", encoding="utf-8")

    code, _out, err = _run(Path(sys.executable), project, human=False, stub=stub)

    assert code == 0
    assert len(_warning_lines(err)) == 1 and "producer fault crash" in err
    assert faults.is_file(), "nothing replaced the blocker"


def test_the_fault_vocabulary_has_no_disabled_class() -> None:
    """OD-OPTOUT / NFR-007: the degraded states are the closed set; there is no producer opt-out to read."""
    from specify_cli.status.producer_faults import FaultClass  # noqa: PLC0415

    assert {fault.value for fault in FaultClass} == {"binary-missing", "crash", "timeout", "malformed-reply", "clock-overflow", "rejected", "busy"}
    assert "disabled" not in {fault.value for fault in FaultClass}
