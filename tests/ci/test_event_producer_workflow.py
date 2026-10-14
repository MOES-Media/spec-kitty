"""Guard tests for ``event-producer.yml`` (which also builds the native image) and the shared smoke action (mission event-producer-reroute, WP01).

Every rule is a function over a parsed workflow document, so each is proved twice: the real file passes it, and a deep copy with one
mutation fails it for that rule. A rule that found nothing to judge fails (no vacuous pass). No job of the workflow may run pytest:
the pin and glue tests run in the router lanes that own them, and a pytest call here would be a duplicate per-change suite execution
(``tests/architectural/test_no_duplicate_suite_execution.py``).
"""

from __future__ import annotations

import copy
import json
import re
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

from tests.contract._loader import load_tool

pytestmark = pytest.mark.fast

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
EVENT_PRODUCER = WORKFLOWS / "event-producer.yml"
RETIRED_PRODUCER_NATIVE = WORKFLOWS / "producer-native.yml"
SMOKE_ACTION = ROOT / ".github" / "actions" / "producer-smoke" / "action.yml"
TOOLS = ROOT / "services" / "event-producer" / "tools"
DIST_SET = ROOT / "scripts" / "release" / "dist-set.json"

THREE_OS = {"ubuntu-24.04", "macos-14", "windows-2022"}
THREE_LEGS = {"linux-x86_64", "macos-arm64", "windows-x86_64"}
GATE_JOB = "event-producer-gate"
GUARD = "github.repository == 'spec-kitty/spec-kitty'"
PINNED_USES = re.compile(r"^[\w.-]+/[\w.-]+(?:/[\w./-]+)?@[0-9a-f]{40}$")


def load(path: Path) -> dict[str, Any]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return document


def jobs(document: dict[str, Any]) -> dict[str, Any]:
    found = document["jobs"]
    assert found, "the workflow has no jobs: the rule would pass vacuously"
    return found  # type: ignore[no-any-return]


def triggers(document: dict[str, Any]) -> dict[str, Any]:
    return document.get("on", document.get(True, {}))  # type: ignore[no-any-return]  # PyYAML reads the key `on` as True


def run_texts(job: dict[str, Any]) -> list[str]:
    return [str(step["run"]) for step in job.get("steps", []) if "run" in step]


def all_run_texts(document: dict[str, Any]) -> list[str]:
    return [text for job in jobs(document).values() for text in run_texts(job)]


def matrix_values(job: dict[str, Any], key: str) -> set[str]:
    include = job["strategy"]["matrix"]["include"]
    return {str(entry[key]) for entry in include}


# --- rules ------------------------------------------------------------------------------------------------------


def pytest_violations(document: dict[str, Any]) -> list[str]:
    return [text for text in all_run_texts(document) if re.search(r"\bpytest\b", text)]


def continue_on_error_violations(document: dict[str, Any]) -> list[str]:
    found = [name for name, job in jobs(document).items() if job.get("continue-on-error")]
    found += [name for name, job in jobs(document).items() for step in job.get("steps", []) if step.get("continue-on-error")]
    return found


def gate_violations(document: dict[str, Any]) -> list[str]:
    all_jobs = jobs(document)
    if GATE_JOB not in all_jobs:
        return ["no terminal gate"]
    gate = all_jobs[GATE_JOB]
    problems = []
    needed = set(gate.get("needs", []))
    others = set(all_jobs) - {GATE_JOB}
    if needed != others:
        problems.append(f"gate needs {sorted(needed)} but the other jobs are {sorted(others)}")
    if "always()" not in str(gate.get("if", "")):
        problems.append("gate is not self-starting (always())")
    if GUARD not in str(gate.get("if", "")):
        problems.append("gate carries no fork guard")
    texts = " ".join(run_texts(gate))
    if '"$result" != "success"' not in texts:
        problems.append("gate does not require every result to be success")
    return problems


def matrix_violations(job: dict[str, Any], key: str, expected: set[str]) -> list[str]:
    problems = []
    found = matrix_values(job, key)
    if found != expected:
        problems.append(f"{key} legs are {sorted(found)}, expected {sorted(expected)}")
    if job["strategy"].get("fail-fast") is not False:
        problems.append("fail-fast is not false")
    return problems


def pin_step_violations(document: dict[str, Any]) -> list[str]:
    texts = " ".join(run_texts(jobs(document)["verify-pins"]))
    problems = []
    if "uv run --frozen --no-sync python contracts/tools/gradle_pin_check.py" not in texts:
        problems.append("no gradle_pin_check step")
    for needle in ("--pins services/event-producer/tools/pins.json", "--workflow .github/workflows/event-producer.yml"):
        if needle not in texts:
            problems.append(f"verify_pins call lacks {needle}")
    if "producer-native.yml" in texts:
        problems.append("verify_pins still names the folded producer-native.yml")
    for mode in ("floor", "tamper", "module", "sbom"):
        if f"expect-failure.sh {mode}" not in texts:
            problems.append(f"no expect-failure.sh {mode} step")
    return problems


def node_id_violations(document: dict[str, Any]) -> list[str]:
    problems = []
    for name, job in jobs(document).items():
        text = " ".join(run_texts(job))
        has_flag = "REQUIRE_LIVE_NODE_ID" in text
        if name == "gradle-test":
            if "PRODUCER_LIVE_PY_NODE_ID" not in text:
                problems.append("gradle-test does not export PRODUCER_LIVE_PY_NODE_ID")
            if "REQUIRE_LIVE_NODE_ID=1" not in text:
                problems.append("gradle-test does not export REQUIRE_LIVE_NODE_ID=1")
        elif has_flag:
            problems.append(f"{name} sets REQUIRE_LIVE_NODE_ID (only gradle-test may)")
    return problems


def gradle_check_violations(document: dict[str, Any]) -> list[str]:
    texts = run_texts(jobs(document)["gradle-test"])
    commands = [line.strip() for text in texts for line in text.splitlines() if "./gradlew" in line]
    if not commands:
        return ["gradle-test runs no gradlew command"]
    return [c for c in commands if not c.endswith("./gradlew --no-daemon check") or " -x " in c]


# --- event-producer.yml -----------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def event_producer() -> dict[str, Any]:
    return load(EVENT_PRODUCER)


def test_no_job_of_the_workflow_runs_pytest(event_producer: dict[str, Any]) -> None:
    assert len(all_run_texts(event_producer)) > 10, "floor: the scan must see the run steps"
    assert pytest_violations(event_producer) == []


def test_mutation_a_pytest_step_is_red(event_producer: dict[str, Any]) -> None:
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"]["verify-pins"]["steps"].append({"run": "uv run --frozen pytest tests/contract/test_gradle_pin_check.py"})
    assert len(pytest_violations(mutated)) == 1


def test_no_continue_on_error_anywhere(event_producer: dict[str, Any]) -> None:
    assert continue_on_error_violations(event_producer) == []
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"]["native-smoke"]["continue-on-error"] = True
    assert continue_on_error_violations(mutated) == ["native-smoke"]


def test_triggers_and_path_filters(event_producer: dict[str, Any]) -> None:
    on = triggers(event_producer)
    assert set(on) == {"pull_request", "push", "workflow_dispatch", "workflow_call"}
    assert on["pull_request"]["paths"] == on["push"]["paths"]
    assert on["push"]["branches"] == ["main"]
    paths = set(on["pull_request"]["paths"])
    for needed in (
        "services/event-producer/**",
        "scripts/release/**",
        "pyproject.toml",
        ".github/actions/producer-smoke/**",
        ".github/workflows/event-producer.yml",
    ):
        assert needed in paths
    assert ".github/workflows/producer-native.yml" not in paths, "producer-native.yml is folded into this workflow"
    assert not any(p.startswith("src/specify_cli/_native") for p in paths), "the gitignored native directory is never a trigger path"


def test_verify_pins_runs_the_pin_scripts_and_every_negative_control(event_producer: dict[str, Any]) -> None:
    assert pin_step_violations(event_producer) == []
    mutated = copy.deepcopy(event_producer)
    steps = mutated["jobs"]["verify-pins"]["steps"]
    mutated["jobs"]["verify-pins"]["steps"] = [s for s in steps if "expect-failure.sh sbom" not in str(s.get("run", ""))]
    assert pin_step_violations(mutated) == ["no expect-failure.sh sbom step"]
    steps = [s for s in steps if "gradle_pin_check" not in str(s.get("run", ""))]
    mutated["jobs"]["verify-pins"]["steps"] = steps
    assert "no gradle_pin_check step" in pin_step_violations(mutated)


def test_verify_pins_has_no_pytest_so_the_pin_tests_stay_in_the_router_lane(event_producer: dict[str, Any]) -> None:
    assert not any("pytest" in text for text in run_texts(jobs(event_producer)["verify-pins"]))


def test_gradle_test_is_a_three_os_matrix_running_the_full_check(event_producer: dict[str, Any]) -> None:
    job = jobs(event_producer)["gradle-test"]
    assert matrix_violations(job, "os", THREE_OS) == []
    assert gradle_check_violations(event_producer) == []
    narrowed = copy.deepcopy(event_producer)
    narrowed["jobs"]["gradle-test"]["steps"][-1]["run"] = "cd services/event-producer && ./gradlew --no-daemon :domain:check"
    assert gradle_check_violations(narrowed) != []
    dropped = copy.deepcopy(event_producer)
    dropped["jobs"]["gradle-test"]["strategy"]["matrix"]["include"].pop()
    assert matrix_violations(dropped["jobs"]["gradle-test"], "os", THREE_OS) != []
    sticky = copy.deepcopy(event_producer)
    sticky["jobs"]["gradle-test"]["strategy"]["fail-fast"] = True
    assert matrix_violations(sticky["jobs"]["gradle-test"], "os", THREE_OS) == ["fail-fast is not false"]


def test_the_live_node_id_is_exported_and_required_only_in_gradle_test(event_producer: dict[str, Any]) -> None:
    assert node_id_violations(event_producer) == []
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"]["native-smoke"]["steps"].append({"run": "echo REQUIRE_LIVE_NODE_ID=1 >> $GITHUB_ENV"})
    assert node_id_violations(mutated) == ["native-smoke sets REQUIRE_LIVE_NODE_ID (only gradle-test may)"]
    missing = copy.deepcopy(event_producer)
    missing["jobs"]["gradle-test"]["steps"] = [s for s in missing["jobs"]["gradle-test"]["steps"] if "REQUIRE_LIVE_NODE_ID" not in str(s.get("run", ""))]
    assert node_id_violations(missing) != []


def test_native_smoke_runs_the_three_legs_through_the_shared_action(event_producer: dict[str, Any]) -> None:
    job = jobs(event_producer)["native-smoke"]
    assert matrix_values(job, "leg") == THREE_LEGS
    assert job["strategy"]["fail-fast"] is False
    uses = [step.get("uses") for step in job["steps"]]
    assert "./.github/actions/producer-smoke" in uses
    action_step = next(step for step in job["steps"] if step.get("uses") == "./.github/actions/producer-smoke")
    assert action_step["with"]["mode"] == "binary"
    assert job["needs"] == "native"


def test_the_native_job_builds_in_this_workflow_with_no_reusable_call(event_producer: dict[str, Any]) -> None:
    job = jobs(event_producer)["native"]
    assert "uses" not in job, "the native build is folded into this workflow, not called"
    assert not RETIRED_PRODUCER_NATIVE.exists(), "producer-native.yml is folded away (the reusable-workflow ceiling)"


def test_the_terminal_gate_needs_every_job_and_requires_success(event_producer: dict[str, Any]) -> None:
    assert gate_violations(event_producer) == []
    assert set(jobs(event_producer)[GATE_JOB]["needs"]) == {"verify-pins", "gradle-test", "native", "native-smoke"}
    for dropped in ("verify-pins", "gradle-test", "native", "native-smoke"):
        mutated = copy.deepcopy(event_producer)
        mutated["jobs"][GATE_JOB]["needs"].remove(dropped)
        assert gate_violations(mutated) != [], dropped
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"][GATE_JOB]["steps"][0]["run"] = "echo ok"
    assert "gate does not require every result to be success" in gate_violations(mutated)
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"][GATE_JOB]["if"] = GUARD
    assert "gate is not self-starting (always())" in gate_violations(mutated)


def test_root_jobs_carry_the_fork_guard(event_producer: dict[str, Any]) -> None:
    for name in ("verify-pins", "gradle-test", "native"):
        assert GUARD in str(jobs(event_producer)[name]["if"]), name
        assert "github.event_name == 'workflow_dispatch'" in str(jobs(event_producer)[name]["if"]), name
        assert "github.event_name == 'pull_request'" in str(jobs(event_producer)[name]["if"]), name


# --- the folded native build ------------------------------------------------------------------------------------


def test_mode_and_ref_inputs_are_exposed_through_workflow_call_and_dispatch(event_producer: dict[str, Any]) -> None:
    on = triggers(event_producer)
    for trigger in ("workflow_call", "workflow_dispatch"):
        inputs = on[trigger]["inputs"]
        assert set(inputs) == {"mode", "ref"}, trigger
        assert inputs["mode"]["default"] == "pr", trigger
        assert inputs["mode"]["required"] is False, trigger
        assert inputs["ref"]["required"] is False, trigger
    assert on["workflow_dispatch"]["inputs"]["mode"]["options"] == ["pr", "release"]
    assert on["workflow_call"]["inputs"]["mode"]["type"] == "string"


def test_mode_selects_the_start_gate_and_the_ref(event_producer: dict[str, Any]) -> None:
    steps = jobs(event_producer)["native"]["steps"]
    package = next(s for s in steps if s.get("name", "").startswith("Package the leg"))
    assert "inputs.mode != 'release'" in package["env"]["ENFORCE"], "the warm start gate is held in pr mode only"
    checkout = next(s for s in steps if "actions/checkout" in str(s.get("uses", "")))
    assert checkout["with"]["ref"] == "${{ inputs.ref }}"


def test_the_native_job_builds_the_three_legs_of_dist_set(event_producer: dict[str, Any]) -> None:
    job = jobs(event_producer)["native"]
    assert matrix_violations(job, "leg", THREE_LEGS) == []
    assert matrix_values(job, "runs-on") == THREE_OS
    legs = {w["leg"] for w in json.loads(DIST_SET.read_text(encoding="utf-8"))["platform_wheels"]}
    assert matrix_values(job, "leg") == legs


def test_the_native_job_pins_its_toolchain_and_floors(event_producer: dict[str, Any]) -> None:
    text = EVENT_PRODUCER.read_text(encoding="utf-8")
    steps = jobs(event_producer)["native"]["steps"]
    run = " ".join(str(s.get("run", "")) for s in steps)
    assert "install-graalvm.sh" in run
    assert 'kind == "container-image"' in run, "the manylinux image is read from pins.json, not written twice"
    assert "manylinux_2_28" in text
    macos = next(s for s in steps if s.get("name") == "Build the native image (macOS)")
    assert macos["env"]["MACOSX_DEPLOYMENT_TARGET"] == "11.0"
    msvc = next(s for s in steps if "msvc-dev-cmd" in str(s.get("uses", "")))
    assert PINNED_USES.match(msvc["uses"].split(" ")[0]), "the MSVC environment action is pinned by commit SHA"
    assert "package-leg.sh" in run and "expect-failure.sh floorgate" in run
    assert "strip" not in run, "the macOS binary is never stripped"


def test_the_native_job_uploads_the_binary_manifest_and_sbom(event_producer: dict[str, Any]) -> None:
    uploads = [s for s in jobs(event_producer)["native"]["steps"] if "upload-artifact" in str(s.get("uses", ""))]
    assert [u["with"]["name"] for u in uploads] == ["producer-${{ matrix.leg }}", "producer-sbom"]
    assert all(u["with"]["if-no-files-found"] == "error" for u in uploads)


def test_every_uses_line_is_pinned_and_the_manifest_passes_verify_pins(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    tool = load_tool(monkeypatch, ROOT / "contracts" / "tools" / "verify_pins.py", "verify_pins_under_test", syspath=ROOT / "contracts" / "tools")
    code = tool.main(["--root", str(ROOT), "--pins", str(TOOLS / "pins.json"), "--workflow", str(EVENT_PRODUCER), "--pins-only"])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "uses_lines=0" not in out


def test_verify_pins_goes_red_on_an_unpinned_use(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    tool = load_tool(monkeypatch, ROOT / "contracts" / "tools" / "verify_pins.py", "verify_pins_mutation", syspath=ROOT / "contracts" / "tools")
    text = EVENT_PRODUCER.read_text(encoding="utf-8")
    assert "actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8" in text
    planted = tmp_path / "planted.yml"
    planted.write_text(text.replace("actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8", "actions/checkout@v5"), encoding="utf-8")
    code = tool.main(["--root", str(ROOT), "--pins", str(TOOLS / "pins.json"), "--workflow", str(planted), "--pins-only"])
    assert code == 1
    assert "UNPINNED_USES" in capsys.readouterr().out


# --- the shared smoke action and the shell tools ----------------------------------------------------------------


def test_the_smoke_action_is_shell_only_with_the_documented_inputs() -> None:
    action = load(SMOKE_ACTION)
    assert set(action["inputs"]) == {"wheel", "mode", "expect", "command"}
    assert action["runs"]["using"] == "composite"
    steps = action["runs"]["steps"]
    assert steps and all(step["shell"] == "bash" for step in steps)
    assert not list(SMOKE_ACTION.parent.glob("*.py")), "the smoke is not a Python file"
    text = SMOKE_ACTION.read_text(encoding="utf-8")
    assert "find .kittify/events/publish -type f -name '*.jsonl'" in text, "the publish log is found by the fixed lookup"
    assert "smoke-request*.json" in text
    assert '{"v":1,"op":"produce","start_path":"."}' in text, "the inline default request the WP01 spike accepts"


@pytest.mark.parametrize("script", ["expect-failure.sh", "check-sbom.sh", "measure-start.sh", "install-graalvm.sh", "package-leg.sh", "check-java-home.sh"])
def test_the_shell_tools_exist_and_are_bash(script: str) -> None:
    path = TOOLS / script
    assert path.is_file()
    assert path.read_text(encoding="utf-8").startswith("#!/usr/bin/env bash\n")
    assert "set -" in path.read_text(encoding="utf-8")


def _fake_jdk(root: Path) -> Path:
    """An unpacked GraalVM-shaped tree: the JDK root plus the lib/svm decoy that holds a native-image but no bin/java."""
    jdk = root / "unpacked" / "graalvm-test-jdk"
    for rel in ("bin/java", "bin/native-image", "lib/svm/bin/native-image"):
        target = jdk / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("#!/bin/sh\n", encoding="utf-8")
    return jdk


def _check(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["bash", str(TOOLS / "check-java-home.sh"), *args], capture_output=True, text=True, check=False)


def test_java_home_detection_picks_the_jdk_root_not_lib_svm(tmp_path: Path) -> None:
    jdk = _fake_jdk(tmp_path)
    found = _check("--find", str(tmp_path / "unpacked"))
    assert found.returncode == 0, found.stderr
    assert found.stdout.strip() == str(jdk)
    assert _check(str(jdk)).returncode == 0


def test_java_home_without_bin_java_fails_closed(tmp_path: Path) -> None:
    jdk = _fake_jdk(tmp_path)
    for bad in (str(jdk / "lib" / "svm"), str(tmp_path / "absent"), ""):
        result = _check(bad)
        assert result.returncode == 1 and "JAVA_HOME_INVALID" in result.stderr, bad
    (jdk / "bin" / "java").unlink()
    assert _check(str(jdk)).returncode == 1, "a root that lost bin/java is refused"
    assert _check("--find", str(tmp_path / "unpacked")).returncode == 1, "the lib/svm decoy alone is never a JAVA_HOME"


def test_install_script_uses_the_checked_root_and_hashes_from_stdin() -> None:
    text = (TOOLS / "install-graalvm.sh").read_text(encoding="utf-8")
    assert "check-java-home.sh" in text and "--find" in text
    assert "-name native-image" not in text, "the root is not derived from the first native-image found (that was lib/svm)"
    # sha256sum given a path with a backslash (a Windows RUNNER_TEMP) prints a leading backslash, which read as a checksum mismatch.
    assert 'sha256sum <"$archive"' in text and 'sha256sum "$archive"' not in text


def test_the_container_build_checks_java_home_on_host_and_inside(event_producer: dict[str, Any]) -> None:
    step = next(s for s in jobs(event_producer)["native"]["steps"] if s.get("name") == "Build the native image (Linux, manylinux_2_28 container)")
    run = str(step["run"])
    assert run.index('check-java-home.sh "$JAVA_HOME"') < run.index("docker run"), "the host JAVA_HOME is checked before the mount"
    assert "check-java-home.sh /graalvm" in run, "and the mount is checked inside the container"


def test_expect_failure_runs_the_java_home_controls() -> None:
    text = (TOOLS / "expect-failure.sh").read_text(encoding="utf-8")
    assert "javahome) javahome ;;" in text and "all) floor; tamper; module; sbom; javahome ;;" in text


def test_the_macos_link_targets_the_pinned_floor_on_the_command_line() -> None:
    gradle = (ROOT / "services" / "event-producer" / "launcher" / "build.gradle.kts").read_text(encoding="utf-8")
    assert "-H:NativeLinkerOption=-mmacosx-version-min=11.0" in gradle
    assert "-H:CCompilerOption=-mmacosx-version-min=11.0" in gradle
    macos = next(w for w in json.loads(DIST_SET.read_text(encoding="utf-8"))["platform_wheels"] if w["leg"] == "macos-arm64")
    assert "macosx_11_0" in macos["tag"], "the flag above and the wheel tag name the same floor"
