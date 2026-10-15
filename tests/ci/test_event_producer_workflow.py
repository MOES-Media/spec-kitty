"""Guard tests for ``event-producer.yml`` (which also builds the native image) and the shared smoke action (mission event-producer-reroute, WP01).

Every rule is a function over a parsed workflow document, so each is proved twice: the real file passes it, and a deep copy with one
mutation fails it for that rule. A rule that found nothing to judge fails (no vacuous pass). Only ``native-e2e`` (WP10) may run pytest:
the pin and glue tests run in the router lanes that own them, and a pytest call in any other job would be a duplicate per-change suite
execution (``tests/architectural/test_no_duplicate_suite_execution.py``). ``native-e2e`` is the one job that builds and installs the real
binary, and the only place ``REQUIRE_NATIVE_PRODUCER=1`` is set.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import shutil
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
E2E_JOB = "native-e2e"
E2E_FLAG = "REQUIRE_NATIVE_PRODUCER"
E2E_FILES = (
    "tests/integration/test_event_producer_e2e.py",
    "tests/integration/test_event_producer_degraded.py",
    "tests/perf/test_event_producer_overhead.py",
    "tests/integration/test_native_binary_helper.py",
)
#: Path filters WP10 added so a later change to the seam, the call sites, the e2e tests or the perf test still runs native-e2e.
E2E_PATH_GLOBS = (
    "tests/integration/test_event_producer_*.py",
    "tests/integration/_native_binary.py",
    "tests/integration/test_native_binary_helper.py",
    "tests/perf/test_event_producer_overhead.py",
    "tests/_perf_helpers.py",
    "src/specify_cli/status/adapters.py",
    "src/specify_cli/status/emit.py",
    "src/specify_cli/status/lifecycle_events.py",
    "src/specify_cli/coordination/outbound.py",
    "src/specify_cli/coordination/status_transition.py",
    "src/specify_cli/decisions/emit.py",
    "src/specify_cli/retrospective/lifecycle_events.py",
    "src/specify_cli/events/runtime_moments.py",
    "src/specify_cli/events/decision_log.py",
    "src/runtime/next/_internal_runtime/events.py",
    "src/runtime/next/runtime_bridge_retrospective.py",
    "src/runtime/next/_internal_runtime/engine.py",
    "src/specify_cli/cli/commands/_event_producer_doctor.py",
)
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
    """Run steps that call pytest in a job other than native-e2e."""
    return [text for name, job in jobs(document).items() if name != E2E_JOB for text in run_texts(job) if re.search(r"\bpytest\b", text)]


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


def test_no_job_other_than_native_e2e_runs_pytest(event_producer: dict[str, Any]) -> None:
    assert len(all_run_texts(event_producer)) > 10, "floor: the scan must see the run steps"
    assert pytest_violations(event_producer) == []
    assert any(re.search(r"\bpytest\b", text) for text in run_texts(jobs(event_producer)[E2E_JOB])), "floor: native-e2e is the job that does"


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
    assert set(on) == {"pull_request", "push", "workflow_dispatch", "workflow_call", "schedule"}
    assert on["schedule"] and all(entry["cron"] for entry in on["schedule"]), "the informational nightly (WP09, AC-036)"
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
    assert set(jobs(event_producer)[GATE_JOB]["needs"]) == {"verify-pins", "gradle-test", "native", "native-smoke", "native-e2e"}
    for dropped in ("verify-pins", "gradle-test", "native", "native-smoke", "native-e2e"):
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


# --- native-e2e (WP10 T083) ---------------------------------------------------------------------------------------


def e2e_violations(document: dict[str, Any]) -> list[str]:
    """Everything the native-e2e job must be: three legs, after native-smoke, the wheel installed by path, one pytest on the four named files."""
    all_jobs = jobs(document)
    if E2E_JOB not in all_jobs:
        return ["no native-e2e job"]
    job = all_jobs[E2E_JOB]
    problems = matrix_violations(job, "leg", THREE_LEGS)
    if matrix_values(job, "os") != THREE_OS:
        problems.append("native-e2e does not run on the three OS")
    if job.get("needs") != "native-smoke":
        problems.append("native-e2e does not need native-smoke")
    steps = job.get("steps", [])
    downloads = [s for s in steps if "download-artifact" in str(s.get("uses", ""))]
    if [d["with"]["name"] for d in downloads] != ["producer-${{ matrix.leg }}"]:
        problems.append("native-e2e does not download the leg's producer artifact")
    texts = " ".join(run_texts(job))
    if "HATCH_BUILD_HOOK_ENABLE_CUSTOM" not in str(steps) or "uv build --wheel" not in texts:
        problems.append("native-e2e does not build the platform wheel with the hook")
    if '--with "$E2E_WHEEL" pytest' not in " ".join(texts.split()):
        problems.append("native-e2e does not install the platform wheel by path (uv run --with)")
    pytest_steps = [s for s in steps if re.search(r"\bpytest\b", str(s.get("run", "")))]
    if len(pytest_steps) != 1:
        problems.append(f"native-e2e has {len(pytest_steps)} pytest steps, expected exactly one")
    else:
        command = " ".join(str(pytest_steps[0]["run"]).split())
        for named in E2E_FILES:
            if named not in command:
                problems.append(f"the pytest command does not name {named}")
        if str(pytest_steps[0].get("env", {}).get(E2E_FLAG)) != "1":
            problems.append(f"the pytest step does not set {E2E_FLAG}=1")
    return problems


def flag_setters(workflows: Path, actions: Path) -> list[str]:
    """Every (workflow or action file, job) whose text sets the fail-on-missing flag; a file-level mention outside a job is reported by file."""
    found = []
    for path in sorted([*workflows.glob("*.yml"), *actions.rglob("action.yml")]):
        document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for name, job in (document.get("jobs") or {}).items():
            if E2E_FLAG in yaml.safe_dump(job):
                found.append(f"{path.name}:{name}")
        if E2E_FLAG in yaml.safe_dump({k: v for k, v in document.items() if k != "jobs"}):
            found.append(f"{path.name}:<file level>")
    return found


def glob_violations(globs: list[str], tracked: list[str]) -> list[str]:
    """The asserted globs: a non-empty list, each present in the trigger paths and matching at least one tracked file."""
    if not globs:
        return ["the glob list is empty"]
    import fnmatch  # noqa: PLC0415

    return [f"{glob} matches no tracked file" for glob in globs if not any(fnmatch.fnmatch(path, glob) for path in tracked)]


def test_native_e2e_is_a_three_os_job_that_installs_the_wheel_and_runs_the_four_named_files_once(event_producer: dict[str, Any]) -> None:
    assert e2e_violations(event_producer) == []
    for mutate, expected in (
        (lambda d: d["jobs"][E2E_JOB]["strategy"]["matrix"]["include"].pop(), "legs are"),
        (lambda d: d["jobs"][E2E_JOB].__setitem__("needs", "native"), "does not need native-smoke"),
        (lambda d: d["jobs"][E2E_JOB]["steps"].append({"run": "uv run pytest tests/x.py"}), "pytest steps"),
        (
            lambda d: d["jobs"][E2E_JOB]["steps"].__setitem__(
                -1,
                {
                    "run": 'uv run --frozen --all-extras --with "$E2E_WHEEL" pytest tests/integration/test_event_producer_e2e.py',
                    "env": {E2E_FLAG: "1"},
                },
            ),
            "does not name",
        ),
    ):
        mutated = copy.deepcopy(event_producer)
        mutate(mutated)
        assert any(expected in problem for problem in e2e_violations(mutated)), expected


def test_the_fail_on_missing_flag_is_set_in_native_e2e_and_nowhere_else(tmp_path: Path) -> None:
    assert flag_setters(WORKFLOWS, ROOT / ".github" / "actions") == [f"event-producer.yml:{E2E_JOB}"]
    # Mutation: the same scan over a copy with the flag planted in another job of the file, and in another workflow.
    copied = tmp_path / "workflows"
    copied.mkdir()
    for path in WORKFLOWS.glob("*.yml"):
        shutil.copy2(path, copied / path.name)
    text = (copied / "event-producer.yml").read_text(encoding="utf-8")
    needle = "  native-smoke:\n    name: native-smoke (${{ matrix.leg }})\n    needs: native\n"
    assert needle in text
    (copied / "event-producer.yml").write_text(text.replace(needle, needle + "    env:\n      REQUIRE_NATIVE_PRODUCER: '1'\n"), encoding="utf-8")
    other = copied / "ci-nightly.yml"
    other.write_text(other.read_text(encoding="utf-8").replace("\njobs:\n", "\nenv:\n  REQUIRE_NATIVE_PRODUCER: '1'\njobs:\n", 1), encoding="utf-8")
    planted = flag_setters(copied, ROOT / ".github" / "actions")
    assert "event-producer.yml:native-smoke" in planted and "ci-nightly.yml:<file level>" in planted


def test_the_gate_waits_for_native_e2e(event_producer: dict[str, Any]) -> None:
    assert E2E_JOB in jobs(event_producer)[GATE_JOB]["needs"]
    mutated = copy.deepcopy(event_producer)
    mutated["jobs"][GATE_JOB]["needs"].remove(E2E_JOB)
    assert gate_violations(mutated) != []


def _tracked_files() -> list[str]:
    listing = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return listing.splitlines()


def test_the_path_filters_cover_the_seam_the_call_sites_and_the_tests_and_each_glob_matches_a_tracked_file(event_producer: dict[str, Any]) -> None:
    on = triggers(event_producer)
    for trigger in ("pull_request", "push"):
        assert set(E2E_PATH_GLOBS) <= set(on[trigger]["paths"]), trigger
    tracked = _tracked_files()
    assert glob_violations(list(E2E_PATH_GLOBS), tracked) == []
    assert glob_violations([], tracked) == ["the glob list is empty"]
    assert glob_violations([*E2E_PATH_GLOBS, "src/specify_cli/events/no_such_module.py"], tracked) == [
        "src/specify_cli/events/no_such_module.py matches no tracked file"
    ]
    mutated = copy.deepcopy(on["pull_request"]["paths"])
    mutated.remove("src/specify_cli/status/adapters.py")
    assert not set(E2E_PATH_GLOBS) <= set(mutated), "mutation: dropping one glob is detected"


def test_the_duplicate_suite_ledger_and_the_delegate_exemption_name_native_e2e_and_it_still_runs_pytest(event_producer: dict[str, Any]) -> None:
    from tests.architectural import _gate_coverage  # noqa: PLC0415
    from tests.architectural.test_no_duplicate_suite_execution import AUTHORIZED_PER_CHANGE_SUITE_JOBS  # noqa: PLC0415

    assert ("event-producer.yml", E2E_JOB) in AUTHORIZED_PER_CHANGE_SUITE_JOBS
    assert ("event-producer.yml", E2E_JOB) in _gate_coverage.DELEGATE_SUITE_JOBS_BY_DESIGN
    assert set(_gate_coverage.DELEGATE_SUITE_JOBS_BY_DESIGN) == {("event-producer.yml", E2E_JOB)}, "the exemption is exactly this one job"
    assert any(re.search(r"\bpytest\b", text) for text in run_texts(jobs(event_producer)[E2E_JOB])), "a stale exemption must be deleted"


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


def _bare_root(root: Path, files: tuple[str, ...]) -> Path:
    jdk = root / "bare-jdk"
    for rel in files:
        target = jdk / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("#!/bin/sh\n", encoding="utf-8")
    return jdk


def _refuses_root_without_native_image(script: Path, jdk: Path) -> bool:
    result = subprocess.run(["bash", str(script), str(jdk)], capture_output=True, text=True, check=False)
    return result.returncode == 1 and "JAVA_HOME_INVALID" in result.stderr


def test_java_home_root_without_native_image_is_refused(tmp_path: Path) -> None:
    """A root holding bin/java (or java.exe) but neither bin/native-image nor native-image.cmd is not a JAVA_HOME."""
    script = TOOLS / "check-java-home.sh"
    for launchers in (("bin/java",), ("bin/java.exe",)):
        assert _refuses_root_without_native_image(script, _bare_root(tmp_path / launchers[0].replace("/", "_"), launchers))
    complete = _bare_root(tmp_path / "complete", ("bin/java", "bin/native-image.cmd"))
    assert subprocess.run(["bash", str(script), str(complete)], capture_output=True, text=True, check=False).returncode == 0


def test_mutation_a_root_check_that_drops_the_native_image_requirement_is_caught(tmp_path: Path) -> None:
    text = (TOOLS / "check-java-home.sh").read_text(encoding="utf-8")
    needle = ' && { [ -f "$d/bin/native-image" ] || [ -f "$d/bin/native-image.cmd" ]; }'
    assert text.count(needle) == 1, "the native-image clause moved: update this control"
    mutated = tmp_path / "check-java-home.sh"
    mutated.write_text(text.replace(needle, ""), encoding="utf-8")
    jdk = _bare_root(tmp_path, ("bin/java",))
    assert not _refuses_root_without_native_image(mutated, jdk), "the control must go red: the mutated script accepts a root without native-image"


WRAPPER_PROPERTIES = ROOT / "services" / "event-producer" / "gradle" / "wrapper" / "gradle-wrapper.properties"
PINS_JSON = TOOLS / "pins.json"


def wrapper_sha_violations(properties: str, pins: dict[str, Any]) -> list[str]:
    """Findings when the wrapper's distributionSha256Sum is absent or differs from the pinned Gradle archive sha256."""
    gradle = [tool for tool in pins["tools"] if tool["name"] == "gradle"]
    if len(gradle) != 1:
        return ["pins.json does not hold exactly one gradle tool"]
    found = re.findall(r"^distributionSha256Sum=(\S*)\s*$", properties, flags=re.MULTILINE)
    if len(found) != 1:
        return [f"expected one distributionSha256Sum line, found {len(found)}"]
    if found[0] != gradle[0]["sha256"]:
        return [f"distributionSha256Sum {found[0]} differs from the pinned gradle sha256 {gradle[0]['sha256']}"]
    return []


def test_the_wrapper_checksum_equals_the_pinned_gradle_sha() -> None:
    pins = json.loads(PINS_JSON.read_text(encoding="utf-8"))
    assert wrapper_sha_violations(WRAPPER_PROPERTIES.read_text(encoding="utf-8"), pins) == []


def test_mutation_a_corrupted_or_missing_wrapper_checksum_is_red() -> None:
    pins = json.loads(PINS_JSON.read_text(encoding="utf-8"))
    real = WRAPPER_PROPERTIES.read_text(encoding="utf-8")
    sha = next(tool["sha256"] for tool in pins["tools"] if tool["name"] == "gradle")
    corrupted = real.replace(sha, sha[:-1] + ("0" if sha[-1] != "0" else "1"))
    assert corrupted != real and wrapper_sha_violations(corrupted, pins)
    assert wrapper_sha_violations(real.replace(f"distributionSha256Sum={sha}\n", ""), pins)


def test_install_script_uses_the_checked_root_and_hashes_from_stdin() -> None:
    text = (TOOLS / "install-graalvm.sh").read_text(encoding="utf-8")
    assert "check-java-home.sh" in text and "--find" in text
    assert "-name native-image" not in text, "the root is not derived from the first native-image found (that was lib/svm)"
    # sha256sum given a path with a backslash (a Windows RUNNER_TEMP) prints a leading backslash, which read as a checksum mismatch.
    assert 'sha256sum <"$archive"' in text and 'sha256sum "$archive"' not in text


_INSTALL_TOOLS = ("jq", "curl", "sha256sum", "cut", "head", "dirname", "mkdir", "rm", "find", "awk", "tar", "uname")


def _install_without(tmp_path: Path, *absent: str) -> subprocess.CompletedProcess[str]:
    """Run install-graalvm.sh against a zip pin with a PATH holding only symlinks to the tools it needs, minus `absent`.

    The Windows runner failed with a bare 127 because 7z was not on the Git Bash PATH. With the tool missing the script must stop
    before any download and say which tool it is.
    """
    bindir = tmp_path / "bin"
    bindir.mkdir()
    for tool in _INSTALL_TOOLS:
        found = shutil.which(tool)
        if found and tool not in absent:
            (bindir / tool).symlink_to(found)
    pins = tmp_path / "pins.json"
    pins.write_text(
        json.dumps({"tools": [{"name": "t", "kind": "jdk-archive", "version": "jdk-1", "url": "https://example.invalid/x.zip", "sha256": "0" * 64}]}),
        encoding="utf-8",
    )
    bash = shutil.which("bash")
    assert bash
    env = {"PATH": str(bindir), "HOME": str(tmp_path)}
    return subprocess.run([bash, str(TOOLS / "install-graalvm.sh"), "t", str(tmp_path / "dest"), str(pins)], capture_output=True, text=True, check=False, env=env)


@pytest.mark.parametrize("tool", ["jq", "curl"])
def test_install_script_names_a_missing_tool_instead_of_a_bare_127(tmp_path: Path, tool: str) -> None:
    result = _install_without(tmp_path, tool)
    assert result.returncode == 2, (result.returncode, result.stderr)
    assert f"install-graalvm: MISSING_TOOL {tool}" in result.stderr


def test_install_script_without_any_zip_extractor_fails_by_name_before_downloading(tmp_path: Path) -> None:
    result = _install_without(tmp_path, "7z", "unzip", "tar")
    assert result.returncode == 2, (result.returncode, result.stderr)
    assert "install-graalvm: MISSING_TOOL 7z" in result.stderr
    assert not (tmp_path / "dest" / "graalvm-archive").exists(), "nothing was downloaded"


def _install_with_a_failing_extractor(tmp_path: Path, script: Path) -> subprocess.CompletedProcess[str]:
    """Run an install script whose zip extractor (a stub `7z`) prints a line and then execs a program that does not exist: exit 127.

    A fake curl supplies the archive so the checksum passes and the run reaches the extractor. No stderr is redirected by the test.
    """
    bindir = tmp_path / "bin"
    bindir.mkdir()
    for tool in (*_INSTALL_TOOLS, "cat", "env", "bash", "sh"):
        found = shutil.which(tool)
        if found and tool != "curl":
            (bindir / tool).symlink_to(found)
    payload = b"not really a zip"
    curl_stub = '#!/usr/bin/env bash\nwhile [ "$#" -gt 0 ]; do [ "$1" = "--output" ] && out="$2"; shift; done\nprintf %s "' + payload.decode() + '" > "$out"\n'
    (bindir / "curl").write_text(curl_stub, encoding="utf-8")
    (bindir / "7z").write_text("#!/usr/bin/env bash\necho extractor-said-hello\nexec /nonexistent/definitely-missing-tool\n", encoding="utf-8")
    for stub in ("curl", "7z"):
        (bindir / stub).chmod(0o755)
    pins = tmp_path / "pins.json"
    digest = hashlib.sha256(payload).hexdigest()  # noqa: TID251 -- file-integrity digest of a stub archive, not a charter hash
    entry = {"name": "t", "kind": "jdk-archive", "version": "jdk-1", "url": "https://example.invalid/x.zip", "sha256": digest}
    pins.write_text(json.dumps({"tools": [entry]}), encoding="utf-8")
    bash = shutil.which("bash")
    assert bash
    env = {"PATH": str(bindir), "HOME": str(tmp_path)}
    return subprocess.run([bash, str(script), "t", str(tmp_path / "dest"), str(pins)], capture_output=True, text=True, check=False, env=env)


def test_a_failing_extractor_is_never_silent_exit_127(tmp_path: Path) -> None:
    result = _install_with_a_failing_extractor(tmp_path, TOOLS / "install-graalvm.sh")
    assert result.returncode == 127, (result.returncode, result.stderr)
    assert "install-graalvm: FAILED rc=127 at line" in result.stderr, result.stderr
    assert "7z x -y" in result.stderr, "the FAILED line names the command that failed"
    assert "extractor-said-hello" in result.stderr, "the extractor's own output is printed, not discarded"


def test_every_install_script_traps_errors_with_the_failing_command() -> None:
    for script, label in (("install-graalvm.sh", "install-graalvm"), ("check-java-home.sh", "check-java-home")):
        text = (TOOLS / script).read_text(encoding="utf-8")
        assert "set -Eeuo pipefail" in text
        assert f'echo "{label}: FAILED rc=$rc at line $LINENO: $BASH_COMMAND" >&2' in text
    assert ">/dev/null" not in "".join(
        line for line in (TOOLS / "install-graalvm.sh").read_text(encoding="utf-8").splitlines(keepends=True) if "run_logged" in line or "tar -xzf" in line
    )


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


def _install_fake_jdk(tmp_path: Path, launchers: tuple[str, ...]) -> subprocess.CompletedProcess[str]:
    """Run the real install-graalvm.sh against a tar.gz pin (a stub curl serves the archive) whose JDK holds only `launchers` under bin/.

    native-image stubs print a GraalVM-style version line, so the pinned-version check passes only when the script can execute the launcher.
    """
    import tarfile

    jdk = tmp_path / "src" / "graalvm-test-jdk"
    (jdk / "bin").mkdir(parents=True)
    for rel in launchers:
        stub = jdk / "bin" / rel
        stub.write_text("#!/bin/sh\necho 'native-image 25.0.2 2026-01-20'\n", encoding="utf-8")
        stub.chmod(0o755)
    archive = tmp_path / "jdk.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(jdk, arcname="graalvm-test-jdk")
    pins = tmp_path / "pins.json"
    pins.write_text(
        json.dumps(
            {
                "tools": [
                    {
                        "name": "t",
                        "kind": "jdk-archive",
                        "version": "jdk-25.0.2",
                        "url": "https://example.invalid/x.tar.gz",
                        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),  # noqa: TID251 — checksum of a synthetic test archive, not charter freshness hashing
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    curl = stubs / "curl"
    curl.write_text(f'#!/bin/sh\nwhile [ "$1" != "--output" ]; do shift; done\ncp "{archive}" "$2"\n', encoding="utf-8")
    curl.chmod(0o755)
    env = {**os.environ, "PATH": f"{stubs}{os.pathsep}{os.environ['PATH']}"}
    env.pop("GITHUB_ENV", None)
    env.pop("GITHUB_PATH", None)
    return subprocess.run(["bash", str(TOOLS / "install-graalvm.sh"), "t", str(tmp_path / "dest"), str(pins)], capture_output=True, text=True, check=False, env=env)


def test_install_checks_the_version_through_the_windows_cmd_and_exe_launchers(tmp_path: Path) -> None:
    """GraalVM on Windows has bin/native-image.cmd and bin/java.exe only; the bare path does not exist (rc=127 on the Windows legs)."""
    result = _install_fake_jdk(tmp_path, ("native-image.cmd", "java.exe"))
    assert result.returncode == 0, result.stderr
    assert "install-graalvm: t jdk-25.0.2" in result.stdout


def test_install_checks_the_version_through_the_bare_linux_launchers(tmp_path: Path) -> None:
    result = _install_fake_jdk(tmp_path, ("native-image", "java"))
    assert result.returncode == 0, result.stderr


def _smoke_step(name_prefix: str) -> dict[str, Any]:
    steps = load(SMOKE_ACTION)["runs"]["steps"]
    return next(step for step in steps if step["name"].startswith(name_prefix))


def test_binary_mode_locates_the_native_dir_without_importing_the_package() -> None:
    """Importing specify_cli runs its __init__, which needs typer; the dependency-less venv of binary mode has none."""
    run = _smoke_step("Binary mode")["run"]
    assert not re.search(r"\bimport\b[^'\"\n]*\bspecify_cli\b", run), "binary mode must not import specify_cli"
    assert "specify_cli.__file__" not in run
    assert "find_spec('specify_cli')" in run and "submodule_search_locations" in run


def test_cli_mode_installs_the_wheel_with_its_dependencies() -> None:
    run = _smoke_step("Install the wheel")["run"]
    cli_branch = run.split('if [ "$MODE" = "cli" ]; then', 1)[1].split("else", 1)[0]
    assert "pip install" in cli_branch and "--no-deps" not in cli_branch
    assert "--no-deps" in run.split("else", 1)[1], "binary mode keeps the dependency-less install"
