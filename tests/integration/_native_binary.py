"""Locating the real native event producer for the real-binary tests, and the harness they share (mission event-producer-reroute, WP10 T083).

Two parts.

**The binary and its fail-on-missing switch.** :func:`require_native_binary` returns the native producer or ends the test:

* ``REQUIRE_NATIVE_PRODUCER=1`` (set only by the ``native-e2e`` job of ``event-producer.yml``): a missing or unrunnable binary is a
  failure, never a skip;
* any other state of that variable, ``CI`` included (``CI`` is deliberately not consulted): the test skips with a message naming
  the flag. ``tests/integration`` and ``tests/perf`` are also collected by directory in the nightly ``integration-next`` job and the
  ``integration-slice`` lane, which never build the binary.

Where it looks, in order: the path in ``NATIVE_PRODUCER_BINARY`` (authoritative: a path that is not a file means "absent", with no
fallback; the guard tests use it to simulate a machine without a binary), the binary bundled in the installed ``specify_cli`` package
as a fresh interpreter in a scratch directory sees it (the platform wheel the ``native-e2e`` job installs), then the local Gradle
build output (a developer's checkout).

**The harness.** A scratch Mission repository, a CLI run in a child interpreter against the binary, and the released-schema check of
a publish-log line. The child is how the tests prove the default install: with the wheel installed the child runs
``python -m specify_cli`` and finds the bundled binary by itself; without it (a checkout) a one-line launcher points the client's
``locate_binary`` at the binary under test, and nothing else differs. Python stays glue here: every producer rule is the producer's.
"""

from __future__ import annotations

import functools
import json
import os
import subprocess
import sys
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest

__all__ = [
    "FLAG",
    "OVERRIDE",
    "MISSION_ID",
    "SLUG",
    "Project",
    "cli_argv",
    "locate_native_binary",
    "make_project",
    "require_native_binary",
    "run_cli",
    "schema_errors",
]

FLAG = "REQUIRE_NATIVE_PRODUCER"
OVERRIDE = "NATIVE_PRODUCER_BINARY"

_REPO_ROOT = Path(__file__).resolve().parents[2]
_BINARY_NAME = "spec-kitty-event-producer"
MISSION_ID = "01KE2E0000000000000000MSN1"
SLUG = "017-e2e-producer"

#: The child's entry when the binary is not the installed wheel's own: it points the client's ``locate_binary`` at ``argv[1]`` (a
#: path, or ``-`` for none) and runs the real CLI. ``stub`` (``argv[2]``, a script path or ``-``) makes the client spawn
#: ``python <stub>`` instead of the binary, so a crash, slow or malformed producer is portable to every OS. ``sys.argv[0]`` is set
#: so the CLI parser does not read ``-c`` as an option.
_LAUNCHER = (
    "import sys, types, subprocess\n"
    "from pathlib import Path\n"
    "from specify_cli.status import producer_client as pc\n"
    "binary, stub = sys.argv[1], sys.argv[2]\n"
    "del sys.argv[1:3]\n"
    "sys.argv[0] = 'spec-kitty'\n"
    "pc.locate_binary = (lambda: None) if binary == '-' else (lambda: Path(binary))\n"
    "if stub != '-':\n"
    "    real = subprocess.Popen\n"
    "    pc.subprocess = types.SimpleNamespace(\n"
    "        Popen=lambda argv, *a, **k: real([sys.executable, stub, *argv[1:]], *a, **k),\n"
    "        PIPE=subprocess.PIPE, DEVNULL=subprocess.DEVNULL, TimeoutExpired=subprocess.TimeoutExpired)\n"
    "from specify_cli import main\n"
    "main()\n"
)

_BUNDLED_PROBE = "from specify_cli.status.producer_client import locate_binary; b = locate_binary(); print(b if b else '')"


def _binary_name() -> str:
    return f"{_BINARY_NAME}.exe" if sys.platform == "win32" else _BINARY_NAME


def _scratch_environment() -> dict[str, str]:
    """The child's environment: no checkout directory on the import path, so an installed wheel (not the source tree) is what it imports."""
    env = {key: value for key, value in os.environ.items() if key != "PYTHONPATH"}
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


@functools.cache
def _bundled_binary() -> Path | None:
    """The binary the default install bundles, as a fresh interpreter in a scratch directory finds it; ``None`` for a checkout."""
    with tempfile.TemporaryDirectory(prefix="producer-probe-") as scratch:
        completed = subprocess.run(
            [sys.executable, "-c", _BUNDLED_PROBE],
            cwd=scratch,
            env=_scratch_environment(),
            capture_output=True,
            text=True,
            check=False,
            timeout=120,
        )
    found = completed.stdout.strip()
    return Path(found) if completed.returncode == 0 and found else None


def _gradle_output() -> Path:
    return _REPO_ROOT / "services" / "event-producer" / "launcher" / "build" / "native" / "nativeCompile" / _binary_name()


def locate_native_binary() -> Path | None:
    """The native producer on this machine, or ``None``. See the module docstring for the order."""
    override = os.environ.get(OVERRIDE)
    if override:
        candidate = Path(override)
        return candidate if candidate.is_file() else None
    bundled = _bundled_binary()
    if bundled is not None and bundled.is_file():
        return bundled
    built = _gradle_output()
    return built if built.is_file() else None


def _runnable(binary: Path) -> bool:
    return sys.platform == "win32" or os.access(binary, os.X_OK)


def require_native_binary() -> Path:
    """The runnable native producer, else a failure under ``REQUIRE_NATIVE_PRODUCER=1`` and a skip otherwise."""
    binary = locate_native_binary()
    if binary is not None and _runnable(binary):
        return binary
    state = "is not runnable" if binary is not None else "was not found (no bundled binary, no Gradle build output, no override)"
    if os.environ.get(FLAG) == "1":
        pytest.fail(f"the native event producer {state}; {FLAG}=1 makes that a failure (the native-e2e job must build and install it)", pytrace=False)
    pytest.skip(f"the native event producer {state}; set {FLAG}=1 to make this a failure (only the native-e2e job does)")


def cli_argv(binary: Path | None, *args: str, stub: Path | None = None) -> list[str]:
    """The child command line: the plain CLI when ``binary`` is the install's own, else the launcher pointing the client at it.

    ``binary=None`` is a client with no binary at all; ``stub`` replaces the spawned process with ``python <stub>``.
    """
    if binary is not None and stub is None and binary == _bundled_binary():
        return [sys.executable, "-m", "specify_cli", *args]
    return [sys.executable, "-c", _LAUNCHER, "-" if binary is None else str(binary), "-" if stub is None else str(stub), *args]


def run_cli(
    binary: Path | None,
    cwd: Path,
    *args: str,
    stub: Path | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float = 300.0,
) -> subprocess.CompletedProcess[str]:
    """Run the CLI once in ``cwd``; the network is denied by pointing every proxy at a closed port (a hosted attempt would fail loudly)."""
    child = _scratch_environment()
    child.update(
        {
            "SPEC_KITTY_ENABLE_SAAS_SYNC": "0",
            "HTTP_PROXY": "http://127.0.0.1:9",
            "HTTPS_PROXY": "http://127.0.0.1:9",
            "NO_PROXY": "",
            "GIT_AUTHOR_NAME": "e2e",
            "GIT_AUTHOR_EMAIL": "e2e@example.invalid",
            "GIT_COMMITTER_NAME": "e2e",
            "GIT_COMMITTER_EMAIL": "e2e@example.invalid",
        }
    )
    child.update(env or {})
    return subprocess.run(cli_argv(binary, *args, stub=stub), cwd=cwd, env=child, capture_output=True, text=True, check=False, timeout=timeout)


def git(cwd: Path, *args: str) -> str:
    """One git command in ``cwd`` with a throwaway identity; returns stdout."""
    completed = subprocess.run(
        ["git", "-c", "user.name=e2e", "-c", "user.email=e2e@example.invalid", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
    )
    return completed.stdout.strip()


@dataclass
class Project:
    """A scratch repository holding one Mission with work packages seeded at ``planned``."""

    root: Path
    slug: str = SLUG
    mission_id: str | None = MISSION_ID
    work_packages: tuple[str, ...] = ("WP01", "WP02", "WP03", "WP04")
    worktrees: dict[str, Path] = field(default_factory=dict)

    @property
    def mission_dir(self) -> Path:
        return self.root / "kitty-specs" / self.slug

    @property
    def publish_dir(self) -> Path:
        return self.root / ".kittify" / "events" / "publish"

    def publish_logs(self) -> list[Path]:
        return sorted(self.publish_dir.rglob("*.jsonl")) if self.publish_dir.is_dir() else []

    def publish_lines(self) -> list[dict[str, Any]]:
        return [json.loads(line) for log in self.publish_logs() for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]

    def fault_rows(self) -> list[dict[str, Any]]:
        faults = self.root / ".kittify" / "events" / "faults"
        return (
            [json.loads(line) for log in sorted(faults.glob("*.jsonl")) for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]
            if faults.is_dir()
            else []
        )

    def ledger(self, checkout: Path | None = None) -> list[dict[str, Any]]:
        """The Mission's ``status.events.jsonl`` as the given checkout holds it (the primary one by default)."""
        path = (checkout or self.root) / "kitty-specs" / self.slug / "status.events.jsonl"
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    def archived_files(self) -> dict[str, bytes]:
        """Every tracked file under ``kitty-specs/`` with its bytes: what the producer must never touch."""
        return {path.relative_to(self.root).as_posix(): path.read_bytes() for path in sorted((self.root / "kitty-specs").rglob("*")) if path.is_file()}


def make_project(root: Path, *, slug: str = SLUG, mission_id: str | None = MISSION_ID, work_packages: Sequence[str] = ("WP01", "WP02", "WP03", "WP04")) -> Project:
    """A committed repository with a Mission, a lane worktree and a coordination worktree.

    The ledger is seeded straight to ``planned`` (as ``finalize-tasks`` does), so the seed is not an event the producer saw: the
    publish log starts at the first command the test runs.
    """
    from tests.status.conftest import seed_wp_to_planned  # noqa: PLC0415 - a heavy import, needed only when a project is built

    root.mkdir(parents=True, exist_ok=True)
    git(root, "init", "-q", "-b", "main")
    (root / ".kittify").mkdir()
    (root / ".kittify" / "config.yaml").write_text("# e2e scratch project\n", encoding="utf-8")
    (root / ".gitignore").write_text(".kittify/events/\n.kittify/derived/\n.worktrees/\n", encoding="utf-8")
    feature = root / "kitty-specs" / slug
    feature.mkdir(parents=True)
    meta: dict[str, Any] = {"mission_slug": slug}
    if mission_id is not None:
        meta.update({"mission_id": mission_id, "mid8": mission_id[:8]})
    (feature / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    for wp_id in work_packages:
        seed_wp_to_planned(feature, wp_id, slug=slug)
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "seed")
    project = Project(root=root, slug=slug, mission_id=mission_id, work_packages=tuple(work_packages))
    lane = root / ".worktrees" / f"{slug}-lane-a"
    coord = root / ".worktrees" / f"{slug}-coord"
    git(root, "worktree", "add", "-q", str(lane), "-b", "kitty/e2e-lane-a")
    git(root, "worktree", "add", "-q", str(coord), "-b", f"kitty/mission-{slug}")
    project.worktrees.update({"lane": lane, "coord": coord})
    return project


def emit_args(slug: str, wp_id: str, to_lane: str) -> list[str]:
    """The lane-transition command whose ``--json`` body the tests compare."""
    return ["agent", "status", "emit", wp_id, "--to", to_lane, "--actor", "codex", "--mission", slug, "--json"]


@functools.cache
def _registry_names() -> dict[str, str]:
    from spec_kitty_events.producer_contract import load_registry  # noqa: PLC0415

    return {entry["type"]: entry["name"] for entry in load_registry()["types"]}


def schema_errors(line: Mapping[str, Any]) -> list[str]:
    """Violations of the RELEASED ``spec_kitty_events`` row schema for the line's type; an unregistered type is itself a violation."""
    from jsonschema import Draft202012Validator  # noqa: PLC0415
    from spec_kitty_events.producer_contract import row_schema  # noqa: PLC0415

    name = _registry_names().get(str(line.get("type")))
    if name is None:
        return [f"type {line.get('type')!r} is not in the released registry"]
    validator = Draft202012Validator(row_schema(name))
    return [f"/{'/'.join(str(p) for p in error.absolute_path)}: {error.message}" for error in validator.iter_errors(dict(line))]
