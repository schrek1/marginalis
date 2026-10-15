"""A thread left open shows the agent's reply that arrived while its tab was in the background (MARG-50).

    PYTHONPATH=<harness>/sandbox py sandbox/scenarios/api_reply_while_away.py [--feature-dir <checkout>]

The real case: the user writes into an open thread, moves to another tab while the agent works, the agent replies
through `comment_reply` while it also holds a `comment_wait` long-poll, and the user comes back. A background
editor tab is taken out of the Swing hierarchy, so the open panel misses the reply unless it catches up when it
is shown again. Each case reads what the panel renders after the user is back: the reply's text among the
messages, and a panel tall enough to show it. The control case replies while the panel stays in view.
"""
from __future__ import annotations

import argparse
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

FILE = "src/review_target.py"
OTHER_FILE = "src/greeting.py"
SHOTS = sandbox.RUNTIME / "shots" / "api_reply_while_away"
SETTLE_S = 2.0
LONG_POLL_S = 30


class AgentListening:
    """The agent's `comment_wait`, held open in the background as a real agent holds it between replies."""

    def __init__(self) -> None:
        self.error: Exception | None = None
        self.thread = threading.Thread(target=self._wait, daemon=True)

    def _wait(self) -> None:
        try:
            ui.agent_waits(LONG_POLL_S)
        except Exception as error:
            self.error = error

    def __enter__(self) -> "AgentListening":
        self.thread.start()
        time.sleep(SETTLE_S)
        return self

    def __exit__(self, *_) -> None:
        self.thread.join(LONG_POLL_S + 30)

    @property
    def open(self) -> bool:
        return self.thread.is_alive() and self.error is None


def editor_on_screen(file: str) -> bool:
    return ui.js(ui._EDITORS + f"String(textEditorOf('{file}').getContentComponent().isDisplayable())") == "true"


def margin_tab_on_screen() -> bool:
    return ui.js(ui._MARGIN + "String(marginPanel().getComponent().isDisplayable())") == "true"


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

    def agent_replies_while_listening(self, case: str, thread_id: str, reply: str) -> None:
        with AgentListening() as listening:
            self.check(f"{case}: the agent's long-poll is open", listening.open, listening.error)
            ui.agent_replies(thread_id, reply)
            time.sleep(SETTLE_S)
            self.check(f"{case}: the long-poll is still open after the reply", listening.open, listening.error)

    def expect_reply_shown(self, case: str, thread_id: str, reply: str) -> None:
        time.sleep(SETTLE_S)
        view = ui.panel_view(thread_id)
        self.shot(case)
        self.check(f"{case}: the open thread renders the agent's reply", reply in view["texts"], view["texts"])
        self.check(f"{case}: the panel grows to fit it", view["height"] >= view["preferredHeight"],
                   f"height {view['height']} < preferred {view['preferredHeight']}")

    def agent_replies_while_the_panel_is_in_view(self) -> None:
        case = "control-in-view"
        thread = ui.agent_adds_line_thread(FILE, 6, "Should total() accept floats?")
        ui.open_in_editor(FILE)
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "Yes, floats too. Can you check the callers?")
        time.sleep(SETTLE_S)
        reply = "Reply E, while the thread stays in view."
        self.agent_replies_while_listening(case, thread, reply)
        self.expect_reply_shown(case, thread, reply)

    def agent_replies_while_the_editor_tab_is_in_the_background(self) -> None:
        case = "editor-tab-away"
        thread = ui.agent_adds_step(FILE, 9, "Step 7: the legacy helper.", order=7, walkthrough="away")
        ui.open_in_editor(FILE)
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "Why split on commas here?")
        time.sleep(SETTLE_S)
        ui.open_in_editor(OTHER_FILE)
        time.sleep(SETTLE_S)
        self.check(f"{case}: the thread's editor tab left the screen", not editor_on_screen(FILE))
        reply = "Reply F, posted while the thread's editor tab was in the background."
        self.agent_replies_while_listening(case, thread, reply)
        ui.open_in_editor(FILE)
        self.expect_reply_shown(case, thread, reply)

    def agent_replies_while_the_margin_tab_is_in_the_background(self) -> None:
        case = "margin-tab-away"
        thread = ui.agent_adds_project_step("Step 1: the release plan for SHOP-42.", order=1, walkthrough="away-tab")
        ui.open_margin_tab(thread)
        time.sleep(SETTLE_S)
        ui.user_replies(thread, "Does the plan cover the shop-api tests?")
        time.sleep(SETTLE_S)
        ui.open_in_editor(FILE)
        time.sleep(SETTLE_S)
        self.check(f"{case}: the Margin tab left the screen", not margin_tab_on_screen())
        reply = "Reply G, posted while the Margin tab was in the background."
        self.agent_replies_while_listening(case, thread, reply)
        ui.open_margin_tab(thread)
        self.expect_reply_shown(case, thread, reply)

    def run(self) -> int:
        ui.show_tool_window()
        for case in (
            self.agent_replies_while_the_panel_is_in_view,
            self.agent_replies_while_the_editor_tab_is_in_the_background,
            self.agent_replies_while_the_margin_tab_is_in_the_background,
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
