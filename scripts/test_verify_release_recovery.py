import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("recovery", Path(__file__).with_name("verify-release-recovery.py"))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class ReleaseRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.repo = "owner/repository"
        self.sha = "a" * 40
        self.run = {"repository": {"full_name": self.repo}, "head_repository": {"full_name": self.repo},
                    "event": "workflow_dispatch", "status": "completed", "head_branch": "main", "head_sha": self.sha}
        self.workflow = {"path": ".github/workflows/agent-release.yml"}
        self.jobs = [{"id": i, "name": n, "conclusion": "success"} for i, n in enumerate(sorted(recovery.REQUIRED_JOBS))]
        self.artifacts = [{"name": n, "expired": False} for n in recovery.REQUIRED_ARTIFACTS]

    def check(self, sha=None):
        return recovery.validate(self.run, self.workflow, self.jobs, self.artifacts,
                                 self.sha if sha is None else sha, "v0.1.38", self.repo)

    def test_failed_publish_can_reuse_passed_gates(self):
        self.run["conclusion"] = "failure"
        self.jobs.append({"name": "Publish Agent release", "conclusion": "failure", "id": 100})
        self.assertEqual(self.check()["source_sha"], self.sha)

    def test_rejects_failed_or_missing_component_gate(self):
        self.jobs[0]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.check()
        self.jobs.pop(0)
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.check()

    def test_latest_attempt_must_pass(self):
        self.jobs.append({**self.jobs[0], "id": 100, "conclusion": "failure"})
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.check()

    def test_rejects_foreign_or_pull_request_source(self):
        self.run["head_repository"]["full_name"] = "fork/repository"
        with self.assertRaisesRegex(ValueError, "fork"):
            self.check()
        self.run["head_repository"]["full_name"] = self.repo
        self.run["event"] = "pull_request"
        with self.assertRaisesRegex(ValueError, "release run"):
            self.check()
        self.run["event"] = "workflow_dispatch"
        self.run["head_branch"] = "unreviewed"
        with self.assertRaisesRegex(ValueError, "exact release tag"):
            self.check()

    def test_rejects_different_workflow_or_source_tag(self):
        with self.assertRaisesRegex(ValueError, "exact source"):
            self.check("b" * 40)
        self.workflow["path"] = ".github/workflows/change-checks.yml"
        with self.assertRaisesRegex(ValueError, "Agent release workflow"):
            self.check()

    def test_rejects_expired_and_missing_artifacts(self):
        self.artifacts[0]["expired"] = True
        with self.assertRaisesRegex(ValueError, "missing or expired"):
            self.check()
        self.artifacts.pop(0)
        with self.assertRaisesRegex(ValueError, "missing or expired"):
            self.check()


if __name__ == "__main__":
    unittest.main()
