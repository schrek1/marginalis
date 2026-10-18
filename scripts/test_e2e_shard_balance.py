import unittest

from e2e_shard_balance import imbalance, scenario_minutes


def job(name: str, started: str, completed: str) -> dict:
    return {"name": name, "steps": [
        {"name": "Set up job", "started_at": "2026-10-18T13:00:00Z", "completed_at": "2026-10-18T13:00:02Z"},
        {"name": "Sandbox scenarios", "started_at": started, "completed_at": completed},
        {"name": "Run actions/upload-artifact@v7", "started_at": completed, "completed_at": "2026-10-18T13:59:00Z"},
    ]}


class ScenarioMinutesTest(unittest.TestCase):

    def test_each_shard_counts_its_scenario_step_only(self):
        jobs = [
            job("e2e (1/2)", "2026-10-18T13:01:00Z", "2026-10-18T13:07:30Z"),
            job("e2e (2/2)", "2026-10-18T13:01:00Z", "2026-10-18T13:06:00Z"),
            job("verify", "2026-10-18T13:01:00Z", "2026-10-18T13:30:00Z"),
        ]

        self.assertEqual({1: 6.5, 2: 5.0}, scenario_minutes(jobs))

    def test_a_shard_whose_step_did_not_run_is_left_out(self):
        skipped = {"name": "e2e (2/2)", "steps": [{"name": "Sandbox scenarios", "started_at": None, "completed_at": None}]}

        self.assertEqual({1: 6.5}, scenario_minutes([job("e2e (1/2)", "2026-10-18T13:01:00Z", "2026-10-18T13:07:30Z"), skipped]))


class ImbalanceTest(unittest.TestCase):

    def test_shards_that_finish_together_pass(self):
        self.assertIsNone(imbalance({1: 5.5, 2: 5.8}))

    def test_one_shard_far_behind_the_other_is_reported(self):
        warning = imbalance({1: 5.0, 2: 14.0})

        self.assertIn("shard 2 took 14.0 min, shard 1 5.0 min", warning)

    def test_a_large_gap_between_long_shards_passes_while_their_ratio_is_small(self):
        self.assertIsNone(imbalance({1: 20.0, 2: 23.5}))

    def test_a_large_ratio_between_short_shards_passes_while_their_gap_is_small(self):
        self.assertIsNone(imbalance({1: 1.0, 2: 2.5}))


if __name__ == "__main__":
    unittest.main()
