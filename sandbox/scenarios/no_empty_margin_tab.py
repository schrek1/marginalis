"""The Margin tab shows only with something in it: a fresh project opens without it, the agent's first project
thread brings it, an empty tab does not come back after a restart, and a project with an open project thread opens
it unpinned.

Restarts the sandbox (fresh project) itself:
    py sandbox/scenarios/no_empty_margin_tab.py [--feature-dir <checkout>]
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

SHOTS = sandbox.RUNTIME / "shots" / "no_empty_margin_tab"
THIS_CHECKOUT = Path(__file__).resolve().parents[2]
# The tab's startup decision runs on the EDT right after the persisted threads land in the store.
SETTLE_S = 3
WAIT_S = 60
THREADS_FILE = sandbox.PROJECT / ".idea" / "marginalis.json"

_MARGIN_TAB = """
var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
var windows = manager.getWindows();
var pinned = false;
for (var i = 0; i < windows.length; i++) {
    if (windows[i].isFileOpen(marginFile) && windows[i].isFilePinned(marginFile)) pinned = true;
}
String(JSON.stringify({open: manager.isFileOpen(marginFile), pinned: pinned}))
"""


def margin_tab() -> dict:
    """Whether the project has the Margin tab open, and pinned."""
    return json.loads(ui.js(_MARGIN_TAB))


def threads_in_store() -> int:
    return int(ui.js("String(store.getThreads().all().size())"))


def project_introduced() -> bool:
    return ui.js("""
        String(com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .getBoolean('marginalis.projectTab.introduced'))
    """) == "true"


def wait_until(condition, what: str) -> None:
    deadline = time.monotonic() + WAIT_S
    while time.monotonic() < deadline:
        try:
            if condition():
                return
        except Exception:
            pass  # the project or the plugin may still be loading
        time.sleep(0.5)
    raise TimeoutError(f"{what} not within {WAIT_S}s")


def persisted_status(thread_id: str) -> str | None:
    text = sandbox.project_text(".idea/marginalis.json")
    if text is None:
        return None
    threads = json.loads(text).get("threads", [])
    return next((thread["status"] for thread in threads if thread["id"] == thread_id), None)


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []
        self.first_thread: str | None = None

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def restart(self, fresh: bool) -> None:
        sandbox.stop()
        sandbox.start(self.feature_dir, fresh)

    def settle_after_start(self, threads: int) -> None:
        wait_until(project_introduced, "the project's first-open mark")
        wait_until(lambda: threads_in_store() >= threads, f"{threads} persisted threads in the store")
        time.sleep(SETTLE_S)

    def fresh_project_opens_without_the_tab(self) -> None:
        self.restart(fresh=True)
        self.settle_after_start(threads=0)
        self.shot("1-fresh-project")
        self.check("a fresh project without project threads opens without the Margin tab",
                   not margin_tab()["open"], margin_tab())

    def first_project_thread_brings_the_tab(self) -> None:
        self.first_thread = ui.agent_starts_project_thread("The first project thread.")
        time.sleep(SETTLE_S)
        self.shot("2-first-project-thread")
        tab = margin_tab()
        self.check("the agent's first project thread opens the Margin tab", tab["open"], tab)
        self.check("the Margin tab opened by a thread is not pinned", not tab["pinned"], tab)

    def empty_tab_is_not_restored(self) -> None:
        ui.resolve_thread(self.first_thread)
        wait_until(lambda: persisted_status(self.first_thread) == "RESOLVED", "the resolved thread on disk")
        self.check("the resolved thread leaves the Margin tab open and empty (precondition)", margin_tab()["open"],
                   margin_tab())
        ui.save_project_state()
        self.restart(fresh=False)
        self.settle_after_start(threads=1)
        self.shot("3-restart-after-empty-tab")
        self.check("a restart does not bring back an empty Margin tab", not margin_tab()["open"], margin_tab())

    def open_thread_opens_the_tab_unpinned(self) -> None:
        thread = ui.agent_starts_project_thread("An open project thread before the project is opened again.")
        wait_until(lambda: persisted_status(thread) == "OPEN", "the open thread on disk")
        threads_on_disk = sandbox.project_text(".idea/marginalis.json")
        sandbox.stop()
        # A first open of a project that already has project threads, as after installing or updating the plugin.
        sandbox.fresh_project()
        THREADS_FILE.parent.mkdir(parents=True, exist_ok=True)
        THREADS_FILE.write_text(threads_on_disk, encoding="utf-8")
        sandbox.start(self.feature_dir, fresh=False)
        self.settle_after_start(threads=2)
        self.shot("4-first-open-with-a-project-thread")
        tab = margin_tab()
        self.check("a project with an open project thread opens with the Margin tab", tab["open"], tab)
        self.check("that Margin tab is not pinned", not tab["pinned"], tab)

    def guarded(self, scenario) -> None:
        try:
            scenario()
        except Exception as error:  # a missing feature on main shows up here, not as a crash of the whole run
            self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
            traceback.print_exc(limit=1)

    def run(self) -> int:
        for scenario in (
            self.fresh_project_opens_without_the_tab,
            self.first_project_thread_brings_the_tab,
            self.empty_tab_is_not_restored,
            self.open_thread_opens_the_tab_unpinned,
        ):
            self.guarded(scenario)
        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        print(f"screenshots: {SHOTS}")
        return 1 if self.failures else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=THIS_CHECKOUT)
    args = parser.parse_args()
    sys.exit(Scenarios(args.feature_dir.resolve()).run())


if __name__ == "__main__":
    main()
