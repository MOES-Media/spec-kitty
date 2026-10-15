"""AC-036 / NFR-002: what one spawn per row costs the largest multi-row command (mission event-producer-reroute, WP10 T068).

The measure is ``emit_status_transition_batch`` (the composite lane-transition command, one seam fire per persisted row) with the REAL
native producer registered on the seam, divided by the same batch with a no-op handler registered instead. Both are sampled
interleaved in one run (``tests/_perf_helpers.measure_group``), so a faster or slower machine moves numerator and denominator together:
the limits are RATIOS and live in ``tests/_perf_helpers.py`` (``tests/architectural/test_perf_limit_authority.py`` refuses a limit defined
here). The first producer sample is the COLD spawn (the first spawn of this process); the median of the rest is the WARM one.

While ``PRODUCER_LIMITS_PROVISIONAL`` is set the ratios are only RECORDED (printed, and appended to the job summary when
``GITHUB_STEP_SUMMARY`` is set), because the limits are to be set from the numbers of the three native-e2e legs. The planted-slowdown test
runs either way: it proves the limit machinery can fail. On a miss after the limits are set, NFR-002's contingency applies: do not
batch here; stop and amend FR-003, FR-026 and AC-004 first.

Collected by directory in lanes that build no binary: skipped there unless ``REQUIRE_NATIVE_PRODUCER=1`` (only ``native-e2e``).
"""

from __future__ import annotations

import os
import statistics
import time
from pathlib import Path

import pytest

from specify_cli.status import adapters, producer_client
from specify_cli.status.emit import emit_status_transition_batch
from specify_cli.status.models import TransitionRequest
from specify_cli.status.producer_client import ProducerClient
from tests._perf_helpers import (
    PRODUCER_COLD_RATIO_LIMIT,
    PRODUCER_LIMITS_PROVISIONAL,
    PRODUCER_PLANT_FACTOR,
    PRODUCER_SAMPLE_RUNS,
    Measurement,
    Sample,
    assert_timing_budget,
    expect_budget_exceeded,
    measure_interleaved,
    PRODUCER_WARM_RATIO_LIMIT,
)
from tests.integration._native_binary import Project, make_project, require_native_binary

pytestmark = [pytest.mark.integration, pytest.mark.timing, pytest.mark.real_event_producer, pytest.mark.git_repo]

#: The lane edges of one composite command: claim, start, hand over for review (three persisted rows, so three spawns).
_EDGES = ("claimed", "in_progress", "for_review")


@pytest.fixture(scope="module", autouse=True)
def binary() -> Path:
    """Autouse: every test of this module needs the real binary, so without it the whole module skips (fails under the flag)."""
    return require_native_binary()


def _batch(project: Project, wp_id: str) -> list[TransitionRequest]:
    return [
        TransitionRequest(
            feature_dir=project.mission_dir,
            mission_slug=project.slug,
            wp_id=wp_id,
            to_lane=lane,
            actor="perf",
            repo_root=project.root,
            workspace_context="worktree",
            subtasks_complete=True,
            implementation_evidence_present=True,
        )
        for lane in _EDGES
    ]


def _timed(requests: list[TransitionRequest]) -> Sample:
    started = time.perf_counter()
    events = emit_status_transition_batch(requests)
    seconds = time.perf_counter() - started
    return Sample(seconds, ok=len(events) == len(_EDGES), detail=f"the batch persisted {len(events)} rows, expected {len(_EDGES)}")


class _Seam:
    """Switches the fan-out seam between the real producer client and a no-op handler, and counts producer spawns."""

    def __init__(self, monkeypatch: pytest.MonkeyPatch, binary: Path) -> None:
        self.monkeypatch = monkeypatch
        self.spawns = 0
        real_run = ProducerClient._run

        def counting_run(client: ProducerClient, request, timeout):  # noqa: ANN001, ANN202 - mirrors the patched method
            self.spawns += 1
            self.before_spawn()
            return real_run(client, request, timeout)

        self.delay = 0.0
        monkeypatch.setattr(ProducerClient, "_run", counting_run)
        monkeypatch.setattr(producer_client, "locate_binary", lambda: binary)

    def before_spawn(self) -> None:
        if self.delay:
            time.sleep(self.delay)

    def use_producer(self) -> None:
        adapters.reset_handlers()
        adapters.ensure_producer_client()

    def use_noop(self) -> None:
        adapters.reset_handlers()
        adapters.register_status_fanout_handler(lambda **_kwargs: None)
        adapters.register_lifecycle_fanout_handler(lambda **_kwargs: None)


@pytest.fixture
def seam(monkeypatch: pytest.MonkeyPatch, binary: Path) -> _Seam:
    return _Seam(monkeypatch, binary)


def _measure(seam: _Seam, tmp_path: Path) -> tuple[Measurement, float, int]:
    """Interleave the real-producer batch with the no-op batch; return the measurement, the cold ratio and the spawns per producer batch."""
    runs = PRODUCER_SAMPLE_RUNS
    wps = tuple(f"WP{n:02d}" for n in range(1, 2 * runs + 1))
    project = make_project(tmp_path / "project", work_packages=wps)
    queue = iter(wps)

    def producer() -> Sample:
        seam.use_producer()
        return _timed(_batch(project, next(queue)))

    def noop() -> Sample:
        seam.use_noop()
        return _timed(_batch(project, next(queue)))

    measurement = measure_interleaved(producer, noop, runs=runs)
    spawns_before = seam.spawns
    assert spawns_before == len(_EDGES) * runs, "one spawn per persisted row, exactly (FR-001): no more, no fewer"
    cold = measurement.variants["command"].samples[0].seconds / measurement.floor.median
    return measurement, cold, len(_EDGES)


def _record(measurement: Measurement, cold: float) -> None:
    warm = [s.seconds for s in measurement.variants["command"].samples[1:]]
    line = (
        f"AC-036 {os.environ.get('RUNNER_OS', 'local')}: warm ratio {measurement.ratio('command'):.3f} (limit {PRODUCER_WARM_RATIO_LIMIT}), "
        f"cold ratio {cold:.3f} (limit {PRODUCER_COLD_RATIO_LIMIT}); producer batch median {measurement.variants['command'].median * 1000:.1f} ms, "
        f"no-op batch median {measurement.floor.median * 1000:.1f} ms, warm producer median {statistics.median(warm) * 1000:.1f} ms; "
        f"producer samples {measurement.variants['command'].raw}; no-op samples {measurement.floor.raw}"
    )
    print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"### {line}\n")


def test_the_overhead_of_one_spawn_per_row_stays_within_the_ratio_limits(seam: _Seam, tmp_path: Path) -> None:
    measurement, cold, _rows = _measure(seam, tmp_path)
    assert not measurement.floor.failure and not measurement.variants["command"].failure, measurement.describe("command", PRODUCER_WARM_RATIO_LIMIT, title="AC-036")
    _record(measurement, cold)
    if PRODUCER_LIMITS_PROVISIONAL:
        return  # the limits are provisional: this run RECORDED the numbers that will set them (see PRODUCER_LIMITS_PROVISIONAL)
    assert_timing_budget(measurement.ratio("command"), PRODUCER_WARM_RATIO_LIMIT, name="warm producer batch over no-op batch (ratio)")
    assert_timing_budget(cold, PRODUCER_COLD_RATIO_LIMIT, name="cold producer batch over no-op batch (ratio)")


def test_a_planted_slow_producer_exceeds_the_limit(seam: _Seam, tmp_path: Path) -> None:
    """The mutation: a client that spends too long per spawn must put the ratio over the warm limit (the machinery can fail)."""
    clean, _cold, rows = _measure(seam, tmp_path / "clean")
    noop_median = clean.floor.median
    seam.spawns = 0
    # Per spawn, enough extra time to push the warm ratio PRODUCER_PLANT_FACTOR times past the limit whatever the machine's speed.
    seam.delay = max(0.0, (PRODUCER_WARM_RATIO_LIMIT * PRODUCER_PLANT_FACTOR - clean.ratio("command")) * noop_median / rows)
    planted, _cold, _rows = _measure(seam, tmp_path / "planted")
    expect_budget_exceeded(planted.ratio("command"), PRODUCER_WARM_RATIO_LIMIT, name="planted slow producer (ratio)")
    assert planted.ratio("command") > clean.ratio("command")


def test_the_measure_is_a_callable_batch_of_three_rows(seam: _Seam, tmp_path: Path) -> None:
    """Floor: the measured command really is a multi-row batch (so the ratio bounds the one-spawn-per-row rule, not a single row)."""
    project = make_project(tmp_path / "project", work_packages=("WP01",))
    seam.use_producer()
    sample = _timed(_batch(project, "WP01"))
    assert sample.ok, sample.detail
    assert seam.spawns == len(_EDGES) >= 2
    assert len(project.publish_lines()) == len(_EDGES)
