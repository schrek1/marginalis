"""Guided layout (fork MARG-10, MARG-47): the tool window lays a walkthrough out by step number (default; consecutive
steps in one file fold under it, consecutive project-level steps under a project node), by folder or by file, remembers the choice per project, and the step arrows follow step
numbers in every layout.

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
from fixtures import interleaved_walkthrough as hopping

SHOTS = sandbox.RUNTIME / "shots" / "guided_layout"
PAUSE_S = 1.5
OPEN_STEPS = sorted(order for order, _, _ in fixture.steps() if order not in fixture.RESOLVED)
HOPPING = f"Guided {hopping.LABEL}"
# The hopping walk by step number: runs of consecutive steps in one file fold, a lone step stays a row.
HOPPING_BY_STEP = [[2, 3], [4, 5], [6], [7], [8], [9], [10], [11], [12, 13], [14], [15, 16], [17], [18]]
# A walkthrough that opens with project-level steps (no file) and comes back to them after file steps; its label sorts
# after the other two, so the plain "Guided" prefix still finds the 33-step walk.
PROJECT_FIRST_LABEL = "SHOP-90"
PROJECT_FIRST = f"Guided {PROJECT_FIRST_LABEL}"
PROJECT_FIRST_STEPS: list[tuple[int, str | None, int | None]] = [
    (1, None, None),
    (2, None, None),
    (3, None, None),
    (4, hopping.CONTROLLER, 12),
    (5, None, None),
    (6, hopping.SERVICE_TEST, 14),
    (7, hopping.SERVICE_TEST, 36),
    (8, None, None),
    (9, None, None),
]
# By step number: a run of two or more project-level steps folds under a project node, a lone one stays a row.
PROJECT_FIRST_BY_STEP = [
    ("ProjectNode", [1, 2, 3]),
    ("ThreadNode", [4]),
    ("ThreadNode", [5]),
    ("FileNode", [6, 7]),
    ("ProjectNode", [8, 9]),
]


def top_level_steps(shape: list[dict]) -> list[int]:
    return [step for child in shape for step in child["steps"]]


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
        hopping.write_files(sandbox.PROJECT)
        ui.refresh_project_files()
        time.sleep(2)
        hopping_ids = {order: ui.agent_adds_step(path, line, f"Step {order}: review note", order, hopping.LABEL)
                       for order, path, line in hopping.steps()}
        for order in hopping.RESOLVED:
            ui.resolve_thread(hopping_ids[order])
        for order, path, line in PROJECT_FIRST_STEPS:
            body = f"Step {order}: {'overview' if path is None else 'review note'}"
            if path is None:
                ui.agent_adds_project_step(body, order, PROJECT_FIRST_LABEL)
            else:
                ui.agent_adds_step(path, line, body, order, PROJECT_FIRST_LABEL)
        time.sleep(3)

    def default_is_the_step_number_list(self) -> None:
        shape = ui.guided_shape()
        self.check("by default the layout is By step number", ui.guided_layout() == "BY_STEP_NUMBER", ui.guided_layout())
        self.check("by default the steps read in step order", top_level_steps(shape) == OPEN_STEPS, top_level_steps(shape))

    def by_folder_groups_under_folders(self) -> None:
        ui.choose_guided_layout("By folder")
        time.sleep(PAUSE_S)
        shape = ui.guided_shape()
        self.shot("1-by-folder")
        self.check("By folder groups the steps under folders", any(c["kind"] == "DirNode" for c in shape), shape)

    def by_step_number_lists_steps_in_order(self) -> None:
        ui.choose_guided_layout("By step number")
        time.sleep(PAUSE_S)
        shape = ui.guided_shape()
        self.shot("2-by-step-number")
        self.check("By step number has no folder nodes",
                   {c["kind"] for c in shape} <= {"ThreadNode", "FileNode"}, {c["kind"] for c in shape})
        self.check("By step number lists the steps 1, 2, 3 … in order",
                   top_level_steps(shape) == OPEN_STEPS, top_level_steps(shape))
        self.check("each lone step's row names its file",
                   all(c["showsFile"] for c in shape if c["kind"] == "ThreadNode"))

    def by_step_number_folds_runs_in_one_file(self) -> None:
        shape = ui.guided_shape(HOPPING)
        self.shot("3-by-step-number-hopping")
        self.check("in a hopping walk, consecutive steps in one file fold under one node and lone steps stay rows",
                   [c["steps"] for c in shape] == HOPPING_BY_STEP, [c["steps"] for c in shape])
        runs = [c for c in shape if c["kind"] == "FileNode"]
        self.check("every folded run holds two or more steps, four runs in all", all(len(c["steps"]) > 1 for c in runs) and len(runs) == 4,
                   [(c["name"], c["steps"]) for c in runs])
        service = hopping.SERVICE
        self.check("a file the walk comes back to appears again in step order",
                   [c["steps"] for c in shape if c["name"] == service] == [[2, 3]]
                   and [6] in [c["steps"] for c in shape] and [9] in [c["steps"] for c in shape],
                   [(c["name"], c["steps"]) for c in shape])

    def by_step_number_folds_project_level_runs(self) -> None:
        shape = ui.guided_shape(PROJECT_FIRST)
        self.shot("3b-by-step-number-project-first")
        self.check("consecutive project-level steps fold under a project node and a lone one stays a row",
                   [(c["kind"], c["steps"]) for c in shape] == PROJECT_FIRST_BY_STEP,
                   [(c["kind"], c["steps"]) for c in shape])
        self.check("a project-level run after file steps folds under a project node of its own",
                   [c["steps"] for c in shape if c["kind"] == "ProjectNode"] == [[1, 2, 3], [8, 9]],
                   [(c["kind"], c["steps"]) for c in shape])
        ui.select_step(1, PROJECT_FIRST)
        time.sleep(PAUSE_S)
        seen = [ui.selected_step()["order"]]
        for _ in range(len(PROJECT_FIRST_STEPS) - 1):
            press("Next Step", "Next Occurrence")
            time.sleep(PAUSE_S)
            seen.append(ui.selected_step()["order"])
        expected = [order for order, _, _ in PROJECT_FIRST_STEPS]
        self.check("Next walks into, through and out of the project runs in step order", seen == expected,
                   f"{seen} vs {expected}")

    def by_file_has_one_node_per_file(self) -> None:
        ui.choose_guided_layout("By file")
        time.sleep(PAUSE_S)
        shape = ui.guided_shape()
        self.shot("4-by-file")
        files = [c for c in shape if c["kind"] == "FileNode"]
        self.check("By file has a file node per file and no folders",
                   len(files) == len(shape) and len(files) > 1, [c["kind"] for c in shape])
        self.check("a file node shows the file's full path", all("/" in c["name"] for c in files),
                   [c["name"] for c in files][:3])
        firsts = [min(c["steps"]) for c in files]
        self.check("files follow their first step", firsts == sorted(firsts), firsts)
        self.check("steps inside a file follow their numbers", all(c["steps"] == sorted(c["steps"]) for c in files))
        hopping_files = {c["name"]: c["steps"] for c in ui.guided_shape(HOPPING)}
        self.check("in a hopping walk, By file gathers every step of a file",
                   hopping_files.get(hopping.SERVICE) == [2, 3, 6, 9], hopping_files)

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
            self.default_is_the_step_number_list,
            self.by_folder_groups_under_folders,
            self.by_step_number_lists_steps_in_order,
            self.by_step_number_folds_runs_in_one_file,
            self.by_step_number_folds_project_level_runs,
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
