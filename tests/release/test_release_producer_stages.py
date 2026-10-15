"""Stage rules of ``release.yml`` and the scheduled producer nightly (mission event-producer-reroute, WP09).

Every rule is a function over a parsed workflow document, so each is proved twice: the real file passes it, and a deep copy with one
mutation fails it for that rule. A rule that found nothing to judge fails (no vacuous pass).

What this file can and cannot show. These are static tests of the workflow graph. ``release.yml`` runs on a tag push or a
``workflow_dispatch`` of the selected ref, so a pull request never starts it. (A ``workflow_call`` callee runs at the caller's ref; only
``schedule`` and ``workflow_run`` use the default-branch copy.) None of the stages below executes on a pull request. The checks they
call are executable and tested elsewhere (``tests/release/test_check_dist_set.py``, ``test_check_exact_install.py``, the shared smoke
action in ``native-smoke``); the first real execution of the stages is the first 4.0 release candidate. Nothing here claims otherwise.
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
SBOM_INVOCATION = re.compile(r"^\s*(?:bash\s+)?\S*tools/check-sbom\.sh\s+producer-in/producer-sbom/\S+", re.MULTILINE)
REBUILD = re.compile(r"python3?\s+-m\s+build\b|\buv\s+build\b|\bpip\s+wheel\b|\bhatch\s+build\b|\bpython3?\s+-m\s+hatchling\b")
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
FORK_GUARD = "(github.repository == 'spec-kitty/spec-kitty' || github.event_name == 'pull_request' || github.event_name == 'workflow_dispatch')"
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
    condition = str(job.get("if", ""))
    if "!cancelled()" not in condition or "needs.build-release.result == 'success'" not in condition:
        problems.append("smoke-wheels if: must be !cancelled() and needs.build-release.result == 'success' (the waiver path skips nightly-gate)")
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
        command = str(with_.get("command", ""))
        # The shared action asserts exactly one publish log holding exactly one line, so the command must hand exactly one row to the
        # producer: `init` does (ProjectInitialized); `agent mission create` adds MissionCreated and SpecifyStarted and a second log.
        if " init " not in command or "mission" in command or command.count("specify_cli") != 1:
            problems.append("smoke-wheels command must run `specify_cli init` alone (exactly one producer row), with no mission command")
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
    if sbom is None or not SBOM_INVOCATION.search(str(sbom.get("run", ""))):
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
    """event-producer.yml carries the nightly itself (a `schedule` trigger): the workflow-count ceiling (20) forbids a new file.

    It stays out of the run release_nightly_gate.py reads because it is a different workflow file; masking it with
    continue-on-error is not how it is non-gating (Standing Order 9).
    """
    problems: list[str] = []
    on = document.get("on", document.get(True, {}))
    schedule = on.get("schedule") if isinstance(on, dict) else None
    if not schedule or not all(isinstance(entry, dict) and entry.get("cron") for entry in schedule):
        problems.append("event-producer.yml has no `schedule` trigger with a cron: the nightly would not run")
    problems.extend(f"event-producer.yml masks a failure with continue-on-error: {found}" for found in continue_on_error_violations(document))
    group = str(document.get("concurrency", {}).get("group", ""))
    if "github.workflow" not in group:
        problems.append("the concurrency group does not name the calling workflow, so the nightly and a release dispatch could cancel each other")
    if document.get("permissions") != {"contents": "read"}:
        problems.append("the workflow-level permissions are not exactly contents: read")
    for name, job in jobs(document).items():
        if "github.repository == 'spec-kitty/spec-kitty'" not in str(job.get("if", "")):
            problems.append(f"job {name} lacks the canonical fork guard, so a fork's schedule would run it")
    return problems


def release_gate_input_violations(ci_nightly: dict[Any, Any], gate_source: str) -> list[str]:
    """release_nightly_gate.py decides from the WHOLE ci-nightly.yml run conclusion, so no producer job may be in that file."""
    problems: list[str] = []
    if 'ci-nightly.yml' not in gate_source:
        problems.append("release_nightly_gate.py no longer reads ci-nightly.yml: re-derive what the producer nightly may touch")
    for name, job in jobs(ci_nightly).items():
        if name == NIGHTLY_JOB or str(job.get("uses", "")).endswith("event-producer.yml"):
            problems.append(f"ci-nightly.yml job {name!r} would change the run conclusion the release gate reads")
    if NIGHTLY_JOB in str(ci_nightly):
        problems.append(f"ci-nightly.yml still mentions {NIGHTLY_JOB}")
    return problems


def ancestors(all_jobs: dict[str, Any], name: str) -> set[str]:
    found: set[str] = set()
    todo = list(needs_of(all_jobs[name]))
    while todo:
        current = todo.pop()
        if current not in found:
            found.add(current)
            todo.extend(needs_of(all_jobs[current]))
    return found


def simulate_publish_path(
    document: dict[Any, Any], *, event: str, skip_nightly: bool, outcomes: dict[str, str] | None = None
) -> dict[str, str]:
    """Evaluate the `needs`/`if` graph of release.yml up to publish-pypi the way GitHub Actions does, and return each job's result.

    A job with no `if:` gets the implicit `success()`: it runs only when every ancestor in its `needs` chain succeeded, so a skipped
    ancestor skips it. A
    job with an `if:` runs when the expression is true. A job that ran takes the scenario outcome (`success` unless `outcomes` says
    otherwise). The expression grammar is the small one release.yml uses: `!cancelled()`, `needs.<job>.result == '<state>'`,
    `github.event_name == '<x>'`, `inputs.skip_nightly`, `&&`, `||`, `!` and parentheses. Anything else raises, so the evaluator cannot
    pass a condition it does not understand.
    """
    outcomes = outcomes or {}
    results: dict[str, str] = {}
    all_jobs = jobs(document)
    pending = ["nightly-gate", "build-producer", "build-release", "smoke-wheels", "publish-pypi"]
    for name in pending:
        job = all_jobs[name]
        needed = needs_of(job)
        assert all(n in results for n in needed), f"{name} needs {needed}: the evaluation order is wrong"
        condition = job.get("if")
        if condition is None:
            # GitHub's implicit success() looks at the whole upstream chain, not only the direct `needs`: a skipped ancestor skips the job
            # (release.yml documents the same propagation for publish-pypi).
            runs = all(results[n] == "success" for n in ancestors(all_jobs, name))
        else:
            expression = str(condition).strip()
            expression = re.sub(r"^\$\{\{|\}\}$", "", expression).strip()
            expression = " ".join(expression.split()).replace("!cancelled()", "True")
            expression = re.sub(r"needs\.([\w-]+)\.result", r"R['\1']", expression)
            expression = expression.replace("github.event_name", "EVENT").replace("inputs.skip_nightly", "SKIP")
            expression = expression.replace("&&", " and ").replace("||", " or ")
            expression = re.sub(r"!(?!=)", " not ", expression)
            assert re.fullmatch(r"[\sA-Za-z_'\[\]()=-]*", expression), f"unsupported expression in {name}: {expression!r}"
            runs = bool(eval(expression, {"__builtins__": {}}, {"R": results, "EVENT": event, "SKIP": skip_nightly}))  # noqa: S307
        results[name] = outcomes.get(name, "success") if runs else "skipped"
    return results


def no_rebuild_violations(document: dict[Any, Any]) -> list[str]:
    """smoke-wheels and publish-pypi act on the artifacts build-release produced: they download `release-distributions` and rebuild nothing."""
    problems: list[str] = []
    for name in ("smoke-wheels", "publish-pypi"):
        job = jobs(document).get(name)
        if job is None:
            problems.append(f"no {name} job")
            continue
        problems.extend(f"{name} would rebuild a distribution: {step.get('name')}" for step in steps(job) if REBUILD.search(str(step.get("run", ""))))
        downloads = [step for step in steps(job) if str(step.get("uses", "")).startswith("actions/download-artifact@")]
        if not any(step.get("with", {}).get("name") == "release-distributions" for step in downloads):
            problems.append(f"{name} does not download the release-distributions artifact")
    return problems


# --- the real files pass ----------------------------------------------------------------------------------------------


@pytest.fixture(name="release")
def release_fixture() -> dict[Any, Any]:
    return load(RELEASE)


@pytest.fixture(name="nightly")
def nightly_fixture() -> dict[Any, Any]:
    return load(NIGHTLY)


@pytest.fixture(name="event_producer")
def event_producer_fixture() -> dict[Any, Any]:
    return load(EVENT_PRODUCER)


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


def test_event_producer_workflow_carries_the_scheduled_nightly(event_producer: dict[Any, Any]) -> None:
    assert nightly_violations(event_producer) == []
    assert triggers(event_producer) == {"pull_request", "push", "workflow_dispatch", "workflow_call", "schedule"}
    text = EVENT_PRODUCER.read_text(encoding="utf-8")
    assert "release_nightly_gate" in text and "ci-nightly.yml" in text, "the file must say why the nightly is not part of ci-nightly.yml"


def test_the_release_gate_input_workflow_does_not_contain_the_producer_job(nightly: dict[Any, Any]) -> None:
    gate_source = (ROOT / "scripts" / "ci" / "release_nightly_gate.py").read_text(encoding="utf-8")
    assert release_gate_input_violations(nightly, gate_source) == []


def test_mutation_a_producer_job_back_in_ci_nightly_is_caught(nightly: dict[Any, Any]) -> None:
    mutated = mutate(nightly)
    jobs(mutated)[NIGHTLY_JOB] = {"uses": PRODUCER_WORKFLOW, "with": {"mode": "pr"}}
    gate_source = (ROOT / "scripts" / "ci" / "release_nightly_gate.py").read_text(encoding="utf-8")
    assert any("would change the run conclusion" in problem for problem in release_gate_input_violations(mutated, gate_source))


def test_mutation_gate_reading_another_file_is_caught(nightly: dict[Any, Any]) -> None:
    assert any("no longer reads ci-nightly.yml" in problem for problem in release_gate_input_violations(nightly, "_WORKFLOW_FILE = 'other.yml'"))


def test_docs_state_the_unproven_stages_and_the_unsigned_binaries() -> None:
    checklist = RELEASE_CHECKLIST.read_text(encoding="utf-8")
    for needle in ("build-producer", "smoke-wheels", "Gatekeeper", "SmartScreen", "Test-PyPI", "unproven"):
        assert needle in checklist, f"RELEASE_CHECKLIST.md does not mention {needle}"
    assert "`schedule` trigger of `event-producer.yml`" in checklist and "`producer-nightly` job in `ci-nightly.yml`" not in checklist
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


def test_mutation_event_producer_without_a_schedule_is_caught(event_producer: dict[Any, Any]) -> None:
    mutated = mutate(event_producer)
    del mutated[True if True in mutated else "on"]["schedule"]
    assert any("no `schedule` trigger" in problem for problem in nightly_violations(mutated))


def test_mutation_event_producer_with_continue_on_error_is_caught(event_producer: dict[Any, Any]) -> None:
    mutated = mutate(event_producer)
    jobs(mutated)["native"]["continue-on-error"] = True
    assert any("continue-on-error" in problem for problem in nightly_violations(mutated))


def test_mutation_event_producer_group_without_the_caller_name_is_caught(event_producer: dict[Any, Any]) -> None:
    mutated = mutate(event_producer)
    mutated["concurrency"] = {"group": "event-producer-${{ github.ref }}"}
    assert any("concurrency group" in problem for problem in nightly_violations(mutated))


def test_mutation_event_producer_job_without_fork_guard_is_caught(event_producer: dict[Any, Any]) -> None:
    mutated = mutate(event_producer)
    jobs(mutated)["native"]["if"] = "true"
    assert any("fork guard" in problem for problem in nightly_violations(mutated))


def test_waiver_path_still_publishes_after_a_green_smoke(release: dict[Any, Any]) -> None:
    """FR-008: a workflow_dispatch with skip_nightly=true skips nightly-gate; build, smoke and publish must still run."""
    result = simulate_publish_path(release, event="workflow_dispatch", skip_nightly=True)
    assert result == {
        "nightly-gate": "skipped",
        "build-producer": "success",
        "build-release": "success",
        "smoke-wheels": "success",
        "publish-pypi": "success",
    }


def test_tag_push_path_publishes(release: dict[Any, Any]) -> None:
    assert simulate_publish_path(release, event="push", skip_nightly=False)["publish-pypi"] == "success"


@pytest.mark.parametrize("event,skip", [("push", False), ("workflow_dispatch", True), ("workflow_dispatch", False)])
def test_a_failed_smoke_blocks_publish_on_every_path(release: dict[Any, Any], event: str, skip: bool) -> None:
    result = simulate_publish_path(release, event=event, skip_nightly=skip, outcomes={"smoke-wheels": "failure"})
    assert result["smoke-wheels"] == "failure"
    assert result["publish-pypi"] == "skipped"


def test_a_failed_nightly_gate_blocks_publish_without_the_waiver(release: dict[Any, Any]) -> None:
    result = simulate_publish_path(release, event="workflow_dispatch", skip_nightly=False, outcomes={"nightly-gate": "failure"})
    assert result["publish-pypi"] == "skipped"


def test_mutation_smoke_wheels_without_its_guard_loses_the_waiver_path(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["smoke-wheels"].pop("if", None)
    assert simulate_publish_path(mutated, event="workflow_dispatch", skip_nightly=True)["publish-pypi"] == "skipped"


def test_mutation_smoke_guard_that_ignores_the_build_result_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    jobs(mutated)["smoke-wheels"]["if"] = "${{ !cancelled() }}"
    assert any("smoke-wheels" in problem and "build-release" in problem for problem in smoke_wheels_violations(mutated))


def test_publish_reruns_the_sbom_check_on_the_downloaded_producer_sbom(release: dict[Any, Any]) -> None:
    assert publish_violations(release) == []


def test_mutation_sbom_step_turned_into_an_echo_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    step = step_named(jobs(mutated)["publish-pypi"], "Check the producer SBOM")
    assert step is not None
    step["run"] = "echo skipped check-sbom.sh producer-in/producer-sbom/producer-sbom.json"
    assert "publish-pypi does not re-run check-sbom.sh on the downloaded producer-sbom" in publish_violations(mutated)


def test_smoke_and_publish_use_the_downloaded_artifacts_and_rebuild_nothing(release: dict[Any, Any]) -> None:
    assert no_rebuild_violations(release) == []
    for name in ("smoke-wheels", "publish-pypi"):
        downloads = [step for step in steps(jobs(release)[name]) if str(step.get("uses", "")).startswith("actions/download-artifact@")]
        assert any(step.get("with", {}).get("name") == "release-distributions" for step in downloads), name


@pytest.mark.parametrize("job_name", ["smoke-wheels", "publish-pypi"])
@pytest.mark.parametrize("build_command", ["python -m build --wheel --outdir release-in/dist", "uv build", "pip wheel .", "hatch build"])
def test_mutation_a_rebuild_in_smoke_or_publish_is_caught(release: dict[Any, Any], job_name: str, build_command: str) -> None:
    mutated = mutate(release)
    jobs(mutated)[job_name]["steps"].insert(3, {"name": "Rebuild", "run": build_command})
    assert any(job_name in problem and "rebuild" in problem for problem in no_rebuild_violations(mutated))


def test_mutation_smoke_without_the_artifact_download_is_caught(release: dict[Any, Any]) -> None:
    mutated = mutate(release)
    job = jobs(mutated)["smoke-wheels"]
    job["steps"] = [step for step in steps(job) if not str(step.get("uses", "")).startswith("actions/download-artifact@")]
    assert any("smoke-wheels" in problem and "release-distributions" in problem for problem in no_rebuild_violations(mutated))


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


def _plant(tmp_path: Path, job_two: dict[str, Any], files: dict[str, str] | None = None) -> Path:
    """A per-change caller of a workflow_call-only two-job delegate whose second job is *job_two*, plus any repository files."""
    workflows = tmp_path / ".github" / "workflows"
    workflows.mkdir(parents=True)
    (workflows / "caller.yml").write_text("on: [schedule]\njobs:\n  call:\n    uses: ./.github/workflows/delegate.yml\n", encoding="utf-8")
    delegate = {
        "on": {"workflow_call": {}},
        "jobs": {"one": {"runs-on": "ubuntu-24.04", "steps": [{"run": "echo build"}]}, "two": {"runs-on": "ubuntu-24.04", **job_two}},
    }
    (workflows / "delegate.yml").write_text(yaml.safe_dump(delegate), encoding="utf-8")
    for relative, content in (files or {}).items():
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    return workflows / "caller.yml"


ACTION_RUNNING_PYTEST = "runs:\n  using: composite\n  steps:\n    - shell: bash\n      run: python -m pytest tests/x\n"
ACTION_RUNNING_ECHO = "runs:\n  using: composite\n  steps:\n    - shell: bash\n      run: echo hello\n"
MAKEFILE = "test-fast:\n\tpython -m pytest tests/x\n\nlint:\n\techo lint\n\nci: test-fast\n"

# (job two, repository files): every route by which a delegate job reaches pytest that the old refusal did not see.
HIDDEN_SUITE_ROUTES = {
    "composite-action": ({"steps": [{"uses": "./.github/actions/x"}]}, {".github/actions/x/action.yml": ACTION_RUNNING_PYTEST}),
    "nested-composite-action": (
        {"steps": [{"uses": "./.github/actions/outer"}]},
        {
            ".github/actions/outer/action.yml": "runs:\n  using: composite\n  steps:\n    - uses: ./.github/actions/x\n",
            ".github/actions/x/action.yml": ACTION_RUNNING_PYTEST,
        },
    ),
    "make-target": ({"steps": [{"run": "make test-fast"}]}, {"Makefile": MAKEFILE}),
    "make-prerequisite": ({"steps": [{"run": "make ci"}]}, {"Makefile": MAKEFILE}),
    "make-after-and": ({"steps": [{"run": "echo go && make test-fast"}]}, {"Makefile": MAKEFILE}),
    "direct-in-first-job-position": ({"steps": [{"run": "python -m pytest tests/x"}]}, {}),
}


@pytest.mark.parametrize("route", sorted(HIDDEN_SUITE_ROUTES))
def test_the_model_refuses_a_multi_job_delegate_that_reaches_pytest_by_any_route(tmp_path: Path, route: str) -> None:
    from tests.architectural import _gate_coverage as gate_coverage

    job_two, files = HIDDEN_SUITE_ROUTES[route]
    caller = _plant(tmp_path, job_two, files)
    with pytest.raises(AssertionError, match="must not run the suite"):
        gate_coverage.load_spliced_workflow(caller)


FAIL_CLOSED_ROUTES = {
    "unresolvable-composite-action": ({"steps": [{"uses": "./.github/actions/missing"}]}, {}),
    "unresolvable-make-target": ({"steps": [{"run": "make no-such-target"}]}, {"Makefile": MAKEFILE}),
    "make-without-a-makefile": ({"steps": [{"run": "make test-fast"}]}, {}),
    "job-level-uses-inside-the-delegate": ({"uses": "./.github/workflows/other.yml"}, {}),
}


@pytest.mark.parametrize("route", sorted(FAIL_CLOSED_ROUTES))
def test_the_model_fails_closed_when_a_delegate_job_cannot_be_resolved(tmp_path: Path, route: str) -> None:
    from tests.architectural import _gate_coverage as gate_coverage

    job_two, files = FAIL_CLOSED_ROUTES[route]
    caller = _plant(tmp_path, job_two, files)
    with pytest.raises(AssertionError, match="must not run the suite|cannot be resolved"):
        gate_coverage.load_spliced_workflow(caller)


BENIGN_ROUTES = {
    "echo": ({"steps": [{"run": "echo smoke"}]}, {}),
    "action-without-pytest": ({"steps": [{"uses": "./.github/actions/x"}]}, {".github/actions/x/action.yml": ACTION_RUNNING_ECHO}),
    "make-target-without-pytest": ({"steps": [{"run": "make lint"}]}, {"Makefile": MAKEFILE}),
    "external-action": ({"steps": [{"uses": "actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8"}]}, {}),
}


@pytest.mark.parametrize("route", sorted(BENIGN_ROUTES))
def test_the_model_still_leaves_a_delegate_without_pytest_opaque(tmp_path: Path, route: str) -> None:
    """event-producer.yml must keep loading: it has five jobs, uses make-free shell and an action that runs no pytest."""
    from tests.architectural import _gate_coverage as gate_coverage

    job_two, files = BENIGN_ROUTES[route]
    spliced = gate_coverage.load_spliced_workflow(_plant(tmp_path, job_two, files))
    assert spliced["jobs"]["call"]["steps"] == []


def test_the_real_producer_workflows_load_through_the_model() -> None:
    from tests.architectural import _gate_coverage as gate_coverage

    for name in ("release.yml", "ci-nightly.yml", "event-producer.yml"):
        assert gate_coverage.load_spliced_workflow(WORKFLOWS / name)["jobs"]


def test_event_producer_concurrency_group_names_the_caller() -> None:
    """Under workflow_call, github.workflow is the CALLER's name: without it the nightly call and a release dispatch share a group and cancel each other."""
    group = str(load(EVENT_PRODUCER)["concurrency"]["group"])
    assert "github.workflow" in group


def test_mutation_a_mission_command_in_the_smoke_is_caught(release: dict[Any, Any]) -> None:
    """`init` hands one row to the producer; adding `agent mission create` hands three and breaks the action's one-log, one-line check."""
    mutated = mutate(release)
    action = next(step for step in steps(jobs(mutated)["smoke-wheels"]) if step.get("uses") == SMOKE_ACTION)
    action["with"]["command"] = str(action["with"]["command"]) + ' && "$SMOKE_PY" -m specify_cli agent mission create smoke-probe --json'
    assert any("exactly one producer row" in problem for problem in smoke_wheels_violations(mutated))
