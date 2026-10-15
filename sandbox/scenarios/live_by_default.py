"""Live by default: the tool window toggle, a thread's own switch, and the Settings default.

Needs a feature dir with the live-by-default change. Restarts the sandbox (fresh project) itself:
    py sandbox/scenarios/live_by_default.py [--feature-dir <checkout>]
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

QUIET_WAIT_S = 6


def wake_after_reply(thread_id: str, body: str, timeout_s: int) -> dict:
    """The agent is waiting while the user submits a reply in the thread; returns how the wait ended."""
    result: dict = {}
    waiter = threading.Thread(target=lambda: result.update(ui.agent_waits(timeout_s)))
    waiter.start()
    time.sleep(1.5)
    ui.user_replies(thread_id, body)
    waiter.join()
    return result


def woke_live(wake: dict, thread_id: str) -> bool:
    return wake.get("reason") == "live" and [t["thread_id"] for t in wake.get("awaiting", [])] == [thread_id]


def set_app_default(enabled: bool) -> None:
    ui.js(f"""
        var settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(pluginLoader.loadClass('dev.marginalis.plugin.settings.MarginalisSettings'));
        settings.getState().setLiveByDefault({str(enabled).lower()});
        String(true)
    """)


def restart(feature_dir: Path, fresh: bool) -> None:
    sandbox.stop()
    sandbox.start(feature_dir, fresh)
    ui.show_tool_window()


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def toolbar_makes_every_open_thread_live(self) -> None:
        first = ui.agent_starts_project_thread("First thread: does a reply wake me?")
        second = ui.agent_starts_project_thread("Second thread")
        time.sleep(1)

        quiet = wake_after_reply(first, "Before live by default", QUIET_WAIT_S)
        self.check("without the default a reply does not wake the agent", quiet.get("reason") == "timeout", quiet)

        ui.press_tool_window_action("Live by default")
        self.check("the toolbar switches the project's default on", ui.live_by_default())
        self.check("every open thread is live", ui.is_live(first) and ui.is_live(second))

        wake = wake_after_reply(second, "After live by default", 30)
        self.check("a reply in any thread wakes the agent with that thread", woke_live(wake, second), wake)

    def thread_switch_overrides_the_default(self) -> None:
        thread = ui.agent_starts_project_thread("Thread to switch off")
        time.sleep(1)
        ui.toggle_thread_live(thread)
        self.check("a thread's own switch turns it off under the default", not ui.is_live(thread))

        quiet = wake_after_reply(thread, "Switched off", QUIET_WAIT_S)
        self.check("a thread switched off stays quiet", quiet.get("reason") == "timeout", quiet)

        ui.press_tool_window_action("Live by default")
        ui.press_tool_window_action("Live by default")
        self.check("flipping the default clears the thread's override", ui.is_live(thread))

    def settings_default_is_copied_once(self) -> None:
        set_app_default(True)
        restart(self.feature_dir, fresh=True)
        self.check("a new project takes the Settings default", ui.live_by_default())

        set_app_default(False)
        restart(self.feature_dir, fresh=False)
        self.check("changing Settings leaves an existing project alone", ui.live_by_default())

        ui.press_tool_window_action("Live by default")
        restart(self.feature_dir, fresh=False)
        self.check("the project's own choice survives a restart", not ui.live_by_default())

        workspace = (sandbox.PROJECT / ".idea" / "workspace.xml").read_text(encoding="utf-8")
        self.check("the choice lives in workspace.xml", 'name="MarginalisLiveDefault"' in workspace)

    def run(self) -> int:
        if not sandbox.robot_up():
            sandbox.start(self.feature_dir, fresh=False)
        set_app_default(False)
        restart(self.feature_dir, fresh=True)
        self.toolbar_makes_every_open_thread_live()
        self.thread_switch_overrides_the_default()
        self.settings_default_is_copied_once()
        set_app_default(False)
        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        return 1 if self.failures else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    args = parser.parse_args()
    sys.exit(Scenarios(args.feature_dir.resolve()).run())


if __name__ == "__main__":
    main()
