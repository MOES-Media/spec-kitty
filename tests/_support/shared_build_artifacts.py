"""Run-scoped sharing of the session wheel/sdist build across xdist workers.

Session-scoped fixtures execute once per *worker process*, so before #80 every
xdist worker collecting a ``distribution`` test ran its own ``python -m build``
into its own temp tree — up to sixteen concurrent builds on the CI runner,
each spending a minute of CPU and leaving its own scratch tree behind at
exactly the moment ``tests/e2e``'s retained tmp_path trees are pushing the
runner's free disk toward zero (the #80 crash mechanism).

This module gives those fixtures one build per RUN instead of one build per
worker. The shared location is derived from pytest's own basetemp, which makes
it automatically scoped to a single run:

* under xdist each worker's basetemp is ``<run>/popen-gwN``, so ``<run>`` —
  the parent — is shared by exactly this run's workers and touched by no
  other run;
* serially the basetemp itself is already run-scoped, and a serial session
  builds once anyway;
* two concurrent pytest processes get distinct basetemps, so their caches
  never meet (they merely each build, as before).

A ``kernel.locks`` machine lock serialises the first build; later workers validate what was
published and reuse it without building. Publication is atomic — the builder
fills a staging directory, then renames it into place while holding the lock —
and a crashed builder's staging directory is swept by the next claimant, so an
interrupted run cannot poison later ones.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import TypeVar

import pytest

from kernel.locks import LockAcquireTimeout, machine_file_lock

_REPO_ROOT = Path(__file__).resolve().parents[2]

_SHARED_BUILD_DIR_NAME = "shared-build-artifacts"
_STAGING_GLOB = _SHARED_BUILD_DIR_NAME + ".staging-*"

_SOURCE_SNAPSHOT_DIR_NAME = "source-snapshot"
_SOURCE_SNAPSHOT_STAGING_GLOB = _SOURCE_SNAPSHOT_DIR_NAME + ".staging-*"
#: A local clone of this repository takes seconds; a few attempts ride out
#: short collisions with whatever else touches the source's git store at
#: session start without giving up the whole file.
_SNAPSHOT_ATTEMPTS = 3
_SNAPSHOT_RETRY_DELAY_S = 2.0
#: One full ``python -m build`` takes on the order of a minute; every other
#: worker of the run queues behind that single holder, hence the generous bound.
_LOCK_TIMEOUT_S = 1200.0

_T = TypeVar("_T")


def default_wheel_sdist_builder(outdir: Path) -> None:
    """Build this repository's wheel + sdist into ``outdir`` (#80's real builder).

    Lives beside the sharing protocol so the session ``build_artifacts``
    fixture in ``tests/conftest.py`` stays a thin shell with no nested
    definitions (the conftest definition-order guard pins that file's
    definition names).

    Runs with ``--no-isolation``: ``build`` and ``hatchling`` are both pinned
    in the ``test`` extra for exactly this, so a synced venv already has
    everything the build needs and the build step itself never reaches out
    to the network for an isolated build environment (FR-004 — the only
    remaining failure mode for *this step* is a genuine product/packaging
    defect, not a transient network condition). This claim is scoped to the
    build: the sibling ``installed_wheel_venv`` fixture (``tests/conftest.py``)
    still runs ``pip install`` against the wheel this produces, which does
    resolve from the index, so an offline run fails there instead.
    """
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "build",
            "--no-isolation",
            "--wheel",
            "--sdist",
            "--outdir",
            str(outdir),
        ],
        cwd=_REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SharedBuildError(f"Build failed: {result.stderr}")


class SharedBuildError(RuntimeError):
    """The wheel/sdist build itself failed; callers fail loudly with this error (FR-004)."""


def run_scoped_shared_root(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Return the temp directory shared by every xdist worker of this run.

    Workers nest their basetemp under the run's own basetemp (``popen-gwN``),
    so the parent of a worker's basetemp is this run's shared root. A serial
    session has no nesting, and its basetemp is already private to the run.
    """
    base = tmp_path_factory.getbasetemp()
    if os.environ.get("PYTEST_XDIST_WORKER"):
        return base.parent
    return base


_DIST_SET_PATH = _REPO_ROOT / "scripts" / "release" / "dist-set.json"
_PLATFORM_WHEEL_DIR_PREFIX = "shared-platform-wheel-"
_HOOK_ENABLE_ENV = "HATCH_BUILD_HOOK_ENABLE_CUSTOM"
_HOOK_ENV_PREFIXES = ("HATCH_BUILD_HOOK_ENABLE", "SPEC_KITTY_PRODUCER_")
_BUILD_INPUT_FILES = ("pyproject.toml", "README.md", "LICENSE", "CHANGELOG.md")
#: ``packs`` is copied whole, internal pack included: a copy without ``packs/internal`` could not catch an include that widened.
_BUILD_INPUT_TREES = ("src", "packs")
PRODUCER_BINARY_NAME = "spec-kitty-event-producer"


def platform_tag_for_leg(leg: str) -> str:
    """The wheel tag ``dist-set.json`` pins for ``leg`` (the single authority; the hook caller reads it the same way)."""
    entries = json.loads(_DIST_SET_PATH.read_text(encoding="utf-8"))["platform_wheels"]
    return str(next(entry["tag"] for entry in entries if entry["leg"] == leg))


def stub_binary_name(leg: str) -> str:
    return PRODUCER_BINARY_NAME + (".exe" if leg.startswith("windows") else "")


def stub_binary_bytes(leg: str) -> bytes:
    """Deterministic stand-in for the native producer of ``leg``: the build hook only copies and hashes it."""
    return f"stub-producer-binary-for-{leg}\n".encode() * 64


def copy_build_inputs(destination: Path) -> Path:
    """Copy what a wheel/sdist build reads, so a hook-on build never writes into the working tree.

    Not a git checkout: hatchling reads ``.gitignore`` directly, and drops every VCS exclusion when the project root path itself
    matches one of its patterns (this repository ignores ``tmp/**`` and ``build/``). The copy keeps only the repository's own lines
    about the native directory, so a stub planted in ``src/specify_cli/_native/`` stays excluded exactly as in a real checkout.
    """
    destination.mkdir(parents=True, exist_ok=True)
    for name in _BUILD_INPUT_FILES:
        shutil.copy2(_REPO_ROOT / name, destination / name)
    for tree in _BUILD_INPUT_TREES:
        shutil.copytree(_REPO_ROOT / tree, destination / tree, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    real_ignore = (_REPO_ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    (destination / ".gitignore").write_text("\n".join(line for line in real_ignore if "_native" in line) + "\n", encoding="utf-8")
    hook_dir = destination / "scripts" / "release"
    hook_dir.mkdir(parents=True, exist_ok=True)
    for name in ("hatch_build_producer.py", "dist-set.json"):
        shutil.copy2(_REPO_ROOT / "scripts" / "release" / name, hook_dir / name)
    return destination


def run_build(project: Path, outdir: Path, *targets: str, env_extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    """``python -m build --no-isolation`` in ``project`` with the producer hook variables cleared, then ``env_extra`` applied."""
    env = {key: value for key, value in os.environ.items() if not key.startswith(_HOOK_ENV_PREFIXES)}
    env.update(env_extra or {})
    return subprocess.run(
        [sys.executable, "-m", "build", "--no-isolation", *targets, "--outdir", str(outdir)],
        cwd=project,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def default_platform_wheel_builder(outdir: Path, *, leg: str) -> None:
    """Build the platform wheel of ``leg`` into ``outdir``: hook on, a stub binary, the tag read from ``dist-set.json``.

    The sibling of :func:`default_wheel_sdist_builder` (the pure wheel). The build runs in a copy of the build inputs.
    """
    with tempfile.TemporaryDirectory(prefix="platform-wheel-") as scratch:
        project = copy_build_inputs(Path(scratch) / "project")
        binary = Path(scratch) / "inputs" / stub_binary_name(leg)
        binary.parent.mkdir()
        binary.write_bytes(stub_binary_bytes(leg))
        binary.chmod(0o644)  # artifact transfer drops mode bits; the hook must restore the exec bit itself
        result = run_build(
            project,
            outdir,
            "--wheel",
            env_extra={
                _HOOK_ENABLE_ENV: "true",
                "SPEC_KITTY_PRODUCER_BINARY": str(binary),
                "SPEC_KITTY_PRODUCER_PLATFORM_TAG": platform_tag_for_leg(leg),
            },
        )
    if result.returncode != 0:
        raise SharedBuildError(f"Platform wheel build for {leg} failed: {result.stderr[-2000:]}")


def published_platform_wheel(shared_dir: Path) -> Path | None:
    """The run's published platform wheel, or ``None`` if it is absent or empty."""
    wheels = sorted(shared_dir.glob("spec_kitty_cli-*.whl"))
    if len(wheels) == 1 and wheels[0].stat().st_size > 0:
        return wheels[0]
    return None


def ensure_shared_platform_wheel(
    run_root: Path,
    leg: str,
    build: Callable[[Path], None] | None = None,
    *,
    lock_timeout_s: float = _LOCK_TIMEOUT_S,
) -> Path:
    """Return the run's platform wheel of ``leg``, building it once under a lock like :func:`ensure_shared_build_artifacts`."""
    if build is None:

        def build(outdir: Path) -> None:
            default_platform_wheel_builder(outdir, leg=leg)

    dir_name = _PLATFORM_WHEEL_DIR_PREFIX + leg
    return _publish_once(
        run_root,
        dir_name=dir_name,
        staging_glob=dir_name + ".staging-*",
        inspect=published_platform_wheel,
        fill=build,
        attempts=1,
        retry_delay_s=0.0,
        lock_timeout_s=lock_timeout_s,
    )


def published_build_artifacts(shared_dir: Path) -> dict[str, Path] | None:
    """Return the run's published artifacts, or ``None`` if they are absent/incomplete."""
    wheels = sorted(shared_dir.glob("spec_kitty_cli-*.whl"))
    sdists = sorted(shared_dir.glob("spec_kitty_cli-*.tar.gz"))
    if not wheels or not sdists:
        return None
    artifacts = {"wheel": wheels[-1], "sdist": sdists[-1]}
    if all(path.is_file() and path.stat().st_size > 0 for path in artifacts.values()):
        return artifacts
    return None


def ensure_shared_build_artifacts(
    run_root: Path,
    build: Callable[[Path], None],
    *,
    lock_timeout_s: float = _LOCK_TIMEOUT_S,
) -> dict[str, Path]:
    """Return the run's wheel/sdist, building them once under a lock.

    ``build(outdir)`` must create the artifacts into ``outdir`` or raise
    :class:`SharedBuildError`. The first caller builds into a staging
    directory and publishes atomically; concurrent callers either queue on the
    lock and then reuse the publication, or find it already present and return
    immediately. Paths are always returned from the published location, never
    from the staging directory the caller happened to fill.
    """
    return _publish_once(
        run_root,
        dir_name=_SHARED_BUILD_DIR_NAME,
        staging_glob=_STAGING_GLOB,
        inspect=published_build_artifacts,
        fill=build,
        attempts=1,
        retry_delay_s=0.0,
        lock_timeout_s=lock_timeout_s,
    )


def published_source_snapshot(snapshot_dir: Path) -> Path | None:
    """Return the snapshot if it holds a complete, resolvable git repository."""
    if not ((snapshot_dir / ".git").exists() and (snapshot_dir / "pyproject.toml").is_file()):
        return None
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=snapshot_dir,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    return snapshot_dir


def _snapshot_commit_of(source_root: Path) -> str | None:
    """Resolve the commit to pin, without trusting a resolvable live HEAD.

    ``git rev-parse HEAD`` is the fast path for a healthy checkout. When it
    fails — #80's runner state was exactly a worktree whose HEAD had been left
    pointing at something unresolvable while discovery and everything else kept
    working — fall back to the gitdir's own bookkeeping files, which record the
    checked-out commit as a raw SHA: ``HEAD`` for a detached CI tree,
    ``ORIG_HEAD`` behind it.
    """
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "HEAD^{commit}"],
        cwd=source_root,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return result.stdout.strip()
    dot_git = source_root / ".git"
    if dot_git.is_dir():
        gitdir = dot_git
    elif dot_git.is_file():
        # A linked worktree: `.git` points at the real per-worktree gitdir.
        pointer = dot_git.read_text(encoding="utf-8").strip()
        if not pointer.startswith("gitdir:"):
            return None
        raw = Path(pointer.removeprefix("gitdir:").strip())
        gitdir = raw if raw.is_absolute() else source_root / raw
    else:
        return None
    for name in ("HEAD", "ORIG_HEAD"):
        try:
            value = (gitdir / name).read_text(encoding="utf-8").strip()
        except OSError:
            continue
        if len(value) == 40 and all(  # a SHA-1 hex digest is always 40 chars
            c in "0123456789abcdef" for c in value
        ):
            return value
    return None


def default_source_snapshot_builder(source_root: Path, outdir: Path) -> None:
    """Materialise ``source_root`` @ its current commit into ``outdir``.

    Deliberately built with ``init`` + ``fetch <sha>`` + detach rather than
    ``clone``: clone resolves the source's live HEAD and so inherits whatever
    transient state that HEAD is in (#80), while fetching the resolved commit
    never reads it. The fetch copies objects into a fresh pack in ``outdir``,
    so the snapshot shares nothing (not even hardlinks) with the source.

    ``--update-shallow`` is required for a shallow source (a depth-1 CI
    checkout): without it git still exits 0 but declines to record the shallow
    boundary, leaving commits whose parents are missing and no ``.git/shallow``,
    so every history walk in a project cloned from the snapshot fails.
    """
    commit = _snapshot_commit_of(source_root)
    if commit is None:
        raise SharedBuildError(f"cannot pin {source_root}: HEAD does not resolve and no detached SHA found")
    commands = (
        ["git", "init", "-q", str(outdir)],
        [
            "git",
            "-c",
            "uploadpack.allowAnySHA1InWant=true",
            "fetch",
            "-q",
            "--no-tags",
            "--update-shallow",
            str(source_root),
            commit,
        ],
        ["git", "checkout", "-q", "--detach", commit],
    )
    for index, command in enumerate(commands):
        # ``git init`` creates outdir, so it cannot itself run inside it.
        cwd = outdir if index else outdir.parent
        result = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
        if result.returncode != 0:
            raise SharedBuildError(f"source snapshot step failed ({command[:3]}): {result.stderr}")


def ensure_run_stable_source_snapshot(
    run_root: Path,
    *,
    source_root: Path = _REPO_ROOT,
    build: Callable[[Path], None] | None = None,
    lock_timeout_s: float = _LOCK_TIMEOUT_S,
) -> Path:
    """Return a per-run snapshot of ``source_root`` that outlives source churn.

    The e2e acceptance fixtures read git provenance from, and clone every test
    project out of, the checkout being tested. On CI runners that checkout is
    a linked worktree over a shared canonical clone whose maintenance (fetches,
    gc/repack, worktree re-pins) runs *during* the session (#80), and any git
    call that lands inside such a window fails with exit 128 — which is why
    whole tails of ``test_worktree_owned_worktrees_isolated`` were red on some
    runner VMs and green on others regardless of diff. Building the snapshot
    once, at session start, into this run's temp root decouples every later
    read and clone from that churn while pinning byte-identical committed
    content (the snapshot is never touched again by anyone).

    Publication follows the same protocol as :func:`ensure_shared_build_artifacts`:
    one build under a lock, atomic rename, later workers reuse it.
    """
    if build is None:

        def build(outdir: Path) -> None:
            default_source_snapshot_builder(source_root, outdir)

    return _publish_once(
        run_root,
        dir_name=_SOURCE_SNAPSHOT_DIR_NAME,
        staging_glob=_SOURCE_SNAPSHOT_STAGING_GLOB,
        inspect=published_source_snapshot,
        fill=build,
        attempts=_SNAPSHOT_ATTEMPTS,
        retry_delay_s=_SNAPSHOT_RETRY_DELAY_S,
        lock_timeout_s=lock_timeout_s,
    )


def _publish_once(
    run_root: Path,
    *,
    dir_name: str,
    staging_glob: str,
    inspect: Callable[[Path], _T | None],
    fill: Callable[[Path], None],
    attempts: int,
    retry_delay_s: float,
    lock_timeout_s: float,
) -> _T:
    """Publish ``dir_name`` under ``run_root`` once; later claims reuse it.

    ``inspect(dir)`` returns the published value or ``None`` when incomplete;
    ``fill(outdir)`` produces it into a staging directory. A failed attempt is
    swept and retried up to ``attempts`` times before the error propagates, so
    a transient collision cannot fail the whole session when a retry would do.
    """
    shared_dir = run_root / dir_name
    published = inspect(shared_dir)
    if published is not None:
        return published
    try:
        with machine_file_lock(run_root / (dir_name + ".lock"), blocking=True, timeout_s=lock_timeout_s):
            published = inspect(shared_dir)
            if published is not None:
                return published
            for stale in run_root.glob(staging_glob):
                shutil.rmtree(stale, ignore_errors=True)
            staging = run_root / f"{dir_name}.staging-{os.getpid()}"
            for attempt in range(attempts):
                shutil.rmtree(staging, ignore_errors=True)
                staging.mkdir(parents=True)
                try:
                    fill(staging)
                    filled = inspect(staging)
                    if filled is None:
                        raise SharedBuildError(f"{dir_name} did not produce a publishable artifact")
                    if shared_dir.exists():
                        shutil.rmtree(shared_dir)
                    os.replace(staging, shared_dir)
                    break
                except Exception:
                    shutil.rmtree(staging, ignore_errors=True)
                    if attempt == attempts - 1:
                        raise
                    time.sleep(retry_delay_s)
    except LockAcquireTimeout as error:
        # Queueing behind another worker's build is expected; a timed-out wait
        # must flow down the same path as any other build failure (a loud
        # fail since FR-004), not escape as a collection ERROR.
        raise SharedBuildError(f"timed out after {lock_timeout_s}s waiting for the {dir_name} lock") from error
    published_after_rename = inspect(shared_dir)
    if published_after_rename is None:
        raise SharedBuildError(f"Published artifacts did not survive publication in {shared_dir}")
    return published_after_rename
