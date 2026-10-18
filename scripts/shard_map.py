"""Plans the shard map, sandbox/shards.json, that splits the sandbox scenarios between CI's e2e shards.

Usage:
  python3 scripts/shard_map.py REPO [--runs N] [--out FILE]   from the last N green CI runs on schreko_stable
  python3 scripts/shard_map.py --local FILE [--out FILE]      from one local run's durations-kotlin.json

CI's e2e-plan job runs it in every run, so the map follows the scenarios' live durations and nobody maintains it. Each
scenario prints its duration into the e2e log (`scenario-duration <class> <seconds> s`). A scenario's duration is its
median over the runs, so one slow runner does not move it, and the longest scenario goes first to the shard with the
least time so far. Ties go by name, so the same durations always give the same map. Without any durations it writes
no map and fails; the shards then split by name.
"""
from __future__ import annotations

import argparse
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
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout


def ci_history(repo: str, count: int) -> list[dict[str, float]]:
    runs = json.loads(gh("run", "list", "--repo", repo, "--workflow", "ci.yml", "--branch", "schreko_stable",
                         "--status", "success", "--limit", str(count), "--json", "databaseId"))
    history = []
    for run in runs:
        jobs = json.loads(gh("api", f"repos/{repo}/actions/runs/{run['databaseId']}/jobs?per_page=100"))["jobs"]
        durations = {}
        for job in jobs:
            if job["name"].startswith("e2e ("):
                # Not `gh api …/logs`: newer gh refuses to print a log with terminal escape sequences in it.
                durations |= durations_in_log(gh("run", "view", "--repo", repo, "--job", str(job["id"]), "--log"))
        if durations:
            history.append(durations)
    return history


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("repo", nargs="?", help="the GitHub repository whose green schreko_stable runs to read")
    source.add_argument("--local", type=Path, help="one local run's durations-kotlin.json")
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--out", type=Path, default=MAP)
    args = parser.parse_args()
    history = [simple_names(json.loads(args.local.read_text()))] if args.local else ci_history(args.repo, args.runs)
    history = [run for run in history if run]
    if not history:
        sys.exit("no scenario durations found: no run logged a scenario-duration line")
    shard_map = balance(medians(history), SHARDS)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(shard_map, indent=2) + "\n")
    for shard in range(1, SHARDS + 1):
        total = sum(placed["seconds"] for placed in shard_map["scenarios"].values() if placed["shard"] == shard)
        print(f"shard {shard}: {total / 60:.1f} scenario-minutes")
    print(f"{args.out} from {len(history)} run(s), {len(shard_map['scenarios'])} scenarios")


if __name__ == "__main__":
    main()
