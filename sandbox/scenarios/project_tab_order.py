"""Margin tab order: oldest first by default, walkthrough steps in step order, the Sort menu, the new-thread draft
on the chosen side, and the choice remembered per project.

Restarts the sandbox (fresh project) itself:
    py sandbox/scenarios/project_tab_order.py [--feature-dir <checkout>]

Hold still's "N new threads" pill needs the tab in front, which the sandbox on another desktop never is; its
direction is covered by the core tests (HoldStillTest).
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

SHOTS = sandbox.RUNTIME / "shots" / "project_tab_order"
SETTLE_S = 1.5


def restart(feature_dir: Path, fresh: bool) -> None:
    sandbox.stop()
    sandbox.start(feature_dir, fresh)


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []
        self.threads: dict[str, str] = {}

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def listed(self, *names: str) -> list[str]:
        """The tab's order as scenario names, keeping only the given threads and the draft."""
        by_id = {thread_id: name for name, thread_id in self.threads.items()}
        wanted = set(names) | {"draft"}
        return [by_id.get(entry, entry) for entry in ui.margin_tab_order() if by_id.get(entry, entry) in wanted]

    def start(self, name: str) -> None:
        self.threads[name] = ui.agent_starts_project_thread(f"Thread {name}")
        time.sleep(0.3)

    def step(self, number: int) -> None:
        self.threads[f"step{number}"] = ui.agent_adds_project_step(f"Step {number}", order=number, walkthrough="tour")
        time.sleep(0.3)

    def default_is_oldest_first(self) -> None:
        for name in ("a", "b", "c"):
            self.start(name)
        ui.agent_replies(self.threads["a"], "news on the oldest thread")
        ui.open_margin_tab(self.threads["a"])
        time.sleep(SETTLE_S)
        self.shot("1-default-oldest-first")
        self.check("by default the tab lists the oldest thread first, and a reply does not move it",
                   self.listed("a", "b", "c") == ["a", "b", "c"], self.listed("a", "b", "c"))

    def walkthrough_steps_read_in_step_order(self) -> None:
        for number in (3, 1, 2):
            self.step(number)
        time.sleep(SETTLE_S)
        self.shot("2-steps-in-step-order")
        steps = [entry for entry in self.listed("step1", "step2", "step3")]
        self.check("project-level steps posted as 3, 1, 2 read 1, 2, 3", steps == ["step1", "step2", "step3"], steps)

    def sort_menu_switches_the_order(self) -> None:
        ui.choose_margin_sort("Newest first")
        time.sleep(SETTLE_S)
        self.shot("3-newest-first")
        self.check("the Sort menu stores Newest first for the project", ui.margin_sort() == "NEWEST_FIRST", ui.margin_sort())
        listed = self.listed("a", "b", "c", "step1", "step2", "step3")
        self.check("newest first lists the latest created thread first, steps still 1, 2, 3",
                   listed == ["step1", "step2", "step3", "c", "b", "a"], listed)

        ui.choose_margin_sort("Recent activity")
        ui.agent_replies(self.threads["b"], "news on b")
        time.sleep(SETTLE_S)
        self.shot("4-recent-activity")
        self.check("recent activity puts the thread with the latest reply first",
                   self.listed("a", "b", "c")[:1] == ["b"], self.listed("a", "b", "c"))

    def draft_sits_on_the_new_side(self) -> None:
        ui.choose_margin_sort("Oldest first")
        ui.user_starts_new_project_thread()
        time.sleep(SETTLE_S)
        self.shot("5-draft-oldest-first")
        listed = self.listed("a", "b", "c")
        self.check("with oldest first a new thread's draft sits below the threads", listed[-1:] == ["draft"], listed)

        ui.choose_margin_sort("Newest first")
        time.sleep(SETTLE_S)
        self.shot("6-draft-newest-first")
        listed = self.listed("a", "b", "c")
        self.check("with newest first the draft sits above the threads", listed[:1] == ["draft"], listed)

    def choice_survives_a_restart(self) -> None:
        ui.choose_margin_sort("Newest first")
        ui.save_project_state()
        restart(self.feature_dir, fresh=False)
        self.check("the project's Sort choice survives a restart", ui.margin_sort() == "NEWEST_FIRST", ui.margin_sort())
        workspace = (sandbox.PROJECT / ".idea" / "workspace.xml").read_text(encoding="utf-8")
        self.check("the choice lives in workspace.xml", 'name="MarginalisProjectTabSort"' in workspace)

    def guarded(self, scenario) -> None:
        try:
            scenario()
        except Exception as error:  # a missing feature on main shows up here, not as a crash of the whole run
            self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
            traceback.print_exc(limit=1)

    def run(self) -> int:
        restart(self.feature_dir, fresh=True)
        for scenario in (
            self.default_is_oldest_first,
            self.walkthrough_steps_read_in_step_order,
            self.sort_menu_switches_the_order,
            self.draft_sits_on_the_new_side,
            self.choice_survives_a_restart,
        ):
            self.guarded(scenario)
        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        print(f"screenshots: {SHOTS}")
        return 1 if self.failures else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    args = parser.parse_args()
    sys.exit(Scenarios(args.feature_dir.resolve()).run())


if __name__ == "__main__":
    main()
