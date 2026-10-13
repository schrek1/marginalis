"""Expand / Collapse in the tool window's toolbar: from the selection's folder one level further out per press, up to
its section, then every section; Collapse folds everything inside (as IntelliJ's Collapse All); folded folders stay
folded when the tree refreshes. Uses the deep walkthrough fixture.
    py sandbox/scenarios/section_expand_collapse.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui
from scenarios.toolwindow_step_navigation import build_walkthrough, press

SHOTS = sandbox.RUNTIME / "shots" / "expand-collapse"
PAUSE_S = 1.0
GUIDED = "Guided SHOP-42"
# From step 16 outwards, the folders around it, then its section.
LEVELS_FROM_16 = ["api", "customer/settings", "main/java/com/acme/backend/common", "shop-common/src", "backend",
                  "acme", GUIDED]
COLLAPSE = ("Collapse Folder", "Collapse Section", "Collapse All Sections", "Collapse")
EXPAND = ("Expand Folder", "Expand Section", "Expand All Sections", "Expand")


def folder_layout() -> None:
    """Folders are the levels Expand and Collapse step through; a build with a Guided layout menu (MARG-10) starts
    in step order, which has none."""
    try:
        ui.guided_layout()
    except Exception:
        return
    ui.choose_guided_layout("By folder")
    time.sleep(PAUSE_S)


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def collapse_levels(self) -> None:
        ui.select_step(16)
        time.sleep(PAUSE_S)
        walked = []
        for _ in LEVELS_FROM_16:
            press(*COLLAPSE)
            time.sleep(PAUSE_S)
            walked.append(ui.selected_node_name())
            if len(walked) == 2:
                ui.screenshot_tool_window(SHOTS / "collapse-two-levels.png")
        print(f"INFO  Collapse from step 16 folded, press by press: {walked}")
        self.check("Collapse folds one level further out with each press, up to the section",
                   walked == LEVELS_FROM_16, walked)
        state = ui.sections_state()
        self.check("Collapse up to the section leaves the other sections open",
                   not state[GUIDED]["expanded"] and state["Open"]["expanded"], state)

        ui.open_section_only(GUIDED)
        time.sleep(PAUSE_S)
        self.check("Collapse folds everything inside, as IntelliJ does", ui.sections_state()[GUIDED]["folded"])
        ui.screenshot_tool_window(SHOTS / "reopened-section-one-level.png")

        # The section is selected and open again: one press folds it, the next one every section.
        press(*COLLAPSE)
        time.sleep(PAUSE_S)
        press(*COLLAPSE)
        time.sleep(PAUSE_S)
        state = ui.sections_state()
        self.check("past the section, Collapse folds every section", not any(s["expanded"] for s in state.values()), state)
        ui.screenshot_tool_window(SHOTS / "collapse-all.png")

    def expand_levels(self) -> None:
        # Everything is folded and the Guided section is selected.
        press(*EXPAND)
        time.sleep(PAUSE_S)
        state = ui.sections_state()
        self.check("Expand opens the selected section all the way down", state[GUIDED]["full"], state)
        self.check("Expand leaves the other sections folded", not state["Open"]["expanded"], state)

        press(*EXPAND)
        time.sleep(PAUSE_S)
        state = ui.sections_state()
        self.check("Expand again opens every section all the way down", all(s["full"] for s in state.values()), state)

        ui.fold_dir(GUIDED, "shop-orders/src")
        ui.select_dir(GUIDED, "shop-orders/src")
        time.sleep(PAUSE_S)
        press(*EXPAND)
        time.sleep(PAUSE_S)
        self.check("Expand on a folded folder opens that folder", ui.dir_expanded(GUIDED, "shop-orders/src"))

    def folds_survive_refresh(self) -> None:
        ui.fold_dir(GUIDED, "shop-orders/src")
        time.sleep(PAUSE_S)
        ui.poke_a_thread()
        time.sleep(PAUSE_S)
        self.check("a folded folder stays folded when the tree refreshes", not ui.dir_expanded(GUIDED, "shop-orders/src"))
        ui.screenshot_tool_window(SHOTS / "folded-folder-after-refresh.png")

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.show_tool_window()
        build_walkthrough()
        folder_layout()
        # Creating 33 threads queues rebuilds; let them land before the first press.
        time.sleep(5)
        self.collapse_levels()
        self.expand_levels()
        self.folds_survive_refresh()
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
