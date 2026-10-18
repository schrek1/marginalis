"""Reports how long each e2e shard spent on its scenarios, and warns when they are far apart.

Usage: python3 scripts/e2e_shard_balance.py REPO RUN_ID

The shards follow the map e2e-plan planned from the last green runs' durations. A scenario whose time just changed a lot
pulls them apart until the medians catch up, and the warning names the gap (docs/testing.md). A gap counts only when it
is both large in minutes and large against the faster shard: one runner's slow day moves both numbers a little, a
misplaced scenario one a lot.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime

JOB = re.compile(r"^e2e \((\d+)/\d+\)$")
STEP = "Sandbox scenarios"
MIN_GAP_MINUTES = 3.0
MIN_RATIO = 1.5


def scenario_minutes(jobs: list[dict]) -> dict[int, float]:
    minutes = {}
    for job in jobs:
        shard = JOB.match(job["name"])
        step = next((step for step in job.get("steps", []) if step["name"] == STEP), None)
        if shard and step and step.get("started_at") and step.get("completed_at"):
            took = datetime.fromisoformat(step["completed_at"]) - datetime.fromisoformat(step["started_at"])
            minutes[int(shard.group(1))] = took.total_seconds() / 60
    return minutes


def imbalance(minutes: dict[int, float]) -> str | None:
    slowest = max(minutes, key=minutes.get)
    fastest = min(minutes, key=minutes.get)
    gap = minutes[slowest] - minutes[fastest]
    if gap < MIN_GAP_MINUTES or minutes[slowest] < MIN_RATIO * minutes[fastest]:
        return None
    return (f"shard {slowest} took {minutes[slowest]:.1f} min, shard {fastest} {minutes[fastest]:.1f} min: "
            f"the live plan split them unevenly; a scenario's time may have changed (docs/testing.md)")


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    repo, run = sys.argv[1:]
    listed = subprocess.run(["gh", "api", f"repos/{repo}/actions/runs/{run}/jobs?per_page=100"],
                            check=True, capture_output=True, text=True).stdout
    minutes = scenario_minutes(json.loads(listed)["jobs"])
    for shard in sorted(minutes):
        print(f"e2e shard {shard}: scenarios {minutes[shard]:.1f} min")
    if len(minutes) < 2:
        print("fewer than two shards ran their scenarios; nothing to compare")
        return
    warning = imbalance(minutes)
    if warning:
        print(f"::warning title=e2e shards apart::{warning}")


if __name__ == "__main__":
    main()
