"""A long thread shortens its history: the read middle hides behind one line (MARG-12).

    py sandbox/scenarios/shorten_long_history.py [--feature-dir <checkout>]

The agent posts a seven-message thread. Unread, every message shows; read and reopened, the panel keeps the first
message and the last two and hides the four between behind one link; the link shows the full history, which stays
on the next visit, and Shorten History in the thread header hides the middle again.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

FILE = "src/review_target.py"
SHOTS = sandbox.RUNTIME / "shots" / "shorten_long_history"
SETTLE_S = 2.0
BODIES = [f"Message {n} of the long thread." for n in range(1, 8)]


HIDDEN = "Show 4 more messages"
SHORTEN = "Shorten History"
FULL = "Show Full History"


def history_links(thread_id: str) -> list[str]:
    raw = ui.js(ui._FIND + """
        var links = [];
        find(component, function (c) {
            if (c instanceof com.intellij.ui.components.ActionLink && /more messages?$/.test(String(c.getText())))
                links.push(String(c.getText()));
            return false;
        });
        String(JSON.stringify(links))
    """, component_id=ui.thread_panel(thread_id))
    return json.loads(raw)


def click_link(thread_id: str, text: str) -> None:
    ui.js(ui._FIND + f"""
        var link = find(component, function (c) {{
            return c instanceof com.intellij.ui.components.ActionLink && String(c.getText()) == {json.dumps(text)};
        }});
        if (link == null) throw 'no link ' + {json.dumps(text)};
        link.doClick();
        String(true)
    """, component_id=ui.thread_panel(thread_id))


def history_action(thread_id: str) -> str | None:
    """The thread header's history button as the toolbar last updated it, or None while it is hidden."""
    raw = ui.js(ui._FIND + """
        var button = find(component, function (c) {
            return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                && /History$/.test(String(c.getPresentation().getText())) && c.isVisible();
        });
        String(button == null ? '' : button.getPresentation().getText())
    """, component_id=ui.thread_panel(thread_id))
    return raw or None


def user_reads(thread_id: str) -> None:
    # The panel only marks read while the IDE is the active app, which the sandbox window on another desktop is not.
    ui.js(f"""
        var thread = store.getThreads().byId('{thread_id}');
        thread.markReadByUser(thread.getMessages());
        store.getThreads().notifyChanged(thread);
        String(true)
    """)


def reopen(thread_id: str) -> None:
    ui.close_all_editors()
    time.sleep(SETTLE_S)
    ui.open_in_editor(FILE)
    ui.open_thread_in_editor(thread_id, FILE)
    time.sleep(SETTLE_S)


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

    def shown(self, thread_id: str) -> list[str]:
        texts = ui.panel_view(thread_id)["texts"]
        return [body for body in BODIES if body in texts]

    def run(self) -> int:
        ui.show_tool_window()
        ui.open_in_editor(FILE)
        thread = ui.agent_adds_line_thread(FILE, 1, BODIES[0])
        for body in BODIES[1:]:
            ui.agent_replies(thread, body)
        ui.open_thread_in_editor(thread, FILE)
        time.sleep(SETTLE_S)
        self.shot("1-unread")
        self.check("an unread long thread shows every message", self.shown(thread) == BODIES, self.shown(thread))
        self.check("an unread long thread hides nothing", history_links(thread) == [], history_links(thread))

        user_reads(thread)
        time.sleep(SETTLE_S)
        self.check("reading does not hide messages under the reader", self.shown(thread) == BODIES,
                   self.shown(thread))
        self.check("a read thread that is still open offers to shorten", history_action(thread) == SHORTEN,
                   history_action(thread))

        reopen(thread)
        self.shot("2-read-reopened")
        expected = [BODIES[0], BODIES[5], BODIES[6]]
        self.check("a read long thread shows the first and the last two", self.shown(thread) == expected,
                   self.shown(thread))
        self.check("the four between hide behind one link", history_links(thread) == [HIDDEN], history_links(thread))

        click_link(thread, HIDDEN)
        time.sleep(SETTLE_S)
        self.shot("3-full-history")
        self.check("the link shows the full history", self.shown(thread) == BODIES, self.shown(thread))
        self.check("the full history offers to shorten", history_action(thread) == SHORTEN, history_action(thread))

        reopen(thread)
        self.check("the full history stays on the next visit", self.shown(thread) == BODIES,
                   self.shown(thread))

        ui.press_panel_action(thread, SHORTEN)
        time.sleep(SETTLE_S)
        self.shot("4-shortened")
        self.check("Shorten history hides the middle again", self.shown(thread) == expected, self.shown(thread))
        self.check("a shortened thread offers the full history again", history_action(thread) == FULL, history_action(thread))

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
