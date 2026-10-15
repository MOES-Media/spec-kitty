"""The publish log and the fault log are gitignored after init and after upgrade (FR-019, AC-023)."""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from specify_cli.gitignore_manager import GitignoreManager
from specify_cli.state.contract import STATE_SURFACES, GitClass, StateRoot
from specify_cli.upgrade.migrations.m_2_0_9_state_gitignore import StateGitignoreMigration

pytestmark = pytest.mark.fast

_PUBLISH = "event_producer_publish_log"
_FAULT = "event_producer_fault_log"
_CLOCK = "event_producer_clock_state"


def _surface(name: str):  # noqa: ANN202 - a test helper over the contract
    (found,) = [s for s in STATE_SURFACES if s.name == name]
    return found


def _concrete(name: str) -> str:
    return _surface(name).path_pattern.replace("<mission_id>", "01J0000000000000000000MSN1").replace("<UTC date>", "2026-10-15")


def _repo(tmp_path: Path) -> Path:
    root = tmp_path / "project"
    root.mkdir(parents=True)
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)  # noqa: S607
    return root


def _ignored(root: Path, relative: str) -> bool:
    return subprocess.run(["git", "check-ignore", "-q", relative], cwd=root, check=False).returncode == 0  # noqa: S607


def _fresh_init(root: Path) -> None:
    """What `spec-kitty init` does for ignore hygiene: the manager protects the runtime entries."""
    result = GitignoreManager(root).protect_all_agents()
    assert result.success


def _upgrade(root: Path) -> None:
    """What `spec-kitty upgrade` does for a project that predates the entries: the state-gitignore migration."""
    (root / ".gitignore").write_text("", encoding="utf-8")
    assert StateGitignoreMigration().apply(root).success


@pytest.mark.parametrize("setup", [_fresh_init, _upgrade], ids=["fresh-init", "upgrade"])
@pytest.mark.parametrize("surface", [_PUBLISH, _FAULT])
def test_the_producer_logs_are_ignored(tmp_path: Path, setup, surface: str) -> None:  # noqa: ANN001
    root = _repo(tmp_path)
    setup(root)
    path = _concrete(surface)
    assert not _ignored(_repo(tmp_path / "unconfigured"), path), "sanity: an unconfigured repo does not ignore it"
    assert _ignored(root, path), f"{path} must be ignored"


def test_the_publish_project_file_beside_the_mission_files_is_ignored_too(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _fresh_init(root)
    assert _ignored(root, ".kittify/events/publish/project-01J0000000000000000000PRJ1.jsonl")


@pytest.mark.parametrize("surface", [_PUBLISH, _FAULT])
def test_removing_the_ignore_rule_makes_the_check_fail(tmp_path: Path, surface: str) -> None:
    """Mutation: the check is not vacuous."""
    root = _repo(tmp_path)
    _fresh_init(root)
    gitignore = root / ".gitignore"
    gitignore.write_text("\n".join(line for line in gitignore.read_text(encoding="utf-8").splitlines() if ".kittify/events" not in line) + "\n", encoding="utf-8")
    assert not _ignored(root, _concrete(surface))


def test_the_surfaces_are_declared_with_the_documented_roots() -> None:
    assert _surface(_PUBLISH).root is StateRoot.PROJECT and _surface(_PUBLISH).git_class is GitClass.IGNORED
    assert _surface(_FAULT).root is StateRoot.PROJECT and _surface(_FAULT).git_class is GitClass.IGNORED
    clock = _surface(_CLOCK)
    assert clock.root is StateRoot.GIT_INTERNAL and clock.git_class is GitClass.GIT_INTERNAL
    assert "COMMON" in clock.notes, "the clock-state entry documents that a linked worktree uses the git common directory"
