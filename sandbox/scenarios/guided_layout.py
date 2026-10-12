"""Guided layout (fork MARG-10): the tool window lays a walkthrough out by folder (default), by step number or by
file, remembers the choice per project, and the step arrows follow step numbers in every layout.

    py sandbox/scenarios/guided_layout.py [--feature-dir <checkout>]
"""
from __future__ import annotations

import argparse
import sys
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import robot
import sandbox
import sandbox_ui as ui
from fixtures import deep_walkthrough as fixture

SHOTS = sandbox.RUNTIME / "shots" / "guided_layout"
PAUSE_S = 1.5
OPEN_STEPS = sorted(order for order, _, _ in fixture.steps() if order not in fixture.RESOLVED)


def press(*names: str) -> None:
    for name in names:
        try:
            ui.press_tool_window_action(name)
            return
        except robot.RobotError as error:
            if "no tool window button" not in str(error):
                raise
    raise RuntimeError(f"none of {names} is in the tool window header")


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_tool_window(SHOTS / f"{name}.png")

    def build(self) -> None:
        ui.show_tool_window()
        fixture.write_files(sandbox.PROJECT)
        ui.refresh_project_files()
        time.sleep(2)
        ids = {order: ui.agent_adds_step(path, line, f"Step {order}: review note", order, fixture.LABEL)
               for order, path, line in fixture.steps()}
        for order in fixture.RESOLVED:
            ui.resolve_thread(ids[order])
        time.sleep(3)

    def default_is_the_folder_tree(self) -> None:
        shape = ui.guided_shape()
        self.shot("1-by-folder")
        self.check("by default the layout is By folder", ui.guided_layout() == "BY_FOLDER", ui.guided_layout())
        self.check("By folder groups the steps under folders", any(c["kind"] == "DirNode" for c in shape), shape)

    def by_step_number_is_a_flat_list(self) -> None:
        ui.choose_guided_layout("By step number")
        time.sleep(PAUSE_S)
        shape = ui.guided_shape()
        self.shot("2-by-step-number")
        self.check("By step number lists only steps, no folder or file nodes",
                   {c["kind"] for c in shape} == {"ThreadNode"}, {c["kind"] for c in shape})
        self.check("By step number lists the steps 1, 2, 3 … in order",
                   [c["steps"][0] for c in shape] == OPEN_STEPS, [c["steps"][0] for c in shape])
        self.check("each row of the flat list names its file", all(c["showsFile"] for c in shape))

    def by_file_has_one_node_per_file(self) -> None:
        ui.choose_guided_layout("By file")
        time.sleep(PAUSE_S)
        shape = ui.guided_shape()
        self.shot("3-by-file")
        files = [c for c in shape if c["kind"] == "FileNode"]
        self.check("By file has a file node per file and no folders",
                   len(files) == len(shape) and len(files) > 1, [c["kind"] for c in shape])
        self.check("a file node shows the file's full path", all("/" in c["name"] for c in files),
                   [c["name"] for c in files][:3])
        firsts = [min(c["steps"]) for c in files]
        self.check("files follow their first step", firsts == sorted(firsts), firsts)
        self.check("steps inside a file follow their numbers", all(c["steps"] == sorted(c["steps"]) for c in files))

    def arrows_follow_step_numbers_by_file(self) -> None:
        ui.select_step(OPEN_STEPS[0])
        time.sleep(PAUSE_S)
        seen = [ui.selected_step()["order"]]
        for _ in range(5):
            press("Next Step", "Next Occurrence")
            time.sleep(PAUSE_S)
            seen.append(ui.selected_step()["order"])
        self.check("in By file Next walks the step numbers", seen == OPEN_STEPS[:6], f"{seen} vs {OPEN_STEPS[:6]}")

    def choice_survives_a_restart(self) -> None:
        ui.save_project_state()
        sandbox.stop()
        sandbox.start(self.feature_dir, fresh=False)
        ui.show_tool_window()
        self.check("the project's layout survives a restart", ui.guided_layout() == "BY_FILE", ui.guided_layout())
        workspace = (sandbox.PROJECT / ".idea" / "workspace.xml").read_text(encoding="utf-8")
        self.check("the choice lives in workspace.xml", 'name="MarginalisGuidedLayout"' in workspace)

    def run(self) -> int:
        self.build()
        for scenario in (
            self.default_is_the_folder_tree,
            self.by_step_number_is_a_flat_list,
            self.by_file_has_one_node_per_file,
            self.arrows_follow_step_numbers_by_file,
            self.choice_survives_a_restart,
        ):
            try:
                scenario()
            except Exception as error:
                self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
                traceback.print_exc(limit=1)
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
    sys.exit(Scenarios(args.feature_dir.resolve()).run())


if __name__ == "__main__":
    main()
