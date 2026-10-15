"""The whole mission end to end against the REAL native producer (mission event-producer-reroute, WP10 T065, T066, T067, T069).

Every test runs the real CLI in a child interpreter against the real binary (the platform wheel the ``native-e2e`` job installs, or a
developer's Gradle build), in a scratch repository, on a default install: no setting, drain off, every proxy pointed at a closed port.
The files are collected by directory in lanes that build no binary, so the module is skipped there unless ``REQUIRE_NATIVE_PRODUCER=1``
(only ``native-e2e``), where a missing binary is a failure (``tests/integration/_native_binary.py``).

Covers AC-001, AC-002, AC-005 (the unresolvable root), AC-015, AC-016, AC-017, AC-018, AC-025, AC-026, AC-034, AC-035, NFR-001, NFR-003,
NFR-004, NFR-006, FR-038, FR-043.
"""

from __future__ import annotations

import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path
from typing import Any

import pytest

from tests.integration._native_binary import (
    MISSION_ID,
    PROJECT_UUID,
    SLUG,
    Project,
    emit_args,
    git,
    make_project,
    require_native_binary,
    run_cli,
    schema_errors,
)

pytestmark = [pytest.mark.integration, pytest.mark.git_repo, pytest.mark.real_event_producer]

REPO_ROOT = Path(__file__).resolve().parents[2]
_ULID = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")


@pytest.fixture(scope="module", autouse=True)
def binary() -> Path:
    """Autouse: every test of this module needs the real binary, so without it the whole module skips (fails under the flag)."""
    return require_native_binary()


def _transition(binary: Path, project: Project, cwd: Path, wp_id: str, to_lane: str = "claimed") -> subprocess.CompletedProcess[str]:
    result = run_cli(binary, cwd, *emit_args(project.slug, wp_id, to_lane))
    assert result.returncode == 0, result.stdout + result.stderr
    return result


def _event_id(result: subprocess.CompletedProcess[str]) -> str:
    return str(json.loads(result.stdout)["event_id"])


# --- T065: a default install, per slot, per checkout -----------------------------------------------------------------


@pytest.mark.parametrize("where", ["primary", "lane", "coord", "mission-subdirectory"])
def test_a_lane_transition_appends_one_valid_line_whose_id_is_the_ledger_event_id(binary: Path, tmp_path: Path, where: str) -> None:
    """AC-001, AC-002, NFR-001: the status slot, fired from a primary checkout, a lane worktree, a coordination worktree and a Mission subdirectory."""
    project = make_project(tmp_path / "project")
    cwd = {
        "primary": project.root,
        "lane": project.worktrees["lane"],
        "coord": project.worktrees["coord"],
        "mission-subdirectory": project.mission_dir,
    }[where]
    result = _transition(binary, project, cwd, "WP01")

    lines = project.publish_lines()
    assert len(lines) == 1, f"{where}: {lines}"
    (line,) = lines
    assert line["id"] == _event_id(result), "the line's id is the ledger row's event_id"
    assert line["type"] == "ai.spec-kitty.lane.transitioned.v1"
    assert line["source"] == f"/spec-kitty/missions/{MISSION_ID}"
    assert line["missionid"] == MISSION_ID
    assert line["subject"] == "WP01"
    assert schema_errors(line) == []
    assert result.stderr == "", "a healthy run writes no warning"
    assert len(project.publish_logs()) == 1
    assert project.fault_rows() == []


def test_every_checkout_reaches_one_publish_log_with_a_strictly_increasing_clock(binary: Path, tmp_path: Path) -> None:
    project = make_project(tmp_path / "project")
    cwds = [project.root, project.worktrees["lane"], project.worktrees["coord"], project.mission_dir]
    ids = [_event_id(_transition(binary, project, cwd, wp_id)) for cwd, wp_id in zip(cwds, project.work_packages, strict=True)]

    assert len(project.publish_logs()) == 1, "a primary checkout and its worktrees share one publish log"
    lines = project.publish_lines()
    assert [line["id"] for line in lines] == ids
    clocks = [line["lamportclock"] for line in lines]
    assert clocks == sorted(set(clocks)), "the clock strictly increases"
    assert len({line["nodeid"] for line in lines}) == 1
    # No checkout wrote its own copy of the log.
    for worktree in project.worktrees.values():
        assert not (worktree / ".kittify" / "events" / "publish").exists()


def test_the_lifecycle_slot_publishes_the_project_initialized_row(binary: Path, tmp_path: Path) -> None:
    """The lifecycle slot, end to end: ``init`` hands exactly one row to the producer (the release smoke's command)."""
    root = tmp_path / "fresh"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    git(root, "commit", "-q", "--allow-empty", "-m", "init")
    result = run_cli(binary, root, "init", ".", "--ai", "claude", "--non-interactive")
    assert result.returncode == 0, result.stdout[-1500:] + result.stderr[-1500:]

    logs = sorted((root / ".kittify" / "events" / "publish").glob("*.jsonl"))
    assert len(logs) == 1 and logs[0].name.startswith("project-")
    lines = [json.loads(text) for text in logs[0].read_text(encoding="utf-8").splitlines()]
    assert len(lines) == 1, "exactly one row, so the smoke's one-line check holds"
    (line,) = lines
    ledger = [json.loads(text) for text in (root / ".kittify" / "canonical-events.jsonl").read_text(encoding="utf-8").splitlines()]
    assert line["id"] in {row["event_id"] for row in ledger}
    assert line["type"] == "ai.spec-kitty.project.initialized.v1"
    assert line["source"].startswith("/spec-kitty/projects/") and "missionid" not in line
    assert schema_errors(line) == []
    assert "binary-missing" not in result.stderr


def test_an_unresolvable_root_warns_is_rejected_and_writes_no_fault_log_row(
    binary: Path, tmp_path: Path, capfd: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """AC-005 (unresolvable root): the real producer refuses a start path under no repository; one stderr warning, no fault-log row."""
    from specify_cli.status import producer_client  # noqa: PLC0415

    ghost = tmp_path / "not-a-repository"
    ghost.mkdir()
    monkeypatch.setenv("GIT_CEILING_DIRECTORIES", str(tmp_path))
    monkeypatch.setattr(producer_client, "locate_binary", lambda: binary)
    row = {
        "actor": "codex",
        "at": "2026-10-15T06:00:00+00:00",
        "event_id": "01KE2E00000000000000000R01",
        "evidence": None,
        "execution_mode": "worktree",
        "force": False,
        "from_lane": "planned",
        "mission_id": MISSION_ID,
        "mission_slug": SLUG,
        "reason": None,
        "review_ref": None,
        "to_lane": "claimed",
        "wp_id": "WP01",
    }
    producer_client.ProducerClient().produce(row=row, slot="status", seam_args={}, start_path=ghost)

    captured = capfd.readouterr()
    warnings = [line for line in captured.err.splitlines() if line.strip()]
    assert len(warnings) == 1 and "rejected" in warnings[0], warnings
    assert not list(tmp_path.rglob("faults/*.jsonl")), "an unresolvable root has nowhere to keep a fault log"
    assert not list(tmp_path.rglob("publish/**/*.jsonl"))


def test_the_orchestrator_api_contract_and_json_snapshots_are_unchanged() -> None:
    """AC-018: the producer touches no envelope; the existing contract tests and ``--json`` snapshots pass as they stand."""
    files = ["tests/contract/test_orchestrator_api.py", "tests/contract/test_events_envelope_matches_resolved_version.py"]
    for name in files:
        assert (REPO_ROOT / name).is_file(), name
    completed = subprocess.run(
        [sys.executable, "-m", "pytest", *files, "-q", "-p", "no:cacheprovider", "-o", "addopts="],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
        timeout=900,
    )
    assert completed.returncode == 0, completed.stdout[-2500:]
    assert " passed" in completed.stdout.strip().splitlines()[-1]


# --- T066: compatibility and the freeze --------------------------------------------------------------------------------


def _archived_ledgers() -> dict[Path, bytes]:
    return {path: path.read_bytes() for path in sorted((REPO_ROOT / "kitty-specs").glob("*/status.events.jsonl"))}


def _read_with_cli_readers(paths: list[Path]) -> int:
    from specify_cli.status.store import read_events  # noqa: PLC0415

    return sum(len(read_events(path.parent)) for path in paths)


def ledgers_unchanged_after(read: Any, ledgers: dict[Path, bytes]) -> bool:
    """Run ``read`` over the ledger paths, then say whether every file still holds exactly its earlier bytes."""
    read(list(ledgers))
    return {path: path.read_bytes() for path in ledgers} == ledgers


def test_the_cli_readers_leave_every_archived_ledger_byte_identical() -> None:
    """AC-015: floor of at least one ledger read; the mutation below plants a reader that rewrites one byte."""
    before = _archived_ledgers()
    assert before, "floor: at least one archived Mission ledger"
    reads: list[int] = []

    def reader(paths: list[Path]) -> None:
        reads.append(_read_with_cli_readers(paths))

    assert ledgers_unchanged_after(reader, before)
    assert reads and reads[0] > 0, "floor: at least one event was read"


def test_mutation_a_reader_that_rewrites_one_byte_is_detected(tmp_path: Path) -> None:
    ledger = tmp_path / "status.events.jsonl"
    ledger.write_bytes(b'{"a": 1}\n')

    def rewriting_reader(paths: list[Path]) -> None:
        for path in paths:
            path.write_bytes(path.read_bytes().replace(b"1", b"2"))

    assert not ledgers_unchanged_after(rewriting_reader, {ledger: ledger.read_bytes()})


def test_the_producer_changes_no_kitty_specs_file_and_creates_none(binary: Path, tmp_path: Path) -> None:
    """AC-016: the producer alone (no Python writer in play) on a scratch repository leaves ``kitty-specs/`` as it found it."""
    project = make_project(tmp_path / "project")
    request = {
        "v": 1,
        "op": "produce",
        "deadline_ms": 20000,
        "start_path": ".",
        "slot": "status",
        "seam_args": {},
        "row": {
            "actor": "codex",
            "at": "2026-10-15T06:00:00+00:00",
            "event_id": "01KE2E00000000000000000R02",
            "evidence": None,
            "execution_mode": "worktree",
            "force": False,
            "from_lane": "planned",
            "mission_id": MISSION_ID,
            "mission_slug": SLUG,
            "reason": None,
            "review_ref": None,
            "to_lane": "claimed",
            "wp_id": "WP01",
        },
    }
    before = project.archived_files()
    status_before = git(project.root, "status", "--porcelain", "--", "kitty-specs")
    completed = subprocess.run([str(binary)], input=json.dumps(request), cwd=project.root, capture_output=True, text=True, check=False, timeout=60)
    assert completed.returncode == 0, completed.stderr
    assert json.loads(completed.stdout)["outcome"] == "produced"
    assert len(project.publish_lines()) == 1
    assert project.archived_files() == before, "no kitty-specs file changed and none was created"
    assert git(project.root, "status", "--porcelain", "--", "kitty-specs") == status_before


def test_a_mission_open_before_the_upgrade_keeps_its_ledger_and_its_log_starts_at_the_first_new_event(binary: Path, tmp_path: Path) -> None:
    """AC-017 (mid-flight): the seeded rows were written with no producer; nothing is backfilled, nothing is rewritten."""
    project = make_project(tmp_path / "project")
    ledger_path = project.mission_dir / "status.events.jsonl"
    seeded = ledger_path.read_bytes()
    seeded_ids = {row["event_id"] for row in project.ledger()}
    assert seeded_ids and not project.publish_logs(), "premise: rows exist and the producer has published nothing"

    new_id = _event_id(_transition(binary, project, project.root, "WP01"))

    assert ledger_path.read_bytes().startswith(seeded), "the pre-upgrade ledger bytes are unchanged; the new row is appended"
    lines = project.publish_lines()
    assert [line["id"] for line in lines] == [new_id], "the publish log starts at the first post-upgrade event"
    assert not seeded_ids & {line["id"] for line in lines}


def test_a_mission_without_a_mission_id_publishes_project_scoped_and_changes_nothing_else(binary: Path, tmp_path: Path) -> None:
    """AC-017 / AC-005 / OD-MISSIONID (b): no ``mission_id`` is no fault. Compared with the same command and no producer at all."""
    absent = make_project(tmp_path / "absent", mission_id=None)
    present = make_project(tmp_path / "present", mission_id=None)

    without = run_cli(None, absent.root, *emit_args(absent.slug, "WP01", "claimed"))
    with_producer = run_cli(binary, present.root, *emit_args(present.slug, "WP01", "claimed"))

    assert with_producer.stderr == "", "no warning"
    assert present.fault_rows() == [], "no fault-log row"
    assert with_producer.returncode == without.returncode == 0

    def body(result: subprocess.CompletedProcess[str], project: Project) -> dict[str, Any]:
        parsed = json.loads(result.stdout)
        parsed.pop("event_id")
        parsed["status_events_path"] = parsed["status_events_path"].replace(str(project.root), "<root>")
        return parsed

    assert body(with_producer, present) == body(without, absent), "a byte-for-byte equal --json body once the per-run values are normalised"
    (line,) = present.publish_lines()
    assert line["source"] == f"/spec-kitty/projects/{PROJECT_UUID}"
    assert line["subject"] == "WP01"
    assert "missionid" not in line
    assert schema_errors(line) == []


def test_a_torn_publish_log_line_stays_flagged_and_the_new_line_is_intact(binary: Path, tmp_path: Path) -> None:
    """AC-026: truncate the log mid-line, append; the torn fragment stays, the new line is whole, one stderr line, one notice row."""
    project = make_project(tmp_path / "project")
    _transition(binary, project, project.root, "WP01")
    (log,) = project.publish_logs()
    original = log.read_bytes()
    log.write_bytes(original[:-15])

    appended = _transition(binary, project, project.root, "WP02")
    second = _event_id(appended)
    stderr_lines = [line for line in appended.stderr.splitlines() if line.strip()]
    assert len(stderr_lines) == 1 and "torn line" in stderr_lines[0], stderr_lines

    text = log.read_bytes().decode("utf-8")
    fragment, intact = text.split("\n")[0], text.split("\n")[1]
    assert fragment == original[:-15].decode("utf-8"), "the torn fragment is left in place"
    assert json.loads(intact)["id"] == second and schema_errors(json.loads(intact)) == []
    rows = project.fault_rows()
    assert [row["notice"] for row in rows] == ["torn-line"] and rows[0]["kind"] == "notice"
    assert rows[0]["path"] == log.relative_to(project.root).as_posix() and rows[0]["line"] == 1


# --- T067: binary-missing, clean virtual environments ---------------------------------------------------------------


def _clean_venv(tmp_path: Path, name: str) -> Path:
    venv = tmp_path / name
    subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True, capture_output=True)
    return venv / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")


def _pip(python: Path, *args: str) -> None:
    completed = subprocess.run(
        [str(python), "-m", "pip", "install", "--quiet", "--disable-pip-version-check", *args], capture_output=True, text=True, check=False, timeout=1200
    )
    assert completed.returncode == 0, completed.stderr[-2500:]


def _init_in_venv(python: Path, tmp_path: Path, name: str) -> subprocess.CompletedProcess[str]:
    repo = tmp_path / name
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "commit", "-q", "--allow-empty", "-m", "init")
    env = {key: value for key, value in os.environ.items() if key != "PYTHONPATH"}
    env.update({"SPEC_KITTY_ENABLE_SAAS_SYNC": "0"})
    return subprocess.run(
        [str(python), "-m", "specify_cli", "init", ".", "--ai", "claude", "--non-interactive"],
        cwd=repo,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=600,
    )


def _assert_binary_missing_but_working(result: subprocess.CompletedProcess[str]) -> None:
    assert result.returncode == 0, result.stdout[-1500:] + result.stderr[-1500:]
    assert "binary-missing" in result.stderr, result.stderr[-1500:]
    assert "NOT produced" in result.stderr


def test_a_pure_wheel_install_degrades_with_binary_missing(binary: Path, build_artifacts: dict[str, Path], tmp_path: Path) -> None:
    """AC-025: the ``py3-none-any`` wheel carries no binary; a lifecycle command succeeds and warns."""
    python = _clean_venv(tmp_path, "pure")
    _pip(python, str(build_artifacts["wheel"]))
    _assert_binary_missing_but_working(_init_in_venv(python, tmp_path, "pure-repo"))


def test_an_sdist_install_degrades_with_binary_missing(binary: Path, build_artifacts: dict[str, Path], tmp_path: Path) -> None:
    """AC-025 / C-007: building from the sdist never needs the binary and the result degrades."""
    with tarfile.open(build_artifacts["sdist"]) as archive:
        names = archive.getnames()
    assert not any("/_native/" in name for name in names), "the sdist ships no binary"
    python = _clean_venv(tmp_path, "sdist")
    _pip(python, str(build_artifacts["sdist"]))
    _assert_binary_missing_but_working(_init_in_venv(python, tmp_path, "sdist-repo"))


def test_with_no_matching_platform_wheel_the_pure_wheel_is_chosen_and_degrades(binary: Path, build_artifacts: dict[str, Path], tmp_path: Path) -> None:
    """AC-025: only a foreign-platform wheel and the pure wheel are offered; pip takes the pure one."""
    from tests._support.shared_build_artifacts import default_platform_wheel_builder  # noqa: PLC0415

    foreign_leg = "windows-x86_64" if sys.platform != "win32" else "linux-x86_64"
    index = tmp_path / "index"
    index.mkdir()
    default_platform_wheel_builder(index, leg=foreign_leg)
    shutil.copy2(build_artifacts["wheel"], index)
    foreign = [p.name for p in index.glob("*.whl") if "none-any" not in p.name]
    assert len(foreign) == 1, "premise: one foreign-tagged wheel beside the pure wheel"
    python = _clean_venv(tmp_path, "nomatch")
    version = build_artifacts["wheel"].name.split("-")[1]
    _pip(python, "--find-links", str(index), f"spec-kitty-cli=={version}")
    _assert_binary_missing_but_working(_init_in_venv(python, tmp_path, "nomatch-repo"))


def test_a_source_tree_run_has_no_binary_and_still_works(binary: Path, tmp_path: Path) -> None:
    """AC-038 (runtime half): the editable/source layout (``src`` on the path, no ``_native`` directory) works and degrades."""
    repo = tmp_path / "src-repo"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "commit", "-q", "--allow-empty", "-m", "init")
    env = dict(os.environ)
    env["PYTHONPATH"] = str(REPO_ROOT / "src")
    probe = subprocess.run(
        [sys.executable, "-c", "from specify_cli.status.producer_client import locate_binary; print(locate_binary())"],
        cwd=repo,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    if probe.stdout.strip() != "None":
        pytest.skip("this checkout has a binary planted under src/specify_cli/_native; the source-tree degrade is not observable")
    result = subprocess.run(
        [sys.executable, "-m", "specify_cli", "init", ".", "--ai", "claude", "--non-interactive"],
        cwd=repo,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=600,
    )
    _assert_binary_missing_but_working(result)


# --- T069: determinism and hygiene -----------------------------------------------------------------------------------

_GOLDEN_ROW = {
    "actor": "codex",
    "at": "2026-10-13T10:00:00+02:00",
    "event_id": "01JAAAA0000000000000000ABC",
    "evidence": None,
    "execution_mode": "worktree",
    "force": False,
    "from_lane": "planned",
    "mission_id": MISSION_ID,
    "mission_slug": SLUG,
    "reason": 'tab\there "quoted" back\\slash /slash \U0001f600 \u2028 \u007f',
    "review_ref": None,
    "to_lane": "claimed",
    "wp_id": "WP01",
}


def _produce_directly(binary: Path, root: Path) -> bytes:
    """Hand the golden row to the real binary in a fresh repository and return the publish-log bytes."""
    request = {"v": 1, "op": "produce", "deadline_ms": 20000, "start_path": ".", "slot": "status", "seam_args": {}, "row": _GOLDEN_ROW}
    completed = subprocess.run([str(binary)], input=json.dumps(request), cwd=root, capture_output=True, text=True, check=False, timeout=60)
    assert completed.returncode == 0 and json.loads(completed.stdout)["outcome"] == "produced", completed.stdout + completed.stderr
    (log,) = (root / ".kittify" / "events" / "publish").rglob("*.jsonl")
    return log.read_bytes()


def _fresh_repo(root: Path) -> Path:
    root.mkdir(parents=True)
    git(root, "init", "-q", "-b", "main")
    (root / ".kittify").mkdir()
    (root / ".kittify" / "config.yaml").write_text(f"project:\n  uuid: {PROJECT_UUID}\n", encoding="utf-8")
    return root


def test_the_same_row_clock_and_node_give_byte_identical_canonical_lines(binary: Path, tmp_path: Path) -> None:
    """AC-034 / NFR-003: two fresh repositories, one row: the same bytes; the bytes are the canonical serialisation, LF only, no BOM."""
    first = _produce_directly(binary, _fresh_repo(tmp_path / "one"))
    second = _produce_directly(binary, _fresh_repo(tmp_path / "two"))

    assert first == second
    assert first.endswith(b"\n") and first.count(b"\n") == 1 and b"\r" not in first
    assert not first.startswith(b"\xef\xbb\xbf"), "no BOM"
    text = first.decode("utf-8")
    assert text.isascii(), "the serialiser escapes every non-ASCII character"
    parsed = json.loads(text)
    assert json.dumps(parsed, sort_keys=True, separators=(",", ":")) + "\n" == text, "sorted members, no padding: the canonical form"
    assert parsed["lamportclock"] == 1
    assert parsed["time"] == "2026-10-13T10:00:00+02:00", "the row's own instant, verbatim"
    assert parsed["data"]["reason"] == _GOLDEN_ROW["reason"], "an awkward string round-trips exactly"
    assert schema_errors(parsed) == []


def hygiene_leaks(text: str, root: Path) -> list[str]:
    """Tokens in ``text`` that must not appear in anything the producer authors: home paths, the user name, the absolute repository root."""
    forbidden = [str(Path.home()), getpass.getuser(), "/home/", "/Users/", "C:\\\\Users", "C:/Users", str(root)]
    return [token for token in forbidden if token and token in text]


def test_no_home_path_or_username_in_anything_the_producer_wrote(binary: Path, tmp_path: Path) -> None:
    """AC-035 / NFR-006: publish-log lines and fault records hold no absolute home path and no user name."""
    project = make_project(tmp_path / "project")
    _transition(binary, project, project.root, "WP01")
    log = project.publish_logs()[0]
    log.write_bytes(log.read_bytes()[:-15])
    _transition(binary, project, project.root, "WP02")
    run_cli(None, project.root, *emit_args(project.slug, "WP03", "claimed"))

    written = [path.read_text(encoding="utf-8") for path in project.publish_logs()]
    written += [path.read_text(encoding="utf-8") for path in (project.root / ".kittify" / "events" / "faults").glob("*.jsonl")]
    assert len(written) >= 2 and all(written), "floor: a publish log and a fault log were scanned"
    for text in written:
        assert hygiene_leaks(text, project.root) == [], "a producer-written file leaks a path or a user name"


def test_mutation_the_scan_finds_a_planted_home_path_user_name_and_root() -> None:
    root = Path("/scratch/root")
    assert hygiene_leaks('{"path":"' + str(Path.home()) + '/x"}', root)
    assert hygiene_leaks('{"actor":"' + getpass.getuser() + '"}', root)
    assert hygiene_leaks('{"path":"/scratch/root/kitty-specs"}', root)
    assert hygiene_leaks('{"path":"/home/someone/x"}', root)
    assert hygiene_leaks('{"path":".kittify/events/publish/x.jsonl"}', root) == []
