"""Ondra's fork PRs #1–#4, all merged into schreko_stable: run against a checkout of it (the dev worktree after
`publish_dev.py --stable`), or against one PR's own worktree for a single check.

    py sandbox/scenarios/stable_prs.py [--feature-dir <checkout>] [--only zoom,tables,...]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

SHOTS = sandbox.RUNTIME / "shots"

REVIEW_FILE = "src/review_target.py"
# The PR's version: three lines added on top (every surviving line moves down by three), legacy_helper removed.
REVIEW_AFTER = """import csv


def parse(text: str) -> list[str]:
    return text.split(",")


def total(values: list[int]) -> int:
    return sum(values)
"""
REVIEW_BEFORE = (sandbox.HERE / "project-template" / REVIEW_FILE).read_text(encoding="utf-8")

WIDE_TABLE = "| " + " | ".join(f"column {i}" for i in range(1, 25)) + " |\n" + \
             "|" + "---|" * 24 + "\n" + \
             "| " + " | ".join(f"value {i}" for i in range(1, 25)) + " |\n"


class Scenarios:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def tables(self) -> None:
        """PR #2 — GFM tables and strikethrough; a wide top-level table scrolls sideways."""
        thread = ui.agent_starts_project_thread(
            "Before the table, prose that should keep wrapping to the panel.\n\n" + WIDE_TABLE +
            "\nAnd ~~struck out~~ text after it.")
        time.sleep(2)
        panes = ui.message_panes(thread)
        tables = [pane for pane in panes if pane["table"]]
        self.check("the table renders as a table", len(tables) == 1, panes)
        self.check("strikethrough renders as <s>", any(pane["strike"] for pane in panes), panes)
        if tables:
            table = tables[0]
            self.check("the wide table keeps its natural width", table["width"] > table["viewportWidth"] > 0, table)
            self.check("the wide table scrolls sideways", table["horizontalBarVisible"], table)
        ui.screenshot_thread(thread, SHOTS / "pr2-table.png")

    def review_diff_icons(self) -> None:
        """PR #3 — thread icons in review diffs, re-anchored on the diff's text; added files too."""
        ui.agent_adds_line_thread(REVIEW_FILE, 2, "On parse")
        ui.agent_adds_line_thread(REVIEW_FILE, 6, "On total")
        ui.agent_adds_line_thread(REVIEW_FILE, 9, "On legacy_helper, which the PR deletes")
        time.sleep(1)
        ui.open_review_diff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        time.sleep(2)
        lines = ui.diff_icon_lines(REVIEW_AFTER)
        # 1-based 2 and 6 in the base (0-based 1 and 5) move to 0-based 4 and 8 in the PR text; legacy_helper is gone.
        self.check("icons sit on the threads' lines in the PR text", lines == [4, 8], lines)

        ui.open_review_diff(REVIEW_FILE, "", REVIEW_AFTER, added=True)
        time.sleep(2)
        self.check("an added file's one-sided diff shows icons too", ui.diff_icon_lines(REVIEW_AFTER) == [4, 8],
                   ui.diff_icon_lines(REVIEW_AFTER))

    def stay_in_diff(self) -> None:
        """PR #1 — Next Step within the same file stays in a diff that shows the file itself (a checked-out
        branch). A diff of a revision's copy (PR #3's case) is reported, not checked: #1 does not cover it."""
        first = ui.agent_adds_line_thread(REVIEW_FILE, 2, "Step 1: parse", order=1)
        second = ui.agent_adds_line_thread(REVIEW_FILE, 6, "Step 2: total", order=2)
        time.sleep(1)

        ui.close_all_editors()
        ui.open_local_diff(REVIEW_FILE, REVIEW_AFTER)
        time.sleep(2)
        ui.open_thread_in_diff(first, REVIEW_BEFORE)
        time.sleep(1)
        ui.press_panel_action(first, "Next Step")
        time.sleep(2)
        self.check("next step in a local diff keeps the diff in front", ui.selected_editor_kind() == "DIFF",
                   ui.selected_editor_kind())
        open_in_diff = ui.thread_ids_open_in_diff(REVIEW_BEFORE)
        self.check("the next step opens in that diff", second in open_in_diff, open_in_diff)

        ui.close_all_editors()
        ui.open_review_diff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        time.sleep(2)
        ui.open_thread_in_diff(first, REVIEW_AFTER)
        time.sleep(1)
        ui.press_panel_action(first, "Next Step")
        time.sleep(2)
        print(f"INFO  next step in a revision-copy diff lands in: {ui.selected_editor_kind()}")

    def resolve_in_review_diff(self) -> None:
        """Resolve with auto-advance in a diff of a revision's copy (an MR not checked out), the way GitLab shows it."""
        first = ui.agent_adds_line_thread(REVIEW_FILE, 2, "Resolve me: parse", order=1)
        second = ui.agent_adds_line_thread(REVIEW_FILE, 6, "Then me: total", order=2)
        time.sleep(1)
        ui.close_all_editors()
        ui.open_review_diff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        time.sleep(2)
        ui.open_thread_in_diff(first, REVIEW_AFTER)
        time.sleep(1)
        ui.press_panel_action(first, "Resolve")
        time.sleep(2)
        kind = ui.selected_editor_kind()
        self.check("Resolve in a review diff keeps the diff in front", kind == "DIFF", kind)
        open_in_diff = ui.thread_ids_open_in_diff(REVIEW_AFTER)
        self.check("the next step opens in that diff", second in open_in_diff, open_in_diff)
        ui.close_all_editors()
        ui.open_local_diff(REVIEW_FILE, REVIEW_AFTER)
        time.sleep(2)
        ui.open_thread_in_diff(second, REVIEW_BEFORE)
        time.sleep(1)
        ui.press_panel_action(second, "Resolve")
        time.sleep(2)
        print(f"INFO  resolve in a local diff (checked-out branch) lands in: {ui.selected_editor_kind()}")

    def zoom(self) -> None:
        """PR #4 — Ctrl+wheel zoom with a thread open keeps the view where the mouse points."""
        file = "src/long_module.py"
        thread = ui.agent_adds_line_thread(file, 92, "A long thread.\n\n" + "\n\n".join(
            f"Paragraph {i}: the body is long so its end is far below the line it is anchored on." for i in range(30)))
        ui.open_in_editor(file)
        time.sleep(1)
        ui.open_thread_in_editor(thread, file)
        time.sleep(1)
        ui.scroll_editor_to(file, 70)
        time.sleep(1)
        before_size = ui.editor_font_size(file)
        before = ui.line_under_point(file, 200, 150)
        ui.ctrl_wheel_zoom(file, 200, 150)
        time.sleep(2)
        after = ui.line_under_point(file, 200, 150)
        self.check("the zoom happened", ui.editor_font_size(file) > before_size, ui.editor_font_size(file))
        self.check("the line under the mouse stays put", abs(after - before) <= 2, f"{before} -> {after}")

    def run(self, only: list[str]) -> int:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.show_tool_window()
        for name in only:
            getattr(self, name)()
        print(f"\n{len(self.failures)} failed" if self.failures else "\nall passed")
        return 1 if self.failures else 0


ALL = ["tables", "review_diff_icons", "stay_in_diff", "zoom"]
# Known gap, not in the default run: `--only resolve_in_review_diff` (Resolve in a diff of an MR not checked out).


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feature-dir", type=Path, default=sandbox.DEFAULT_FEATURE_DIR)
    parser.add_argument("--only", default=",".join(ALL))
    parser.add_argument("--no-restart", action="store_true", help="reuse the running sandbox as it is")
    args = parser.parse_args()
    if not args.no_restart:
        sandbox.stop()
        sandbox.start(args.feature_dir.resolve(), fresh=True)
    sys.exit(Scenarios().run(args.only.split(",")))


if __name__ == "__main__":
    main()
