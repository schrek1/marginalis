package dev.marginalis.plugin.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.remoterobot.fixtures.ComponentFixture
import java.nio.file.Path

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
"""

    const val EDITORS = """
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

    fun showToolWindow() {
        js(
            """
            var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
            window.show();
            if (window.getComponent().getWidth() < 300) window.stretchWidth(450 - window.getComponent().getWidth());
            String(true)
            """,
        )
    }

    fun threadPanel(threadId: String): ComponentFixture =
        Sandbox.robot.findAll("//div[@javaclass='dev.marginalis.plugin.ui.ThreadPanel']")
            .firstOrNull { panel -> js("String(threadOf(component).getId())", panel) == threadId }
            ?: error("no panel shows thread $threadId")

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
        js(
            FIND + """
            var button = find(component, function (c) {
                return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                    && c.getPresentation().getText() == $escaped;
            });
            if (button == null) throw 'no panel button ' + $escaped;
            var action = button.getAction();
            action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null));
            String(true)
            """,
            threadPanel(threadId),
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
        Sandbox.robot.screenshot(target, Sandbox.robot.findOne("//div[@class='IdeFrameImpl']"))
    }

    // --- Margin (project) tab -----------------------------------------------------------------------------------

    fun openMarginTab(threadId: String) {
        js(MARGIN + "projectTab().reveal(project, store.getThreads().byId('$threadId'), null); String(true)")
    }

    /** The "+ New project thread" link: opens an empty draft in the Margin tab. */
    fun userStartsNewProjectThread() {
        js(MARGIN + "projectTab().draftNew(project); String(true)")
    }

    /** What the Margin tab lists, top to bottom: thread ids, `conversation`, `draft`, or a component kind. */
    fun marginTabOrder(): List<String> = stringList(
        js(
            MARGIN + """
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
        js(
            MARGIN + FIND + """
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

    fun marginSort(): String = js(MARGIN + "String(service('dev.marginalis.plugin.ui.tab.ProjectTabSort').getOrder().getStored())")

    /** Forced save of the project's workspace.xml, off the EDT: non-forced saves skip recently loaded components. */
    fun saveProjectState() {
        Sandbox.robot.callJs(PRELUDE + "com.intellij.configurationStore.StoreUtil.saveSettings(project, true); String(true)", edt = false)
    }

    // --- decoding -----------------------------------------------------------------------------------------------

    fun obj(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    fun stringList(raw: String): List<String> = JsonParser.parseString(raw).asJsonArray.map { it.asString }
}
