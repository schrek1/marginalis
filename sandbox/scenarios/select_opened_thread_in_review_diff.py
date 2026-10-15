"""Select Opened Thread in the tool window header works with a review diff in front (MARG-60).

    py sandbox/scenarios/select_opened_thread_in_review_diff.py [--feature-dir <checkout>] [--no-restart]

Until this branch carries the in-repo harness (MARG-29), run it with that harness on the path:

    PYTHONPATH=D:/OSOBNI/programming/marginalis-stable/sandbox \
        py sandbox/scenarios/select_opened_thread_in_review_diff.py --feature-dir D:/OSOBNI/programming/marginalis-MARG-60

The review diff is the one `review_diff_walkthrough` builds: two project files shown as revision copies. The caret is
put on a line of the review's text, and the button is read as the user would meet it: enabled or greyed out, then
pressed.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui
import review_diff_ui as review
import review_diff_walkthrough as walkthrough

SHOTS = sandbox.RUNTIME / "shots" / "select_opened_thread_in_review_diff"
PAUSE_S = 1.5
BUTTON = "Select Opened Thread"
# 0-based lines in TARGET_AFTER: the review's copy moves every line down by three, line 0 is its new import.
TOTAL_IN_REVIEW = 8
IMPORT_IN_REVIEW = 0


def button_enabled() -> bool:
    for attempt in range(ui.HEADER_ATTEMPTS):
        try:
            return ui.js(ui._FIND + f"""
                var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
                var root = window.getComponent();
                while (root.getParent() != null && root.getClass().getName().indexOf('InternalDecorator') < 0) root = root.getParent();
                var button = find(root, function (c) {{
                    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                        && c.getPresentation().getText() == {json.dumps(BUTTON)};
                }});
                if (button == null) throw 'no tool window button ' + {json.dumps(BUTTON)};
                var action = button.getAction();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                    'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                action.update(event);
                String(event.getPresentation().isEnabled())
            """) == "true"
        except Exception as error:
            if "no tool window button" not in str(error) or attempt == ui.HEADER_ATTEMPTS - 1:
                raise
            time.sleep(1)
    return False


def put_caret_in_review(after: str, line: int) -> None:
    ui.js(ui._EDITORS + f"""
        var editor = diffEditorShowing({json.dumps(after)});
        if (editor == null) throw 'no diff editor shows that text';
        editor.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition({line}, 0));
        String(true)
    """)


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def build(self) -> None:
        ui.show_tool_window()
        self.total = ui.agent_adds_step(walkthrough.TARGET, 6, "Step 1: total sums the values.", 1, "review")
        ui.close_all_editors()
        review.open_review_diff_with_files(walkthrough.REVIEW_FILES, shown=walkthrough.SHOWS_TARGET)
        shown = walkthrough.wait_until(lambda: review.diff_shows(walkthrough.TARGET_AFTER))
        self.check("setup: the review diff shows review_target.py", shown)
        # Opening the diff can hide the tool window.
        ui.show_tool_window()
        time.sleep(PAUSE_S)
        # Start from somewhere else, so a selection that does not move is caught.
        ui.select_dir("Open", "src")
        time.sleep(PAUSE_S)

    def button_is_enabled_in_a_review_diff(self) -> None:
        kind = ui.selected_editor_kind()
        self.check("the review diff is in front", kind == "DIFF", kind)
        self.check("Select Opened Thread is enabled with a review diff in front", button_enabled())

    def button_selects_the_thread_on_the_caret_line(self) -> None:
        put_caret_in_review(walkthrough.TARGET_AFTER, TOTAL_IN_REVIEW)
        ui.press_tool_window_action(BUTTON)
        time.sleep(PAUSE_S)
        got = ui.selected_step() or {}
        ui.screenshot_tool_window(SHOTS / "1-thread.png")
        self.check("on the line of a thread in the review's text it selects that thread", got.get("thread") == self.total, got)

    def button_selects_the_file_elsewhere(self) -> None:
        put_caret_in_review(walkthrough.TARGET_AFTER, IMPORT_IN_REVIEW)
        ui.press_tool_window_action(BUTTON)
        time.sleep(PAUSE_S)
        name = ui.selected_node_name()
        self.check("on a line without a thread it selects the file", name == "review_target.py", name)

    def run(self) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        for scenario in (
            self.build,
            self.button_is_enabled_in_a_review_diff,
            self.button_selects_the_thread_on_the_caret_line,
            self.button_selects_the_file_elsewhere,
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
