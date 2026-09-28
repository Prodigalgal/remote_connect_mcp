import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "push_gitops_component", Path(__file__).with_name("push-gitops-component.py"))
promotion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promotion)


class PushGitopsComponentTest(unittest.TestCase):
    def test_concurrent_distinct_component_promotions_both_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            remote = root / "remote.git"
            seed = root / "seed"
            center = root / "center"
            console = root / "console"

            def run(*args):
                return subprocess.check_output(["git", *map(str, args)], text=True, encoding="utf-8").strip()

            run("init", "--bare", "-q", remote)
            run("clone", "-q", remote, seed)
            run("-C", seed, "config", "user.name", "test")
            run("-C", seed, "config", "user.email", "test@example.invalid")
            (seed / "center.yaml").write_text("center: old\n", encoding="utf-8")
            (seed / "console.yaml").write_text("console: old\n", encoding="utf-8")
            run("-C", seed, "add", ".")
            run("-C", seed, "commit", "-qm", "baseline")
            run("-C", seed, "branch", "-M", "main")
            run("-C", seed, "push", "-q", "origin", "main")
            run("-C", remote, "symbolic-ref", "HEAD", "refs/heads/main")
            run("clone", "-q", remote, center)
            run("clone", "-q", remote, console)
            for checkout in (center, console):
                run("-C", checkout, "config", "user.name", "test")
                run("-C", checkout, "config", "user.email", "test@example.invalid")
            (center / "center.yaml").write_text("center: new\n", encoding="utf-8")
            (console / "console.yaml").write_text("console: new\n", encoding="utf-8")
            for checkout in (center, console):
                run("-C", checkout, "add", ".")
                run("-C", checkout, "commit", "-qm", "promote")

            promotion.push(center)
            promotion.push(console)
            self.assertEqual("center: new", run("-C", console, "show", "HEAD:center.yaml"))
            self.assertEqual("console: new", run("-C", console, "show", "HEAD:console.yaml"))


if __name__ == "__main__":
    unittest.main()
