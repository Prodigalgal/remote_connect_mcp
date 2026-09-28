#!/usr/bin/env python3
"""Push one component promotion, replaying its commit after a concurrent promotion."""

import argparse
from pathlib import Path
import subprocess
import sys


def git(checkout: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(checkout), *args], check=check)


def push(checkout: Path, attempts: int = 4) -> None:
    for attempt in range(1, attempts + 1):
        if git(checkout, "push", "origin", "HEAD:main", check=False).returncode == 0:
            return
        if attempt == attempts:
            raise RuntimeError(f"GitOps push failed after {attempts} attempts")
        git(checkout, "fetch", "origin", "main")
        # A network/authentication failure can also reject a push. Rebase only
        # when the remote actually advanced; keep a real conflict visible.
        if git(checkout, "merge-base", "--is-ancestor", "origin/main", "HEAD", check=False).returncode != 0:
            if git(checkout, "rebase", "origin/main", check=False).returncode != 0:
                git(checkout, "rebase", "--abort", check=False)
                raise RuntimeError("GitOps promotion conflicts with a concurrent change")
        print(f"GitOps main advanced; retrying component promotion ({attempt + 1}/{attempts})", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    push(args.checkout)


if __name__ == "__main__":
    main()
