"""Every scenario against one checkout, one after another: the sandbox half of the stable gate.

Each scenario restarts the sandbox from the checkout itself. Writes a Markdown summary (one row per scenario,
every FAIL line listed) for the PR into schreko_stable. `--tag smoke` runs only the scenarios of the runbooks
tagged smoke.

    py sandbox/run_all.py [--feature-dir <checkout>] [--summary gate.md] [--only a,b | --tag smoke]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
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


def run_one(name: str, feature_dir: Path, log_dir: Path) -> tuple[list[str], list[str], int, float]:
    started = time.monotonic()
    log = log_dir / f"{name}.log"
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run([sys.executable, str(SCENARIOS / f"{name}.py"), "--feature-dir", str(feature_dir)],
                                cwd=HERE, stdout=out, stderr=subprocess.STDOUT)
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    passed = [line for line in lines if line.startswith("PASS")]
    failed = [line for line in lines if line.startswith("FAIL")]
    if result.returncode != 0 and not failed:
        failed = [f"FAIL  {name} exited {result.returncode} without a FAIL line; see {log}"]
    return passed, failed, result.returncode, time.monotonic() - started


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    parser.add_argument("--summary", type=Path, help="write the Markdown summary here")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--only", help="comma-separated scenario names")
    selection.add_argument("--tag", help="the scenarios of the runbooks carrying this tag, e.g. smoke")
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
    log_dir = sandbox.RUNTIME / "gate-logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    rows, failures = [], []
    for name in names:
        print(f"== {name}", flush=True)
        passed, failed, code, seconds = run_one(name, feature_dir, log_dir)
        for line in passed + failed:
            print(line, flush=True)
        rows.append(f"| `{name}` | {len(passed)} | {len(failed)} | {seconds / 60:.1f} min |")
        failures += [f"- `{name}`: {re.sub(r'^FAIL\s+', '', line)}" for line in failed]

    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=feature_dir, text=True,
                          capture_output=True).stdout.strip()
    summary = "\n".join([
        f"Sandbox scenarios against `{head}`: **{'all passed' if not failures else f'{len(failures)} failed'}**",
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
