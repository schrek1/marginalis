"""Rebuilds sandbox/shards.json, the committed map that splits the sandbox scenarios between CI's e2e shards.

Usage:
  python3 scripts/shard_map.py REPO [--runs N]   from the last N green CI runs on schreko_stable (default 10)
  python3 scripts/shard_map.py --local FILE      from one local run's durations-kotlin.json

Each scenario prints its duration into the e2e log (`scenario-duration <class> <seconds> s`). A scenario's duration is
its median over the runs, so one slow runner does not move it, and the longest scenario goes first to the shard with
the least time so far. Ties go by name, so the same durations always give the same map.
"""
from __future__ import annotations

import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

SHARDS = 2
DURATION = re.compile(r"scenario-duration (\w+) ([0-9.]+) s")
MAP = Path(__file__).resolve().parent.parent / "sandbox" / "shards.json"


def durations_in_log(log: str) -> dict[str, float]:
    return {match.group(1): float(match.group(2)) for match in DURATION.finditer(log)}


def medians(runs: list[dict[str, float]]) -> dict[str, float]:
    names = sorted({name for run in runs for name in run})
    return {name: statistics.median(run[name] for run in runs if name in run) for name in names}


def simple_names(durations: dict[str, float]) -> dict[str, float]:
    return {name.rsplit(".", 1)[-1]: seconds for name, seconds in durations.items()}


def balance(seconds: dict[str, float], shards: int) -> dict:
    load = [0.0] * shards
    placed = {}
    for name in sorted(seconds, key=lambda name: (-seconds[name], name)):
        shard = load.index(min(load))
        load[shard] += seconds[name]
        placed[name] = {"shard": shard + 1, "seconds": seconds[name]}
    return {"shards": shards, "scenarios": dict(sorted(placed.items()))}


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def ci_history(repo: str, count: int) -> list[dict[str, float]]:
    runs = json.loads(gh("run", "list", "--repo", repo, "--workflow", "ci.yml", "--branch", "schreko_stable",
                         "--status", "success", "--limit", str(count), "--json", "databaseId"))
    history = []
    for run in runs:
        jobs = json.loads(gh("api", f"repos/{repo}/actions/runs/{run['databaseId']}/jobs?per_page=100"))["jobs"]
        durations = {}
        for job in jobs:
            if job["name"].startswith("e2e"):
                durations |= durations_in_log(gh("api", f"repos/{repo}/actions/jobs/{job['id']}/logs"))
        if durations:
            history.append(durations)
    return history


def main() -> None:
    args = sys.argv[1:]
    if args[:1] == ["--local"] and len(args) == 2:
        history = [simple_names(json.loads(Path(args[1]).read_text()))]
    elif len(args) in (1, 3) and not args[0].startswith("-") and (len(args) == 1 or args[1] == "--runs"):
        history = ci_history(args[0], int(args[2]) if len(args) == 3 else 10)
    else:
        sys.exit(__doc__)
    if not history:
        sys.exit("no scenario durations found: the runs predate the scenario-duration log line")
    shard_map = balance(medians(history), SHARDS)
    MAP.write_text(json.dumps(shard_map, indent=2) + "\n")
    for shard in range(1, SHARDS + 1):
        total = sum(placed["seconds"] for placed in shard_map["scenarios"].values() if placed["shard"] == shard)
        print(f"shard {shard}: {total / 60:.1f} scenario-minutes")
    print(f"{MAP} from {len(history)} run(s), {len(shard_map['scenarios'])} scenarios")


if __name__ == "__main__":
    main()
