"""Walking the Guided section with the tool window's arrows on a review-sized walkthrough (fixtures/deep_walkthrough).

Records what each press selects, so a jump back to the first node or an order that ignores step numbers shows up.
    py sandbox/scenarios/toolwindow_step_navigation.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import robot
import sandbox
import sandbox_ui as ui
from fixtures import deep_walkthrough as fixture

SHOTS = sandbox.RUNTIME / "shots" / "navigation"
HUMAN_PAUSE_S = 1.5


def press(*names: str) -> None:
    """The header buttons carry the occurrence actions' own names or the panel's step names."""
    for name in names:
        try:
            ui.press_tool_window_action(name)
            return
        except robot.RobotError as error:
            if "no tool window button" not in str(error):
                raise
    raise RuntimeError(f"none of {names} is in the tool window header")


def build_walkthrough() -> None:
    fixture.write_files(sandbox.PROJECT)
    ui.refresh_project_files()
    time.sleep(2)
    ids = {}
    for order, path, line in fixture.steps():
        ids[order] = ui.agent_adds_step(path, line, f"Step {order}: review note", order, fixture.LABEL)
    for order in fixture.RESOLVED:
        ui.resolve_thread(ids[order])


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def walk(self, label: str, start: int, buttons: tuple[str, ...], presses: int, tree: list[int],
             section: str = "Guided") -> list:
        ui.select_step(start, section)
        time.sleep(HUMAN_PAUSE_S)
        seen = [ui.selected_step()]
        for i in range(presses):
            press(*buttons)
            time.sleep(HUMAN_PAUSE_S)
            # In Ondra's IDE opening a step marks it read, which rebuilds the tree; the sandbox sits on another
            # desktop and does not always, so the rebuild is triggered here.
            ui.poke_a_thread()
            time.sleep(0.5)
            seen.append(ui.selected_step())
            if i == 2:
                ui.screenshot_tool_window(SHOTS / f"{label}-after-3.png")
        orders = [s["order"] if s else None for s in seen]
        print(f"INFO  {label} from {start}: {orders}")
        return orders

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.show_tool_window()
        build_walkthrough()
        time.sleep(3)
        shown = ui.tree_steps()
        print(f"INFO  Guided in tree order: {shown}")
        # Step navigation in Guided follows the step numbers, not the folder-and-file order the tree shows.
        tree = sorted(shown)
        ui.screenshot_tool_window(SHOTS / "tree.png")

        down = self.walk("next", 16, ("Next Step", "Next Occurrence"), 8, tree)
        at = tree.index(16)
        expected_down = tree[at:at + 9]
        self.check("Next keeps a selection after every press", None not in down, down)
        self.check("Next walks up the step numbers without jumping back", down == expected_down,
                   f"got {down}, expected {expected_down}")

        up = self.walk("previous", 22, ("Previous Step", "Previous Occurrence"), 3, tree)
        at = tree.index(22)
        expected_up = list(reversed(tree[at - 3:at + 1]))
        self.check("Previous walks down the step numbers without jumping back", up == expected_up,
                   f"got {up}, expected {expected_up}")

        ui.select_step(20)
        time.sleep(HUMAN_PAUSE_S)
        press("First Step")
        time.sleep(HUMAN_PAUSE_S)
        first = ui.selected_step()
        self.check("First Step lands on the lowest open step", first and first["order"] == tree[0], first)

        press("Last Step")
        time.sleep(HUMAN_PAUSE_S)
        ui.poke_a_thread()
        time.sleep(0.5)
        last = ui.selected_step()
        self.check("Last Step lands on the highest open step", last and last["order"] == tree[-1], last)

        open_tree = ui.tree_steps("Open")
        print(f"INFO  Open in tree order: {open_tree}")
        start = open_tree[0]
        in_open = self.walk("next-in-open", start, ("Next Step", "Next Occurrence"), 4, open_tree, section="Open")
        sections = {s["section"] for s in [ui.selected_step()] if s}
        self.check("in Open, Next follows the tree, not the numbers", in_open == open_tree[:5],
                   f"got {in_open}, tree says {open_tree[:5]}")
        self.check("in Open, Next stays in Open", sections == {"Open"}, sections)

        ui.select_step(10)
        time.sleep(HUMAN_PAUSE_S)
        press("Next Step", "Next Occurrence")
        time.sleep(HUMAN_PAUSE_S)
        press("Next Step", "Next Occurrence")
        time.sleep(HUMAN_PAUSE_S)
        settled = ui.selected_step()
        self.check("the selection is still there after the tree refreshes", settled and settled["order"] == 12, settled)
        ui.screenshot_tool_window(SHOTS / "after-last.png")

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
