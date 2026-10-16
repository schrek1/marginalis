"""The ✉ envelope shows only while something in the thread is unread (MARG-36).

Restarts the sandbox (fresh project) itself:
    py sandbox/scenarios/envelope_only_while_unread.py [--feature-dir <checkout>]

Four line threads in one file cover every case: A an agent reply the user has not read, B one the user read but
did not answer, C one the user answered (the agent's move), D an FYI the user read. Two project threads repeat
A and B in the Margin tab. The user reads B, C, D and Project B through the store, as an open thread panel does
once it is on screen, so which threads are read does not depend on what the sandbox happens to show. Project A
arrives while the code editor is in front: a card on screen in the Margin tab would be read at once.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import traceback
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import sandbox
import sandbox_ui as ui

FILE = "src/review_target.py"
FILE_NAME = "review_target.py"
SHOTS = sandbox.RUNTIME / "shots" / "envelope_only_while_unread"
SETTLE_S = 2.0

_SIGNALS = """
var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
var turns = pluginLoader.loadClass('dev.marginalis.core.Turn');
var yourMoveIcon = icons.turnSignal(turns.getField('USER_OWES').get(null));
var agentsMoveIcon = icons.turnSignal(turns.getField('AGENT_OWES').get(null));
function signalOf(icon) {
    if (icon === yourMoveIcon) return 'you';
    if (icon === agentsMoveIcon) return 'agent';
    if (icon instanceof com.intellij.util.ui.EmptyIcon) return 'none';
    return 'unknown:' + (icon == null ? 'null' : icon.getClass().getSimpleName());
}
function trailingSignal(rowIcon) {
    if (rowIcon == null) return 'unknown:no icon';
    if (!rowIcon.getIcon) return 'unknown:' + rowIcon.getClass().getSimpleName();
    return signalOf(rowIcon.getIcon(2));
}
function labelsIn(c, found) {
    if (c instanceof javax.swing.JLabel) found.push(c);
    var children = c.getComponents ? c.getComponents() : [];
    for (var i = 0; i < children.length; i++) labelsIn(children[i], found);
    return found;
}
"""


def user_reads(thread_id: str) -> None:
    ui.js(f"""
        var thread = store.getThreads().byId('{thread_id}');
        thread.markReadByUser(thread.getMessages());
        store.getThreads().notifyChanged(thread);
        String(true)
    """)


def unread_by_user(thread_id: str) -> int:
    return int(ui.js(f"String(store.getThreads().byId('{thread_id}').unreadByUserCount())"))


def tree_signals() -> dict:
    """What the Open section of the tool window tree shows: per thread id the turn signal after its mark
    (`you`, `agent` or `none`), and per file name the ✉ and ✈ counts on its row (null when hidden)."""
    raw = ui.js(ui._TREE + _SIGNALS + """
        var tree = marginalisTree();
        var renderer = tree.getCellRenderer();
        var root = tree.getModel().getRoot();
        var result = { threads: {}, files: {} };
        for (var s = 0; s < root.getChildCount(); s++) {
            var section = root.getChildAt(s);
            if (sectionTitle(section).indexOf('Open') != 0) continue;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var node = nodes.nextElement();
                var data = node.getUserObject();
                var kind = data == null ? '' : String(data.getClass().getSimpleName());
                if (kind != 'ThreadNode' && kind != 'FileNode') continue;
                var cell = renderer.getTreeCellRendererComponent(tree, node, false, true, node.isLeaf(), 0, false);
                if (kind == 'ThreadNode') {
                    var words = null;
                    var parts = cell.getComponents();
                    for (var i = 0; i < parts.length; i++) {
                        if (parts[i] instanceof com.intellij.ui.ColoredTreeCellRenderer) words = parts[i];
                    }
                    result.threads[String(data.getThread().getId())] = trailingSignal(words == null ? null : words.getIcon());
                } else {
                    var counts = { you: null, agent: null };
                    var labels = labelsIn(cell, []);
                    for (var j = 0; j < labels.length; j++) {
                        var label = labels[j];
                        var side = signalOf(label.getIcon());
                        if ((side == 'you' || side == 'agent') && label.isVisible()) counts[side] = Number(String(label.getText()));
                    }
                    result.files[String(data.getName())] = counts;
                }
            }
        }
        String(JSON.stringify(result))
    """)
    return json.loads(raw)


def stripe_badge() -> str:
    """The Marginalis stripe button's icon: `AWAITING_YOU`, `BLOCKER` or `NONE` (the plain brand icon)."""
    return ui.js("""
        var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
        var badges = pluginLoader.loadClass('dev.marginalis.core.StripeBadge');
        var shown = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis').getIcon();
        for (var depth = 0; depth < 5 && shown != null && shown.retrieveIcon; depth++) {
            var inner = shown.retrieveIcon();
            if (inner === shown) break;
            shown = inner;
        }
        var verdict = 'unknown:' + (shown == null ? 'null' : shown.getClass().getName());
        if (shown === icons.toolWindow(null)) verdict = 'NONE';
        if (shown === icons.toolWindow(badges.getField('AWAITING_YOU').get(null))) verdict = 'AWAITING_YOU';
        if (shown === icons.toolWindow(badges.getField('BLOCKER').get(null))) verdict = 'BLOCKER';
        String(verdict)
    """)


TAB_LABEL_WAIT_S = 10


def margin_tab_title() -> str:
    """The Margin tab's title as its editor tab shows it. The title provider is no stand-in: it skips the refresh
    of the tab, so a label that never shows comes back as a reason, and the check on it fails."""
    deadline = time.monotonic() + TAB_LABEL_WAIT_S
    last_error = None
    while True:
        try:
            title = json.loads(ui.js(ui._MARGIN + """
                var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
                var title = null;
                var windows = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getWindows();
                for (var i = 0; i < windows.length && title == null; i++) {
                    var tabs = windows[i].getTabbedPane().getTabs().getTabs();
                    for (var j = 0; j < tabs.size(); j++) {
                        var object = tabs.get(j).getObject();
                        if (object === marginFile || (object != null && object.getFile && object.getFile() === marginFile)) {
                            title = String(tabs.get(j).getText());
                        }
                    }
                }
                String(JSON.stringify({ title: title }))
            """))["title"]
            if title is not None:
                return title
        except Exception as error:
            last_error = error
        if time.monotonic() >= deadline:
            return (f"<no Margin tab label within {TAB_LABEL_WAIT_S} s"
                    f"{f'; last {type(last_error).__name__}: {last_error}' if last_error else ''}>")
        time.sleep(0.5)


def margin_card_signals() -> dict:
    """Per thread id, the turn signal after the mark on its Margin tab card."""
    raw = ui.js(ui._MARGIN + _SIGNALS + """
        var children = fieldOf(marginPanel(), 'stack').getComponents();
        var result = {};
        for (var i = 0; i < children.length; i++) {
            if (String(children[i].getClass().getSimpleName()) != 'ThreadCard') continue;
            var icon = fieldOf(children[i], 'markLabel').getIcon();
            result[String(children[i].getThread().getId())] = trailingSignal(icon);
        }
        String(JSON.stringify(result))
    """)
    return json.loads(raw)


def margin_counts() -> str:
    return ui.js(ui._MARGIN + "String(fieldOf(marginPanel(), 'counts').getText())")


def file_turn(path: str) -> str:
    """The turn behind the editor tab and Project view badges of a file."""
    return ui.js(f"var turn = store.getFileTurns().of('{path}'); String(turn == null ? 'null' : turn.name())")


def awaiting_user_ids() -> set[str]:
    query = urllib.parse.urlencode({"project": ui.PROJECT_NAME, "awaiting": "user", "summary": "true", **ui.AGENT})
    with urllib.request.urlopen(f"{ui.api_base()}/comment_list?{query}", timeout=30) as response:
        return {thread["thread_id"] for thread in json.loads(response.read())["threads"]}


def agent_adds_fyi(line: int, body: str) -> str:
    anchor = (sandbox.PROJECT / FILE).read_text(encoding="utf-8").splitlines()[line - 1]
    return ui.api_post("comment_add", {"body": body, "file": FILE, "line": line, "anchor_text": anchor,
                                       "intent": "fyi", "project": ui.PROJECT_NAME, **ui.AGENT})["thread_id"]


def agent_adds_blocker(line: int, body: str) -> str:
    anchor = (sandbox.PROJECT / FILE).read_text(encoding="utf-8").splitlines()[line - 1]
    return ui.api_post("comment_add", {"body": body, "file": FILE, "line": line, "anchor_text": anchor,
                                       "severity": "blocker", "project": ui.PROJECT_NAME, **ui.AGENT})["thread_id"]


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []
        self.ids: dict[str, str] = {}

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def thread_signals(self) -> dict:
        shown = tree_signals()["threads"]
        return {name: shown.get(thread_id, "missing") for name, thread_id in self.ids.items()}

    def seed_the_four_cases(self) -> None:
        ui.show_tool_window()
        ui.open_in_editor(FILE)
        self.ids["A"] = ui.agent_adds_line_thread(FILE, 1, "A · a reply you have not read yet")
        self.ids["D"] = agent_adds_fyi(2, "D · an FYI you have already read")
        self.ids["B"] = ui.agent_adds_line_thread(FILE, 5, "B · a reply you read but did not answer")
        self.ids["C"] = ui.agent_adds_line_thread(FILE, 9, "C · you answered, the agent is on it")
        self.ids["Project B"] = ui.agent_starts_project_thread("Project B · a project note you read, not answered")
        ui.open_thread_in_editor(self.ids["C"], FILE)
        time.sleep(SETTLE_S)
        ui.user_replies(self.ids["C"], "Go ahead.")
        ui.open_margin_tab(self.ids["Project B"])
        time.sleep(SETTLE_S)
        for name in ("B", "C", "D", "Project B"):
            user_reads(self.ids[name])
        # Project A arrives while the user is back in the code: an expanded card on screen in the active IDE is read
        # at once, so posting it with the Margin tab in front would read it before any check.
        ui.open_in_editor(FILE)
        time.sleep(SETTLE_S)
        self.ids["Project A"] = ui.agent_starts_project_thread("Project A · a new project note, unread")
        time.sleep(SETTLE_S)
        ui.open_in_editor(FILE)
        time.sleep(SETTLE_S)
        self.shot("1-seeded")
        self.check_still_unread("setup")

    def check_still_unread(self, when: str) -> None:
        unread = {name: unread_by_user(self.ids[name]) for name in ("A", "Project A")}
        self.check(f"{when}: A and Project A are still unread", all(count > 0 for count in unread.values()), unread)

    def tree_shows_the_envelope_only_while_unread(self) -> None:
        signals = self.thread_signals()
        expected = {"A": "you", "B": "none", "C": "agent", "D": "none", "Project A": "you", "Project B": "none"}
        for name, signal in expected.items():
            self.check(f"tree: thread {name} shows {signal}", signals[name] == signal, signals[name])
        counts = tree_signals()["files"].get(FILE_NAME)
        self.check("tree: the file row counts ✉ 1 and ✈ 1", counts == {"you": 1, "agent": 1}, counts)

    def stripe_keeps_the_envelope_while_something_is_unread(self) -> None:
        badge = stripe_badge()
        self.check("stripe: the envelope badge stays while A is unread", badge == "AWAITING_YOU", badge)

    def margin_tab_shows_the_envelope_only_while_unread(self) -> None:
        self.check_still_unread("before the Margin tab checks")
        title = margin_tab_title()
        self.check("Margin tab: the title counts only the unread project thread (✉ 1)", title == "✉ 1", title)
        cards = margin_card_signals()
        self.check("Margin tab: Project A's card keeps the envelope", cards.get(self.ids["Project A"]) == "you", cards)
        self.check("Margin tab: Project B's card shows no envelope", cards.get(self.ids["Project B"]) == "none", cards)
        counts = margin_counts()
        self.check("Margin tab: the header still counts both as your move", "2 your move" in counts, counts)

    def the_real_turn_is_unchanged(self) -> None:
        awaiting = awaiting_user_ids()
        for name in ("B", "Project B"):
            self.check(f"API: {name} stays in awaiting=user", self.ids[name] in awaiting, sorted(awaiting))
        self.check("the file's editor-tab badge still follows your move", file_turn(FILE) == "USER_OWES", file_turn(FILE))

    def everything_read_clears_every_envelope(self) -> None:
        for name in ("A", "Project A"):
            user_reads(self.ids[name])
        time.sleep(SETTLE_S)
        self.shot("2-all-read")
        badge = stripe_badge()
        self.check("stripe: once everything is read the button is plain", badge == "NONE", badge)
        counts = tree_signals()["files"].get(FILE_NAME)
        self.check("tree: the file row shows no ✉ once everything is read", counts == {"you": None, "agent": 1}, counts)
        title = margin_tab_title()
        self.check("Margin tab: the title is Margin once everything is read", title == "Margin", title)
        self.check("the file's editor-tab badge still follows your move", file_turn(FILE) == "USER_OWES", file_turn(FILE))

    def a_new_agent_message_brings_the_envelope_back(self) -> None:
        ui.agent_replies(self.ids["B"], "One more thing about B.")
        time.sleep(SETTLE_S)
        self.shot("3-new-message-on-b")
        signal = self.thread_signals()["B"]
        self.check("tree: a new agent message on B brings its envelope back", signal == "you", signal)
        badge = stripe_badge()
        self.check("stripe: the envelope badge returns with it", badge == "AWAITING_YOU", badge)

    def a_blocker_still_wins(self) -> None:
        agent_adds_blocker(3, "A blocker: this must change before merge.")
        time.sleep(SETTLE_S)
        badge = stripe_badge()
        self.check("stripe: an open blocker's red badge wins over the envelope", badge == "BLOCKER", badge)

    def guarded(self, scenario) -> None:
        try:
            scenario()
        except Exception as error:  # a check that cannot run is a failure, not a crash of the whole run
            self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
            traceback.print_exc(limit=1)

    def run(self) -> int:
        sandbox.stop()
        sandbox.start(self.feature_dir, fresh=True)
        for scenario in (
            self.seed_the_four_cases,
            self.tree_shows_the_envelope_only_while_unread,
            self.stripe_keeps_the_envelope_while_something_is_unread,
            self.margin_tab_shows_the_envelope_only_while_unread,
            self.the_real_turn_is_unchanged,
            self.everything_read_clears_every_envelope,
            self.a_new_agent_message_brings_the_envelope_back,
            self.a_blocker_still_wins,
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
