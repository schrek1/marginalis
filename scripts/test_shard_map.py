import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from shard_map import balance, ci_history, durations_in_log, medians, simple_names

SCRIPT = Path(__file__).resolve().parent / "shard_map.py"


class CommandLineTest(unittest.TestCase):

    def test_the_map_goes_where_out_points(self):
        with tempfile.TemporaryDirectory() as tmp:
            durations = Path(tmp, "durations-kotlin.json")
            durations.write_text(json.dumps({"dev.marginalis.plugin.sandbox.scenarios.A": 90.0, "x.B": 30.0}))
            out = Path(tmp, "plan", "shards.json")

            run = subprocess.run([sys.executable, str(SCRIPT), "--local", str(durations), "--out", str(out)],
                                 capture_output=True, text=True)

            self.assertEqual(0, run.returncode, run.stderr)
            self.assertEqual({"A": 1, "B": 2}, {name: placed["shard"] for name, placed in json.loads(out.read_text())["scenarios"].items()})

    def test_no_durations_write_no_map_and_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            durations = Path(tmp, "durations-kotlin.json")
            durations.write_text("{}")
            out = Path(tmp, "shards.json")

            run = subprocess.run([sys.executable, str(SCRIPT), "--local", str(durations), "--out", str(out)],
                                 capture_output=True, text=True)

            self.assertNotEqual(0, run.returncode)
            self.assertFalse(out.exists())


class FakeGitHub:
    """Answers the gh calls ci_history makes: green runs, their jobs, the e2e jobs' logs. A run id in `failing`
    fails its jobs call `failing[id]` times in a row."""

    def __init__(self, logs: dict[int, str], failing: dict[int, int] | None = None):
        self.logs = logs
        self.failing = dict(failing or {})

    def __call__(self, *args: str) -> str:
        if args[:2] == ("run", "list"):
            return json.dumps([{"databaseId": run} for run in self.logs])
        if args[0] == "api":
            run = int(args[1].split("/runs/")[1].split("/")[0])
            if self.failing.get(run, 0) > 0:
                self.failing[run] -= 1
                raise subprocess.CalledProcessError(1, ["gh", *args], stderr="HTTP 502: Bad Gateway")
            return json.dumps({"jobs": [{"name": "e2e (1/2)", "id": run * 10}, {"name": "e2e", "id": run * 10 + 1}]})
        return self.logs[int(args[args.index("--job") + 1]) // 10]


class CiHistoryTest(unittest.TestCase):

    def test_a_run_whose_jobs_cannot_be_read_is_left_out_and_the_others_count(self):
        # GIVEN - three green runs, the middle one's jobs unreadable however often they are asked for
        github = FakeGitHub({1: "scenario-duration A 10.0 s", 2: "scenario-duration A 99.0 s", 3: "scenario-duration A 30.0 s"},
                            failing={2: 99})
        notes = []

        # WHEN - the history is read
        history = ci_history("acme/shop", 3, gh=github, report=notes.append)

        # THEN - the plan uses the two readable runs and says which run it left out and why
        self.assertEqual([{"A": 10.0}, {"A": 30.0}], history)
        self.assertTrue(any("run 2" in note and "Bad Gateway" in note for note in notes), notes)

    def test_a_call_that_fails_once_is_asked_again(self):
        github = FakeGitHub({1: "scenario-duration A 10.0 s"}, failing={1: 1})

        self.assertEqual([{"A": 10.0}], ci_history("acme/shop", 1, gh=github, report=lambda note: None))

    def test_each_run_reports_how_many_durations_it_gave(self):
        # GIVEN - one run with two scenarios and one whose log holds none
        github = FakeGitHub({1: "scenario-duration A 10.0 s\nscenario-duration B 5.0 s", 2: "no durations here"})
        notes = []

        # WHEN - the history is read
        history = ci_history("acme/shop", 2, gh=github, report=notes.append)

        # THEN - the empty run is no history, and the notes say what each run gave
        self.assertEqual([{"A": 10.0, "B": 5.0}], history)
        self.assertIn("run 1: 2 scenario durations", notes)
        self.assertIn("run 2: 0 scenario durations", notes)


class DurationsInLogTest(unittest.TestCase):

    def test_each_scenario_line_of_a_job_log_counts(self):
        log = (
            "2026-10-18T13:30:52.1Z     sandbox stopped\n"
            "2026-10-18T13:30:52.2Z     scenario-duration AgentUserLoopTest 138.4 s\n"
            "2026-10-18T13:39:08.0Z     INFO  select_opened_thread_in_review_diff ends at 13:39:08\n"
            "2026-10-18T13:39:08.1Z     scenario-duration LiveByDefaultTest 246.0 s\n"
        )

        self.assertEqual({"AgentUserLoopTest": 138.4, "LiveByDefaultTest": 246.0}, durations_in_log(log))


class MediansTest(unittest.TestCase):

    def test_each_scenario_takes_the_median_of_the_runs_it_appears_in(self):
        runs = [{"A": 100.0, "B": 60.0}, {"A": 300.0, "B": 62.0}, {"A": 110.0}]

        self.assertEqual({"A": 110.0, "B": 61.0}, medians(runs))


class SimpleNamesTest(unittest.TestCase):

    def test_the_local_durations_file_keys_become_class_names(self):
        local = {"dev.marginalis.plugin.sandbox.scenarios.LiveByDefaultTest": 246.0}

        self.assertEqual({"LiveByDefaultTest": 246.0}, simple_names(local))


class BalanceTest(unittest.TestCase):

    def test_the_longest_scenario_goes_first_to_the_shard_with_the_least_time_so_far(self):
        seconds = {"A": 90.0, "B": 240.0, "C": 100.0, "D": 160.0, "E": 150.0}

        shards = balance(seconds, 2)

        # 340 s and 400 s
        self.assertEqual({"A": 2, "B": 1, "C": 1, "D": 2, "E": 2},
                         {name: placement["shard"] for name, placement in shards["scenarios"].items()})
        self.assertEqual(2, shards["shards"])

    def test_each_scenario_keeps_its_duration_in_the_map(self):
        self.assertEqual({"shard": 1, "seconds": 240.6}, balance({"A": 240.6}, 2)["scenarios"]["A"])

    def test_equal_durations_split_the_same_way_whatever_their_order(self):
        seconds = {"A": 60.0, "B": 60.0, "C": 60.0, "D": 60.0}

        self.assertEqual(balance(seconds, 2), balance(dict(reversed(list(seconds.items()))), 2))
        self.assertEqual({"A": 1, "B": 2, "C": 1, "D": 2},
                         {name: placement["shard"] for name, placement in balance(seconds, 2)["scenarios"].items()})


if __name__ == "__main__":
    unittest.main()
