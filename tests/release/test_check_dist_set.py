"""``scripts/release/check_dist_set.py``: the dist-set, binary and manifest checks (mission event-producer-reroute, WP08 T052, T053, T082).

The script compares ``dist/`` with the pinned list in ``scripts/release/dist-set.json`` (three platform wheels, one sdist, the pure
wheel), re-hashes each platform wheel's binary against its ``PRODUCER.sha256`` member (``--verify-binaries``) and proves checksum
continuity from the downloaded ``producer-<leg>`` manifests to the wheels (``--verify-manifests``). Every rule has a floor (it found
the expected files and the check ran over them) and mutation cases that feed it the defect and require it to go red. Fixtures are stub
wheels: no wheel is built here.
"""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import stat
import tarfile
import zipfile
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

pytestmark = [pytest.mark.fast]

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release" / "check_dist_set.py"
DIST_SET = ROOT / "scripts" / "release" / "dist-set.json"

COMMIT = "0123456789abcdef0123456789abcdef01234567"
VERSION = "4.0.0"
NATIVE = "specify_cli/_native"
CHECKSUM = "PRODUCER.sha256"


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()  # noqa: TID251 -- file-integrity checksum of a stub binary, not the charter hash


def load_script() -> ModuleType:
    assert SCRIPT.is_file(), f"{SCRIPT.relative_to(ROOT)} does not exist yet"
    spec = importlib.util.spec_from_file_location("check_dist_set_under_test", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def real_dist_set() -> dict[str, Any]:
    return json.loads(DIST_SET.read_text(encoding="utf-8"))


def binary_name(leg: str) -> str:
    return "spec-kitty-event-producer.exe" if leg.startswith("windows") else "spec-kitty-event-producer"


def binary_bytes(leg: str) -> bytes:
    return f"stub-producer-binary-for-{leg}\n".encode() * 8


def write_wheel(
    dist: Path,
    tag: str,
    *,
    leg: str | None = None,
    binary: bytes | None = None,
    member: str | None = None,
    mode: int = 0o755,
    checksum_text: str | None = None,
    version: str = VERSION,
    extra: dict[str, bytes] | None = None,
) -> Path:
    """A stub wheel. With ``leg`` it carries the binary and ``PRODUCER.sha256`` the way the build hook lays them out."""
    wheel = dist / f"spec_kitty_cli-{version}-{tag}.whl"
    with zipfile.ZipFile(wheel, "w") as archive:
        archive.writestr("specify_cli/__init__.py", b"")
        archive.writestr(f"spec_kitty_cli-{version}.dist-info/METADATA", f"Name: spec-kitty-cli\nVersion: {version}\n")
        if leg is not None:
            name = member or binary_name(leg)
            data = binary if binary is not None else binary_bytes(leg)
            info = zipfile.ZipInfo(f"{NATIVE}/{name}")
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, data)
            text = checksum_text if checksum_text is not None else f"{sha256_of(binary_bytes(leg))}  {binary_name(leg)}\n"
            archive.writestr(f"{NATIVE}/{CHECKSUM}", text)
        for path, content in (extra or {}).items():
            archive.writestr(path, content)
    return wheel


def write_sdist(dist: Path, *, version: str = VERSION, members: dict[str, bytes] | None = None) -> Path:
    sdist = dist / f"spec_kitty_cli-{version}.tar.gz"
    with tarfile.open(sdist, "w:gz") as archive:
        for name, data in (members or {"PKG-INFO": b"Name: spec-kitty-cli\n"}).items():
            info = tarfile.TarInfo(f"spec_kitty_cli-{version}/{name}")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return sdist


def write_good_dist(dist: Path) -> dict[str, Path]:
    dist.mkdir(parents=True, exist_ok=True)
    files: dict[str, Path] = {}
    for entry in real_dist_set()["platform_wheels"]:
        files[entry["leg"]] = write_wheel(dist, entry["tag"], leg=entry["leg"])
    files["pure"] = write_wheel(dist, "py3-none-any")
    files["sdist"] = write_sdist(dist)
    return files


def write_manifests(root: Path, *, commit: str = COMMIT, skip: str | None = None, overrides: dict[str, dict[str, Any]] | None = None) -> Path:
    """The downloaded artifacts: ``<root>/producer-<leg>/{producer-manifest.json, <binary>}`` per leg."""
    for entry in real_dist_set()["platform_wheels"]:
        leg = entry["leg"]
        if leg == skip:
            continue
        directory = root / f"producer-{leg}"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / binary_name(leg)).write_bytes(binary_bytes(leg))
        manifest = {
            "v": 1,
            "leg": leg,
            "wheel_tag": entry["tag"],
            "binary": binary_name(leg),
            "binary_sha256": sha256_of(binary_bytes(leg)),
            "source_commit": commit,
        }
        manifest.update((overrides or {}).get(leg, {}))
        (directory / "producer-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return root


@pytest.fixture
def module() -> ModuleType:
    return load_script()


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    directory = tmp_path / "dist"
    write_good_dist(directory)
    return directory


def set_problems(module: ModuleType, dist: Path) -> list[str]:
    return list(module.check_dist_set(dist, real_dist_set()))


def binary_problems(module: ModuleType, dist: Path) -> list[str]:
    return list(module.verify_binaries(dist, real_dist_set()))


def manifest_problems(module: ModuleType, dist: Path, manifests: Path, commit: str = COMMIT) -> list[str]:
    return list(module.verify_manifests(dist, manifests, commit, real_dist_set()))


# --- the pinned list is the expected set (T052, AC-037 data, G-12) ----------------------------------------------


def test_the_pinned_list_has_the_floor_the_checker_iterates() -> None:
    data = real_dist_set()
    assert len(data["platform_wheels"]) == 3, "floor: three platform wheels"
    assert data["sdist"] == 1
    assert data["pure_wheels"] == ["py3-none-any"]


def test_a_complete_dist_directory_is_green(module: ModuleType, dist: Path) -> None:
    assert len(list(dist.iterdir())) == 5, "floor: the fixture holds the five expected files"
    assert set_problems(module, dist) == []


def test_mutation_removing_each_platform_wheel_is_red(module: ModuleType, dist: Path) -> None:
    for entry in real_dist_set()["platform_wheels"]:
        wheel = dist / f"spec_kitty_cli-{VERSION}-{entry['tag']}.whl"
        data = wheel.read_bytes()
        wheel.unlink()
        try:
            problems = set_problems(module, dist)
            assert any(entry["tag"] in problem and "missing" in problem for problem in problems), (entry["leg"], problems)
        finally:
            wheel.write_bytes(data)
    assert set_problems(module, dist) == []


def test_mutation_an_unexpected_platform_wheel_is_red(module: ModuleType, dist: Path) -> None:
    write_wheel(dist, "py3-none-manylinux_2_28_aarch64", leg="linux-aarch64")
    problems = set_problems(module, dist)
    assert any("manylinux_2_28_aarch64" in problem and "unexpected" in problem for problem in problems), problems


def test_mutation_a_duplicated_tag_is_red(module: ModuleType, dist: Path) -> None:
    write_wheel(dist, "py3-none-any", version="4.0.1")
    problems = set_problems(module, dist)
    assert any("py3-none-any" in problem for problem in problems), problems


def test_mutation_dropping_the_sdist_is_red(module: ModuleType, dist: Path) -> None:
    (dist / f"spec_kitty_cli-{VERSION}.tar.gz").unlink()
    assert any("sdist" in problem and "missing" in problem for problem in set_problems(module, dist))


def test_mutation_a_second_sdist_is_red(module: ModuleType, dist: Path) -> None:
    write_sdist(dist, version="4.0.1")
    assert any("sdist" in problem for problem in set_problems(module, dist))


def test_mutation_dropping_the_pure_wheel_is_red(module: ModuleType, dist: Path) -> None:
    (dist / f"spec_kitty_cli-{VERSION}-py3-none-any.whl").unlink()
    assert any("py3-none-any" in problem and "missing" in problem for problem in set_problems(module, dist))


def test_mutation_a_stray_file_is_red(module: ModuleType, dist: Path) -> None:
    (dist / "notes.txt").write_text("stray", encoding="utf-8")
    assert any("notes.txt" in problem and "unexpected" in problem for problem in set_problems(module, dist))


def test_mutation_wheels_of_different_versions_are_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][0]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag, leg="linux-x86_64", version="3.9.9")
    assert any("version" in problem for problem in set_problems(module, dist))


def test_mutation_an_empty_or_missing_dist_directory_is_red(module: ModuleType, tmp_path: Path) -> None:
    empty = tmp_path / "empty"
    empty.mkdir()
    assert set_problems(module, empty) != []
    assert any("not a directory" in problem for problem in set_problems(module, tmp_path / "absent"))


def test_mutation_an_empty_expected_list_is_red_not_vacuous(module: ModuleType, dist: Path) -> None:
    data = real_dist_set()
    data["platform_wheels"] = []
    problems = list(module.check_dist_set(dist, data))
    assert any("floor" in problem for problem in problems), problems


def test_the_result_names_every_problem_not_only_the_first(module: ModuleType, dist: Path) -> None:
    (dist / f"spec_kitty_cli-{VERSION}.tar.gz").unlink()
    (dist / f"spec_kitty_cli-{VERSION}-py3-none-any.whl").unlink()
    assert len(set_problems(module, dist)) >= 2


# --- --verify-binaries (T053; AC-024, AC-032 data) ---------------------------------------------------------------


def test_verify_binaries_is_green_on_a_good_set_and_visits_every_platform_wheel(module: ModuleType, dist: Path) -> None:
    assert binary_problems(module, dist) == []
    assert module.verify_binaries.__name__ == "verify_binaries"


def test_mutation_a_tampered_binary_byte_is_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][0]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    tampered = bytearray(binary_bytes("linux-x86_64"))
    tampered[3] ^= 0xFF
    write_wheel(dist, tag, leg="linux-x86_64", binary=bytes(tampered), checksum_text=f"{sha256_of(binary_bytes('linux-x86_64'))}  spec-kitty-event-producer\n")
    problems = binary_problems(module, dist)
    assert any(tag in problem and "sha256" in problem for problem in problems), problems


def test_mutation_a_missing_checksum_member_is_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][1]["tag"]
    wheel = dist / f"spec_kitty_cli-{VERSION}-{tag}.whl"
    wheel.unlink()
    write_wheel(dist, tag, leg="macos-arm64", checksum_text="")
    assert any(tag in problem and CHECKSUM in problem for problem in binary_problems(module, dist))


def test_mutation_a_checksum_naming_another_binary_is_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][0]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag, leg="linux-x86_64", checksum_text=f"{sha256_of(binary_bytes('linux-x86_64'))}  other-binary\n")
    assert any(tag in problem and "names" in problem for problem in binary_problems(module, dist))


@pytest.mark.parametrize("leg", ["linux-x86_64", "macos-arm64"])
def test_mutation_a_non_executable_unix_binary_is_red(module: ModuleType, dist: Path, leg: str) -> None:
    tag = next(e["tag"] for e in real_dist_set()["platform_wheels"] if e["leg"] == leg)
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag, leg=leg, mode=0o644)
    problems = binary_problems(module, dist)
    assert any(tag in problem and "executable" in problem for problem in problems), problems


def test_a_windows_binary_needs_no_exec_bit(module: ModuleType, dist: Path) -> None:
    tag = next(e["tag"] for e in real_dist_set()["platform_wheels"] if e["leg"] == "windows-x86_64")
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag, leg="windows-x86_64", mode=0o644)
    assert binary_problems(module, dist) == []


def test_mutation_a_platform_wheel_without_a_binary_is_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][2]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag)
    assert any(tag in problem and "binary" in problem for problem in binary_problems(module, dist))


def test_mutation_two_binaries_in_one_platform_wheel_are_red(module: ModuleType, dist: Path) -> None:
    tag = real_dist_set()["platform_wheels"][0]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    write_wheel(dist, tag, leg="linux-x86_64", extra={f"{NATIVE}/second-binary": b"x"})
    assert any(tag in problem and "binary" in problem for problem in binary_problems(module, dist))


def test_mutation_a_binary_in_the_pure_wheel_is_red(module: ModuleType, dist: Path) -> None:
    (dist / f"spec_kitty_cli-{VERSION}-py3-none-any.whl").unlink()
    write_wheel(dist, "py3-none-any", leg="linux-x86_64")
    assert any("py3-none-any" in problem and "binary" in problem for problem in binary_problems(module, dist))


def test_mutation_a_binary_in_the_sdist_is_red(module: ModuleType, dist: Path) -> None:
    (dist / f"spec_kitty_cli-{VERSION}.tar.gz").unlink()
    write_sdist(dist, members={"PKG-INFO": b"x", "src/specify_cli/_native/spec-kitty-event-producer": b"x"})
    assert any("sdist" in problem and "binary" in problem for problem in binary_problems(module, dist))


# --- --verify-manifests (T082; FR-042, AC-032, G-8) --------------------------------------------------------------


def test_verify_manifests_is_green_on_a_consistent_release(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    assert len(list(manifests.glob("producer-*/producer-manifest.json"))) == 3, "floor: three manifests"
    assert manifest_problems(module, dist, manifests) == []


def test_mutation_a_wrong_source_commit_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", commit="f" * 40)
    problems = manifest_problems(module, dist, manifests)
    assert len([p for p in problems if "source commit" in p]) == 3, problems


def test_mutation_one_leg_built_from_another_commit_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", overrides={"macos-arm64": {"source_commit": "e" * 40}})
    problems = manifest_problems(module, dist, manifests)
    assert len(problems) == 1 and "macos-arm64" in problems[0] and "source commit" in problems[0], problems


def test_mutation_an_empty_expected_commit_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", commit="")
    assert any("commit" in problem for problem in manifest_problems(module, dist, manifests, commit=""))


def test_mutation_a_tampered_wheel_binary_byte_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    tag = real_dist_set()["platform_wheels"][0]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    tampered = bytearray(binary_bytes("linux-x86_64"))
    tampered[0] ^= 0x01
    write_wheel(dist, tag, leg="linux-x86_64", binary=bytes(tampered))
    problems = manifest_problems(module, dist, manifests)
    assert any("linux-x86_64" in problem and "sha256" in problem for problem in problems), problems


def test_mutation_a_tampered_downloaded_binary_byte_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    downloaded = manifests / "producer-windows-x86_64" / binary_name("windows-x86_64")
    tampered = bytearray(downloaded.read_bytes())
    tampered[5] ^= 0x40
    downloaded.write_bytes(bytes(tampered))
    problems = manifest_problems(module, dist, manifests)
    assert any("windows-x86_64" in problem and "sha256" in problem for problem in problems), problems


def test_mutation_a_manifest_digest_that_matches_nothing_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", overrides={"linux-x86_64": {"binary_sha256": "0" * 64}})
    assert any("linux-x86_64" in problem and "sha256" in problem for problem in manifest_problems(module, dist, manifests))


def test_mutation_a_missing_manifest_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", skip="macos-arm64")
    problems = manifest_problems(module, dist, manifests)
    assert any("macos-arm64" in problem and "manifest" in problem and "missing" in problem for problem in problems), problems


def test_mutation_a_manifest_for_the_wrong_tag_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests", overrides={"linux-x86_64": {"wheel_tag": "py3-none-manylinux_2_17_x86_64"}})
    assert any("linux-x86_64" in problem and "tag" in problem for problem in manifest_problems(module, dist, manifests))


def test_mutation_an_unlisted_manifest_directory_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    (manifests / "producer-linux-aarch64").mkdir()
    (manifests / "producer-linux-aarch64" / "producer-manifest.json").write_text("{}", encoding="utf-8")
    assert any("linux-aarch64" in problem and "unexpected" in problem for problem in manifest_problems(module, dist, manifests))


def test_mutation_an_extra_wheel_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    write_wheel(dist, "py3-none-manylinux_2_28_aarch64", leg="linux-aarch64")
    assert any("unexpected" in problem for problem in manifest_problems(module, dist, manifests))


def test_mutation_a_missing_wheel_is_red(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    tag = real_dist_set()["platform_wheels"][1]["tag"]
    (dist / f"spec_kitty_cli-{VERSION}-{tag}.whl").unlink()
    assert any(tag in problem and "missing" in problem for problem in manifest_problems(module, dist, manifests))


def test_mutation_an_unreadable_manifest_is_red_not_an_exception(module: ModuleType, dist: Path, tmp_path: Path) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    (manifests / "producer-linux-x86_64" / "producer-manifest.json").write_text("{not json", encoding="utf-8")
    assert any("linux-x86_64" in problem and "manifest" in problem for problem in manifest_problems(module, dist, manifests))


# --- the command line ---------------------------------------------------------------------------------------------


def run_main(module: ModuleType, monkeypatch: pytest.MonkeyPatch, *argv: str) -> int:
    monkeypatch.setattr("sys.argv", ["check_dist_set.py", "--dist-set", str(DIST_SET), *argv])
    return int(module.main())


def test_main_exits_zero_on_a_good_set_and_names_the_problem_otherwise(
    module: ModuleType, dist: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    assert run_main(module, monkeypatch, "--dist-dir", str(dist), "--verify-binaries") == 0
    (dist / f"spec_kitty_cli-{VERSION}.tar.gz").unlink()
    assert run_main(module, monkeypatch, "--dist-dir", str(dist)) == 1
    assert "sdist" in capsys.readouterr().err


def test_main_verify_manifests_needs_manifests_and_commit(
    module: ModuleType, dist: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifests = write_manifests(tmp_path / "manifests")
    assert run_main(module, monkeypatch, "--dist-dir", str(dist), "--verify-manifests", "--manifests", str(manifests), "--commit", COMMIT) == 0
    assert run_main(module, monkeypatch, "--dist-dir", str(dist), "--verify-manifests", "--manifests", str(manifests), "--commit", "d" * 40) == 1
    with pytest.raises(SystemExit):
        run_main(module, monkeypatch, "--dist-dir", str(dist), "--verify-manifests")
