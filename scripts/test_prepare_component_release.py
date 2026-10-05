import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).with_name("prepare-component-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

SHA = "a" * 40
OTHER = "b" * 40


class ComponentReleaseTest(unittest.TestCase):
    def test_main_automatically_publishes_stable_per_component(self):
        tags = {"java-v0.1.38": OTHER, "center-v0.1.39": OTHER,
                "console-v0.1.32": OTHER, "java-v0.0.0-main.40": OTHER}
        for component, version in (("agent", "v0.1.39"), ("center", "v0.1.40"), ("console", "v0.1.33")):
            values = release.coordinates(component, {"EVENT_NAME": "push", "REF_TYPE": "branch",
                "REF_NAME": "main", "SHA": SHA}, tags)
            self.assertEqual(version, values["version"])
            self.assertEqual("false", values["prerelease"])
            self.assertEqual("true", values["deploy"])

    def test_numeric_order_and_unpublished_reservations_are_preserved(self):
        self.assertEqual("v0.2.11", release.next_stable_version("agent", {
            "java-v0.2.9": OTHER, "java-v0.2.10": OTHER, "java-v0.3.0-beta.1": OTHER}, SHA))

    def test_retry_reuses_its_exact_source_reservation(self):
        self.assertEqual("v0.1.39", release.next_stable_version("agent", {
            "java-v0.1.39": SHA, "java-v0.1.40": OTHER}, SHA))

    def test_explicit_prerelease_still_targets_staging(self):
        values = release.coordinates("center", {"EVENT_NAME": "workflow_dispatch",
            "INPUT_VERSION": "center-v0.2.0-beta.1", "INPUT_PRERELEASE": "true"}, {})
        self.assertEqual("center-v0.2.0-beta.1", values["tag"])
        self.assertEqual("true", values["prerelease"])

    def test_tag_cannot_replace_another_source(self):
        with patch.object(release.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                release.pin_tag("owner/repo", "java-v0.1.39", SHA, {"java-v0.1.39": OTHER})
            run.assert_not_called()

    def test_tag_pin_uses_exact_sha_in_json_body(self):
        with patch.object(release.subprocess, "run") as run:
            release.pin_tag("owner/repo", "java-v0.1.39", SHA, {})
            import json
            self.assertEqual({"ref": "refs/tags/java-v0.1.39", "sha": SHA}, json.loads(run.call_args.kwargs["input"]))

    def test_unrelated_branch_and_foreign_tag_cannot_publish(self):
        for env in ({"EVENT_NAME": "push", "REF_NAME": "feature", "SHA": SHA},
                    {"EVENT_NAME": "push", "REF_TYPE": "tag", "REF_NAME": "center-v0.2.0"}):
            with self.assertRaises(ValueError):
                release.coordinates("agent", env, {})


if __name__ == "__main__":
    unittest.main()
