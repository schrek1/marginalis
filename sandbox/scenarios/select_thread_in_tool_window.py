"""Select a thread in the tool window (fork MARG-11): "Select in Marginalis" in a thread's header, and "Select Opened
Thread" in the tool window header, both land on the thread's node, in the section that lists it.

    py sandbox/scenarios/select_thread_in_tool_window.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import sys
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui
from fixtures import deep_walkthrough as fixture

SHOTS = sandbox.RUNTIME / "shots" / "select_thread"
PAUSE_S = 1.5
FILE = "src/review_target.py"


def wait_for_panel(thread_id: str, timeout_s: float = 10) -> None:
    """A freshly reopened editor lays its inlays out a moment later; the robot sees a panel only once it is on screen."""
    deadline = time.monotonic() + timeout_s
    while True:
        try:
            ui.thread_panel(thread_id)
            return
        except RuntimeError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.5)


def user_reads(thread_id: str) -> None:
    ui.js(f"""
        var thread = store.getThreads().byId('{thread_id}');
        thread.markReadByUser(thread.getMessages());
        store.getThreads().notifyChanged(thread);
        String(true)
    """)


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.steps: dict[int, str] = {}

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def build(self) -> None:
        ui.show_tool_window()
        fixture.write_files(sandbox.PROJECT)
        ui.refresh_project_files()
        time.sleep(2)
        for order, path, line in fixture.steps():
            self.steps[order] = ui.agent_adds_step(path, line, f"Step {order}: review note", order, fixture.LABEL)
        for order in fixture.RESOLVED:
            ui.resolve_thread(self.steps[order])
        self.plain = ui.agent_adds_line_thread(FILE, 5, "A thread outside the walkthrough")
        self.done = ui.agent_adds_line_thread(FILE, 9, "A thread already resolved")
        ui.resolve_thread(self.done)
        time.sleep(3)

    def selected(self) -> dict:
        time.sleep(PAUSE_S)
        return ui.selected_step() or {}

    def header_button_selects_a_step_in_its_guided_section(self) -> None:
        order = 27
        path = next(path for o, path, _ in fixture.steps() if o == order)
        ui.open_in_editor(path)
        ui.open_thread_in_editor(self.steps[order], path)
        time.sleep(PAUSE_S)
        ui.press_panel_action(self.steps[order], "Select in Marginalis")
        got = self.selected()
        ui.screenshot_tool_window(SHOTS / "1-step.png")
        self.check("Select in Marginalis selects the step", got.get("thread") == self.steps[order], got)
        self.check("…in its Guided section", got.get("section", "").startswith("Guided"), got)

    def header_button_selects_a_plain_thread_in_open(self) -> None:
        ui.open_in_editor(FILE)
        ui.open_thread_in_editor(self.plain, FILE)
        time.sleep(PAUSE_S)
        ui.press_panel_action(self.plain, "Select in Marginalis")
        got = self.selected()
        self.check("a thread outside any walkthrough is selected in Open",
                   got.get("thread") == self.plain and got.get("section") == "Open", got)

    def header_button_reaches_into_the_folded_resolved_section(self) -> None:
        # Opening an unread resolved thread marks it read, and that change closes a resolved thread's panel at once.
        user_reads(self.done)
        # The robot only sees panels on screen; the plain thread's open panel would push this one below the fold.
        ui.close_all_editors()
        time.sleep(PAUSE_S)
        ui.open_in_editor(FILE)
        time.sleep(PAUSE_S)
        ui.open_thread_in_editor(self.done, FILE)
        wait_for_panel(self.done)
        ui.press_panel_action(self.done, "Select in Marginalis")
        got = self.selected()
        ui.screenshot_tool_window(SHOTS / "2-resolved.png")
        self.check("a resolved thread is selected inside the folded Resolved section",
                   got.get("thread") == self.done and got.get("section") == "Resolved", got)

    def toolbar_selects_the_thread_on_the_caret_line(self) -> None:
        ui.close_all_editors()
        ui.open_in_editor(FILE)
        time.sleep(PAUSE_S)
        ui.move_caret(FILE, 5)
        ui.press_tool_window_action("Select Opened Thread")
        got = self.selected()
        self.check("Select Opened Thread selects the thread on the caret's line", got.get("thread") == self.plain, got)

        ui.move_caret(FILE, 2)
        ui.press_tool_window_action("Select Opened Thread")
        time.sleep(PAUSE_S)
        name = ui.selected_node_name()
        self.check("on a line without a thread it selects the file", name == "review_target.py", name)

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        self.build()
        for scenario in (
            self.header_button_selects_a_step_in_its_guided_section,
            self.header_button_selects_a_plain_thread_in_open,
            self.header_button_reaches_into_the_folded_resolved_section,
            self.toolbar_selects_the_thread_on_the_caret_line,
        ):
            try:
                scenario()
            except Exception as error:
                self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
                traceback.print_exc()
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
