import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("verify_release", Path(__file__).with_name("verify-release.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerifyReleaseTest(unittest.TestCase):
    def test_center_manifest_requires_digest_and_version(self):
        digest = "sha256:" + "a" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "center.yaml"
            path.write_text("""
image: ghcr.io/prodigalgal/remote-connect-mcp-center-java@%s
env:
  - name: RCM_CENTER_VERSION
    value: v1.2.3
""" % digest, encoding="utf-8")
            self.assertTrue(verify.verify_manifest("center", "v1.2.3", path, digest)["verified"])
            with self.assertRaises(verify.VerificationError):
                verify.verify_manifest("center", "v1.2.4", path, digest)

    def test_agent_fleet_reports_partial_coverage_explicitly(self):
        machines = [
            {"id": "one", "version": "v2.0.0", "online": True},
            {"id": "two", "version": "v1.0.0", "online": False},
        ]
        campaigns = [{"id": "upgrade-1", "version": "v2.0.0", "status": "running"}]
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            full = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", False)
        self.assertFalse(full["verified"])
        self.assertEqual(["two"], full["offline"])
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            reachable = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", True)
        self.assertTrue(reachable["verified"])
        self.assertEqual("online_only", reachable["coverage"])

    def test_console_runtime_requires_source_commit_when_given(self):
        with patch.object(verify, "fetch_json", return_value={"service": "console", "version": "v1.2.3", "source_sha": "abc"}):
            self.assertTrue(verify.verify_runtime("console", "v1.2.3", "https://console.example", "abc", None, False)["verified"])
            with self.assertRaises(verify.VerificationError):
                verify.verify_runtime("console", "v1.2.3", "https://console.example", "def", None, False)

    def test_agent_component_plan_requires_completed_target_and_component(self):
        machines = [{"id": "one", "os": "linux", "arch": "amd64", "version": "v2.0.0", "online": True,
                     "capabilities": ["command", "browser"]}]
        campaigns = [{"id": "upgrade-2", "version": "v2.0.0", "status": "paused",
                      "component_plans": {"linux/amd64": [{"component": "browser-agent"}]},
                      "targets": [{"machine_id": "one", "status": "failed",
                                   "component_statuses": {"browser-agent": "failed"}}]}]
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            result = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", False)
        self.assertFalse(result["verified"])
        self.assertEqual("browser-agent", result["components_unverified"][0]["component"])

    def test_command_only_machine_does_not_require_optional_component_status(self):
        machines = [{"id": "one", "os": "linux", "arch": "amd64", "version": "v2.0.0", "online": True,
                     "capabilities": ["command"]}]
        campaigns = [{"id": "upgrade-3", "version": "v2.0.0", "status": "completed",
                      "component_plans": {"linux/amd64": [{"component": "browser-agent"}]},
                      "targets": [{"machine_id": "one", "status": "completed", "component_statuses": {}}]}]
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            result = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", False)
        self.assertTrue(result["verified"])
        self.assertEqual([], result["components_unverified"])

    def test_browser_runtime_requires_explicit_native_only_scope(self):
        machines = [{"id": "browser-host", "os": "windows", "arch": "amd64", "version": "v2.0.0",
                     "online": True, "capabilities": ["command", "browser"]}]
        campaigns = [{"id": "upgrade-4", "version": "v2.0.0", "status": "completed",
                      "component_plans": {"windows/amd64": [{"component": "browser-agent"}]},
                      "targets": [{"machine_id": "browser-host", "status": "completed",
                                   "component_statuses": {"browser-agent": "completed"}}]}]
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            complete = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", False)
        self.assertFalse(complete["verified"])
        self.assertEqual(["browser-host"], complete["browser_runtime"]["machines"])
        with patch.object(verify, "pages", side_effect=[machines, campaigns]):
            native = verify.verify_runtime("agent", "v2.0.0", "https://center.example", None, "token", False, True)
        self.assertTrue(native["verified"])
        self.assertEqual("all_registered_live_native_only", native["coverage"])


if __name__ == "__main__":
    unittest.main()
