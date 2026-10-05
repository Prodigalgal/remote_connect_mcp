#!/usr/bin/env python3
"""Allow publishing existing CI assets only after verifying their source and gates."""
import argparse
import json
from pathlib import Path
import re
import subprocess

REQUIRED_JOBS = {
    "prepare", "Agent and updater JVM component gate",
    "Camoufox navigation gate / Camoufox navigation gate",
    "Agent Native (amd64)", "Agent Native (arm64)",
    "Agent Native (windows-amd64)", "Publish command-agent image",
}
REQUIRED_ARTIFACTS = {"rcm-agent-linux-amd64", "rcm-agent-linux-arm64", "rcm-agent-windows-amd64"}


def validate(run, workflow, jobs, artifacts, tag_sha, version, repository):
    if not re.fullmatch(r"v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}", version):
        raise ValueError("invalid release version")
    if (run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository):
        raise ValueError("release assets must come from this repository, not a fork")
    if workflow.get("path") != ".github/workflows/agent-release.yml":
        raise ValueError("source run is not the Agent release workflow")
    if run.get("event") not in ("push", "workflow_dispatch") or run.get("status") != "completed":
        raise ValueError("source run must be a completed release run")
    if run.get("head_branch") not in ("main", "java-" + version):
        raise ValueError("source run must target main or this exact release tag")
    sha = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or tag_sha != sha:
        raise ValueError("release tag must already pin the exact source run SHA")
    latest = {}
    for job in jobs:
        name = job.get("name")
        if name not in latest or job.get("id", 0) > latest[name].get("id", 0):
            latest[name] = job
    failed = sorted(name for name in REQUIRED_JOBS
                    if latest.get(name, {}).get("conclusion") != "success")
    if failed:
        raise ValueError("source component gates did not pass: " + ", ".join(failed))
    available = {a.get("name") for a in artifacts if a.get("expired") is False}
    if not REQUIRED_ARTIFACTS <= available:
        raise ValueError("source component artifacts are missing or expired")
    return {"version": version, "tag": "java-" + version, "source_sha": sha}


def api(route, paginate=False):
    command = ["gh", "api", route]
    if paginate:
        command.extend(["--paginate", "--slurp"])
    return json.loads(subprocess.check_output(command, text=True, encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.run_id.isdecimal() or not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        parser.error("invalid source run ID or repository")
    version = args.version.removeprefix("java-")
    if not re.fullmatch(r"v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}", version):
        parser.error("invalid release version")
    root = "repos/" + args.repository
    run = api(root + "/actions/runs/" + args.run_id)
    workflow = api(root + "/actions/workflows/" + str(run["workflow_id"]))
    jobs = [job for page in api(root + "/actions/runs/" + args.run_id + "/jobs?filter=latest&per_page=100", True)
            for job in page["jobs"]]
    artifacts = [a for page in api(root + "/actions/runs/" + args.run_id + "/artifacts?per_page=100", True)
                 for a in page["artifacts"]]
    tag = api(root + "/git/ref/tags/java-" + version)["object"]
    for _ in range(4):
        if tag.get("type") == "commit":
            break
        if tag.get("type") != "tag":
            raise ValueError("release tag does not resolve to a commit")
        tag = api(root + "/git/tags/" + tag["sha"])["object"]
    else:
        raise ValueError("release tag nesting exceeds the supported limit")
    result = validate(run, workflow, jobs, artifacts, tag["sha"], version, args.repository)
    with args.output.open("a", encoding="utf-8") as output:
        output.write("".join(f"{key}={value}\n" for key, value in result.items()))
    print(json.dumps({"verified": True, "source_run": args.run_id, **result}))


if __name__ == "__main__":
    main()
