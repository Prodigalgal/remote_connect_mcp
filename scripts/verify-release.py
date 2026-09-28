#!/usr/bin/env python3
"""Read-only proof of a pinned GitOps image and, when requested, live runtime."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen


IMAGES = {
    "center": "ghcr.io/prodigalgal/remote-connect-mcp-center-java",
    "console": "ghcr.io/prodigalgal/remote-connect-mcp-console",
}


class VerificationError(Exception):
    pass


def verify_manifest(component: str, version: str, manifest: Path, digest: str) -> dict:
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise VerificationError("expected digest must be sha256 followed by 64 lowercase hex digits")
    body = manifest.read_text(encoding="utf-8")
    image = IMAGES[component]
    matches = re.findall(r"(?m)^\s*image:\s*" + re.escape(image) + r"@(sha256:[0-9a-f]{64})\s*$", body)
    if len(matches) != 1 or matches[0] != digest:
        raise VerificationError(f"{component} GitOps manifest does not pin the expected image digest exactly once")
    if component == "center":
        versions = re.findall(r"(?m)^\s*-\s*name:\s*RCM_CENTER_VERSION\s*\n\s*value:\s*([^\s#]+)", body)
        if versions != [version]:
            raise VerificationError("Center GitOps manifest version does not match the expected release")
    return {"manifest": str(manifest), "digest": digest, "verified": True}


def fetch_json(url: str, token: str | None = None) -> dict:
    headers = {"Accept": "application/json", "Cache-Control": "no-cache"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urlopen(Request(url, headers=headers), timeout=15) as response:
        body = json.load(response)
    if not isinstance(body, dict):
        raise VerificationError(f"expected JSON object from {url}")
    return body


def pages(url: str, resource: str, token: str, limit: int) -> list[dict]:
    items: list[dict] = []
    for _ in range(20):
        offset = len(items)
        page = fetch_json(f"{url}/api/v1/admin/{resource}?offset={offset}&limit={limit}", token)
        batch = page.get("items")
        if not isinstance(batch, list) or not all(isinstance(item, dict) for item in batch):
            raise VerificationError(f"invalid {resource} page")
        items.extend(batch)
        if not page.get("has_more", False):
            return items
        if not batch:
            raise VerificationError(f"{resource} pagination made no progress")
    raise VerificationError(f"{resource} exceeds the verification page limit")


def component_expected(machine: dict, component: str) -> bool:
    capabilities = machine.get("capabilities") or []
    if component in ("desktop-companion", "desktop"):
        return "desktop" in capabilities
    if component in ("browser-agent", "browser"):
        return "browser" in capabilities
    return True


def verify_runtime(component: str, version: str, url: str, source_sha: str | None,
                   token: str | None, online_only: bool, native_only: bool = False) -> dict:
    base = url.rstrip("/")
    if component == "center":
        identity = fetch_json(base + "/api/v1/version")
        ready = fetch_json(base + "/api/v1/readyz")
        if identity.get("service") != "center" or identity.get("version") != version:
            raise VerificationError("live Center version differs from the release")
        if ready.get("status") != "ready":
            raise VerificationError("live Center is not ready")
        return {"version": version, "readiness": "ready", "verified": True}
    if component == "console":
        identity = fetch_json(base + "/release.json")
        if identity.get("service") != "console" or identity.get("version") != version:
            raise VerificationError("live Console version differs from the release")
        if source_sha and identity.get("source_sha") != source_sha:
            raise VerificationError("live Console source SHA differs from the release")
        return {"version": version, "source_sha": identity.get("source_sha"), "verified": True}
    if not token:
        raise VerificationError("RCM_VERIFY_ADMIN_TOKEN is required to verify Agent machines")
    machines = pages(base, "machines", token, 200)
    campaigns = [item for item in pages(base, "upgrades", token, 100) if item.get("version") == version]
    if not machines:
        raise VerificationError("Center has no registered machines")
    if not campaigns:
        raise VerificationError("Center has no upgrade campaign for this Agent release")
    current = [item for item in machines if item.get("version") == version]
    online = [item for item in machines if item.get("online") is True]
    current_online = [item for item in online if item.get("version") == version]
    missing = [str(item.get("name") or item.get("id")) for item in machines
               if item.get("version") != version and (not online_only or item.get("online") is True)]
    offline = [str(item.get("name") or item.get("id")) for item in machines if item.get("online") is not True]
    unfinished = []
    components_unverified = []
    for machine in machines:
        if online_only and machine.get("online") is not True:
            continue
        latest = next(((campaign, target) for campaign in campaigns
                       for target in campaign.get("targets", [])
                       if target.get("machine_id") == machine.get("id")), None)
        if latest is None:
            continue
        campaign, target = latest
        name = str(machine.get("name") or machine.get("id"))
        if target.get("status") != "completed":
            unfinished.append({"machine": name, "status": target.get("status")})
        platform = f"{str(machine.get('os') or '').lower()}/{str(machine.get('arch') or '').lower()}"
        plans = (campaign.get("component_plans") or {}).get(platform, [])
        statuses = target.get("component_statuses") or {}
        for plan in plans:
            component = plan.get("component")
            if component_expected(machine, component) and statuses.get(component) != "completed":
                components_unverified.append({"machine": name, "component": component,
                                              "status": statuses.get(component)})
    browser_machines = [str(item.get("name") or item.get("id")) for item in machines
                        if "browser" in (item.get("capabilities") or [])
                        and (not online_only or item.get("online") is True)]
    result = {"registered": len(machines), "online": len(online), "current": len(current),
              "current_online": len(current_online), "offline": offline, "not_current": missing,
              "unfinished_targets": unfinished, "components_unverified": components_unverified,
              "browser_runtime": {"verified": not browser_machines, "machines": browser_machines,
                                  "reason": "Camoufox Node package and browser binary are outside Native upgrade"
                                  if browser_machines else "no browser-capable machines in coverage"},
              "campaigns": [{"id": item.get("id"), "status": item.get("status")}
                            for item in campaigns], "verified": False}
    result["verified"] = not (missing or unfinished or components_unverified) and (
        online_only and bool(online) or not online_only and not offline) and (native_only or not browser_machines)
    result["coverage"] = ("online_only" if online_only else "all_registered_live") + (
        "_native_only" if native_only else "")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=("center", "console", "agent"))
    parser.add_argument("version")
    parser.add_argument("--manifest", type=Path, help="GitOps center.yaml or console.yaml")
    parser.add_argument("--digest", help="immutable image digest expected in the GitOps manifest")
    parser.add_argument("--url", help="live Center or Console base URL")
    parser.add_argument("--source-sha", help="expected Console source commit")
    parser.add_argument("--online-only", action="store_true", help="Agent: verify reachable machines only")
    parser.add_argument("--native-only", action="store_true",
                        help="Agent: explicitly accept Native components without Camoufox runtime verification")
    args = parser.parse_args()
    if (args.manifest is None) != (args.digest is None):
        parser.error("--manifest and --digest must be supplied together")
    if args.component == "agent" and not args.url:
        parser.error("Agent verification requires --url pointing to Center")
    if args.component == "agent" and args.manifest:
        parser.error("Agent releases do not use a GitOps image manifest")
    if args.component != "agent" and args.native_only:
        parser.error("--native-only applies only to Agent verification")
    if not args.manifest and not args.url:
        parser.error("provide a GitOps manifest or a runtime URL")
    result: dict = {"component": args.component, "version": args.version,
                    "gitops": {"verified": False, "reason": "not requested"},
                    "runtime": {"verified": False, "reason": "not requested"}}
    try:
        if args.manifest:
            result["gitops"] = verify_manifest(args.component, args.version, args.manifest, args.digest)
        if args.url:
            result["runtime"] = verify_runtime(args.component, args.version, args.url,
                                               args.source_sha, os.environ.get("RCM_VERIFY_ADMIN_TOKEN"),
                                               args.online_only, args.native_only)
    except (OSError, ValueError, VerificationError) as error:
        result["error"] = str(error)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if (not args.manifest or result["gitops"]["verified"]) and (
        not args.url or result["runtime"]["verified"]) else 1


if __name__ == "__main__":
    sys.exit(main())
