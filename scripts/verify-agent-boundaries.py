#!/usr/bin/env python3
"""Static guard for the three independent Agent Native Image targets.

This check deliberately does not build Java.  It protects the dependency
boundary that keeps command-agent headless and small, desktop-companion
interactive, and browser-agent limited to its supervisor/protocol contract.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


MODULES = {
    "center": {"protocol"},
    "agent": {"protocol"},
    "desktop": {"protocol"},
    "browser": {"protocol"},
}

MAIN_CLASSES = {
    "center": "com.prodigalgal.remoteconnectmcp.center.RemoteConnectCenterApplication",
    "agent": "com.prodigalgal.remoteconnectmcp.agent.RemoteConnectAgentApplication",
    "desktop": "com.prodigalgal.remoteconnectmcp.desktop.DesktopCompanionApplication",
    "browser": "com.prodigalgal.remoteconnectmcp.browser.BrowserAgentApplication",
}

IMAGE_NAMES = {
    "center": "rcm-center",
    "agent": "rcm-agent",
    "desktop": "rcm-desktop-companion",
    "browser": "rcm-browser-agent",
}


def fail(message: str) -> None:
    print(f"agent-boundary: {message}", file=sys.stderr)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors: list[str] = []

    for module, allowed in MODULES.items():
        module_dir = root / "java" / module
        build_file = module_dir / "build.gradle.kts"
        if not build_file.is_file():
            errors.append(f"missing build file: {build_file.relative_to(root)}")
            continue
        build_text = build_file.read_text(encoding="utf-8")
        local_dependencies = set(re.findall(r'project\(\s*["\']:(\w+)["\']\s*\)', build_text))
        unexpected = sorted(local_dependencies - allowed)
        if unexpected:
            errors.append(
                f"{module} declares forbidden local dependencies: {', '.join(unexpected)}"
            )

        expected_main = MAIN_CLASSES[module]
        if f'mainClass.set("{expected_main}")' not in build_text:
            errors.append(f"{module} has an unexpected application main class")
        expected_image = IMAGE_NAMES[module]
        if f'imageName.set("{expected_image}")' not in build_text:
            errors.append(f"{module} has an unexpected Native Image name")

    agent_main = root / "java" / "agent" / "src" / "main" / "java"
    if agent_main.is_dir():
        for source in agent_main.rglob("*.java"):
            text = source.read_text(encoding="utf-8")
            if re.search(r"\b(?:java\.awt|javax\.imageio)(?:\.|\b)", text):
                errors.append(
                    f"command-agent imports GUI toolkit from {source.relative_to(root)}"
                )
            if source.name == "DesktopCompanionServer.java":
                errors.append(
                    f"desktop implementation is present in command-agent: {source.relative_to(root)}"
                )
    desktop_server = (
        root
        / "java"
        / "desktop"
        / "src"
        / "main"
        / "java"
        / "com"
        / "prodigalgal"
        / "remoteconnectmcp"
        / "desktop"
        / "DesktopCompanionServer.java"
    )
    if not desktop_server.is_file():
        errors.append("desktop companion implementation is missing from the desktop module")
    desktop_jni = (
        root
        / "java"
        / "desktop"
        / "src"
        / "main"
        / "resources"
        / "META-INF"
        / "native-image"
        / "com.prodigalgal.remoteconnectmcp"
        / "desktop"
        / "jni-config.json"
    )
    if not desktop_jni.is_file():
        errors.append("desktop companion JNI metadata is missing from the desktop module")
    else:
        jni_text = desktop_jni.read_text(encoding="utf-8")
        if "java.awt.Toolkit" not in jni_text:
            errors.append("desktop companion JNI metadata does not cover java.awt.Toolkit")
        # Robot-based screen capture initializes the AWT volatile-image path
        # through JNI.  Keep this class in the checked-in metadata so a
        # Native Image release cannot regress to a binary that starts but
        # fails on the first screenshot request.
        required_desktop_jni = (
            "sun.awt.image.VolatileSurfaceManager",
            "getButtonDownMasks",
            # Windows Robot/Toolkit reaches these peer classes through JNI;
            # keeping the platform entries here avoids a release that only
            # works on Linux while still passing the generic AWT check.
            "sun.awt.windows.WComponentPeer",
            "sun.awt.windows.WRobotPeer",
        )
        for required in required_desktop_jni:
            if required not in jni_text:
                errors.append(f"desktop companion JNI metadata does not cover {required}")

    for module in ("agent", "browser", "center"):
        resource_root = root / "java" / module / "src" / "main" / "resources"
        if not resource_root.is_dir():
            continue
        for metadata in resource_root.rglob("*config.json"):
            if "java.awt" in metadata.read_text(encoding="utf-8"):
                errors.append(
                    f"{module} carries desktop AWT JNI metadata: {metadata.relative_to(root)}"
                )

    for module, forbidden in {
        "desktop": ("com.prodigalgal.remoteconnectmcp.agent", "com.prodigalgal.remoteconnectmcp.browser", "com.prodigalgal.remoteconnectmcp.center"),
        "browser": ("com.prodigalgal.remoteconnectmcp.agent", "com.prodigalgal.remoteconnectmcp.desktop", "com.prodigalgal.remoteconnectmcp.center"),
    }.items():
        source_root = root / "java" / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in source_root.rglob("*.java"):
            text = source.read_text(encoding="utf-8")
            for package in forbidden:
                if package in text:
                    errors.append(
                        f"{module} imports another runtime module ({package}) in {source.relative_to(root)}"
                    )

    if errors:
        for error in errors:
            fail(error)
        return 1

    print("agent-boundary: command-agent, desktop-companion, browser-agent and center boundaries passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
