"""Choose and supervise one optional private PC attempt for a trusted build."""

import argparse
import os
import re
import time
from urllib.parse import urlencode

from nexus.github import GitHub, GitHubError


BASE_LABELS = {"self-hosted", "windows", "x64", "ricci", "mtx-nexus"}
CAPABILITIES = {"android-build", "godot-build"}


def select_runner(repository, event, trusted_ref, preference, capability, runners, runner_name="RICCI", runner_label=None):
    if repository["private"] is not True or event not in {"push", "workflow_dispatch"} or not trusted_ref:
        return "cloud", "repository-or-ref-ineligible"
    if preference == "github":
        return "cloud", "cloud-override"
    if capability not in CAPABILITIES or preference not in {"auto", "pc"}:
        raise ValueError("Invalid routing policy")
    labels = ({"self-hosted", "windows", "x64", runner_label.casefold()}
              if runner_label else BASE_LABELS | {capability})
    eligible = [
        runner for runner in runners
        if labels <= {label["name"].casefold() for label in runner["labels"]}
    ]
    if len(eligible) != 1:
        return "cloud", "runner-missing-or-label-collision"
    runner = eligible[0]
    if runner["name"] != runner_name or runner["os"].casefold() != "windows":
        return "cloud", "unexpected-runner-identity"
    if runner["status"] != "online":
        return "cloud", "runner-offline"
    if runner["busy"]:
        return "cloud", "runner-busy"
    return "pc", "runner-available"


def supervise(api, repo, worker, ref, sha, artifact_name, queue_seconds, execution_seconds,
              poll_seconds=5, clock=time.monotonic, sleep=time.sleep, runner_name="RICCI",
              build_steps=()):
    """A dispatched worker has no signing or publication authority.

    A failed or timed-out worker is discarded. Ambiguous dispatch responses are
    never retried. The exact run ID is used for monitoring and cancellation.
    """
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an immutable commit SHA")
    if not re.fullmatch(r"[\w.-]+\.ya?ml", worker):
        raise ValueError("Invalid worker workflow name")
    path = f"/repos/{repo}/actions"
    try:
        dispatch = api.request(f"{path}/workflows/{worker}/dispatches", {
            "ref": ref,
            "inputs": {"source_sha": sha},
        })
        run_id = dispatch["workflow_run_id"]
        if type(run_id) is not int or run_id <= 0:
            raise ValueError("Unexpected workflow run ID")
    except (GitHubError, ValueError, KeyError, TypeError):
        return "cloud", "dispatch-unconfirmed", None

    run_path = f"{path}/runs/{run_id}"
    queue_deadline = clock() + queue_seconds
    execution_deadline = None
    reason = "worker-queue-timeout"
    try:
        while True:
            run = api.request(run_path)
            now = clock()
            if run["status"] == "completed":
                jobs = api.request(run_path + "/jobs")["jobs"]
                if run["conclusion"] != "success":
                    for job in jobs:
                        if job.get("runner_name") != runner_name:
                            continue
                        if any(step.get("name") in build_steps and step.get("conclusion") == "failure"
                               for step in job.get("steps", ())):
                            return "failed", "worker-build-or-test-failed", run_id
                    return "cloud", "worker-failed", run_id
                if not any(job.get("conclusion") == "success" and job.get("runner_name") == runner_name
                           for job in jobs):
                    return "cloud", "worker-job-not-verified", run_id
                artifacts = list(api.pages(run_path + "/artifacts?" + urlencode({"name": artifact_name}),
                                           "artifacts"))
                matches = [artifact for artifact in artifacts if artifact["name"] == artifact_name
                           and not artifact["expired"] and artifact["size_in_bytes"] > 0]
                if len(matches) != 1:
                    return "cloud", "worker-artifact-not-verified", run_id
                return "pc", "worker-succeeded", run_id
            if execution_deadline is None and run["status"] == "in_progress":
                jobs = api.request(run_path + "/jobs")["jobs"]
                # A run can be "in_progress" while its PC job is still queued.
                if any(job["status"] == "in_progress" and job.get("runner_name") == runner_name
                       for job in jobs):
                    execution_deadline = clock() + execution_seconds
            deadline = execution_deadline if execution_deadline is not None else queue_deadline
            if now >= deadline:
                if execution_deadline is not None:
                    reason = "worker-execution-timeout"
                break
            sleep(min(poll_seconds, deadline - now))
    except (GitHubError, ValueError, KeyError, TypeError):
        reason = "worker-status-unavailable"
    try:
        api.request(run_path + "/cancel", {})
    except GitHubError as exc:
        if exc.status != 409:
            reason += "-cancel-unconfirmed"
    return "cloud", reason, run_id


def route_for_run(api, runner_token, repo, event, trusted, preference, capability, runner_name="RICCI", runner_label=None):
    if not trusted or event not in {"push", "workflow_dispatch"}:
        return "cloud", "repository-or-ref-ineligible"
    if preference == "github":
        return "cloud", "cloud-override"
    try:
        metadata = api.request(f"/repos/{repo}")
        if metadata["private"] is not True:
            return "cloud", "repository-or-ref-ineligible"
        if not runner_token:
            return "cloud", "runner-authorization-missing"
        runners = list(GitHub(runner_token).pages(f"/repos/{repo}/actions/runners", "runners"))
        return select_runner(metadata, event, trusted, preference, capability, runners, runner_name, runner_label)
    except (GitHubError, ValueError, KeyError, TypeError):
        return "cloud", "runner-status-unavailable"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--capability", required=True, choices=sorted(CAPABILITIES))
    parser.add_argument("--preference", default="auto", choices=("auto", "pc", "github"))
    parser.add_argument("--trusted-ref", required=True)
    parser.add_argument("--worker", required=True)
    parser.add_argument("--worker-artifact", required=True)
    parser.add_argument("--runner-name", default="RICCI")
    parser.add_argument("--runner-label", help="Repository-specific custom label used by the worker.")
    parser.add_argument("--queue-seconds", type=int, choices=range(1, 61), default=60,
                        metavar="1..60")
    parser.add_argument("--execution-seconds", type=int, default=1500)
    parser.add_argument("--build-step", action="append", default=[],
                        help="Exact worker step name whose failure should stop the build; repeatable.")
    args = parser.parse_args()
    if args.execution_seconds < 1:
        parser.error("--execution-seconds must be positive")
    repo = os.environ["GITHUB_REPOSITORY"]
    api = GitHub(os.environ["GH_TOKEN"])
    event = os.environ["GITHUB_EVENT_NAME"]
    ref = os.environ["GITHUB_REF"]
    trusted = ref == f"refs/heads/{args.trusted_ref}"
    runner_token = os.getenv("NEXUS_RUNNER_READ_TOKEN")
    target, reason = route_for_run(api, runner_token, repo, event, trusted,
                                   args.preference, args.capability, args.runner_name, args.runner_label)
    run_id = None
    if target == "pc":
        target, reason, run_id = supervise(api, repo, args.worker, args.trusted_ref,
                                            os.environ["GITHUB_SHA"], args.worker_artifact,
                                            args.queue_seconds, args.execution_seconds,
                                            runner_name=args.runner_name, build_steps=args.build_step)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        print(f"target={target}\nreason={reason}\nworker_run_id={run_id or ''}", file=out)
    print(f"MTX Nexus route: {target} ({reason})")
    if target == "failed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
