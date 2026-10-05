#!/usr/bin/env python3
"""Resolve a component release and pin its source before any build starts."""

import argparse
import json
import os
import re
import subprocess
from pathlib import Path

PREFIXES = {"agent": "java-", "center": "center-", "console": "console-"}
SAFE_VERSION = re.compile(r"v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}")


def next_stable_version(component: str, tags: dict[str, str], sha: str) -> str:
    pattern = re.compile(re.escape(PREFIXES[component]) + r"v(\d{1,9})\.(\d{1,9})\.(\d{1,9})")
    versions = [(tuple(map(int, match.groups())), tag, source)
                for tag, source in tags.items() if (match := pattern.fullmatch(tag))]
    # A retried prepare job must use its previously reserved version.
    own = [version for version, _, source in versions if source == sha]
    if own:
        return "v" + ".".join(map(str, max(own)))
    major, minor, patch = max((version for version, _, _ in versions), default=(0, 1, 0))
    if patch >= 999_999_999:
        raise ValueError("component patch version exhausted")
    return f"v{major}.{minor}.{patch + 1}"


def remote_tags(component: str) -> dict[str, str]:
    text = subprocess.check_output(
        ["git", "ls-remote", "--tags", "origin", f"refs/tags/{PREFIXES[component]}v*"],
        text=True, encoding="utf-8")
    tags = {}
    for line in text.splitlines():
        source, ref = line.split("\t", 1)
        name = ref.removeprefix("refs/tags/")
        if name.endswith("^{}"):
            tags[name[:-3]] = source
        else:
            tags.setdefault(name, source)
    return tags


def pin_tag(repository: str, tag: str, sha: str, tags: dict[str, str]) -> None:
    if tag in tags:
        if tags[tag] != sha:
            raise ValueError("release tag already points at a different source")
        return
    payload = json.dumps({"ref": f"refs/tags/{tag}", "sha": sha})
    subprocess.run(["gh", "api", "--method", "POST", f"repos/{repository}/git/refs",
                    "--input", "-"], input=payload, text=True, check=True, stdout=subprocess.DEVNULL)


def coordinates(component: str, env: dict[str, str], tags: dict[str, str]) -> dict[str, str]:
    prefix = PREFIXES[component]
    event = env.get("EVENT_NAME", "")
    if event == "workflow_dispatch":
        version = env.get("INPUT_VERSION", "").removeprefix(prefix)
        prerelease = env.get("INPUT_PRERELEASE") or "false"
        deploy = env.get("INPUT_DEPLOY") or "true"
    elif env.get("REF_TYPE") == "tag":
        ref = env.get("REF_NAME", "")
        if not ref.startswith(prefix):
            raise ValueError("tag does not belong to this component")
        version, prerelease, deploy = ref.removeprefix(prefix), "false", "true"
    elif event == "push" and env.get("REF_NAME") == "main":
        version = next_stable_version(component, tags, env["SHA"])
        prerelease, deploy = "false", "true"
    else:
        raise ValueError("only main, component tags or an explicit dispatch can publish")
    if not SAFE_VERSION.fullmatch(version) or prerelease not in ("true", "false") or deploy not in ("true", "false"):
        raise ValueError("release coordinates are invalid")
    return {"version": version, "tag": prefix + version, "prerelease": prerelease,
            "deploy": deploy, "publish": "true"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=PREFIXES)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    env = dict(os.environ)
    sha = env.get("SHA", "")
    repository = env.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("release source or repository is invalid")
    tags = remote_tags(args.component)
    values = coordinates(args.component, env, tags)
    pin_tag(repository, values["tag"], sha, tags)
    with Path(args.output).open("a", encoding="utf-8", newline="\n") as output:
        output.write("".join(f"{key}={value}\n" for key, value in values.items()))
    print(f"{args.component}: {values['tag']} pinned to {sha}; prerelease={values['prerelease']}")


if __name__ == "__main__":
    main()
