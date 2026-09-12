#!/usr/bin/env python3
"""Fail a CI gate when a Native Agent smoke RSS report is invalid or too large."""

from __future__ import annotations

import json
import os
import pathlib
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} REPORT.json", file=sys.stderr)
        return 2
    report_path = pathlib.Path(sys.argv[1])
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        peak_mib = float(report["peak_rss_mib"])
        samples = int(report["rss_samples"])
        limit_mib = float(os.environ.get("RCM_AGENT_MAX_RSS_MIB", "256"))
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"invalid Agent resource report: {exc}", file=sys.stderr)
        return 1
    if limit_mib <= 0 or peak_mib <= 0 or samples < 1:
        print(
            f"invalid Agent resource values: peak_rss_mib={peak_mib}, "
            f"rss_samples={samples}, limit={limit_mib}",
            file=sys.stderr,
        )
        return 1
    if peak_mib > limit_mib:
        print(
            f"Native Agent RSS budget exceeded: peak={peak_mib:.1f} MiB "
            f"> limit={limit_mib:.1f} MiB",
            file=sys.stderr,
        )
        return 1
    print(
        f"Native Agent RSS budget passed: peak={peak_mib:.1f} MiB, "
        f"samples={samples}, limit={limit_mib:.1f} MiB"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
