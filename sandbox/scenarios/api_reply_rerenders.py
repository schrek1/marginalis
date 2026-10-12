"""An open thread in the editor shows a reply the agent posts through the HTTP API (MARG-14).

    py sandbox/scenarios/api_reply_rerenders.py [--feature-dir <checkout>]

Each case opens a thread in the editor, lets the agent reply through `comment_reply`, and reads what the open
panel renders: the reply's text among the messages, and a panel at least as tall as it needs (an inlay that does
not grow hides the new message below its edge).
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

FILE = "src/review_target.py"
SHOTS = sandbox.RUNTIME / "shots" / "api_reply_rerenders"
SETTLE_S = 2.0


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def expect_reply_shown(self, case: str, thread_id: str, reply: str) -> None:
        time.sleep(SETTLE_S)
        view = ui.panel_view(thread_id)
        self.shot(case)
        self.check(f"{case}: the open thread renders the agent's reply", reply in view["texts"], view["texts"])
        self.check(f"{case}: the panel grows to fit it", view["height"] >= view["preferredHeight"],
                   f"height {view['height']} < preferred {view['preferredHeight']}")

    def agent_replies_to_an_open_thread(self) -> None:
        thread = ui.agent_adds_line_thread(FILE, 1, "Does this parse quoted fields?")
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        ui.agent_replies(thread, "Reply A, posted through the API while the thread is open.")
        self.expect_reply_shown("plain", thread, "Reply A, posted through the API while the thread is open.")

    def agent_replies_after_the_user_submitted(self) -> None:
        thread = ui.agent_adds_line_thread(FILE, 6, "Should total() accept floats?")
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "Yes, floats too.")
        time.sleep(SETTLE_S)
        ui.agent_replies(thread, "Reply B, after the user's own reply.")
        self.expect_reply_shown("after-user-reply", thread, "Reply B, after the user's own reply.")

    def agent_replies_to_an_open_walkthrough_step(self) -> None:
        thread = ui.agent_adds_step(FILE, 9, "Step 1: the legacy helper.", order=1, walkthrough="repro")
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "Why split on commas?")
        time.sleep(SETTLE_S)
        ui.agent_replies(thread, "Reply C, in a walkthrough step.")
        self.expect_reply_shown("walkthrough-step", thread, "Reply C, in a walkthrough step.")

    def agent_replies_to_a_project_step_in_the_margin_tab(self) -> None:
        thread = ui.agent_adds_project_step("Step 1: the plan, about the whole project.", order=1, walkthrough="tab")
        ui.open_margin_tab(thread)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "What about the tests?")
        time.sleep(SETTLE_S)
        ui.agent_replies(thread, "Reply D, in a project-level step in the Margin tab.")
        self.expect_reply_shown("margin-tab-step", thread, "Reply D, in a project-level step in the Margin tab.")

    def run(self) -> int:
        ui.show_tool_window()
        ui.open_in_editor(FILE)
        for case in (
            self.agent_replies_to_an_open_thread,
            self.agent_replies_after_the_user_submitted,
            self.agent_replies_to_an_open_walkthrough_step,
            self.agent_replies_to_a_project_step_in_the_margin_tab,
        ):
            try:
                case()
            except Exception as error:
                self.check(case.__name__, False, f"{type(error).__name__}: {error}")
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
