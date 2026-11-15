import json
import unittest
from unittest import mock

import stable_release

RELEASE_COMMENT = "In `schreko_stable` since **0.2.8-schreko.85** (tag `0.2.8-schreko.85`, commit `e93c549`)."


def issue_json(state: str, labels: list[str], comments: list[str] = ()) -> str:
    return json.dumps({"state": state, "labels": [{"name": name} for name in labels],
                       "comments": [{"body": body} for body in comments]})


def told(issue: str) -> list[list[str]]:
    """The `gh` calls that tell an issue about a release, given what `gh issue view` returns for it."""
    calls = []

    def fake_gh(*args: str) -> str:
        if args[:2] == ("issue", "view"):
            return issue
        calls.append(list(args))
        return ""

    with mock.patch.object(stable_release, "gh", fake_gh):
        stable_release.tell_issue(stable_release.Actions(dry_run=False), 224, RELEASE_COMMENT)
    return calls


class TellIssueTest(unittest.TestCase):

    def test_issue_with_stage_label_is_commented_relabelled_and_closed(self):
        calls = told(issue_json("OPEN", ["bug", "in-progress"]))

        self.assertEqual(["issue", "comment", "224"], calls[0][:3])
        self.assertEqual(["issue", "edit", "224", "--repo", "schrek1/marginalis", "--add-label", "in-stable",
                          "--remove-label", "in-progress"], calls[1])
        self.assertEqual(["issue", "close", "224"], calls[2][:3])

    def test_reopened_issue_carrying_in_stable_from_an_earlier_release_is_told_again(self):
        earlier = "In `schreko_stable` since **0.2.8-schreko.83** (tag `0.2.8-schreko.83`, commit `a28abb4`)."

        calls = told(issue_json("OPEN", ["bug", "in-stable", "request-merge"], [earlier]))

        self.assertEqual(["comment", "edit", "close"], [call[1] for call in calls])
        self.assertIn("request-merge", calls[1])

    def test_rerun_for_the_same_release_posts_and_changes_nothing(self):
        calls = told(issue_json("CLOSED", ["bug", "in-stable"], [RELEASE_COMMENT]))

        self.assertEqual([], calls)

    def test_pending_issue_stays_open(self):
        calls = told(issue_json("OPEN", ["bug", "pending"]))

        self.assertEqual(["comment", "edit"], [call[1] for call in calls])


if __name__ == "__main__":
    unittest.main()
