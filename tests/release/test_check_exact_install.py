from __future__ import annotations

import importlib.util
import inspect
import subprocess
import sys
import zipfile
from pathlib import Path
from types import ModuleType

import pytest

pytestmark = [pytest.mark.integration]

SCRIPT = (
    Path(__file__).resolve().parents[2]
    / "scripts"
    / "release"
    / "check_exact_install.py"
)


def load_script_module() -> ModuleType:
    spec = importlib.util.spec_from_file_location("check_exact_install", SCRIPT)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_wheel(dist_dir: Path, *, package: str, version: str, tag: str = "py3-none-any") -> Path:
    dist_dir.mkdir(exist_ok=True)
    prefix = package.replace("-", "_")
    wheel = dist_dir / f"{prefix}-{version}-{tag}.whl"
    with zipfile.ZipFile(wheel, "w") as zf:
        zf.writestr(
            f"{prefix}-{version}.dist-info/METADATA",
            f"Metadata-Version: 2.4\nName: {package}\nVersion: {version}\n",
        )
    return wheel


def test_console_script_smoke_runs_after_exact_install(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = load_script_module()
    write_wheel(tmp_path / "dist", package="spec-kitty-cli", version="3.2.0rc99")
    calls: list[list[str]] = []

    def fake_run(
        cmd: list[str], *, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        calls.append(cmd)
        stdout = "3.2.0rc99\n" if "-c" in cmd else ""
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(module, "run", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(SCRIPT),
            "--dist-dir",
            str(tmp_path / "dist"),
            "--package",
            "spec-kitty-cli",
            "--console-script",
            "spec-kitty",
            "--console-arg=--version",
        ],
    )

    assert module.main() == 0

    assert any(call[-3:] == ["install", "--upgrade", "pip"] for call in calls)
    assert any(
        "spec-kitty" in Path(call[0]).name and call[1:] == ["--version"]
        for call in calls
    )


def test_console_script_smoke_failure_fails_release_gate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = load_script_module()
    write_wheel(tmp_path / "dist", package="spec-kitty-cli", version="3.2.0rc99")

    def fake_run(
        cmd: list[str], *, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        if "spec-kitty" in Path(cmd[0]).name:
            return subprocess.CompletedProcess(
                cmd,
                1,
                stdout="",
                stderr="ModuleNotFoundError: No module named 'click'\n",
            )
        stdout = "3.2.0rc99\n" if "-c" in cmd else ""
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(module, "run", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(SCRIPT),
            "--dist-dir",
            str(tmp_path / "dist"),
            "--package",
            "spec-kitty-cli",
            "--console-script",
            "spec-kitty",
            "--console-arg=--version",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        module.main()

    assert exc.value.code
    assert "console script smoke test" in str(exc.value)
    assert "No module named 'click'" in str(exc.value)


def test_from_index_installs_exact_version_without_local_wheel(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = load_script_module()
    calls: list[list[str]] = []

    def fake_run(
        cmd: list[str], *, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        calls.append(cmd)
        stdout = "3.2.0rc99\n" if "-c" in cmd else ""
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(module, "run", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(SCRIPT),
            "--package",
            "spec-kitty-cli",
            "--version",
            "3.2.0rc99",
            "--from-index",
            "--index-url",
            "https://pypi.org/simple",
        ],
    )

    assert module.main() == 0

    assert any(
        call[-3:] == [
            "--index-url",
            "https://pypi.org/simple",
            "spec-kitty-cli==3.2.0rc99",
        ]
        for call in calls
    )


def test_from_index_requires_exact_version(monkeypatch: pytest.MonkeyPatch) -> None:
    module = load_script_module()
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(SCRIPT),
            "--package",
            "spec-kitty-cli",
            "--from-index",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        module.main()

    assert "--version is required when --from-index is used" in str(exc.value)


# --- --wheel-tag: pick one wheel out of a multi-wheel dist directory (mission event-producer-reroute, WP08 T054, G-2) ----

PLATFORM_TAGS = (
    "py3-none-manylinux_2_28_x86_64",
    "py3-none-macosx_11_0_arm64",
    "py3-none-win_amd64",
)


def write_wheel_set(dist_dir: Path) -> dict[str, Path]:
    wheels = {
        tag: write_wheel(dist_dir, package="spec-kitty-cli", version="4.0.0", tag=tag)
        for tag in (*PLATFORM_TAGS, "py3-none-any")
    }
    assert len(wheels) == 4, "floor: the fixture holds four wheels"
    return wheels


def require_wheel_tag_support(module: ModuleType) -> None:
    assert "wheel_tag" in inspect.signature(module.locate_wheel).parameters, (
        "locate_wheel takes no wheel_tag yet"
    )


def test_untagged_multi_wheel_directory_still_refuses(tmp_path: Path) -> None:
    module = load_script_module()
    write_wheel_set(tmp_path / "dist")

    with pytest.raises(SystemExit) as exc:
        module.locate_wheel(tmp_path / "dist", "spec-kitty-cli")

    assert "Expected exactly one wheel" in str(exc.value)


@pytest.mark.parametrize("tag", [*PLATFORM_TAGS, "py3-none-any"])
def test_wheel_tag_selects_the_matching_wheel_by_path(
    tmp_path: Path, tag: str
) -> None:
    module = load_script_module()
    require_wheel_tag_support(module)
    wheels = write_wheel_set(tmp_path / "dist")

    assert (
        module.locate_wheel(tmp_path / "dist", "spec-kitty-cli", wheel_tag=tag)
        == wheels[tag]
    )


def test_wheel_tag_with_no_matching_wheel_refuses(tmp_path: Path) -> None:
    module = load_script_module()
    require_wheel_tag_support(module)
    write_wheel_set(tmp_path / "dist")

    with pytest.raises(SystemExit) as exc:
        module.locate_wheel(
            tmp_path / "dist",
            "spec-kitty-cli",
            wheel_tag="py3-none-manylinux_2_28_aarch64",
        )

    assert "py3-none-manylinux_2_28_aarch64" in str(exc.value)


def test_wheel_tag_is_matched_whole_not_as_a_substring(tmp_path: Path) -> None:
    module = load_script_module()
    require_wheel_tag_support(module)
    write_wheel_set(tmp_path / "dist")

    with pytest.raises(SystemExit):
        module.locate_wheel(
            tmp_path / "dist", "spec-kitty-cli", wheel_tag="none-win_amd64"
        )


def test_wheel_tag_with_two_matching_wheels_refuses(tmp_path: Path) -> None:
    module = load_script_module()
    require_wheel_tag_support(module)
    dist = tmp_path / "dist"
    write_wheel(dist, package="spec-kitty-cli", version="4.0.0", tag="py3-none-any")
    write_wheel(dist, package="spec-kitty-cli", version="4.0.1", tag="py3-none-any")

    with pytest.raises(SystemExit) as exc:
        module.locate_wheel(dist, "spec-kitty-cli", wheel_tag="py3-none-any")

    assert "Expected exactly one wheel" in str(exc.value)


def test_main_installs_the_wheel_the_tag_selects(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    module = load_script_module()
    wheels = write_wheel_set(tmp_path / "dist")
    calls: list[list[str]] = []

    def fake_run(
        cmd: list[str], *, env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        calls.append(cmd)
        stdout = "4.0.0\n" if "-c" in cmd else ""
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(module, "run", fake_run)
    chosen = wheels["py3-none-win_amd64"]
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(SCRIPT),
            "--dist-dir",
            str(tmp_path / "dist"),
            "--package",
            "spec-kitty-cli",
            "--wheel-tag",
            "py3-none-win_amd64",
        ],
    )

    assert module.main() == 0

    assert any(call[-1] == str(chosen) for call in calls)
    assert not any(
        call[-1] == str(other) for call in calls for other in wheels.values() if other != chosen
    )
    assert f"- wheel: {chosen.name}" in capsys.readouterr().out


def test_main_without_a_tag_on_a_multi_wheel_directory_refuses(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = load_script_module()
    write_wheel_set(tmp_path / "dist")
    monkeypatch.setattr(
        sys,
        "argv",
        [str(SCRIPT), "--dist-dir", str(tmp_path / "dist"), "--package", "spec-kitty-cli"],
    )

    with pytest.raises(SystemExit) as exc:
        module.main()

    assert "Expected exactly one wheel" in str(exc.value)
