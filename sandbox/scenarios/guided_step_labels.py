"""Two walkthroughs open at once: every step shows just `(n/total)`; the section title carries the label.
On `main` the label is glued to the number ("(SHOP-42 · round 2 – …1/3)"), so the checks fail there.
    py sandbox/scenarios/guided_step_labels.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui
from fixtures import deep_walkthrough as fixture
from scenarios.toolwindow_step_navigation import build_walkthrough

SHOTS = sandbox.RUNTIME / "shots" / "step-labels"
ROUND_TWO = "SHOP-42 · round 2 – split the importer"
ROUND_TWO_FILE = "src/review_target.py"
NUMBER_ONLY = re.compile(r"^\(\d+/\d+\)$")


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.show_tool_window()
        build_walkthrough()
        for order, line in enumerate((2, 6, 9), start=1):
            ui.agent_adds_step(ROUND_TWO_FILE, line, f"Round 2, step {order}", order, ROUND_TWO)
        time.sleep(4)

        prefixes = ui.step_prefixes()
        print(f"INFO  sections: {list(prefixes)}")
        first_round = prefixes.get(f"Guided {fixture.LABEL}", [])
        second_round = prefixes.get(f"Guided {ROUND_TWO}", [])
        self.check("both walkthroughs have their own section", bool(first_round) and bool(second_round), list(prefixes))
        self.check("round 2 steps show just their number", second_round == ["(1/3)", "(2/3)", "(3/3)"], second_round)
        odd = [p for p in first_round if not NUMBER_ONLY.match(p)]
        self.check("round 1 steps show just their number", not odd and len(first_round) == 24, odd[:3] or len(first_round))
        ui.screenshot_tool_window(SHOTS / "two-walkthroughs.png")

        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        return 1 if self.failures else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    parser.add_argument("--no-restart", action="store_true")
    args = parser.parse_args()
    if not args.no_restart:
        sandbox.stop()
        sandbox.start(args.feature_dir.resolve(), fresh=True)
    sys.exit(Scenarios().run())


if __name__ == "__main__":
    main()
