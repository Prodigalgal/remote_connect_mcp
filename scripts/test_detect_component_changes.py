import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "detect_component_changes", Path(__file__).with_name("detect-component-changes.py"))
detect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(detect)


class DetectComponentChangesTest(unittest.TestCase):
    def test_agent_baseline_uses_published_releases_only(self):
        self.assertEqual(['java-v0.1.38', 'java-v0.1.39-beta.1'], detect.published_agent_tags([
            {'tag_name':'java-v0.1.38', 'draft':False, 'published_at':'2026-10-05'},
            {'tag_name':'java-v0.1.39-beta.1', 'draft':False, 'published_at':'2026-10-05'},
            {'tag_name':'java-v0.1.39', 'draft':True, 'published_at':None},
            {'tag_name':'center-v0.1.39', 'draft':False, 'published_at':'2026-10-05'},
            {'tag_name':'java-v-malformed*', 'draft':False, 'published_at':'2026-10-05'},
        ]))

    def test_pr_checks_cover_release_inputs(self):
        self.assertTrue(detect.matches("center", "java/Dockerfile.center.native"))
        self.assertTrue(detect.matches("agent", "java/Dockerfile.agent.native"))
        self.assertTrue(detect.matches("agent", "scripts/browser-runtime/package-lock.json"))
        self.assertTrue(detect.matches("agent", "java/updater/src/main/java/UpdaterApplication.java"))
        self.assertTrue(detect.matches("agent", "java/Dockerfile.updater.native"))
        self.assertTrue(detect.matches("console", "web/Dockerfile"))
        self.assertFalse(detect.matches("center", "web/src/views/TasksView.tsx"))

    def test_canceled_center_release_is_included_after_console_only_push(self):
        with tempfile.TemporaryDirectory() as directory:
            old_cwd = os.getcwd()
            try:
                os.chdir(directory)
                def run(*args):
                    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()
                run("init", "-q")
                run("config", "user.name", "test")
                run("config", "user.email", "test@example.invalid")
                Path("java/center").mkdir(parents=True)
                Path("web").mkdir()
                Path("java/center/Center.java").write_text("old\n", encoding="utf-8")
                Path("web/App.tsx").write_text("old\n", encoding="utf-8")
                run("add", ".")
                run("commit", "-qm", "baseline")
                run("tag", "center-published-100-1")
                Path("java/center/Center.java").write_text("new\n", encoding="utf-8")
                run("commit", "-qam", "center changed")
                center_push = run("rev-parse", "HEAD")
                run("tag", "center-v2.0.0")  # A requested version is not proof of publication.
                Path("web/App.tsx").write_text("new\n", encoding="utf-8")
                run("commit", "-qam", "console changed")
                latest = run("rev-parse", "HEAD")

                self.assertFalse(any(detect.matches("center", path)
                                     for path in detect.changed_files(latest, center_push)))
                release_tag = detect.last_release("center", latest)
                self.assertEqual("center-published-100-1", release_tag)
                self.assertTrue(any(detect.matches("center", path)
                                    for path in detect.changed_files(latest, release_tag)))
            finally:
                os.chdir(old_cwd)

    def test_published_console_source_prevents_rebuild_on_unrelated_push(self):
        with tempfile.TemporaryDirectory() as directory:
            old_cwd = os.getcwd()
            try:
                os.chdir(directory)
                def run(*args):
                    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()
                run("init", "-q")
                run("config", "user.name", "test")
                run("config", "user.email", "test@example.invalid")
                Path("web").mkdir()
                Path("web/App.tsx").write_text("first\n", encoding="utf-8")
                run("add", ".")
                run("commit", "-qm", "published console")
                run("tag", "console-published-123-1")
                Path("README.md").write_text("documentation\n", encoding="utf-8")
                run("add", ".")
                run("commit", "-qm", "docs only")
                head = run("rev-parse", "HEAD")
                base = detect.last_release("console", head)
                self.assertEqual("console-published-123-1", base)
                self.assertFalse(any(detect.matches("console", path)
                                     for path in detect.changed_files(head, base)))
            finally:
                os.chdir(old_cwd)


if __name__ == "__main__":
    unittest.main()
