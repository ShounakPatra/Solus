import unittest
from validate_release_source import validate


class ReleaseSourceTests(unittest.TestCase):
    def source(self, **changes):
        return dict(status="completed", conclusion="success", event="push", headBranch="main",
                    workflowName="Android CI", headSha="a" * 40, **changes)

    def test_matching_commit_is_accepted(self):
        validate(self.source(), "A" * 40 + "\n")

    def test_unrelated_release_tag_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            validate(self.source(), "b" * 40)

    def test_failed_incomplete_pr_and_wrong_branch_runs_are_rejected(self):
        for key, value in (("status", "in_progress"), ("conclusion", "failure"), ("event", "pull_request"),
                           ("headBranch", "feature"), ("workflowName", "Other workflow")):
            run = self.source()
            run[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate(run, "a" * 40)

    def test_missing_and_abbreviated_shas_are_rejected(self):
        for value in ("", "abc123", None):
            run = self.source()
            run["headSha"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate(run, "a" * 40)
        with self.assertRaises(ValueError):
            validate(self.source(), "main")
