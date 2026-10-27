package dev.marginalis.plugin.sandbox

import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.SandboxUi.FIND
import dev.marginalis.plugin.sandbox.SandboxUi.js
import dev.marginalis.plugin.sandbox.SandboxUi.lit
import dev.marginalis.plugin.sandbox.SandboxUi.obj
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * The Marginalis tool window as the user sees it: its header buttons and its tree (a port of the tool window part of
 * `sandbox/sandbox_ui.py`, same JavaScript). Tree steps select nodes as a click would, without editor navigation.
 */
object ToolWindowUi {

    private val HEADER_WAIT: Duration = Duration.ofSeconds(60)
    private const val NO_HEADER_BUTTON = "no tool window button"

    const val TREE = """
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

    private const val HEADER = """
var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis');
// A restart can restore the tool window hidden; its header is built only once it shows.
if (!window.isVisible()) window.show(null);
var root = window.getComponent();
while (root.getParent() != null && root.getClass().getName().indexOf('InternalDecorator') < 0) root = root.getParent();
"""

    // --- header -------------------------------------------------------------------------------------------------

    /**
     * Presses the first of `names` the tool window header shows (the occurrence actions and Expand/Collapse rename
     * themselves). The new UI paints header buttons only on hover, so they are absent from the robot's hierarchy and
     * the button's own action runs instead, with the button's data context.
     *
     * All names are looked up at once: a header still being built is retried, a name the header does not use is not.
     */
    fun pressToolWindowAction(vararg names: String) {
        var token = ""
        retryWhileHeaderIsBuilt("none of ${names.toList()} is in the tool window header") {
            token = js(
                FIND + HEADER + """
                var names = ${lit(names)};
                var button = null;
                for (var n = 0; n < names.length && button == null; n++) {
                    button = find(root, function (c) {
                        return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                            && c.getPresentation().getText() == names[n];
                    });
                }
                if (button == null) throw '$NO_HEADER_BUTTON ' + names.join(' / ');
                var action = button.getAction();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                    'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                String(performLater(action, event))
                """,
            )
        }
        SandboxUi.awaitActionRan(names.first(), token)
    }

    /** Whether the header button `name` is enabled, as its action's own update reports it to the user. */
    fun isToolWindowActionEnabled(name: String): Boolean {
        var enabled = false
        retryWhileHeaderIsBuilt("no $name button in the tool window header") {
            enabled = js(
                FIND + HEADER + """
                var button = find(root, function (c) {
                    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                        && c.getPresentation().getText() == ${lit(name)};
                });
                if (button == null) throw '$NO_HEADER_BUTTON ' + ${lit(name)};
                var action = button.getAction();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                    'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                action.update(event);
                String(event.getPresentation().isEnabled())
                """,
            ) == "true"
        }
        return enabled
    }

    /**
     * Presses the header's "Make every open thread live" toggle once its own update enables it (turning it on needs a
     * listening agent): the toolbar repaints its text asynchronously, so the button is found by its action.
     */
    fun toggleProjectLive() {
        val begun = System.nanoTime()
        var headerAnsweredAfter: Double? = null
        val enabled = readUntil("the project's live toggle is enabled", read = {
            val state = projectLiveToggle(press = false)
            headerAnsweredAfter = headerAnsweredAfter ?: ((System.nanoTime() - begun) / 1e9)
            state == "enabled"
        }) { it }
        check(enabled) {
            "the project's live toggle stayed disabled: no agent is listening" +
                " (the header first answered after %.1f s)".format(Locale.ROOT, headerAnsweredAfter)
        }
        SandboxUi.awaitActionRan("Make every open thread live", projectLiveToggle(press = true))
    }

    fun isProjectLiveToggleEnabled(): Boolean = projectLiveToggle(press = false) == "enabled"

    private fun projectLiveToggle(press: Boolean): String {
        var outcome = ""
        retryWhileHeaderIsBuilt("no live toggle in the tool window header") {
            outcome = js(
                FIND + HEADER + """
                var button = find(root, function (c) {
                    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                        && c.getAction().getClass().getSimpleName() == 'ProjectLiveAction';
                });
                if (button == null) throw '$NO_HEADER_BUTTON ProjectLiveAction';
                var action = button.getAction();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    action, com.intellij.ide.DataManager.getInstance().getDataContext(button), button.getPresentation().clone(),
                    'MarginalisSandbox', com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                action.update(event);
                ${if (press) "String(performLater(action, event))" else "String(event.getPresentation().isEnabled() ? 'enabled' : 'disabled')"}
                """,
            )
        }
        return outcome
    }

    /** Picks an entry of the header's Guided Layout menu through the button's own action group. */
    fun chooseGuidedLayout(label: String) {
        val escaped = lit(label)
        retryWhileHeaderIsBuilt("no Guided Layout button in the tool window header") {
            js(
                FIND + HEADER + """
                var button = find(root, function (c) {
                    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                        && String(c.getAction().getClass().getSimpleName()) == 'GuidedLayoutMenuAction';
                });
                if (button == null) throw '$NO_HEADER_BUTTON Guided Layout';
                var children = button.getAction().getChildActionsOrStubs();
                var picked = null;
                for (var i = 0; i < children.length; i++) if (children[i].getTemplatePresentation().getText() == $escaped) picked = children[i];
                if (picked == null) throw 'no Guided Layout entry ' + $escaped;
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    picked, com.intellij.ide.DataManager.getInstance().getDataContext(button),
                    picked.getTemplatePresentation().clone(), 'MarginalisSandbox',
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
                picked.setSelected(event, true);
                String(true)
                """,
            )
        }
    }

    // Right after a start the header is built a while after the tool window shows; with several sandboxes busy at
    // once that took longer than 15 s. Only a missing header button means "not yet": any other error is the step's.
    private fun retryWhileHeaderIsBuilt(what: String, press: () -> Unit) {
        awaitUntil(what, HEADER_WAIT, Duration.ofSeconds(1), retryOn = { it.message?.contains(NO_HEADER_BUTTON) == true }) {
            press()
            true
        }
    }

    fun guidedLayout(): String = js("String(service('dev.marginalis.plugin.ui.toolwindow.GuidedLayoutSetting').getLayout().getStored())")

    // --- tree ---------------------------------------------------------------------------------------------------

    data class SectionState(val expanded: Boolean, val full: Boolean, val folded: Boolean)

    /**
     * Per section title: whether its node is expanded, whether everything under it is (`full`), and whether nothing
     * below the section node is (`folded`). JTree reports a node under a collapsed parent as collapsed whatever its
     * own state, so check `folded` with the section itself open ([openSectionOnly]).
     */
    fun sectionsState(): Map<String, SectionState> {
        val raw = obj(
            js(
                TREE + """
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
                """,
            ),
        )
        return raw.keySet().associateWith { title ->
            raw.getAsJsonObject(title).let { SectionState(it.get("expanded").asBoolean, it.get("full").asBoolean, it.get("folded").asBoolean) }
        }
    }

    /** Opens just the section node, as a click on its arrow would. */
    fun openSectionOnly(sectionPrefix: String) {
        js(
            TREE + """
            var tree = marginalisTree();
            var root = tree.getModel().getRoot();
            for (var i = 0; i < root.getChildCount(); i++) {
                var section = root.getChildAt(i);
                if (sectionTitle(section).indexOf(${lit(sectionPrefix)}) == 0) {
                    tree.expandPath(new javax.swing.tree.TreePath(section.getPath()));
                    break;
                }
            }
            String(true)
            """,
        )
    }

    private fun dirNode(sectionPrefix: String, dirName: String) = TREE + """
        var tree = marginalisTree();
        var root = tree.getModel().getRoot();
        var dir = null;
        for (var i = 0; i < root.getChildCount() && dir == null; i++) {
            var section = root.getChildAt(i);
            if (sectionTitle(section).indexOf(${lit(sectionPrefix)}) != 0) continue;
            var nodes = section.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var node = nodes.nextElement();
                var data = node.getUserObject();
                if (data.getClass().getSimpleName() == 'DirNode' && String(data.getName()) == ${lit(dirName)}) { dir = node; break; }
            }
        }
        if (dir == null) throw 'no folder ' + ${lit(dirName)};
        var dirPath = new javax.swing.tree.TreePath(dir.getPath());
        """

    /** Folds a folder node as a click on its arrow would. */
    fun foldDir(sectionPrefix: String, dirName: String) {
        js(dirNode(sectionPrefix, dirName) + "tree.collapsePath(dirPath); String(true)")
    }

    fun dirExpanded(sectionPrefix: String, dirName: String): Boolean =
        js(dirNode(sectionPrefix, dirName) + "String(tree.isExpanded(dirPath))") == "true"

    /** Selects the section node, as a click on its title would. */
    fun selectSection(sectionPrefix: String) {
        js(
            TREE + """
            var tree = marginalisTree();
            var root = tree.getModel().getRoot();
            var found = null;
            for (var i = 0; i < root.getChildCount() && found == null; i++) {
                if (sectionTitle(root.getChildAt(i)).indexOf(${lit(sectionPrefix)}) == 0) found = root.getChildAt(i);
            }
            if (found == null) throw 'no section starting with ' + ${lit(sectionPrefix)};
            tree.setSelectionPath(new javax.swing.tree.TreePath(found.getPath()));
            String(true)
            """,
        )
    }

    fun selectDir(sectionPrefix: String, dirName: String) {
        js(dirNode(sectionPrefix, dirName) + "tree.setSelectionPath(dirPath); String(true)")
    }

    /**
     * Reports a change on some thread, as an agent reply or a step marked read would. The tool window rebuilds its
     * tree on any thread change, so this makes that rebuild happen on cue instead of depending on focus.
     */
    fun pokeAThread() {
        js(
            """
            var threads = store.getThreads().all();
            store.getThreads().notifyChanged(threads.get(threads.size() - 1));
            String(true)
            """,
        )
    }

    /** Step numbers of the thread nodes of the first section whose title starts with `sectionPrefix`, in tree order. */
    fun treeSteps(sectionPrefix: String = "Guided"): List<Int> = intList(
        js(
            TREE + """
            var root = marginalisTree().getModel().getRoot();
            var orders = [];
            for (var i = 0; i < root.getChildCount(); i++) {
                var section = root.getChildAt(i);
                if (sectionTitle(section).indexOf(${lit(sectionPrefix)}) != 0) continue;
                var nodes = section.preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    var thread = threadOfNode(nodes.nextElement());
                    if (thread != null) orders.push(thread.getOrder() == null ? -1 : thread.getOrder().intValue());
                }
                break;
            }
            String(JSON.stringify(orders))
            """,
        ),
    )

    /** Per Guided section title: the prefix each step shows in the tree, e.g. `(1/5)`, in tree order. */
    /** The ids of every thread the tool window's tree holds, folded sections included, each once. */
    fun treeThreadIds(): Set<String> =
        JsonParser.parseString(
            js(
                TREE + """
                var nodes = marginalisTree().getModel().getRoot().preorderEnumeration();
                var ids = {};
                while (nodes.hasMoreElements()) {
                    var thread = threadOfNode(nodes.nextElement());
                    if (thread != null) ids[String(thread.getId())] = true;
                }
                String(JSON.stringify(Object.keys(ids)))
                """,
            ),
        ).asJsonArray.map { it.asString }.toSet()

    fun stepPrefixes(): Map<String, List<String>> {
        val raw = obj(
            js(
                TREE + """
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
                """,
            ),
        )
        return raw.keySet().associateWith { title -> raw.getAsJsonArray(title).map { it.asString } }
    }

    data class SelectedStep(val order: Int?, val thread: String?, val section: String)

    /** The selected tree node: its step number and thread (when it is a thread) and its section title. */
    fun selectedStep(): SelectedStep? {
        val raw = JsonParser.parseString(
            js(
                TREE + """
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
                """,
            ),
        )
        if (raw.isJsonNull) return null
        val selected = raw.asJsonObject
        return SelectedStep(
            order = selected.get("order")?.takeUnless { it.isJsonNull }?.asInt,
            thread = selected.get("thread")?.takeUnless { it.isJsonNull }?.asString,
            section = selected.get("section").asString,
        )
    }

    /** The selected node as shown: a folder's or file's name, a section's title, `project`, or `step N`. */
    fun selectedNodeName(): String? {
        val raw = JsonParser.parseString(
            js(
                TREE + """
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
                """,
            ),
        )
        return if (raw.isJsonNull) null else raw.asString
    }

    /** Selects the step's node in the tree, as a click on it would (no editor navigation). */
    fun selectStep(order: Int, sectionPrefix: String = "Guided") {
        js(
            TREE + """
            var tree = marginalisTree();
            var root = tree.getModel().getRoot();
            var found = null;
            for (var i = 0; i < root.getChildCount() && found == null; i++) {
                var section = root.getChildAt(i);
                if (sectionTitle(section).indexOf(${lit(sectionPrefix)}) != 0) continue;
                var nodes = section.preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    var node = nodes.nextElement();
                    var thread = threadOfNode(node);
                    if (thread != null && thread.getOrder() != null && thread.getOrder().intValue() == $order) { found = node; break; }
                }
            }
            if (found == null) throw 'no step $order in a section starting with ' + ${lit(sectionPrefix)};
            var path = new javax.swing.tree.TreePath(found.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            String(true)
            """,
        )
    }

    data class ShapeNode(val kind: String, val name: String?, val steps: List<Int>, val showsFile: Boolean?)

    /** The Guided section's direct children: kind, name (folder/file) and step numbers beneath, in tree order. */
    fun guidedShape(sectionPrefix: String = "Guided"): List<ShapeNode> =
        JsonParser.parseString(
            js(
                TREE + """
                var root = marginalisTree().getModel().getRoot();
                var shape = [];
                for (var i = 0; i < root.getChildCount(); i++) {
                    var section = root.getChildAt(i);
                    if (sectionTitle(section).indexOf(${lit(sectionPrefix)}) != 0) continue;
                    for (var j = 0; j < section.getChildCount(); j++) {
                        var child = section.getChildAt(j);
                        var data = child.getUserObject();
                        var kind = String(data.getClass().getSimpleName());
                        var steps = [];
                        var nodes = child.preorderEnumeration();
                        while (nodes.hasMoreElements()) {
                            var thread = threadOfNode(nodes.nextElement());
                            if (thread != null) steps.push(thread.getOrder().intValue());
                        }
                        var name = (kind == 'DirNode' || kind == 'FileNode') ? String(data.getName()) : null;
                        var showsFile = kind == 'ThreadNode' ? data.getShowFile() : null;
                        shape.push({kind: kind, name: name, steps: steps, showsFile: showsFile});
                    }
                    break;
                }
                String(JSON.stringify(shape))
                """,
            ),
        ).asJsonArray.map { element ->
            val node = element.asJsonObject
            ShapeNode(
                kind = node.get("kind").asString,
                name = node.get("name")?.takeUnless { it.isJsonNull }?.asString,
                steps = node.getAsJsonArray("steps").map { it.asInt },
                showsFile = node.get("showsFile")?.takeUnless { it.isJsonNull }?.asBoolean,
            )
        }

    fun screenshotToolWindow(target: Path) {
        SandboxUi.showToolWindow()
        val xpath = "//div[@class='MarginalisToolWindowPanel' or @javaclass='dev.marginalis.plugin.ui.toolwindow.MarginalisToolWindowPanel']"
        // The robot's hierarchy misses the panel for a moment while the tool window relays out; on the floor IDE in a
        // container that moment ran past 5 s. Longer, the tool window is off screen, which the user would notice.
        val panel = awaitValue("one Marginalis tool window panel on screen", Duration.ofSeconds(20), Duration.ofSeconds(1)) {
            Sandbox.robot.findAll(xpath).singleOrNull()
        }
        Sandbox.robot.screenshot(target, panel)
    }

    private fun intList(raw: String): List<Int> = JsonParser.parseString(raw).asJsonArray.map { it.asInt }
}
