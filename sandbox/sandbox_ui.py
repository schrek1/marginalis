"""Marginalis-specific steps in the sandbox IDE: the user's side through the real Swing components, the agent's
side through the plugin's REST API.

UI steps never move the OS mouse or keyboard (Ondra works in his own IDE meanwhile): buttons are pressed with
`doClick()` / `ActionButton.click()` and text is set on the component, all on the EDT. Reads that have no UI
(store state) go through the plugin's own classes, loaded with the plugin's class loader.

Every function here reads one thing or performs one user step; scenarios compose them.
"""
from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request

import robot
import sandbox

PROJECT_NAME = "project"
AGENT = {"author_name": "Claude", "author_id": "claude-sandbox"}
HEADER_ATTEMPTS = 60  # one per second while the tool window header is still being built

_PRELUDE = """
var pluginLoader = com.intellij.ide.plugins.PluginManagerCore
    .getPlugin(com.intellij.openapi.extensions.PluginId.getId('dev.marginalis.plugin')).getPluginClassLoader();
var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
function service(name) { return project.getService(pluginLoader.loadClass(name)); }
var store = service('dev.marginalis.plugin.store.MarginalisStore');
function threadOf(panel) {
    var field = panel.getClass().getDeclaredField('thread');
    field.setAccessible(true);
    return field.get(panel);
}
"""


def js(script: str, component_id: str | None = None) -> str:
    # The robot runs EDT scripts without the write-intent lock an ordinary EDT event holds; 2025.2 then refuses
    # model reads (2026.2 grants the lock implicitly), so the script takes it as plugin code on the EDT has it.
    locked = f"""String(com.intellij.openapi.application.WriteIntentReadAction.compute(
        new com.intellij.openapi.util.ThrowableComputable({{ compute: function () {{ return eval({json.dumps(script)}); }} }})))"""
    return robot.call_js(_PRELUDE + locked, edt=True, component_id=component_id)


# --- agent side (REST) ---------------------------------------------------------------------------------------

def api_base() -> str:
    port = sandbox.marginalis_port()
    if port is None:
        raise RuntimeError("sandbox is not running; start it with sandbox.py start")
    return f"http://127.0.0.1:{port}/api/marginalis"


def api_post(route: str, payload: dict) -> dict:
    request = urllib.request.Request(f"{api_base()}/{route}", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def agent_starts_project_thread(body: str) -> str:
    return api_post("comment_add", {"body": body, "project": PROJECT_NAME, **AGENT})["thread_id"]


def agent_adds_line_thread(file: str, line: int, body: str, order: int | None = None) -> str:
    """`line` is 1-based, as agents send it; the anchor text is read from the sandbox project's file."""
    anchor = (sandbox.PROJECT / file).read_text(encoding="utf-8").splitlines()[line - 1]
    payload = {"body": body, "file": file, "line": line, "anchor_text": anchor, "project": PROJECT_NAME, **AGENT}
    if order is not None:
        payload["order"] = order
    return api_post("comment_add", payload)["thread_id"]


def agent_adds_step(file: str, line: int, body: str, order: int, walkthrough: str) -> str:
    anchor = (sandbox.PROJECT / file).read_text(encoding="utf-8").splitlines()[line - 1]
    return api_post("comment_add", {"body": body, "file": file, "line": line, "anchor_text": anchor,
                                    "order": order, "walkthrough": walkthrough, "project": PROJECT_NAME, **AGENT})["thread_id"]


def resolve_thread(thread_id: str) -> None:
    api_post("comment_resolve", {"thread_id": thread_id, **AGENT})


def agent_waits(timeout_s: int) -> dict:
    """Blocks like an agent's `comment_wait` from now on; returns the wake or the timeout."""
    since = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
    query = urllib.parse.urlencode({"project": PROJECT_NAME, "since": since, "timeout": timeout_s, **AGENT})
    with urllib.request.urlopen(f"{api_base()}/comment_wait?{query}", timeout=timeout_s + 30) as response:
        return json.loads(response.read())


# --- user side (UI) ------------------------------------------------------------------------------------------

def show_tool_window() -> None:
    js("""
        var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
        window.show();
        if (window.getComponent().getWidth() < 300) window.stretchWidth(450 - window.getComponent().getWidth());
        String(true)
    """)


def press_tool_window_action(text: str) -> None:
    """Presses a button in the Marginalis tool window's header. The new UI paints those only on hover, so they are
    absent from the robot's hierarchy, and both `click()` and `tryToExecute` silently skip them. The button's own
    action runs instead, with the button's data context."""
    escaped = json.dumps(text)
    # Right after a start the header is built a while after the tool window shows; with several sandboxes busy at
    # once that took longer than 15 s.
    for attempt in range(HEADER_ATTEMPTS):
        try:
            _press_tool_window_action(escaped)
            return
        except robot.RobotError as error:
            if "no tool window button" not in str(error) or attempt == HEADER_ATTEMPTS - 1:
                raise
            time.sleep(1)


def _press_tool_window_action(escaped: str) -> None:
    js(_FIND + f"""
        var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
        var root = window.getComponent();
        while (root.getParent() != null && root.getClass().getName().indexOf('InternalDecorator') < 0) {{
            root = root.getParent();
        }}
        var button = find(root, function (c) {{
            return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                && c.getPresentation().getText() == {escaped};
        }});
        if (button == null) throw 'no tool window button ' + {escaped};
        var action = button.getAction();
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
            action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
            'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
        action.actionPerformed(event);
        String(true)
    """)


def live_by_default() -> bool:
    return js("String(store.getLiveByDefault())") == "true"


def is_live(thread_id: str) -> bool:
    return js(f"String(store.isLive(store.getThreads().byId('{thread_id}')))") == "true"


def message_bodies(thread_id: str) -> list[str]:
    raw = js(f"""
        var messages = store.getThreads().byId('{thread_id}').getMessages();
        var bodies = [];
        for (var i = 0; i < messages.size(); i++) bodies.push(String(messages.get(i).getBody()));
        String(JSON.stringify(bodies))
    """)
    return json.loads(raw)


def thread_panel(thread_id: str) -> str:
    for panel in robot.find_all("//div[@javaclass='dev.marginalis.plugin.ui.ThreadPanel']"):
        if js("String(threadOf(component).getId())", component_id=panel["id"]) == thread_id:
            return panel["id"]
    raise RuntimeError(f"no panel shows thread {thread_id}")


_EDITORS = """
function projectFile(path) { return project.getBaseDir().findFileByRelativePath(path); }
function textEditorOf(path) {
    var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(projectFile(path));
    var editors = com.intellij.openapi.editor.EditorFactory.getInstance().getEditors(document, project);
    for (var i = 0; i < editors.length; i++) {
        if (editors[i].getEditorKind() == com.intellij.openapi.editor.EditorKind.MAIN_EDITOR) return editors[i];
    }
    return null;
}
// A review-style diff editor: its own document (a revision's copy), so it is never the file's document.
// Several diffs may show the same text; only the one on screen counts.
function diffEditorShowing(text) {
    var editors = com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors();
    for (var i = 0; i < editors.length; i++) {
        if (editors[i].getEditorKind() == com.intellij.openapi.editor.EditorKind.DIFF
            && editors[i].getDocument().getText() == text
            && editors[i].getComponent().isShowing()) return editors[i];
    }
    return null;
}
function threadById(id) { return store.getThreads().byId(id); }
function inlays() { return pluginLoader.loadClass('dev.marginalis.plugin.ui.ThreadInlayManager').getField('INSTANCE').get(null); }
"""


def open_in_editor(file: str) -> None:
    js(_EDITORS + f"""
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(projectFile('{file}'), true);
        String(true)
    """)


def open_review_diff(file: str, before: str, after: str, added: bool = False) -> None:
    """A diff shaped like a PR review: the right side is a copy of `after` that only names `file` as its
    highlight file. `added` shows a file the change adds (no left side, a one-sided viewer)."""
    js(_EDITORS + f"""
        var factory = com.intellij.diff.DiffContentFactory.getInstance();
        var left = {('factory.createEmpty()' if added else f'factory.create(project, {json.dumps(before)}, projectFile({json.dumps(file)}))')};
        var right = factory.create(project, {json.dumps(after)}, projectFile({json.dumps(file)}));
        var request = new com.intellij.diff.requests.SimpleDiffRequest('Sandbox review diff', left, right, 'base', 'PR');
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        String(true)
    """)


def open_local_diff(file: str, other: str) -> None:
    """A diff whose right side is the file's own document (a checked-out branch), against `other` on the left."""
    js(_EDITORS + f"""
        var factory = com.intellij.diff.DiffContentFactory.getInstance();
        var left = factory.create(project, {json.dumps(other)}, projectFile({json.dumps(file)}));
        var right = factory.create(project, projectFile({json.dumps(file)}));
        var request = new com.intellij.diff.requests.SimpleDiffRequest('Sandbox local diff', left, right, 'other', 'local');
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        String(true)
    """)


def close_all_editors() -> None:
    js("""
        var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
        manager.closeAllFiles();
        String(true)
    """)


def diff_icon_lines(after: str) -> list[int]:
    """0-based lines of the thread gutter icons in the diff editor showing `after`."""
    raw = js(_EDITORS + f"""
        var editor = diffEditorShowing({json.dumps(after)});
        if (editor == null) throw 'no diff editor shows that text';
        var lines = [];
        var highlighters = editor.getMarkupModel().getAllHighlighters();
        for (var i = 0; i < highlighters.length; i++) {{
            var renderer = highlighters[i].getGutterIconRenderer();
            if (renderer != null && renderer.getClass().getSimpleName() == 'ThreadGutterIconRenderer') {{
                lines.push(editor.getDocument().getLineNumber(highlighters[i].getStartOffset()));
            }}
        }}
        lines.sort(function (a, b) {{ return a - b; }});
        String(JSON.stringify(lines))
    """)
    return json.loads(raw)


def open_thread_in_diff(thread_id: str, after: str) -> None:
    js(_EDITORS + f"""
        inlays().open(project, diffEditorShowing({json.dumps(after)}), threadById('{thread_id}'), null);
        String(true)
    """)


def open_thread_in_editor(thread_id: str, file: str) -> None:
    js(_EDITORS + f"""
        inlays().open(project, textEditorOf('{file}'), threadById('{thread_id}'), null);
        String(true)
    """)


def scroll_editor_to(file: str, top_line: int) -> None:
    """Puts 0-based `top_line` at the top of the file's editor, without animation."""
    js(_EDITORS + f"""
        var editor = textEditorOf('{file}');
        var scrolling = editor.getScrollingModel();
        scrolling.disableAnimation();
        scrolling.scrollVertically(editor.logicalPositionToXY(new com.intellij.openapi.editor.LogicalPosition({top_line}, 0)).y);
        scrolling.enableAnimation();
        String(true)
    """)


def line_under_point(file: str, x: int, y: int) -> int:
    """0-based line shown at (x, y) of the editor's viewport."""
    return int(js(_EDITORS + f"""
        var editor = textEditorOf('{file}');
        var area = editor.getScrollingModel().getVisibleArea();
        String(editor.xyToLogicalPosition(new java.awt.Point(area.x + {x}, area.y + {y})).line)
    """))


def ctrl_wheel_zoom(file: str, x: int, y: int, notches: int = -2) -> None:
    """Ctrl+mouse wheel over (x, y) of the editor's viewport, as one dispatched event (negative = zoom in)."""
    js(_EDITORS + f"""
        com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance().setWheelFontChangeEnabled(true);
        var pane = textEditorOf('{file}').getScrollPane();
        var point = javax.swing.SwingUtilities.convertPoint(pane.getViewport(), {x}, {y}, pane);
        pane.dispatchEvent(new java.awt.event.MouseWheelEvent(pane, java.awt.event.MouseEvent.MOUSE_WHEEL,
            java.lang.System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK, point.x, point.y, 0, false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, {notches}));
        String(true)
    """)


def editor_font_size(file: str) -> float:
    return float(js(_EDITORS + f"String(textEditorOf('{file}').getColorsScheme().getEditorFontSize2D())"))


def message_panes(thread_id: str) -> list[dict]:
    """Every rendered message pane in the thread's panel: what its HTML holds and how it sits in a scroll pane."""
    raw = js(f"""
        var panes = [];
        function collect(c) {{
            if (c instanceof javax.swing.JEditorPane) {{
                var html = String(c.getText());
                var viewport = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport, c);
                var scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane, c);
                panes.push({{
                    table: html.indexOf('<table') >= 0,
                    strike: html.indexOf('<s>') >= 0,
                    width: c.getWidth(),
                    viewportWidth: viewport == null ? -1 : viewport.getExtentSize().width,
                    horizontalPolicy: scroll == null ? -1 : scroll.getHorizontalScrollBarPolicy(),
                    horizontalBarVisible: scroll != null && scroll.getHorizontalScrollBar().isVisible()
                }});
            }}
            var children = c.getComponents ? c.getComponents() : [];
            for (var i = 0; i < children.length; i++) collect(children[i]);
        }}
        collect(component);
        String(JSON.stringify(panes))
    """, component_id=thread_panel(thread_id))
    return json.loads(raw)


def word_boxes(thread_id: str, words: list[str]) -> dict:
    """Where each word's first letter is laid out in its rendered message pane: {word: {top, bottom, paneTop}}
    in pane pixels (`paneTop` = the pane's top inset), plus `html` of the pane holding the first word."""
    raw = js(f"""
        var words = {json.dumps(words)};
        var result = {{}};
        function collect(c) {{
            if (c instanceof javax.swing.JEditorPane) {{
                var document = c.getDocument();
                var text = String(document.getText(0, document.getLength()));
                for (var i = 0; i < words.length; i++) {{
                    var at = text.indexOf(words[i]);
                    if (at < 0 || result[words[i]]) continue;
                    var box = c.modelToView2D(at);
                    result[words[i]] = {{ top: box.getY(), bottom: box.getY() + box.getHeight(), paneTop: c.getInsets().top }};
                    if (i == 0) result.html = String(c.getText());
                }}
            }}
            var children = c.getComponents ? c.getComponents() : [];
            for (var j = 0; j < children.length; j++) collect(children[j]);
        }}
        collect(component);
        String(JSON.stringify(result))
    """, component_id=thread_panel(thread_id))
    return json.loads(raw)


def screenshot_thread(thread_id: str, target) -> None:
    robot.screenshot(target, thread_panel(thread_id))


def press_panel_action(thread_id: str, text: str) -> None:
    """Runs a button of the thread header (e.g. "Next Step") with that button's own data context."""
    escaped = json.dumps(text)
    js(_FIND + f"""
        var button = find(component, function (c) {{
            return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                && c.getPresentation().getText() == {escaped};
        }});
        if (button == null) throw 'no panel button ' + {escaped};
        var action = button.getAction();
        action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
            action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
            'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null));
        String(true)
    """, component_id=thread_panel(thread_id))


def selected_editor_kind() -> str:
    """What the editor area shows in front: `DIFF` for a diff tab, `TEXT:<file>` for a file's own tab."""
    return js("""
        var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
        if (selected == null) String('NONE');
        else if (selected instanceof com.intellij.openapi.fileEditor.TextEditor) String('TEXT:' + selected.getFile().getName());
        else String(selected.getClass().getSimpleName().indexOf('Diff') >= 0 ? 'DIFF' : selected.getClass().getSimpleName())
    """)


def thread_ids_open_in_diff(after: str) -> list[str]:
    raw = js(_EDITORS + _FIND + f"""
        var editor = diffEditorShowing({json.dumps(after)});
        var ids = [];
        function collect(c) {{
            if (c.getClass().getName() == 'dev.marginalis.plugin.ui.ThreadPanel') ids.push(String(threadOf(c).getId()));
            var children = c.getComponents ? c.getComponents() : [];
            for (var i = 0; i < children.length; i++) collect(children[i]);
        }}
        collect(editor.getContentComponent());
        String(JSON.stringify(ids))
    """)
    return json.loads(raw)


_FIND = """
function find(root, test) {
    if (test(root)) return root;
    var children = root.getComponents ? root.getComponents() : [];
    for (var i = 0; i < children.length; i++) {
        var hit = find(children[i], test);
        if (hit != null) return hit;
    }
    return null;
}
"""


_TREE = """
function marginalisTree() {
    var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
    var stack = [window.getComponent()];
    while (stack.length > 0) {
        var c = stack.pop();
        if (c instanceof javax.swing.JTree) return c;
        var children = c.getComponents ? c.getComponents() : [];
        for (var i = 0; i < children.length; i++) stack.push(children[i]);
    }
    throw 'no tree in the Marginalis tool window';
}
function threadOfNode(node) {
    var data = node == null ? null : node.getUserObject();
    if (data == null || data.getClass().getSimpleName() != 'ThreadNode') return null;
    return data.getThread();
}
function sectionTitle(node) { return String(node.getUserObject().getTitle()); }
"""


def refresh_project_files() -> None:
    """Makes files written from outside visible to the IDE. A synchronous refresh runs off the EDT: on it, it would
    need a write-intent lock."""
    robot.call_js(_PRELUDE + """
        com.intellij.openapi.vfs.VfsUtil['markDirtyAndRefresh(boolean,boolean,boolean,com.intellij.openapi.vfs.VirtualFile[])'](
            false, true, true, [project.getBaseDir()]);
        String(true)
    """, edt=False)


def sections_state() -> dict:
    """Per section title: whether its node is expanded, whether everything under it is (`full`), and whether
    nothing below the section node is (`folded`). JTree reports a node under a collapsed parent as collapsed
    whatever its own state, so check `folded` with the section itself open (`open_section_only`)."""
    raw = js(_TREE + """
        var tree = marginalisTree();
        var root = tree.getModel().getRoot();
        var result = {};
        for (var i = 0; i < root.getChildCount(); i++) {
            var section = root.getChildAt(i);
            var full = true;
            var folded = true;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var node = nodes.nextElement();
                if (node.isLeaf()) continue;
                var open = tree.isExpanded(new javax.swing.tree.TreePath(node.getPath()));
                if (!open) full = false;
                if (open && node != section) folded = false;
            }
            result[sectionTitle(section)] = {
                expanded: tree.isExpanded(new javax.swing.tree.TreePath(section.getPath())),
                full: full,
                folded: folded
            };
        }
        String(JSON.stringify(result))
    """)
    return json.loads(raw)


def open_section_only(section_prefix: str) -> None:
    """Opens just the section node, as a click on its arrow would."""
    js(_TREE + f"""
        var tree = marginalisTree();
        var root = tree.getModel().getRoot();
        for (var i = 0; i < root.getChildCount(); i++) {{
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf({json.dumps(section_prefix)}) == 0) {{
                tree.expandPath(new javax.swing.tree.TreePath(section.getPath()));
                break;
            }}
        }}
        String(true)
    """)


def _dir_node_js(section_prefix: str, dir_name: str) -> str:
    return _TREE + f"""
        var tree = marginalisTree();
        var root = tree.getModel().getRoot();
        var dir = null;
        for (var i = 0; i < root.getChildCount() && dir == null; i++) {{
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf({json.dumps(section_prefix)}) != 0) continue;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {{
                var node = nodes.nextElement();
                var data = node.getUserObject();
                if (data.getClass().getSimpleName() == 'DirNode' && String(data.getName()) == {json.dumps(dir_name)}) {{ dir = node; break; }}
            }}
        }}
        if (dir == null) throw 'no folder ' + {json.dumps(dir_name)};
        var dirPath = new javax.swing.tree.TreePath(dir.getPath());
    """


def fold_dir(section_prefix: str, dir_name: str) -> None:
    """Folds a folder node as a click on its arrow would."""
    js(_dir_node_js(section_prefix, dir_name) + "tree.collapsePath(dirPath); String(true)")


def dir_expanded(section_prefix: str, dir_name: str) -> bool:
    return js(_dir_node_js(section_prefix, dir_name) + "String(tree.isExpanded(dirPath))") == "true"


def poke_a_thread() -> None:
    """Reports a change on some thread, as an agent reply or a step marked read would. The tool window rebuilds its
    tree on any thread change, so this makes that rebuild happen on cue instead of depending on focus."""
    js("""
        var threads = store.getThreads().all();
        store.getThreads().notifyChanged(threads.get(threads.size() - 1));
        String(true)
    """)


def tree_steps(section_prefix: str = "Guided") -> list[int]:
    """Step numbers of the thread nodes of the first section whose title starts with `section_prefix`, in tree
    (top-to-bottom) order."""
    raw = js(_TREE + f"""
        var root = marginalisTree().getModel().getRoot();
        var orders = [];
        for (var i = 0; i < root.getChildCount(); i++) {{
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf({json.dumps(section_prefix)}) != 0) continue;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {{
                var thread = threadOfNode(nodes.nextElement());
                if (thread != null) orders.push(thread.getOrder() == null ? -1 : thread.getOrder().intValue());
            }}
            break;
        }}
        String(JSON.stringify(orders))
    """)
    return json.loads(raw)


def step_prefixes() -> dict:
    """Per Guided section title: the prefix each step shows in the tree, e.g. `(1/5)`, in tree order."""
    raw = js(_TREE + """
        var root = marginalisTree().getModel().getRoot();
        var result = {};
        for (var i = 0; i < root.getChildCount(); i++) {
            var section = root.getChildAt(i);
            var title = sectionTitle(section);
            if (title.indexOf('Guided') != 0) continue;
            var prefixes = [];
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var data = nodes.nextElement().getUserObject();
                if (data.getClass().getSimpleName() == 'ThreadNode') prefixes.push(String(data.getWalkthroughPrefix()));
            }
            result[title] = prefixes;
        }
        String(JSON.stringify(result))
    """)
    return json.loads(raw)


def selected_step() -> dict | None:
    """The selected tree node: its step number (or null) and section title; None when nothing is selected."""
    raw = js(_TREE + """
        var tree = marginalisTree();
        var node = tree.getLastSelectedPathComponent();
        if (node == null) String('null');
        else {
            var thread = threadOfNode(node);
            var section = node.getPath().length > 1 ? sectionTitle(node.getPath()[1]) : '';
            String(JSON.stringify({
                order: thread == null || thread.getOrder() == null ? null : thread.getOrder().intValue(),
                thread: thread == null ? null : String(thread.getId()),
                section: section
            }));
        }
    """)
    return json.loads(raw)


def selected_node_name() -> str | None:
    """The selected node as shown: a folder's or file's name, a section's title, `project`, or `step N`."""
    raw = js(_TREE + """
        var node = marginalisTree().getLastSelectedPathComponent();
        if (node == null) String('null');
        else {
            var data = node.getUserObject();
            var kind = data.getClass().getSimpleName();
            if (kind == 'Section') String(JSON.stringify(String(data.getTitle())));
            else if (kind == 'DirNode' || kind == 'FileNode') String(JSON.stringify(String(data.getName())));
            else if (kind == 'ProjectNode') String(JSON.stringify('project'));
            else String(JSON.stringify('step ' + data.getThread().getOrder()));
        }
    """)
    return json.loads(raw)


def select_dir(section_prefix: str, dir_name: str) -> None:
    js(_dir_node_js(section_prefix, dir_name) + "tree.setSelectionPath(dirPath); String(true)")


def select_step(order: int, section_prefix: str = "Guided") -> None:
    """Selects the step's node in the tree, as a click on it would (no editor navigation)."""
    js(_TREE + f"""
        var tree = marginalisTree();
        var root = tree.getModel().getRoot();
        var found = null;
        for (var i = 0; i < root.getChildCount() && found == null; i++) {{
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf({json.dumps(section_prefix)}) != 0) continue;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {{
                var node = nodes.nextElement();
                var thread = threadOfNode(node);
                if (thread != null && thread.getOrder() != null && thread.getOrder().intValue() == {order}) {{ found = node; break; }}
            }}
        }}
        if (found == null) throw 'no step {order} in a section starting with ' + {json.dumps(section_prefix)};
        var path = new javax.swing.tree.TreePath(found.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        String(true)
    """)


def screenshot_tool_window(target) -> None:
    xpath = "//div[@class='MarginalisToolWindowPanel' or @javaclass='dev.marginalis.plugin.ui.toolwindow.MarginalisToolWindowPanel']"
    # The robot's hierarchy misses the panel for a moment while the tool window relays out.
    for attempt in range(5):
        found = robot.find_all(xpath)
        if len(found) == 1:
            robot.screenshot(target, found[0]["id"])
            return
        time.sleep(1)
    robot.find_one(xpath)


def toggle_thread_live(thread_id: str) -> None:
    """Presses the thread header's own ⚡ ("Live: …" when on, "Go Live: …" when off)."""
    js(_FIND + """
        var button = find(component, function (c) {
            if (!(c instanceof com.intellij.openapi.actionSystem.impl.ActionButton)) return false;
            var text = c.getPresentation().getText();
            return text != null && (text.startsWith('Live:') || text.startsWith('Go Live:'));
        });
        button.click();
        String(true)
    """, component_id=thread_panel(thread_id))


def user_replies(thread_id: str, body: str) -> None:
    """Opens the composer with the real "Reply…" link, types the body into it and presses the send button."""
    escaped = json.dumps(body)
    js(_FIND + f"""
        var link = find(component, function (c) {{
            return c instanceof com.intellij.ui.components.ActionLink && c.getText() == 'Reply…';
        }});
        link.doClick();
        var area = find(component, function (c) {{ return c instanceof com.intellij.ui.EditorTextField; }});
        area.setText({escaped});
        var send = find(component, function (c) {{ return c instanceof com.intellij.ui.components.JBOptionButton; }});
        send.doClick();
        String(true)
    """, component_id=thread_panel(thread_id))


# --- Margin (project) tab ------------------------------------------------------------------------------------

_MARGIN = """
function marginPanel() {
    var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
    var editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getAllEditors(marginFile);
    if (editors.length == 0) throw 'the Margin tab is not open';
    var field = editors[0].getClass().getDeclaredField('content');
    field.setAccessible(true);
    return field.get(editors[0]);
}
function fieldOf(object, name) {
    var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
}
function projectTab() { return pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.ProjectTab').getField('INSTANCE').get(null); }
"""


def agent_adds_project_step(body: str, order: int, walkthrough: str) -> str:
    return api_post("comment_add", {"body": body, "order": order, "walkthrough": walkthrough,
                                    "project": PROJECT_NAME, **AGENT})["thread_id"]


def agent_replies(thread_id: str, body: str) -> None:
    api_post("comment_reply", {"thread_id": thread_id, "body": body, **AGENT})


def open_margin_tab(thread_id: str) -> None:
    js(_MARGIN + f"projectTab().reveal(project, store.getThreads().byId('{thread_id}'), null); String(true)")


def user_starts_new_project_thread() -> None:
    """The "+ New project thread" link: opens an empty draft in the Margin tab."""
    js(_MARGIN + "projectTab().draftNew(project); String(true)")


def margin_tab_order() -> list[str]:
    """What the Margin tab lists, top to bottom: thread ids, 'conversation:<name>', 'draft', or a component kind."""
    raw = js(_MARGIN + """
        var stack = fieldOf(marginPanel(), 'stack');
        var draft = fieldOf(marginPanel(), 'draftShown');
        var children = stack.getComponents();
        var order = [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            var kind = String(child.getClass().getSimpleName());
            if (kind == 'ThreadCard') order.push(String(child.getThread().getId()));
            else if (kind == 'ConversationCard') order.push('conversation');
            else if (draft != null && child === fieldOf(draft, 'panel')) order.push('draft');
            else order.push(kind);
        }
        String(JSON.stringify(order))
    """)
    return json.loads(raw)


def margin_pill_text() -> str:
    return js(_MARGIN + "String(fieldOf(marginPanel(), 'pill').getText())")


def set_margin_list_mode(hold_still: bool) -> None:
    mode = "HOLD_STILL" if hold_still else "LIVE"
    js(_MARGIN + f"""
        var settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(pluginLoader.loadClass('dev.marginalis.plugin.settings.MarginalisSettings'));
        settings.getState().setProjectTabList('{mode}');
        projectTab().applyPrefs();
        String(true)
    """)


def choose_margin_sort(label: str) -> None:
    """Picks an entry of the Margin tab's Sort menu, through the toolbar button's own action group."""
    escaped = json.dumps(label)
    js(_MARGIN + _FIND + f"""
        var root = fieldOf(marginPanel(), 'root');
        var button = find(root, function (c) {{
            return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                && String(c.getAction().getClass().getSimpleName()) == 'ProjectTabSortMenu';
        }});
        if (button == null) throw 'no Sort button in the Margin tab';
        var group = button.getAction();
        var context = com.intellij.ide.DataManager.getInstance().getDataContext(button);
        var children = group.getChildActionsOrStubs();
        var picked = null;
        for (var i = 0; i < children.length; i++) {{
            if (children[i].getTemplatePresentation().getText() == {escaped}) picked = children[i];
        }}
        if (picked == null) throw 'no Sort entry ' + {escaped};
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
            picked, context, picked.getTemplatePresentation().clone(),
            'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
        picked.setSelected(event, true);
        String(true)
    """)


def margin_sort() -> str:
    return js(_MARGIN + "String(service('dev.marginalis.plugin.ui.tab.ProjectTabSort').getOrder().getStored())")


def save_project_state() -> None:
    """Forced save of the project's workspace.xml, off the EDT (see the skill: non-forced saves skip components)."""
    robot.call_js(_PRELUDE + """
        com.intellij.configurationStore.StoreUtil.saveSettings(project, true);
        String(true)
    """, edt=False)


def screenshot_frame(target) -> None:
    robot.screenshot(target, robot.find_one("//div[@class='IdeFrameImpl']")["id"])


# --- an open thread panel as the user sees it ---------------------------------------------------------------

def panel_view(thread_id: str) -> dict:
    """What the thread's open panel shows: the plain text of each rendered message, and whether its bounds keep
    up with what it would need (an inlay that does not grow hides the last message below its edge)."""
    raw = js("""
        var texts = [];
        function collect(c) {
            if (c instanceof javax.swing.JEditorPane && !c.isEditable()) {
                var doc = c.getDocument();
                texts.push(String(doc.getText(0, doc.getLength())).trim());
            }
            var children = c.getComponents ? c.getComponents() : [];
            for (var i = 0; i < children.length; i++) collect(children[i]);
        }
        collect(component);
        String(JSON.stringify({
            texts: texts,
            height: component.getHeight(),
            preferredHeight: component.getPreferredSize().height,
            valid: component.isValid(),
            showing: component.isShowing()
        }))
    """, component_id=thread_panel(thread_id))
    return json.loads(raw)


def reload_plugin() -> dict:
    """Unloads Marginalis and loads it again in the running IDE, as an update installed without a restart does."""
    raw = robot.call_js("""
        var id = com.intellij.openapi.extensions.PluginId.getId('dev.marginalis.plugin');
        var before = com.intellij.ide.plugins.PluginManagerCore.getPlugin(id);
        var oldLoader = before.getPluginClassLoader();
        var dynamic = com.intellij.ide.plugins.DynamicPlugins.INSTANCE;
        var unloaded = dynamic.unloadPlugin(before);
        var loaded = dynamic.loadPlugin(before);
        var after = com.intellij.ide.plugins.PluginManagerCore.getPlugin(id);
        String(JSON.stringify({unloaded: unloaded, loaded: loaded,
            newClassLoader: after.getPluginClassLoader() !== oldLoader}))
    """, edt=True)
    return json.loads(raw)


# --- Guided layout (fork MARG-10) ----------------------------------------------------------------------------

def choose_guided_layout(label: str) -> None:
    """Picks an entry of the tool window header's Guided Layout menu through the button's own action group (the
    header paints its buttons only on hover, see press_tool_window_action)."""
    escaped = json.dumps(label)
    for attempt in range(HEADER_ATTEMPTS):
        try:
            js(_FIND + f"""
                var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
                var root = window.getComponent();
                while (root.getParent() != null && root.getClass().getName().indexOf('InternalDecorator') < 0) root = root.getParent();
                var button = find(root, function (c) {{
                    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                        && String(c.getAction().getClass().getSimpleName()) == 'GuidedLayoutMenuAction';
                }});
                if (button == null) throw 'no tool window button Guided Layout';
                var children = button.getAction().getChildActionsOrStubs();
                var picked = null;
                for (var i = 0; i < children.length; i++) if (children[i].getTemplatePresentation().getText() == {escaped}) picked = children[i];
                if (picked == null) throw 'no Guided Layout entry ' + {escaped};
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    picked, com.intellij.ide.DataManager.getInstance().getDataContext(button),
                    picked.getTemplatePresentation().clone(), 'MarginalisSandbox',
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                picked.setSelected(event, true);
                String(true)
            """)
            return
        except robot.RobotError as error:
            if "no tool window button" not in str(error) or attempt == HEADER_ATTEMPTS - 1:
                raise
            time.sleep(1)


def guided_layout() -> str:
    return js("String(service('dev.marginalis.plugin.ui.toolwindow.GuidedLayoutSetting').getLayout().getStored())")


def guided_shape(section_prefix: str = "Guided") -> list[dict]:
    """The Guided section's direct children: kind, name (folder/file) and step numbers beneath, in tree order."""
    raw = js(_TREE + f"""
        var root = marginalisTree().getModel().getRoot();
        var shape = [];
        for (var i = 0; i < root.getChildCount(); i++) {{
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf({json.dumps(section_prefix)}) != 0) continue;
            for (var j = 0; j < section.getChildCount(); j++) {{
                var child = section.getChildAt(j);
                var data = child.getUserObject();
                var kind = String(data.getClass().getSimpleName());
                var steps = [];
                var nodes = child.preorderEnumeration();
                while (nodes.hasMoreElements()) {{
                    var thread = threadOfNode(nodes.nextElement());
                    if (thread != null) steps.push(thread.getOrder().intValue());
                }}
                var name = (kind == 'DirNode' || kind == 'FileNode') ? String(data.getName()) : null;
                var showsFile = kind == 'ThreadNode' ? data.getShowFile() : null;
                shape.push({{kind: kind, name: name, steps: steps, showsFile: showsFile}});
            }}
            break;
        }}
        String(JSON.stringify(shape))
    """)
    return json.loads(raw)


def move_caret(file: str, line: int) -> None:
    """Puts the caret of the file's open editor on a 1-based line, as a click there would."""
    js(_EDITORS + f"""
        var editor = textEditorOf('{file}');
        editor.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition({line - 1}, 0));
        String(true)
    """)
