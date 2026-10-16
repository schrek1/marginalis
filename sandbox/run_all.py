"""Every scenario against one checkout: the sandbox half of the stable gate.

Each scenario restarts the sandbox from the checkout itself. Writes a Markdown summary (one row per scenario,
every FAIL line listed) for the PR into schreko_stable. `--tag smoke` runs only the scenarios of the runbooks
tagged smoke. `--jobs N` (Docker mode only) builds the checkout once and runs N sandboxes side by side, each
taking the next scenario from one queue, longest first; see docs/testing.md.

    py sandbox/run_all.py [--feature-dir <checkout>] [--summary gate.md] [--only a,b | --tag smoke] [--jobs N]
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

import sandbox

HERE = Path(__file__).resolve().parent
SCENARIOS = HERE / "scenarios"


RUNBOOKS = HERE / "runbooks"
TAGS = re.compile(r"^- \*\*Tags:\*\*\s*(.+)$", re.MULTILINE)
SCENARIO = re.compile(r"^- \*\*Scenario:\*\*\s*`(\w+)\.py`", re.MULTILINE)


def scenario_names() -> list[str]:
    return sorted(path.stem for path in SCENARIOS.glob("*.py") if not path.stem.startswith("_"))


def tagged_scenario_names(tag: str) -> list[str]:
    names = set()
    for runbook in RUNBOOKS.glob("*.md"):
        if runbook.name == "README.md":
            continue
        text = runbook.read_text(encoding="utf-8")
        tags, scenario = TAGS.search(text), SCENARIO.search(text)
        if tags and scenario and tag in (part.strip() for part in tags.group(1).split(",")):
            names.add(scenario.group(1))
    return sorted(names)


def run_one(name: str, feature_dir: Path, log_dir: Path,
            env: dict | None = None) -> tuple[list[str], list[str], int, float]:
    started = time.monotonic()
    log = log_dir / f"{name}.log"
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run([sys.executable, str(SCENARIOS / f"{name}.py"), "--feature-dir", str(feature_dir)],
                                cwd=HERE, stdout=out, stderr=subprocess.STDOUT, env=env)
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    passed = [line for line in lines if line.startswith("PASS")]
    failed = [line for line in lines if line.startswith("FAIL")]
    if result.returncode != 0 and not failed:
        failed = [f"FAIL  {name} exited {result.returncode} without a FAIL line; see {log}"]
    return passed, failed, result.returncode, time.monotonic() - started


# Parallel runs live next to the sandbox runtime, one folder per run, so runs of several checkouts never share a
# project, a log or a build.
RUNS = sandbox.RUNTIME.parent / "marginalis-sandbox-runs"
DURATIONS = RUNS / "durations.json"


def free_robot_ports(count: int) -> list[int]:
    """Robot ports from 8082 up that neither listen nor belong to a sandbox container (one between restarts does not
    listen). Their REST ports (+10000) stay below Windows' dynamic range."""
    containers = set(subprocess.run(["docker", "ps", "-a", "--format", "{{.Names}}"], capture_output=True,
                                    text=True, check=True).stdout.split())
    ports, port = [], 8082
    while len(ports) < count and port < 9000:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
        except OSError:
            if f"marginalis-sandbox-{port}" not in containers:
                ports.append(port)
        port += 1
    return ports


def failure_lines(name: str, failed: list[str]) -> list[str]:
    return [f"- `{name}`: {re.sub(r'^FAIL\s+', '', line)}" for line in failed]


def run_parallel(names: list[str], feature_dir: Path, jobs: int) -> tuple[list[str], list[str]]:
    """One build, then `jobs` sandboxes that each take the next scenario from a queue, longest first."""
    if not sandbox.DOCKER:
        sys.exit("--jobs needs MARGINALIS_SANDBOX_DOCKER=1: several sandbox IDEs need a screen each")
    run = RUNS / f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}"
    prebuilt = run / "prebuilt"
    sandbox.build_in_docker(feature_dir, prebuilt)
    durations = json.loads(DURATIONS.read_text(encoding="utf-8")) if DURATIONS.exists() else {}
    queue = collections.deque(sorted(names, key=lambda name: -durations.get(name, 3.0)))
    lock = threading.Lock()
    results: dict[str, tuple[list[str], list[str], float]] = {}
    ports = free_robot_ports(min(jobs, len(names)))

    def work(port: int) -> None:
        runtime = run / str(port)
        log_dir = runtime / "gate-logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ, MARGINALIS_ROBOT_PORT=str(port), MARGINALIS_SANDBOX_RUNTIME=str(runtime),
                   MARGINALIS_SANDBOX_PREBUILT=str(prebuilt))
        while True:
            with lock:
                if not queue:
                    return
                name = queue.popleft()
            print(f"== {name} (robot port {port})", flush=True)
            passed, failed, _, seconds = run_one(name, feature_dir, log_dir, env)
            with lock:
                results[name] = (passed, failed, seconds)
                print(f"{name}: {len(passed)} passed, {len(failed)} failed in {seconds / 60:.1f} min", flush=True)

    workers = [threading.Thread(target=work, args=(port,)) for port in ports]
    try:
        for worker in workers:
            worker.start()
            time.sleep(2)
        for worker in workers:
            worker.join()
    finally:
        # Also when the run is stopped: leftover sandboxes would eat the next run's CPU, memory and ports.
        subprocess.run(["docker", "rm", "-f", *(f"marginalis-sandbox-{port}" for port in ports)], capture_output=True)
    durations.update({name: seconds / 60 for name, (_, _, seconds) in results.items()})
    DURATIONS.parent.mkdir(parents=True, exist_ok=True)
    DURATIONS.write_text(json.dumps(durations, indent=1, sort_keys=True), encoding="utf-8")
    rows, failures = [], []
    for name in names:
        passed, failed, seconds = results.get(name, ([], [f"FAIL  {name} never ran"], 0.0))
        rows.append(f"| `{name}` | {len(passed)} | {len(failed)} | {seconds / 60:.1f} min |")
        failures += failure_lines(name, failed)
    return rows, failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    parser.add_argument("--summary", type=Path, help="write the Markdown summary here")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--only", help="comma-separated scenario names")
    selection.add_argument("--tag", help="the scenarios of the runbooks carrying this tag, e.g. smoke")
    parser.add_argument("--jobs", type=int, default=6 if sandbox.DOCKER else 1,
                        help="sandboxes side by side, Docker mode only (default 6, measured in docs/testing.md)")
    args = parser.parse_args()
    feature_dir = args.feature_dir.resolve()
    if args.only:
        names = args.only.split(",")
    elif args.tag:
        names = tagged_scenario_names(args.tag)
        if not names:
            sys.exit(f"no runbook in {RUNBOOKS} is tagged {args.tag}")
    else:
        names = scenario_names()
    started = time.monotonic()
    if args.jobs > 1:
        rows, failures = run_parallel(names, feature_dir, args.jobs)
    else:
        log_dir = sandbox.RUNTIME / "gate-logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        rows, failures = [], []
        for name in names:
            print(f"== {name}", flush=True)
            passed, failed, code, seconds = run_one(name, feature_dir, log_dir)
            for line in passed + failed:
                print(line, flush=True)
            rows.append(f"| `{name}` | {len(passed)} | {len(failed)} | {seconds / 60:.1f} min |")
            failures += failure_lines(name, failed)

    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=feature_dir, text=True,
                          capture_output=True).stdout.strip()
    summary = "\n".join([
        f"Sandbox scenarios against `{head}`{f' ({args.jobs} sandboxes in parallel)' if args.jobs > 1 else ''}, "
        f"{(time.monotonic() - started) / 60:.1f} min: **{'all passed' if not failures else f'{len(failures)} failed'}**",
        "",
        "| scenario | pass | fail | time |",
        "|---|---|---|---|",
        *rows,
        *(["", "Failures:", *failures] if failures else []),
    ])
    print("\n" + summary)
    if args.summary:
        args.summary.write_text(summary + "\n", encoding="utf-8")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
