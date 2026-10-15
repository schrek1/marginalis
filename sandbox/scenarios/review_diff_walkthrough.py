"""A guided walkthrough followed inside a pull/merge request review diff stays in that diff (MARG-17).

    py sandbox/scenarios/review_diff_walkthrough.py [--feature-dir <checkout>] [--only case,...] [--no-restart]

Until this branch carries the in-repo harness (MARG-29), run it with that harness on the path:

    PYTHONPATH=D:/OSOBNI/programming/marginalis-MARG-29/sandbox \
        py sandbox/scenarios/review_diff_walkthrough.py --feature-dir D:/OSOBNI/programming/marginalis-MARG-17

The review diff is the platform's `MutableDiffRequestProcessor` over two project files, both shown as revision
copies (the review branch is not checked out), built by `review_diff_ui` without the GitHub/GitLab plugins. Each
case opens a fresh diff, opens its starting step in the diff, presses a step button of that thread, and reads where
the next step ended up: which tab is in front, which file the diff shows, which threads are open where, and the
caret line in the diff.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui
import review_diff_ui as review

SHOTS = sandbox.RUNTIME / "shots" / "review_diff_walkthrough"
TIMEOUT_S = 10.0

TARGET = "src/review_target.py"
TARGET_BEFORE = (sandbox.HERE / "project-template" / TARGET).read_text(encoding="utf-8")
# The review's version: three lines on top (every surviving line moves down by three), legacy_helper removed.
TARGET_AFTER = """import csv


def parse(text: str) -> list[str]:
    return text.split(",")


def total(values: list[int]) -> int:
    return sum(values)
"""

GREETING = "src/greeting.py"
GREETING_BEFORE = (sandbox.HERE / "project-template" / GREETING).read_text(encoding="utf-8")
# The review's version: a new greeting, and a docstring that moves farewell's body down by one.
GREETING_AFTER = '''def greet(name: str) -> str:
    return f"Hi, {name}!"


def farewell(name: str) -> str:
    """Says goodbye."""
    return f"Goodbye, {name}."


if __name__ == "__main__":
    print(greet("sandbox"))
    print(farewell("sandbox"))
'''

OUTSIDE = "src/long_module.py"
REVIEW_FILES = [(TARGET, TARGET_BEFORE, TARGET_AFTER), (GREETING, GREETING_BEFORE, GREETING_AFTER)]
SHOWS_TARGET, SHOWS_GREETING = 0, 1


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.steps: dict[str, str] = {}

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def agent_posts_the_walkthroughs(self) -> None:
        # Line numbers are 1-based in the local files; the review's copies shift them (see the *_AFTER texts).
        self.steps = {
            "parse": ui.agent_adds_step(TARGET, 2, "Step 1: parse splits on commas.", 1, "review"),
            "total": ui.agent_adds_step(TARGET, 6, "Step 2: total sums the values.", 2, "review"),
            "farewell": ui.agent_adds_step(GREETING, 6, "Step 3: farewell keeps its wording.", 3, "review"),
            "legacy": ui.agent_adds_step(TARGET, 9, "Step 4: legacy_helper, which the review removes.", 4, "review"),
            "entry": ui.agent_adds_step(GREETING, 9, "Step 1: the entry point.", 1, "outside"),
            "outside": ui.agent_adds_step(OUTSIDE, 10, "Step 2: a file the review does not touch.", 2, "outside"),
        }

    def user_reads_in_the_review_diff(self, step: str, shown: int) -> None:
        ui.close_all_editors()
        review.open_review_diff_with_files(REVIEW_FILES, shown=shown)
        after = REVIEW_FILES[shown][2]
        wait_until(lambda: review.diff_shows(after))
        ui.open_thread_in_diff(self.steps[step], after)
        wait_until(lambda: self.steps[step] in ui.thread_ids_open_in_diff(after))

    def expect_step_in_diff(self, case: str, step: str, after: str, line: int) -> None:
        opened = wait_until(lambda: self.steps[step] in ui.thread_ids_open_in_diff(after))
        self.shot(case)
        kind = ui.selected_editor_kind()
        self.check(f"{case}: the review diff stays in front", kind == "DIFF", kind)
        self.check(f"{case}: the diff shows the step's file", review.diff_shows(after))
        self.check(f"{case}: the step's thread opens in the diff", opened, safe(lambda: ui.thread_ids_open_in_diff(after)))
        caret = safe(lambda: review.diff_caret_line(after))
        self.check(f"{case}: the diff scrolls to the step's line in the review's text", caret == line, caret)

    def expect_step_in_file_tab(self, case: str, step: str, file: str) -> None:
        opened = wait_until(lambda: self.steps[step] in review.thread_ids_open_in_file_tab(file))
        self.shot(case)
        kind = ui.selected_editor_kind()
        self.check(f"{case}: the step opens in the file's own tab", kind == f"TEXT:{Path(file).name}", kind)
        self.check(f"{case}: the step's thread opens there", opened, safe(lambda: review.thread_ids_open_in_file_tab(file)))

    def harness_builds_a_review_diff(self) -> bool:
        ui.close_all_editors()
        review.open_review_diff_with_files(REVIEW_FILES, shown=SHOWS_TARGET)
        shown = wait_until(lambda: review.diff_shows(TARGET_AFTER))
        processor = review.selected_diff_processor()
        self.check("harness: the review diff runs on MutableDiffRequestProcessor", processor == review.PROCESSOR, processor)
        self.check("harness: the review diff shows its first file", shown)
        return processor == review.PROCESSOR and shown

    def next_step_in_the_same_file(self) -> None:
        # GIVEN step 1 open in the review diff of review_target.py (a revision copy)
        self.user_reads_in_the_review_diff("parse", SHOWS_TARGET)
        # WHEN the user presses Next Step
        ui.press_panel_action(self.steps["parse"], "Next Step")
        # THEN step 2 opens in the same diff, at its line in the review's text
        self.expect_step_in_diff("same file, revision copy", "total", TARGET_AFTER, line=8)

    def next_step_in_another_file_of_the_review(self) -> None:
        self.user_reads_in_the_review_diff("total", SHOWS_TARGET)
        ui.press_panel_action(self.steps["total"], "Next Step")
        self.expect_step_in_diff("next, another file of the review", "farewell", GREETING_AFTER, line=6)

    def previous_step_back_to_the_first_file(self) -> None:
        self.user_reads_in_the_review_diff("farewell", SHOWS_GREETING)
        ui.press_panel_action(self.steps["farewell"], "Previous Step")
        self.expect_step_in_diff("previous, back to the first file", "total", TARGET_AFTER, line=8)

    def step_whose_anchor_the_review_removed(self) -> None:
        # The review shows review_target.py, but without legacy_helper: the step has nothing to anchor on there.
        self.user_reads_in_the_review_diff("farewell", SHOWS_GREETING)
        ui.press_panel_action(self.steps["farewell"], "Next Step")
        self.expect_step_in_file_tab("anchor not in the review's text", "legacy", TARGET)

    def step_in_a_file_outside_the_review(self) -> None:
        self.user_reads_in_the_review_diff("entry", SHOWS_GREETING)
        ui.press_panel_action(self.steps["entry"], "Next Step")
        self.expect_step_in_file_tab("file outside the review", "outside", OUTSIDE)

    def resolve_moves_to_another_file_of_the_review(self) -> None:
        # Last: it resolves step 2, which the other cases walk through.
        self.user_reads_in_the_review_diff("total", SHOWS_TARGET)
        ui.press_panel_action(self.steps["total"], "Resolve")
        self.expect_step_in_diff("resolve, another file of the review", "farewell", GREETING_AFTER, line=6)

    def run(self, only: list[str]) -> int:
        ui.show_tool_window()
        self.agent_posts_the_walkthroughs()
        time.sleep(1)
        try:
            ready = self.harness_builds_a_review_diff()
        except Exception as error:
            self.check("harness: the review diff opens", False, f"{type(error).__name__}: {error}")
            ready = False
        if not ready:
            print("\nthe sandbox could not build a review diff; no walkthrough case ran")
            return 1
        for name in only:
            try:
                getattr(self, name)()
            except Exception as error:
                self.check(name, False, f"{type(error).__name__}: {error}")
        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        return 1 if self.failures else 0


ALL = [
    "next_step_in_the_same_file",
    "next_step_in_another_file_of_the_review",
    "previous_step_back_to_the_first_file",
    "step_whose_anchor_the_review_removed",
    "step_in_a_file_outside_the_review",
    "resolve_moves_to_another_file_of_the_review",
]


def wait_until(condition, timeout_s: float = TIMEOUT_S) -> bool:
    """Polls `condition` (a failing read counts as not yet) until it holds or the timeout passes."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            if condition():
                return True
        except Exception:
            pass
        time.sleep(0.5)
    return False


def safe(read):
    """`read()`, or the error it raised as text — for a FAIL line's detail."""
    try:
        return read()
    except Exception as error:
        return f"{type(error).__name__}: {error}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    parser.add_argument("--only", default=",".join(ALL))
    parser.add_argument("--no-restart", action="store_true")
    args = parser.parse_args()
    if not args.no_restart:
        sandbox.stop()
        sandbox.start(args.feature_dir.resolve(), fresh=True)
    sys.exit(Scenarios().run(args.only.split(",")))


if __name__ == "__main__":
    main()
