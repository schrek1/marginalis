import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import stable_channel

TABLE = "| Key | Feature | Branch | Fork PR | Upstream issue | Applied head |\n|---|---|---|---|---|---|\n"


class StableHistory:
    """A small schreko_stable: features listed in STABLE.md first, then the file retired, then features by commit."""

    def __init__(self, root: Path):
        self.root = root
        self.git("init", "-q", "-b", "schreko_stable")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Test")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, subject: str, stable_md: str | None = None, retire: bool = False, touch: str = "src.txt") -> None:
        path = self.root / touch
        path.write_text(path.read_text() + subject + "\n" if path.exists() else subject + "\n")
        self.git("add", touch)
        if stable_md is not None:
            (self.root / "STABLE.md").write_text(stable_md)
            self.git("add", "STABLE.md")
        if retire:
            self.git("rm", "-q", "STABLE.md")
        self.git("commit", "-q", "-m", subject)

    def tag(self, name: str) -> None:
        self.git("tag", name)


class StableFeaturesTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.history = StableHistory(Path(self.dir.name))
        self.cwd = os.getcwd()
        os.chdir(self.dir.name)

    def tearDown(self):
        os.chdir(self.cwd)
        self.dir.cleanup()

    def build(self):
        # GIVEN - two features listed in STABLE.md, a release, the list retired, then features landing by commit only
        self.history.commit("MARG-10: Old feature (#5)", stable_md=TABLE + "| MARG-10 | Old feature | `b` | — | — | `x` |\n")
        self.history.commit("Merge pull request #6 from acme/fix/MARG-11-older",
                            stable_md=TABLE + "| MARG-10 | Old feature | `b` | — | — | `x` |\n| MARG-11 | Older one | `c` | — | — | `y` |\n")
        self.history.tag("0.1.0-schreko.1")
        self.history.commit("MARG-20: The list comes from history (#7)", retire=True)
        self.history.commit("Sync upstream 0.1.1 (#8)")
        self.history.commit("MARG-21: A newer feature (#9)")
        self.history.tag("0.1.0-schreko.2")

    def test_a_tag_after_the_list_was_retired_has_the_listed_features_and_every_feature_commit_since(self):
        self.build()

        features = stable_channel.stable_features("0.1.0-schreko.2")

        self.assertEqual([("MARG-10", "Old feature"), ("MARG-11", "Older one"),
                          ("MARG-20", "The list comes from history"), ("MARG-21", "A newer feature")], features)

    def test_a_tag_from_before_has_its_stable_md_rows(self):
        self.build()

        self.assertEqual([("MARG-10", "Old feature"), ("MARG-11", "Older one")], stable_channel.stable_features("0.1.0-schreko.1"))

    def test_a_release_lists_the_features_it_adds_and_the_other_commits_apart(self):
        self.build()

        added, others = stable_channel.news("0.1.0-schreko.2", "0.1.0-schreko.1")

        self.assertEqual(["MARG-20 The list comes from history", "MARG-21 A newer feature"], added)
        self.assertEqual(["Sync upstream 0.1.1 (#8)"], others)


if __name__ == "__main__":
    unittest.main()
