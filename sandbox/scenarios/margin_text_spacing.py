"""Margin text spacing: a single Enter is a line break, paragraphs and lists get a gap, the first block sits flush.

Every check but the two guards must FAIL on code without the change (run it against a main-based worktree).
    py sandbox/scenarios/margin_text_spacing.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

SHOTS = sandbox.RUNTIME / "shots"
MIN_GAP_PX = 4


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def posted(self, body: str) -> str:
        thread = ui.agent_starts_project_thread(body)
        time.sleep(2)
        return thread

    def single_enter(self) -> None:
        thread = self.posted("Alphaline first\nBravoline second")
        boxes = ui.word_boxes(thread, ["Alphaline", "Bravoline"])
        self.check("a single Enter starts a new line",
                   boxes["Bravoline"]["top"] >= boxes["Alphaline"]["bottom"], boxes)
        ui.screenshot_thread(thread, SHOTS / "spacing-single-enter.png")

    def hard_break_guard(self) -> None:
        thread = self.posted("Alphaline first  \nBravoline second")
        boxes = ui.word_boxes(thread, ["Alphaline", "Bravoline"])
        breaks = boxes["html"].count("<br")
        self.check("two trailing spaces still give exactly one break", breaks == 1, boxes["html"])

    def extra_blank_lines(self) -> None:
        thread = self.posted("Alphablank one\n\nBravoblank two\n\n\nCharlieblank three")
        boxes = ui.word_boxes(thread, ["Alphablank", "Bravoblank", "Charlieblank"])
        one_blank = boxes["Bravoblank"]["top"] - boxes["Alphablank"]["bottom"]
        two_blank = boxes["Charlieblank"]["top"] - boxes["Bravoblank"]["bottom"]
        line = boxes["Alphablank"]["bottom"] - boxes["Alphablank"]["top"]
        self.check("a second blank line adds an empty line", two_blank >= one_blank + line - 2,
                   f"one blank {one_blank}px, two blank {two_blank}px, line {line}px")
        ui.screenshot_thread(thread, SHOTS / "spacing-blank-lines.png")

    def paragraphs(self) -> None:
        thread = self.posted("Alphapara one.\n\nBravopara two.\n\nCharliepara three.")
        boxes = ui.word_boxes(thread, ["Alphapara", "Bravopara", "Charliepara"])
        gap = boxes["Bravopara"]["top"] - boxes["Alphapara"]["bottom"]
        self.check("paragraphs are set apart", gap >= MIN_GAP_PX, f"gap {gap}px")
        first = boxes["Alphapara"]["top"] - boxes["Alphapara"]["paneTop"]
        self.check("the first paragraph sits flush with the header", first <= 1, f"{first}px above it")
        ui.screenshot_thread(thread, SHOTS / "spacing-paragraphs.png")

    def lists(self) -> None:
        thread = self.posted("Alphaintro text:\n\n- Bravoitem one\n- Charlieitem two\n\nDeltaoutro text.")
        boxes = ui.word_boxes(thread, ["Alphaintro", "Bravoitem", "Charlieitem", "Deltaoutro"])
        above = boxes["Bravoitem"]["top"] - boxes["Alphaintro"]["bottom"]
        self.check("a list is set off from the text above", above >= MIN_GAP_PX, f"gap {above}px")
        between = boxes["Charlieitem"]["top"] - boxes["Bravoitem"]["bottom"]
        self.check("list items get a small gap", between >= 1, f"gap {between}px")
        below = boxes["Deltaoutro"]["top"] - boxes["Charlieitem"]["bottom"]
        self.check("the gap below a list matches a paragraph gap", MIN_GAP_PX <= below <= 10, f"gap {below}px")
        ui.screenshot_thread(thread, SHOTS / "spacing-lists.png")

    def leading_list_guard(self) -> None:
        thread = self.posted("- Alphalead item\n- Bravolead item")
        boxes = ui.word_boxes(thread, ["Alphalead"])
        first = boxes["Alphalead"]["top"] - boxes["Alphalead"]["paneTop"]
        self.check("a message starting with a list has no gap on top", first <= 3, f"{first}px above it")

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.show_tool_window()
        self.single_enter()
        self.hard_break_guard()
        self.extra_blank_lines()
        self.paragraphs()
        self.lists()
        self.leading_list_guard()
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
