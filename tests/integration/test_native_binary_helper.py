"""The fail-on-missing switch of the real-binary tests, and the two guards that keep it from leaking (mission event-producer-reroute, WP10 T083).

The real-binary tests (``test_event_producer_e2e.py``, ``test_event_producer_degraded.py`` and
``tests/perf/test_event_producer_overhead.py``) obtain the native producer through
:func:`tests.integration._native_binary.require_native_binary`. The ONLY switch that turns a missing or
unrunnable binary into a failure is ``REQUIRE_NATIVE_PRODUCER=1``, set by the ``native-e2e`` job of
``event-producer.yml`` and by nothing else. ``CI`` is not consulted: ``tests/integration`` and ``tests/perf`` are also
collected by directory in the nightly ``integration-next`` job and the ``integration-slice`` lane, neither of which builds the
binary, and a CI-wide fail-on-missing would turn both red every night.

Two guards pin the defect class: no file outside the allowlist calls ``require_native_binary()`` (a new binary-hungry test
elsewhere would be silently skipped or, worse, fail the nightly lanes), and a nightly-shaped collection of the three
real-binary files (flag unset, no binary) yields skips only.
"""

from __future__ import annotations

import ast
import os
import subprocess
import sys
from collections.abc import Iterable
from pathlib import Path

import pytest

from tests.integration import _native_binary
from tests.integration._native_binary import FLAG, OVERRIDE, require_native_binary

pytestmark = pytest.mark.fast

REPO_ROOT = Path(__file__).resolve().parents[2]
SCANNED_TREES = ("tests/integration", "tests/perf")
#: The three real-binary files plus this file, which only exercises the function under a patched environment.
ALLOWLIST = frozenset(
    {
        "tests/integration/test_event_producer_e2e.py",
        "tests/integration/test_event_producer_degraded.py",
        "tests/perf/test_event_producer_overhead.py",
        "tests/integration/test_native_binary_helper.py",
    }
)
REAL_BINARY_FILES = tuple(sorted(ALLOWLIST - {"tests/integration/test_native_binary_helper.py"}))
HELPER_FILE = "tests/integration/_native_binary.py"


@pytest.fixture
def no_binary(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """No native binary anywhere: the override names a path that does not exist (an authoritative 'absent')."""
    missing = tmp_path / "absent" / "spec-kitty-event-producer"
    monkeypatch.setenv(OVERRIDE, str(missing))
    return missing


@pytest.fixture
def a_binary(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """An executable file the override names: stands in for the binary (the helper never runs it)."""
    binary = tmp_path / "bin" / ("spec-kitty-event-producer.exe" if sys.platform == "win32" else "spec-kitty-event-producer")
    binary.parent.mkdir()
    binary.write_bytes(b"\x00")
    binary.chmod(0o755)
    monkeypatch.setenv(OVERRIDE, str(binary))
    return binary


def test_the_flag_and_the_override_have_their_documented_names() -> None:
    assert (FLAG, OVERRIDE) == ("REQUIRE_NATIVE_PRODUCER", "NATIVE_PRODUCER_BINARY")


def test_with_the_flag_set_a_missing_binary_is_a_failure_not_a_skip(no_binary: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(FLAG, "1")
    with pytest.raises(pytest.fail.Exception) as raised:
        require_native_binary()
    assert FLAG in str(raised.value)


def test_with_the_flag_unset_a_missing_binary_skips_and_the_message_names_the_flag(no_binary: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(FLAG, raising=False)
    with pytest.raises(pytest.skip.Exception) as raised:
        require_native_binary()
    assert FLAG in str(raised.value)


@pytest.mark.parametrize("value", ["", "0", "true", "yes", "2"])
def test_only_the_literal_one_turns_a_missing_binary_into_a_failure(no_binary: Path, monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv(FLAG, value)
    with pytest.raises(pytest.skip.Exception):
        require_native_binary()


def test_ci_is_not_consulted(no_binary: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(FLAG, raising=False)
    for ci in ("true", "1", "false", ""):
        monkeypatch.setenv("CI", ci)
        monkeypatch.setenv("GITHUB_ACTIONS", "true")
        with pytest.raises(pytest.skip.Exception):
            require_native_binary()


@pytest.mark.parametrize("flag", [None, "1"])
def test_a_present_binary_is_returned_whatever_the_flag(a_binary: Path, monkeypatch: pytest.MonkeyPatch, flag: str | None) -> None:
    if flag is None:
        monkeypatch.delenv(FLAG, raising=False)
    else:
        monkeypatch.setenv(FLAG, flag)
    assert require_native_binary() == a_binary


@pytest.mark.skipif(sys.platform == "win32", reason="the exec bit is a POSIX notion; a Windows binary is runnable by its suffix")
def test_an_unrunnable_binary_is_a_failure_under_the_flag_and_a_skip_without_it(a_binary: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    a_binary.chmod(0o644)
    monkeypatch.setenv(FLAG, "1")
    with pytest.raises(pytest.fail.Exception):
        require_native_binary()
    monkeypatch.delenv(FLAG, raising=False)
    with pytest.raises(pytest.skip.Exception):
        require_native_binary()


def test_the_override_is_authoritative_a_missing_path_does_not_fall_back_to_another_binary(no_binary: Path) -> None:
    assert _native_binary.locate_native_binary() is None


# --- guard 1: who may call require_native_binary() ------------------------------------------------------------------


def callers_of_the_helper(root: Path, trees: Iterable[str] = SCANNED_TREES) -> set[str]:
    """Repository-relative paths of the Python files under ``trees`` that CALL ``require_native_binary`` (an AST scan: a mention is no call)."""
    found: set[str] = set()
    for tree in trees:
        for path in sorted((root / tree).rglob("*.py")):
            relative = path.relative_to(root).as_posix()
            if relative == HELPER_FILE:
                continue
            module = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(module):
                if isinstance(node, ast.Call):
                    func = node.func
                    name = func.id if isinstance(func, ast.Name) else func.attr if isinstance(func, ast.Attribute) else ""
                    if name == "require_native_binary":
                        found.add(relative)
    return found


def test_only_the_allowlisted_files_call_the_helper() -> None:
    callers = callers_of_the_helper(REPO_ROOT)
    assert callers <= ALLOWLIST, f"files that need a native binary outside the native-e2e allowlist: {sorted(callers - ALLOWLIST)}"


def test_every_allowlisted_file_exists_and_calls_the_helper() -> None:
    """The floor: the scan sees all four allowlisted files, so an emptied or renamed file cannot pass vacuously."""
    assert callers_of_the_helper(REPO_ROOT) == ALLOWLIST


def test_mutation_a_planted_caller_outside_the_allowlist_is_found(tmp_path: Path) -> None:
    (tmp_path / "tests" / "integration").mkdir(parents=True)
    (tmp_path / "tests" / "perf").mkdir(parents=True)
    (tmp_path / "tests" / "integration" / "test_planted.py").write_text(
        "from tests.integration._native_binary import require_native_binary\n\n\ndef test_x():\n    require_native_binary()\n", encoding="utf-8"
    )
    (tmp_path / "tests" / "perf" / "test_docstring_only.py").write_text('"""require_native_binary() is only mentioned here."""\n', encoding="utf-8")
    assert callers_of_the_helper(tmp_path) == {"tests/integration/test_planted.py"}


# --- guard 2: the nightly-shaped collection --------------------------------------------------------------------------


def test_a_nightly_shaped_collection_of_the_real_binary_files_only_skips(tmp_path: Path) -> None:
    """Flag unset and no binary, as in ``integration-next`` and ``integration-slice``: skips only, no failure, no error, no pass."""
    for relative in REAL_BINARY_FILES:
        assert (REPO_ROOT / relative).is_file(), f"{relative} does not exist yet"
    env = {key: value for key, value in os.environ.items() if key != FLAG}
    env[OVERRIDE] = str(tmp_path / "absent" / "spec-kitty-event-producer")
    completed = subprocess.run(
        [sys.executable, "-m", "pytest", *REAL_BINARY_FILES, "-q", "-p", "no:cacheprovider", "-rs", "-o", "addopts="],
        cwd=REPO_ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=600,
    )
    tail = completed.stdout[-2000:]
    assert completed.returncode == 0, tail
    summary = completed.stdout.strip().splitlines()[-1]
    assert " skipped" in summary, summary
    for forbidden in ("passed", "failed", "error"):
        assert forbidden not in summary, summary
    assert FLAG in completed.stdout, "the skip message must name the flag"
