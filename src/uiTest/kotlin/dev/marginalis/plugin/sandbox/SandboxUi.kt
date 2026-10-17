package dev.marginalis.plugin.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.remoterobot.fixtures.ComponentFixture
import java.nio.file.Path
import java.time.Duration

/**
 * The user's side of a sandbox scenario, through the real Swing components (a port of `sandbox/sandbox_ui.py`, same
 * JavaScript). Nothing moves the OS mouse or keyboard: buttons are pressed with `doClick()` or their action's
 * `actionPerformed`, text is set on the component, all on the EDT. Reads that have no UI (store state) go through
 * the plugin's own classes, loaded with the plugin's class loader.
 */
object SandboxUi {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** `value` as a JavaScript literal, as `json.dumps` writes it into the Python harness's scripts. */
    fun lit(value: Any): String = gson.toJson(value)

    private const val PRELUDE = """
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
// The robot's EDT scripts hold no lock, and 2025.2 (the floor) then refuses model and caret reads, and caret moves.
// Take the lock around just those calls: around a whole script, opening an editor waits on background reads it holds
// back. An int computed in `read` comes back as a JS double, so callers take `| 0`.
function read(compute) {
    return com.intellij.openapi.application.ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: compute }));
}
function asUser(run) {
    return com.intellij.openapi.application.WriteIntentReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: run }));
}
// An action runs as its own EDT event, with the locks a click gives it. A robot script is a raw EDT event of its own
// and can overtake the application's invokeLater queue on a busy IDE, so the action records under the returned token
// that it ran, for [SandboxUi.awaitActionRan].
function performLater(action, event) {
    var token = String(java.util.UUID.randomUUID());
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
        new java.lang.Runnable({ run: function () {
            var outcome = 'ok';
            try { action.actionPerformed(event); } catch (error) { outcome = 'failed: ' + error; }
            java.lang.System.getProperties().put('marginalis.sandbox.action.' + token, outcome);
        } }));
    return token;
}
"""

    const val EDITORS = """
function projectFile(path) { return project.getBaseDir().findFileByRelativePath(path); }
function textEditorOf(path) {
    var document = read(function () { return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(projectFile(path)); });
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

    const val FIND = """
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

    const val MARGIN = """
function marginTabOpen() {
    var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
    return com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getAllEditors(marginFile).length > 0;
}
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

    fun js(script: String, component: ComponentFixture? = null): String =
        Sandbox.robot.callJs(PRELUDE + script, edt = true, component = component)

    // --- tool window and editors --------------------------------------------------------------------------------

    /**
     * Shows the Marginalis tool window and returns once it has been on screen for a second. On a busy machine the IDE
     * restores its own tool window layout late and puts the Project view over it; it is then shown again, as a user
     * would click it again.
     */
    fun showToolWindow() {
        var shownSince: Long? = null
        awaitUntil("the Marginalis tool window on screen for a second", Duration.ofSeconds(60), Duration.ofMillis(250)) {
            val shown = js(
                """
                var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
                if (!window.isVisible()) window.show();
                var component = window.getComponent();
                if (window.isVisible() && component.isShowing() && component.getWidth() < 300) window.stretchWidth(450 - component.getWidth());
                String(window.isVisible() && component.isShowing())
                """,
            ) == "true"
            if (!shown) shownSince = null else if (shownSince == null) shownSince = System.nanoTime()
            shownSince?.let { since -> System.nanoTime() - since >= Duration.ofSeconds(1).toNanos() } ?: false
        }
    }

    fun threadPanel(threadId: String): ComponentFixture =
        awaitValue("a panel on screen shows thread $threadId") {
            Sandbox.robot.findAll("//div[@javaclass='dev.marginalis.plugin.ui.ThreadPanel']")
                .firstOrNull { panel -> js("String(threadOf(component).getId())", panel) == threadId }
        }

    fun openInEditor(file: String) {
        js(EDITORS + "com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(projectFile('$file'), true); String(true)")
    }

    fun closeAllEditors() {
        js(
            """
            var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
            manager.closeAllFiles();
            String(true)
            """,
        )
    }

    fun openThreadInDiff(threadId: String, after: String) {
        js(EDITORS + "inlays().open(project, diffEditorShowing(${lit(after)}), threadById('$threadId'), null); String(true)")
    }

    fun openThreadInEditor(threadId: String, file: String) {
        js(EDITORS + "inlays().open(project, textEditorOf('$file'), threadById('$threadId'), null); String(true)")
    }

    /** What the editor area shows in front: `DIFF` for a diff tab, `TEXT:<file>` for a file's own tab. */
    fun selectedEditorKind(): String = js(
        """
        var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
        if (selected == null) String('NONE');
        else if (selected instanceof com.intellij.openapi.fileEditor.TextEditor) String('TEXT:' + selected.getFile().getName());
        else String(selected.getClass().getSimpleName().indexOf('Diff') >= 0 ? 'DIFF' : selected.getClass().getSimpleName())
        """,
    )

    /** Makes files written from outside visible to the IDE; a synchronous refresh on the EDT would need a write-intent lock. */
    fun refreshProjectFiles() {
        Sandbox.pushProject()
        Sandbox.robot.callJs(
            PRELUDE + """
            com.intellij.openapi.vfs.VfsUtil['markDirtyAndRefresh(boolean,boolean,boolean,com.intellij.openapi.vfs.VirtualFile[])'](
                false, true, true, [project.getBaseDir()]);
            String(true)
            """,
            edt = false,
        )
    }

    /** Puts the caret of the file's open editor on a 1-based line, as a click there would. */
    fun moveCaret(file: String, line: Int) {
        js(
            EDITORS + """
            var editor = textEditorOf('$file');
            asUser(function () { editor.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition(${line - 1}, 0)); });
            String(true)
            """,
        )
    }

    /**
     * A diff shaped like a PR review: the right side is a copy of `after` that only names `file` as its highlight file.
     * `added` shows a file the change adds (no left side, a one-sided viewer).
     */
    fun openReviewDiff(file: String, before: String, after: String, added: Boolean = false) {
        val left = if (added) "factory.createEmpty()" else "factory.create(project, ${lit(before)}, projectFile(${lit(file)}))"
        js(
            EDITORS + """
            var factory = com.intellij.diff.DiffContentFactory.getInstance();
            var left = $left;
            var right = factory.create(project, ${lit(after)}, projectFile(${lit(file)}));
            var request = new com.intellij.diff.requests.SimpleDiffRequest('Sandbox review diff', left, right, 'base', 'PR');
            com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
            String(true)
            """,
        )
    }

    /** A diff whose right side is the file's own document (a checked-out branch), against `other` on the left. */
    fun openLocalDiff(file: String, other: String) {
        js(
            EDITORS + """
            var factory = com.intellij.diff.DiffContentFactory.getInstance();
            var left = factory.create(project, ${lit(other)}, projectFile(${lit(file)}));
            var right = factory.create(project, projectFile(${lit(file)}));
            var request = new com.intellij.diff.requests.SimpleDiffRequest('Sandbox local diff', left, right, 'other', 'local');
            com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
            String(true)
            """,
        )
    }

    /** 0-based lines of the thread gutter icons in the diff editor showing `after`. */
    fun diffIconLines(after: String): List<Int> =
        JsonParser.parseString(
            js(
                EDITORS + """
                var editor = diffEditorShowing(${lit(after)});
                if (editor == null) throw 'no diff editor shows that text';
                var lines = [];
                var highlighters = editor.getMarkupModel().getAllHighlighters();
                for (var i = 0; i < highlighters.length; i++) {
                    var renderer = highlighters[i].getGutterIconRenderer();
                    if (renderer != null && renderer.getClass().getSimpleName() == 'ThreadGutterIconRenderer') {
                        var offset = highlighters[i].getStartOffset();
                        lines.push(read(function () { return editor.getDocument().getLineNumber(offset); }) | 0);
                    }
                }
                lines.sort(function (a, b) { return a - b; });
                String(JSON.stringify(lines))
                """,
            ),
        ).asJsonArray.map { it.asInt }

    /** Puts 0-based `topLine` at the top of the file's editor, without animation. */
    fun scrollEditorTo(file: String, topLine: Int) {
        js(
            EDITORS + """
            var editor = textEditorOf('$file');
            var scrolling = editor.getScrollingModel();
            scrolling.disableAnimation();
            scrolling.scrollVertically(editor.logicalPositionToXY(new com.intellij.openapi.editor.LogicalPosition($topLine, 0)).y);
            scrolling.enableAnimation();
            String(true)
            """,
        )
    }

    /** 0-based line shown at (x, y) of the editor's viewport. */
    fun lineUnderPoint(file: String, x: Int, y: Int): Int = js(
        EDITORS + """
        var editor = textEditorOf('$file');
        var area = editor.getScrollingModel().getVisibleArea();
        String(editor.xyToLogicalPosition(new java.awt.Point(area.x + $x, area.y + $y)).line)
        """,
    ).toInt()

    /** Ctrl+mouse wheel over (x, y) of the editor's viewport, as one event dispatched in the IDE (negative = zoom in). */
    fun ctrlWheelZoom(file: String, x: Int, y: Int, notches: Int = -2) {
        js(
            EDITORS + """
            com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance().setWheelFontChangeEnabled(true);
            var pane = textEditorOf('$file').getScrollPane();
            var point = javax.swing.SwingUtilities.convertPoint(pane.getViewport(), $x, $y, pane);
            pane.dispatchEvent(new java.awt.event.MouseWheelEvent(pane, java.awt.event.MouseEvent.MOUSE_WHEEL,
                java.lang.System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK, point.x, point.y, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, $notches));
            String(true)
            """,
        )
    }

    fun editorFontSize(file: String): Float = js(EDITORS + "String(textEditorOf('$file').getColorsScheme().getEditorFontSize2D())").toFloat()

    fun threadIdsOpenInDiff(after: String): List<String> = stringList(
        js(
            EDITORS + FIND + """
            var editor = diffEditorShowing(${lit(after)});
            var ids = [];
            function collect(c) {
                if (c.getClass().getName() == 'dev.marginalis.plugin.ui.ThreadPanel') ids.push(String(threadOf(c).getId()));
                var children = c.getComponents ? c.getComponents() : [];
                for (var i = 0; i < children.length; i++) collect(children[i]);
            }
            collect(editor.getContentComponent());
            String(JSON.stringify(ids))
            """,
        ),
    )

    // --- an open thread panel -----------------------------------------------------------------------------------

    /** Runs a button of the thread header (e.g. "Next Step") with that button's own data context. */
    fun pressPanelAction(threadId: String, text: String) {
        val escaped = lit(text)
        val token = js(
            FIND + """
            var button = find(component, function (c) {
                return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                    && c.getPresentation().getText() == $escaped;
            });
            if (button == null) throw 'no panel button ' + $escaped;
            var action = button.getAction();
            var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
            String(performLater(action, event))
            """,
            threadPanel(threadId),
        )
        awaitActionRan(text, token)
    }

    /** Waits until the action [performLater] queued under `token` has run; an action that threw fails the step. */
    fun awaitActionRan(name: String, token: String) {
        val key = "'marginalis.sandbox.action.$token'"
        val outcome = awaitValue("the $name action ran", Duration.ofSeconds(30)) {
            js("var outcome = java.lang.System.getProperties().remove($key); String(outcome == null ? '' : outcome)")
                .takeIf { it.isNotEmpty() }
        }
        check(outcome == "ok") { "the $name action $outcome" }
    }

    /** Presses the thread header's own ⚡ ("Live: …" when on, "Go Live: …" when off) with its Swing `click()`. */
    fun toggleThreadLive(threadId: String) {
        js(
            FIND + """
            var button = find(component, function (c) {
                if (!(c instanceof com.intellij.openapi.actionSystem.impl.ActionButton)) return false;
                var text = c.getPresentation().getText();
                return text != null && (text.startsWith('Live:') || text.startsWith('Go Live:'));
            });
            button.click();
            String(true)
            """,
            threadPanel(threadId),
        )
    }

    fun liveByDefault(): Boolean = js("String(store.getLiveByDefault())") == "true"

    fun isLive(threadId: String): Boolean = js("String(store.isLive(store.getThreads().byId('$threadId')))") == "true"

    /**
     * Marks the whole thread read, as its panel does once on screen. The panel only marks read while the IDE is the
     * active app, which the sandbox window on another desktop is not.
     */
    fun userReads(threadId: String) {
        js(
            """
            var thread = store.getThreads().byId('$threadId');
            thread.markReadByUser(thread.getMessages());
            store.getThreads().notifyChanged(thread);
            String(true)
            """,
        )
    }

    data class MessagePane(
        val table: Boolean,
        val strike: Boolean,
        val width: Int,
        val viewportWidth: Int,
        val horizontalPolicy: Int,
        val horizontalBarVisible: Boolean,
    )

    /** Every rendered message pane in the thread's panel: what its HTML holds and how it sits in a scroll pane. */
    fun messagePanes(threadId: String): List<MessagePane> =
        JsonParser.parseString(
            js(
                """
                var panes = [];
                function collect(c) {
                    if (c instanceof javax.swing.JEditorPane) {
                        var html = String(c.getText());
                        var viewport = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport, c);
                        var scroll = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane, c);
                        panes.push({
                            table: html.indexOf('<table') >= 0,
                            strike: html.indexOf('<s>') >= 0,
                            width: c.getWidth(),
                            viewportWidth: viewport == null ? -1 : viewport.getExtentSize().width,
                            horizontalPolicy: scroll == null ? -1 : scroll.getHorizontalScrollBarPolicy(),
                            horizontalBarVisible: scroll != null && scroll.getHorizontalScrollBar().isVisible()
                        });
                    }
                    var children = c.getComponents ? c.getComponents() : [];
                    for (var i = 0; i < children.length; i++) collect(children[i]);
                }
                collect(component);
                String(JSON.stringify(panes))
                """,
                threadPanel(threadId),
            ),
        ).asJsonArray.map { element ->
            val pane = element.asJsonObject
            MessagePane(
                table = pane.get("table").asBoolean,
                strike = pane.get("strike").asBoolean,
                width = pane.get("width").asInt,
                viewportWidth = pane.get("viewportWidth").asInt,
                horizontalPolicy = pane.get("horizontalPolicy").asInt,
                horizontalBarVisible = pane.get("horizontalBarVisible").asBoolean,
            )
        }

    /** Opens the composer with the real "Reply…" link, types the body into it and presses the send button. */
    fun userReplies(threadId: String, body: String) {
        js(
            FIND + """
            var link = find(component, function (c) {
                return c instanceof com.intellij.ui.components.ActionLink && c.getText() == 'Reply…';
            });
            link.doClick();
            var area = find(component, function (c) { return c instanceof com.intellij.ui.EditorTextField; });
            area.setText(${lit(body)});
            var send = find(component, function (c) { return c instanceof com.intellij.ui.components.JBOptionButton; });
            send.doClick();
            String(true)
            """,
            threadPanel(threadId),
        )
    }

    data class PanelView(val texts: List<String>, val height: Int, val preferredHeight: Int, val valid: Boolean, val showing: Boolean)

    /**
     * What the thread's open panel shows: the plain text of each rendered message, and whether its bounds keep up
     * with what it would need (an inlay that does not grow hides the last message below its edge).
     */
    fun panelView(threadId: String): PanelView {
        val view = obj(
            js(
                """
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
                """,
                threadPanel(threadId),
            ),
        )
        return PanelView(
            texts = view.getAsJsonArray("texts").map { it.asString },
            height = view.get("height").asInt,
            preferredHeight = view.get("preferredHeight").asInt,
            valid = view.get("valid").asBoolean,
            showing = view.get("showing").asBoolean,
        )
    }

    data class WordBox(val top: Double, val bottom: Double, val paneTop: Double)

    data class WordBoxes(val boxes: Map<String, WordBox>, val html: String?) {
        operator fun get(word: String): WordBox = boxes[word] ?: error("$word is not laid out in the thread: $this")
    }

    /**
     * Where each word's first letter is laid out in its rendered message pane, in pane pixels (`paneTop` = the
     * pane's top inset), plus `html` of the pane holding the first word.
     */
    fun wordBoxes(threadId: String, words: List<String>): WordBoxes {
        val raw = obj(
            js(
                """
                var words = ${lit(words)};
                var result = {};
                function collect(c) {
                    if (c instanceof javax.swing.JEditorPane) {
                        var document = c.getDocument();
                        var text = String(document.getText(0, document.getLength()));
                        for (var i = 0; i < words.length; i++) {
                            var at = text.indexOf(words[i]);
                            if (at < 0 || result[words[i]]) continue;
                            var box = c.modelToView2D(at);
                            result[words[i]] = { top: box.getY(), bottom: box.getY() + box.getHeight(), paneTop: c.getInsets().top };
                            if (i == 0) result.html = String(c.getText());
                        }
                    }
                    var children = c.getComponents ? c.getComponents() : [];
                    for (var j = 0; j < children.length; j++) collect(children[j]);
                }
                collect(component);
                String(JSON.stringify(result))
                """,
                threadPanel(threadId),
            ),
        )
        val boxes = words.filter { raw.has(it) }.associateWith { word ->
            raw.getAsJsonObject(word).let { WordBox(it.get("top").asDouble, it.get("bottom").asDouble, it.get("paneTop").asDouble) }
        }
        return WordBoxes(boxes, raw.get("html")?.asString)
    }

    fun screenshotThread(threadId: String, target: Path) {
        Sandbox.robot.screenshot(target, threadPanel(threadId))
    }

    fun screenshotFrame(target: Path) {
        Sandbox.robot.screenshot(target, awaitValue("the IDE frame on screen") { Sandbox.robot.findAll("//div[@class='IdeFrameImpl']").singleOrNull() })
    }

    // --- Margin (project) tab -----------------------------------------------------------------------------------

    fun openMarginTab(threadId: String) {
        // A project thread the agent just posted opens the tab behind the file in front: the tab is selected before its
        // editor exists, then the file comes back to the front. A reveal in that window loses to the file; it lasts a
        // moment on a desktop and seconds on a CI runner (the plugin side is MARG-96), so wait it out.
        awaitUntil("the Margin tab done opening behind the file in front", Duration.ofSeconds(30)) {
            val state = obj(js(MARGIN + EDITOR_STATE))
            val opening = state.get("selected").asString == "Margin" && !state.get("marginOpen").asBoolean
            !opening && !state.get("openBehindQueued").asBoolean
        }
        settleEditorSelection()
        println("INFO  before the reveal: ${js(MARGIN + EDITOR_STATE)}")
        js(MARGIN + "projectTab().reveal(project, store.getThreads().byId('$threadId'), null); String(true)")
        awaitUntil("the Margin tab in front after the reveal") {
            obj(js(MARGIN + EDITOR_STATE)).get("selected").asString == "Margin"
        }
    }

    private const val EDITOR_STATE = """
        var editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
        var files = editors.getSelectedFiles();
        var tabState = service('dev.marginalis.plugin.ui.tab.ProjectTabState');
        String(JSON.stringify({ selected: files.length ? String(files[0].getName()) : '', marginOpen: marginTabOpen(),
                                openBehindQueued: fieldOf(tabState, 'openBehindQueued').get() }))
        """

    /** Waits until the editor in front has not changed for `quiet`, at most `limit`. */
    fun settleEditorSelection(quiet: Duration = Duration.ofMillis(500), limit: Duration = Duration.ofSeconds(5)) {
        fun selected() = js(
            """
            var files = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedFiles();
            String(files.length ? files[0].getUrl() : '')
            """,
        )
        var last = selected()
        var since = System.nanoTime()
        awaitUntil("the editor in front stays the same for ${quiet.toMillis()} ms", limit, Duration.ofMillis(100)) {
            val now = selected()
            if (now != last) {
                last = now
                since = System.nanoTime()
            }
            System.nanoTime() - since >= quiet.toNanos()
        }
    }

    /** Runs a script that uses [MARGIN]'s helpers once the Margin tab is open. */
    fun marginJs(script: String): String {
        awaitUntil("the Margin tab is open") { js(MARGIN + "String(marginTabOpen())") == "true" }
        return js(MARGIN + script)
    }

    /** The "+ New project thread" link: opens an empty draft in the Margin tab. */
    fun userStartsNewProjectThread() {
        js(MARGIN + "projectTab().draftNew(project); String(true)")
    }

    /** What the Margin tab lists, top to bottom: thread ids, `conversation`, `draft`, or a component kind. */
    fun marginTabOrder(): List<String> = stringList(
        marginJs(
            """
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
            """,
        ),
    )

    /** Picks an entry of the Margin tab's Sort menu, through the toolbar button's own action group. */
    fun chooseMarginSort(label: String) {
        val escaped = lit(label)
        marginJs(
            FIND + """
            var root = fieldOf(marginPanel(), 'root');
            var button = find(root, function (c) {
                return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                    && String(c.getAction().getClass().getSimpleName()) == 'ProjectTabSortMenu';
            });
            if (button == null) throw 'no Sort button in the Margin tab';
            var group = button.getAction();
            var context = com.intellij.ide.DataManager.getInstance().getDataContext(button);
            var children = group.getChildActionsOrStubs();
            var picked = null;
            for (var i = 0; i < children.length; i++) {
                if (children[i].getTemplatePresentation().getText() == $escaped) picked = children[i];
            }
            if (picked == null) throw 'no Sort entry ' + $escaped;
            var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                picked, context, picked.getTemplatePresentation().clone(),
                'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
            picked.setSelected(event, true);
            String(true)
            """,
        )
    }

    fun marginSort(): String = js("String(service('dev.marginalis.plugin.ui.tab.ProjectTabSort').getOrder().getStored())")

    /** Forced save of the project's workspace.xml, off the EDT: non-forced saves skip recently loaded components. */
    fun saveProjectState() {
        Sandbox.robot.callJs(PRELUDE + "com.intellij.configurationStore.StoreUtil.saveSettings(project, true); String(true)", edt = false)
    }

    // --- decoding -----------------------------------------------------------------------------------------------

    fun obj(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    fun stringList(raw: String): List<String> = JsonParser.parseString(raw).asJsonArray.map { it.asString }
}
