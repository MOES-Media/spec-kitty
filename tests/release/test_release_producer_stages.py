"""Stage rules of ``release.yml`` and the nightly producer job (mission event-producer-reroute, WP09).

Every rule is a function over a parsed workflow document, so each is proved twice: the real file passes it, and a deep copy with one
mutation fails it for that rule. A rule that found nothing to judge fails (no vacuous pass).

What this file can and cannot show. These are static tests of the workflow graph. ``release.yml`` runs on a tag push or a
``workflow_dispatch`` of the default-branch copy only, and a ``workflow_run`` or ``workflow_call`` follower runs the default-branch
copy as well, so none of the stages below executes on a pull request. The checks they call are executable and tested elsewhere
(``tests/release/test_check_dist_set.py``, ``test_check_exact_install.py``, the shared smoke action in ``native-smoke``); the first real
execution of the stages is the first 4.0 release candidate. Nothing here claims otherwise.
"""

from __future__ import annotations

import copy
import re
from collections import Counter
from pathlib import Path
from typing import Any

import pytest
import yaml

pytestmark = pytest.mark.fast

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
RELEASE = WORKFLOWS / "release.yml"
NIGHTLY = WORKFLOWS / "ci-nightly.yml"
EVENT_PRODUCER = WORKFLOWS / "event-producer.yml"
RELEASE_CHECKLIST = ROOT / "RELEASE_CHECKLIST.md"
CONTRIBUTING = ROOT / "CONTRIBUTING.md"

SMOKE_ACTION = "./.github/actions/producer-smoke"
PRODUCER_WORKFLOW = "./.github/workflows/event-producer.yml"
RELEASE_REF = "${{ github.event_name == 'workflow_dispatch' && inputs.tag || github.ref_name }}"
PINNED = re.compile(r"^[\w.-]+/[\w.-]+(?:/[\w./-]+)?@[0-9a-f]{40}$")

# The tag-pinned `uses:` lines that existed on the base commit of this mission, with their counts. They are held, not re-pinned (out of
# scope unless the operator asks). Any further tag-pinned line, or one more of these, is a new unpinned action and fails the rule.
BASE_TAG_PINNED_RELEASE = Counter(
    {
        "actions/checkout@v6": 4,
        "actions/setup-python@v6": 4,
        "actions/upload-artifact@v7": 2,
        "actions/download-artifact@v8": 1,
    }
)
BASE_TAG_PINNED_NIGHTLY: Counter[str] = Counter()

NIGHTLY_JOB = "producer-nightly"
THREE_OS = {"ubuntu-24.04", "macos-14", "windows-2022"}


def load(path: Path) -> dict[Any, Any]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return document


def jobs(document: dict[Any, Any]) -> dict[str, Any]:
    found = document["jobs"]
    assert found, "the workflow has no jobs: the rule would pass vacuously"
    return found  # type: ignore[no-any-return]


def triggers(document: dict[Any, Any]) -> set[str]:
    on = document.get("on", document.get(True, {}))  # PyYAML reads the key `on` as True
    return {on} if isinstance(on, str) else set(on)


def steps(job: dict[str, Any]) -> list[dict[str, Any]]:
    return list(job.get("steps", []))


def step_named(job: dict[str, Any], fragment: str) -> dict[str, Any] | None:
    return next((step for step in steps(job) if fragment in str(step.get("name", ""))), None)


def step_index(job: dict[str, Any], fragment: str) -> int:
    for index, step in enumerate(steps(job)):
        if fragment in str(step.get("name", "")):
            return index
    return -1


def needs_of(job: dict[str, Any]) -> list[str]:
    needs = job.get("needs", [])
    return [needs] if isinstance(needs, str) else list(needs)


def all_uses(document: dict[Any, Any]) -> list[str]:
    found: list[str] = []
    for job in jobs(document).values():
        if "uses" in job:
            found.append(str(job["uses"]))
        found.extend(str(step["uses"]) for step in steps(job) if "uses" in step)
    return found


# --- rules ------------------------------------------------------------------------------------------------------------


def unpinned_uses_violations(document: dict[Any, Any], held: Counter[str]) -> list[str]:
    """Every `uses:` that is neither a local path, nor owner/repo@<40-hex>, nor one of the held base-commit lines (counted)."""
    seen: Counter[str] = Counter()
    problems: list[str] = []
    for use in all_uses(document):
        if use.startswith("./") or PINNED.match(use):
            continue
        seen[use] += 1
        if seen[use] > held[use]:
            problems.append(f"new action not pinned to a 40-hex commit: {use}")
    return problems


def continue_on_error_violations(document: dict[Any, Any]) -> list[str]:
    found = [name for name, job in jobs(document).items() if job.get("continue-on-error")]
    found += [f"{name}/{step.get('name')}" for name, job in jobs(document).items() for step in steps(job) if step.get("continue-on-error")]
    return found


def trigger_violations(document: dict[Any, Any], allowed: set[str]) -> list[str]:
    """No `pull_request` or branch-push trigger: these workflows are rows of NON_CHANGE_TRIGGERED_WORKFLOWS."""
    extra = sorted(triggers(document) - allowed)
    on = document.get("on", document.get(True, {}))
    push = on.get("push", {}) if isinstance(on, dict) else {}
    if isinstance(push, dict) and "branches" in push:
        extra.append("push.branches")
    return extra


def build_producer_violations(document: dict[Any, Any]) -> list[str]:
    job = jobs(document).get("build-producer")
    if job is None:
        return ["no build-producer job"]
    problems: list[str] = []
    if job.get("uses") != PRODUCER_WORKFLOW:
        problems.append(f"build-producer calls {job.get('uses')!r}, not {PRODUCER_WORKFLOW}")
    with_ = job.get("with", {})
    if with_.get("mode") != "release":
        problems.append("build-producer does not pass mode: release")
    if with_.get("ref") != RELEASE_REF:
        problems.append("build-producer ref input is not the RELEASE_REF expression repeated verbatim (env is unavailable in a job-level with)")
    if document["env"]["RELEASE_REF"] != RELEASE_REF:
        problems.append("the workflow-level RELEASE_REF differs from the text repeated in build-producer.with.ref")
    if needs_of(job) != ["nightly-gate"]:
        problems.append(f"build-producer needs {needs_of(job)}, expected the nightly gate only")
    condition = str(job.get("if", ""))
    if "nightly-gate" not in condition or "skip_nightly" not in condition:
        problems.append("build-producer does not honour the nightly-gate waiver condition")
    return problems


BUILD_ORDER = (
    "Build distributions",
    "Download the producer legs",
    "Build the platform wheels",
    "Verify checksum continuity",
    "Verify the platform-wheel binaries",
    "Verify exact installability",
    "Verify wheel contents",
    "Check distributions",
)


STEP_CALLS = (
    (
        "Verify checksum continuity",
        ("check_dist_set.py", "--verify-manifests", "git rev-parse HEAD"),
        "the manifest step does not call check_dist_set.py --verify-manifests against git rev-parse HEAD",
    ),
    (
        "Verify the platform-wheel binaries",
        ("check_dist_set.py", "--verify-binaries"),
        "the binary step does not call check_dist_set.py --verify-binaries",
    ),
    (
        "Verify exact installability",
        ("check_exact_install.py", "--wheel-tag"),
        "the exact-install step does not select a wheel with --wheel-tag",
    ),
)


def step_call_problems(job: dict[str, Any], table: tuple[tuple[str, tuple[str, ...], str], ...]) -> list[str]:
    """For each (step name fragment, required tokens, message): a present step whose `run` lacks a token is a problem."""
    problems: list[str] = []
    for fragment, tokens, message in table:
        step = step_named(job, fragment)
        if step is not None and not all(token in str(step.get("run", "")) for token in tokens):
            problems.append(message)
    return problems


def build_release_violations(document: dict[Any, Any]) -> list[str]:
    job = jobs(document)["build-release"]
    problems: list[str] = []
    if "build-producer" not in needs_of(job) or "nightly-gate" not in needs_of(job):
        problems.append(f"build-release needs {needs_of(job)}, expected nightly-gate and build-producer")
    if "build-producer" not in str(job.get("if", "")) or "'success'" not in str(job.get("if", "")):
        problems.append("build-release if: does not require build-producer to have succeeded")
    positions = [step_index(job, name) for name in BUILD_ORDER]
    for name, position in zip(BUILD_ORDER, positions, strict=True):
        if position < 0:
            problems.append(f"no build-release step named like {name!r}")
    if all(position >= 0 for position in positions) and positions != sorted(positions):
        problems.append(f"build-release steps out of order: {dict(zip(BUILD_ORDER, positions, strict=True))}")
    pure = step_named(job, "Build distributions")
    if pure is not None and str(pure.get("run", "")).strip() != "python -m build":
        problems.append("the pure wheel and sdist build is not the unchanged `python -m build`")
    platform = step_named(job, "Build the platform wheels")
    if platform is not None:
        run = str(platform.get("run", ""))
        for needle in (
            "--wheel --outdir dist",
            "HATCH_BUILD_HOOK_ENABLE_CUSTOM=true",
            "SPEC_KITTY_PRODUCER_BINARY=",
            "SPEC_KITTY_PRODUCER_PLATFORM_TAG=",
            "rm -rf build",
            "dist-set.json",
        ):
            if needle not in run:
                problems.append(f"platform-wheel step lacks {needle!r}")
        if "sdist" in run:
            problems.append("platform wheels must not be built through an sdist argument")
    problems.extend(step_call_problems(job, STEP_CALLS))
    if not any("uv lock --check" in str(step.get("run", "")) for step in steps(job)):
        problems.append("no `uv lock --check` step")
    download = step_named(job, "Download the producer legs")
    if download is not None and not PINNED.match(str(download.get("uses", ""))):
        problems.append("the producer-leg download is not SHA-pinned")
    return problems


def smoke_wheels_violations(document: dict[Any, Any]) -> list[str]:
    job = jobs(document).get("smoke-wheels")
    if job is None:
        return ["no smoke-wheels job"]
    problems: list[str] = []
    if "build-release" not in needs_of(job):
        problems.append("smoke-wheels does not need build-release")
    include = job.get("strategy", {}).get("matrix", {}).get("include", [])
    systems = {str(entry.get("os")) for entry in include}
    if systems != THREE_OS:
        problems.append(f"smoke-wheels runs on {sorted(systems)}, expected {sorted(THREE_OS)}")
    produced = [entry for entry in include if entry.get("expect") == "produced"]
    missing = [entry for entry in include if entry.get("expect") == "binary-missing"]
    if len(produced) != 3 or len(missing) != 1:
        problems.append("smoke-wheels needs three `produced` legs and one `binary-missing` (pure wheel) leg")
    if job.get("strategy", {}).get("fail-fast") is not False:
        problems.append("smoke-wheels must set fail-fast: false")
    action = next((step for step in steps(job) if step.get("uses") == SMOKE_ACTION), None)
    if action is None:
        problems.append(f"smoke-wheels does not use {SMOKE_ACTION}")
    else:
        with_ = action.get("with", {})
        if with_.get("mode") != "cli" or "matrix.expect" not in str(with_.get("expect", "")) or not with_.get("command"):
            problems.append("smoke-wheels must run the shared action in cli mode with matrix.expect and a command")
    return problems


def publish_violations(document: dict[Any, Any]) -> list[str]:
    job = jobs(document)["publish-pypi"]
    problems: list[str] = []
    for needed in ("build-release", "smoke-wheels"):
        if needed not in needs_of(job):
            problems.append(f"publish-pypi does not need {needed}")
        if f"needs.{needed}.result == 'success'" not in str(job.get("if", "")):
            problems.append(f"publish-pypi if: does not require {needed} to have succeeded")
    set_check = step_named(job, "Re-check the distribution set")
    if set_check is None or not all(token in str(set_check.get("run", "")) for token in ("check_dist_set.py", "--verify-binaries")):
        problems.append("publish-pypi does not re-run check_dist_set.py --verify-binaries on the downloaded artifact")
    sbom = step_named(job, "Check the producer SBOM")
    if sbom is None or "check-sbom.sh" not in str(sbom.get("run", "")):
        problems.append("publish-pypi does not re-run check-sbom.sh on the downloaded producer-sbom")
    attest = step_named(job, "Attest")
    if attest is None or not str(attest.get("uses", "")).startswith("actions/attest-build-provenance@") or not PINNED.match(str(attest.get("uses", ""))):
        problems.append("publish-pypi has no SHA-pinned attest-build-provenance step")
    permissions = job.get("permissions", {})
    for scope in ("id-token", "attestations"):
        if permissions.get(scope) != "write":
            problems.append(f"publish-pypi lacks permissions.{scope}: write")
    order = [
        step_index(job, name) for name in ("Re-check the distribution set", "Check the producer SBOM", "Attest", "Create GitHub Release", "Publish distributions")
    ]
    if any(position < 0 for position in order) or order != sorted(order):
        problems.append(f"publish-pypi checks, attestation and publication are out of order: {order}")
    release = step_named(job, "Create GitHub Release")
    if release is not None and "producer-sbom" not in str(release.get("with", {}).get("files", "")):
        problems.append("the GitHub release does not attach the producer SBOM")
    return problems


def nightly_violations(document: dict[Any, Any]) -> list[str]:
    job = jobs(document).get(NIGHTLY_JOB)
    if job is None:
        return [f"no {NIGHTLY_JOB} job"]
    problems: list[str] = []
    if job.get("uses") != PRODUCER_WORKFLOW:
        problems.append(f"{NIGHTLY_JOB} calls {job.get('uses')!r}, not {PRODUCER_WORKFLOW}")
    if NIGHTLY_JOB in needs_of(jobs(document)["nightly-summary"]):
        problems.append(f"{NIGHTLY_JOB} is in nightly-summary.needs: a hosted-runner flake would block the fail-closed nightly-gate")
    if job.get("continue-on-error"):
        problems.append(f"{NIGHTLY_JOB} uses continue-on-error (it is non-gating by omission from needs, not by masking)")
    return problems


# --- the real files pass ----------------------------------------------------------------------------------------------


@pytest.fixture(name="release")
def release_fixture() -> dict[Any, Any]:
    return load(RELEASE)


@pytest.fixture(name="nightly")
def nightly_fixture() -> dict[Any, Any]:
    return load(NIGHTLY)


def test_every_new_action_of_release_and_nightly_is_sha_pinned(release: dict[Any, Any], nightly: dict[Any, Any]) -> None:
    assert unpinned_uses_violations(release, BASE_TAG_PINNED_RELEASE) == []
    assert unpinned_uses_violations(nightly, BASE_TAG_PINNED_NIGHTLY) == []
    assert any(PINNED.match(use) for use in all_uses(release)), "floor: the release workflow pins at least one action by SHA"


def test_no_stage_masks_a_failure(release: dict[Any, Any]) -> None:
    """Standing Order 9 for the release workflow (the nightly file keeps older non-gating steps; its new job is checked separately)."""
    assert continue_on_error_violations(release) == []


def test_release_and_nightly_stay_non_change_triggered(release: dict[Any, Any], nightly: dict[Any, Any]) -> None:
    assert trigger_violations(release, {"push", "workflow_dispatch"}) == []
    assert trigger_violations(nightly, {"schedule", "workflow_dispatch"}) == []


def test_build_producer_calls_the_producer_workflow_on_the_release_ref(release: dict[Any, Any]) -> None:
    assert build_producer_violations(release) == []


def test_build_release_order_and_calls(release: dict[Any, Any]) -> None:
    assert build_release_violations(release) == []


def test_smoke_wheels_runs_the_shared_action_on_three_os_and_the_pure_wheel(release: dict[Any, Any]) -> None:
    assert smoke_wheels_violations(release) == []


def test_publish_is_unreachable_unless_build_and_smoke_succeeded(release: dict[Any, Any]) -> None:
    assert publish_violations(release) == []


def test_verify_pypi_installability_still_follows_publish(release: dict[Any, Any]) -> None:
    assert needs_of(jobs(release)["verify-pypi-installability"]) == ["publish-pypi"]


def test_native_smoke_and_smoke_wheels_share_one_action(release: dict[Any, Any]) -> None:
    """Drift between the PR-time and the release-time check is a test failure: both call the same composite action."""
    native = jobs(load(EVENT_PRODUCER))["native-smoke"]
    assert any(step.get("uses") == SMOKE_ACTION for step in steps(native))
    assert any(step.get("uses") == SMOKE_ACTION for step in steps(jobs(release)["smoke-wheels"]))


def test_nightly_producer_job_is_informational_and_says_why(nightly: dict[Any, Any]) -> None:
    assert nightly_violations(nightly) == []
    text = NIGHTLY.read_text(encoding="utf-8")
    comment = text[text.index(f"  {NIGHTLY_JOB}:") - 1800 : text.index(f"  {NIGHTLY_JOB}:")]
    assert "non-gating" in comment and "nightly-gate" in comment and "smoke-wheels" in comment, "the job must say why it is outside nightly-summary.needs"


def test_docs_state_the_unproven_stages_and_the_unsigned_binaries() -> None:
    checklist = RELEASE_CHECKLIST.read_text(encoding="utf-8")
    for needle in ("build-producer", "smoke-wheels", "Gatekeeper", "SmartScreen", "Test-PyPI", "unproven"):
        assert needle in checklist, f"RELEASE_CHECKLIST.md does not mention {needle}"
    contributing = CONTRIBUTING.read_text(encoding="utf-8")
    for needle in ("Gatekeeper", "SmartScreen", "platform wheels"):
        assert needle in contributing, f"CONTRIBUTING.md does not mention {needle}"


# --- one mutation per rule: each must turn the rule red ---------------------------------------------------------------


def mutate(document: dict[Any, Any]) -> dict[Any, Any]:
    return copy.deepcopy(document)


def test_mutation_a_tag_pinned_new_action_fails_the_pin_rule(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["smoke-wheels"]["steps"].append({"name": "x", "uses": "some/action@v1"})
    jobs(mutated)["smoke-wheels"]["steps"].append({"name": "y", "uses": "actions/checkout@v6"})  # one more than the base commit holds
    assert unpinned_uses_violations(mutated, BASE_TAG_PINNED_RELEASE) == [
        "new action not pinned to a 40-hex commit: some/action@v1",
        "new action not pinned to a 40-hex commit: actions/checkout@v6",
    ]


def test_mutation_continue_on_error_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["smoke-wheels"]["continue-on-error"] = True
    assert continue_on_error_violations(mutated) == ["smoke-wheels"]


def test_mutation_a_pull_request_trigger_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    mutated[True if True in mutated else "on"]["pull_request"] = {}  # PyYAML reads the key `on` as True
    assert trigger_violations(mutated, {"push", "workflow_dispatch"}) == ["pull_request"]


def test_mutation_build_producer_ref_drift_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["build-producer"]["with"]["ref"] = "${{ github.ref_name }}"
    assert any("RELEASE_REF expression" in problem for problem in build_producer_violations(mutated))


def test_mutation_build_producer_in_pr_mode_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["build-producer"]["with"]["mode"] = "pr"
    assert "build-producer does not pass mode: release" in build_producer_violations(mutated)


def test_mutation_build_release_without_build_producer_edge_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["build-release"]["needs"] = ["nightly-gate"]
    assert any("build-release needs" in problem for problem in build_release_violations(mutated))


def test_mutation_swapping_two_build_steps_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    build_steps = jobs(mutated)["build-release"]["steps"]
    a, b = step_index(jobs(mutated)["build-release"], "Build the platform wheels"), step_index(jobs(mutated)["build-release"], "Verify checksum continuity")
    build_steps[a], build_steps[b] = build_steps[b], build_steps[a]
    assert any("out of order" in problem for problem in build_release_violations(mutated))


def test_mutation_platform_wheel_through_sdist_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    step = step_named(jobs(mutated)["build-release"], "Build the platform wheels")
    assert step is not None
    step["run"] = str(step["run"]).replace("--wheel --outdir dist", "--sdist --wheel --outdir dist")
    problems = build_release_violations(mutated)
    assert "platform wheels must not be built through an sdist argument" in problems or "platform-wheel step lacks '--wheel --outdir dist'" in problems


def test_mutation_dropping_the_manifest_check_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    job = jobs(mutated)["build-release"]
    job["steps"] = [step for step in steps(job) if "Verify checksum continuity" not in str(step.get("name", ""))]
    assert any("Verify checksum continuity" in problem for problem in build_release_violations(mutated))


def test_mutation_dropping_the_binary_check_flag_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    step = step_named(jobs(mutated)["build-release"], "Verify the platform-wheel binaries")
    assert step is not None
    step["run"] = str(step["run"]).replace("--verify-binaries", "")
    assert "the binary step does not call check_dist_set.py --verify-binaries" in build_release_violations(mutated)


def test_mutation_exact_install_without_wheel_tag_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    step = step_named(jobs(mutated)["build-release"], "Verify exact installability")
    assert step is not None
    step["run"] = str(step["run"]).replace("--wheel-tag", "")
    assert "the exact-install step does not select a wheel with --wheel-tag" in build_release_violations(mutated)


def test_mutation_smoke_wheels_off_the_shared_action_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    for step in steps(jobs(mutated)["smoke-wheels"]):
        if step.get("uses") == SMOKE_ACTION:
            step["uses"] = "./.github/actions/another-smoke"
    assert f"smoke-wheels does not use {SMOKE_ACTION}" in smoke_wheels_violations(mutated)


def test_mutation_smoke_wheels_losing_a_leg_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    include = jobs(mutated)["smoke-wheels"]["strategy"]["matrix"]["include"]
    include[:] = [entry for entry in include if entry["os"] != "windows-2022"]
    assert any("smoke-wheels runs on" in problem for problem in smoke_wheels_violations(mutated))


def test_mutation_smoke_wheels_without_the_pure_leg_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    include = jobs(mutated)["smoke-wheels"]["strategy"]["matrix"]["include"]
    include[:] = [entry for entry in include if entry["expect"] != "binary-missing"]
    assert any("three `produced` legs and one" in problem for problem in smoke_wheels_violations(mutated))


def test_mutation_publish_without_smoke_edge_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["publish-pypi"]["needs"] = ["build-release"]
    assert "publish-pypi does not need smoke-wheels" in publish_violations(mutated)


def test_mutation_publish_condition_ignoring_smoke_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["publish-pypi"]["if"] = "${{ !cancelled() && needs.build-release.result == 'success' }}"
    assert "publish-pypi if: does not require smoke-wheels to have succeeded" in publish_violations(mutated)


def test_mutation_publish_without_attestation_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    job = jobs(mutated)["publish-pypi"]
    job["steps"] = [step for step in steps(job) if "Attest" not in str(step.get("name", ""))]
    assert "publish-pypi has no SHA-pinned attest-build-provenance step" in publish_violations(mutated)


def test_mutation_publish_without_the_set_recheck_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    job = jobs(mutated)["publish-pypi"]
    job["steps"] = [step for step in steps(job) if "Re-check the distribution set" not in str(step.get("name", ""))]
    assert "publish-pypi does not re-run check_dist_set.py --verify-binaries on the downloaded artifact" in publish_violations(mutated)


def test_mutation_nightly_job_in_the_summary_needs_is_caught(nightly: dict[Any, Any]) -> None:
    mutated = mutate(nightly)
    jobs(mutated)["nightly-summary"]["needs"] = [*needs_of(jobs(mutated)["nightly-summary"]), NIGHTLY_JOB]
    assert any("is in nightly-summary.needs" in problem for problem in nightly_violations(mutated))


def test_mutation_nightly_job_removed_is_caught(nightly: dict[Any, Any]) -> None:
    mutated = mutate(nightly)
    del jobs(mutated)[NIGHTLY_JOB]
    assert nightly_violations(mutated) == [f"no {NIGHTLY_JOB} job"]


# --- the workflow model accepts a multi-job reusable workflow that does not run the suite --------------------------------


def _caller_and_delegate(tmp_path: Path, delegate_run: str) -> Path:
    workflows = tmp_path / ".github" / "workflows"
    workflows.mkdir(parents=True)
    (workflows / "caller.yml").write_text("on: [schedule]\njobs:\n  call:\n    uses: ./.github/workflows/delegate.yml\n", encoding="utf-8")
    delegate = {
        "on": {"workflow_call": {}},
        "jobs": {
            "one": {"runs-on": "ubuntu-24.04", "steps": [{"run": "echo build"}]},
            "two": {"runs-on": "ubuntu-24.04", "steps": [{"run": delegate_run}]},
        },
    }
    (workflows / "delegate.yml").write_text(yaml.safe_dump(delegate), encoding="utf-8")
    return workflows / "caller.yml"


def test_the_model_leaves_a_multi_job_delegate_opaque_when_it_runs_no_pytest(tmp_path: Path) -> None:
    """event-producer.yml has five jobs and is called from release.yml and ci-nightly.yml; the splicer used to refuse any multi-job delegate."""
    from tests.architectural import _gate_coverage as gate_coverage

    caller = _caller_and_delegate(tmp_path, "echo smoke")
    spliced = gate_coverage.load_spliced_workflow(caller)
    assert spliced["jobs"]["call"]["steps"] == []


def test_the_model_refuses_a_multi_job_delegate_that_runs_pytest(tmp_path: Path) -> None:
    """The mutation: a pytest step in a multi-job delegate would be invisible to every consumer of the model, so it is refused."""
    from tests.architectural import _gate_coverage as gate_coverage

    caller = _caller_and_delegate(tmp_path, "python -m pytest tests/x")
    with pytest.raises(AssertionError, match="must not run the suite"):
        gate_coverage.load_spliced_workflow(caller)
