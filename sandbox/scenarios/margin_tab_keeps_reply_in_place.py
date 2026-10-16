"""The Margin tab keeps what you look at in place while other threads change (MARG-45).

    py sandbox/scenarios/margin_tab_keeps_reply_in_place.py [--feature-dir <checkout>]

Three tall project threads; the agent posts a long message into the expanded thread above the one being looked at.
The card being written in (a reply draft, then a new project thread's draft) must keep its on-screen position, its
focus and its caret; without a draft, the topmost visible card must keep its position.

The sandbox window sits on another desktop and is never the active window, so no real focus can land in it. Where a
plain focus request is not remembered, the scenario records the reply box as the window's most recent focus owner,
which is exactly the state of a user who typed there and switched to another application.
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

SHOTS = sandbox.RUNTIME / "shots" / "margin_tab_keeps_reply_in_place"
SETTLE_S = 2.0
TOLERANCE_PX = 1
DRAFT_TEXT = "Half-written reply: the release can go out once the"
DRAFT_CARET = 20


def tall_body(title: str, paragraphs: int) -> str:
    return "\n\n".join([f"**{title}**"] + [f"{title}, paragraph {n}: notes on the release plan." for n in range(1, paragraphs + 1)])


_TAB = ui._MARGIN + ui._FIND + """
function stackOf() { return fieldOf(marginPanel(), 'stack'); }
function viewportOf() { return fieldOf(marginPanel(), 'scroll').getViewport(); }
function target(key) {
    if (key == 'draft') return fieldOf(fieldOf(marginPanel(), 'draftShown'), 'panel');
    var card = fieldOf(marginPanel(), 'cards').get(key);
    if (card == null) throw 'no card for ' + key;
    return card;
}
function topIn(component) { return javax.swing.SwingUtilities.convertPoint(component, 0, 0, stackOf()).y; }
function replyAreaOf(component) {
    return find(component, function (c) { return c instanceof com.intellij.ui.EditorTextField; });
}
function windowOf(component) { return javax.swing.SwingUtilities.getWindowAncestor(component); }
function rememberFocus(component) {
    component.requestFocusInWindow();
    var window = windowOf(component);
    if (window.getMostRecentFocusOwner() === component) return 'requested';
    var types = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Class'), 1);
    types[0] = java.lang.Class.forName('java.awt.Component');
    var remember = java.lang.Class.forName('java.awt.KeyboardFocusManager').getDeclaredMethod('setMostRecentFocusOwner', types);
    remember.setAccessible(true);
    var args = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Object'), 1);
    args[0] = component;
    remember.invoke(null, args);
    if (window.getMostRecentFocusOwner() !== component) throw 'the focus owner could not be recorded';
    return 'recorded';
}
"""


def tab_js(script: str) -> dict:
    return json.loads(ui.js(_TAB + script))


def card_order(ids: list[str]) -> list[str]:
    return [entry for entry in ui.margin_tab_order() if entry in ids]


def expanded_thread_ids() -> list[str]:
    return tab_js("""
        var order = [];
        var children = stackOf().getComponents();
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (String(child.getClass().getSimpleName()) == 'ThreadCard' && child.getPanel() != null)
                order.push(String(child.getThread().getId()));
        }
        String(JSON.stringify(order))
    """)


def start_draft_in(key: str) -> str:
    """Opens the composer (the real "Reply…" link when the card shows one), types the draft and places the caret."""
    tab_js(f"""
        var link = find(target({json.dumps(key)}), function (c) {{
            return c instanceof com.intellij.ui.components.ActionLink && c.getText() == 'Reply…';
        }});
        if (link != null) link.doClick();
        String(JSON.stringify(link != null))
    """)
    # Only a laid-out, showing reply box can take the focus.
    time.sleep(1.0)
    return tab_js(f"""
        var host = target({json.dumps(key)});
        var area = replyAreaOf(host);
        area.setText({json.dumps(DRAFT_TEXT)});
        var editor = area.getEditor();
        if (editor == null) throw 'the reply box has no editor yet';
        asUser(function () {{ editor.getCaretModel().moveToOffset({DRAFT_CARET}); }});
        String(JSON.stringify({{ focus: rememberFocus(editor.getContentComponent()) }}))
    """)["focus"]


def focus_outside_cards() -> None:
    tab_js("String(JSON.stringify({ focus: rememberFocus(fieldOf(marginPanel(), 'root')) }))")


def scroll_to(view_top_js: str) -> int:
    """Sets the tab's scroll position from a JS expression over `h` (view height) and `top(key)` / `bottom(key)`."""
    return tab_js(f"""
        var viewport = viewportOf();
        var h = viewport.getExtentSize().height;
        function top(key) {{ return topIn(target(key)); }}
        function bottom(key) {{ return topIn(target(key)) + target(key).getHeight(); }}
        viewport.setViewPosition(new java.awt.Point(0, Math.max(0, Math.round({view_top_js}))));
        String(JSON.stringify(viewport.getViewPosition().y))
    """)


def on_screen(key: str, grower: str) -> dict:
    """Where `key` sits in the view, whether it still holds the focus and its draft, and the height of `grower`."""
    return tab_js(f"""
        var host = target({json.dumps(key)});
        var viewport = viewportOf();
        var area = replyAreaOf(host);
        var editor = area == null ? null : area.getEditor();
        var focused = windowOf(host).getMostRecentFocusOwner();
        String(JSON.stringify({{
            offset: topIn(host) - viewport.getViewPosition().y,
            viewTop: viewport.getViewPosition().y,
            focused: focused != null && javax.swing.SwingUtilities.isDescendingFrom(focused, host),
            text: area == null ? null : String(area.getText()),
            caret: editor == null ? null : read(function () {{ return editor.getCaretModel().getOffset(); }}) | 0,
            grower: target({json.dumps(grower)}).getHeight()
        }}))
    """)


class Scenarios:
    def __init__(self, feature_dir: Path) -> None:
        self.feature_dir = feature_dir
        self.failures: list[str] = []
        self.upper = self.middle = self.lower = ""

    def check(self, name: str, passed: bool, detail: object = "") -> None:
        print(f"{'PASS' if passed else 'FAIL'}  {name}{f'  ({detail})' if not passed else ''}")
        if not passed:
            self.failures.append(name)

    def shot(self, name: str) -> None:
        SHOTS.mkdir(parents=True, exist_ok=True)
        ui.screenshot_frame(SHOTS / f"{name}.png")

    def three_tall_threads(self) -> None:
        created = [ui.agent_starts_project_thread(tall_body(f"Thread {name}", paragraphs))
                   for name, paragraphs in (("one", 30), ("two", 12), ("three", 30))]
        ui.open_margin_tab(created[0])
        time.sleep(SETTLE_S)
        # Whatever the tab's sort order, the message goes into the card above the one being looked at.
        self.upper, self.middle, self.lower = card_order(created)
        for thread_id in (self.upper, self.middle):
            ui.open_margin_tab(thread_id)
        time.sleep(SETTLE_S)
        expanded = expanded_thread_ids()
        self.check("the threads above and in view are expanded",
                   self.upper in expanded and self.middle in expanded, expanded)

    def message_above(self, watched: str, grower: str, shot: str) -> tuple[dict, dict]:
        before = on_screen(watched, grower)
        self.shot(f"{shot}-before")
        ui.agent_replies(grower, tall_body("Agent update", 10))
        time.sleep(SETTLE_S)
        after = on_screen(watched, grower)
        self.shot(f"{shot}-after")
        self.check(f"{shot}: the message made the thread above taller", after["grower"] > before["grower"],
                   (before["grower"], after["grower"]))
        return before, after

    def reading_keeps_the_topmost_card(self) -> None:
        focus_outside_cards()
        # The card in view straddles the view top; the growing thread is entirely above it.
        scroll_to(f"top({json.dumps(self.middle)}) + 10")
        time.sleep(SETTLE_S)
        before, after = self.message_above(self.middle, self.upper, "1-reading")
        self.check("reading without a draft, the topmost visible card stays where it was on screen",
                   abs(after["offset"] - before["offset"]) <= TOLERANCE_PX, (before, after))

    def draft_in_a_thread_stays(self) -> None:
        focus = start_draft_in(self.middle)
        time.sleep(SETTLE_S)
        # The thread above straddles the view top, so only the card being written in can serve as the anchor.
        scroll_to(f"Math.max(top({json.dumps(self.upper)}) + 20, top({json.dumps(self.middle)}) - h / 2)")
        time.sleep(SETTLE_S)
        before, after = self.message_above(self.middle, self.upper, "2-draft-in-thread")
        print(f"      focus {focus}")
        self.check("a reply draft stays at the same place on screen when the thread above grows",
                   abs(after["offset"] - before["offset"]) <= TOLERANCE_PX, (before, after))
        self.check("the reply box keeps the focus", after["focused"], after)
        self.check("the reply box keeps the draft and the caret",
                   after["text"] == DRAFT_TEXT and after["caret"] == DRAFT_CARET, after)

    def draft_in_a_new_project_thread_stays(self) -> None:
        ui.user_starts_new_project_thread()
        time.sleep(SETTLE_S)
        focus = start_draft_in("draft")
        time.sleep(SETTLE_S)
        order = [entry for entry in ui.margin_tab_order() if entry in (self.upper, self.middle, self.lower, "draft")]
        at = order.index("draft")
        # The expanded thread right above the draft; with new threads on top nothing is above it, so the one below.
        grower = order[at - 1] if at > 0 else order[at + 1]
        if grower == self.lower:
            ui.open_margin_tab(grower)
            time.sleep(SETTLE_S)
        scroll_to("top('draft') - h / 2")
        time.sleep(SETTLE_S)
        before, after = self.message_above("draft", grower, "3-draft-new-thread")
        print(f"      focus {focus}, draft at {at} of {order}")
        self.check("a new project thread's draft stays at the same place on screen when another thread grows",
                   abs(after["offset"] - before["offset"]) <= TOLERANCE_PX, (before, after))
        self.check("the new thread's reply box keeps the focus, the draft and the caret",
                   after["focused"] and after["text"] == DRAFT_TEXT and after["caret"] == DRAFT_CARET, after)

    def guarded(self, scenario) -> None:
        try:
            scenario()
        except Exception as error:  # a missing feature on main shows up here, not as a crash of the whole run
            self.check(scenario.__name__, False, f"{type(error).__name__}: {error}")
            traceback.print_exc(limit=1)

    def run(self) -> int:
        sandbox.stop()
        sandbox.start(self.feature_dir, fresh=True)
        for scenario in (
            self.three_tall_threads,
            self.reading_keeps_the_topmost_card,
            self.draft_in_a_thread_stays,
            self.draft_in_a_new_project_thread_stays,
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
