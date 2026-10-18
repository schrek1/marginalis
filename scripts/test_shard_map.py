import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from shard_map import balance, durations_in_log, medians, simple_names

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
